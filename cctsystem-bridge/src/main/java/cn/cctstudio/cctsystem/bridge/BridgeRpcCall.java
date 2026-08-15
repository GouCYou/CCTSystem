package cn.cctstudio.cctsystem.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;

public record BridgeRpcCall(
    String requestId,
    String operation,
    String idempotencyKey,
    JsonNode payload
) {
    public BridgeRpcCall {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(payload, "payload");
    }
}
