package cn.cctstudio.cctsystem.redeem;

import cn.cctstudio.cctsystem.bridge.BridgeProvider;
import cn.cctstudio.cctsystem.bridge.BridgeRpcCall;
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
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

public final class RedeemRpcModule implements CctModule {
    public static final String OPERATION = "redeem.execute";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ModuleDescriptor DESCRIPTOR = new ModuleDescriptor(
        "redeem-rpc",
        "redeem",
        Set.of(PlatformType.PAPER),
        Set.of(NodeRole.BUSINESS_AUTHORITY),
        Set.of(BridgeProvider.ID, RedeemCodeServiceProvider.ID),
        Set.of(Capability.REDEEM_EXECUTE)
    );

    @Override
    public ModuleDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public CompletableFuture<Void> start(ModuleContext context) {
        BridgeRpcRouter router = context.providers().find(BridgeProvider.KEY).orElseThrow();
        RedeemCodeService service = context.providers().find(RedeemCodeServiceProvider.KEY).orElseThrow();
        router.registerCall(OPERATION, call -> mapErrors(execute(call, service)));
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> stop() {
        return CompletableFuture.completedFuture(null);
    }

    private static CompletionStage<JsonNode> execute(BridgeRpcCall call, RedeemCodeService service) {
        if (call.idempotencyKey() == null || call.idempotencyKey().isBlank()) {
            throw invalidRequest();
        }
        JsonNode payload = call.payload();
        UUID playerUuid;
        try {
            playerUuid = UUID.fromString(requireText(payload, "playerUuid", 36));
        } catch (IllegalArgumentException exception) {
            throw invalidRequest();
        }
        return service.redeem(new RedeemRequest(
            playerUuid,
            requireText(payload, "code", 48),
            call.idempotencyKey(),
            requireText(payload, "origin", 24)
        )).thenApply(RedeemRpcModule::json);
    }

    private static ObjectNode json(RedeemResult result) {
        ObjectNode root = JSON.createObjectNode();
        root.put("transactionId", result.useId().toString());
        root.put("status", result.status());
        if (result.errorCode() == null) root.putNull("errorCode");
        else root.put("errorCode", result.errorCode());
        var rewards = root.putArray("rewards");
        result.rewards().forEach(reward -> {
            ObjectNode item = rewards.addObject();
            item.put("type", reward.type().name());
            item.put("description", reward.description());
            item.put("status", reward.status());
            if (reward.errorCode() == null) item.putNull("errorCode");
            else item.put("errorCode", reward.errorCode());
        });
        return root;
    }

    private static String requireText(JsonNode payload, String field, int maxLength) {
        if (payload == null || !payload.isObject() || !payload.path(field).isTextual()) {
            throw invalidRequest();
        }
        String value = payload.path(field).textValue();
        if (value.isBlank() || value.length() > maxLength) throw invalidRequest();
        return value;
    }

    private static CompletionStage<JsonNode> mapErrors(CompletionStage<? extends JsonNode> operation) {
        CompletableFuture<JsonNode> result = new CompletableFuture<>();
        operation.whenComplete((value, failure) -> {
            if (failure == null) {
                result.complete(value);
                return;
            }
            Throwable cause = unwrap(failure);
            if (cause instanceof RedeemException redeem) {
                result.completeExceptionally(new RpcHandlingException(
                    redeem.code(), redeem.getMessage(), redeem.retryable()
                ));
            } else {
                result.completeExceptionally(cause);
            }
        });
        return result;
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static RpcHandlingException invalidRequest() {
        return new RpcHandlingException("REDEEM_REQUEST_INVALID", "Invalid redeem request", false);
    }
}
