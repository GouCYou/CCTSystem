package cn.cctstudio.cctsystem.membership;

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
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

public final class MembershipRpcModule implements CctModule {
    public static final String CATALOG_OPERATION = "membership.catalog";
    public static final String SUMMARY_OPERATION = "membership.summary";
    public static final String MENU_OPERATION = "membership.menu";
    public static final String QUOTE_OPERATION = "membership.quote";
    public static final String PURCHASE_OPERATION = "membership.purchase";
    public static final String ADMIN_OPERATION = "membership.admin";
    public static final String SOCIAL_REWARD_OPERATION = "membership.social-binding-reward";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ModuleDescriptor DESCRIPTOR = new ModuleDescriptor(
        "membership-rpc",
        "membership",
        Set.of(PlatformType.PAPER),
        Set.of(NodeRole.BUSINESS_AUTHORITY),
        Set.of(BridgeProvider.ID, MembershipServiceProvider.ID),
        Set.of(Capability.MEMBERSHIP_READ, Capability.MEMBERSHIP_MUTATE)
    );

    @Override
    public ModuleDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public CompletableFuture<Void> start(ModuleContext context) {
        BridgeRpcRouter router = context.providers().find(BridgeProvider.KEY).orElseThrow();
        MembershipService service = context.providers().find(MembershipServiceProvider.KEY).orElseThrow();
        router.register(CATALOG_OPERATION, ignored -> mapErrors(
            service.catalog().thenApply(MembershipRpcModule::catalogJson)
        ));
        router.register(SUMMARY_OPERATION, payload -> mapErrors(
            service.summary(requireUuid(payload)).thenApply(MembershipRpcModule::summaryJson)
        ));
        router.register(MENU_OPERATION, payload -> mapErrors(
            service.menu(requireUuid(payload)).thenApply(MembershipRpcModule::menuJson)
        ));
        router.register(QUOTE_OPERATION, payload -> mapErrors(quote(payload, service)));
        router.registerCall(PURCHASE_OPERATION, call -> mapErrors(purchase(call, service)));
        router.register(ADMIN_OPERATION, payload -> mapErrors(
            service.admin(adminRequest(payload)).thenApply(MembershipRpcModule::summaryJson)
        ));
        router.registerCall(SOCIAL_REWARD_OPERATION, call -> {
            if (call.idempotencyKey() == null || call.idempotencyKey().isBlank()) throw invalidRequest();
            return mapErrors(service.grantSocialBindingReward(requireUuid(call.payload()))
                .thenApply(summary -> {
                    ObjectNode result = JSON.createObjectNode();
                    result.put("delivered", true);
                    result.set("membership", summaryJson(summary));
                    return result;
                }));
        });
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> stop() {
        return CompletableFuture.completedFuture(null);
    }

    private static CompletionStage<JsonNode> quote(JsonNode payload, MembershipService service) {
        return service.quote(
            requireUuid(payload),
            requireText(payload, "tierKey", 64),
            payload.path("months").asInt(0),
            requireMode(payload)
        ).thenApply(MembershipRpcModule::quoteJson);
    }

    private static CompletionStage<JsonNode> purchase(BridgeRpcCall call, MembershipService service) {
        if (call.idempotencyKey() == null || call.idempotencyKey().isBlank()) {
            throw invalidRequest();
        }
        JsonNode payload = call.payload();
        return service.purchase(new MembershipPurchaseRequest(
            requireUuid(payload),
            requireText(payload, "tierKey", 64),
            payload.path("months").asInt(0),
            requireMode(payload),
            call.idempotencyKey(),
            requireText(payload, "origin", 32)
        )).thenApply(MembershipRpcModule::resultJson);
    }

    private static AdminMembershipRequest adminRequest(JsonNode payload) {
        try {
            String tierKey = payload.path("tierKey").isNull() ? null
                : requireText(payload, "tierKey", 64);
            java.time.Instant expiresAt = payload.path("expiresAt").isNull() ? null
                : java.time.Instant.parse(requireText(payload, "expiresAt", 40));
            return new AdminMembershipRequest(
                AdminMembershipAction.valueOf(requireText(payload, "action", 32)),
                requireUuid(payload),
                tierKey,
                payload.path("days").asInt(-1),
                expiresAt,
                requireText(payload, "actor", 80),
                requireText(payload, "reason", 255)
            );
        } catch (IllegalArgumentException exception) {
            throw invalidRequest();
        }
    }

    private static ArrayNode catalogJson(java.util.List<MembershipTier> tiers) {
        ArrayNode result = JSON.createArrayNode();
        tiers.forEach(tier -> result.add(tierJson(tier)));
        return result;
    }

    private static ObjectNode tierJson(MembershipTier tier) {
        ObjectNode result = JSON.createObjectNode();
        result.put("key", tier.key());
        result.put("displayName", tier.displayName());
        result.put("priority", tier.priority());
        result.put("luckPermsGroup", tier.luckPermsGroup());
        result.put("durationDays", tier.durationDays());
        result.put("pricePoints", tier.pricePoints());
        result.put("upgradeCreditRateBps", tier.upgradeCreditRateBps());
        result.put("displayMaterial", tier.displayMaterial());
        ArrayNode benefits = result.putArray("benefits");
        tier.benefits().forEach(benefits::add);
        result.put("enabled", tier.enabled());
        result.put("version", tier.version());
        return result;
    }

    private static ObjectNode summaryJson(MembershipSummary summary) {
        ObjectNode result = JSON.createObjectNode();
        result.set("active", entitlementJson(summary.active()));
        ArrayNode paused = result.putArray("paused");
        summary.paused().forEach(entitlement -> paused.add(entitlementJson(entitlement)));
        result.put("permissionSyncStatus", summary.permissionSyncStatus());
        result.put("checkedAt", summary.checkedAt().toString());
        return result;
    }

    private static ObjectNode menuJson(MembershipMenuSnapshot snapshot) {
        ObjectNode result = JSON.createObjectNode();
        result.set("summary", summaryJson(snapshot.summary()));
        ArrayNode tiers = result.putArray("tiers");
        snapshot.tiers().forEach(view -> {
            ObjectNode item = tiers.addObject();
            item.set("tier", tierJson(view.tier()));
            item.set("quote", view.quote() == null ? JSON.nullNode() : quoteJson(view.quote()));
            if (view.unavailableCode() == null) {
                item.putNull("unavailableCode");
            } else {
                item.put("unavailableCode", view.unavailableCode());
            }
        });
        return result;
    }

    private static JsonNode entitlementJson(MembershipEntitlement entitlement) {
        if (entitlement == null) {
            return JSON.nullNode();
        }
        ObjectNode result = JSON.createObjectNode();
        result.put("entitlementId", entitlement.entitlementId().toString());
        result.set("tier", tierJson(entitlement.tier()));
        result.put("tierKey", entitlement.tier().key());
        result.put("displayName", entitlement.tier().displayName());
        result.put("state", entitlement.state().name());
        putInstant(result, "startsAt", entitlement.startsAt());
        putInstant(result, "expiresAt", entitlement.expiresAt());
        if (entitlement.remainingSeconds() == null) {
            result.putNull("remainingSeconds");
        } else {
            result.put("remainingSeconds", entitlement.remainingSeconds());
        }
        if (entitlement.resumeSequence() == null) {
            result.putNull("resumeSequence");
        } else {
            result.put("resumeSequence", entitlement.resumeSequence());
        }
        return result;
    }

    private static ObjectNode quoteJson(MembershipQuote quote) {
        ObjectNode result = JSON.createObjectNode();
        result.put("quoteId", quote.quoteId().toString());
        result.set("tier", tierJson(quote.targetTier()));
        result.put("months", quote.months());
        result.put("upgradeMode", quote.upgradeMode().name());
        result.put("basePricePoints", quote.basePricePoints());
        result.put("promotionOffBps", quote.promotionOffBps());
        putInstant(result, "promotionEndsAt", quote.promotionEndsAt());
        result.put("discountedPricePoints", quote.discountedPricePoints());
        result.put("upgradeCreditPoints", quote.upgradeCreditPoints());
        result.put("finalPricePoints", quote.finalPricePoints());
        result.set("current", entitlementJson(quote.currentEntitlement()));
        result.put("validUntil", quote.validUntil().toString());
        result.put("quotedAt", quote.quotedAt().toString());
        return result;
    }

    private static ObjectNode resultJson(MembershipOrderResult order) {
        ObjectNode result = JSON.createObjectNode();
        result.put("orderId", order.orderId().toString());
        result.put("status", order.status().name());
        result.put("tierKey", order.tierKey());
        result.put("months", order.months());
        result.put("finalPricePoints", order.finalPricePoints());
        if (order.entitlementId() == null) {
            result.putNull("entitlementId");
        } else {
            result.put("entitlementId", order.entitlementId().toString());
        }
        putInstant(result, "expiresAt", order.expiresAt());
        result.put("permissionSyncStatus", order.permissionSyncStatus());
        if (order.errorCode() == null) {
            result.putNull("errorCode");
        } else {
            result.put("errorCode", order.errorCode());
        }
        return result;
    }

    private static UUID requireUuid(JsonNode payload) {
        try {
            return UUID.fromString(requireText(payload, "playerUuid", 36));
        } catch (IllegalArgumentException exception) {
            throw invalidRequest();
        }
    }

    private static UpgradeMode requireMode(JsonNode payload) {
        try {
            return UpgradeMode.parse(requireText(payload, "upgradeMode", 24));
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

    private static void putInstant(ObjectNode node, String field, java.time.Instant value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value.toString());
        }
    }

    private static CompletionStage<JsonNode> mapErrors(CompletionStage<? extends JsonNode> operation) {
        CompletableFuture<JsonNode> mapped = new CompletableFuture<>();
        operation.whenComplete((value, throwable) -> {
            if (throwable == null) {
                mapped.complete(value);
                return;
            }
            Throwable cause = unwrap(throwable);
            if (cause instanceof MembershipException membership) {
                mapped.completeExceptionally(new RpcHandlingException(
                    membership.code(), membership.getMessage(), membership.retryable()
                ));
            } else {
                mapped.completeExceptionally(cause);
            }
        });
        return mapped;
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static RpcHandlingException invalidRequest() {
        return new RpcHandlingException(
            "MEMBERSHIP_REQUEST_INVALID", "Invalid membership request", false
        );
    }
}
