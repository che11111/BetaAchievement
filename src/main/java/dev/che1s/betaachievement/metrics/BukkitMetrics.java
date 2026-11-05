package dev.che1s.betaachievement.metrics;

import org.bstats.MetricsBase;
import org.bstats.config.MetricsConfig;
import org.bstats.json.JsonObjectBuilder;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * bStats 3.x adapter for the old Bukkit API used by Project Poseidon.
 *
 * <p>The official bStats Bukkit adapter targets modern Bukkit and references
 * APIs that do not exist in Poseidon 1.1.8. This adapter keeps the official
 * core, submission format, schedule, and global opt-out behavior while using
 * only APIs available on this server.</p>
 */
public final class BukkitMetrics {
    private final MetricsBase metricsBase;

    public BukkitMetrics(Plugin plugin, int serviceId) throws IOException {
        File bStatsFolder = new File(plugin.getDataFolder().getParentFile(), "bStats");
        MetricsConfig config = new MetricsConfig(
            new File(bStatsFolder, "config.yml"),
            true
        );
        Logger logger = plugin.getServer().getLogger();
        int onlineMode = Bukkit.getOnlineMode() ? 1 : 0;
        String bukkitVersion = Bukkit.getVersion();
        String bukkitName = Bukkit.getName();
        String pluginVersion = plugin.getDescription().getVersion();

        metricsBase = new MetricsBase(
            "bukkit",
            config.getServerUUID(),
            serviceId,
            config.isEnabled(),
            builder -> appendPlatformData(
                builder,
                onlineMode,
                bukkitVersion,
                bukkitName
            ),
            builder -> builder.appendField("pluginVersion", pluginVersion),
            submitTask -> Bukkit.getScheduler().scheduleSyncDelayedTask(plugin, submitTask),
            plugin::isEnabled,
            (message, error) -> logger.log(Level.WARNING, message, error),
            logger::info,
            config.isLogErrorsEnabled(),
            config.isLogSentDataEnabled(),
            config.isLogResponseStatusTextEnabled(),
            false
        );
    }

    public void shutdown() {
        metricsBase.shutdown();
    }

    private static void appendPlatformData(
        JsonObjectBuilder builder,
        int onlineMode,
        String bukkitVersion,
        String bukkitName
    ) {
        builder.appendField("playerAmount", Bukkit.getOnlinePlayers().length);
        builder.appendField("onlineMode", onlineMode);
        builder.appendField("bukkitVersion", bukkitVersion);
        builder.appendField("bukkitName", bukkitName);
        builder.appendField("javaVersion", System.getProperty("java.version"));
        builder.appendField("osName", System.getProperty("os.name"));
        builder.appendField("osArch", System.getProperty("os.arch"));
        builder.appendField("osVersion", System.getProperty("os.version"));
        builder.appendField("coreCount", Runtime.getRuntime().availableProcessors());
    }
}
