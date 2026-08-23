package cn.cctstudio.cctsystem.membership;

import cn.cctstudio.cctsystem.contract.NodeRole;
import cn.cctstudio.cctsystem.contract.PlatformType;
import cn.cctstudio.cctsystem.core.config.MembershipTierConfig;
import cn.cctstudio.cctsystem.core.module.CctModule;
import cn.cctstudio.cctsystem.core.module.ModuleContext;
import cn.cctstudio.cctsystem.core.module.ModuleDescriptor;
import cn.cctstudio.cctsystem.points.PointsService;
import cn.cctstudio.cctsystem.points.PointsServiceProvider;
import cn.cctstudio.cctsystem.promotion.PromotionService;
import cn.cctstudio.cctsystem.promotion.PromotionServiceProvider;
import cn.cctstudio.cctsystem.storage.mysql.DatabaseAccess;
import cn.cctstudio.cctsystem.storage.mysql.DatabaseProvider;
import java.time.Clock;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public final class MembershipRuntimeModule implements CctModule {
    private static final ModuleDescriptor DESCRIPTOR = new ModuleDescriptor(
        "membership-runtime",
        "membership",
        Set.of(PlatformType.PAPER),
        Set.of(NodeRole.BUSINESS_AUTHORITY),
        Set.of(
            DatabaseProvider.ID,
            PointsServiceProvider.ID,
            PromotionServiceProvider.ID,
            MembershipPermissionGatewayProvider.ID,
            MembershipAccessGatewayProvider.ID
        ),
        Set.of()
    );
    private volatile MembershipProjectionWorker projectionWorker;

    @Override
    public ModuleDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public CompletableFuture<Void> start(ModuleContext context) {
        DatabaseAccess database = context.providers().find(DatabaseProvider.KEY).orElseThrow();
        PointsService points = context.providers().find(PointsServiceProvider.KEY).orElseThrow();
        PromotionService promotions = context.providers().find(PromotionServiceProvider.KEY).orElseThrow();
        MembershipPermissionGateway permissions = context.providers()
            .find(MembershipPermissionGatewayProvider.KEY).orElseThrow();
        MembershipAccessGateway membershipAccess = context.providers()
            .find(MembershipAccessGatewayProvider.KEY).orElseThrow();
        JdbcMembershipStore store = new JdbcMembershipStore(
            database, context.executors(), context.config().nodeId()
        );
        Clock clock = Clock.systemUTC();
        Set<String> managedGroups = context.config().membership().tiers().stream()
            .map(MembershipTierConfig::luckPermsGroup)
            .collect(Collectors.toUnmodifiableSet());
        MembershipProjectionWorker worker = new MembershipProjectionWorker(
            store, permissions, managedGroups, context.executors(), context.logger(), clock
        );
        return store.syncConfiguration(context.config().membership().tiers())
            .thenCompose(ignored -> store.recoverInterruptedOrders())
            .thenRun(() -> {
                context.providers().register(
                    MembershipServiceProvider.KEY,
                    new JdbcMembershipService(
                        store,
                        points,
                        promotions,
                        membershipAccess,
                        clock,
                        context.config().membership().quoteTtlSeconds(),
                        context.config().membership().maxPurchaseDays(),
                        context.config().membership().purchaseBlockedGroups(),
                        managedGroups,
                        true,
                        worker::trigger
                    )
                );
                projectionWorker = worker;
                worker.start();
            })
            .toCompletableFuture();
    }

    @Override
    public CompletableFuture<Void> stop() {
        MembershipProjectionWorker existing = projectionWorker;
        projectionWorker = null;
        if (existing != null) {
            existing.stop();
        }
        return CompletableFuture.completedFuture(null);
    }
}
