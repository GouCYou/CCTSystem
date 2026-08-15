package cn.cctstudio.cctsystem.contract;

import java.util.Objects;
import java.util.Set;

public record NodeHello(
    int protocolVersion,
    String networkId,
    String serverId,
    String nodeId,
    PlatformType platform,
    Set<NodeRole> roles,
    Set<Capability> capabilities,
    String bootId,
    String pluginVersion
) {
    public NodeHello {
        Objects.requireNonNull(networkId, "networkId");
        Objects.requireNonNull(serverId, "serverId");
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(platform, "platform");
        roles = Set.copyOf(roles);
        capabilities = Set.copyOf(capabilities);
        Objects.requireNonNull(bootId, "bootId");
        Objects.requireNonNull(pluginVersion, "pluginVersion");
    }
}
