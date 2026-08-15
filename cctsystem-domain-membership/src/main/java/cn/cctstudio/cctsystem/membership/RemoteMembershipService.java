package cn.cctstudio.cctsystem.membership;

import cn.cctstudio.cctsystem.bridge.BridgeCallException;
import cn.cctstudio.cctsystem.bridge.BridgeRpcClient;
import cn.cctstudio.cctsystem.contract.Capability;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

final class RemoteMembershipService implements MembershipService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(8);
    private final BridgeRpcClient bridge;

    RemoteMembershipService(BridgeRpcClient bridge) {
        this.bridge = bridge;
    }

    @Override
    public CompletionStage<List<MembershipTier>> catalog() {
        return call(Capability.MEMBERSHIP_READ, MembershipRpcModule.CATALOG_OPERATION,
            JSON.createObjectNode(), null, RemoteMembershipService::catalog);
    }

    @Override
    public CompletionStage<MembershipSummary> summary(UUID playerUuid) {
        ObjectNode payload = JSON.createObjectNode().put("playerUuid", playerUuid.toString());
        return call(Capability.MEMBERSHIP_READ, MembershipRpcModule.SUMMARY_OPERATION,
            payload, null, RemoteMembershipService::summary);
    }

    @Override
    public CompletionStage<MembershipMenuSnapshot> menu(UUID playerUuid) {
        ObjectNode payload = JSON.createObjectNode().put("playerUuid", playerUuid.toString());
        return call(Capability.MEMBERSHIP_READ, MembershipRpcModule.MENU_OPERATION,
            payload, null, RemoteMembershipService::menuSnapshot);
    }

    @Override
    public CompletionStage<MembershipQuote> quote(
        UUID playerUuid,
        String tierKey,
        int months,
        UpgradeMode upgradeMode
    ) {
        ObjectNode payload = JSON.createObjectNode();
        payload.put("playerUuid", playerUuid.toString());
        payload.put("tierKey", tierKey);
        payload.put("months", months);
        payload.put("upgradeMode", upgradeMode.name());
        return call(Capability.MEMBERSHIP_READ, MembershipRpcModule.QUOTE_OPERATION,
            payload, null, RemoteMembershipService::quote);
    }

    @Override
    public CompletionStage<MembershipOrderResult> purchase(MembershipPurchaseRequest request) {
        ObjectNode payload = JSON.createObjectNode();
        payload.put("playerUuid", request.playerUuid().toString());
        payload.put("tierKey", request.tierKey());
        payload.put("months", request.months());
        payload.put("upgradeMode", request.upgradeMode().name());
        payload.put("origin", request.origin());
        return call(Capability.MEMBERSHIP_MUTATE, MembershipRpcModule.PURCHASE_OPERATION,
            payload, request.idempotencyKey(), RemoteMembershipService::order);
    }

    @Override
    public CompletionStage<MembershipSummary> reconcile(UUID playerUuid) {
        return summary(playerUuid);
    }

    @Override
    public CompletionStage<MembershipSummary> admin(AdminMembershipRequest request) {
        return CompletableFuture.failedFuture(new MembershipException(
            "MEMBERSHIP_ADMIN_UNAVAILABLE",
            "Membership administration must run on the business authority",
            false
        ));
    }

    private <T> CompletionStage<T> call(
        Capability capability,
        String operation,
        JsonNode payload,
        String idempotencyKey,
        Function<JsonNode, T> parser
    ) {
        CompletableFuture<T> result = new CompletableFuture<>();
        bridge.call(capability, operation, payload, idempotencyKey, TIMEOUT)
            .whenComplete((response, failure) -> {
                if (failure == null) {
                    try {
                        result.complete(parser.apply(response));
                    } catch (RuntimeException invalidResponse) {
                        result.completeExceptionally(new MembershipException(
                            "MEMBERSHIP_RESPONSE_INVALID", "Invalid membership response", true
                        ));
                    }
                    return;
                }
                Throwable cause = unwrap(failure);
                if (cause instanceof BridgeCallException bridgeFailure) {
                    result.completeExceptionally(new MembershipException(
                        bridgeFailure.code(), bridgeFailure.getMessage(), bridgeFailure.retryable()
                    ));
                } else {
                    result.completeExceptionally(cause);
                }
            });
        return result;
    }

    private static List<MembershipTier> catalog(JsonNode node) {
        if (!node.isArray()) {
            throw new IllegalArgumentException("catalog");
        }
        List<MembershipTier> tiers = new ArrayList<>();
        node.forEach(value -> tiers.add(tier(value)));
        return List.copyOf(tiers);
    }

    private static MembershipSummary summary(JsonNode node) {
        List<MembershipEntitlement> paused = new ArrayList<>();
        node.path("paused").forEach(value -> paused.add(entitlement(value)));
        return new MembershipSummary(
            node.path("active").isNull() ? null : entitlement(node.path("active")),
            paused,
            text(node, "permissionSyncStatus"),
            instant(node, "checkedAt")
        );
    }

    private static MembershipMenuSnapshot menuSnapshot(JsonNode node) {
        List<MembershipMenuTier> tiers = new ArrayList<>();
        node.path("tiers").forEach(value -> tiers.add(new MembershipMenuTier(
            tier(value.path("tier")),
            value.path("quote").isNull() ? null : quote(value.path("quote")),
            nullableText(value, "unavailableCode")
        )));
        return new MembershipMenuSnapshot(summary(node.path("summary")), tiers);
    }

    private static MembershipQuote quote(JsonNode node) {
        return new MembershipQuote(
            uuid(node, "quoteId"),
            tier(node.path("tier")),
            node.path("months").asInt(),
            UpgradeMode.valueOf(text(node, "upgradeMode")),
            node.path("basePricePoints").asInt(),
            nullableUuid(node, "promotionId"),
            node.path("promotionOffBps").asInt(),
            nullableInstant(node, "promotionEndsAt"),
            node.path("discountedPricePoints").asInt(),
            node.path("upgradeCreditPoints").asInt(),
            node.path("finalPricePoints").asInt(),
            node.path("current").isNull() ? null : entitlement(node.path("current")),
            instant(node, "validUntil"),
            instant(node, "quotedAt")
        );
    }

    private static MembershipOrderResult order(JsonNode node) {
        return new MembershipOrderResult(
            uuid(node, "orderId"),
            MembershipOrderStatus.valueOf(text(node, "status")),
            text(node, "tierKey"),
            node.path("months").asInt(),
            node.path("finalPricePoints").asInt(),
            nullableUuid(node, "entitlementId"),
            nullableInstant(node, "expiresAt"),
            text(node, "permissionSyncStatus"),
            nullableText(node, "errorCode")
        );
    }

    private static MembershipEntitlement entitlement(JsonNode node) {
        JsonNode tierNode = node.path("tier");
        MembershipTier tier = tierNode.isObject() ? tier(tierNode) : new MembershipTier(
            text(node, "tierKey"), text(node, "displayName"), 0, "", 30, 0, 0,
            "NAME_TAG", List.of(), true, 0
        );
        return new MembershipEntitlement(
            uuid(node, "entitlementId"),
            tier,
            EntitlementState.valueOf(text(node, "state")),
            nullableInstant(node, "startsAt"),
            nullableInstant(node, "expiresAt"),
            nullableLong(node, "remainingSeconds"),
            nullableLong(node, "resumeSequence")
        );
    }

    private static MembershipTier tier(JsonNode node) {
        List<String> benefits = new ArrayList<>();
        node.path("benefits").forEach(value -> benefits.add(value.asText()));
        return new MembershipTier(
            text(node, "key"),
            text(node, "displayName"),
            node.path("priority").asInt(),
            node.path("luckPermsGroup").asText(""),
            node.path("durationDays").asInt(),
            node.path("pricePoints").asInt(),
            node.path("upgradeCreditRateBps").asInt(),
            text(node, "displayMaterial"),
            benefits,
            node.path("enabled").asBoolean(true),
            node.path("version").asLong()
        );
    }

    private static String text(JsonNode node, String field) {
        if (!node.path(field).isTextual()) {
            throw new IllegalArgumentException(field);
        }
        return node.path(field).textValue();
    }

    private static String nullableText(JsonNode node, String field) {
        return node.path(field).isNull() || node.path(field).isMissingNode()
            ? null : text(node, field);
    }

    private static UUID uuid(JsonNode node, String field) {
        return UUID.fromString(text(node, field));
    }

    private static UUID nullableUuid(JsonNode node, String field) {
        String value = nullableText(node, field);
        return value == null ? null : UUID.fromString(value);
    }

    private static Instant instant(JsonNode node, String field) {
        return Instant.parse(text(node, field));
    }

    private static Instant nullableInstant(JsonNode node, String field) {
        String value = nullableText(node, field);
        return value == null ? null : Instant.parse(value);
    }

    private static Long nullableLong(JsonNode node, String field) {
        return node.path(field).isNumber() ? node.path(field).longValue() : null;
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
