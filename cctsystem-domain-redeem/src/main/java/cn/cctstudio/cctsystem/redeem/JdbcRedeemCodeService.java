package cn.cctstudio.cctsystem.redeem;

import cn.cctstudio.cctsystem.membership.AdminMembershipAction;
import cn.cctstudio.cctsystem.membership.AdminMembershipRequest;
import cn.cctstudio.cctsystem.membership.MembershipException;
import cn.cctstudio.cctsystem.membership.MembershipService;
import cn.cctstudio.cctsystem.membership.MembershipTier;
import cn.cctstudio.cctsystem.points.PointsMutationDisposition;
import cn.cctstudio.cctsystem.points.PointsMutationResult;
import cn.cctstudio.cctsystem.points.PointsService;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

final class JdbcRedeemCodeService implements RedeemCodeService {
    private final JdbcRedeemStore store;
    private final RedeemCodeCodec codec;
    private final PointsService points;
    private final MembershipService memberships;
    private final Clock clock;
    private final int codeLength;

    JdbcRedeemCodeService(
        JdbcRedeemStore store,
        RedeemCodeCodec codec,
        PointsService points,
        MembershipService memberships,
        Clock clock,
        int codeLength
    ) {
        this.store = store;
        this.codec = codec;
        this.points = points;
        this.memberships = memberships;
        this.clock = clock;
        this.codeLength = codeLength;
    }

    @Override
    public CompletionStage<GeneratedRedeemBatch> generate(GenerateRedeemCodesRequest request) {
        GenerateRedeemCodesRequest valid = validateGeneration(request);
        return validateMembershipRewards(valid.rewards()).thenCompose(ignored -> {
            List<JdbcRedeemStore.GeneratedCode> generated = new ArrayList<>(valid.count());
            Set<String> hashes = new HashSet<>();
            while (generated.size() < valid.count()) {
                String code = codec.generate(codeLength);
                byte[] hash = codec.hash(code);
                if (hashes.add(java.util.HexFormat.of().formatHex(hash))) {
                    generated.add(new JdbcRedeemStore.GeneratedCode(code, hash));
                }
            }
            return store.createBatch(valid, codeLength, generated, clock.instant());
        });
    }

    @Override
    public CompletionStage<RedeemResult> redeem(RedeemRequest request) {
        RedeemRequest valid = validateRedeem(request);
        byte[] hash = codec.hash(valid.code());
        return store.reserve(valid, hash, RedeemCodeCodec.fingerprint(hash), clock.instant())
            .thenCompose(reservation -> {
                if (!reservation.proceed()) {
                    return CompletableFuture.completedFuture(reservation.existing());
                }
                CompletionStage<Void> chain = CompletableFuture.completedFuture(null);
                for (JdbcRedeemStore.StoredReward reward : reservation.rewards()) {
                    chain = chain.thenCompose(ignored -> deliver(
                        reservation.useId(), valid.playerUuid(), reward
                    ));
                }
                return chain.handle((ignored, failure) -> null)
                    .thenCompose(ignored -> store.finishUse(reservation.useId(), clock.instant()));
            });
    }

    private CompletionStage<Void> deliver(
        java.util.UUID useId,
        java.util.UUID playerUuid,
        JdbcRedeemStore.StoredReward stored
    ) {
        return store.beginDelivery(stored.deliveryId()).thenCompose(acquired -> {
            if (!acquired) {
                return CompletableFuture.completedFuture(null);
            }
            RedeemReward reward = stored.reward();
            CompletionStage<String> operation = switch (reward.type()) {
                case POINTS -> creditPoints(useId, playerUuid, stored);
                case MEMBERSHIP -> grantMembership(useId, playerUuid, stored);
            };
            CompletableFuture<Void> completed = new CompletableFuture<>();
            operation.whenComplete((reference, failure) -> {
                CompletionStage<Void> finish;
                if (failure == null) {
                    finish = store.completeDelivery(stored.deliveryId(), reference);
                } else {
                    Failure classified = classify(failure);
                    finish = store.failDelivery(
                        stored.deliveryId(), classified.ambiguous(), classified.code()
                    );
                }
                finish.whenComplete((ignored, auditFailure) -> completed.complete(null));
            });
            return completed;
        });
    }

