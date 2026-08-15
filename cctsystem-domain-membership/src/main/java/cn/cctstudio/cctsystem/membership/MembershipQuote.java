package cn.cctstudio.cctsystem.membership;

import java.time.Instant;
import java.util.UUID;

public record MembershipQuote(
    UUID quoteId,
    MembershipTier targetTier,
    int months,
    UpgradeMode upgradeMode,
    int basePricePoints,
    UUID promotionId,
    int promotionOffBps,
    Instant promotionEndsAt,
    int discountedPricePoints,
    int upgradeCreditPoints,
    int finalPricePoints,
    MembershipEntitlement currentEntitlement,
    Instant validUntil,
    Instant quotedAt
) {
}
