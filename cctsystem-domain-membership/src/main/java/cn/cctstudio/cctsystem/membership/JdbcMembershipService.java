package cn.cctstudio.cctsystem.membership;

import cn.cctstudio.cctsystem.core.id.UuidV7;
import cn.cctstudio.cctsystem.points.PointsMutationDisposition;
import cn.cctstudio.cctsystem.points.PointsMutationResult;
import cn.cctstudio.cctsystem.points.PointsService;
import cn.cctstudio.cctsystem.promotion.PromotionService;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

final class JdbcMembershipService implements MembershipService {
    private final JdbcMembershipStore store;
    private final PointsService points;
    private final PromotionService promotions;
    private final MembershipAccessGateway accessGateway;
    private final Clock clock;
    private final int quoteTtlSeconds;
    private final int maxPurchaseDays;
    private final Set<String> purchaseBlockedGroups;
    private final Runnable projectionSignal;

    JdbcMembershipService(
        JdbcMembershipStore store,
        PointsService points,
        PromotionService promotions,
        MembershipAccessGateway accessGateway,
        Clock clock,
        int quoteTtlSeconds,
        int maxPurchaseDays,
        Set<String> purchaseBlockedGroups,
        Runnable projectionSignal
    ) {
        this.store = store;
        this.points = points;
        this.promotions = promotions;
        this.accessGateway = accessGateway;
        this.clock = clock;
        this.quoteTtlSeconds = quoteTtlSeconds;
        this.maxPurchaseDays = maxPurchaseDays;
        this.purchaseBlockedGroups = Set.copyOf(purchaseBlockedGroups);
        this.projectionSignal = Objects.requireNonNull(projectionSignal, "projectionSignal");
    }

    @Override
    public CompletionStage<List<MembershipTier>> catalog() {
        return store.catalog();
    }

    @Override
    public CompletionStage<MembershipSummary> summary(UUID playerUuid) {
        return store.reconcileAndSummary(Objects.requireNonNull(playerUuid, "playerUuid"), clock.instant());
    }

