package cn.cctstudio.cctsystem.exchange;

import java.util.Objects;
import java.util.UUID;

public record ExchangeRequest(
    UUID playerUuid,
    String sourceId,
    int requestedPoints,
    ExchangeOrigin origin,
    String idempotencyKey
) {
    public ExchangeRequest {
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    }
}
