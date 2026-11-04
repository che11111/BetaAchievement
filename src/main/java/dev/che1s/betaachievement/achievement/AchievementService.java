package dev.che1s.betaachievement.achievement;

import dev.che1s.betaachievement.BetaAchievement;
import dev.che1s.betaachievement.api.CompletionRankCallback;
import dev.che1s.betaachievement.api.CompletionStatus;
import dev.che1s.betaachievement.database.DatabaseManager;
import dev.che1s.betaachievement.run.AchievementRun;
import dev.che1s.betaachievement.run.RunLeaderboardEntry;
import dev.che1s.betaachievement.run.RunProgressEntry;
import dev.che1s.betaachievement.run.RunQueryCallback;
import dev.che1s.betaachievement.run.RunSnapshot;
import dev.che1s.betaachievement.run.RunStatus;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

/**
 * Coordinates asynchronous run loading, achievement triggers and serialized
 * database writes with the main-thread player cache.
 */
public final class AchievementService {
    private static final int MAX_LOAD_ATTEMPTS = 3;

    public enum Action {
        APPLIED,
        QUEUED,
        IGNORED,
        UNAVAILABLE
    }

    private enum OperationType {
        UNLOCK,
        REVOKE,
        RESET,
        GRANT_ALL,
        NEXT_RUN,
        ABANDON_RUN
    }

    private static final class PendingOperation {
        final OperationType type;
        final String achievementId;
        final boolean adminGrant;

        PendingOperation(OperationType type, String achievementId, boolean adminGrant) {
            this.type = type;
            this.achievementId = achievementId;
            this.adminGrant = adminGrant;
        }
    }

    private static final class State {
        final Set<String> unlocked = ConcurrentHashMap.newKeySet();
        final Map<String, RunProgressEntry> progress = new ConcurrentHashMap<String, RunProgressEntry>();
        final List<PendingOperation> queued = new ArrayList<PendingOperation>();
        volatile AchievementRun run;
        volatile boolean hasCompletedRun = false;
        volatile boolean loading = true;
        volatile boolean failed = false;

        void queue(PendingOperation operation) {
            synchronized (queued) {
                queued.add(operation);
            }
        }

        List<PendingOperation> takeQueued() {
            synchronized (queued) {
                List<PendingOperation> copy = new ArrayList<PendingOperation>(queued);
                queued.clear();
                return copy;
            }
        }

        int nextSequence() {
            RunProgressEntry last = lastProgress();
            return last == null ? 1 : last.getSequence() + 1;
        }

        RunProgressEntry lastProgress() {
            RunProgressEntry last = null;
            for (RunProgressEntry entry : progress.values()) {
                if (last == null || entry.getSequence() > last.getSequence()) {
                    last = entry;
                }
            }
            return last;
        }

    }

    private final BetaAchievement plugin;
    private final AchievementRegistry registry;
    private final DatabaseManager database;
    private final ConcurrentMap<UUID, State> states = new ConcurrentHashMap<UUID, State>();
    private final ConcurrentMap<UUID, String> debugTargets = new ConcurrentHashMap<UUID, String>();
    private volatile boolean timeoutCheckRunning = false;

    public AchievementService(BetaAchievement plugin, AchievementRegistry registry, DatabaseManager database) {
        this.plugin = plugin;
        this.registry = registry;
        this.database = database;
    }

    public void load(final Player player) {
        final UUID playerId = player.getUniqueId();
        final State state = new State();
        states.put(playerId, state);

        if (!database.isEnabled()) {
            state.run = memoryRun(player, state.run == null ? 1 : state.run.getRunNumber() + 1);
            state.loading = false;
            return;
        }
        attemptLoad(player, state, 1);
    }

    private void attemptLoad(final Player player, final State state, final int attempt) {
        final UUID playerId = player.getUniqueId();
        final String playerName = player.getName();
        boolean scheduled = database.runAsync(new Runnable() {
            public void run() {
                try {
                    final RunSnapshot snapshot = database.loadOrCreateCurrentRun(playerId, playerName);
                    final boolean hasCompletedRun = database.hasCompletedRun(playerId);
                    onMain(new Runnable() {
                        public void run() {
                            if (states.get(playerId) != state) {
                                return;
                            }
                            state.hasCompletedRun = hasCompletedRun;
                            installSnapshotAndResume(player, state, snapshot);
                        }
                    });
                } catch (final Exception exception) {
                    onMain(new Runnable() {
                        public void run() {
                            handleLoadFailure(player, state, attempt, exception);
                        }
                    });
                }
            }
        });

        if (!scheduled) {
            state.loading = false;
            state.failed = true;
        }
    }

