package cn.cctstudio.cctsystem.exchange;

import java.math.BigDecimal;
import java.time.Instant;

public record ExchangeQuote(
    String sourceId,
    String currencyDisplayName,
    BigDecimal currencyUnitsPerPoint,
    BigDecimal currencyBalance,
    int pointsBalance,
    int weeklyLimitPoints,
    int weeklyUsedPoints,
    int weeklyReservedPoints,
    int weeklyRemainingPoints,
    int maximumExchangeablePoints,
    Instant resetsAt
) {
    public BigDecimal costFor(int points) {
        return currencyUnitsPerPoint.multiply(BigDecimal.valueOf(points));
    }
}
