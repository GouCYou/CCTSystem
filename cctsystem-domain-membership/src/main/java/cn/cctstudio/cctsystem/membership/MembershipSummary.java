package cn.cctstudio.cctsystem.membership;

import java.time.Instant;
import java.util.List;

public record MembershipSummary(
    MembershipEntitlement active,
    List<MembershipEntitlement> paused,
    String permissionSyncStatus,
    Instant checkedAt
) {
    public MembershipSummary {
        paused = List.copyOf(paused);
    }
}