    @Override
    public CompletionStage<MembershipMenuSnapshot> menu(UUID playerUuid) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        Instant now = clock.instant();
        return context(playerUuid, now).thenCompose(context -> {
            if (MembershipPurchasePolicy.isBlocked(context.access(), purchaseBlockedGroups)) {
                return CompletableFuture.completedFuture(new MembershipMenuSnapshot(
                    context.summary(),
                    context.catalog().stream()
                        .map(tier -> new MembershipMenuTier(
                            tier, null, "MEMBERSHIP_PURCHASE_FORBIDDEN"
                        ))
                        .toList()
                ));
            }
            List<CompletableFuture<MembershipMenuTier>> tiers = new ArrayList<>();
            for (MembershipTier tier : context.catalog()) {
                UpgradeMode mode = context.summary().active() != null
                    && tier.priority() > context.summary().active().tier().priority()
                    ? UpgradeMode.PAUSE
                    : UpgradeMode.NONE;
                tiers.add(promotions.activeForMembership(tier.key(), now)
                    .thenApply(promotion -> new MembershipMenuTier(
                        tier,
                        quote(context, tier, 1, mode, promotion, now),
                        null
                    ))
                    .exceptionally(failure -> new MembershipMenuTier(
                        tier, null, errorCode(failure)
                    ))
                    .toCompletableFuture());
            }
            return CompletableFuture.allOf(tiers.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> new MembershipMenuSnapshot(
                    context.summary(), tiers.stream().map(CompletableFuture::join).toList()
                ));
        });
    }

    @Override
    public CompletionStage<MembershipQuote> quote(
        UUID playerUuid,
        String tierKey,
        int months,
        UpgradeMode upgradeMode
    ) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        String normalizedTier = normalizeTier(tierKey);
        UpgradeMode mode = Objects.requireNonNullElse(upgradeMode, UpgradeMode.NONE);
        Instant now = clock.instant();
        return context(playerUuid, now)
            .thenCompose(context -> {
                MembershipPurchasePolicy.ensureAccess(context.access(), purchaseBlockedGroups);
                MembershipTier target = context.catalog().stream()
                    .filter(tier -> tier.key().equals(normalizedTier) && tier.enabled())
                    .findFirst()
                    .orElseThrow(() -> new MembershipException(
                        "MEMBERSHIP_TIER_UNAVAILABLE", "Membership tier is unavailable", false
                    ));
                return promotions.activeForMembership(normalizedTier, now)
                    .thenApply(promotion -> quote(context, target, months, mode, promotion, now));
            });
    }

    @Override
    public CompletionStage<MembershipOrderResult> purchase(MembershipPurchaseRequest request) {
        validate(request);
        return store.existingResult(request.playerUuid(), request.idempotencyKey())
            .thenCompose(existing -> existing.<CompletionStage<MembershipOrderResult>>map(
                CompletableFuture::completedFuture
            ).orElseGet(() -> startPurchase(request)))
            .thenApply(this::signalProjection);
    }

    private CompletionStage<MembershipOrderResult> startPurchase(MembershipPurchaseRequest request) {
        Instant now = clock.instant();
        UUID orderId = UuidV7.create(now);
        UUID operationId = UuidV7.create(now.plusNanos(1));
        return quote(
            request.playerUuid(),
            request.tierKey(),
            request.months(),
            request.upgradeMode()
        ).thenCompose(quote -> store.prepare(request, quote, orderId, operationId, clock.instant()))
            .thenCompose(prepared -> {
                if (!prepared.proceed()) {
                    return CompletableFuture.completedFuture(prepared.existingResult());
                }
                return debitAndApply(prepared);
            });
    }

    @Override
    public CompletionStage<MembershipSummary> reconcile(UUID playerUuid) {
        return summary(playerUuid);
    }

    @Override
    public CompletionStage<MembershipSummary> admin(AdminMembershipRequest request) {
        if (request == null || request.action() == null || request.playerUuid() == null
            || request.actor() == null || request.actor().isBlank() || request.actor().length() > 80
            || request.reason() == null || request.reason().isBlank() || request.reason().length() > 255
            || request.days() < 0 || request.days() > maxPurchaseDays) {
            throw new MembershipException("MEMBERSHIP_ADMIN_REQUEST_INVALID", "Invalid admin request", false);
        }
        if (request.action() == AdminMembershipAction.GRANT
            || request.action() == AdminMembershipAction.EXTEND
            || request.action() == AdminMembershipAction.RECLAIM
            || request.action() == AdminMembershipAction.REMOVE) {
            normalizeTier(request.tierKey());
        }
        if (request.action() == AdminMembershipAction.GRANT) {
            if (request.days() < 1) {
                throw new MembershipException("MEMBERSHIP_ADMIN_REQUEST_INVALID", "Grant days are required", false);
            }
        }
        if (request.action() == AdminMembershipAction.EXTEND && request.days() < 1) {
            throw new MembershipException(
                "MEMBERSHIP_ADMIN_REQUEST_INVALID", "Extension days are required", false
            );
        }
        if (request.action() == AdminMembershipAction.SET_EXPIRY && request.expiresAt() == null) {
            throw new MembershipException("MEMBERSHIP_ADMIN_REQUEST_INVALID", "Expiry is required", false);
        }
        Instant now = clock.instant();
        if (request.action() == AdminMembershipAction.SET_EXPIRY
            && request.expiresAt().isAfter(now.plus(Duration.ofDays(maxPurchaseDays)))) {
            throw durationLimit();
        }
        if (request.action() != AdminMembershipAction.EXTEND
            && request.action() != AdminMembershipAction.GRANT) {
            return store.admin(request, now).thenApply(this::signalProjection);
        }
        return summary(request.playerUuid()).thenCompose(summary -> {
            MembershipEntitlement entitlement = entitlement(summary, request.tierKey());
            if (entitlement != null) {
                if (entitlement.state() == EntitlementState.PAUSED) {
                    long current = Math.max(0L, entitlement.remainingSeconds());
                    if (current + Duration.ofDays(request.days()).toSeconds()
                        > Duration.ofDays(maxPurchaseDays).toSeconds()) {
                        throw durationLimit();
                    }
                } else {
                    Instant base = entitlement.expiresAt() == null
                        || entitlement.expiresAt().isBefore(now) ? now : entitlement.expiresAt();
                    if (base.plus(Duration.ofDays(request.days()))
                        .isAfter(now.plus(Duration.ofDays(maxPurchaseDays)))) {
                        throw durationLimit();
                    }
                }
            }
            return store.admin(request, now).thenApply(this::signalProjection);
        });
    }

    private MembershipOrderResult signalProjection(MembershipOrderResult result) {
        if (result.status() == MembershipOrderStatus.COMPLETED) {
            projectionSignal.run();
        }
        return result;
    }

    private MembershipSummary signalProjection(MembershipSummary summary) {
        projectionSignal.run();
        return summary;
    }

    private static MembershipEntitlement entitlement(MembershipSummary summary, String tierKey) {
        if (summary.active() != null && summary.active().tier().key().equals(tierKey)) {
            return summary.active();
        }
        return summary.paused().stream()
            .filter(item -> item.tier().key().equals(tierKey))
            .findFirst()
            .orElse(null);
    }

    private CompletionStage<QuoteContext> context(UUID playerUuid, Instant now) {
        CompletionStage<MembershipSummary> summary = store.reconcileAndSummary(playerUuid, now);
        CompletionStage<List<MembershipTier>> catalog = store.catalog();
        CompletionStage<MembershipAccess> access = accessGateway.access(playerUuid);
        return summary.thenCombine(catalog, SummaryCatalog::new)
            .thenCombine(access, (data, membershipAccess) -> new QuoteContext(
                data.summary(), data.catalog(), membershipAccess
            ));
    }

    private MembershipQuote quote(
        QuoteContext context,
        MembershipTier target,
        int months,
        UpgradeMode mode,
        cn.cctstudio.cctsystem.promotion.Promotion promotion,
        Instant now
    ) {
        MembershipPurchasePolicy.ensureDuration(
            context.summary(), target, months, now, maxPurchaseDays
        );
        return MembershipPricing.quote(
            target,
            months,
            mode,
            context.summary().active(),
            highestPriority(context.summary()),
            promotion,
            now,
            quoteTtlSeconds
        );
    }

    private static MembershipException durationLimit() {
        return new MembershipException(
            "MEMBERSHIP_DURATION_LIMIT",
            "Membership validity cannot exceed the configured limit",
            false
        );
    }

    private static String errorCode(Throwable failure) {
        Throwable cause = unwrap(failure);
        return cause instanceof MembershipException membership
            ? membership.code()
            : "MEMBERSHIP_TIER_UNAVAILABLE";
    }

    private CompletionStage<MembershipOrderResult> debitAndApply(PreparedMembershipOrder prepared) {
        UUID orderId = prepared.orderId();
        int price = prepared.quote().finalPricePoints();
        return store.pointsDebitRequested(orderId).thenCompose(ignored -> {
            CompletionStage<PointsMutationResult> debit;
            if (price == 0) {
                debit = store.recordZeroPointDebit(
                    prepared.operationId(), prepared.playerUuid(), orderId
                );
            } else {
                debit = points.debit(
                    prepared.playerUuid(),
                    price,
                    prepared.operationId(),
                    "MEMBERSHIP_PURCHASE",
                    orderId.toString()
                );
            }
            return debit.thenCompose(result -> handleDebit(prepared, result));
        });
    }

    private CompletionStage<MembershipOrderResult> handleDebit(
        PreparedMembershipOrder prepared,
        PointsMutationResult debit
    ) {
        UUID orderId = prepared.orderId();
        return switch (debit.disposition()) {
            case REJECTED -> store.failDebit(orderId, Objects.requireNonNullElse(
                    debit.errorCode(), "POINTS_DEBIT_REJECTED"
                ))
                .thenCompose(ignored -> store.result(orderId));
            case AMBIGUOUS -> store.reviewRequired(
                    orderId,
                    MembershipOrderStatus.POINTS_DEBIT_REQUESTED,
                    Objects.requireNonNullElse(debit.errorCode(), "POINTS_DEBIT_AMBIGUOUS")
                )
                .thenCompose(ignored -> store.result(orderId));
            case COMPLETED -> persistDebitAndApply(prepared, debit);
        };
    }

    private CompletionStage<MembershipOrderResult> persistDebitAndApply(
        PreparedMembershipOrder prepared,
        PointsMutationResult debit
    ) {
        UUID orderId = prepared.orderId();
        CompletableFuture<MembershipOrderResult> result = new CompletableFuture<>();
        store.pointsDebited(orderId, debit).whenComplete((ignored, persistFailure) -> {
            if (persistFailure != null) {
                resolveDebitPersistenceFailure(prepared)
                    .whenComplete((order, failure) -> complete(result, order, failure));
                return;
            }
            applySafely(prepared).whenComplete((order, failure) -> complete(result, order, failure));
        });
        return result;
    }

    private CompletionStage<MembershipOrderResult> resolveDebitPersistenceFailure(
        PreparedMembershipOrder prepared
    ) {
        UUID orderId = prepared.orderId();
        return store.result(orderId).thenCompose(order -> {
            if (order.status() == MembershipOrderStatus.POINTS_DEBITED) {
                return applySafely(prepared);
            }
            if (order.status() == MembershipOrderStatus.COMPLETED) {
                return CompletableFuture.completedFuture(order);
            }
            if (order.status() == MembershipOrderStatus.POINTS_DEBIT_REQUESTED) {
                return store.reviewRequired(
                    orderId,
                    MembershipOrderStatus.POINTS_DEBIT_REQUESTED,
                    "POINTS_DEBIT_AUDIT_FAILED"
                ).thenCompose(ignored -> store.result(orderId));
            }
            return CompletableFuture.completedFuture(order);
        });
    }

    private CompletionStage<MembershipOrderResult> applySafely(PreparedMembershipOrder prepared) {
        UUID orderId = prepared.orderId();
        CompletableFuture<MembershipOrderResult> result = new CompletableFuture<>();
        store.apply(orderId, clock.instant()).whenComplete((applied, applyFailure) -> {
            if (applyFailure == null) {
                result.complete(applied);
                return;
            }
            // A failed commit can be ambiguous. Read the durable order before deciding whether
            // compensation is safe; never create a free membership by refunding blindly.
            store.result(orderId).whenComplete((durable, readFailure) -> {
                if (readFailure != null) {
                    result.completeExceptionally(unwrap(readFailure));
                } else if (durable.status() == MembershipOrderStatus.COMPLETED) {
                    result.complete(durable);
                } else if (durable.status() == MembershipOrderStatus.POINTS_DEBITED) {
                    compensate(prepared)
                        .whenComplete((compensated, failure) -> complete(result, compensated, failure));
                } else {
                    result.complete(durable);
                }
            });
        });
        return result;
    }

    private CompletionStage<MembershipOrderResult> compensate(PreparedMembershipOrder prepared) {
        UUID orderId = prepared.orderId();
        int price = prepared.quote().finalPricePoints();
        return store.beginCompensation(orderId, "MEMBERSHIP_APPLY_FAILED")
            .thenCompose(ignored -> {
                if (price == 0) {
                    return store.compensated(orderId).thenCompose(nothing -> store.result(orderId));
                }
                UUID refundOperation = UUID.nameUUIDFromBytes(
                    ("membership-refund:" + orderId).getBytes(StandardCharsets.UTF_8)
                );
                return points.credit(
                    prepared.playerUuid(),
                    price,
                    refundOperation,
                    "MEMBERSHIP_REFUND",
                    orderId.toString()
                ).thenCompose(refund -> {
                    if (refund.disposition() == PointsMutationDisposition.COMPLETED) {
                        return store.compensated(orderId).thenCompose(nothing -> store.result(orderId));
                    }
                    String code = refund.disposition() == PointsMutationDisposition.AMBIGUOUS
                        ? "POINTS_REFUND_AMBIGUOUS"
                        : "POINTS_REFUND_REJECTED";
                    return store.compensationPending(orderId, code)
                        .thenCompose(nothing -> store.result(orderId));
                });
            });
    }

    private static int highestPriority(MembershipSummary summary) {
        int highest = summary.active() == null ? 0 : summary.active().tier().priority();
        for (MembershipEntitlement paused : summary.paused()) {
            highest = Math.max(highest, paused.tier().priority());
        }
        return highest;
    }

    private static void validate(MembershipPurchaseRequest request) {
        if (request == null || request.playerUuid() == null) {
            throw new MembershipException("MEMBERSHIP_REQUEST_INVALID", "Invalid membership request", false);
        }
        normalizeTier(request.tierKey());
        if (request.months() < 1 || request.months() > 120
            || request.upgradeMode() == null
            || request.idempotencyKey() == null || request.idempotencyKey().isBlank()
            || request.idempotencyKey().length() > 80
            || request.origin() == null || request.origin().isBlank() || request.origin().length() > 32) {
            throw new MembershipException("MEMBERSHIP_REQUEST_INVALID", "Invalid membership request", false);
        }
    }

    private static String normalizeTier(String tierKey) {
        if (tierKey == null || !tierKey.matches("[a-z0-9][a-z0-9_-]{1,63}")) {
            throw new MembershipException("MEMBERSHIP_TIER_INVALID", "Invalid membership tier", false);
        }
        return tierKey.toLowerCase(Locale.ROOT);
    }

    private static <T> void complete(CompletableFuture<T> future, T value, Throwable failure) {
        if (failure == null) {
            future.complete(value);
        } else {
            future.completeExceptionally(unwrap(failure));
        }
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private record SummaryCatalog(MembershipSummary summary, List<MembershipTier> catalog) {
    }

    private record QuoteContext(
        MembershipSummary summary,
        List<MembershipTier> catalog,
        MembershipAccess access
    ) {
    }
}
