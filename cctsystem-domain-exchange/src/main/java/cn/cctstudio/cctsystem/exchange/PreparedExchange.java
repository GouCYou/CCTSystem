package cn.cctstudio.cctsystem.exchange;

import java.math.BigDecimal;
import java.util.UUID;

record PreparedExchange(
    boolean proceed,
    UUID transactionId,
    BigDecimal currencyCost,
    ExchangeResult existingResult
) {
    static PreparedExchange proceed(UUID transactionId, BigDecimal currencyCost) {
        return new PreparedExchange(true, transactionId, currencyCost, null);
    }

    static PreparedExchange existing(ExchangeResult result) {
        return new PreparedExchange(false, result.transactionId(), result.currencyCost(), result);
    }
}
