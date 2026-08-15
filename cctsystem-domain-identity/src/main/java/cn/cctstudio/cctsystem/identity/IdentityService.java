package cn.cctstudio.cctsystem.identity;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

public interface IdentityService {
    CompletionStage<PlayerIdentity> recordSeen(UUID playerUuid, String displayName, Instant seenAt);

    CompletionStage<Optional<PlayerIdentity>> findByUuid(UUID playerUuid);

    CompletionStage<Optional<PlayerIdentity>> findByName(String playerName);
}
