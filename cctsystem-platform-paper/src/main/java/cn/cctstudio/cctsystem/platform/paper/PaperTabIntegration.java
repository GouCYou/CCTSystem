package cn.cctstudio.cctsystem.platform.paper;

import cn.cctstudio.cctsystem.core.logging.CctLogger;
import java.lang.reflect.Method;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** Optional TAB 6 adapter. Keeps anonymous profile, tab list and nametag in one identity. */
final class PaperTabIntegration {
    private final JavaPlugin plugin;
    private final CctLogger logger;
    private final Object api;
    private final Object tabList;
    private final Object nameTags;
    private final Object sorting;
    private final Method getPlayer;
    private final Method isLoaded;
    private final Method setExpectedProfileName;
    private final Method getExpectedProfileName;
    private final Method tabSetPrefix;
    private final Method tabSetName;
    private final Method tabSetSuffix;
    private final Method tabOriginalPrefix;
    private final Method tabOriginalSuffix;
    private final Method tagSetPrefix;
    private final Method tagSetSuffix;
    private final Method tagOriginalPrefix;
    private final Method tagOriginalSuffix;
    private final Method sortingForceTeamName;
    private final Method sortingOriginalTeamName;
    private final Method sortingForcedTeamName;
    private volatile boolean failureLogged;

