package cn.cctstudio.cctsystem.exchange;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

public interface CurrencyGateway {
    CompletionStage<BigDecimal> balance(UUID playerUuid);

    CompletionStage<CurrencyMutationResult> withdraw(UUID playerUuid, BigDecimal amount);

    CompletionStage<CurrencyMutationResult> deposit(UUID playerUuid, BigDecimal amount);
}
