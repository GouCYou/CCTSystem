package cn.cctstudio.cctsystem.platform.paper;

import cn.cctstudio.cctsystem.core.logging.CctLogger;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** Optional LibsDisguises adapter. CCTSystem remains the owner of nickname state. */
final class PaperDisguiseProvider {
    private final CctLogger logger;
    private final Constructor<?> playerDisguise;
    private final Method disguiseToAll;
    private final Method undisguiseToAll;
    private final Method setName;
    private final Map<UUID, Object> active = new ConcurrentHashMap<>();
    private volatile boolean failureLogged;

    PaperDisguiseProvider(JavaPlugin plugin, CctLogger logger) {
        this.logger = logger;
        Constructor<?> constructor = null;
        Method apply = null;
        Method remove = null;
        Method rename = null;
        if (plugin.getServer().getPluginManager().isPluginEnabled("LibsDisguises")) {
            try {
                Class<?> disguiseType = Class.forName(
                    "me.libraryaddict.disguise.disguisetypes.Disguise"
                );
                Class<?> playerType = Class.forName(
                    "me.libraryaddict.disguise.disguisetypes.PlayerDisguise"
                );
                Class<?> api = Class.forName("me.libraryaddict.disguise.DisguiseAPI");
                constructor = playerType.getConstructor(String.class, String.class);
                rename = playerType.getMethod("setName", String.class);
                apply = api.getMethod("disguiseToAll", Entity.class, disguiseType);
                remove = api.getMethod("undisguiseToAll", Entity.class);
            } catch (ReflectiveOperationException exception) {
                logger.warn("LibsDisguises was found but its API is incompatible", exception);
            }
        }
        this.playerDisguise = constructor;
        this.disguiseToAll = apply;
        this.undisguiseToAll = remove;
        this.setName = rename;
    }

    boolean available() {
        return playerDisguise != null && disguiseToAll != null && undisguiseToAll != null;
    }

    void apply(Player player, String nickname, String skinName, String visibleName) {
        if (!available()) return;
        try {
            String name = visibleName == null || visibleName.isBlank() ? nickname : visibleName;
            Object disguise = playerDisguise.newInstance(name, skinName);
            disguiseToAll.invoke(null, player, disguise);
            active.put(player.getUniqueId(), disguise);
        } catch (ReflectiveOperationException exception) {
            logOnce("Unable to apply player disguise", exception);
        }
    }

    void updateName(Player player, String visibleName) {
        Object disguise = active.get(player.getUniqueId());
        if (disguise == null || setName == null || visibleName == null || visibleName.isBlank()) return;
        try {
            setName.invoke(disguise, visibleName);
        } catch (ReflectiveOperationException exception) {
            logOnce("Unable to update player disguise name", exception);
        }
    }

    void remove(Player player) {
        active.remove(player.getUniqueId());
        if (!available()) return;
        try {
            undisguiseToAll.invoke(null, player);
        } catch (ReflectiveOperationException exception) {
            logOnce("Unable to remove player disguise", exception);
        }
    }

    private void logOnce(String message, Exception exception) {
        if (failureLogged) return;
        failureLogged = true;
        logger.warn(message, exception);
    }
}
