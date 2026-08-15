package cn.cctstudio.cctsystem.points;

import java.util.UUID;
import java.util.concurrent.CompletionStage;

public interface PointsService {
    CompletionStage<Integer> balance(UUID playerUuid);

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
