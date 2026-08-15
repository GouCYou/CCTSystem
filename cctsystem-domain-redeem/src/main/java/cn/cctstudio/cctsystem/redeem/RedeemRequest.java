package cn.cctstudio.cctsystem.redeem;

import java.util.UUID;

public record RedeemRequest(
    UUID playerUuid,
    String code,
    String idempotencyKey,
    String origin
) {
}
