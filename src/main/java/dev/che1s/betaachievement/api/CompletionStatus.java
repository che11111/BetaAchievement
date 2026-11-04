package dev.che1s.betaachievement.api;

/** Immutable completion-rank snapshot safe for consumption by other plugins. */
public final class CompletionStatus {
    private final boolean complete;
    private final int rank;
    private final int completedCount;
    private final int totalCount;
    public CompletionStatus(boolean complete, int rank, int completedCount, int totalCount) {
        this.complete = complete;
        this.rank = rank;
        this.completedCount = completedCount;
        this.totalCount = totalCount;
    }
    /** True when the player has unlocked every achievement. */
    public boolean isComplete() { return complete; }
    /** 1-based position among players who completed all achievements; 0 when not complete. */
    public int getRank() { return rank; }
    public int getCompletedCount() { return completedCount; }
    public int getTotalCount() { return totalCount; }
}
