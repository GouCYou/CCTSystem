package cn.cctstudio.cctsystem.core.config;

import java.util.List;
import java.util.Locale;
import java.util.Set;

public record MembershipConfig(
    int quoteTtlSeconds,
    int maxPurchaseDays,
    Set<String> purchaseBlockedGroups,
    List<MembershipTierConfig> tiers
) {
    public MembershipConfig {
        quoteTtlSeconds = quoteTtlSeconds <= 0 ? 60 : quoteTtlSeconds;
        maxPurchaseDays = maxPurchaseDays <= 0 ? 365 : maxPurchaseDays;
        purchaseBlockedGroups = purchaseBlockedGroups == null
            ? Set.of("helper", "mod", "admin", "owner")
            : purchaseBlockedGroups.stream()
                .filter(group -> group != null && !group.isBlank())
                .map(group -> group.trim().toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        tiers = tiers == null ? List.of() : List.copyOf(tiers);
    }

    public static MembershipConfig defaults() {
        return new MembershipConfig(60, 365, Set.of("helper", "mod", "admin", "owner"), List.of(
            tier("vip", "VIP", 100, "vip", 100, "GOLD_INGOT"),
            tier("vip_plus", "VIP+", 200, "vipp", 200, "DIAMOND"),
            tier("mvp", "MVP", 300, "mvp", 400, "EMERALD"),
            tier("mvp_plus", "MVP+", 400, "mvpp", 800, "NETHER_STAR")
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
