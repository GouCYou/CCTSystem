package cn.cctstudio.cctsystem.points;

import java.time.Instant;
import java.util.UUID;

public record PointsLedgerEntry(
    UUID operationId,
    int deltaPoints,
    Integer balanceAfter,
    String sourceType,
    String sourceReference,
    Instant createdAt
) {
}
