package dev.che1s.betaachievement.run;

public final class RunProgressEntry {
    private final long runId;
    private final String achievementId;
    private final int sequence;
    private final long completedAtMs;
    private final long elapsedMs;
    private final long splitMs;

    public RunProgressEntry(
        long runId,
        String achievementId,
        int sequence,
        long completedAtMs,
        long elapsedMs,
        long splitMs
    ) {
        this.runId = runId;
        this.achievementId = achievementId;
        this.sequence = sequence;
        this.completedAtMs = completedAtMs;
        this.elapsedMs = elapsedMs;
        this.splitMs = splitMs;
    }

    public long getRunId() { return runId; }
    public String getAchievementId() { return achievementId; }
    public int getSequence() { return sequence; }
    public long getCompletedAtMs() { return completedAtMs; }
    public long getElapsedMs() { return elapsedMs; }
    public long getSplitMs() { return splitMs; }
}
