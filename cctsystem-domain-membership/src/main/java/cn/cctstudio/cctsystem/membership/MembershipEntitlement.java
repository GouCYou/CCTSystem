package cn.cctstudio.cctsystem.membership;

import java.time.Instant;
import java.util.UUID;

public record MembershipEntitlement(
    UUID entitlementId,
    MembershipTier tier,
    EntitlementState state,
    Instant startsAt,
    Instant expiresAt,
    Long remainingSeconds,
    Long resumeSequence
) {
}
