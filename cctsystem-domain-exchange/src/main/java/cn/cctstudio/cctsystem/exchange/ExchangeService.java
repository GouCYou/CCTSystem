package cn.cctstudio.cctsystem.exchange;

import java.util.UUID;
import java.util.concurrent.CompletionStage;

public interface ExchangeService {
    CompletionStage<ExchangeQuote> quote(UUID playerUuid, String sourceId);

    CompletionStage<ExchangeResult> execute(ExchangeRequest request);
}
