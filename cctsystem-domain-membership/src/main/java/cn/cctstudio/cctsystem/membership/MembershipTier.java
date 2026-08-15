package cn.cctstudio.cctsystem.membership;

import java.time.Duration;
import java.util.List;

public record MembershipTier(
    String key,
    String displayName,
    int priority,
    String luckPermsGroup,
    int durationDays,
    int pricePoints,
    int upgradeCreditRateBps,
    String displayMaterial,
    List<String> benefits,
    boolean enabled,
    long version
) {
    public MembershipTier {
        benefits = List.copyOf(benefits);
    }

    public long durationSeconds() {
        return Duration.ofDays(durationDays).toSeconds();
    }
}
