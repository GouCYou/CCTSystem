package cn.cctstudio.cctsystem.auth;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record AuthVerification(
    boolean authenticated,
    String displayName,
    Optional<UUID> playerUuid
) {
    public AuthVerification {
        Objects.requireNonNull(displayName, "displayName");
        playerUuid = Objects.requireNonNull(playerUuid, "playerUuid");
    }

    public static AuthVerification rejected(String displayName) {
        return new AuthVerification(false, displayName, Optional.empty());
    }
}
