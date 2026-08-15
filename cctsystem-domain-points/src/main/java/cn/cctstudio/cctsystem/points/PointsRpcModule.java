package cn.cctstudio.cctsystem.points;

import cn.cctstudio.cctsystem.bridge.BridgeProvider;
import cn.cctstudio.cctsystem.bridge.BridgeRpcRouter;
import cn.cctstudio.cctsystem.bridge.RpcHandlingException;
import cn.cctstudio.cctsystem.contract.Capability;
import cn.cctstudio.cctsystem.contract.NodeRole;
import cn.cctstudio.cctsystem.contract.PlatformType;
import cn.cctstudio.cctsystem.core.module.CctModule;
import cn.cctstudio.cctsystem.core.module.ModuleContext;
import cn.cctstudio.cctsystem.core.module.ModuleDescriptor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class PointsRpcModule implements CctModule {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ModuleDescriptor DESCRIPTOR = new ModuleDescriptor(
        "points-rpc",
        "points",
        Set.of(PlatformType.PAPER),
        Set.of(NodeRole.POINTS_AUTHORITY),
        Set.of(BridgeProvider.ID, PointsServiceProvider.ID),
        Set.of(Capability.POINTS_READ)
    );

    @Override
    public ModuleDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public CompletableFuture<Void> start(ModuleContext context) {
        BridgeRpcRouter router = context.providers().find(BridgeProvider.KEY)
            .orElseThrow(() -> new IllegalStateException("Bridge router provider is unavailable"));
        PointsService points = context.providers().find(PointsServiceProvider.KEY)
            .orElseThrow(() -> new IllegalStateException("Points service provider is unavailable"));
        router.register(Capability.POINTS_READ.value(), payload -> readBalance(payload, points));
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> stop() {
        return CompletableFuture.completedFuture(null);
    }

    private static CompletionStage<JsonNode> readBalance(JsonNode payload, PointsService points) {
        UUID playerUuid = requireUuid(payload);
        return points.balance(playerUuid).thenApply(balance -> {
            ObjectNode response = JSON.createObjectNode();
            response.put("balance", balance);
            return response;
        });
    }

    private static UUID requireUuid(JsonNode payload) {
        if (payload == null || !payload.isObject() || !payload.path("playerUuid").isTextual()) {
            throw invalidRequest();
        }
        try {
            return UUID.fromString(payload.path("playerUuid").textValue());
        } catch (IllegalArgumentException exception) {
            throw invalidRequest();
        }
    }

    private static RpcHandlingException invalidRequest() {
        return new RpcHandlingException("POINTS_REQUEST_INVALID", "Invalid points request", false);
    }
}
