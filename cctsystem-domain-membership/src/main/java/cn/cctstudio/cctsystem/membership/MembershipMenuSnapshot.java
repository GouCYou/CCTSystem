package cn.cctstudio.cctsystem.membership;

import java.util.List;

public record MembershipMenuSnapshot(
    MembershipSummary summary,
    List<MembershipMenuTier> tiers
) {
    public MembershipMenuSnapshot {
        tiers = tiers == null ? List.of() : List.copyOf(tiers);
    }
}