    private void installSnapshotAndResume(Player player, State state, RunSnapshot snapshot) {
        state.run = snapshot.getRun();
        state.progress.clear();
        state.unlocked.clear();
        for (RunProgressEntry entry : snapshot.getProgress().values()) {
            if (registry.get(entry.getAchievementId()) != null) {
                state.progress.put(entry.getAchievementId(), entry);
                state.unlocked.add(entry.getAchievementId());
            }
        }
        state.loading = false;
        state.failed = false;
        for (PendingOperation operation : state.takeQueued()) {
            applyQueued(player, operation);
        }
    }

    private void handleLoadFailure(Player player, State state, int attempt, Exception exception) {
        UUID playerId = player.getUniqueId();
        if (states.get(playerId) != state) {
            return;
        }

        if (attempt < MAX_LOAD_ATTEMPTS) {
            plugin.getServer().getLogger().warning(
                "Could not load achievements for " + player.getName() + " (attempt "
                    + attempt + "/" + MAX_LOAD_ATTEMPTS + "): " + exception.getMessage()
            );
            onMainDelayed(new Runnable() {
                public void run() {
                    if (states.get(playerId) == state) {
                        attemptLoad(player, state, attempt + 1);
                    }
                }
            }, 20L * attempt);
            return;
        }

        state.loading = false;
        state.failed = true;
        state.takeQueued();
        plugin.getServer().getLogger().severe(
            "Could not load achievements for " + player.getName()
                + " after " + MAX_LOAD_ATTEMPTS + " attempts: " + exception.getMessage()
        );
        if (player.isOnline()) {
            player.sendMessage(ChatColor.RED + "Achievements could not be loaded. Please reconnect.");
        }
    }

    private void applyQueued(Player player, PendingOperation operation) {
        if (operation.type == OperationType.UNLOCK) {
            unlock(player, operation.achievementId, operation.adminGrant);
        } else if (operation.type == OperationType.REVOKE) {
            revoke(player, operation.achievementId);
        } else if (operation.type == OperationType.RESET) {
            reset(player);
        } else if (operation.type == OperationType.GRANT_ALL) {
            grantAll(player);
        } else if (operation.type == OperationType.NEXT_RUN) {
            nextRun(player);
        } else if (operation.type == OperationType.ABANDON_RUN) {
            abandonRun(player);
        }
    }

    public void unload(Player player) {
        UUID playerId = player.getUniqueId();
        states.remove(playerId);
        debugTargets.remove(playerId);
    }

    public void shutdown() {
        states.clear();
        debugTargets.clear();
    }

    public void startTasks() {
        if (!database.isEnabled()) {
            return;
        }
        int timeoutHours = plugin.getConfiguration().getInt("runs.repeat-timeout-hours", 12);
        if (timeoutHours <= 0) {
            return;
        }
        plugin.getServer().getScheduler().scheduleSyncRepeatingTask(
            plugin,
            new Runnable() {
                public void run() {
                    checkExpiredRuns();
                }
            },
            20L * 60L,
            20L * 60L
        );
    }

    private void checkExpiredRuns() {
        if (timeoutCheckRunning || !database.isEnabled() || !database.isReady()) {
            return;
        }
        final int timeoutHours = plugin.getConfiguration().getInt("runs.repeat-timeout-hours", 12);
        if (timeoutHours <= 0) {
            return;
        }

        timeoutCheckRunning = true;
        final long cutoffMs = System.currentTimeMillis()
            - TimeUnit.HOURS.toMillis(timeoutHours);
        boolean scheduled = database.runAsync(new Runnable() {
            public void run() {
                try {
                    final Set<UUID> affected = database.deleteExpiredRuns(cutoffMs);
                    onMain(new Runnable() {
                        public void run() {
                            timeoutCheckRunning = false;
                            for (UUID playerId : affected) {
                                refreshExpiredPlayer(playerId, timeoutHours);
                            }
                        }
                    });
                } catch (final Exception exception) {
                    onMain(new Runnable() {
                        public void run() {
                            timeoutCheckRunning = false;
                            plugin.getServer().getLogger().severe(
                                "Could not expire achievement runs: " + exception.getMessage()
                            );
                        }
                    });
                }
            }
        });
        if (!scheduled) {
            timeoutCheckRunning = false;
        }
    }

