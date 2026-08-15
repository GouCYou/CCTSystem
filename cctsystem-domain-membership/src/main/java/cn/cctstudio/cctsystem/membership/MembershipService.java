package cn.cctstudio.cctsystem.membership;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

public interface MembershipService {
    CompletionStage<List<MembershipTier>> catalog();

    CompletionStage<MembershipSummary> summary(UUID playerUuid);

    CompletionStage<MembershipQuote> quote(
        UUID playerUuid,
        String tierKey,
        int months,
        UpgradeMode upgradeMode
    );

    CompletionStage<MembershipOrderResult> purchase(MembershipPurchaseRequest request);

    CompletionStage<MembershipSummary> reconcile(UUID playerUuid);

    CompletionStage<MembershipSummary> admin(AdminMembershipRequest request);
}
