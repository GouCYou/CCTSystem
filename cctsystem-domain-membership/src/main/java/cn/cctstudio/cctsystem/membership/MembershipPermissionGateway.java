package cn.cctstudio.cctsystem.membership;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

public interface MembershipPermissionGateway {
    CompletionStage<Void> project(
        UUID playerUuid,
        Set<String> managedGroups,
        String desiredGroup,
        Instant desiredExpiry
    );

    default CompletionStage<Boolean> matches(
        UUID playerUuid,
        Set<String> managedGroups,
        String desiredGroup,
        Instant desiredExpiry
    ) {
        return java.util.concurrent.CompletableFuture.completedFuture(false);
    }
}
