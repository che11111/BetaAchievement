package dev.che1s.betaachievement;

import dev.che1s.betaachievement.achievement.AchievementRegistry;
import dev.che1s.betaachievement.achievement.AchievementService;
import dev.che1s.betaachievement.api.BetaAchievementApi;
import dev.che1s.betaachievement.command.AchievementCommand;
import dev.che1s.betaachievement.database.DatabaseManager;
import dev.che1s.betaachievement.listener.AchievementListener;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public final class BetaAchievement extends JavaPlugin {
    private AchievementRegistry registry;
    private DatabaseManager database;
    private AchievementService achievements;
    private BetaAchievementApi api;

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

        registry = new AchievementRegistry();
        database = new DatabaseManager(this);
        achievements = new AchievementService(this, registry, database);
        api = new BetaAchievementApi(this);

        AchievementCommand command = new AchievementCommand(this);
        PluginCommand achievementCommand = getCommand("achievement");
        if (achievementCommand == null) {
            getServer().getLogger().severe("Could not register plugin commands.");
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
                getServer().getLogger().info(
                    "Enabled with all 16 Beta 1.7.3 achievement definitions."
                );
            }
        }, new Runnable() {
            public void run() {
                getServer().getLogger().severe(
                    "BetaAchievement is disabled: persistent storage must be available."
                );
                getServer().getPluginManager().disablePlugin(BetaAchievement.this);
            }
        });
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
            getServer().getLogger().warning(
                "Could not create " + name + ": " + exception.getMessage()
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
}
