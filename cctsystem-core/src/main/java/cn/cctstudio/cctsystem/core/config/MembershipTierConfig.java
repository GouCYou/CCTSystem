package cn.cctstudio.cctsystem.core.config;

import java.util.List;
import java.util.Locale;

public record MembershipTierConfig(
    String key,
    String displayName,
    int priority,
    String luckPermsGroup,
    int durationDays,
    int pricePoints,
    int upgradeCreditRateBps,
    String displayMaterial,
    List<String> benefits,
    boolean enabled
) {
    public MembershipTierConfig {
        key = key == null ? "" : key.trim().toLowerCase(Locale.ROOT);
        displayName = displayName == null ? "" : displayName.trim();
        luckPermsGroup = luckPermsGroup == null ? "" : luckPermsGroup.trim().toLowerCase(Locale.ROOT);
        durationDays = durationDays <= 0 ? 30 : durationDays;
        upgradeCreditRateBps = upgradeCreditRateBps < 0 ? 0 : upgradeCreditRateBps;
        displayMaterial = displayMaterial == null || displayMaterial.isBlank()
            ? "GOLD_INGOT"
            : displayMaterial.trim().toUpperCase(Locale.ROOT);
        benefits = benefits == null ? List.of() : List.copyOf(benefits);
    }
}
