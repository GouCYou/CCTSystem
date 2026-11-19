package cn.cctstudio.cctsystem.platform.paper;

import cn.cctstudio.cctsystem.core.logging.CctLogger;
import cn.cctstudio.cctsystem.core.provider.ProviderRegistry;
import cn.cctstudio.cctsystem.membership.MembershipServiceProvider;
import cn.cctstudio.cctsystem.membership.MembershipFailures;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.event.EventSubscription;
import net.luckperms.api.event.node.NodeMutateEvent;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.NodeType;
import org.bukkit.plugin.java.JavaPlugin;

final class PaperMembershipAccessSynchronizer implements AutoCloseable {
    private final JavaPlugin plugin;
    private final ProviderRegistry providers;
    private final CctLogger logger;
    private final Set<String> accessRelevantGroups;
    private final Set<UUID> queued = ConcurrentHashMap.newKeySet();
    private final EventSubscription<NodeMutateEvent> subscription;

    PaperMembershipAccessSynchronizer(
        JavaPlugin plugin,
        ProviderRegistry providers,
        CctLogger logger,
        LuckPerms luckPerms,
        Set<String> accessRelevantGroups
    ) {
        this.plugin = plugin;
        this.providers = providers;
        this.logger = logger;
        this.accessRelevantGroups = Set.copyOf(accessRelevantGroups);
        this.subscription = luckPerms.getEventBus().subscribe(
            plugin,
            NodeMutateEvent.class,
            this::onNodeMutation
        );
    }

    private void onNodeMutation(NodeMutateEvent event) {
        if (!(event.getTarget() instanceof User user)) return;
        if (!affectsAccess(event.getDataBefore(), event.getDataAfter(), accessRelevantGroups)) {
            return;
        }
        queue(user.getUniqueId());
    }

    static boolean affectsAccess(Set<Node> before, Set<Node> after, Set<String> relevantGroups) {
        return MembershipAccessGroupChanges.affectsAccess(
            inheritanceGroups(before),
            inheritanceGroups(after),
            relevantGroups
        );
    }

    private static Set<String> inheritanceGroups(Set<Node> nodes) {
        return nodes.stream()
            .filter(NodeType.INHERITANCE::matches)
            .map(NodeType.INHERITANCE::cast)
            .map(node -> node.getGroupName())
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private void queue(UUID playerUuid) {
        if (!queued.add(playerUuid)) return;
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            providers.find(MembershipServiceProvider.KEY).ifPresentOrElse(memberships ->
                memberships.reconcile(playerUuid).whenComplete((ignored, throwable) -> {
                    queued.remove(playerUuid);
                    if (throwable == null) return;
                    MembershipFailures.log(logger, "Membership access check after LuckPerms change", throwable);
                }),
                () -> queued.remove(playerUuid)
            );
        }, 2L);
    }

    @Override
    public void close() {
        subscription.close();
        queued.clear();
    }
}
