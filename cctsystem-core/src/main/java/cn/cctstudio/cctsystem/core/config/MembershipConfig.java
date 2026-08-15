package cn.cctstudio.cctsystem.core.config;

import java.util.List;

public record MembershipConfig(int quoteTtlSeconds, List<MembershipTierConfig> tiers) {
    public MembershipConfig {
        quoteTtlSeconds = quoteTtlSeconds <= 0 ? 60 : quoteTtlSeconds;
        tiers = tiers == null ? List.of() : List.copyOf(tiers);
    }

    public static MembershipConfig defaults() {
        return new MembershipConfig(60, List.of(
            tier("vip", "VIP", 100, "vip", 100, "GOLD_INGOT"),
            tier("vip_plus", "VIP+", 200, "vip+", 200, "DIAMOND"),
            tier("mvp", "MVP", 300, "mvp", 400, "EMERALD"),
            tier("mvp_plus", "MVP+", 400, "mvp+", 800, "NETHER_STAR")
        ));
    }

    private static MembershipTierConfig tier(
        String key,
        String name,
        int priority,
        String group,
        int price,
        String material
    ) {
        return new MembershipTierConfig(
            key, name, priority, group, 30, price, 10_000, material, List.of(), true
        );
    }
}
