package cn.cctstudio.cctsystem.promotion;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

public interface PromotionService {
    CompletionStage<Promotion> activeForMembership(String tierKey, Instant now);

    CompletionStage<Promotion> create(CreatePromotionRequest request);

    CompletionStage<Boolean> stop(UUID promotionId, String actor, String reason, Instant now);

    CompletionStage<List<Promotion>> listCurrent(Instant now);

    CompletionStage<Promotion> find(UUID promotionId);
}
