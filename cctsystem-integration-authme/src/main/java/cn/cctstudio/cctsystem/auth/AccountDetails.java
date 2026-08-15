package cn.cctstudio.cctsystem.auth;

import java.time.Instant;
import java.util.Optional;

public record AccountDetails(
    Optional<String> email,
    Instant registeredAt,
    Optional<Instant> lastLoginAt,
    Optional<String> lastLoginIp
) {
    public AccountDetails {
        email = email == null ? Optional.empty() : email;
        lastLoginAt = lastLoginAt == null ? Optional.empty() : lastLoginAt;
        lastLoginIp = lastLoginIp == null ? Optional.empty() : lastLoginIp;
    }
}
