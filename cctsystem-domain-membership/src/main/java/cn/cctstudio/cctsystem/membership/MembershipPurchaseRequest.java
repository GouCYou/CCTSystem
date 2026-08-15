package cn.cctstudio.cctsystem.membership;

import java.util.UUID;

public record MembershipPurchaseRequest(
    UUID playerUuid,
    String tierKey,
    int months,
    UpgradeMode upgradeMode,
    String idempotencyKey,
    String origin
) {
}
