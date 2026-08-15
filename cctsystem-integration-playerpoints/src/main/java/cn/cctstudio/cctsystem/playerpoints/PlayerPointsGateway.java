package cn.cctstudio.cctsystem.playerpoints;

import cn.cctstudio.cctsystem.core.concurrent.PlatformTaskExecutor;
import cn.cctstudio.cctsystem.points.PointsGateway;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import org.black_ixx.playerpoints.PlayerPointsAPI;

public final class PlayerPointsGateway implements PointsGateway {
    private final PlayerPointsAPI api;
    private final PlatformTaskExecutor platformTasks;

    public PlayerPointsGateway(PlayerPointsAPI api, PlatformTaskExecutor platformTasks) {
        this.api = Objects.requireNonNull(api, "api");
        this.platformTasks = Objects.requireNonNull(platformTasks, "platformTasks");
    }

    @Override
    public CompletionStage<Integer> balance(UUID playerUuid) {
        return platformTasks.callMain(() -> api.look(playerUuid));
    }

    @Override
    public CompletionStage<Boolean> credit(UUID playerUuid, int points) {
        return platformTasks.callMain(() -> api.give(playerUuid, points));
    }

    @Override
    public CompletionStage<Boolean> debit(UUID playerUuid, int points) {
        return platformTasks.callMain(() -> api.take(playerUuid, points));
    }
}
