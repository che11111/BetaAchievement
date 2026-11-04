package dev.che1s.betaachievement.achievement;
import java.util.Set;
/** Internal raw-storage callback used by the public API adapter. */
public interface AchievementSetCallback {
    void onResult(Set<String> completed);
    void onFailure(Exception exception);
}
