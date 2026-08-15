package cn.cctstudio.cctsystem.exchange;

import java.math.BigDecimal;

public record CurrencyMutationResult(
    boolean successful,
    BigDecimal balanceBefore,
    BigDecimal balanceAfter,
    String errorCode
) {
}