    private CompletionStage<String> creditPoints(
        java.util.UUID useId,
        java.util.UUID playerUuid,
        JdbcRedeemStore.StoredReward stored
    ) {
        return points.credit(
            playerUuid,
            stored.reward().points(),
            stored.deliveryId(),
            "REDEEM_CODE",
            useId.toString()
        ).thenCompose(result -> switch (result.disposition()) {
            case COMPLETED -> CompletableFuture.completedFuture(result.operationId().toString());
            case REJECTED -> CompletableFuture.failedFuture(new DeliveryException(
                Objects.requireNonNullElse(result.errorCode(), "REDEEM_POINTS_REJECTED"), false
            ));
            case AMBIGUOUS -> CompletableFuture.failedFuture(new DeliveryException(
                Objects.requireNonNullElse(result.errorCode(), "REDEEM_POINTS_AMBIGUOUS"), true
            ));
        });
    }

    private CompletionStage<String> grantMembership(
        java.util.UUID useId,
        java.util.UUID playerUuid,
        JdbcRedeemStore.StoredReward stored
    ) {
        RedeemReward reward = stored.reward();
        return memberships.admin(new AdminMembershipRequest(
            AdminMembershipAction.GRANT,
            playerUuid,
            reward.tierKey(),
            reward.days(),
            null,
            "redeem:" + useId,
            "兑换码奖励"
        )).thenApply(summary -> "membership:" + reward.tierKey());
    }

    private CompletionStage<Void> validateMembershipRewards(List<RedeemReward> rewards) {
        Set<String> requested = rewards.stream()
            .filter(reward -> reward.type() == RedeemRewardType.MEMBERSHIP)
            .map(RedeemReward::tierKey)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (requested.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        return memberships.catalog().thenApply(tiers -> {
            Set<String> available = tiers.stream().filter(MembershipTier::enabled)
                .map(MembershipTier::key).collect(java.util.stream.Collectors.toSet());
            if (!available.containsAll(requested)) {
                throw new RedeemException(
                    "REDEEM_REWARD_INVALID", "Membership reward tier is unavailable", false
                );
            }
            return null;
        });
    }

    private GenerateRedeemCodesRequest validateGeneration(GenerateRedeemCodesRequest request) {
        if (request == null || request.count() < 1 || request.count() > 1_000
            || request.maxUsesPerCode() < 1 || request.maxUsesPerCode() > 1_000_000
            || request.creator() == null || request.creator().isBlank() || request.creator().length() > 80
            || request.note() == null || request.note().length() > 255
            || request.rewards().isEmpty() || request.rewards().size() > 16) {
            throw new RedeemException("REDEEM_GENERATE_INVALID", "Invalid generation request", false);
        }
        Instant from = Objects.requireNonNullElse(request.validFrom(), clock.instant());
        if (request.validUntil() != null && !request.validUntil().isAfter(from)) {
            throw new RedeemException("REDEEM_GENERATE_INVALID", "Invalid validity period", false);
        }
        return new GenerateRedeemCodesRequest(
            request.count(), request.maxUsesPerCode(), from, request.validUntil(),
            request.creator().trim(), request.note().trim(), request.rewards()
        );
    }

    private static RedeemRequest validateRedeem(RedeemRequest request) {
        if (request == null || request.playerUuid() == null
            || request.idempotencyKey() == null || request.idempotencyKey().isBlank()
            || request.idempotencyKey().length() > 80
            || request.origin() == null || request.origin().isBlank() || request.origin().length() > 24) {
            throw new RedeemException("REDEEM_REQUEST_INVALID", "Invalid redeem request", false);
        }
        String normalized = RedeemCodeCodec.normalize(request.code());
        return new RedeemRequest(
            request.playerUuid(), normalized, request.idempotencyKey(),
            request.origin().trim().toUpperCase(Locale.ROOT)
        );
    }

    private static Failure classify(Throwable throwable) {
        Throwable cause = unwrap(throwable);
        if (cause instanceof DeliveryException delivery) {
            return new Failure(delivery.code, delivery.ambiguous);
        }
        if (cause instanceof MembershipException membership) {
            return new Failure(membership.code(), membership.retryable());
        }
        return new Failure("REDEEM_REWARD_RESULT_UNKNOWN", true);
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private record Failure(String code, boolean ambiguous) {
    }

    private static final class DeliveryException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String code;
        private final boolean ambiguous;

        private DeliveryException(String code, boolean ambiguous) {
            super(code);
            this.code = code;
            this.ambiguous = ambiguous;
        }
    }
}
