package dev.che1s.betaachievement.database;

import dev.che1s.betaachievement.BetaAchievement;
import dev.che1s.betaachievement.run.AchievementRun;
import dev.che1s.betaachievement.run.RunLeaderboardEntry;
import dev.che1s.betaachievement.run.RunProgressEntry;
import dev.che1s.betaachievement.run.RunSnapshot;
import dev.che1s.betaachievement.run.RunStatus;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Owns all JDBC work. A single executor serializes reads and writes so an
 * insert cannot overtake a later reset or revocation.
 */
public final class DatabaseManager {
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 10L;
    private static final int RULESET_VERSION = 1;

    private final BetaAchievement plugin;
    private final BlockingQueue<Connection> idle = new LinkedBlockingQueue<Connection>();
    private final Set<Connection> allConnections = Collections.newSetFromMap(
        new ConcurrentHashMap<Connection, Boolean>()
    );
    private final ExecutorService executor = Executors.newSingleThreadExecutor(new ThreadFactory() {
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "BetaAchievement-Database");
            thread.setDaemon(true);
            return thread;
        }
    });

    private String url;
    private String user;
    private String password;
    private String prefix;
    private boolean sqlite;
    private volatile boolean enabled;
    private volatile boolean ready;
    private volatile boolean stopping;

    public DatabaseManager(BetaAchievement plugin) {
        this.plugin = plugin;
    }

    public void startAsync(final Runnable onSuccess, final Runnable onFailure) {
        submit(new Runnable() {
            public void run() {
                final boolean started = start();
                scheduleOnMain(new Runnable() {
                    public void run() {
                        if (!plugin.isEnabled()) {
                            return;
                        }
                        if (started) {
                            onSuccess.run();
                        } else {
                            onFailure.run();
                        }
                    }
                });
            }
        });
    }

    private boolean start() {
        ready = false;
        enabled = false;
        if (!plugin.getConfiguration().getBoolean("database.enabled", true)) {
            ready = true;
            plugin.getLanguage().warning(
                "database.disabled",
                "Database disabled: achievements will not be authoritative or persistent."
            );
            return true;
        }

        try {
            String type = plugin.getConfiguration().getString("database.type", "sqlite");
            sqlite = "sqlite".equalsIgnoreCase(type);
            if (!sqlite && !"mysql".equalsIgnoreCase(type)) {
                throw new IllegalArgumentException(plugin.getLanguage().consoleValue(
                    "database.invalid-type",
                    "database.type must be sqlite or mysql"
                ));
            }

            prefix = plugin.getConfiguration().getString("database.table-prefix", "beta_achievements_");
            if (!prefix.matches("[A-Za-z0-9_]{1,40}")) {
                throw new IllegalArgumentException(plugin.getLanguage().consoleValue(
                    "database.invalid-prefix",
                    "database.table-prefix must contain 1-40 letters, digits or underscores"
                ));
            }

            if (sqlite) {
                File file = new File(
                    plugin.getDataFolder(),
                    plugin.getConfiguration().getString("database.file", "achievements.db")
                );
                url = "jdbc:sqlite:" + file.getAbsolutePath();
                user = "";
                password = "";
            } else {
                url = "jdbc:mysql://"
                    + plugin.getConfiguration().getString("database.host", "127.0.0.1")
                    + ":" + plugin.getConfiguration().getInt("database.port", 3306)
                    + "/" + plugin.getConfiguration().getString("database.database", "minecraft")
                    + "?useUnicode=true&characterEncoding=utf8"
                    + "&connectTimeout=5000&socketTimeout=10000";
                user = plugin.getConfiguration().getString("database.username", "");
                password = plugin.getConfiguration().getString("database.password", "");
            }

            Class.forName(sqlite ? "org.sqlite.JDBC" : "com.mysql.jdbc.Driver");
            idle.add(open());
            schema();
            enabled = true;
            ready = true;
            return true;
        } catch (Exception exception) {
            closeIdleConnections();
            plugin.getLanguage().severe(
                "database.start-failed",
                "Cannot start {type}: {error}",
                "type",
                sqlite ? "SQLite" : "MySQL",
                "error",
                exception.getMessage()
            );
            return false;
        }
    }

    public boolean runAsync(Runnable task) {
        return submit(task);
    }

    private boolean submit(Runnable task) {
        if (stopping || executor.isShutdown()) {
            plugin.getLanguage().warning(
                "database.task-rejected",
                "Database task rejected: storage is stopping."
            );
            return false;
        }
        try {
            executor.execute(task);
            return true;
        } catch (RejectedExecutionException exception) {
            plugin.getLanguage().warning(
                "database.task-rejected",
                "Database task rejected: storage is stopping."
            );
            return false;
        }
    }

    private void scheduleOnMain(Runnable task) {
        try {
            plugin.getServer().getScheduler().scheduleSyncDelayedTask(plugin, task);
        } catch (IllegalStateException ignored) {
            // The server may already be shutting down.
        }
    }

    private Connection open() throws SQLException {
        Connection connection = sqlite
            ? DriverManager.getConnection(url)
            : DriverManager.getConnection(url, user, password);
        if (sqlite) {
            Statement statement = connection.createStatement();
            try {
                statement.execute("PRAGMA busy_timeout=5000");
                statement.execute("PRAGMA foreign_keys=ON");
            } finally {
                statement.close();
            }
        }
        allConnections.add(connection);
        return connection;
    }

    private Connection borrow() throws Exception {
        Connection connection = idle.poll(5, TimeUnit.SECONDS);
        if (connection == null) {
            throw new SQLException(plugin.getLanguage().consoleValue(
                "database.pool-exhausted",
                "Database pool exhausted"
            ));
        }
        if (connection.isClosed()) {
            allConnections.remove(connection);
            return open();
        }
        return connection;
    }

    private void release(Connection connection) {
        if (connection == null) {
            return;
        }
        if (stopping) {
            closeQuietly(connection);
        } else {
            idle.offer(connection);
        }
    }

    private void schema() throws Exception {
        Connection connection = borrow();
        try {
            Statement statement = connection.createStatement();
            try {
                if (sqlite) {
                    statement.executeUpdate(
                        "CREATE TABLE IF NOT EXISTS `" + prefix + "players` ("
                            + "player_uuid TEXT NOT NULL PRIMARY KEY, player_name TEXT NOT NULL)"
                    );
                    statement.executeUpdate(
                        "CREATE TABLE IF NOT EXISTS `" + prefix + "runs` ("
                            + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                            + "player_uuid TEXT NOT NULL, player_name TEXT NOT NULL, "
                            + "run_number INTEGER NOT NULL, status TEXT NOT NULL, "
                            + "started_at_ms INTEGER NULL, finished_at_ms INTEGER NULL, "
                            + "duration_ms INTEGER NULL, start_achievement TEXT NULL, "
                            + "created_at_ms INTEGER NOT NULL, timing_source TEXT NOT NULL, "
                            + "ruleset_version INTEGER NOT NULL, "
                            + "UNIQUE(player_uuid, run_number))"
                    );
                    statement.executeUpdate(
                        "CREATE INDEX IF NOT EXISTS `" + prefix + "idx_runs_status_duration` "
                            + "ON `" + prefix + "runs` (status, duration_ms)"
                    );
                    statement.executeUpdate(
                        "CREATE TABLE IF NOT EXISTS `" + prefix + "run_progress` ("
                            + "run_id INTEGER NOT NULL, achievement_id TEXT NOT NULL, "
                            + "sequence INTEGER NOT NULL, completed_at_ms INTEGER NOT NULL, "
                            + "elapsed_ms INTEGER NOT NULL, split_ms INTEGER NOT NULL, "
                            + "PRIMARY KEY(run_id, achievement_id), "
                            + "UNIQUE(run_id, sequence), "
                            + "FOREIGN KEY(run_id) REFERENCES `" + prefix + "runs`(id) "
                            + "ON DELETE CASCADE)"
                    );
                    statement.executeUpdate(
                        "CREATE INDEX IF NOT EXISTS `" + prefix + "idx_run_progress_sequence` "
                            + "ON `" + prefix + "run_progress` (run_id, sequence)"
                    );
                } else {
                    statement.executeUpdate(
                        "CREATE TABLE IF NOT EXISTS `" + prefix + "players` ("
                            + "player_uuid VARCHAR(36) NOT NULL PRIMARY KEY, "
                            + "player_name VARCHAR(16) NOT NULL) ENGINE=InnoDB DEFAULT CHARSET=utf8"
                    );
                    statement.executeUpdate(
                        "CREATE TABLE IF NOT EXISTS `" + prefix + "runs` ("
                            + "id BIGINT NOT NULL AUTO_INCREMENT, player_uuid VARCHAR(36) NOT NULL, "
                            + "player_name VARCHAR(16) NOT NULL, run_number INT NOT NULL, "
                            + "status VARCHAR(16) NOT NULL, started_at_ms BIGINT NULL, "
                            + "finished_at_ms BIGINT NULL, duration_ms BIGINT NULL, "
                            + "start_achievement VARCHAR(64) NULL, created_at_ms BIGINT NOT NULL, "
                            + "timing_source VARCHAR(16) NOT NULL, ruleset_version INT NOT NULL, "
                            + "PRIMARY KEY (id), "
                            + "UNIQUE KEY uk_player_run (player_uuid, run_number), "
                            + "INDEX idx_runs_status_duration (status, duration_ms)) "
                            + "ENGINE=InnoDB DEFAULT CHARSET=utf8"
                    );
                    statement.executeUpdate(
                        "CREATE TABLE IF NOT EXISTS `" + prefix + "run_progress` ("
                            + "run_id BIGINT NOT NULL, achievement_id VARCHAR(64) NOT NULL, "
                            + "sequence INT NOT NULL, completed_at_ms BIGINT NOT NULL, "
                            + "elapsed_ms BIGINT NOT NULL, split_ms BIGINT NOT NULL, "
                            + "PRIMARY KEY(run_id, achievement_id), "
                            + "UNIQUE KEY uk_run_sequence (run_id, sequence), "
                            + "INDEX idx_run_progress_sequence (run_id, sequence), "
                            + "FOREIGN KEY(run_id) REFERENCES `" + prefix + "runs`(id) "
                            + "ON DELETE CASCADE) ENGINE=InnoDB DEFAULT CHARSET=utf8"
                    );
                }
            } finally {
                statement.close();
            }
        } finally {
            release(connection);
        }
    }

    public String findUuidByName(String name) throws Exception {
        Connection connection = borrow();
        try {
            PreparedStatement statement = connection.prepareStatement(
                "SELECT player_uuid FROM `" + prefix + "players` "
                    + "WHERE LOWER(player_name)=LOWER(?) LIMIT 1"
            );
            try {
                statement.setString(1, name);
                ResultSet rows = statement.executeQuery();
                String result = rows.next() ? rows.getString(1) : null;
                rows.close();
                return result;
            } finally {
                statement.close();
            }
        } finally {
            release(connection);
        }
    }

    public List<String> completionOrder() throws Exception {
        List<String> result = new ArrayList<String>();
        Connection connection = borrow();
        try {
            PreparedStatement statement = connection.prepareStatement(
                "SELECT player_uuid FROM `" + prefix + "runs` WHERE status='COMPLETED' "
                    + "GROUP BY player_uuid ORDER BY MIN(finished_at_ms) ASC"
            );
            try {
                ResultSet rows = statement.executeQuery();
                while (rows.next()) {
                    result.add(rows.getString(1));
                }
                rows.close();
            } finally {
                statement.close();
            }
        } finally {
            release(connection);
        }
        return result;
    }

    private void execute(String sql, String... values) throws Exception {
        Connection connection = borrow();
        try {
            PreparedStatement statement = connection.prepareStatement(sql);
            try {
                for (int index = 0; index < values.length; index++) {
                    statement.setString(index + 1, values[index]);
                }
                statement.executeUpdate();
            } finally {
                statement.close();
            }
        } finally {
            release(connection);
        }
    }

    public RunSnapshot loadOrCreateCurrentRun(UUID playerId, String playerName) throws Exception {
        Connection connection = borrow();
        try {
            saveName(connection, playerId.toString(), playerName);
            long timeoutCutoff = repeatTimeoutCutoffMs();
            if (timeoutCutoff != Long.MIN_VALUE) {
                connection.setAutoCommit(false);
                try {
                    deleteExpiredRuns(connection, playerId.toString(), timeoutCutoff);
                    connection.commit();
                } catch (Exception exception) {
                    rollbackQuietly(connection);
                    throw exception;
                } finally {
                    connection.setAutoCommit(true);
                }
            }
            AchievementRun run = findLatestRun(connection, playerId.toString());
            if (run == null) {
                connection.setAutoCommit(false);
                try {
                    long runId = insertRun(
                        connection,
                        playerId.toString(),
                        playerName,
                        1,
                        RunStatus.READY,
                        null,
                        null,
                        null,
                        null,
                        System.currentTimeMillis(),
                        "NORMAL"
                    );
                    connection.commit();
                    run = findRunById(connection, runId);
                } catch (Exception exception) {
                    rollbackQuietly(connection);
                    throw exception;
                } finally {
                    connection.setAutoCommit(true);
                }
            }
            return new RunSnapshot(run, loadRunProgress(connection, run.getId()));
        } finally {
            release(connection);
        }
    }

    public boolean hasCompletedRun(UUID playerId) throws Exception {
        Connection connection = borrow();
        try {
            PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM `" + prefix + "runs` WHERE player_uuid=? "
                    + "AND status='COMPLETED' LIMIT 1"
            );
            try {
                statement.setString(1, playerId.toString());
                ResultSet rows = statement.executeQuery();
                boolean found = rows.next();
                rows.close();
                return found;
            } finally {
                statement.close();
            }
        } finally {
            release(connection);
        }
    }

    public Set<UUID> deleteExpiredRuns(long cutoffMs) throws Exception {
        Connection connection = borrow();
        try {
            connection.setAutoCommit(false);
            try {
                PreparedStatement statement = connection.prepareStatement(
                    "SELECT id, player_uuid FROM `" + prefix + "runs` "
                        + "WHERE status='ACTIVE' AND run_number>1 "
                        + "AND started_at_ms IS NOT NULL AND started_at_ms<=?"
                );
                try {
                    statement.setLong(1, cutoffMs);
                    List<Long> runIds = new ArrayList<Long>();
                    Set<UUID> affected = new HashSet<UUID>();
                    ResultSet rows = statement.executeQuery();
                    while (rows.next()) {
                        runIds.add(rows.getLong(1));
                        affected.add(UUID.fromString(rows.getString(2)));
                    }
                    rows.close();
                    for (Long runId : runIds) {
                        deleteRun(connection, runId);
                    }
                    connection.commit();
                    return affected;
                } catch (Exception exception) {
                    rollbackQuietly(connection);
                    throw exception;
                } finally {
                    statement.close();
                }
            } finally {
                connection.setAutoCommit(true);
            }
        } finally {
            release(connection);
        }
    }

    private long repeatTimeoutCutoffMs() {
        int hours = plugin.getConfiguration().getInt("runs.repeat-timeout-hours", 12);
        if (hours <= 0) {
            return Long.MIN_VALUE;
        }
        return System.currentTimeMillis() - TimeUnit.HOURS.toMillis(hours);
    }

    private void deleteExpiredRuns(
        Connection connection,
        String playerId,
        long cutoffMs
    ) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(
            "SELECT id FROM `" + prefix + "runs` WHERE player_uuid=? AND status='ACTIVE' "
                + "AND run_number>1 AND started_at_ms IS NOT NULL AND started_at_ms<=?"
        );
        try {
            statement.setString(1, playerId);
            statement.setLong(2, cutoffMs);
            List<Long> runIds = new ArrayList<Long>();
            ResultSet rows = statement.executeQuery();
            while (rows.next()) {
                runIds.add(rows.getLong(1));
            }
            rows.close();
            for (Long runId : runIds) {
                deleteRun(connection, runId);
            }
        } finally {
            statement.close();
        }
    }

    private void deleteRun(Connection connection, long runId) throws SQLException {
        PreparedStatement progress = connection.prepareStatement(
            "DELETE FROM `" + prefix + "run_progress` WHERE run_id=?"
        );
        try {
            progress.setLong(1, runId);
            progress.executeUpdate();
        } finally {
            progress.close();
        }
        PreparedStatement run = connection.prepareStatement(
            "DELETE FROM `" + prefix + "runs` WHERE id=?"
        );
        try {
            run.setLong(1, runId);
            run.executeUpdate();
        } finally {
            run.close();
        }
    }

    public RunSnapshot loadLatestRunSnapshot(UUID playerId) throws Exception {
        Connection connection = borrow();
        try {
            AchievementRun run = findLatestRun(connection, playerId.toString());
            if (run == null) {
                return null;
            }
            return new RunSnapshot(run, loadRunProgress(connection, run.getId()));
        } finally {
            release(connection);
        }
    }

    public RunSnapshot createNextRun(UUID playerId, String playerName) throws Exception {
        Connection connection = borrow();
        try {
            connection.setAutoCommit(false);
            try {
                AchievementRun latest = findLatestRun(connection, playerId.toString());
                int runNumber = latest == null ? 1 : latest.getRunNumber() + 1;
                if (latest != null && !latest.getStatus().isClosed()) {
                    throw new IllegalStateException(plugin.getLanguage().consoleValue(
                        "database.current-run-open",
                        "Current run is still {status}",
                        "status",
                        latest.getStatus().toString()
                    ));
                }
                long runId = insertRun(
                    connection,
                    playerId.toString(),
                    playerName,
                    runNumber,
                    RunStatus.READY,
                    null,
                    null,
                    null,
                    null,
                    System.currentTimeMillis(),
                    "NORMAL"
                );
                connection.commit();
                AchievementRun run = findRunById(connection, runId);
                return new RunSnapshot(run, Collections.<String, RunProgressEntry>emptyMap());
            } catch (Exception exception) {
                rollbackQuietly(connection);
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } finally {
            release(connection);
        }
    }

    public void saveRunProgress(
        AchievementRun run,
        RunProgressEntry progress
    ) throws Exception {
        Connection connection = borrow();
        try {
            connection.setAutoCommit(false);
            try {
                PreparedStatement update = connection.prepareStatement(
                    "UPDATE `" + prefix + "runs` SET status=?, started_at_ms=?, "
                        + "finished_at_ms=?, duration_ms=?, start_achievement=? WHERE id=?"
                );
                try {
                    update.setString(1, run.getStatus().name());
                    setNullableLong(update, 2, run.getStartedAtMs());
                    setNullableLong(update, 3, run.getFinishedAtMs());
                    setNullableLong(update, 4, run.getDurationMs());
                    if (run.getStartAchievement() == null) {
                        update.setNull(5, java.sql.Types.VARCHAR);
                    } else {
                        update.setString(5, run.getStartAchievement());
                    }
                    update.setLong(6, run.getId());
                    if (update.executeUpdate() != 1) {
                        throw new SQLException(plugin.getLanguage().consoleValue(
                            "database.run-not-found",
                            "Run not found: {run}",
                            "run",
                            Long.toString(run.getId())
                        ));
                    }
                } finally {
                    update.close();
                }

                insertRunProgress(
                    connection,
                    run.getId(),
                    progress.getAchievementId(),
                    progress.getSequence(),
                    progress.getCompletedAtMs(),
                    progress.getElapsedMs(),
                    progress.getSplitMs()
                );
                connection.commit();
            } catch (Exception exception) {
                rollbackQuietly(connection);
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } finally {
            release(connection);
        }
    }

    public boolean setRunStatus(long runId, RunStatus status, long endedAtMs) throws Exception {
        if (!status.isClosed()) {
            throw new IllegalArgumentException(plugin.getLanguage().consoleValue(
                "database.terminal-status-required",
                "Run status must be terminal"
            ));
        }
        Connection connection = borrow();
        try {
            PreparedStatement statement = connection.prepareStatement(
                "UPDATE `" + prefix + "runs` SET status=?, "
                    + "finished_at_ms=COALESCE(finished_at_ms, ?) WHERE id=?"
            );
            try {
                statement.setString(1, status.name());
                statement.setLong(2, endedAtMs);
                statement.setLong(3, runId);
                return statement.executeUpdate() == 1;
            } finally {
                statement.close();
            }
        } finally {
            release(connection);
        }
    }

    public RunSnapshot invalidateAndCreateNextRun(
        UUID playerId,
        String playerName,
        long currentRunId,
        long endedAtMs
    ) throws Exception {
        Connection connection = borrow();
        try {
            connection.setAutoCommit(false);
            try {
                PreparedStatement invalidate = connection.prepareStatement(
                    "UPDATE `" + prefix + "runs` SET status='INVALID', "
                        + "finished_at_ms=COALESCE(finished_at_ms, ?) WHERE id=?"
                );
                try {
                    invalidate.setLong(1, endedAtMs);
                    invalidate.setLong(2, currentRunId);
                    invalidate.executeUpdate();
                } finally {
                    invalidate.close();
                }
                AchievementRun latest = findLatestRun(connection, playerId.toString());
                int runNumber = latest == null ? 1 : latest.getRunNumber() + 1;
                long runId = insertRun(
                    connection,
                    playerId.toString(),
                    playerName,
                    runNumber,
                    RunStatus.READY,
                    null,
                    null,
                    null,
                    null,
                    System.currentTimeMillis(),
                    "NORMAL"
                );
                connection.commit();
                AchievementRun run = findRunById(connection, runId);
                return new RunSnapshot(run, Collections.<String, RunProgressEntry>emptyMap());
            } catch (Exception exception) {
                rollbackQuietly(connection);
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } finally {
            release(connection);
        }
    }

    public void deleteRunAchievement(long runId, String achievementId) throws Exception {
        execute(
            "DELETE FROM `" + prefix + "run_progress` WHERE run_id=? AND achievement_id=?",
            Long.toString(runId),
            achievementId
        );
    }

    public RunLeaderboardEntry loadPersonalBest(UUID playerId) throws Exception {
        Connection connection = borrow();
        try {
            PreparedStatement statement = connection.prepareStatement(
                "SELECT player_uuid, player_name, run_number, duration_ms, finished_at_ms, "
                    + "timing_source FROM `" + prefix + "runs` WHERE player_uuid=? "
                    + "AND status='COMPLETED' ORDER BY duration_ms ASC, finished_at_ms ASC, id ASC "
                    + "LIMIT 1"
            );
            try {
                statement.setString(1, playerId.toString());
                ResultSet rows = statement.executeQuery();
                RunLeaderboardEntry entry = rows.next() ? leaderboardEntry(rows) : null;
                rows.close();
                return entry;
            } finally {
                statement.close();
            }
        } finally {
            release(connection);
        }
    }

    public List<RunLeaderboardEntry> loadLeaderboard(int limit) throws Exception {
        List<RunLeaderboardEntry> result = new ArrayList<RunLeaderboardEntry>();
        Connection connection = borrow();
        try {
            PreparedStatement statement = connection.prepareStatement(
                "SELECT r.player_uuid, r.player_name, r.run_number, r.duration_ms, "
                    + "r.finished_at_ms, r.timing_source FROM `" + prefix + "runs` r "
                    + "WHERE r.status='COMPLETED' AND NOT EXISTS ("
                    + "SELECT 1 FROM `" + prefix + "runs` better "
                    + "WHERE better.player_uuid=r.player_uuid AND better.status='COMPLETED' "
                    + "AND (better.duration_ms<r.duration_ms "
                    + "OR (better.duration_ms=r.duration_ms AND better.finished_at_ms<r.finished_at_ms) "
                    + "OR (better.duration_ms=r.duration_ms AND better.finished_at_ms=r.finished_at_ms "
                    + "AND better.id<r.id))) "
                    + "ORDER BY r.duration_ms ASC, r.finished_at_ms ASC, r.id ASC LIMIT ?"
            );
            try {
                statement.setInt(1, Math.max(1, limit));
                ResultSet rows = statement.executeQuery();
                while (rows.next()) {
                    result.add(leaderboardEntry(rows));
                }
                rows.close();
            } finally {
                statement.close();
            }
        } finally {
            release(connection);
        }
        return result;
    }

    public List<AchievementRun> loadRunHistory(UUID playerId, int limit) throws Exception {
        List<AchievementRun> result = new ArrayList<AchievementRun>();
        Connection connection = borrow();
        try {
            PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM `" + prefix + "runs` WHERE player_uuid=? "
                    + "ORDER BY run_number DESC LIMIT ?"
            );
            try {
                statement.setString(1, playerId.toString());
                statement.setInt(2, Math.max(1, limit));
                ResultSet rows = statement.executeQuery();
                while (rows.next()) {
                    result.add(run(rows));
                }
                rows.close();
            } finally {
                statement.close();
            }
        } finally {
            release(connection);
        }
        return result;
    }

    public List<RunProgressEntry> loadRunSplits(long runId) throws Exception {
        Connection connection = borrow();
        try {
            return new ArrayList<RunProgressEntry>(
                loadRunProgress(connection, runId).values()
            );
        } finally {
            release(connection);
        }
    }

    private void saveName(Connection connection, String uuid, String name) throws Exception {
        String sql = sqlite
            ? "INSERT OR REPLACE INTO `" + prefix + "players` (player_uuid,player_name) VALUES (?,?)"
            : "REPLACE INTO `" + prefix + "players` (player_uuid,player_name) VALUES (?,?)";
        PreparedStatement statement = connection.prepareStatement(sql);
        try {
            statement.setString(1, uuid);
            statement.setString(2, name);
            statement.executeUpdate();
        } finally {
            statement.close();
        }
    }

    private long insertRun(
        Connection connection,
        String playerUuid,
        String playerName,
        int runNumber,
        RunStatus status,
        Long startedAtMs,
        Long finishedAtMs,
        Long durationMs,
        String startAchievement,
        long createdAtMs,
        String timingSource
    ) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(
            "INSERT INTO `" + prefix + "runs` (player_uuid, player_name, run_number, status, "
                + "started_at_ms, finished_at_ms, duration_ms, start_achievement, "
                + "created_at_ms, timing_source, ruleset_version) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?)"
        );
        try {
            statement.setString(1, playerUuid);
            statement.setString(2, playerName);
            statement.setInt(3, runNumber);
            statement.setString(4, status.name());
            setNullableLong(statement, 5, startedAtMs);
            setNullableLong(statement, 6, finishedAtMs);
            setNullableLong(statement, 7, durationMs);
            if (startAchievement == null) {
                statement.setNull(8, java.sql.Types.VARCHAR);
            } else {
                statement.setString(8, startAchievement);
            }
            statement.setLong(9, createdAtMs);
            statement.setString(10, timingSource);
            statement.setInt(11, RULESET_VERSION);
            statement.executeUpdate();
        } finally {
            statement.close();
        }

        PreparedStatement lookup = connection.prepareStatement(
            "SELECT id FROM `" + prefix + "runs` WHERE player_uuid=? AND run_number=?"
        );
        try {
            lookup.setString(1, playerUuid);
            lookup.setInt(2, runNumber);
            ResultSet rows = lookup.executeQuery();
            if (!rows.next()) {
                throw new SQLException(plugin.getLanguage().consoleValue(
                    "database.inserted-run-unreadable",
                    "Inserted run could not be read back"
                ));
            }
            long runId = rows.getLong(1);
            rows.close();
            return runId;
        } finally {
            lookup.close();
        }
    }

    private void insertRunProgress(
        Connection connection,
        long runId,
        String achievementId,
        int sequence,
        long completedAtMs,
        long elapsedMs,
        long splitMs
    ) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(
            "INSERT INTO `" + prefix + "run_progress` "
                + "(run_id, achievement_id, sequence, completed_at_ms, elapsed_ms, split_ms) "
                + "VALUES (?,?,?,?,?,?)"
        );
        try {
            statement.setLong(1, runId);
            statement.setString(2, achievementId);
            statement.setInt(3, sequence);
            statement.setLong(4, completedAtMs);
            statement.setLong(5, elapsedMs);
            statement.setLong(6, splitMs);
            statement.executeUpdate();
        } finally {
            statement.close();
        }
    }

    private AchievementRun findLatestRun(Connection connection, String playerUuid) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(
            "SELECT * FROM `" + prefix + "runs` WHERE player_uuid=? "
                + "ORDER BY run_number DESC LIMIT 1"
        );
        try {
            statement.setString(1, playerUuid);
            ResultSet rows = statement.executeQuery();
            AchievementRun result = rows.next() ? run(rows) : null;
            rows.close();
            return result;
        } finally {
            statement.close();
        }
    }

    private AchievementRun findRunById(Connection connection, long runId) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(
            "SELECT * FROM `" + prefix + "runs` WHERE id=?"
        );
        try {
            statement.setLong(1, runId);
            ResultSet rows = statement.executeQuery();
            if (!rows.next()) {
                throw new SQLException(plugin.getLanguage().consoleValue(
                    "database.run-not-found",
                    "Run not found: {run}",
                    "run",
                    Long.toString(runId)
                ));
            }
            AchievementRun result = run(rows);
            rows.close();
            return result;
        } finally {
            statement.close();
        }
    }

    private Map<String, RunProgressEntry> loadRunProgress(
        Connection connection,
        long runId
    ) throws SQLException {
        Map<String, RunProgressEntry> result = new LinkedHashMap<String, RunProgressEntry>();
        PreparedStatement statement = connection.prepareStatement(
            "SELECT run_id, achievement_id, sequence, completed_at_ms, elapsed_ms, split_ms "
                + "FROM `" + prefix + "run_progress` WHERE run_id=? ORDER BY sequence ASC"
        );
        try {
            statement.setLong(1, runId);
            ResultSet rows = statement.executeQuery();
            while (rows.next()) {
                RunProgressEntry entry = new RunProgressEntry(
                    rows.getLong(1),
                    rows.getString(2),
                    rows.getInt(3),
                    rows.getLong(4),
                    rows.getLong(5),
                    rows.getLong(6)
                );
                result.put(entry.getAchievementId(), entry);
            }
            rows.close();
        } finally {
            statement.close();
        }
        return result;
    }

    private AchievementRun run(ResultSet rows) throws SQLException {
        return new AchievementRun(
            rows.getLong("id"),
            UUID.fromString(rows.getString("player_uuid")),
            rows.getString("player_name"),
            rows.getInt("run_number"),
            RunStatus.fromDatabase(rows.getString("status")),
            nullableLong(rows, "started_at_ms"),
            nullableLong(rows, "finished_at_ms"),
            nullableLong(rows, "duration_ms"),
            rows.getString("start_achievement"),
            rows.getLong("created_at_ms"),
            rows.getString("timing_source"),
            rows.getInt("ruleset_version")
        );
    }

    private RunLeaderboardEntry leaderboardEntry(ResultSet rows) throws SQLException {
        return new RunLeaderboardEntry(
            UUID.fromString(rows.getString(1)),
            rows.getString(2),
            rows.getInt(3),
            rows.getLong(4),
            rows.getLong(5),
            rows.getString(6)
        );
    }

    private Long nullableLong(ResultSet rows, String column) throws SQLException {
        long value = rows.getLong(column);
        return rows.wasNull() ? null : value;
    }

    private void setNullableLong(PreparedStatement statement, int index, Long value)
        throws SQLException {
        if (value == null) {
            statement.setNull(index, java.sql.Types.BIGINT);
        } else {
            statement.setLong(index, value);
        }
    }

    private void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // Preserve the original database failure.
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isReady() {
        return ready;
    }

    public void close() {
        ready = false;
        enabled = false;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                plugin.getLanguage().warning(
                    "database.shutdown-timeout",
                    "Database writes did not finish within {seconds} seconds.",
                    "seconds",
                    Long.toString(SHUTDOWN_TIMEOUT_SECONDS)
                );
                executor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
        stopping = true;
        closeIdleConnections();
        for (Connection connection : new ArrayList<Connection>(allConnections)) {
            closeQuietly(connection);
        }
    }

    private void closeIdleConnections() {
        Connection connection;
        while ((connection = idle.poll()) != null) {
            closeQuietly(connection);
        }
    }

    private void closeQuietly(Connection connection) {
        try {
            connection.close();
        } catch (SQLException ignored) {
            // Nothing useful can be done while closing the pool.
        } finally {
            allConnections.remove(connection);
        }
    }
}
