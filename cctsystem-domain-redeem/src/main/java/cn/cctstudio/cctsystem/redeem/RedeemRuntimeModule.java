package cn.cctstudio.cctsystem.redeem;

import cn.cctstudio.cctsystem.contract.NodeRole;
import cn.cctstudio.cctsystem.contract.PlatformType;
import cn.cctstudio.cctsystem.core.module.CctModule;
import cn.cctstudio.cctsystem.core.module.ModuleContext;
import cn.cctstudio.cctsystem.core.module.ModuleDescriptor;
import cn.cctstudio.cctsystem.membership.MembershipService;
import cn.cctstudio.cctsystem.membership.MembershipServiceProvider;
import cn.cctstudio.cctsystem.points.PointsService;
import cn.cctstudio.cctsystem.points.PointsServiceProvider;
import cn.cctstudio.cctsystem.storage.mysql.DatabaseAccess;
import cn.cctstudio.cctsystem.storage.mysql.DatabaseProvider;
import java.time.Clock;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public final class RedeemRuntimeModule implements CctModule {
    private static final ModuleDescriptor DESCRIPTOR = new ModuleDescriptor(
        "redeem-runtime",
        "redeem",
        Set.of(PlatformType.PAPER),
        Set.of(NodeRole.BUSINESS_AUTHORITY),
        Set.of(DatabaseProvider.ID, PointsServiceProvider.ID, MembershipServiceProvider.ID),
        Set.of()
    );

    @Override
    public ModuleDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public CompletableFuture<Void> start(ModuleContext context) {
        RedeemCodeCodec codec = new RedeemCodeCodec(context.config().redeemCode().pepper());
        DatabaseAccess database = context.providers().find(DatabaseProvider.KEY).orElseThrow();
        PointsService points = context.providers().find(PointsServiceProvider.KEY).orElseThrow();
        MembershipService memberships = context.providers().find(MembershipServiceProvider.KEY).orElseThrow();
        JdbcRedeemStore store = new JdbcRedeemStore(
            database, context.executors(), context.config().nodeId()
        );
        return store.recoverInterruptedUses().thenRun(() -> context.providers().register(
            RedeemCodeServiceProvider.KEY,
            new JdbcRedeemCodeService(
                store,
                codec,
                points,
                memberships,
                Clock.systemUTC(),
                context.config().redeemCode().length()
            )
        )).toCompletableFuture();
    }

    @Override
    public CompletableFuture<Void> stop() {
        return CompletableFuture.completedFuture(null);
    }
}