    private void refreshExpiredPlayer(final UUID playerId, final int timeoutHours) {
        final Player player = plugin.getServer().getPlayer(playerId);
        final State state = states.get(playerId);
        if (player == null || state == null) {
            return;
        }

        final int expiredRunNumber = state.run == null ? 0 : state.run.getRunNumber();
        state.loading = true;
        boolean scheduled = database.runAsync(new Runnable() {
            public void run() {
                try {
                    final RunSnapshot snapshot = database.loadOrCreateCurrentRun(
                        playerId,
                        player.getName()
                    );
                    onMain(new Runnable() {
                        public void run() {
                            if (states.get(playerId) != state) {
                                return;
                            }
                            installSnapshotAndResume(player, state, snapshot);
                            player.sendMessage(
                                ChatColor.YELLOW + "Run #" + expiredRunNumber
                                    + " expired after " + timeoutHours
                                    + " hours and was deleted."
                            );
                        }
                    });
                } catch (final Exception exception) {
                    onMain(new Runnable() {
                        public void run() {
                            if (states.get(playerId) == state) {
                                state.loading = false;
                                state.failed = true;
                            }
                            plugin.getServer().getLogger().severe(
                                "Could not refresh expired run for " + player.getName() + ": "
                                    + exception.getMessage()
                            );
                        }
                    });
                }
            }
        });
        if (!scheduled) {
            state.loading = false;
        }
    }

    public boolean hasCompletedRun(Player player) {
        State state = states.get(player.getUniqueId());
        return state != null && state.hasCompletedRun;
    }

    public AchievementRun getCurrentRun(Player player) {
        State state = states.get(player.getUniqueId());
        return state == null ? null : state.run;
    }

    public AchievementRun getCurrentRun(UUID playerId) {
        State state = states.get(playerId);
        return state == null ? null : state.run;
    }

    public long getCurrentElapsedMs(Player player) {
        AchievementRun run = getCurrentRun(player);
        if (run == null || run.getStartedAtMs() == null) {
            return 0L;
        }
        if (run.getStatus() == RunStatus.COMPLETED && run.getDurationMs() != null) {
            return run.getDurationMs();
        }
        if (run.getFinishedAtMs() != null) {
            return Math.max(0L, run.getFinishedAtMs() - run.getStartedAtMs());
        }
        return Math.max(0L, System.currentTimeMillis() - run.getStartedAtMs());
    }

    public List<RunProgressEntry> getCachedSplits(Player player) {
        State state = states.get(player.getUniqueId());
        if (state == null) {
            return Collections.emptyList();
        }
        List<RunProgressEntry> result = new ArrayList<RunProgressEntry>(state.progress.values());
        Collections.sort(result, new Comparator<RunProgressEntry>() {
            public int compare(RunProgressEntry left, RunProgressEntry right) {
                return Integer.compare(left.getSequence(), right.getSequence());
            }
        });
        return result;
    }

    public int grantAll(Player player) {
        State state = states.get(player.getUniqueId());
        if (state == null || state.failed) {
            return -1;
        }
        if (state.loading) {
            state.queue(new PendingOperation(OperationType.GRANT_ALL, null, true));
            return -1;
        }

        int count = 0;
        for (AchievementDefinition achievement : registry.all()) {
            if (!state.unlocked.contains(achievement.getId())) {
                if (applyAchievement(player, state, achievement, true, false)
                    == Action.APPLIED) {
                    count++;
                }
            }
        }
        return count;
    }

    public Action unlock(Player player, String achievementId, boolean adminGrant) {
        AchievementDefinition achievement = registry.get(achievementId);
        UUID playerId = player.getUniqueId();
        boolean debug = achievementId.equals(debugTargets.get(playerId));
        if (achievement == null) {
            if (debug) {
                debug(player, "未知成就 ID: " + achievementId);
            }
            return Action.UNAVAILABLE;
        }

        State state = states.get(playerId);
        if (state == null) {
            if (debug) {
                debug(player, "玩家状态未加载（未 join 或未 load）");
            }
            return Action.UNAVAILABLE;
        }
        if (state.failed) {
            if (debug) {
                debug(player, "成就数据加载失败，忽略触发");
            }
            return Action.UNAVAILABLE;
        }
        if (state.loading) {
            if (debug) {
                debug(player, "玩家数据仍在加载，调试触发未记录");
                return Action.IGNORED;
            }
            state.queue(new PendingOperation(OperationType.UNLOCK, achievementId, adminGrant));
            return Action.QUEUED;
        }
        if (state.unlocked.contains(achievementId)) {
            if (debug) {
                debug(player, "触发 " + achievement.getName() + " 但本轮已完成，跳过");
            }
            return Action.IGNORED;
        }
        if (!debug && !adminGrant && !runAcceptsAchievements(state.run)) {
            return Action.IGNORED;
        }
        if (!adminGrant && !debug && achievement.getParent() != null
            && !state.unlocked.contains(achievement.getParent())) {
            return Action.IGNORED;
        }
        if (adminGrant) {
            grantParents(player, state, achievement);
        }
        if (debug) {
            debug(
                player,
                "[OK] 触发成就「" + achievement.getName() + "」(" + achievementId
                    + ")：debug 忽略前置、未写入数据库"
            );
            return Action.IGNORED;
        }
        return applyAchievement(player, state, achievement, adminGrant, true);
    }

