package cn.cctstudio.cctsystem.platform.paper;

import cn.cctstudio.cctsystem.core.logging.CctLogger;
import java.lang.reflect.Method;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** Optional TAB 6 adapter. Keeps anonymous profile, tab list and nametag in one identity. */
final class PaperTabIntegration {
    private final CctLogger logger;
    private final Object api;
    private final Object tabList;
    private final Object nameTags;
    private final Method getPlayer;
    private final Method isLoaded;
    private final Method setExpectedProfileName;
    private final Method tabSetPrefix;
    private final Method tabSetName;
    private final Method tabSetSuffix;
    private final Method tabOriginalPrefix;
    private final Method tabOriginalSuffix;
    private final Method tagSetPrefix;
    private final Method tagSetSuffix;
    private final Method tagOriginalPrefix;
    private final Method tagOriginalSuffix;
    private volatile boolean failureLogged;

    PaperTabIntegration(JavaPlugin plugin, CctLogger logger) {
        this.logger = logger;
        Object foundApi = null;
        Object foundTabList = null;
        Object foundNameTags = null;
        Method foundGetPlayer = null;
        Method foundIsLoaded = null;
        Method foundExpectedName = null;
        Method foundTabSetPrefix = null;
        Method foundTabSetName = null;
        Method foundTabSetSuffix = null;
        Method foundTabOriginalPrefix = null;
        Method foundTabOriginalSuffix = null;
        Method foundTagSetPrefix = null;
        Method foundTagSetSuffix = null;
        Method foundTagOriginalPrefix = null;
        Method foundTagOriginalSuffix = null;
        if (plugin.getServer().getPluginManager().isPluginEnabled("TAB")) {
            try {
                Class<?> apiType = Class.forName("me.neznamy.tab.api.TabAPI");
                Class<?> playerType = Class.forName("me.neznamy.tab.api.TabPlayer");
                Class<?> tabListType = Class.forName(
                    "me.neznamy.tab.api.tablist.TabListFormatManager"
                );
                Class<?> nameTagType = Class.forName(
                    "me.neznamy.tab.api.nametag.NameTagManager"
                );
                foundApi = apiType.getMethod("getInstance").invoke(null);
                foundGetPlayer = apiType.getMethod("getPlayer", UUID.class);
                foundTabList = apiType.getMethod("getTabListFormatManager").invoke(foundApi);
                foundNameTags = apiType.getMethod("getNameTagManager").invoke(foundApi);
                foundIsLoaded = playerType.getMethod("isLoaded");
                foundExpectedName = playerType.getMethod(
                    "setExpectedProfileName", String.class
                );
                foundTabSetPrefix = tabListType.getMethod(
                    "setPrefix", playerType, String.class
                );
                foundTabSetName = tabListType.getMethod(
                    "setName", playerType, String.class
                );
                foundTabSetSuffix = tabListType.getMethod(
                    "setSuffix", playerType, String.class
                );
                foundTabOriginalPrefix = tabListType.getMethod(
                    "getOriginalRawPrefix", playerType
                );
                foundTabOriginalSuffix = tabListType.getMethod(
                    "getOriginalRawSuffix", playerType
                );
                foundTagSetPrefix = nameTagType.getMethod(
                    "setPrefix", playerType, String.class
                );
                foundTagSetSuffix = nameTagType.getMethod(
                    "setSuffix", playerType, String.class
                );
                foundTagOriginalPrefix = nameTagType.getMethod(
                    "getOriginalRawPrefix", playerType
                );
                foundTagOriginalSuffix = nameTagType.getMethod(
                    "getOriginalRawSuffix", playerType
                );
            } catch (ReflectiveOperationException | LinkageError exception) {
                logger.warn("TAB was found but its API is incompatible", exception);
                foundApi = null;
            }
        }
        this.api = foundApi;
        this.tabList = foundTabList;
        this.nameTags = foundNameTags;
        this.getPlayer = foundGetPlayer;
        this.isLoaded = foundIsLoaded;
        this.setExpectedProfileName = foundExpectedName;
        this.tabSetPrefix = foundTabSetPrefix;
        this.tabSetName = foundTabSetName;
        this.tabSetSuffix = foundTabSetSuffix;
        this.tabOriginalPrefix = foundTabOriginalPrefix;
        this.tabOriginalSuffix = foundTabOriginalSuffix;
        this.tagSetPrefix = foundTagSetPrefix;
        this.tagSetSuffix = foundTagSetSuffix;
        this.tagOriginalPrefix = foundTagOriginalPrefix;
        this.tagOriginalSuffix = foundTagOriginalSuffix;
    }

