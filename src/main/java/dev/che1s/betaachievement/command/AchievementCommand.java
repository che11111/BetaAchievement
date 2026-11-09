package dev.che1s.betaachievement.command;

import dev.che1s.betaachievement.BetaAchievement;
import dev.che1s.betaachievement.achievement.AchievementDefinition;
import dev.che1s.betaachievement.achievement.AchievementService;
import dev.che1s.betaachievement.run.AchievementRun;
import dev.che1s.betaachievement.run.RunLeaderboardEntry;
import dev.che1s.betaachievement.run.RunProgressEntry;
import dev.che1s.betaachievement.run.RunQueryCallback;
import dev.che1s.betaachievement.run.RunStatus;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class AchievementCommand implements CommandExecutor {
    private final BetaAchievement plugin;

    public AchievementCommand(BetaAchievement plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!plugin.isReady()) {
            msg(
                sender,
                "command.storage-initializing",
                "&eAchievement storage is still initializing."
            );
            return true;
        }
        if (args.length == 0) {
            if (sender instanceof Player) {
                return showProgress(sender, (Player) sender);
            }
            msg(
                sender,
                "command.console-check-usage",
                "Console: /{label} check <player>",
                "label",
                label
            );
            return true;
        }

        String action = args[0].toLowerCase();
        if ("check".equals(action)) {
            return check(sender, args);
        }
        if ("debug".equals(action)) {
            return debug(sender, args, label);
        }
        if ("run".equals(action)) {
            return run(sender, args, label);
        }
        if ("history".equals(action)) {
            return runHistory(sender);
        }
        if ("pb".equals(action)) {
            return personalBest(sender, args);
        }
        if ("top".equals(action)) {
            return leaderboard(sender, args);
        }
        if (args.length < 2) {
            msg(
                sender,
                "command.admin-usage",
                "/{label} <grant|grantall|revoke|reset> <player> [achievement]",
                "label",
                label
            );
            return true;
        }

        Player target = plugin.getServer().getPlayer(args[1]);
        if (target == null) {
            msg(sender, "command.player-online", "&cPlayer must be online.");
            return true;
        }
        if (plugin.getAchievements().isLoadFailed(target)) {
            msg(
                sender,
                "command.player-data-load-failed",
                "&c{player}'s achievement data failed to load.",
                "player",
                target.getName()
            );
            return true;
        }

        if ("reset".equals(action)) {
            if (!allow(sender, "betaachievement.reset")) {
                return true;
            }
            AchievementService.Action result = plugin.getAchievements().reset(target);
            if (result == AchievementService.Action.QUEUED) {
                msg(sender, "reset.queued", "&eReset and next run queued.");
            } else if (result == AchievementService.Action.APPLIED) {
                msg(
                    sender,
                    "reset.applied",
                    "Reset {player}; history was preserved.",
                    "player",
                    target.getName()
                );
            } else {
                msg(
                    sender,
                    "command.state-unavailable",
                    "&cPlayer achievement state is unavailable."
                );
            }
            return true;
        }
        if ("grantall".equals(action)) {
            if (!allow(sender, "betaachievement.grantall")) {
                return true;
            }
            int count = plugin.getAchievements().grantAll(target);
            if (count < 0) {
                msg(sender, "grant-all.queued", "&eGrant-all queued until data loads.");
            } else if (count == 0) {
                msg(sender, "grant-all.none", "No missing achievements were granted.");
            } else {
                msg(
                    sender,
                    "grant-all.applied",
                    "Granted {count} achievement(s) to {player}. This run is invalid for rankings.",
                    "count",
                    Integer.toString(count),
                    "player",
                    target.getName()
                );
            }
            return true;
        }
        if (!"grant".equals(action) && !"revoke".equals(action)) {
            msg(
                sender,
                "command.unknown-action",
                "&cUnknown action. Use run, pb, top, grant, grantall, revoke, reset or debug."
            );
            return true;
        }
        if (args.length < 3) {
            msg(sender, "command.missing-achievement", "&cMissing achievement ID.");
            return true;
        }

        AchievementDefinition achievement = plugin.getRegistry().get(args[2]);
        if (achievement == null) {
            msg(sender, "command.unknown-achievement", "&cUnknown Beta achievement ID.");
            return true;
        }

        if ("grant".equals(action)) {
            if (!allow(sender, "betaachievement.grant")) {
                return true;
            }
            AchievementService.Action result = plugin.getAchievements().unlock(
                target,
                achievement.getId(),
                true
            );
            if (result == AchievementService.Action.APPLIED) {
                msg(
                    sender,
                    "grant.applied",
                    "Granted {achievement}. Current run is invalid for rankings.",
                    "achievement",
                    plugin.getLanguage().achievementName(sender, achievement)
                );
            } else if (result == AchievementService.Action.QUEUED) {
                msg(sender, "grant.queued", "&eGrant queued until data loads.");
            } else if (result == AchievementService.Action.IGNORED) {
                msg(
                    sender,
                    "grant.already",
                    "{achievement} is already granted in this run.",
                    "achievement",
                    plugin.getLanguage().achievementName(sender, achievement)
                );
            } else {
                msg(
                    sender,
                    "command.state-unavailable",
                    "&cPlayer achievement state is unavailable."
                );
            }
            return true;
        }

        if (!allow(sender, "betaachievement.revoke")) {
            return true;
        }
        AchievementService.Action result = plugin.getAchievements().revoke(target, achievement.getId());
        if (result == AchievementService.Action.APPLIED) {
            msg(
                sender,
                "revoke.applied",
                "Revoked {achievement}. Current run is invalid for rankings.",
                "achievement",
                plugin.getLanguage().achievementName(sender, achievement)
            );
        } else if (result == AchievementService.Action.QUEUED) {
            msg(sender, "revoke.queued", "&eRevoke queued until data loads.");
        } else if (result == AchievementService.Action.IGNORED) {
            msg(
                sender,
                "revoke.not-granted",
                "{achievement} is not granted in this run.",
                "achievement",
                plugin.getLanguage().achievementName(sender, achievement)
            );
        } else {
            msg(
                sender,
                "command.state-unavailable",
                "&cPlayer achievement state is unavailable."
            );
        }
        return true;
    }

    private boolean run(CommandSender sender, String[] args, String label) {
        if (!(sender instanceof Player)) {
            msg(
                sender,
                "run.player-only",
                "&cThis command can only be used by a player."
            );
            return true;
        }
        Player player = (Player) sender;
        String subcommand = args.length < 2 ? "info" : args[1].toLowerCase();

        if ("info".equals(subcommand)) {
            return runInfo(sender, label);
        }
        if ("next".equals(subcommand)) {
            if (plugin.getAchievements().isLoading(player)) {
                msg(sender, "run.loading-retry", "&eAchievements are still loading; try again.");
                return true;
            }
            if (plugin.getAchievements().isLoadFailed(player)) {
                msg(sender, "run.data-failed", "&cAchievement data failed to load.");
                return true;
            }
            if (!plugin.getAchievements().hasCompletedRun(player)) {
                msg(
                    sender,
                    "run.next-requires-completion",
                    "&cComplete your first full 16/16 run before starting another."
                );
                return true;
            }
            AchievementService.Action result = plugin.getAchievements().nextRun(player);
            if (result == AchievementService.Action.QUEUED) {
                msg(sender, "run.next-queued", "&eNext run is being created...");
            } else if (result == AchievementService.Action.APPLIED) {
                msg(sender, "run.next-created", "&aNext run created. Progress reset to 0/16.");
            } else if (result == AchievementService.Action.IGNORED) {
                msg(
                    sender,
                    "run.next-current-open",
                    "&cComplete or close the current run before starting another."
                );
            } else {
                msg(sender, "run.state-unavailable", "&cRun state is unavailable.");
            }
            return true;
        }
        if ("abandon".equals(subcommand)) {
            if (plugin.getAchievements().isLoading(player)) {
                msg(sender, "run.loading-retry", "&eAchievements are still loading; try again.");
                return true;
            }
            if (plugin.getAchievements().isLoadFailed(player)) {
                msg(sender, "run.data-failed", "&cAchievement data failed to load.");
                return true;
            }
            if (!plugin.getAchievements().hasCompletedRun(player)) {
                msg(
                    sender,
                    "run.abandon-requires-completion",
                    "&cComplete your first full 16/16 run before abandoning a race."
                );
                return true;
            }
            AchievementService.Action result = plugin.getAchievements().abandonRun(player);
            if (result == AchievementService.Action.QUEUED) {
                msg(sender, "run.abandon-queued", "&eAbandon request queued.");
            } else if (result == AchievementService.Action.APPLIED) {
                msg(
                    sender,
                    "run.abandon-applied",
                    "&eCurrent run abandoned. History was preserved."
                );
            } else if (result == AchievementService.Action.IGNORED) {
                msg(sender, "run.abandon-none", "&cThere is no active repeat run to abandon.");
            } else {
                msg(sender, "run.state-unavailable", "&cRun state is unavailable.");
            }
            return true;
        }
        if ("history".equals(subcommand)) {
            return runHistory(sender);
        }

        msg(
            sender,
            "run.usage",
            "Usage: /{label} run <info|next|abandon|history>",
            "label",
            label
        );
        return true;
    }

    private boolean runHistory(CommandSender sender) {
        if (!(sender instanceof Player)) {
            msg(
                sender,
                "history.player-only",
                "&cThis command can only be used by a player."
            );
            return true;
        }
        Player player = (Player) sender;
        final CommandSender output = sender;
        plugin.getAchievements().queryRunHistory(
            player.getUniqueId(),
            10,
            new RunQueryCallback<List<AchievementRun>>() {
                public void onResult(List<AchievementRun> runs) {
                    msg(output, "history.header", "&6Achievement run history");
                    if (runs.isEmpty()) {
                        msg(output, "history.empty", "&7No runs recorded.");
                        return;
                    }
                    for (AchievementRun run : runs) {
                        msg(
                            output,
                            "history.entry",
                            "&e#{run} &7{status}&f  {time}",
                            "run",
                            Integer.toString(run.getRunNumber()),
                            "status",
                            status(sender, run.getStatus()),
                            "time",
                            run.getDurationMs() == null
                                ? plugin.getLanguage().value(
                                    sender,
                                    "history.in-progress",
                                    "in progress"
                                )
                                : formatDuration(run.getDurationMs())
                        );
                    }
                }

                public void onFailure(Exception exception) {
                    msg(output, "history.failed", "&cCould not read run history.");
                }
            }
        );
        return true;
    }

    private boolean runInfo(CommandSender sender, String label) {
        if (!(sender instanceof Player)) {
            msg(
                sender,
                "info.player-only",
                "&cThis command can only be used by a player."
            );
            return true;
        }
        Player player = (Player) sender;
        if (plugin.getAchievements().isLoading(player)) {
            msg(sender, "info.loading", "&eAchievements are loading...");
            return true;
        }
        if (plugin.getAchievements().isLoadFailed(player)) {
            msg(
                sender,
                "info.load-failed",
                "&cAchievements failed to load. Please reconnect."
            );
            return true;
        }

        AchievementRun run = plugin.getAchievements().getCurrentRun(player);
        if (run == null) {
            msg(sender, "info.no-run", "&cNo current achievement run.");
            return true;
        }
        int completed = plugin.getAchievements().get(player).size();
        msg(
            sender,
            "info.header",
            "&6Achievement Run #{run} &7[{status}]",
            "run",
            Integer.toString(run.getRunNumber()),
            "status",
            status(sender, run.getStatus())
        );
        msg(
            sender,
            "info.progress",
            "&eProgress: {completed}/{total}&7  Elapsed: {elapsed}",
            "completed",
            Integer.toString(completed),
            "total",
            Integer.toString(plugin.getRegistry().all().size()),
            "elapsed",
            formatDuration(plugin.getAchievements().getCurrentElapsedMs(player))
        );
        if (run.getDurationMs() != null) {
            msg(
                sender,
                "info.final",
                "&aFinal time: {time}",
                "time",
                formatDuration(run.getDurationMs())
            );
        } else if (run.getStatus() == RunStatus.ACTIVE
            && run.getRunNumber() > 1
            && run.getStartedAtMs() != null) {
            int timeoutHours = plugin.getConfiguration().getInt("runs.repeat-timeout-hours", 12);
            if (timeoutHours > 0) {
                long deadline = run.getStartedAtMs() + timeoutHours * 60L * 60L * 1000L;
                msg(
                    sender,
                    "info.expires",
                    "&cThis run is deleted if unfinished after {time}.",
                    "time",
                    formatDuration(Math.max(0L, deadline - System.currentTimeMillis()))
                );
            }
        }

        for (RunProgressEntry entry : plugin.getAchievements().getCachedSplits(player)) {
            AchievementDefinition achievement = plugin.getRegistry().get(entry.getAchievementId());
            String name = achievement == null
                ? entry.getAchievementId()
                : plugin.getLanguage().achievementName(sender, achievement);
            msg(
                sender,
                "info.split",
                "&7{sequence}. &f{achievement}&e  +{split}&7  total {total}",
                "sequence",
                String.format(Locale.US, "%2d", entry.getSequence()),
                "achievement",
                name,
                "split",
                formatDuration(entry.getSplitMs()),
                "total",
                formatDuration(entry.getElapsedMs())
            );
        }
        return true;
    }

    private boolean personalBest(CommandSender sender, String[] args) {
        Player target;
        if (args.length >= 2) {
            target = plugin.getServer().getPlayer(args[1]);
            if (target == null) {
                msg(sender, "command.player-online", "&cPlayer must be online.");
                return true;
            }
            if (!allow(sender, "betaachievement.view.others")) {
                return true;
            }
        } else if (sender instanceof Player) {
            target = (Player) sender;
        } else {
            msg(
                sender,
                "pb.console-usage",
                "Console: /{label} pb <player>",
                "label",
                "ach"
            );
            return true;
        }

        final CommandSender output = sender;
        final String playerName = target.getName();
        plugin.getAchievements().queryPersonalBest(
            target.getUniqueId(),
            new RunQueryCallback<RunLeaderboardEntry>() {
                public void onResult(RunLeaderboardEntry best) {
                    if (best == null) {
                        msg(
                            output,
                            "pb.empty",
                            "&e{player} has no completed run.",
                            "player",
                            playerName
                        );
                    } else {
                        msg(
                            output,
                            "pb.result",
                            "&a{player} PB: {time}&7 (run #{run}, {source})",
                            "player",
                            playerName,
                            "time",
                            formatDuration(best.getDurationMs()),
                            "run",
                            Integer.toString(best.getRunNumber()),
                            "source",
                            best.getTimingSource()
                        );
                    }
                }

                public void onFailure(Exception exception) {
                    msg(output, "pb.failed", "&cCould not read personal best.");
                }
            }
        );
        return true;
    }

    private boolean leaderboard(CommandSender sender, String[] args) {
        int limit = 10;
        if (args.length >= 2) {
            try {
                limit = Math.max(1, Math.min(25, Integer.parseInt(args[1])));
            } catch (NumberFormatException exception) {
                msg(sender, "top.invalid-size", "&cLeaderboard size must be a number.");
                return true;
            }
        }

        final CommandSender output = sender;
        plugin.getAchievements().queryLeaderboard(
            limit,
            new RunQueryCallback<List<RunLeaderboardEntry>>() {
                public void onResult(List<RunLeaderboardEntry> entries) {
                    msg(
                        output,
                        "top.header",
                        "&6Unlimited Achievement Race - Top {count}",
                        "count",
                        Integer.toString(entries.size())
                    );
                    if (entries.isEmpty()) {
                        msg(output, "top.empty", "&7No completed runs yet.");
                        return;
                    }
                    int rank = 1;
                    for (RunLeaderboardEntry entry : entries) {
                        msg(
                            output,
                            "top.entry",
                            "&e{rank}. &f{player}&7  {time}  run #{run}  {source}",
                            "rank",
                            String.format(Locale.US, "%2d", rank++),
                            "player",
                            entry.getPlayerName(),
                            "time",
                            formatDuration(entry.getDurationMs()),
                            "run",
                            Integer.toString(entry.getRunNumber()),
                            "source",
                            entry.getTimingSource()
                        );
                    }
                }

                public void onFailure(Exception exception) {
                    msg(output, "top.failed", "&cCould not read leaderboard.");
                }
            }
        );
        return true;
    }

    private boolean debug(CommandSender sender, String[] args, String label) {
        if (!allow(sender, "betaachievement.debug")) {
            return true;
        }
        if (!(sender instanceof Player)) {
            msg(
                sender,
                "debug-command.player-only",
                "&cThis command can only be used by a player."
            );
            return true;
        }

        Player admin = (Player) sender;
        if (args.length < 2) {
            String current = plugin.getAchievements().getDebugTarget(admin);
            plugin.getAchievements().stopDebug(admin);
            if (current == null) {
                msg(sender, "debug-command.not-active", "Debug was not active.");
            } else {
                msg(
                    sender,
                    "debug-command.stopped",
                    "Debug stopped for {player}.",
                    "player",
                    current
                );
            }
            return true;
        }

        AchievementDefinition achievement = plugin.getRegistry().get(args[1]);
        if (achievement == null) {
            msg(sender, "command.unknown-achievement", "&cUnknown Beta achievement ID.");
            return true;
        }
        plugin.getAchievements().startDebug(admin, achievement.getId());
        msg(
            sender,
            "debug-command.started",
            "&eNow debugging {achievement} ({id}). Parent requirements ignored; completions are not saved. Stop with /{label} debug.",
            "achievement",
            plugin.getLanguage().achievementName(sender, achievement),
            "id",
            achievement.getId(),
            "label",
            label
        );
        return true;
    }

    private boolean check(CommandSender sender, String[] args) {
        if (args.length < 2) {
            msg(
                sender,
                "check.usage",
                "Usage: /{label} check <player>",
                "label",
                "ach"
            );
            return true;
        }
        if (!allow(sender, "betaachievement.view.others")) {
            return true;
        }
        Player target = plugin.getServer().getPlayer(args[1]);
        if (target == null) {
            msg(sender, "command.player-online", "&cPlayer must be online.");
            return true;
        }
        return showProgress(sender, target);
    }

    private boolean showProgress(CommandSender sender, Player target) {
        if (plugin.getAchievements().isLoading(target)) {
            msg(sender, "info.loading", "&eAchievements are loading...");
            return true;
        }
        if (plugin.getAchievements().isLoadFailed(target)) {
            msg(
                sender,
                "info.load-failed",
                "&cAchievements failed to load. Please reconnect."
            );
            return true;
        }

        AchievementRun run = plugin.getAchievements().getCurrentRun(target);
        Set<String> completed = plugin.getAchievements().get(target);
        int total = plugin.getRegistry().all().size();
        int percent = total == 0 ? 0 : completed.size() * 100 / total;
        msg(
            sender,
            "progress.header",
            "&6Beta 1.7.3 Achievements - {player}  Run #{run}",
            "player",
            target.getName(),
            "run",
            run == null ? "?" : Integer.toString(run.getRunNumber())
        );
        msg(
            sender,
            "progress.progress",
            "&eProgress: {completed}/{total} ({percent}%)&7  Elapsed: {elapsed}",
            "completed",
            Integer.toString(completed.size()),
            "total",
            Integer.toString(total),
            "percent",
            Integer.toString(percent),
            "elapsed",
            formatDuration(plugin.getAchievements().getCurrentElapsedMs(target))
        );
        msg(sender, "progress.completed", "&aCompleted:");
        for (AchievementDefinition achievement : plugin.getRegistry().all()) {
            if (completed.contains(achievement.getId())) {
                msg(
                    sender,
                    "progress.completed-entry",
                    "&a[+] {achievement}&7 ({id})",
                    "achievement",
                    plugin.getLanguage().achievementName(sender, achievement),
                    "id",
                    achievement.getId()
                );
            }
        }
        msg(sender, "progress.not-completed", "&cNot completed:");
        for (AchievementDefinition achievement : plugin.getRegistry().all()) {
            if (!completed.contains(achievement.getId())) {
                msg(
                    sender,
                    "progress.missing-entry",
                    "&c[-] {achievement}&7 ({id})",
                    "achievement",
                    plugin.getLanguage().achievementName(sender, achievement),
                    "id",
                    achievement.getId()
                );
            }
        }
        return true;
    }

    private boolean allow(CommandSender sender, String permission) {
        String legacyPermission = permission.replace("betaachievement.", "betaachievements.");
        if (sender.hasPermission("betaachievement.admin")
            || sender.hasPermission("betaachievements.admin")
            || sender.hasPermission(permission)
            || sender.hasPermission(legacyPermission)) {
            return true;
        }
        msg(sender, "command.no-permission", "&cYou do not have permission.");
        return false;
    }

    private void msg(
        CommandSender sender,
        String key,
        String fallback,
        String... replacements
    ) {
        plugin.getLanguage().send(sender, key, fallback, replacements);
    }

    private String status(CommandSender sender, RunStatus status) {
        return plugin.getLanguage().value(
            sender,
            "status." + status.name().toLowerCase(Locale.ROOT),
            status.name()
        );
    }

    private String formatDuration(long milliseconds) {
        long totalSeconds = Math.max(0L, milliseconds) / 1000L;
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        long millis = Math.max(0L, milliseconds) % 1000L;
        return String.format(Locale.US, "%02d:%02d:%02d.%03d", hours, minutes, seconds, millis);
    }
}
