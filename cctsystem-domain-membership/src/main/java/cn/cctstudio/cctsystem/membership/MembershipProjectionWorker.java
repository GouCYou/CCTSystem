package cn.cctstudio.cctsystem.membership;

import cn.cctstudio.cctsystem.core.concurrent.CctExecutors;
import cn.cctstudio.cctsystem.core.logging.CctLogger;
import java.time.Clock;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

final class MembershipProjectionWorker {
    private final JdbcMembershipStore store;
    private final MembershipPermissionGateway permissions;
    private final Set<String> managedGroups;
    private final CctExecutors executors;
    private final CctLogger logger;
    private final Clock clock;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile ScheduledFuture<?> scheduled;

    MembershipProjectionWorker(
        JdbcMembershipStore store,
        MembershipPermissionGateway permissions,
        Set<String> managedGroups,
        CctExecutors executors,
        CctLogger logger,
        Clock clock
    ) {
        this.store = store;
        this.permissions = permissions;
        this.managedGroups = Set.copyOf(managedGroups);
        this.executors = executors;
        this.logger = logger;
        this.clock = clock;
    }

    void start() {
        scheduled = executors.scheduler().scheduleWithFixedDelay(
            this::tick, 0, 10, TimeUnit.SECONDS
        );
    }

    void stop() {
        ScheduledFuture<?> existing = scheduled;
        scheduled = null;
        if (existing != null) {
            existing.cancel(false);
        }
    }

    private void tick() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        store.pendingProjections(clock.instant(), 25)
            .thenCompose(projections -> {
                CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
                for (MembershipProjection projection : projections) {
                    chain = chain.thenCompose(ignored -> deliver(projection));
                }
                return chain;
            })
            .whenComplete((ignored, failure) -> {
                running.set(false);
                if (failure != null) {
                    logger.warn("Membership permission projection pass failed", failure);
                }
            });
    }

    private CompletableFuture<Void> deliver(MembershipProjection projection) {
        CompletableFuture<Void> delivered = new CompletableFuture<>();
        java.util.concurrent.CompletionStage<Void> projectionCall;
        try {
            projectionCall = permissions.project(
                projection.playerUuid(),
                managedGroups,
                projection.desiredGroup(),
                projection.desiredExpiry()
            );
        } catch (RuntimeException failure) {
            projectionCall = CompletableFuture.failedFuture(failure);
        }
        projectionCall.whenComplete((ignored, failure) -> {
            if (failure == null) {
                store.projectionApplied(projection).whenComplete((nothing, updateFailure) -> {
                    if (updateFailure == null) {
                        delivered.complete(null);
                    } else {
                        delivered.completeExceptionally(updateFailure);
                    }
                });
                return;
            }
            store.projectionFailed(projection, clock.instant().plus(Duration.ofSeconds(30)))
                .whenComplete((nothing, updateFailure) -> {
                    logger.warn("LuckPerms membership projection failed for a player", failure);
                    if (updateFailure == null) {
                        delivered.complete(null);
                    } else {
                        delivered.completeExceptionally(updateFailure);
                    }
                });
        });
        return delivered;
    }
}
