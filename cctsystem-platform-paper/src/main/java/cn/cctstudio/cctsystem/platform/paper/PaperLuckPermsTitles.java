package cn.cctstudio.cctsystem.platform.paper;

import cn.cctstudio.cctsystem.membership.MembershipTier;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import org.bukkit.entity.Player;

final class PaperLuckPermsTitles {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private final LuckPerms luckPerms;

    PaperLuckPermsTitles(LuckPerms luckPerms) {
        this.luckPerms = luckPerms;
    }

    Component playerTitle(Player player) {
        return parse(rawPlayerTitle(player), "-");
    }

    Component playerIdentity(Player player) {
        String raw = rawPlayerTitle(player);
        String formatting = raw == null ? "" : raw.replace('§', '&').trim();
        if (formatting.isBlank() || plain(formatting).isBlank()) {
            return LEGACY.deserialize((formatting.isBlank() ? "&7" : formatting)
                + player.getName());
        }
        String prefix = normalized(raw, "-");
        return LEGACY.deserialize(prefix + " " + player.getName());
    }

    String playerIdentityLegacy(Player player) {
        return LEGACY.serialize(playerIdentity(player));
    }

    Component rankIdentity(String groupName, String playerName) {
        Group group = luckPerms == null || groupName == null
            ? null
            : luckPerms.getGroupManager().getGroup(groupName);
        String prefix = group == null ? null : group.getCachedData().getMetaData().getPrefix();
        String formatting = prefix == null ? "" : prefix.replace('§', '&').trim();
        if (formatting.isBlank() || plain(formatting).isBlank()) {
            return LEGACY.deserialize((formatting.isBlank() ? "&7" : formatting) + playerName);
        }
        return LEGACY.deserialize(normalized(prefix, "-") + " " + playerName);
    }

    String rankIdentityLegacy(String groupName, String playerName) {
        return LEGACY.serialize(rankIdentity(groupName, playerName));
    }

    String primaryGroup(Player player) {
        User user = luckPerms == null ? null : luckPerms.getUserManager().getUser(player.getUniqueId());
        return user == null ? "default" : user.getPrimaryGroup();
    }

    String prefixLegacy(Player player) {
        return legacyOrEmpty(rawPlayerTitle(player));
    }

    String prefixLegacy(String groupName) {
        Group group = luckPerms == null || groupName == null
            ? null : luckPerms.getGroupManager().getGroup(groupName);
        return legacyOrEmpty(group == null ? null : group.getCachedData().getMetaData().getPrefix());
    }

    String suffixLegacy(Player player) {
        User user = luckPerms == null ? null : luckPerms.getUserManager().getUser(player.getUniqueId());
        return legacyOrEmpty(user == null ? null : user.getCachedData().getMetaData().getSuffix());
    }

    String suffixLegacy(String groupName) {
        Group group = luckPerms == null || groupName == null
            ? null : luckPerms.getGroupManager().getGroup(groupName);
        return legacyOrEmpty(group == null ? null : group.getCachedData().getMetaData().getSuffix());
    }

    private String rawPlayerTitle(Player player) {
        User user = luckPerms == null ? null : luckPerms.getUserManager().getUser(player.getUniqueId());
        if (user == null) {
            return "&7[-]";
        }
        String custom = user.getCachedData().getMetaData().getMetaValue("cct-title");
        String prefix = custom == null || custom.isBlank()
            ? user.getCachedData().getMetaData().getPrefix()
            : custom;
        if (prefix == null || prefix.isBlank()) {
            Group group = luckPerms.getGroupManager().getGroup(user.getPrimaryGroup());
            prefix = group == null ? null : group.getCachedData().getMetaData().getPrefix();
        }
        return prefix;
    }

    Component tierTitle(MembershipTier tier) {
        Group group = luckPerms == null
            ? null
            : luckPerms.getGroupManager().getGroup(tier.luckPermsGroup());
        String prefix = group == null ? null : group.getCachedData().getMetaData().getPrefix();
        return parse(prefix, tier.displayName());
    }

    private static Component parse(String raw, String fallback) {
        return LEGACY.deserialize(normalized(raw, fallback));
    }

    private static String normalized(String raw, String fallback) {
        if (raw == null || raw.isBlank()) {
            return "-".equals(fallback) ? "&7-" : "&7[" + fallback + "]";
        }
        String normalized = raw.replace('§', '&').trim();
        if (plain(normalized).isBlank()) {
            return "-".equals(fallback)
                ? normalized + "-" : normalized + "[" + fallback + "]";
        }
        if (!plain(normalized).contains("[")) {
            normalized = "[" + normalized + "]";
        }
        return normalized;
    }

    private static String plain(String value) {
        return value.replaceAll("(?i)&[0-9A-FK-ORX]", "").toLowerCase(Locale.ROOT);
    }

    private static String legacyOrEmpty(String value) {
        return value == null ? "" : value.replace('§', '&');
    }
}
