package dev.che1s.betaachievement.api;

import dev.che1s.betaachievement.*;
import dev.che1s.betaachievement.achievement.*;
import dev.che1s.betaachievement.run.*;
import org.bukkit.entity.Player;
import java.util.*;

/** Public API. Obtain it from ((BetaAchievement) plugin).getApi(). */
public final class BetaAchievementApi {
    private final BetaAchievement plugin;
    public BetaAchievementApi(BetaAchievement plugin) { this.plugin = plugin; }
    /** Returns a snapshot from the in-memory cache; an offline UUID has an empty snapshot until queried. */
    public AchievementProgress getCachedProgress(Player player) { return snapshot(player.getUniqueId(), plugin.getAchievements().get(player)); }
    public AchievementProgress getCachedProgress(UUID playerId) { return snapshot(playerId, plugin.getAchievements().get(playerId)); }
    public AchievementRun getCachedRun(Player player) { return plugin.getAchievements().getCurrentRun(player); }
    public AchievementRun getCachedRun(UUID playerId) { return plugin.getAchievements().getCurrentRun(playerId); }
    /** Queries durable storage asynchronously; callback always runs on the server thread. */
    public void queryProgress(final UUID playerId, final AchievementQueryCallback callback) {
        plugin.getAchievements().query(playerId, new AchievementSetCallback() {
            public void onResult(Set<String> completed) { callback.onResult(snapshot(playerId, completed)); }
            public void onFailure(Exception exception) { callback.onFailure(exception); }
        });
    }
    /** Queries whether a player has completed all achievements and, if so, their completion rank (1-based). */
    public void queryCompletionRank(UUID playerId, CompletionRankCallback callback) { plugin.getAchievements().queryCompletionRank(playerId, callback); }
    public void queryCompletionRank(Player player, CompletionRankCallback callback) { queryCompletionRank(player.getUniqueId(), callback); }
    /** Resolves the player by name (online first, then persisted names) and reports completion rank; onFailure is called for unknown names. */
    public void queryCompletionRankByName(String playerName, CompletionRankCallback callback) { plugin.getAchievements().queryCompletionRankByName(playerName, callback); }
    public void queryPersonalBest(UUID playerId, RunQueryCallback<RunLeaderboardEntry> callback) { plugin.getAchievements().queryPersonalBest(playerId, callback); }
    public void queryLeaderboard(int limit, RunQueryCallback<List<RunLeaderboardEntry>> callback) { plugin.getAchievements().queryLeaderboard(limit, callback); }
    public void queryRunHistory(UUID playerId, int limit, RunQueryCallback<List<AchievementRun>> callback) { plugin.getAchievements().queryRunHistory(playerId, limit, callback); }
    public void queryRunSplits(long runId, RunQueryCallback<List<RunProgressEntry>> callback) { plugin.getAchievements().queryRunSplits(runId, callback); }
    private AchievementProgress snapshot(UUID id, Set<String> completed) {
        Set<String> knownCompleted = plugin.getRegistry().knownOnly(completed);
        Set<String> missing = new LinkedHashSet<String>();
        for (AchievementDefinition definition : plugin.getRegistry().all()) {
            if (!knownCompleted.contains(definition.getId())) {
                missing.add(definition.getId());
            }
        }
        return new AchievementProgress(id, knownCompleted, missing);
    }
}
