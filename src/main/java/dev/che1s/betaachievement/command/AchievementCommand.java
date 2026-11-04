package dev.che1s.betaachievement.command;

import dev.che1s.betaachievement.BetaAchievement;
import dev.che1s.betaachievement.achievement.AchievementDefinition;
import dev.che1s.betaachievement.achievement.AchievementService;
import dev.che1s.betaachievement.run.AchievementRun;
import dev.che1s.betaachievement.run.RunLeaderboardEntry;
import dev.che1s.betaachievement.run.RunProgressEntry;
import dev.che1s.betaachievement.run.RunQueryCallback;
import dev.che1s.betaachievement.run.RunStatus;
import org.bukkit.ChatColor;
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
            sender.sendMessage(ChatColor.YELLOW + "Achievement storage is still initializing.");
            return true;
        }
        if (args.length == 0) {
            if (sender instanceof Player) {
                return showProgress(sender, (Player) sender);
            }
            sender.sendMessage(
                "Console: /" + label + " check <player>"
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
            sender.sendMessage("/" + label + " <grant|grantall|revoke|reset> <player> [achievement]");
            return true;
        }

        Player target = plugin.getServer().getPlayer(args[1]);
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "Player must be online.");
            return true;
        }
        if (plugin.getAchievements().isLoadFailed(target)) {
            sender.sendMessage(ChatColor.RED + target.getName() + "'s achievement data failed to load.");
            return true;
        }

        if ("reset".equals(action)) {
            if (!allow(sender, "betaachievement.reset")) {
                return true;
            }
            AchievementService.Action result = plugin.getAchievements().reset(target);
            if (result == AchievementService.Action.QUEUED) {
                sender.sendMessage(ChatColor.YELLOW + "Reset and next run queued.");
            } else if (result == AchievementService.Action.APPLIED) {
                sender.sendMessage("Reset " + target.getName() + "; history was preserved.");
            } else {
                sender.sendMessage(ChatColor.RED + "Player achievement state is unavailable.");
            }
            return true;
        }
        if ("grantall".equals(action)) {
            if (!allow(sender, "betaachievement.grantall")) {
                return true;
            }
            int count = plugin.getAchievements().grantAll(target);
            if (count < 0) {
                sender.sendMessage(ChatColor.YELLOW + "Grant-all queued until data loads.");
            } else if (count == 0) {
                sender.sendMessage("No missing achievements were granted.");
            } else {
                sender.sendMessage(
                    "Granted " + count + " achievement(s) to " + target.getName()
                        + ". This run is invalid for rankings."
                );
            }
            return true;
        }
        if (!"grant".equals(action) && !"revoke".equals(action)) {
            sender.sendMessage(
                ChatColor.RED
                    + "Unknown action. Use run, pb, top, grant, grantall, revoke, reset or debug."
            );
            return true;
        }
        if (args.length < 3) {
            sender.sendMessage("Missing achievement ID.");
            return true;
        }

        AchievementDefinition achievement = plugin.getRegistry().get(args[2]);
        if (achievement == null) {
            sender.sendMessage("Unknown Beta achievement ID.");
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
                sender.sendMessage(
                    "Granted " + achievement.getName() + ". Current run is invalid for rankings."
                );
            } else if (result == AchievementService.Action.QUEUED) {
                sender.sendMessage(ChatColor.YELLOW + "Grant queued until data loads.");
            } else if (result == AchievementService.Action.IGNORED) {
                sender.sendMessage(achievement.getName() + " is already granted in this run.");
            } else {
                sender.sendMessage(ChatColor.RED + "Player achievement state is unavailable.");
            }
            return true;
        }

        if (!allow(sender, "betaachievement.revoke")) {
            return true;
        }
        AchievementService.Action result = plugin.getAchievements().revoke(target, achievement.getId());
        if (result == AchievementService.Action.APPLIED) {
            sender.sendMessage(
                "Revoked " + achievement.getName() + ". Current run is invalid for rankings."
            );
        } else if (result == AchievementService.Action.QUEUED) {
            sender.sendMessage(ChatColor.YELLOW + "Revoke queued until data loads.");
        } else if (result == AchievementService.Action.IGNORED) {
            sender.sendMessage(achievement.getName() + " is not granted in this run.");
        } else {
            sender.sendMessage(ChatColor.RED + "Player achievement state is unavailable.");
        }
        return true;
    }

    private boolean run(CommandSender sender, String[] args, String label) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Run commands are player-only.");
            return true;
        }
        Player player = (Player) sender;
        String subcommand = args.length < 2 ? "info" : args[1].toLowerCase();

        if ("info".equals(subcommand)) {
            return runInfo(sender, label);
        }
        if ("next".equals(subcommand)) {
            if (plugin.getAchievements().isLoading(player)) {
                sender.sendMessage(ChatColor.YELLOW + "Achievements are still loading; try again.");
                return true;
            }
            if (plugin.getAchievements().isLoadFailed(player)) {
                sender.sendMessage(ChatColor.RED + "Achievement data failed to load.");
                return true;
            }
            if (!plugin.getAchievements().hasCompletedRun(player)) {
                sender.sendMessage(
                    ChatColor.RED + "Complete your first full 16/16 run before starting another."
                );
                return true;
            }
            AchievementService.Action result = plugin.getAchievements().nextRun(player);
            if (result == AchievementService.Action.QUEUED) {
                sender.sendMessage(ChatColor.YELLOW + "Next run is being created...");
            } else if (result == AchievementService.Action.APPLIED) {
                sender.sendMessage(ChatColor.GREEN + "Next run created. Progress reset to 0/16.");
            } else if (result == AchievementService.Action.IGNORED) {
                sender.sendMessage(
                    ChatColor.RED + "Complete or close the current run before starting another."
                );
            } else {
                sender.sendMessage(ChatColor.RED + "Run state is unavailable.");
            }
            return true;
        }
        if ("abandon".equals(subcommand)) {
            if (plugin.getAchievements().isLoading(player)) {
                sender.sendMessage(ChatColor.YELLOW + "Achievements are still loading; try again.");
                return true;
            }
            if (plugin.getAchievements().isLoadFailed(player)) {
                sender.sendMessage(ChatColor.RED + "Achievement data failed to load.");
                return true;
            }
            if (!plugin.getAchievements().hasCompletedRun(player)) {
                sender.sendMessage(
                    ChatColor.RED + "Complete your first full 16/16 run before abandoning a race."
                );
                return true;
            }
            AchievementService.Action result = plugin.getAchievements().abandonRun(player);
            if (result == AchievementService.Action.QUEUED) {
                sender.sendMessage(ChatColor.YELLOW + "Abandon request queued.");
            } else if (result == AchievementService.Action.APPLIED) {
                sender.sendMessage(ChatColor.YELLOW + "Current run abandoned. History was preserved.");
            } else if (result == AchievementService.Action.IGNORED) {
                sender.sendMessage(ChatColor.RED + "There is no active repeat run to abandon.");
            } else {
                sender.sendMessage(ChatColor.RED + "Run state is unavailable.");
            }
            return true;
        }
        if ("history".equals(subcommand)) {
            return runHistory(sender);
        }

        sender.sendMessage("Usage: /" + label + " run <info|next|abandon|history>");
        return true;
    }

    private boolean runHistory(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Run history is player-only.");
            return true;
        }
        Player player = (Player) sender;
        final CommandSender output = sender;
        plugin.getAchievements().queryRunHistory(
            player.getUniqueId(),
            10,
            new RunQueryCallback<List<AchievementRun>>() {
                public void onResult(List<AchievementRun> runs) {
                    output.sendMessage(ChatColor.GOLD + "Achievement run history");
                    if (runs.isEmpty()) {
                        output.sendMessage(ChatColor.GRAY + "No runs recorded.");
                        return;
                    }
                    for (AchievementRun run : runs) {
                        output.sendMessage(
                            ChatColor.YELLOW + "#" + run.getRunNumber() + " "
                                + ChatColor.GRAY + run.getStatus()
                                + ChatColor.WHITE + "  "
                                + (run.getDurationMs() == null
                                    ? "in progress"
                                    : formatDuration(run.getDurationMs()))
                        );
                    }
                }

                public void onFailure(Exception exception) {
                    output.sendMessage(ChatColor.RED + "Could not read run history.");
                }
            }
        );
        return true;
    }

    private boolean runInfo(CommandSender sender, String label) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("Console: /" + label + " run is player-only.");
            return true;
        }
        Player player = (Player) sender;
        if (plugin.getAchievements().isLoading(player)) {
            sender.sendMessage(ChatColor.YELLOW + "Achievements are loading...");
            return true;
        }
        if (plugin.getAchievements().isLoadFailed(player)) {
            sender.sendMessage(ChatColor.RED + "Achievements failed to load. Please reconnect.");
            return true;
        }

        AchievementRun run = plugin.getAchievements().getCurrentRun(player);
        if (run == null) {
            sender.sendMessage(ChatColor.RED + "No current achievement run.");
            return true;
        }
        int completed = plugin.getAchievements().get(player).size();
        sender.sendMessage(
            ChatColor.GOLD + "Achievement Run #" + run.getRunNumber()
                + ChatColor.GRAY + " [" + run.getStatus() + "]"
        );
        sender.sendMessage(
            ChatColor.YELLOW + "Progress: " + completed + "/16"
                + ChatColor.GRAY + "  Elapsed: "
                + formatDuration(plugin.getAchievements().getCurrentElapsedMs(player))
        );
        if (run.getDurationMs() != null) {
            sender.sendMessage(
                ChatColor.GREEN + "Final time: " + formatDuration(run.getDurationMs())
            );
        } else if (run.getStatus() == RunStatus.ACTIVE
            && run.getRunNumber() > 1
            && run.getStartedAtMs() != null) {
            int timeoutHours = plugin.getConfiguration().getInt("runs.repeat-timeout-hours", 12);
            if (timeoutHours > 0) {
                long deadline = run.getStartedAtMs() + timeoutHours * 60L * 60L * 1000L;
                sender.sendMessage(
                    ChatColor.RED + "This run is deleted if unfinished after "
                        + formatDuration(Math.max(0L, deadline - System.currentTimeMillis()))
                );
            }
        }

        for (RunProgressEntry entry : plugin.getAchievements().getCachedSplits(player)) {
            AchievementDefinition achievement = plugin.getRegistry().get(entry.getAchievementId());
            String name = achievement == null ? entry.getAchievementId() : achievement.getName();
            sender.sendMessage(
                ChatColor.GRAY + String.format(Locale.US, "%2d. ", entry.getSequence())
                    + ChatColor.WHITE + name
                    + ChatColor.YELLOW + "  +" + formatDuration(entry.getSplitMs())
                    + ChatColor.GRAY + "  total " + formatDuration(entry.getElapsedMs())
            );
        }
        return true;
    }

    private boolean personalBest(CommandSender sender, String[] args) {
        Player target;
        if (args.length >= 2) {
            target = plugin.getServer().getPlayer(args[1]);
            if (target == null) {
                sender.sendMessage(ChatColor.RED + "Player must be online.");
                return true;
            }
            if (!allow(sender, "betaachievement.view.others")) {
                return true;
            }
        } else if (sender instanceof Player) {
            target = (Player) sender;
        } else {
            sender.sendMessage("Console: /ach pb <player>");
            return true;
        }

        final CommandSender output = sender;
        final String playerName = target.getName();
        plugin.getAchievements().queryPersonalBest(
            target.getUniqueId(),
            new RunQueryCallback<RunLeaderboardEntry>() {
                public void onResult(RunLeaderboardEntry best) {
                    if (best == null) {
                        output.sendMessage(ChatColor.YELLOW + playerName + " has no completed run.");
                    } else {
                        output.sendMessage(
                            ChatColor.GREEN + playerName + " PB: "
                                + formatDuration(best.getDurationMs())
                                + ChatColor.GRAY + " (run #" + best.getRunNumber()
                                + ", " + best.getTimingSource() + ")"
                        );
                    }
                }

                public void onFailure(Exception exception) {
                    output.sendMessage(ChatColor.RED + "Could not read personal best.");
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
                sender.sendMessage(ChatColor.RED + "Leaderboard size must be a number.");
                return true;
            }
        }

        final CommandSender output = sender;
        plugin.getAchievements().queryLeaderboard(
            limit,
            new RunQueryCallback<List<RunLeaderboardEntry>>() {
                public void onResult(List<RunLeaderboardEntry> entries) {
                    output.sendMessage(ChatColor.GOLD + "Unlimited Achievement Race — Top "
                        + entries.size());
                    if (entries.isEmpty()) {
                        output.sendMessage(ChatColor.GRAY + "No completed runs yet.");
                        return;
                    }
                    int rank = 1;
                    for (RunLeaderboardEntry entry : entries) {
                        output.sendMessage(
                            ChatColor.YELLOW + String.format(Locale.US, "%2d. ", rank++)
                                + ChatColor.WHITE + entry.getPlayerName()
                                + ChatColor.GRAY + "  " + formatDuration(entry.getDurationMs())
                                + "  run #" + entry.getRunNumber()
                                + "  " + entry.getTimingSource()
                        );
                    }
                }

                public void onFailure(Exception exception) {
                    output.sendMessage(ChatColor.RED + "Could not read leaderboard.");
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
            sender.sendMessage(ChatColor.RED + "Debug mode is player-only.");
            return true;
        }

        Player admin = (Player) sender;
        if (args.length < 2) {
            String current = plugin.getAchievements().getDebugTarget(admin);
            plugin.getAchievements().stopDebug(admin);
            sender.sendMessage(
                current == null ? "Debug was not active." : "Debug stopped for " + current + "."
            );
            return true;
        }

        AchievementDefinition achievement = plugin.getRegistry().get(args[1]);
        if (achievement == null) {
            sender.sendMessage("Unknown Beta achievement ID.");
            return true;
        }
        plugin.getAchievements().startDebug(admin, achievement.getId());
        sender.sendMessage(
            ChatColor.YELLOW + "Now debugging " + achievement.getName() + " (" + achievement.getId()
                + "). Parent requirements ignored; completions are not saved. Stop with /"
                + label + " debug."
        );
        return true;
    }

    private boolean check(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("Usage: /ach check <player>");
            return true;
        }
        if (!allow(sender, "betaachievement.view.others")) {
            return true;
        }
        Player target = plugin.getServer().getPlayer(args[1]);
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "Player must be online.");
            return true;
        }
        return showProgress(sender, target);
    }

    private boolean showProgress(CommandSender sender, Player target) {
        if (plugin.getAchievements().isLoading(target)) {
            sender.sendMessage(ChatColor.YELLOW + "Achievements are loading...");
            return true;
        }
        if (plugin.getAchievements().isLoadFailed(target)) {
            sender.sendMessage(ChatColor.RED + "Achievements failed to load. Please reconnect.");
            return true;
        }

        AchievementRun run = plugin.getAchievements().getCurrentRun(target);
        Set<String> completed = plugin.getAchievements().get(target);
        int total = plugin.getRegistry().all().size();
        int percent = total == 0 ? 0 : completed.size() * 100 / total;
        sender.sendMessage(
            ChatColor.GOLD + "Beta 1.7.3 Achievements - " + target.getName()
                + "  Run #" + (run == null ? "?" : run.getRunNumber())
        );
        sender.sendMessage(
            ChatColor.YELLOW + "Progress: " + completed.size() + "/" + total + " (" + percent + "%)"
                + ChatColor.GRAY + "  Elapsed: "
                + formatDuration(plugin.getAchievements().getCurrentElapsedMs(target))
        );
        sender.sendMessage(ChatColor.GREEN + "Completed:");
        for (AchievementDefinition achievement : plugin.getRegistry().all()) {
            if (completed.contains(achievement.getId())) {
                sender.sendMessage(
                    ChatColor.GREEN + "[+] " + achievement.getName()
                        + ChatColor.GRAY + " (" + achievement.getId() + ")"
                );
            }
        }
        sender.sendMessage(ChatColor.RED + "Not completed:");
        for (AchievementDefinition achievement : plugin.getRegistry().all()) {
            if (!completed.contains(achievement.getId())) {
                sender.sendMessage(
                    ChatColor.RED + "[-] " + achievement.getName()
                        + ChatColor.GRAY + " (" + achievement.getId() + ")"
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
        String message = plugin.getConfiguration().getString(
            "messages.no-permission",
            "&cYou don't have permission."
        );
        sender.sendMessage(colour(message));
        return false;
    }

    private String colour(String input) {
        StringBuilder output = new StringBuilder();
        for (int index = 0; index < input.length(); index++) {
            if (input.charAt(index) == '&' && index + 1 < input.length()) {
                int code = Character.digit(input.charAt(index + 1), 16);
                if (code >= 0) {
                    output.append(ChatColor.getByCode(code));
                    index++;
                    continue;
                }
            }
            output.append(input.charAt(index));
        }
        return output.toString();
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
