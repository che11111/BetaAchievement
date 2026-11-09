package dev.che1s.betaachievement.lang;

import dev.che1s.betaachievement.BetaAchievement;
import dev.che1s.betaachievement.achievement.AchievementDefinition;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.util.config.Configuration;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

/**
 * Loads fixed English in-game messages and console messages in the server's
 * configured language. Beta 1.7.3 clients cannot render Chinese text.
 */
public final class LanguageService {
    private final BetaAchievement plugin;
    private Configuration game;
    private Configuration console;
    private String gamePrefix;
    private String consolePrefix;
    private boolean chineseConsole;

    public LanguageService(BetaAchievement plugin) {
        this.plugin = plugin;
    }

    public void load() {
        File folder = new File(plugin.getDataFolder(), "lang");
        if (!folder.exists() && !folder.mkdirs()) {
            plugin.getServer().getLogger().warning(
                "Could not create language directory: " + folder.getAbsolutePath()
            );
        }

        File gameFile = ensureFile(folder, "game.yml");
        File consoleZh = ensureFile(folder, "console-zh.yml");
        File consoleEn = ensureFile(folder, "console-en.yml");
        chineseConsole = isChinese(resolvedConsoleLanguage(
            plugin.getConfiguration().getString("language.console", "auto")
        ));

        game = new Configuration(gameFile);
        game.load();
        console = new Configuration(chineseConsole ? consoleZh : consoleEn);
        console.load();
        gamePrefix = color(game.getString("prefix", ""));
        consolePrefix = color(console.getString("prefix", "[BetaAchievement]"));
    }

    public String game(String key, String fallback, String... replacements) {
        String value = game == null ? fallback : game.getString(key, fallback);
        return gamePrefix + color(replace(value, replacements));
    }

    public String gameValue(String key, String fallback, String... replacements) {
        String value = game == null ? fallback : game.getString(key, fallback);
        return color(replace(value, replacements));
    }

    public String consoleValue(String key, String fallback, String... replacements) {
        String value = console == null ? fallback : console.getString(key, fallback);
        return replace(value, replacements);
    }

    public String console(String key, String fallback, String... replacements) {
        return consolePrefix + " " + color(consoleValue(key, fallback, replacements));
    }

    public void send(CommandSender sender, String key, String fallback, String... replacements) {
        if (sender instanceof Player) {
            sender.sendMessage(game(key, fallback, replacements));
        } else {
            sender.sendMessage(console(key, fallback, replacements));
        }
    }

    public String value(CommandSender sender, String key, String fallback, String... replacements) {
        return sender instanceof Player
            ? gameValue(key, fallback, replacements)
            : consoleValue(key, fallback, replacements);
    }

    public void info(String key, String fallback, String... replacements) {
        plugin.getServer().getLogger().info(plain(console(key, fallback, replacements)));
    }

    public void warning(String key, String fallback, String... replacements) {
        plugin.getServer().getLogger().warning(plain(console(key, fallback, replacements)));
    }

    public void severe(String key, String fallback, String... replacements) {
        plugin.getServer().getLogger().severe(plain(console(key, fallback, replacements)));
    }

    public String achievementName(CommandSender sender, AchievementDefinition achievement) {
        return sender instanceof Player
            ? gameValue(
                "achievements." + achievement.getId() + ".name",
                achievement.getName()
            )
            : achievementName(achievement);
    }

    public String achievementName(AchievementDefinition achievement) {
        if (chineseConsole) {
            return consoleValue(
                "achievements." + achievement.getId() + ".name",
                achievement.getChineseName()
            );
        }
        return consoleValue(
            "achievements." + achievement.getId() + ".name",
            achievement.getName()
        );
    }

    public boolean isChineseConsole() {
        return chineseConsole;
    }

    static String resolvedConsoleLanguage(String configured) {
        if (configured == null || configured.trim().length() == 0
            || "auto".equalsIgnoreCase(configured.trim())) {
            return System.getProperty(
                "user.language",
                Locale.getDefault().getLanguage()
            );
        }
        return configured.trim();
    }

    static boolean isChinese(String language) {
        String value = language == null ? "en" : language.toLowerCase(Locale.ROOT);
        return value.startsWith("zh")
            || value.contains("chinese")
            || value.contains("中文");
    }

    private String replace(String value, String... replacements) {
        String result = value == null ? "" : value;
        if (replacements != null) {
            for (int index = 0; index + 1 < replacements.length; index += 2) {
                result = result.replace(
                    "{" + replacements[index] + "}",
                    replacements[index + 1] == null ? "" : replacements[index + 1]
                );
            }
        }
        return result;
    }

    private String color(String value) {
        String input = value == null ? "" : value;
        StringBuilder output = new StringBuilder();
        for (int index = 0; index < input.length(); index++) {
            char character = input.charAt(index);
            if (character == '&' && index + 1 < input.length()) {
                int code = Character.digit(input.charAt(index + 1), 16);
                if (code >= 0) {
                    output.append(ChatColor.getByCode(code));
                    index++;
                    continue;
                }
            }
            output.append(character);
        }
        return output.toString();
    }

    private String plain(String value) {
        StringBuilder output = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '\u00A7') {
                if (index + 1 < value.length()) {
                    index++;
                }
            } else {
                output.append(character);
            }
        }
        return output.toString();
    }

    private File ensureFile(File folder, String name) {
        File target = new File(folder, name);
        if (target.isFile()) {
            return target;
        }

        InputStream input = null;
        OutputStream output = null;
        try {
            input = plugin.getClass().getResourceAsStream("/lang/" + name);
            if (input != null) {
                output = new FileOutputStream(target);
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    output.write(buffer, 0, read);
                }
            }
        } catch (IOException exception) {
            plugin.getServer().getLogger().warning(
                "Could not create lang/" + name + ": " + exception.getMessage()
            );
        } finally {
            close(input);
            close(output);
        }
        return target;
    }

    private void close(java.io.Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
            // Nothing useful can be done while copying bundled resources.
        }
    }
}
