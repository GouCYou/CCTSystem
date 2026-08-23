package cn.cctstudio.cctsystem.membership;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.cctstudio.cctsystem.promotion.Promotion;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class MembershipPricingTest {
    private static final Instant NOW = Instant.parse("2026-08-15T04:00:00Z");
    private static final MembershipTier VIP = tier("vip", 100, 100);
    private static final MembershipTier MVP = tier("mvp", 300, 400);

    @Test
    void appliesPromotionAndCreditsTheOldActualPurchaseValue() {
        MembershipEntitlement active = new MembershipEntitlement(
            UUID.randomUUID(), VIP, EntitlementState.ACTIVE, NOW.minusSeconds(100),
            NOW.plusSeconds(15L * 86_400L), null, null,
            80, 30L * 86_400L, NOW.minusSeconds(15L * 86_400L)
        );
        Promotion promotion = new Promotion(
            UUID.randomUUID(), "八折", null, 2_000, NOW.minusSeconds(1), NOW.plusSeconds(3600), 1, "ACTIVE"
        );

        MembershipQuote quote = MembershipPricing.quote(
            MVP, 1, UpgradeMode.CREDIT, active, 100, promotion, NOW, 60
        );

        assertEquals(400, quote.basePricePoints());
        assertEquals(320, quote.discountedPricePoints());
        assertEquals(32, quote.upgradeCreditPoints());
        assertEquals(288, quote.finalPricePoints());
    }

    @Test
    void discountRoundsUpAndCreditRoundsDown() {
        MembershipEntitlement active = new MembershipEntitlement(
            UUID.randomUUID(), VIP, EntitlementState.ACTIVE, NOW,
            NOW.plusSeconds(86_400L), null, null
        );
        Promotion promotion = new Promotion(
            UUID.randomUUID(), "33.33%", null, 3_333, NOW, NOW.plusSeconds(3600), 1, "ACTIVE"
        );

        MembershipQuote quote = MembershipPricing.quote(
            MVP, 1, UpgradeMode.CREDIT, active, 100, promotion, NOW, 60
        );

        assertEquals(267, quote.discountedPricePoints());
        assertEquals(2, quote.upgradeCreditPoints());
        assertEquals(265, quote.finalPricePoints());
    }

    @Test
    void adminGrantedTimeNeverProducesUpgradeCredit() {
        MembershipEntitlement adminGranted = new MembershipEntitlement(
            UUID.randomUUID(), VIP, EntitlementState.ACTIVE, NOW,
            NOW.plusSeconds(30L * 86_400L), null, null,
            0, 0, null
        );

        MembershipQuote quote = MembershipPricing.quote(
            MVP, 1, UpgradeMode.CREDIT, adminGranted, 100, Promotion.none(), NOW, 60
        );

        assertEquals(0, quote.upgradeCreditPoints());
        assertEquals(400, quote.finalPricePoints());
    }

    @Test
    void forbidsPurchasingBelowPausedHighestTier() {
        MembershipException exception = assertThrows(MembershipException.class, () ->
            MembershipPricing.quote(VIP, 1, UpgradeMode.NONE, null, 300, Promotion.none(), NOW, 60)
        );
        assertEquals("MEMBERSHIP_DOWNGRADE_FORBIDDEN", exception.code());
    }

    @Test
    void requiresAnUpgradeChoice() {
        MembershipEntitlement active = new MembershipEntitlement(
            UUID.randomUUID(), VIP, EntitlementState.ACTIVE, NOW, NOW.plusSeconds(3600), null, null
        );
        MembershipException exception = assertThrows(MembershipException.class, () ->
            MembershipPricing.quote(MVP, 1, UpgradeMode.NONE, active, 100, Promotion.none(), NOW, 60)
        );
        assertEquals("MEMBERSHIP_UPGRADE_MODE_REQUIRED", exception.code());
    }

    private static MembershipTier tier(String key, int priority, int price) {
        return new MembershipTier(
            key, key.toUpperCase(), priority, key, 30, price, 8_000,
            "GOLD_INGOT", List.of(), true, 1
        );
    }
}
