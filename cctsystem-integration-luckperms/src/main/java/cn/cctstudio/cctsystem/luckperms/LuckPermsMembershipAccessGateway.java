package cn.cctstudio.cctsystem.luckperms;

import cn.cctstudio.cctsystem.membership.MembershipAccess;
import cn.cctstudio.cctsystem.membership.MembershipAccessGateway;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.node.NodeType;

public final class LuckPermsMembershipAccessGateway implements MembershipAccessGateway {
    private final LuckPerms luckPerms;

    public LuckPermsMembershipAccessGateway(LuckPerms luckPerms) {
        this.luckPerms = Objects.requireNonNull(luckPerms, "luckPerms");
    }

    @Override
    public CompletionStage<MembershipAccess> access(UUID playerUuid) {
        return luckPerms.getUserManager().loadUser(playerUuid).thenApply(user -> {
            Set<String> groups = new HashSet<>();
            groups.add(user.getPrimaryGroup());
            user.getNodes(NodeType.INHERITANCE).forEach(node -> groups.add(node.getGroupName()));
            return new MembershipAccess(groups);
        });
    }
}