    private boolean runAcceptsAchievements(AchievementRun run) {
        return run != null && (run.getStatus() == RunStatus.READY || run.getStatus() == RunStatus.ACTIVE);
    }

    private void grantParents(Player player, State state, AchievementDefinition achievement) {
        if (achievement.getParent() == null) {
            return;
        }
        AchievementDefinition parent = registry.get(achievement.getParent());
        if (parent == null || state.unlocked.contains(parent.getId())) {
            return;
        }
        grantParents(player, state, parent);
        applyAchievement(player, state, parent, true, false);
    }

    private Action applyAchievement(
        Player player,
        State state,
        AchievementDefinition achievement,
        boolean adminGrant,
        boolean notify
    ) {
        AchievementRun run = state.run;
        if (run == null) {
            return Action.UNAVAILABLE;
        }

        long now = System.currentTimeMillis();
        if (run.getStartedAtMs() == null) {
            run = run.start(now, achievement.getId());
            if (adminGrant) {
                run = run.end(RunStatus.INVALID, now);
            }
        } else if (adminGrant
            && run.getStatus() != RunStatus.INVALID
            && run.getStatus() != RunStatus.ABANDONED) {
            run = run.end(RunStatus.INVALID, now);
        }

        if (!adminGrant && !runAcceptsAchievements(run)) {
            state.run = run;
            return Action.IGNORED;
        }

        int sequence = state.nextSequence();
        RunProgressEntry previous = state.lastProgress();
        long splitMs = previous == null ? 0L : Math.max(0L, now - previous.getCompletedAtMs());
        long elapsedMs = Math.max(0L, now - run.getStartedAtMs());
        RunProgressEntry entry = new RunProgressEntry(
            run.getId(),
            achievement.getId(),
            sequence,
            now,
            elapsedMs,
            splitMs
        );

        if (!adminGrant
            && run.getStatus() == RunStatus.ACTIVE
            && state.progress.size() + 1 >= registry.all().size()) {
            run = run.complete(now, elapsedMs);
            state.hasCompletedRun = true;
        }
        state.run = run;
        state.progress.put(achievement.getId(), entry);
        state.unlocked.add(achievement.getId());
        persistRunProgress(player, state, run, entry);
        if (notify) {
            notifyUnlocked(player, achievement);
        }
        return Action.APPLIED;
    }

    private void persistRunProgress(
        final Player player,
        final State state,
        final AchievementRun run,
        final RunProgressEntry progress
    ) {
        if (!database.isEnabled()) {
            return;
        }
        boolean scheduled = database.runAsync(new Runnable() {
            public void run() {
                try {
                    database.saveRunProgress(run, progress);
                } catch (final Exception exception) {
                    onMain(new Runnable() {
                        public void run() {
                            if (states.get(player.getUniqueId()) == state) {
                                state.failed = true;
                            }
                            plugin.getServer().getLogger().severe(
                                "Could not save run progress for " + player.getName() + ": "
                                    + exception.getMessage()
                            );
                        }
                    });
                }
            }
        });
        if (!scheduled) {
            state.failed = true;
            plugin.getServer().getLogger().severe(
                "Could not queue run progress for " + player.getName()
            );
        }
    }

    public void startDebug(Player player, String achievementId) {
        debugTargets.put(player.getUniqueId(), achievementId);
    }

    public void stopDebug(Player player) {
        debugTargets.remove(player.getUniqueId());
    }

    public String getDebugTarget(Player player) {
        return debugTargets.get(player.getUniqueId());
    }

    public boolean isDebugging(Player player) {
        return debugTargets.containsKey(player.getUniqueId());
    }

    public boolean isDebugging(Player player, String achievementId) {
        return achievementId.equals(debugTargets.get(player.getUniqueId()));
    }

    public void debug(Player player, String message) {
        player.sendMessage(ChatColor.GRAY + "[AchievementDebug] " + ChatColor.WHITE + message);
    }

