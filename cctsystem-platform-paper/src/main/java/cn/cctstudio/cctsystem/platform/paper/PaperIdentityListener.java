package cn.cctstudio.cctsystem.platform.paper;

import cn.cctstudio.cctsystem.core.logging.CctLogger;
import cn.cctstudio.cctsystem.core.provider.ProviderRegistry;
import cn.cctstudio.cctsystem.identity.IdentityProvider;
import cn.cctstudio.cctsystem.membership.MembershipServiceProvider;
import java.time.Instant;
import java.util.UUID;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

final class PaperIdentityListener implements Listener {
    private final JavaPlugin plugin;
    private final ProviderRegistry providers;
    private final CctLogger logger;

    PaperIdentityListener(JavaPlugin plugin, ProviderRegistry providers, CctLogger logger) {
        this.plugin = plugin;
        this.providers = providers;
        this.logger = logger;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        UUID playerUuid = event.getPlayer().getUniqueId();
        providers.find(IdentityProvider.KEY).ifPresent(identities -> identities.recordSeen(
            playerUuid,
            event.getPlayer().getName(),
            Instant.now()
        ).exceptionally(throwable -> {
            logger.warn("Unable to update joined player identity", throwable);
            return null;
        }));
        plugin.getServer().getScheduler().runTaskLater(plugin, () ->
            providers.find(MembershipServiceProvider.KEY).ifPresent(memberships ->
                memberships.summary(playerUuid).exceptionally(throwable -> {
                    logger.warn("Unable to reconcile joined player membership access", throwable);
                    return null;
                })
            ),
            20L
        );
    }
}
