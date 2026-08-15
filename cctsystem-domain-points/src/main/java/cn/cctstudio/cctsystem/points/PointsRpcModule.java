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
import com.fasterxml.jackson.databind.node.ArrayNode;
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
        Set.of(Capability.POINTS_READ, Capability.POINTS_HISTORY_READ, Capability.POINTS_MUTATE)
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
        router.register(Capability.POINTS_HISTORY_READ.value(), payload -> readHistory(payload, points));
        router.register(Capability.POINTS_MUTATE.value(), payload -> credit(payload, points));
        return CompletableFuture.completedFuture(null);
    }

    private static CompletionStage<JsonNode> readHistory(JsonNode payload, PointsService points) {
        UUID playerUuid = requireUuid(payload);
        int page = payload.path("page").asInt(-1);
        int pageSize = payload.path("pageSize").asInt(-1);
        if (page < 1 || pageSize < 1 || pageSize > 50) {
            throw invalidRequest();
        }
        return points.history(playerUuid, page, pageSize).thenApply(history -> {
            ObjectNode response = JSON.createObjectNode();
            ArrayNode items = response.putArray("items");
            for (PointsLedgerEntry entry : history.items()) {
                ObjectNode item = items.addObject();
                item.put("operationId", entry.operationId().toString());
                item.put("deltaPoints", entry.deltaPoints());
                if (entry.balanceAfter() == null) item.putNull("balanceAfter");
                else item.put("balanceAfter", entry.balanceAfter());
                item.put("sourceType", entry.sourceType());
                item.put("sourceReference", entry.sourceReference());
                item.put("createdAt", entry.createdAt().toString());
            }
            response.put("page", history.page());
            response.put("pageSize", history.pageSize());
            response.put("totalItems", history.totalItems());
            response.put("totalPages", history.totalPages());
            return response;
        });
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

    private static CompletionStage<JsonNode> credit(JsonNode payload, PointsService points) {
        UUID playerUuid = requireUuid(payload);
        JsonNode amountNode = payload.path("points");
        int amount = amountNode.canConvertToInt() ? amountNode.intValue() : -1;
        String sourceType = requireText(payload, "sourceType", 32);
        String sourceReference = requireText(payload, "sourceReference", 80);
        UUID operationId;
        try {
            operationId = UUID.fromString(requireText(payload, "operationId", 36));
        } catch (IllegalArgumentException exception) {
            throw invalidRequest();
        }
        if (amount < 1) {
            throw invalidRequest();
        }
        return points.credit(playerUuid, amount, operationId, sourceType, sourceReference)
            .thenApply(result -> {
                ObjectNode response = JSON.createObjectNode();
                response.put("operationId", result.operationId().toString());
                response.put("disposition", result.disposition().name());
                if (result.balanceBefore() == null) response.putNull("balanceBefore");
                else response.put("balanceBefore", result.balanceBefore());
                if (result.balanceAfter() == null) response.putNull("balanceAfter");
                else response.put("balanceAfter", result.balanceAfter());
                if (result.errorCode() == null) response.putNull("errorCode");
                else response.put("errorCode", result.errorCode());
                return response;
            });
    }

    private static String requireText(JsonNode payload, String field, int maximumLength) {
        if (payload == null || !payload.isObject() || !payload.path(field).isTextual()) {
            throw invalidRequest();
        }
        String value = payload.path(field).textValue();
        if (value.isBlank() || value.length() > maximumLength) {
            throw invalidRequest();
        }
        return value;
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