    private void notifyUnlocked(Player player, AchievementDefinition achievement) {
        String message = plugin.getConfiguration()
            .getString(
                "messages.achievement-unlocked",
                "&6Achievement Get! &f{player} &eunlocked &f{achievement}"
            )
            .replace("{player}", player.getName())
            .replace("{achievement}", achievement.getName());
        String coloured = colour(message);
        if (plugin.getConfiguration().getBoolean("announce.broadcast", true)) {
            plugin.getServer().broadcastMessage(coloured);
        } else {
            player.sendMessage(coloured);
        }
        if (plugin.getConfiguration().getBoolean("announce.console", true)) {
            String consoleMessage = plugin.getConfiguration()
                .getString(
                    "messages.achievement-unlocked-console",
                    "[成就] {player} 获得了成就「{achievement}」"
                )
                .replace("{player}", player.getName())
                .replace("{achievement}", achievement.getChineseName());
            plugin.getServer().getLogger().info(plain(colour(consoleMessage)));
        }
    }

    private String colour(String input) {
        StringBuilder output = new StringBuilder();
        for (int index = 0; index < input.length(); index++) {
            if (input.charAt(index) == '&' && index + 1 < input.length()) {
                int code = Character.digit(input.charAt(index + 1), 16);
                if (code >= 0) {
                    output.append(ChatColor.getByCode(code));
                    index++;
                    continue;
                }
                output.append('&');
            }
            output.append(input.charAt(index));
        }
        return output.toString();
    }

    private String plain(String input) {
        StringBuilder output = new StringBuilder();
        for (int index = 0; index < input.length(); index++) {
            char character = input.charAt(index);
            if (character == '\u00A7') {
                if (index + 1 < input.length()) {
                    index++;
                }
            } else {
                output.append(character);
            }
        }
        return output.toString();
    }

    public Set<String> get(Player player) {
        State state = states.get(player.getUniqueId());
        return state == null
            ? Collections.<String>emptySet()
            : new HashSet<String>(state.unlocked);
    }

    public boolean isLoading(Player player) {
        State state = states.get(player.getUniqueId());
        return state != null && state.loading;
    }

    public boolean isLoadFailed(Player player) {
        State state = states.get(player.getUniqueId());
        return state != null && state.failed;
    }

    public boolean isUnlocked(Player player, String achievementId) {
        State state = states.get(player.getUniqueId());
        return state != null && state.unlocked.contains(achievementId);
    }

    public Set<String> get(UUID playerId) {
        State state = states.get(playerId);
        return state == null
            ? Collections.<String>emptySet()
            : new HashSet<String>(state.unlocked);
    }

    public Action nextRun(final Player player) {
        final State state = states.get(player.getUniqueId());
        if (state == null || state.failed) {
            return Action.UNAVAILABLE;
        }
        if (state.loading) {
            state.queue(new PendingOperation(OperationType.NEXT_RUN, null, false));
            return Action.QUEUED;
        }
        if (!state.hasCompletedRun) {
            return Action.IGNORED;
        }
        AchievementRun current = state.run;
        if (current == null || !current.getStatus().isClosed()) {
            return Action.IGNORED;
        }
        if (!database.isEnabled()) {
            state.run = memoryRun(player, current.getRunNumber() + 1);
            state.progress.clear();
            state.unlocked.clear();
            return Action.APPLIED;
        }

        state.loading = true;
        final UUID playerId = player.getUniqueId();
        boolean scheduled = database.runAsync(new Runnable() {
            public void run() {
                try {
                    final RunSnapshot snapshot = database.createNextRun(playerId, player.getName());
                    onMain(new Runnable() {
                        public void run() {
                            if (states.get(playerId) == state) {
                                installSnapshotAndResume(player, state, snapshot);
                            }
                        }
                    });
                } catch (final Exception exception) {
                    onMain(new Runnable() {
                        public void run() {
                            if (states.get(playerId) == state) {
                                state.loading = false;
                            }
                            plugin.getServer().getLogger().severe(
                                "Could not create next run for " + player.getName() + ": "
                                    + exception.getMessage()
                            );
                        }
                    });
                }
            }
        });
        if (!scheduled) {
            state.loading = false;
            return Action.UNAVAILABLE;
        }
        return Action.QUEUED;
    }

    public Action abandonRun(Player player) {
        State state = states.get(player.getUniqueId());
        if (state == null || state.failed) {
            return Action.UNAVAILABLE;
        }
        if (state.loading) {
            state.queue(new PendingOperation(OperationType.ABANDON_RUN, null, false));
            return Action.QUEUED;
        }
        if (!state.hasCompletedRun) {
            return Action.IGNORED;
        }
        AchievementRun run = state.run;
        if (run == null
            || run.getStatus() != RunStatus.ACTIVE
            || run.getRunNumber() <= 1) {
            return Action.IGNORED;
        }
        final long runId = run.getId();
        long now = System.currentTimeMillis();
        state.run = run.end(RunStatus.ABANDONED, now);
        if (database.isEnabled()) {
            database.runAsync(new Runnable() {
                public void run() {
                    try {
                        database.setRunStatus(runId, RunStatus.ABANDONED, now);
                    } catch (Exception exception) {
                        plugin.getServer().getLogger().severe(
                            "Could not abandon run: " + exception.getMessage()
                        );
                    }
                }
            });
        }
        return Action.APPLIED;
    }

