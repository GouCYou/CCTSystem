package cn.cctstudio.cctsystem.membership;

import java.util.UUID;

public record MembershipPurchaseRequest(
    UUID playerUuid,
    String tierKey,
    int months,
    UpgradeMode upgradeMode,
    String idempotencyKey,
    String origin,
    String discountCode
) {
    public MembershipPurchaseRequest(UUID playerUuid, String tierKey, int months, UpgradeMode mode, String key, String origin) {
        this(playerUuid,tierKey,months,mode,key,origin,null);
    }
}