    PaperTabIntegration(JavaPlugin plugin, CctLogger logger) {
        this.plugin = plugin;
        this.logger = logger;
        Object foundApi = null;
        Object foundTabList = null;
        Object foundNameTags = null;
        Object foundSorting = null;
        Method foundGetPlayer = null;
        Method foundIsLoaded = null;
        Method foundExpectedName = null;
        Method foundGetExpectedName = null;
        Method foundTabSetPrefix = null;
        Method foundTabSetName = null;
        Method foundTabSetSuffix = null;
        Method foundTabOriginalPrefix = null;
        Method foundTabOriginalSuffix = null;
        Method foundTagSetPrefix = null;
        Method foundTagSetSuffix = null;
        Method foundTagOriginalPrefix = null;
        Method foundTagOriginalSuffix = null;
        Method foundSortingForceTeamName = null;
        Method foundSortingOriginalTeamName = null;
        Method foundSortingForcedTeamName = null;
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
                Class<?> sortingType = Class.forName(
                    "me.neznamy.tab.api.tablist.SortingManager"
                );
                foundApi = apiType.getMethod("getInstance").invoke(null);
                foundGetPlayer = apiType.getMethod("getPlayer", UUID.class);
                foundTabList = apiType.getMethod("getTabListFormatManager").invoke(foundApi);
                foundNameTags = apiType.getMethod("getNameTagManager").invoke(foundApi);
                foundSorting = apiType.getMethod("getSortingManager").invoke(foundApi);
                foundIsLoaded = playerType.getMethod("isLoaded");
                foundExpectedName = playerType.getMethod(
                    "setExpectedProfileName", String.class
                );
                foundGetExpectedName = playerType.getMethod("getExpectedProfileName");
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
                foundSortingForceTeamName = sortingType.getMethod(
                    "forceTeamName", playerType, String.class
                );
                foundSortingOriginalTeamName = sortingType.getMethod(
                    "getOriginalTeamName", playerType
                );
                foundSortingForcedTeamName = sortingType.getMethod(
                    "getForcedTeamName", playerType
                );
            } catch (ReflectiveOperationException | LinkageError exception) {
                logger.warn("TAB was found but its API is incompatible", exception);
                foundApi = null;
            }
        }
        this.api = foundApi;
        this.tabList = foundTabList;
        this.nameTags = foundNameTags;
        this.sorting = foundSorting;
        this.getPlayer = foundGetPlayer;
        this.isLoaded = foundIsLoaded;
        this.setExpectedProfileName = foundExpectedName;
        this.getExpectedProfileName = foundGetExpectedName;
        this.tabSetPrefix = foundTabSetPrefix;
        this.tabSetName = foundTabSetName;
        this.tabSetSuffix = foundTabSetSuffix;
        this.tabOriginalPrefix = foundTabOriginalPrefix;
        this.tabOriginalSuffix = foundTabOriginalSuffix;
        this.tagSetPrefix = foundTagSetPrefix;
        this.tagSetSuffix = foundTagSetSuffix;
        this.tagOriginalPrefix = foundTagOriginalPrefix;
        this.tagOriginalSuffix = foundTagOriginalSuffix;
        this.sortingForceTeamName = foundSortingForceTeamName;
        this.sortingOriginalTeamName = foundSortingOriginalTeamName;
        this.sortingForcedTeamName = foundSortingForcedTeamName;
    }

    boolean apply(Player player, String nickname, String rankPrefix, String rankSuffix) {
        if (api == null) return true;
        try {
            Object tabPlayer = getPlayer.invoke(api, player.getUniqueId());
            if (tabPlayer == null || !Boolean.TRUE.equals(isLoaded.invoke(tabPlayer))) return false;
            String tabPrefix = TabAffixFormatting.replaceAffix(
                value(tabOriginalPrefix.invoke(tabList, tabPlayer)), rankPrefix, true
            );
            String tabSuffix = TabAffixFormatting.replaceAffix(
                value(tabOriginalSuffix.invoke(tabList, tabPlayer)), rankSuffix, false
            );
            String tagPrefix = TabAffixFormatting.replaceAffix(
                value(tagOriginalPrefix.invoke(nameTags, tabPlayer)), rankPrefix, true
            );
            String tagSuffix = TabAffixFormatting.replaceAffix(
                value(tagOriginalSuffix.invoke(nameTags, tabPlayer)), rankSuffix, false
            );
            setExpectedProfileName.invoke(tabPlayer, nickname);
            tabSetPrefix.invoke(tabList, tabPlayer, tabPrefix);
            tabSetName.invoke(tabList, tabPlayer, nickname);
            tabSetSuffix.invoke(tabList, tabPlayer, tabSuffix);
            tagSetPrefix.invoke(nameTags, tabPlayer, tagPrefix);
            tagSetSuffix.invoke(nameTags, tabPlayer, tagSuffix);
            refreshSorting(tabPlayer, player.getUniqueId(), nickname);
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
            refreshSorting(tabPlayer, player.getUniqueId(), player.getName());
        } catch (ReflectiveOperationException | RuntimeException exception) {
            logOnce("Unable to restore TAB identity", exception);
        }
    }

    void forceSort(Player player, int titleWeight) {
        if (api == null || sorting == null) return;
        applyForcedSort(player, titleWeight);
        // apply() temporarily refreshes TAB's team and restores its prior override after
        // two ticks. Reassert the selected title order after that refresh completes.
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) applyForcedSort(player, titleWeight);
        }, 3L);
    }

    private void applyForcedSort(Player player, int titleWeight) {
        try {
            Object tabPlayer = getPlayer.invoke(api, player.getUniqueId());
            if (tabPlayer == null || !Boolean.TRUE.equals(isLoaded.invoke(tabPlayer))) return;
            String original = value(sortingOriginalTeamName.invoke(sorting, tabPlayer));
            if (original.isEmpty()) return;
            // TAB's GROUPS sorting type uses '0' for owner through '8' for default.
            // Preserve the remaining original team name so players with a selected
            // title sort alongside players who naturally have that group.
            int order = 8 - Math.max(0, Math.min(8, titleWeight));
            String teamName = (char) ('0' + order) + original.substring(1);
            sortingForceTeamName.invoke(sorting, tabPlayer, teamName);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            logOnce("Unable to apply CCT title sorting to TAB", exception);
        }
    }

    /**
     * Changing TAB's expected profile name removes and recreates the client-side entry. TAB 6
     * otherwise keeps the old scoreboard team until its next refresh, which temporarily places
     * the player above every configured group. Re-applying TAB's own calculated team name forces
     * an immediate update; the short-lived override is then restored to its previous state.
     */
    private void refreshSorting(Object tabPlayer, UUID playerUuid, String expectedName)
        throws ReflectiveOperationException {
        if (sorting == null) return;
        Object previous = sortingForcedTeamName.invoke(sorting, tabPlayer);
        String calculated = value(sortingOriginalTeamName.invoke(sorting, tabPlayer));
        if (calculated.isBlank() || calculated.equals(previous)) return;
        sortingForceTeamName.invoke(sorting, tabPlayer, calculated);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            try {
                Object current = getPlayer.invoke(api, playerUuid);
                if (current != tabPlayer
                    || !expectedName.equals(String.valueOf(getExpectedProfileName.invoke(tabPlayer)))) {
                    return;
                }
                sortingForceTeamName.invoke(sorting, tabPlayer, previous);
            } catch (ReflectiveOperationException | RuntimeException exception) {
                logOnce("Unable to release TAB sorting refresh", exception);
            }
        }, 2L);
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
