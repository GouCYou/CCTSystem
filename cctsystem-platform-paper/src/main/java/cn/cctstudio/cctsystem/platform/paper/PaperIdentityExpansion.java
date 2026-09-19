package cn.cctstudio.cctsystem.platform.paper;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import java.util.regex.Pattern;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/** Stable public identity placeholders that never expose a nicked player's real rank or name. */
final class PaperIdentityExpansion extends PlaceholderExpansion {
    private final PaperNicknameService nicknames;

    PaperIdentityExpansion(PaperNicknameService nicknames) {
        this.nicknames = nicknames;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "cctsystem";
    }

    @Override
    public @NotNull String getAuthor() {
        return "CCTStudio";
    }

    @Override
    public @NotNull String getVersion() {
        return "1.0.0";
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer offlinePlayer, @NotNull String parameters) {
        if (!(offlinePlayer instanceof Player player)) return "";
        return switch (parameters.toLowerCase(java.util.Locale.ROOT)) {
            case "name" -> nicknames.displayName(player);
            case "identity" -> nicknames.identityLegacy(player);
            case "identity_no_world" -> withoutWorldPrefix(
                nicknames.identityLegacy(player), player.getWorld().getName()
            );
            case "prefix" -> nicknames.prefixLegacy(player);
            case "suffix" -> nicknames.suffixLegacy(player);
            case "rank" -> nicknames.displayRank(player);
            case "anonymous" -> Boolean.toString(nicknames.anonymous(player));
            default -> null;
        };
    }

    private static String withoutWorldPrefix(String identity, String worldName) {
        if (identity == null || identity.isBlank() || worldName == null || worldName.isBlank()) {
            return identity;
        }
        String colors = "(?:&[0-9A-FK-ORX])*";
        String worldTag = "(?i)" + colors + "\\[" + colors
            + Pattern.quote(worldName) + colors + "\\]\\s*";
        return identity.replaceFirst(worldTag, "");
    }
}
