package cn.cctstudio.cctsystem.platform.paper;

import cn.cctstudio.cctsystem.core.logging.CctLogger;
import cn.cctstudio.cctsystem.core.provider.ProviderRegistry;
import cn.cctstudio.cctsystem.membership.MembershipServiceProvider;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.event.EventSubscription;
import net.luckperms.api.event.user.UserDataRecalculateEvent;
import org.bukkit.plugin.java.JavaPlugin;

final class PaperMembershipAccessSynchronizer implements AutoCloseable {
    private final JavaPlugin plugin;
    private final ProviderRegistry providers;
    private final CctLogger logger;
    private final Set<UUID> queued = ConcurrentHashMap.newKeySet();
    private final EventSubscription<UserDataRecalculateEvent> subscription;

    PaperMembershipAccessSynchronizer(
        JavaPlugin plugin,
        ProviderRegistry providers,
        CctLogger logger,
        LuckPerms luckPerms
    ) {
        this.plugin = plugin;
        this.providers = providers;
        this.logger = logger;
        this.subscription = luckPerms.getEventBus().subscribe(
            plugin,
            UserDataRecalculateEvent.class,
            event -> queue(event.getUser().getUniqueId())
        );
    }

    private void queue(UUID playerUuid) {
        if (!queued.add(playerUuid)) return;
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            queued.remove(playerUuid);
            providers.find(MembershipServiceProvider.KEY).ifPresent(memberships ->
                memberships.reconcile(playerUuid).exceptionally(throwable -> {
                    logger.warn("Unable to reconcile membership after LuckPerms access changed", throwable);
                    return null;
                })
            );
        }, 2L);
    }

    @Override
    public void close() {
        subscription.close();
        queued.clear();
    }
}
