package cn.cctstudio.cctsystem.membership;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

final class MembershipPurchasePolicy {
    private MembershipPurchasePolicy() {
    }

    static boolean isBlocked(MembershipAccess access, Set<String> blockedGroups) {
        return access.belongsToAny(blockedGroups);
    }

    static void ensureAccess(MembershipAccess access, Set<String> blockedGroups) {
        if (isBlocked(access, blockedGroups)) {
            throw new MembershipException(
                "MEMBERSHIP_PURCHASE_FORBIDDEN",
                "This permission group cannot purchase memberships",
                false
            );
        }
    }

    static void ensureDuration(
        MembershipSummary summary,
        MembershipTier target,
        int months,
        Instant now,
        int maxPurchaseDays
    ) {
        if (months < 1) return;
        MembershipEntitlement active = summary.active();
        Instant base = active != null
            && active.tier().key().equals(target.key())
            && active.expiresAt() != null
            && active.expiresAt().isAfter(now)
            ? active.expiresAt()
            : now;
        long seconds = Math.multiplyExact(target.durationSeconds(), (long) months);
        if (base.plusSeconds(seconds).isAfter(now.plus(Duration.ofDays(maxPurchaseDays)))) {
            throw new MembershipException(
                "MEMBERSHIP_DURATION_LIMIT",
                "Membership validity cannot exceed the configured limit",
                false
            );
        }
    }
}
