package cn.cctstudio.cctsystem.membership;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

public interface MembershipService {
    CompletionStage<List<MembershipTier>> catalog();

    CompletionStage<MembershipSummary> summary(UUID playerUuid);

    default CompletionStage<MembershipMenuSnapshot> menu(UUID playerUuid) {
        return java.util.concurrent.CompletableFuture.failedFuture(new MembershipException(
            "MEMBERSHIP_MENU_UNAVAILABLE",
            "Membership menu snapshot is unavailable",
            true
        ));
    }

    CompletionStage<MembershipQuote> quote(
        UUID playerUuid,
        String tierKey,
        int months,
        UpgradeMode upgradeMode
    );

    CompletionStage<MembershipOrderResult> purchase(MembershipPurchaseRequest request);

    CompletionStage<MembershipSummary> reconcile(UUID playerUuid);

    CompletionStage<MembershipSummary> admin(AdminMembershipRequest request);

    default CompletionStage<MembershipSummary> grantSocialBindingReward(UUID playerUuid) {
        return java.util.concurrent.CompletableFuture.failedFuture(new MembershipException(
            "MEMBERSHIP_SOCIAL_REWARD_UNAVAILABLE", "Social binding reward is unavailable", true
        ));
    }
}