    public Action reset(final Player player) {
        final State state = states.get(player.getUniqueId());
        if (state == null || state.failed) {
            return Action.UNAVAILABLE;
        }
        if (state.loading) {
            state.queue(new PendingOperation(OperationType.RESET, null, true));
            return Action.QUEUED;
        }
        if (!database.isEnabled()) {
            state.run = memoryRun(player, state.run == null ? 1 : state.run.getRunNumber() + 1);
            state.progress.clear();
            state.unlocked.clear();
            return Action.APPLIED;
        }

        state.loading = true;
        final UUID playerId = player.getUniqueId();
        final long runId = state.run == null ? -1L : state.run.getId();
        boolean scheduled = database.runAsync(new Runnable() {
            public void run() {
                try {
                    final RunSnapshot snapshot = database.invalidateAndCreateNextRun(
                        playerId,
                        player.getName(),
                        runId,
                        System.currentTimeMillis()
                    );
                    onMain(new Runnable() {
                        public void run() {
                            if (states.get(playerId) == state) {
                                installSnapshotAndResume(player, state, snapshot);
                            }
                        }
                    });
                } catch (final Exception exception) {
                    onMain(new Runnable() {
                        public void run() {
                            if (states.get(playerId) == state) {
                                state.loading = false;
                            }
                            plugin.getServer().getLogger().severe(
                                "Could not reset achievements for " + player.getName() + ": "
                                    + exception.getMessage()
                            );
                        }
                    });
                }
            }
        });
        if (!scheduled) {
            state.loading = false;
            return Action.UNAVAILABLE;
        }
        return Action.QUEUED;
    }

    public Action revoke(Player player, String achievementId) {
        State state = states.get(player.getUniqueId());
        if (state == null || state.failed) {
            return Action.UNAVAILABLE;
        }
        if (state.loading) {
            state.queue(new PendingOperation(OperationType.REVOKE, achievementId, false));
            return Action.QUEUED;
        }
        if (!state.unlocked.remove(achievementId)) {
            return Action.IGNORED;
        }
        state.progress.remove(achievementId);

        AchievementRun run = state.run;
        if (run != null && run.getStatus() != RunStatus.INVALID
            && run.getStatus() != RunStatus.ABANDONED) {
            run = run.end(RunStatus.INVALID, System.currentTimeMillis());
            state.run = run;
        }
        if (database.isEnabled() && run != null) {
            final long runId = run.getId();
            database.runAsync(new Runnable() {
                public void run() {
                    try {
                        database.setRunStatus(runId, RunStatus.INVALID, System.currentTimeMillis());
                        database.deleteRunAchievement(runId, achievementId);
                    } catch (Exception exception) {
                        plugin.getServer().getLogger().severe(
                            "Could not revoke " + achievementId + ": " + exception.getMessage()
                        );
                    }
                }
            });
        }
        return Action.APPLIED;
    }

    public void query(final UUID playerId, final AchievementSetCallback callback) {
        if (!database.isReady()) {
            onMain(new Runnable() {
                public void run() {
                    callback.onFailure(new IllegalStateException("Database is not ready"));
                }
            });
            return;
        }
        if (!database.isEnabled()) {
            final Set<String> result = get(playerId);
            onMain(new Runnable() {
                public void run() {
                    callback.onResult(result);
                }
            });
            return;
        }

        boolean scheduled = database.runAsync(new Runnable() {
            public void run() {
                try {
                    final RunSnapshot snapshot = database.loadLatestRunSnapshot(playerId);
                    final Set<String> result = snapshot == null
                        ? Collections.<String>emptySet()
                        : registry.knownOnly(snapshot.getProgress().keySet());
                    onMain(new Runnable() {
                        public void run() {
                            callback.onResult(result);
                        }
                    });
                } catch (final Exception exception) {
                    onMain(new Runnable() {
                        public void run() {
                            callback.onFailure(exception);
                        }
                    });
                }
            }
        });
        if (!scheduled) {
            onMain(new Runnable() {
                public void run() {
                    callback.onFailure(new IllegalStateException("Database is stopping"));
                }
            });
        }
    }

