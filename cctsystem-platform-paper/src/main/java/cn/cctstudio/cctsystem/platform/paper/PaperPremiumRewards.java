package cn.cctstudio.cctsystem.platform.paper;

import cn.cctstudio.cctsystem.core.CctRuntime;
import cn.cctstudio.cctsystem.membership.MembershipException;
import cn.cctstudio.cctsystem.membership.MembershipServiceProvider;
import cn.cctstudio.cctsystem.points.PointsMutationDisposition;
import cn.cctstudio.cctsystem.points.PointsServiceProvider;
import cn.cctstudio.cctsystem.storage.mysql.PremiumLoginStore.Claim;
import cn.cctstudio.cctsystem.storage.mysql.DatabaseProvider;
import cn.cctstudio.cctsystem.storage.mysql.SocialBindingLookup;
import cn.cctstudio.cctsystem.storage.mysql.PremiumLoginStore.Status;
import cn.cctstudio.cctsystem.storage.mysql.PremiumLoginStore.Step;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

final class PaperPremiumRewards {
    private final PaperBootstrap plugin;
    private final CctRuntime runtime;
    private final PaperTaskExecutor tasks;
    private final PaperPremiumLogin login;
    private final AtomicBoolean processing = new AtomicBoolean();

    PaperPremiumRewards(PaperBootstrap plugin, CctRuntime runtime, PaperTaskExecutor tasks, PaperPremiumLogin login) {
        this.plugin = plugin;
        this.runtime = runtime;
        this.tasks = tasks;
        this.login = login;
    }

    void poll() {
        if (!processing.compareAndSet(false, true)) return;
        login.query(() -> login.store().pending(runtime.config().serverId())).thenCompose(claims -> {
            CompletionStage<Void> work = CompletableFuture.completedFuture(null);
            for (Claim claim : claims) {
                work = work.thenCompose(ignored -> deliver(claim).exceptionally(failure -> {
                    plugin.getLogger().warning("Java login reward delivery failed for " + claim.mojangUuid()
                        + " (" + cause(failure).getClass().getSimpleName() + ")");
                    return null;
                }));
            }
            return work;
        }).whenComplete((ignored, failure) -> {
            processing.set(false);
            if (failure != null) plugin.getLogger().warning("Java login reward queue is unavailable");
        });
    }

    private CompletionStage<Void> deliver(Claim claim) {
        return deliverMembership(claim).thenCompose(ignored -> deliverPoints(claim))
            .thenCompose(ignored -> deliverCommands(claim));
    }

    private CompletionStage<Void> deliverMembership(Claim claim) {
        if (claim.membership() != Status.PENDING) return CompletableFuture.completedFuture(null);
        var membership = runtime.providers().find(MembershipServiceProvider.KEY).orElseThrow();
        return login.query(() -> SocialBindingLookup.isBound(
            runtime.providers().find(DatabaseProvider.KEY).orElseThrow(), claim.playerUuid()
        )).thenCompose(bound -> bound ? membership.grantPremiumLoginReward(claim.mojangUuid(), claim.playerUuid())
            : CompletableFuture.completedFuture(null)).exceptionallyCompose(failure -> {
            if (cause(failure) instanceof MembershipException invalid && !invalid.retryable()) {
                return changeStatus(claim, Step.MEMBERSHIP, Status.PENDING, Status.REVIEW).thenAccept(changed -> {
                    if (changed) plugin.getLogger().warning("Java login membership requires review for "
                        + claim.mojangUuid() + " (" + invalid.code() + ")");
                });
            }
            return CompletableFuture.failedFuture(failure);
        });
    }

    private CompletionStage<Void> deliverPoints(Claim claim) {
        if (claim.pointsDelivery() != Status.PENDING) return CompletableFuture.completedFuture(null);
        var points = runtime.providers().find(PointsServiceProvider.KEY).orElseThrow();
        UUID operation = UUID.nameUUIDFromBytes(
            ("cctsystem:java-login:points:" + claim.mojangUuid()).getBytes(StandardCharsets.UTF_8)
        );
        return points.credit(claim.playerUuid(), claim.points(), operation, "JAVA_LOGIN", claim.mojangUuid().toString())
            .thenCompose(result -> {
                boolean completed = result.disposition() == PointsMutationDisposition.COMPLETED;
                return changeStatus(claim, Step.POINTS, Status.PENDING, completed ? Status.COMPLETED : Status.REVIEW)
                    .thenCompose(changed -> {
                        if (completed && !changed) {
                            return changeStatus(claim, Step.POINTS, Status.REVIEW, Status.COMPLETED).thenAccept(v -> {});
                        }
                        if (!completed && changed) plugin.getLogger().warning("Java login points require review for "
                            + claim.mojangUuid() + " (operation " + operation + ")");
                        return CompletableFuture.completedFuture(null);
                    });
            });
    }

    private CompletionStage<Void> deliverCommands(Claim claim) {
        // The next poll sees both built-in rewards committed.
        if (claim.commandDelivery() != Status.PENDING || claim.membership() != Status.COMPLETED
            || claim.pointsDelivery() != Status.COMPLETED) return CompletableFuture.completedFuture(null);
        return changeStatus(claim, Step.COMMANDS, Status.PENDING, Status.RUNNING).thenCompose(reserved -> {
            if (!reserved) return CompletableFuture.completedFuture(null);
            return tasks.callMain(() -> {
                var server = plugin.getServer();
                for (String template : claim.commands()) {
                    String command = template.replace("{player}", claim.name())
                        .replace("{uuid}", claim.playerUuid().toString());
                    if (!server.dispatchCommand(server.getConsoleSender(), command)) return false;
                }
                return true;
            }).handle((success, failure) -> {
                boolean delivered = failure == null && Boolean.TRUE.equals(success);
                if (!delivered) plugin.getLogger().warning("Java login commands require review for " + claim.mojangUuid());
                return delivered ? Status.COMPLETED : Status.REVIEW;
            }).thenCompose(status -> changeStatus(claim, Step.COMMANDS, Status.RUNNING, status)).thenAccept(ignored -> {});
        });
    }

    private CompletableFuture<Boolean> changeStatus(Claim claim, Step step, Status from, Status to) {
        return login.query(() -> login.store().changeStatus(claim.mojangUuid(), step, from, to));
    }

    private static Throwable cause(Throwable failure) {
        while (failure instanceof CompletionException && failure.getCause() != null) failure = failure.getCause();
        return failure;
    }
}
