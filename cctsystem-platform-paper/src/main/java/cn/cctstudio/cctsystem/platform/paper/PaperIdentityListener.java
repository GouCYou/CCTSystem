package cn.cctstudio.cctsystem.platform.paper;

import cn.cctstudio.cctsystem.core.logging.CctLogger;
import cn.cctstudio.cctsystem.core.provider.ProviderRegistry;
import cn.cctstudio.cctsystem.identity.IdentityProvider;
import java.time.Instant;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

final class PaperIdentityListener implements Listener {
    private final ProviderRegistry providers;
    private final CctLogger logger;

    PaperIdentityListener(ProviderRegistry providers, CctLogger logger) {
        this.providers = providers;
        this.logger = logger;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        providers.find(IdentityProvider.KEY).ifPresent(identities -> identities.recordSeen(
            event.getPlayer().getUniqueId(),
            event.getPlayer().getName(),
            Instant.now()
        ).exceptionally(throwable -> {
            logger.warn("Unable to update joined player identity", throwable);
            return null;
        }));
    }
}
