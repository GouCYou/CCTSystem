package cn.cctstudio.cctsystem.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

public final class BridgeRpcRouter {
    private final Map<String, Function<BridgeRpcCall, CompletionStage<JsonNode>>> handlers = new ConcurrentHashMap<>();

    public void register(String operation, Function<JsonNode, CompletionStage<JsonNode>> handler) {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(handler, "handler");
        registerCall(operation, call -> handler.apply(call.payload()));
    }

    public void registerCall(
        String operation,
        Function<BridgeRpcCall, CompletionStage<JsonNode>> handler
    ) {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(handler, "handler");
        if (handlers.putIfAbsent(operation, handler) != null) {
            throw new IllegalStateException("RPC handler already registered: " + operation);
        }
    }

    public CompletionStage<JsonNode> handle(String operation, JsonNode payload) {
        return handle(new BridgeRpcCall("local", operation, null, payload));
    }

    public CompletionStage<JsonNode> handle(BridgeRpcCall call) {
        Function<BridgeRpcCall, CompletionStage<JsonNode>> handler = handlers.get(call.operation());
        if (handler == null) {
            return CompletableFuture.failedFuture(
                new RpcHandlingException("CAPABILITY_NOT_IMPLEMENTED", "Operation is not implemented", false)
            );
        }
        try {
            return handler.apply(call);
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }
}
