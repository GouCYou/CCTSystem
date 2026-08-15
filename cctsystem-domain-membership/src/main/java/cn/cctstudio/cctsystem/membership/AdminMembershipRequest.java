package cn.cctstudio.cctsystem.membership;

import java.time.Instant;
import java.util.UUID;

public record AdminMembershipRequest(
    AdminMembershipAction action,
    UUID playerUuid,
    String tierKey,
    int days,
    Instant expiresAt,
    String actor,
    String reason
) {
}
