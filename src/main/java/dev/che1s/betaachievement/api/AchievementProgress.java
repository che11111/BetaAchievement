package dev.che1s.betaachievement.api;

import java.util.*;

/** Immutable progress snapshot safe for consumption by other plugins. */
public final class AchievementProgress {
    private final UUID playerId;
    private final Set<String> completed;
    private final Set<String> missing;
    AchievementProgress(UUID playerId, Set<String> completed, Set<String> missing) {
        this.playerId = playerId;
        this.completed = Collections.unmodifiableSet(new LinkedHashSet<String>(completed));
        this.missing = Collections.unmodifiableSet(new LinkedHashSet<String>(missing));
    }
    public UUID getPlayerId() { return playerId; }
    public Set<String> getCompletedAchievementIds() { return completed; }
    public Set<String> getMissingAchievementIds() { return missing; }
    public int getCompletedCount() { return completed.size(); }
    public int getTotalCount() { return completed.size() + missing.size(); }
    public int getPercent() { return getTotalCount() == 0 ? 0 : (completed.size() * 100 / getTotalCount()); }
}
