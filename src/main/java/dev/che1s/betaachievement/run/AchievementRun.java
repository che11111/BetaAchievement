package dev.che1s.betaachievement.run;

import java.util.UUID;

public final class AchievementRun {
    private final long id;
    private final UUID playerId;
    private final String playerName;
    private final int runNumber;
    private final RunStatus status;
    private final Long startedAtMs;
    private final Long finishedAtMs;
    private final Long durationMs;
    private final String startAchievement;
    private final long createdAtMs;
    private final String timingSource;
    private final int rulesetVersion;

    public AchievementRun(
        long id,
        UUID playerId,
        String playerName,
        int runNumber,
        RunStatus status,
        Long startedAtMs,
        Long finishedAtMs,
        Long durationMs,
        String startAchievement,
        long createdAtMs,
        String timingSource,
        int rulesetVersion
    ) {
        this.id = id;
        this.playerId = playerId;
        this.playerName = playerName;
        this.runNumber = runNumber;
        this.status = status;
        this.startedAtMs = startedAtMs;
        this.finishedAtMs = finishedAtMs;
        this.durationMs = durationMs;
        this.startAchievement = startAchievement;
        this.createdAtMs = createdAtMs;
        this.timingSource = timingSource;
        this.rulesetVersion = rulesetVersion;
    }

    public long getId() { return id; }
    public UUID getPlayerId() { return playerId; }
    public String getPlayerName() { return playerName; }
    public int getRunNumber() { return runNumber; }
    public RunStatus getStatus() { return status; }
    public Long getStartedAtMs() { return startedAtMs; }
    public Long getFinishedAtMs() { return finishedAtMs; }
    public Long getDurationMs() { return durationMs; }
    public String getStartAchievement() { return startAchievement; }
    public long getCreatedAtMs() { return createdAtMs; }
    public String getTimingSource() { return timingSource; }
    public int getRulesetVersion() { return rulesetVersion; }

    public AchievementRun start(long startedAt, String achievementId) {
        return new AchievementRun(
            id,
            playerId,
            playerName,
            runNumber,
            RunStatus.ACTIVE,
            startedAt,
            finishedAtMs,
            durationMs,
            achievementId,
            createdAtMs,
            timingSource,
            rulesetVersion
        );
    }

    public AchievementRun complete(long completedAt, long totalDurationMs) {
        return new AchievementRun(
            id,
            playerId,
            playerName,
            runNumber,
            RunStatus.COMPLETED,
            startedAtMs,
            completedAt,
            totalDurationMs,
            startAchievement,
            createdAtMs,
            timingSource,
            rulesetVersion
        );
    }

    public AchievementRun withStatus(RunStatus nextStatus) {
        return new AchievementRun(
            id,
            playerId,
            playerName,
            runNumber,
            nextStatus,
            startedAtMs,
            finishedAtMs,
            durationMs,
            startAchievement,
            createdAtMs,
            timingSource,
            rulesetVersion
        );
    }

    public AchievementRun end(RunStatus terminalStatus, long endedAt) {
        return new AchievementRun(
            id,
            playerId,
            playerName,
            runNumber,
            terminalStatus,
            startedAtMs,
            endedAt,
            durationMs,
            startAchievement,
            createdAtMs,
            timingSource,
            rulesetVersion
        );
    }
}
