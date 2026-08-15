package cn.cctstudio.cctsystem.platform.paper;

import cn.cctstudio.cctsystem.core.logging.CctLogger;
import java.io.File;
import java.lang.reflect.Method;
import java.util.Comparator;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/** Mirrors the source server's installed chat format for cross-server shouts. */
final class PaperChatStyleProvider {
    private final JavaPlugin plugin;
    private final PaperMessages messages;
    private final PaperLuckPermsTitles titles;
    private final PaperNicknameService nicknames;
    private final CctLogger logger;
    private final String serverId;
    private final Method placeholderMethod;
    private volatile boolean placeholderFailureLogged;

    PaperChatStyleProvider(
        JavaPlugin plugin,
        PaperMessages messages,
        PaperLuckPermsTitles titles,
        PaperNicknameService nicknames,
        CctLogger logger,
        String serverId
    ) {
        this.plugin = plugin;
        this.messages = messages;
        this.titles = titles;
        this.nicknames = nicknames;
        this.logger = logger;
        this.serverId = serverId;
        this.placeholderMethod = placeholderMethod();
    }

    String format(Player player, String rawMessage) {
        String body;
        if (enabled("CMI")) {
            body = cmi(player, rawMessage);
        } else if (enabled("ChatFormatter")) {
            body = chatFormatter(player, rawMessage);
        } else {
            body = defaultFormat(player, rawMessage);
        }
        String serverName = messages.raw("chat.servers." + serverId, serverId);
        String shoutPrefix = messages.raw(
            "chat.shout-prefix", "&f[&6喊话&f] &f[&b{server}&f] "
        )
            .replace("{server}", serverName);
        return spaceBracketGroups(shoutPrefix + body.stripLeading());
    }

    private String cmi(Player player, String message) {
        Plugin cmi = plugin.getServer().getPluginManager().getPlugin("CMI");
        if (cmi == null) return defaultFormat(player, message);
        File file = new File(cmi.getDataFolder(), "Settings/Chat.yml");
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        String format = yaml.getString("Chat.GeneralFormat", "{prefix}{displayName}{suffix}&7: &r{message}");
        String generalFormat = format;
        ConfigurationSection groups = yaml.getConfigurationSection("Chat.GroupFormat");
        if (groups != null) {
            format = groups.getKeys(false).stream()
                .filter(key -> player.hasPermission("cmi.chatgroup." + key))
                .max(Comparator.comparingInt(PaperChatStyleProvider::number))
                .map(key -> yaml.getString("Chat.GroupFormat." + key, generalFormat))
                .orElse(format);
        }
        format = addWorldToShoutFormat(format);
        format = placeholders(player, format);
        return replaceChatTokens(player, format, message);
    }

    private static String addWorldToShoutFormat(String format) {
        if (format.contains("%cctstudio_world_display%")
            || format.contains("%multiverse_world_alias%")) return format;
        String world = "&f[&a%cctstudio_world_display%&f] ";
        if (format.contains("%cctsystem_identity%")) {
            return format.replace("%cctsystem_identity%", world + "%cctsystem_identity%");
        }
        return world + format;
    }

    private String chatFormatter(Player player, String message) {
        Plugin formatter = plugin.getServer().getPluginManager().getPlugin("ChatFormatter");
        if (formatter == null) return defaultFormat(player, message);
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(
            new File(formatter.getDataFolder(), "config.yml")
        );
        String rank = nicknames.displayRank(player);
        String format = yaml.getString("format." + rank,
            yaml.getString("defaultFormat", "%vault_prefix%{displayname}%vault_suffix% &7: &r{message}"));
        String prefix = prefix(player);
        String suffix = suffix(player);
        format = format
            .replace("%vault_prefix%", prefix)
            .replace("%luckperms_prefix%", prefix)
            .replace("%vault_suffix%", suffix)
            .replace("%luckperms_suffix%", suffix);
        format = placeholders(player, format);
        return replaceChatTokens(player, format, message);
    }

    private String defaultFormat(Player player, String message) {
        return prefix(player) + nicknames.displayName(player) + suffix(player)
            + "&7: &r" + cleanMessage(message);
    }

    private String replaceChatTokens(Player player, String format, String message) {
        return format
            .replace("{prefix}", prefix(player))
            .replace("{displayName}", nicknames.displayName(player))
            .replace("{displayname}", nicknames.displayName(player))
            .replace("<displayname>", nicknames.displayName(player))
            .replace("{name}", nicknames.displayName(player))
            .replace("<name>", nicknames.displayName(player))
            .replace("{suffix}", suffix(player))
            .replace("{group}", nicknames.displayRank(player))
            .replace("{world}", player.getWorld().getName())
            .replace("{shout}", "")
            .replace("{shoutcolor}", "")
            .replace("{nicknameprefix}", "")
            .replace("{message}", cleanMessage(message))
            .replace("<message>", cleanMessage(message));
    }

    private String prefix(Player player) {
        return nicknames.profile(player).isPresent()
            ? titles.prefixLegacy(nicknames.displayRank(player))
            : titles.prefixLegacy(player);
    }

    private String suffix(Player player) {
        return nicknames.profile(player).isPresent()
            ? titles.suffixLegacy(nicknames.displayRank(player))
            : titles.suffixLegacy(player);
    }

    private String placeholders(Player player, String input) {
        String prepared = input.replace(
            "%multiverse_world_alias%", "%cctstudio_world_display%"
        );
        if (placeholderMethod == null) {
            return worldFallback(player, prepared);
        }
        try {
            return worldFallback(
                player,
                String.valueOf(placeholderMethod.invoke(null, player, prepared))
            );
        } catch (ReflectiveOperationException exception) {
            if (!placeholderFailureLogged) {
                placeholderFailureLogged = true;
                logger.warn("Unable to resolve chat placeholders", exception);
            }
            return worldFallback(player, prepared);
        }
    }

    private static String worldFallback(Player player, String value) {
        return value
            .replace("%multiverse_world_alias%", player.getWorld().getName())
            .replace("%cctstudio_world_display%", player.getWorld().getName());
    }

    private static String spaceBracketGroups(String value) {
        return value
            .replaceAll("](?=(?:&[0-9A-FK-ORX])*\\[)", "] ")
            .replaceAll("[ \\t]{2,}", " ")
            .strip();
    }

    private boolean enabled(String pluginName) {
        return plugin.getServer().getPluginManager().isPluginEnabled(pluginName);
    }

    private static String cleanMessage(String message) {
        return message.replace('§', ' ')
            .replaceAll("(?i)&[0-9A-FK-ORX]", "")
            .strip();
    }

    private static int number(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return Integer.MIN_VALUE;
        }
    }

    private static Method placeholderMethod() {
        try {
            return Class.forName("me.clip.placeholderapi.PlaceholderAPI")
                .getMethod("setPlaceholders", OfflinePlayer.class, String.class);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }
}
