package cn.cctstudio.cctsystem.platform.paper;

import cn.cctstudio.cctsystem.core.logging.CctLogger;
import cn.cctstudio.cctsystem.core.provider.ProviderRegistry;
import cn.cctstudio.cctsystem.identity.IdentityProvider;
import cn.cctstudio.cctsystem.membership.MembershipServiceProvider;
import cn.cctstudio.cctsystem.membership.MembershipFailures;
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
        reconcileJoinedPlayer(playerUuid, 0, 20L);
    }

    private void reconcileJoinedPlayer(UUID playerUuid, int attempt, long delayTicks) {
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            var player = plugin.getServer().getPlayer(playerUuid);
            if (player == null || !player.isOnline()) return;
            providers.find(MembershipServiceProvider.KEY).ifPresent(memberships ->
                memberships.summary(playerUuid).whenComplete((summary, failure) -> {
                    if (failure == null) return;
                    if (MembershipFailures.temporaryTransport(failure) && attempt < 2) {
                        // Only retry the read/reconcile operation, never purchases or debits.
                        if (plugin.isEnabled()) reconcileJoinedPlayer(playerUuid, attempt + 1, 100L * (attempt + 1));
                        return;
                    }
                    MembershipFailures.log(logger, "Joined player membership check for " + playerUuid, failure);
                })
            );
        }, delayTicks);
    }
}
