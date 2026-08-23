package cn.cctstudio.cctsystem.exchange;

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

public final class ExchangeRpcModule implements CctModule {
    public static final String QUOTE_OPERATION = "exchange.quote";
    public static final String SOCIAL_REWARD_OPERATION = "economy.social-binding-reward";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ModuleDescriptor DESCRIPTOR = new ModuleDescriptor(
        "exchange-rpc",
        "exchange",
        Set.of(PlatformType.PAPER),
        Set.of(NodeRole.ECONOMY_SOURCE),
        Set.of(BridgeProvider.ID, ExchangeServiceProvider.ID),
        Set.of(Capability.EXCHANGE_EXECUTE)
    );

    @Override
    public ModuleDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public CompletableFuture<Void> start(ModuleContext context) {
        BridgeRpcRouter router = context.providers().find(BridgeProvider.KEY)
            .orElseThrow(() -> new IllegalStateException("Bridge router provider is unavailable"));
        ExchangeService service = context.providers().find(ExchangeServiceProvider.KEY)
            .orElseThrow(() -> new IllegalStateException("Exchange service provider is unavailable"));
        router.register(QUOTE_OPERATION, payload -> mapErrors(quote(payload, service)));
        router.registerCall(Capability.EXCHANGE_EXECUTE.value(), call -> mapErrors(execute(call, service)));
        router.registerCall(SOCIAL_REWARD_OPERATION, call -> {
            if (call.idempotencyKey() == null || call.idempotencyKey().isBlank()) throw invalidRequest();
            return mapErrors(service.grantSocialBindingReward(requireUuid(call.payload()))
                .thenApply(delivered -> {
                    ObjectNode result = JSON.createObjectNode();
                    result.put("delivered", delivered);
                    return result;
                }));
        });
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> stop() {
        return CompletableFuture.completedFuture(null);
    }

    private static CompletionStage<JsonNode> quote(JsonNode payload, ExchangeService service) {
        UUID playerUuid = requireUuid(payload);
        String sourceId = requireText(payload, "sourceId", 64);
        try {
            return service.quote(playerUuid, sourceId).thenApply(ExchangeRpcModule::quoteJson);
        } catch (ExchangeException exception) {
            throw rpcError(exception);
        }
    }

    private static CompletionStage<JsonNode> execute(BridgeRpcCall call, ExchangeService service) {
        JsonNode payload = call.payload();
        UUID playerUuid = requireUuid(payload);
        String sourceId = requireText(payload, "sourceId", 64);
        String idempotencyKey = call.idempotencyKey();
        if (idempotencyKey == null) {
            throw invalidRequest();
        }
        int requestedPoints = payload.path("requestedPoints").asInt(0);
        ExchangeOrigin origin;
        try {
            origin = ExchangeOrigin.parse(requireText(payload, "origin", 24));
        } catch (IllegalArgumentException exception) {
            throw invalidRequest();
        }
        try {
            return service.execute(new ExchangeRequest(
                playerUuid,
                sourceId,
                requestedPoints,
                origin,
                idempotencyKey
            )).thenApply(ExchangeRpcModule::resultJson);
        } catch (ExchangeException exception) {
            throw rpcError(exception);
        }
    }

    private static ObjectNode quoteJson(ExchangeQuote quote) {
        ObjectNode result = JSON.createObjectNode();
        result.put("sourceId", quote.sourceId());
        result.put("currencyDisplayName", quote.currencyDisplayName());
        result.put("currencyUnitsPerPoint", quote.currencyUnitsPerPoint());
        result.put("currencyBalance", quote.currencyBalance());
        result.put("pointsBalance", quote.pointsBalance());
        result.put("weeklyLimitPoints", quote.weeklyLimitPoints());
        result.put("weeklyUsedPoints", quote.weeklyUsedPoints());
        result.put("weeklyReservedPoints", quote.weeklyReservedPoints());
        result.put("weeklyRemainingPoints", quote.weeklyRemainingPoints());
        result.put("maximumExchangeablePoints", quote.maximumExchangeablePoints());
        result.put("resetsAt", quote.resetsAt().toString());
        return result;
    }

    private static ObjectNode resultJson(ExchangeResult exchange) {
        ObjectNode result = JSON.createObjectNode();
        result.put("transactionId", exchange.transactionId().toString());
        result.put("status", exchange.status().name());
        result.put("sourceId", exchange.sourceId());
        result.put("requestedPoints", exchange.requestedPoints());
        result.put("currencyCost", exchange.currencyCost());
        result.put("weeklyUsedPoints", exchange.weeklyUsedPoints());
        result.put("weeklyReservedPoints", exchange.weeklyReservedPoints());
        result.put("weeklyRemainingPoints", exchange.weeklyRemainingPoints());
        result.put("resetsAt", exchange.resetsAt().toString());
        if (exchange.errorCode() == null) {
            result.putNull("errorCode");
        } else {
            result.put("errorCode", exchange.errorCode());
        }
        return result;
    }

    private static CompletionStage<JsonNode> mapErrors(CompletionStage<JsonNode> operation) {
        CompletableFuture<JsonNode> mapped = new CompletableFuture<>();
        operation.whenComplete((result, throwable) -> {
            if (throwable == null) {
                mapped.complete(result);
                return;
            }
            Throwable cause = unwrap(throwable);
            if (cause instanceof ExchangeException exchange) {
                mapped.completeExceptionally(new RpcHandlingException(
                    exchange.code(),
                    exchange.getMessage(),
                    exchange.retryable()
                ));
            } else {
                mapped.completeExceptionally(cause);
            }
        });
        return mapped;
    }

    private static UUID requireUuid(JsonNode payload) {
        try {
            return UUID.fromString(requireText(payload, "playerUuid", 36));
        } catch (IllegalArgumentException exception) {
            throw invalidRequest();
        }
    }

    private static String requireText(JsonNode payload, String field, int maxLength) {
        if (payload == null || !payload.isObject() || !payload.path(field).isTextual()) {
            throw invalidRequest();
        }
        String value = payload.path(field).textValue();
        if (value.isBlank() || value.length() > maxLength) {
            throw invalidRequest();
        }
        return value;
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static RpcHandlingException invalidRequest() {
        return new RpcHandlingException("EXCHANGE_REQUEST_INVALID", "Invalid exchange request", false);
    }

    private static RpcHandlingException rpcError(ExchangeException exchange) {
        return new RpcHandlingException(
            exchange.code(),
            exchange.getMessage(),
            exchange.retryable()
        );
    }
}
