package cn.cctstudio.cctsystem.membership;

import java.time.Instant;
import java.util.UUID;

public record MembershipOrderResult(
    UUID orderId,
    MembershipOrderStatus status,
    String tierKey,
    int months,
    int finalPricePoints,
    UUID entitlementId,
    Instant expiresAt,
    String permissionSyncStatus,
    String errorCode
) {
}
