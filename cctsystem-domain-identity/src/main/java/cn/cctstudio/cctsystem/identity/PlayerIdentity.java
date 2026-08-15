package cn.cctstudio.cctsystem.identity;

import java.util.Objects;
import java.util.UUID;

public record PlayerIdentity(UUID playerUuid, String displayName) {
    public PlayerIdentity {
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(displayName, "displayName");
    }
}