    public void queryCompletionRank(final UUID playerId, final CompletionRankCallback callback) {
        final int total = registry.all().size();
        if (!database.isReady()) {
            onMain(new Runnable() {
                public void run() {
                    callback.onFailure(new IllegalStateException("Database is not ready"));
                }
            });
            return;
        }
        if (!database.isEnabled()) {
            AchievementRun run = states.containsKey(playerId) ? states.get(playerId).run : null;
            final Set<String> completed = get(playerId);
            final boolean complete = run != null && run.getStatus() == RunStatus.COMPLETED;
            onMain(new Runnable() {
                public void run() {
                    callback.onResult(new CompletionStatus(complete, 0, completed.size(), total));
                }
            });
            return;
        }

        boolean scheduled = database.runAsync(new Runnable() {
            public void run() {
                try {
                    final RunSnapshot snapshot = database.loadLatestRunSnapshot(playerId);
                    final Set<String> completed = snapshot == null
                        ? Collections.<String>emptySet()
                        : registry.knownOnly(snapshot.getProgress().keySet());
                    final List<String> order = database.completionOrder();
                    final int index = order.indexOf(playerId.toString());
                    final boolean complete = index >= 0;
                    final CompletionStatus status = new CompletionStatus(
                        complete,
                        index >= 0 ? index + 1 : 0,
                        complete ? total : completed.size(),
                        total
                    );
                    onMain(new Runnable() {
                        public void run() {
                            callback.onResult(status);
                        }
                    });
                } catch (final Exception exception) {
                    onMain(new Runnable() {
                        public void run() {
                            callback.onFailure(exception);
                        }
                    });
                }
            }
        });
        if (!scheduled) {
            onMain(new Runnable() {
                public void run() {
                    callback.onFailure(new IllegalStateException("Database is stopping"));
                }
            });
        }
    }

    public void queryCompletionRankByName(final String name, final CompletionRankCallback callback) {
        onMain(new Runnable() {
            public void run() {
                Player online = null;
                for (Player player : plugin.getServer().getOnlinePlayers()) {
                    if (player.getName().equalsIgnoreCase(name)) {
                        online = player;
                        break;
                    }
                }
                if (online != null) {
                    queryCompletionRank(online.getUniqueId(), callback);
                    return;
                }
                if (!database.isReady()) {
                    callback.onFailure(new IllegalStateException("Database is not ready"));
                    return;
                }
                if (!database.isEnabled()) {
                    callback.onFailure(new IllegalArgumentException("Unknown player name: " + name));
                    return;
                }

                boolean scheduled = database.runAsync(new Runnable() {
                    public void run() {
                        try {
                            final String playerId = database.findUuidByName(name);
                            if (playerId == null) {
                                onMain(new Runnable() {
                                    public void run() {
                                        callback.onFailure(
                                            new IllegalArgumentException("Unknown player name: " + name)
                                        );
                                    }
                                });
                                return;
                            }
                            final UUID uuid = UUID.fromString(playerId);
                            final RunSnapshot snapshot = database.loadLatestRunSnapshot(uuid);
                            final Set<String> completed = snapshot == null
                                ? Collections.<String>emptySet()
                                : registry.knownOnly(snapshot.getProgress().keySet());
                            final List<String> order = database.completionOrder();
                            final int index = order.indexOf(playerId);
                            final boolean complete = index >= 0;
                            final CompletionStatus status = new CompletionStatus(
                                complete,
                                index >= 0 ? index + 1 : 0,
                                complete ? registry.all().size() : completed.size(),
                                registry.all().size()
                            );
                            onMain(new Runnable() {
                                public void run() {
                                    callback.onResult(status);
                                }
                            });
                        } catch (final Exception exception) {
                            onMain(new Runnable() {
                                public void run() {
                                    callback.onFailure(exception);
                                }
                            });
                        }
                    }
                });
                if (!scheduled) {
                    callback.onFailure(new IllegalStateException("Database is stopping"));
                }
            }
        });
    }

    public void queryPersonalBest(
        final UUID playerId,
        final RunQueryCallback<RunLeaderboardEntry> callback
    ) {
        if (!database.isReady()) {
            onMain(new Runnable() {
                public void run() {
                    callback.onFailure(new IllegalStateException("Database is not ready"));
                }
            });
            return;
        }
        if (!database.isEnabled()) {
            onMain(new Runnable() {
                public void run() {
                    callback.onResult(null);
                }
            });
            return;
        }
        databaseQuery(new DatabaseQuery<RunLeaderboardEntry>() {
            public RunLeaderboardEntry execute() throws Exception {
                return database.loadPersonalBest(playerId);
            }

            public void result(RunLeaderboardEntry value) {
                callback.onResult(value);
            }

            public void failure(Exception exception) {
                callback.onFailure(exception);
            }
        });
    }

