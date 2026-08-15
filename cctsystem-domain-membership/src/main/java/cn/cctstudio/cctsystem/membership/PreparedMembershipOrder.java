package cn.cctstudio.cctsystem.membership;

import java.util.UUID;

record PreparedMembershipOrder(
    boolean proceed,
    UUID orderId,
    UUID operationId,
    UUID playerUuid,
    MembershipQuote quote,
    MembershipOrderResult existingResult
) {
    static PreparedMembershipOrder proceed(
        UUID orderId,
        UUID operationId,
        UUID playerUuid,
        MembershipQuote quote
    ) {
        return new PreparedMembershipOrder(true, orderId, operationId, playerUuid, quote, null);
    }

    static PreparedMembershipOrder existing(MembershipOrderResult result) {
        return new PreparedMembershipOrder(false, result.orderId(), null, null, null, result);
    }
}
