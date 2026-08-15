package cn.cctstudio.cctsystem.core.config;

import cn.cctstudio.cctsystem.contract.NodeRole;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Set;

public record CctConfig(
    String networkId,
    String serverId,
    String nodeId,
    Set<NodeRole> roles,
    DatabaseConfig database,
    BridgeConfig bridge,
    ExchangeConfig exchange,
    MembershipConfig membership,
    PromotionConfig promotion,
    RedeemCodeConfig redeemCode,
    LinkedHashMap<String, ModuleConfig> modules
) {
    public CctConfig {
        networkId = normalize(networkId);
        serverId = normalize(serverId);
        nodeId = normalize(nodeId);
        roles = roles == null ? Set.of() : Set.copyOf(roles);
        database = Objects.requireNonNullElseGet(
            database,
            () -> new DatabaseConfig(false, "", "", "", 6, 5_000L)
        );
        bridge = Objects.requireNonNullElseGet(
            bridge,
            () -> new BridgeConfig(false, "", "", 10, 8)
        );
        exchange = Objects.requireNonNullElseGet(
            exchange,
            () -> new ExchangeConfig(300, "Asia/Shanghai", java.util.List.of())
        );
        membership = Objects.requireNonNullElseGet(membership, MembershipConfig::defaults);
        promotion = Objects.requireNonNullElseGet(promotion, PromotionConfig::defaults);
        redeemCode = Objects.requireNonNullElseGet(redeemCode, RedeemCodeConfig::defaults);
        modules = modules == null ? new LinkedHashMap<>() : new LinkedHashMap<>(modules);
    }

    public ModuleMode moduleMode(String moduleId) {
        ModuleConfig moduleConfig = modules.get(moduleId);
        return moduleConfig == null ? ModuleMode.AUTO : moduleConfig.enabled();
    }

    public CctConfig(
        String networkId,
        String serverId,
        String nodeId,
        Set<NodeRole> roles,
        DatabaseConfig database,
        BridgeConfig bridge,
        LinkedHashMap<String, ModuleConfig> modules
    ) {
        this(
            networkId,
            serverId,
            nodeId,
            roles,
            database,
            bridge,
            new ExchangeConfig(300, "Asia/Shanghai", java.util.List.of()),
            MembershipConfig.defaults(),
            PromotionConfig.defaults(),
            RedeemCodeConfig.defaults(),
            modules
        );
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
