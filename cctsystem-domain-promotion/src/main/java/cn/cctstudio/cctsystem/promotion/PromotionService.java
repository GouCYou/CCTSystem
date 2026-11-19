package cn.cctstudio.cctsystem.promotion;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

public interface PromotionService {
    CompletionStage<Promotion> activeForMembership(String tierKey, Instant now);

    default CompletionStage<Promotion> forCode(String code, String tierKey, Instant now) {
        return java.util.concurrent.CompletableFuture.failedFuture(new IllegalArgumentException("Discount codes unavailable"));
    }

    default CompletionStage<Promotion> forCode(String code, String tierKey, UUID playerUuid, Instant now) {
        return forCode(code,tierKey,now);
    }

    default CompletionStage<Promotion> forCodeAny(String code, UUID playerUuid, Instant now) {
        return java.util.concurrent.CompletableFuture.failedFuture(new IllegalArgumentException("Discount code unavailable"));
    }

    default CompletionStage<List<Promotion>> listCodes(Instant now) { return java.util.concurrent.CompletableFuture.completedFuture(List.of()); }
    default CompletionStage<Promotion> updateCode(UUID id, CreatePromotionRequest request) { throw new UnsupportedOperationException(); }
    default CompletionStage<Boolean> deleteCode(UUID id) { throw new UnsupportedOperationException(); }

    CompletionStage<Promotion> create(CreatePromotionRequest request);

    CompletionStage<Boolean> stop(UUID promotionId, String actor, String reason, Instant now);

    CompletionStage<List<Promotion>> listCurrent(Instant now);

    CompletionStage<Promotion> find(UUID promotionId);
}
