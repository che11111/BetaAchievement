package dev.che1s.betaachievement.run;

import java.util.UUID;

public final class RunLeaderboardEntry {
    private final UUID playerId;
    private final String playerName;
    private final int runNumber;
    private final long durationMs;
    private final long finishedAtMs;
    private final String timingSource;

    public RunLeaderboardEntry(
        UUID playerId,
        String playerName,
        int runNumber,
        long durationMs,
        long finishedAtMs,
        String timingSource
    ) {
        this.playerId = playerId;
        this.playerName = playerName;
        this.runNumber = runNumber;
        this.durationMs = durationMs;
        this.finishedAtMs = finishedAtMs;
        this.timingSource = timingSource;
    }

    public UUID getPlayerId() { return playerId; }
    public String getPlayerName() { return playerName; }
    public int getRunNumber() { return runNumber; }
    public long getDurationMs() { return durationMs; }
    public long getFinishedAtMs() { return finishedAtMs; }
    public String getTimingSource() { return timingSource; }
}
