package dev.che1s.betaachievement;

import dev.che1s.betaachievement.achievement.AchievementRegistry;
import dev.che1s.betaachievement.achievement.AchievementService;
import dev.che1s.betaachievement.api.BetaAchievementApi;
import dev.che1s.betaachievement.command.AchievementCommand;
import dev.che1s.betaachievement.database.DatabaseManager;
import dev.che1s.betaachievement.lang.LanguageService;
import dev.che1s.betaachievement.listener.AchievementListener;
import dev.che1s.betaachievement.metrics.BukkitMetrics;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public final class BetaAchievement extends JavaPlugin {
    private static final int BSTATS_PLUGIN_ID = 35134;

    private AchievementRegistry registry;
    private DatabaseManager database;
    private AchievementService achievements;
    private BetaAchievementApi api;
    private BukkitMetrics metrics;
    private LanguageService language;

    @Override
    public void onEnable() {
        copyDefaultResources();
        try {
            getConfiguration().load();
        } catch (Exception exception) {
            getServer().getLogger().severe("Could not load config.yml: " + exception.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        language = new LanguageService(this);
        language.load();

        startMetrics();

        registry = new AchievementRegistry();
        database = new DatabaseManager(this);
        achievements = new AchievementService(this, registry, database);
        api = new BetaAchievementApi(this);

        AchievementCommand command = new AchievementCommand(this);
        PluginCommand achievementCommand = getCommand("achievement");
        if (achievementCommand == null) {
            language.severe("startup.command-register-failed", "Could not register plugin commands.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        achievementCommand.setExecutor(command);

        database.startAsync(new Runnable() {
            public void run() {
                if (!isEnabled()) {
                    return;
                }
                getServer().getPluginManager().registerEvents(new AchievementListener(BetaAchievement.this), BetaAchievement.this);
                achievements.startTasks();
                for (Player player : getServer().getOnlinePlayers()) {
                    achievements.load(player);
                }
                language.info(
                    "startup.ready",
                    "Enabled with all 16 Beta 1.7.3 achievement definitions."
                );
            }
        }, new Runnable() {
            public void run() {
                language.severe(
                    "startup.storage-required",
                    "BetaAchievement is disabled: persistent storage must be available."
                );
                getServer().getPluginManager().disablePlugin(BetaAchievement.this);
            }
        });
    }

    private void startMetrics() {
        if (!getConfiguration().getBoolean("bstats.enabled", true)) {
            return;
        }
        try {
            metrics = new BukkitMetrics(this, BSTATS_PLUGIN_ID);
        } catch (Exception | LinkageError exception) {
            metrics = null;
            language.warning(
                "startup.bstats-failed",
                "Could not initialize bStats: {error}",
                "error",
                exception.getMessage()
            );
        }
    }

    private void copyDefaultResources() {
        getDataFolder().mkdirs();
        copyResource("config.yml");
    }

    private void copyResource(String name) {
        File output = new File(getDataFolder(), name);
        if (output.exists()) {
            return;
        }
        InputStream input = getClassLoader().getResourceAsStream(name);
        if (input == null) {
            return;
        }
        try {
            OutputStream outputStream = new FileOutputStream(output);
            try {
                byte[] buffer = new byte[4096];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, read);
                }
            } finally {
                outputStream.close();
            }
        } catch (IOException exception) {
            String message = language == null
                ? "Could not create " + name + ": " + exception.getMessage()
                : language.console(
                    "startup.resource-create-failed",
                    "Could not create {file}: {error}",
                    "file",
                    name,
                    "error",
                    exception.getMessage()
                );
            getServer().getLogger().warning(
                message
            );
        } finally {
            try {
                input.close();
            } catch (IOException ignored) {
                // Nothing useful can be done while copying bundled resources.
            }
        }
    }

    @Override
    public void onDisable() {
        if (metrics != null) {
            metrics.shutdown();
        }
        if (achievements != null) {
            achievements.shutdown();
        }
        if (database != null) {
            database.close();
        }
    }

    public boolean isReady() {
        return database != null && database.isReady();
    }

    public AchievementRegistry getRegistry() {
        return registry;
    }

    public AchievementService getAchievements() {
        return achievements;
    }

    public BetaAchievementApi getApi() {
        return api;
    }

    public LanguageService getLanguage() {
        return language;
    }
}
