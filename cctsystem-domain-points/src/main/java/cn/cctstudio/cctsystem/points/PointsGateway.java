package cn.cctstudio.cctsystem.points;

import java.util.UUID;
import java.util.concurrent.CompletionStage;

public interface PointsGateway {
    CompletionStage<Integer> balance(UUID playerUuid);

    CompletionStage<Boolean> credit(UUID playerUuid, int points);

    CompletionStage<Boolean> debit(UUID playerUuid, int points);
}