    boolean apply(Player player, String nickname, String rankPrefix, String rankSuffix) {
        if (api == null) return true;
        try {
            Object tabPlayer = getPlayer.invoke(api, player.getUniqueId());
            if (tabPlayer == null || !Boolean.TRUE.equals(isLoaded.invoke(tabPlayer))) return false;
            String tabPrefix = replaceAffix(
                value(tabOriginalPrefix.invoke(tabList, tabPlayer)), rankPrefix, true
            );
            String tabSuffix = replaceAffix(
                value(tabOriginalSuffix.invoke(tabList, tabPlayer)), rankSuffix, false
            );
            String tagPrefix = replaceAffix(
                value(tagOriginalPrefix.invoke(nameTags, tabPlayer)), rankPrefix, true
            );
            String tagSuffix = replaceAffix(
                value(tagOriginalSuffix.invoke(nameTags, tabPlayer)), rankSuffix, false
            );
            setExpectedProfileName.invoke(tabPlayer, nickname);
            tabSetPrefix.invoke(tabList, tabPlayer, tabPrefix);
            tabSetName.invoke(tabList, tabPlayer, nickname);
            tabSetSuffix.invoke(tabList, tabPlayer, tabSuffix);
            tagSetPrefix.invoke(nameTags, tabPlayer, tagPrefix);
            tagSetSuffix.invoke(nameTags, tabPlayer, tagSuffix);
            return true;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            logOnce("Unable to synchronize nickname with TAB", exception);
            return true;
        }
    }

    void restore(Player player) {
        if (api == null) return;
        try {
            Object tabPlayer = getPlayer.invoke(api, player.getUniqueId());
            if (tabPlayer == null || !Boolean.TRUE.equals(isLoaded.invoke(tabPlayer))) return;
            setExpectedProfileName.invoke(tabPlayer, player.getName());
            tabSetPrefix.invoke(tabList, tabPlayer, (Object) null);
            tabSetName.invoke(tabList, tabPlayer, (Object) null);
            tabSetSuffix.invoke(tabList, tabPlayer, (Object) null);
            tagSetPrefix.invoke(nameTags, tabPlayer, (Object) null);
            tagSetSuffix.invoke(nameTags, tabPlayer, (Object) null);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            logOnce("Unable to restore TAB identity", exception);
        }
    }

    private static String replaceAffix(String template, String replacement, boolean prefix) {
        String value = template == null ? "" : template;
        String affix = replacement == null ? "" : replacement.strip();
        String replaced = value
            .replace("%vault_prefix%", affix)
            .replace("%vault-prefix%", affix)
            .replace("%luckperms_prefix%", affix)
            .replace("%cctsystem_prefix%", affix)
            .replace("%vault_suffix%", affix)
            .replace("%vault-suffix%", affix)
            .replace("%luckperms_suffix%", affix);
        replaced = replaced.replace("%cctsystem_suffix%", affix);
        if (replaced.equals(value)
            && !value.contains("%vault_prefix%")
            && !value.contains("%vault-prefix%")
            && !value.contains("%luckperms_prefix%")
            && !value.contains("%cctsystem_prefix%")
            && !value.contains("%vault_suffix%")
            && !value.contains("%vault-suffix%")
            && !value.contains("%luckperms_suffix%")
            && !value.contains("%cctsystem_suffix%")) {
            replaced = prefix ? affix : affix + value;
        }
        String result = replaced.replaceAll("](?=(?:&[0-9A-FK-ORX])*\\[)", "] ")
            .replaceAll("[ \\t]{2,}", " ")
            .stripTrailing();
        return prefix && hasVisibleText(result) ? result + " " : result;
    }

    private static boolean hasVisibleText(String value) {
        return !value.replaceAll("(?i)&[0-9A-FK-ORX]", "").isBlank();
    }

    private static String value(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private void logOnce(String message, Exception exception) {
        if (failureLogged) return;
        failureLogged = true;
        logger.warn(message, exception);
    }
}
