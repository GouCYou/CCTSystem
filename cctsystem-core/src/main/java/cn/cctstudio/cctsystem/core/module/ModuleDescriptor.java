package cn.cctstudio.cctsystem.core.module;

import cn.cctstudio.cctsystem.contract.Capability;
import cn.cctstudio.cctsystem.contract.NodeRole;
import cn.cctstudio.cctsystem.contract.PlatformType;
import java.util.Objects;
import java.util.Set;

public record ModuleDescriptor(
    String id,
    String configKey,
    Set<PlatformType> platforms,
    Set<NodeRole> roles,
    Set<String> requiredProviders,
    Set<Capability> capabilities
) {
    public ModuleDescriptor {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(configKey, "configKey");
        platforms = Set.copyOf(platforms);
        roles = Set.copyOf(roles);
        requiredProviders = Set.copyOf(requiredProviders);
        capabilities = Set.copyOf(capabilities);
    }

    public ModuleDescriptor(
        String id,
        Set<PlatformType> platforms,
        Set<NodeRole> roles,
        Set<String> requiredProviders,
        Set<Capability> capabilities
    ) {
        this(id, id, platforms, roles, requiredProviders, capabilities);
    }
}
