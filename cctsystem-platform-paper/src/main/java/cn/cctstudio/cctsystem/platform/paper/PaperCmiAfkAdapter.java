package cn.cctstudio.cctsystem.platform.paper;

import cn.cctstudio.cctsystem.core.logging.CctLogger;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/** Optional CMI integration that keeps vanished staff out of CMI's AFK state. */
final class PaperCmiAfkAdapter {
    private static final PaperCmiAfkAdapter NO_OP = new PaperCmiAfkAdapter(
        null, null, null, null, null, null, null
    );

    private final Object afkManager;
    private final Object playerManager;
    private final Method getUser;
    private final Method isAfk;
    private final Method removeUserFromAfk;
    private final Method setLastAction;
    private final CctLogger logger;

    private PaperCmiAfkAdapter(
        Object afkManager,
        Object playerManager,
        Method getUser,
        Method isAfk,
        Method removeUserFromAfk,
        Method setLastAction,
        CctLogger logger
    ) {
        this.afkManager = afkManager;
        this.playerManager = playerManager;
        this.getUser = getUser;
        this.isAfk = isAfk;
        this.removeUserFromAfk = removeUserFromAfk;
        this.setLastAction = setLastAction;
        this.logger = logger;
    }

    static PaperCmiAfkAdapter install(
        JavaPlugin plugin,
        Predicate<UUID> vanished,
        CctLogger logger
    ) {
        Plugin cmi = plugin.getServer().getPluginManager().getPlugin("CMI");
        if (cmi == null || !cmi.isEnabled()) return NO_OP;
        try {
            ClassLoader loader = cmi.getClass().getClassLoader();
            Class<? extends Event> enterEvent = Class
                .forName("com.Zrips.CMI.events.CMIAfkEnterEvent", false, loader)
                .asSubclass(Event.class);
            Method eventPlayer = enterEvent.getMethod("getPlayer");
            Listener listener = new Listener() { };
            plugin.getServer().getPluginManager().registerEvent(
                enterEvent,
                listener,
                EventPriority.HIGHEST,
                (ignored, event) -> {
                    // CMI keeps several custom events on a shared HandlerList. Bukkit can
                    // therefore route sibling CMI events (for example balance changes) to
                    // this executor even though it was registered for CMIAfkEnterEvent.
                    if (!enterEvent.isInstance(event)) return;
                    try {
                        Player player = (Player) eventPlayer.invoke(event);
                        if (vanished.test(player.getUniqueId())) {
                            ((Cancellable) event).setCancelled(true);
                        }
                    } catch (IllegalAccessException | InvocationTargetException | RuntimeException exception) {
                        logger.warn("Unable to inspect CMI AFK event", exception);
                    }
                },
                plugin,
                true
            );

            Method getAfkManager = cmi.getClass().getMethod("getAfkManager");
            Method getPlayerManager = cmi.getClass().getMethod("getPlayerManager");
            Object afkManager = getAfkManager.invoke(cmi);
            Object playerManager = getPlayerManager.invoke(cmi);
            Method getUser = playerManager.getClass().getMethod("getUser", Player.class);
            Class<?> userClass = getUser.getReturnType();
            Method isAfk = afkManager.getClass().getMethod("isAfk", UUID.class);
            Method remove = afkManager.getClass().getMethod(
                "removeUserFromAfk", userClass, List.class
            );
            Method setLastAction = afkManager.getClass().getMethod(
                "setLastAction", userClass, long.class
            );
            logger.info("CMI AFK integration is active for vanished staff");
            return new PaperCmiAfkAdapter(
                afkManager, playerManager, getUser, isAfk, remove, setLastAction, logger
            );
        } catch (ReflectiveOperationException | RuntimeException exception) {
            logger.warn("Unable to initialize CMI AFK integration", exception);
            return NO_OP;
        }
    }

    void leaveAfkSilently(Player player) {
        if (afkManager == null) return;
        try {
            Object user = getUser.invoke(playerManager, player);
            if (user == null) return;
            if (Boolean.TRUE.equals(isAfk.invoke(afkManager, player.getUniqueId()))) {
                removeUserFromAfk.invoke(afkManager, user, List.of());
            }
            setLastAction.invoke(afkManager, user, System.currentTimeMillis());
        } catch (IllegalAccessException | InvocationTargetException | RuntimeException exception) {
            logger.warn("Unable to keep vanished player out of CMI AFK", exception);
        }
    }
}
