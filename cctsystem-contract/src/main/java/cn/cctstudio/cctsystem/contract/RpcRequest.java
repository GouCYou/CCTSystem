package cn.cctstudio.cctsystem.contract;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;

public record RpcRequest(
    int version,
    String kind,
    String requestId,
    long sequence,
    String operation,
    String deadline,
    String idempotencyKey,
    JsonNode payload
) {
    public RpcRequest {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(deadline, "deadline");
        Objects.requireNonNull(payload, "payload");
    }
}
