package cn.cctstudio.cctsystem.membership;

import java.time.Instant;
import java.util.UUID;

record MembershipProjection(
    UUID playerUuid,
    String desiredTierKey,
    String desiredGroup,
    Instant desiredExpiry,
    long desiredVersion,
    boolean verificationOnly
) {
}
