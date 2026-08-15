package cn.cctstudio.cctsystem.contract;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;

public record RpcResponse(
    int version,
    String kind,
    String requestId,
    long sequence,
    boolean ok,
    JsonNode payload,
    RpcError error
) {
    public RpcResponse {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(requestId, "requestId");
    }
}
