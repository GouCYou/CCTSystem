package cn.cctstudio.cctsystem.membership;

import java.util.UUID;
import java.util.concurrent.CompletionStage;

public interface MembershipAccessGateway {
    CompletionStage<MembershipAccess> access(UUID playerUuid);
}
