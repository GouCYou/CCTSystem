package cn.cctstudio.cctsystem.bridge;

import cn.cctstudio.cctsystem.contract.Capability;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.concurrent.CompletionStage;

public interface BridgeRpcClient {
    CompletionStage<JsonNode> call(
        Capability capability,
        String operation,
        JsonNode payload,
        String idempotencyKey,
        Duration timeout
    );
}
