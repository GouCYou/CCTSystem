package cn.cctstudio.cctsystem.exchange;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ExchangeResult(
    UUID transactionId,
    ExchangeStatus status,
    String sourceId,
    int requestedPoints,
    BigDecimal currencyCost,
    int weeklyUsedPoints,
    int weeklyReservedPoints,
    int weeklyRemainingPoints,
    Instant resetsAt,
    String errorCode
) {
    public boolean completed() {
        return status == ExchangeStatus.COMPLETED;
    }
}
