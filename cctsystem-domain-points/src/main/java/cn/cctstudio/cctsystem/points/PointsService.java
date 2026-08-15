package cn.cctstudio.cctsystem.points;

import java.util.UUID;
import java.util.concurrent.CompletionStage;

public interface PointsService {
    CompletionStage<Integer> balance(UUID playerUuid);

    default CompletionStage<PointsLedgerPage> history(UUID playerUuid, int page, int pageSize) {
        return java.util.concurrent.CompletableFuture.failedFuture(
            new UnsupportedOperationException("Points history is unavailable")
        );
    }

    CompletionStage<PointsMutationResult> credit(
        UUID playerUuid,
        int points,
        UUID operationId,
        String sourceType,
        String sourceReference
    );

    CompletionStage<PointsMutationResult> debit(
        UUID playerUuid,
        int points,
        UUID operationId,
        String sourceType,
        String sourceReference
    );
}
