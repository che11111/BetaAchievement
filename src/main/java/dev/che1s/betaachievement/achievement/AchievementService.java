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
            plugin.getLanguage().warning(
                "achievement.load-retry",
                "Could not load achievements for {player} (attempt {attempt}/{max}): {error}",
                "player",
                player.getName(),
                "attempt",
                Integer.toString(attempt),
                "max",
                Integer.toString(MAX_LOAD_ATTEMPTS),
                "error",
                exception.getMessage()
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
        plugin.getLanguage().severe(
            "achievement.load-failed",
            "Could not load achievements for {player} after {max} attempts: {error}",
            "player",
            player.getName(),
            "max",
            Integer.toString(MAX_LOAD_ATTEMPTS),
            "error",
            exception.getMessage()
        );
        if (player.isOnline()) {
            plugin.getLanguage().send(
                player,
                "achievement.player-load-failed",
                "&cAchievements could not be loaded. Please reconnect."
            );
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
                            plugin.getLanguage().severe(
                                "achievement.expire-failed",
                                "Could not expire achievement runs: {error}",
                                "error",
                                exception.getMessage()
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
                            plugin.getLanguage().send(
                                player,
                                "achievement.expired",
                                "&eRun #{run} expired after {hours} hours and was deleted.",
                                "run",
                                Integer.toString(expiredRunNumber),
                                "hours",
                                Integer.toString(timeoutHours)
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
                            plugin.getLanguage().severe(
                                "achievement.refresh-failed",
                                "Could not refresh expired run for {player}: {error}",
                                "player",
                                player.getName(),
                                "error",
                                exception.getMessage()
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
                debug(
                    player,
                    "debug.unknown-id",
                    "Unknown achievement ID: {achievement}",
                    "achievement",
                    achievementId
                );
            }
            return Action.UNAVAILABLE;
        }

        State state = states.get(playerId);
        if (state == null) {
            if (debug) {
                debug(
                    player,
                    "debug.state-missing",
                    "Player state is not loaded (the player has not joined or been loaded)."
                );
            }
            return Action.UNAVAILABLE;
        }
        if (state.failed) {
            if (debug) {
                debug(
                    player,
                    "debug.data-failed",
                    "Achievement data failed to load; trigger ignored."
                );
            }
            return Action.UNAVAILABLE;
        }
        if (state.loading) {
            if (debug) {
                debug(
                    player,
                    "debug.loading",
                    "Player data is still loading; debug trigger was not recorded."
                );
                return Action.IGNORED;
            }
            state.queue(new PendingOperation(OperationType.UNLOCK, achievementId, adminGrant));
            return Action.QUEUED;
        }
        if (state.unlocked.contains(achievementId)) {
            if (debug) {
                debug(
                    player,
                    "debug.already-unlocked",
                    "Triggered {achievement}, but it is already complete in this run.",
                    "achievement",
                    plugin.getLanguage().achievementName(player, achievement)
                );
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
                "debug.triggered",
                "[OK] Triggered {achievement} ({id}); debug ignored prerequisites and did not write to the database.",
                "achievement",
                plugin.getLanguage().achievementName(player, achievement),
                "id",
                achievementId
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
                            plugin.getLanguage().severe(
                                "achievement.save-progress-failed",
                                "Could not save run progress for {player}: {error}",
                                "player",
                                player.getName(),
                                "error",
                                exception.getMessage()
                            );
                        }
                    });
                }
            }
        });
        if (!scheduled) {
            state.failed = true;
            plugin.getLanguage().severe(
                "achievement.queue-progress-failed",
                "Could not queue run progress for {player}.",
                "player",
                player.getName()
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

    public void debug(
        Player player,
        String key,
        String fallback,
        String... replacements
    ) {
        plugin.getLanguage().send(
            player,
            "debug.format",
            "&8[AchievementDebug] &f{message}",
            "message",
            plugin.getLanguage().gameValue(key, fallback, replacements)
        );
    }

    private void notifyUnlocked(Player player, AchievementDefinition achievement) {
        String message = plugin.getLanguage().gameValue(
            "achievement.unlocked",
            "&6Achievement Get! &f{player} &eunlocked &f{achievement}",
            "player",
            player.getName(),
            "achievement",
            plugin.getLanguage().achievementName(player, achievement)
        );
        if (plugin.getConfiguration().getBoolean("announce.broadcast", true)) {
            plugin.getServer().broadcastMessage(message);
        } else {
            player.sendMessage(message);
        }
        if (plugin.getConfiguration().getBoolean("announce.console", true)) {
            plugin.getLanguage().info(
                "achievement.unlocked-console",
                "[Achievement] {player} unlocked {achievement}",
                "player",
                player.getName(),
                "achievement",
                plugin.getLanguage().achievementName(achievement)
            );
        }
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
                            plugin.getLanguage().severe(
                                "achievement.next-run-failed",
                                "Could not create next run for {player}: {error}",
                                "player",
                                player.getName(),
                                "error",
                                exception.getMessage()
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
                        plugin.getLanguage().severe(
                            "achievement.abandon-failed",
                            "Could not abandon run: {error}",
                            "error",
                            exception.getMessage()
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
                            plugin.getLanguage().severe(
                                "achievement.reset-failed",
                                "Could not reset achievements for {player}: {error}",
                                "player",
                                player.getName(),
                                "error",
                                exception.getMessage()
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
                        plugin.getLanguage().severe(
                            "achievement.revoke-failed",
                            "Could not revoke {achievement}: {error}",
                            "achievement",
                            achievementId,
                            "error",
                            exception.getMessage()
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
                    callback.onFailure(databaseNotReady());
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
                        callback.onFailure(databaseStopping());
                }
            });
        }
    }

    public void queryCompletionRank(final UUID playerId, final CompletionRankCallback callback) {
        final int total = registry.all().size();
        if (!database.isReady()) {
            onMain(new Runnable() {
                public void run() {
                    callback.onFailure(databaseNotReady());
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
                    callback.onFailure(databaseStopping());
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
                    callback.onFailure(databaseNotReady());
                    return;
                }
                if (!database.isEnabled()) {
                    callback.onFailure(unknownPlayer(name));
                    return;
                }

                boolean scheduled = database.runAsync(new Runnable() {
                    public void run() {
                        try {
                            final String playerId = database.findUuidByName(name);
                            if (playerId == null) {
                                onMain(new Runnable() {
                                    public void run() {
                                        callback.onFailure(unknownPlayer(name));
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
                    callback.onFailure(databaseStopping());
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
                    callback.onFailure(databaseNotReady());
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
                    callback.onFailure(databaseNotReady());
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
                    callback.onFailure(databaseNotReady());
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
                    callback.onFailure(databaseNotReady());
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
                    query.failure(databaseNotReady());
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
                    query.failure(databaseStopping());
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

    private IllegalStateException databaseNotReady() {
        return new IllegalStateException(plugin.getLanguage().consoleValue(
            "error.database-not-ready",
            "Database is not ready"
        ));
    }

    private IllegalStateException databaseStopping() {
        return new IllegalStateException(plugin.getLanguage().consoleValue(
            "error.database-stopping",
            "Database is stopping"
        ));
    }

    private IllegalArgumentException unknownPlayer(String name) {
        return new IllegalArgumentException(plugin.getLanguage().consoleValue(
            "error.unknown-player",
            "Unknown player name: {player}",
            "player",
            name
        ));
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
