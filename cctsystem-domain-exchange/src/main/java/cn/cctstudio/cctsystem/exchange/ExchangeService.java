package cn.cctstudio.cctsystem.exchange;

import java.util.UUID;
import java.util.concurrent.CompletionStage;

public interface ExchangeService {
    CompletionStage<ExchangeQuote> quote(UUID playerUuid, String sourceId);

    CompletionStage<ExchangeResult> execute(ExchangeRequest request);

    default CompletionStage<Boolean> grantSocialBindingReward(UUID playerUuid) {
        return java.util.concurrent.CompletableFuture.failedFuture(new ExchangeException(
            "SOCIAL_REWARD_UNAVAILABLE", "Social binding reward is unavailable", true
        ));
    }
}
