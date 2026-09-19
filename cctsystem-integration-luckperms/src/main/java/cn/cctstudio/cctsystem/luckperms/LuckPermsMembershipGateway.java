package cn.cctstudio.cctsystem.luckperms;

import cn.cctstudio.cctsystem.membership.MembershipPermissionGateway;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.node.NodeType;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.types.InheritanceNode;

public final class LuckPermsMembershipGateway implements MembershipPermissionGateway {
    private final LuckPerms luckPerms;

    public LuckPermsMembershipGateway(LuckPerms luckPerms) {
        this.luckPerms = Objects.requireNonNull(luckPerms, "luckPerms");
    }

    @Override
    public CompletionStage<Void> project(
        UUID playerUuid,
        Set<String> managedGroups,
        String desiredGroup,
        Instant desiredExpiry
    ) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        Set<String> normalizedManaged = Set.copyOf(managedGroups);
        if (desiredGroup != null && !normalizedManaged.contains(desiredGroup)) {
            throw new IllegalArgumentException("Desired group is not managed by CCTSystem");
        }
        if (desiredGroup != null && desiredExpiry == null) {
            throw new IllegalArgumentException("Temporary membership group requires an expiry");
        }
        if (desiredGroup != null && luckPerms.getGroupManager().getGroup(desiredGroup) == null) {
            throw new IllegalStateException("LuckPerms group does not exist: " + desiredGroup);
        }
        return luckPerms.getUserManager().loadUser(playerUuid).thenCompose(user -> {
            user.data().clear(node -> NodeType.INHERITANCE.matches(node)
                && normalizedManaged.contains(NodeType.INHERITANCE.cast(node).getGroupName()));
            if (desiredGroup != null) {
                user.data().add(InheritanceNode.builder(desiredGroup)
                    .expiry(desiredExpiry)
                    .build());
            }
            return luckPerms.getUserManager().saveUser(user).thenRun(() ->
                luckPerms.getMessagingService().ifPresent(service -> service.pushUserUpdate(user))
            );
        });
    }

    @Override
    public CompletionStage<Boolean> matches(
        UUID playerUuid,
        Set<String> managedGroups,
        String desiredGroup,
        Instant desiredExpiry
    ) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        Set<String> normalizedManaged = Set.copyOf(managedGroups);
        return luckPerms.getUserManager().loadUser(playerUuid).thenApply(user -> {
            Set<Node> inherited = user.data().toCollection().stream()
                .filter(NodeType.INHERITANCE::matches)
                .filter(node -> normalizedManaged.contains(
                    NodeType.INHERITANCE.cast(node).getGroupName()
                ))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
            if (desiredGroup == null) return inherited.isEmpty();
            if (inherited.size() != 1) return false;
            Node node = inherited.iterator().next();
            return desiredGroup.equals(NodeType.INHERITANCE.cast(node).getGroupName())
                && sameExpiry(desiredExpiry, node.getExpiry());
        });
    }

    private static boolean sameExpiry(Instant expected, Instant actual) {
        if (expected == null || actual == null) return expected == actual;
        // LuckPerms' SQL schema persists node expiries at epoch-second precision.
        return expected.getEpochSecond() == actual.getEpochSecond();
    }
}
