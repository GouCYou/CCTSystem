package cn.cctstudio.cctsystem.membership;

import cn.cctstudio.cctsystem.core.id.UuidV7;
import cn.cctstudio.cctsystem.promotion.Promotion;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;

final class MembershipPricing {
    private MembershipPricing() {
    }

    static MembershipQuote quote(
        MembershipTier target,
        int months,
        UpgradeMode requestedMode,
        MembershipEntitlement active,
        int highestOwnedPriority,
        Promotion promotion,
        Instant now,
        int quoteTtlSeconds
    ) {
        if (months < 1 || months > 120) {
            throw error("MEMBERSHIP_MONTHS_INVALID", "Membership months must be between 1 and 120");
        }
        if (highestOwnedPriority > target.priority()) {
            throw error("MEMBERSHIP_DOWNGRADE_FORBIDDEN", "A lower membership tier cannot be purchased");
        }

        UpgradeMode mode = UpgradeMode.NONE;
        if (active != null && target.priority() > active.tier().priority()) {
            if (requestedMode != UpgradeMode.PAUSE && requestedMode != UpgradeMode.CREDIT) {
                throw error("MEMBERSHIP_UPGRADE_MODE_REQUIRED", "Choose how to handle the current membership");
            }
            mode = requestedMode;
        } else if (active != null && target.priority() < active.tier().priority()) {
            throw error("MEMBERSHIP_DOWNGRADE_FORBIDDEN", "A lower membership tier cannot be purchased");
        }

        long baseLong = Math.multiplyExact((long) target.pricePoints(), months);
        if (baseLong > Integer.MAX_VALUE) {
            throw error("MEMBERSHIP_PRICE_TOO_LARGE", "Membership price is too large");
        }
        int basePrice = (int) baseLong;
        int offBps = promotion.active() ? promotion.percentOffBps() : 0;
        int discounted = BigDecimal.valueOf(basePrice)
            .multiply(BigDecimal.valueOf(10_000L - offBps))
            .divide(BigDecimal.valueOf(10_000L), 0, RoundingMode.CEILING)
            .intValueExact();
        int credit = mode == UpgradeMode.CREDIT
            ? credit(active, now, discounted)
            : 0;
        Instant validUntil = now.plusSeconds(quoteTtlSeconds);
        if (promotion.active() && promotion.endsAt().isBefore(validUntil)) {
            validUntil = promotion.endsAt();
        }
        return new MembershipQuote(
            UuidV7.create(now),
            target,
            months,
            mode,
            basePrice,
            promotion.promotionId(),
            offBps,
            promotion.active() ? promotion.endsAt() : null,
            discounted,
            credit,
            discounted - credit,
            active,
            validUntil,
            now
        );
    }

    static int remainingSeconds(MembershipEntitlement entitlement, Instant now) {
        if (entitlement == null || entitlement.expiresAt() == null) {
            return 0;
        }
        long seconds = Math.max(0, Duration.between(now, entitlement.expiresAt()).toSeconds());
        return (int) Math.min(Integer.MAX_VALUE, seconds);
    }

    static int remainingPaidValue(MembershipEntitlement active, Instant now) {
        long remaining = remainingSeconds(active, now);
        if (remaining == 0 || active == null || active.creditBasisPoints() <= 0
            || active.creditBasisSeconds() <= 0) {
            return 0;
        }
        long creditedSeconds = Math.min(remaining, active.creditBasisSeconds());
        return BigDecimal.valueOf(active.creditBasisPoints())
            .multiply(BigDecimal.valueOf(creditedSeconds))
            .divide(BigDecimal.valueOf(active.creditBasisSeconds()), 0, RoundingMode.FLOOR)
            .intValueExact();
    }

    private static int credit(MembershipEntitlement active, Instant now, int maximum) {
        int remainingPaidValue = remainingPaidValue(active, now);
        if (remainingPaidValue == 0 || maximum == 0) {
            return 0;
        }
        BigDecimal raw = BigDecimal.valueOf(remainingPaidValue)
            .multiply(BigDecimal.valueOf(active.tier().upgradeCreditRateBps()))
            .divide(BigDecimal.valueOf(10_000), 0, RoundingMode.FLOOR);
        return Math.min(maximum, raw.intValueExact());
    }

    private static MembershipException error(String code, String message) {
        return new MembershipException(code, message, false);
    }
}
