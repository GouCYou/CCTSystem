package cn.cctstudio.cctsystem.membership;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class MembershipPurchasePolicyTest {
    private static final Instant NOW = Instant.parse("2026-08-15T08:00:00Z");
    private static final MembershipTier VIP = new MembershipTier(
        "vip", "VIP", 100, "vip", 30, 100, 10_000,
        "GOLD_INGOT", List.of(), true, 1
    );

    @Test
    void rejectsWhenExpiryAfterPurchaseWouldExceed365Days() {
        MembershipSummary summary = summaryWithExpiry(NOW.plus(Duration.ofDays(350)));

        MembershipException error = assertThrows(MembershipException.class, () ->
            MembershipPurchasePolicy.ensureDuration(summary, VIP, 1, NOW, 365)
        );

        assertEquals("MEMBERSHIP_DURATION_LIMIT", error.code());
    }

    @Test
    void acceptsWhenExpiryAfterPurchaseIsExactly365Days() {
        MembershipSummary summary = summaryWithExpiry(NOW.plus(Duration.ofDays(335)));

        assertDoesNotThrow(() ->
            MembershipPurchasePolicy.ensureDuration(summary, VIP, 1, NOW, 365)
        );
    }

    @Test
    void blocksStaffGroupEvenWhenItIsNotPrimary() {
        MembershipAccess access = new MembershipAccess(Set.of("default", "helper"));

        MembershipException error = assertThrows(MembershipException.class, () ->
            MembershipPurchasePolicy.ensureAccess(access, Set.of("helper", "mod", "admin", "owner"))
        );

        assertEquals("MEMBERSHIP_PURCHASE_FORBIDDEN", error.code());
    }

    private static MembershipSummary summaryWithExpiry(Instant expiry) {
        MembershipEntitlement active = new MembershipEntitlement(
            UUID.randomUUID(), VIP, EntitlementState.ACTIVE, NOW, expiry, null, null
        );
        return new MembershipSummary(active, List.of(), "APPLIED", NOW);
    }
}
