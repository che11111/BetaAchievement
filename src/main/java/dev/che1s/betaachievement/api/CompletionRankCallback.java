package dev.che1s.betaachievement.api;

/** Invoked on the Bukkit main thread after an asynchronous completion-rank query. */
public interface CompletionRankCallback {
    void onResult(CompletionStatus status);
    void onFailure(Exception exception);
}
