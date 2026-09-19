package cn.cctstudio.cctsystem.redeem;

import cn.cctstudio.cctsystem.bridge.BridgeCallException;
import cn.cctstudio.cctsystem.bridge.BridgeRpcClient;
import cn.cctstudio.cctsystem.contract.Capability;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

final class RemoteRedeemCodeService implements RedeemCodeService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(8);
    private final BridgeRpcClient bridge;

    RemoteRedeemCodeService(BridgeRpcClient bridge) {
        this.bridge = bridge;
    }

    @Override
    public CompletionStage<GeneratedRedeemBatch> generate(GenerateRedeemCodesRequest request) {
        return CompletableFuture.failedFuture(new RedeemException(
            "REDEEM_GENERATE_REMOTE_UNAVAILABLE",
            "Redeem codes can only be generated on a business authority node",
            false
        ));
    }

    @Override
    public CompletionStage<RedeemResult> redeem(RedeemRequest request) {
        ObjectNode payload = JSON.createObjectNode();
        payload.put("playerUuid", request.playerUuid().toString());
        payload.put("code", request.code());
        payload.put("origin", request.origin());

        CompletableFuture<RedeemResult> result = new CompletableFuture<>();
        bridge.call(
            Capability.REDEEM_EXECUTE,
            RedeemRpcModule.OPERATION,
            payload,
            request.idempotencyKey(),
            TIMEOUT
        ).whenComplete((response, failure) -> {
            if (failure == null) {
                try {
                    result.complete(parse(response));
                } catch (RuntimeException invalidResponse) {
                    result.completeExceptionally(new RedeemException(
                        "REDEEM_RESPONSE_INVALID", "Invalid redeem response", true
                    ));
                }
                return;
            }
            Throwable cause = unwrap(failure);
            if (cause instanceof BridgeCallException bridgeFailure) {
                result.completeExceptionally(new RedeemException(
                    bridgeFailure.code(), bridgeFailure.getMessage(), bridgeFailure.retryable()
                ));
            } else {
                result.completeExceptionally(cause);
            }
        });
        return result;
    }

    private static RedeemResult parse(JsonNode node) {
        if (!node.isObject() || !node.path("transactionId").isTextual()
            || !node.path("status").isTextual() || !node.path("rewards").isArray()) {
            throw new IllegalArgumentException("redeem response");
        }
        List<RedeemRewardResult> rewards = new ArrayList<>();
        node.path("rewards").forEach(reward -> rewards.add(new RedeemRewardResult(
            RedeemRewardType.valueOf(requiredText(reward, "type")),
            requiredText(reward, "description"),
            requiredText(reward, "status"),
            nullableText(reward, "errorCode")
        )));
        return new RedeemResult(
            UUID.fromString(node.path("transactionId").textValue()),
            node.path("status").textValue(),
            List.copyOf(rewards),
            nullableText(node, "errorCode")
        );
    }

    private static String requiredText(JsonNode node, String field) {
        if (!node.path(field).isTextual()) throw new IllegalArgumentException(field);
        return node.path(field).textValue();
    }

    private static String nullableText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNull() || value.isMissingNode() ? null : requiredText(node, field);
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
