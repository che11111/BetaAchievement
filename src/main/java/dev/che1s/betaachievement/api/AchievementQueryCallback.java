package dev.che1s.betaachievement.api;

/** Invoked on the Bukkit main thread after an asynchronous database query. */
public interface AchievementQueryCallback {
    void onResult(AchievementProgress progress);
    void onFailure(Exception exception);
}