    public void queryLeaderboard(
        final int limit,
        final RunQueryCallback<List<RunLeaderboardEntry>> callback
    ) {
        if (!database.isReady()) {
            onMain(new Runnable() {
                public void run() {
                    callback.onFailure(new IllegalStateException("Database is not ready"));
                }
            });
            return;
        }
        if (!database.isEnabled()) {
            onMain(new Runnable() {
                public void run() {
                    callback.onResult(Collections.<RunLeaderboardEntry>emptyList());
                }
            });
            return;
        }
        databaseQuery(new DatabaseQuery<List<RunLeaderboardEntry>>() {
            public List<RunLeaderboardEntry> execute() throws Exception {
                return database.loadLeaderboard(limit);
            }

            public void result(List<RunLeaderboardEntry> value) {
                callback.onResult(value);
            }

            public void failure(Exception exception) {
                callback.onFailure(exception);
            }
        });
    }

    public void queryRunHistory(
        final UUID playerId,
        final int limit,
        final RunQueryCallback<List<AchievementRun>> callback
    ) {
        if (!database.isReady()) {
            onMain(new Runnable() {
                public void run() {
                    callback.onFailure(new IllegalStateException("Database is not ready"));
                }
            });
            return;
        }
        if (!database.isEnabled()) {
            onMain(new Runnable() {
                public void run() {
                    State state = states.get(playerId);
                    List<AchievementRun> runs = state == null || state.run == null
                        ? Collections.<AchievementRun>emptyList()
                        : Collections.singletonList(state.run);
                    callback.onResult(runs);
                }
            });
            return;
        }
        databaseQuery(new DatabaseQuery<List<AchievementRun>>() {
            public List<AchievementRun> execute() throws Exception {
                return database.loadRunHistory(playerId, limit);
            }

            public void result(List<AchievementRun> value) {
                callback.onResult(value);
            }

            public void failure(Exception exception) {
                callback.onFailure(exception);
            }
        });
    }

    public void queryRunSplits(
        final long runId,
        final RunQueryCallback<List<RunProgressEntry>> callback
    ) {
        if (!database.isReady()) {
            onMain(new Runnable() {
                public void run() {
                    callback.onFailure(new IllegalStateException("Database is not ready"));
                }
            });
            return;
        }
        if (!database.isEnabled()) {
            onMain(new Runnable() {
                public void run() {
                    callback.onResult(Collections.<RunProgressEntry>emptyList());
                }
            });
            return;
        }
        databaseQuery(new DatabaseQuery<List<RunProgressEntry>>() {
            public List<RunProgressEntry> execute() throws Exception {
                return database.loadRunSplits(runId);
            }

            public void result(List<RunProgressEntry> value) {
                callback.onResult(value);
            }

            public void failure(Exception exception) {
                callback.onFailure(exception);
            }
        });
    }

    private interface DatabaseQuery<T> {
        T execute() throws Exception;
        void result(T value);
        void failure(Exception exception);
    }

    private <T> void databaseQuery(final DatabaseQuery<T> query) {
        if (!database.isReady()) {
            onMain(new Runnable() {
                public void run() {
                    query.failure(new IllegalStateException("Database is not ready"));
                }
            });
            return;
        }
        boolean scheduled = database.runAsync(new Runnable() {
            public void run() {
                try {
                    final T result = query.execute();
                    onMain(new Runnable() {
                        public void run() {
                            query.result(result);
                        }
                    });
                } catch (final Exception exception) {
                    onMain(new Runnable() {
                        public void run() {
                            query.failure(exception);
                        }
                    });
                }
            }
        });
        if (!scheduled) {
            onMain(new Runnable() {
                public void run() {
                    query.failure(new IllegalStateException("Database is stopping"));
                }
            });
        }
    }

    private AchievementRun memoryRun(Player player, int runNumber) {
        return new AchievementRun(
            -runNumber,
            player.getUniqueId(),
            player.getName(),
            runNumber,
            RunStatus.READY,
            null,
            null,
            null,
            null,
            System.currentTimeMillis(),
            "MEMORY",
            1
        );
    }

    private Set<String> knownOnly(Collection<String> achievements) {
        return registry.knownOnly(achievements);
    }

    private void onMain(Runnable task) {
        if (!plugin.isEnabled()) {
            return;
        }
        try {
            plugin.getServer().getScheduler().scheduleSyncDelayedTask(plugin, task);
        } catch (IllegalStateException ignored) {
            // The server may already be shutting down.
        }
    }

    private void onMainDelayed(Runnable task, long delayTicks) {
        if (!plugin.isEnabled()) {
            return;
        }
        try {
            plugin.getServer().getScheduler().scheduleSyncDelayedTask(plugin, task, delayTicks);
        } catch (IllegalStateException ignored) {
            // The server may already be shutting down.
        }
    }
}
