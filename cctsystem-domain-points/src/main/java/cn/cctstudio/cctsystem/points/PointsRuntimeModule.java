package cn.cctstudio.cctsystem.points;

import cn.cctstudio.cctsystem.contract.NodeRole;
import cn.cctstudio.cctsystem.contract.PlatformType;
import cn.cctstudio.cctsystem.core.module.CctModule;
import cn.cctstudio.cctsystem.core.module.ModuleContext;
import cn.cctstudio.cctsystem.core.module.ModuleDescriptor;
import cn.cctstudio.cctsystem.storage.mysql.DatabaseAccess;
import cn.cctstudio.cctsystem.storage.mysql.DatabaseProvider;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public final class PointsRuntimeModule implements CctModule {
    private static final ModuleDescriptor DESCRIPTOR = new ModuleDescriptor(
        "points-runtime",
        "points",
        Set.of(PlatformType.PAPER),
        EnumSet.allOf(NodeRole.class),
        Set.of(DatabaseProvider.ID, PointsGatewayProvider.ID),
        Set.of()
    );

    @Override
    public ModuleDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public CompletableFuture<Void> start(ModuleContext context) {
        DatabaseAccess database = context.providers().find(DatabaseProvider.KEY)
            .orElseThrow(() -> new IllegalStateException("Database provider is unavailable"));
        PointsGateway gateway = context.providers().find(PointsGatewayProvider.KEY)
            .orElseThrow(() -> new IllegalStateException("PlayerPoints provider is unavailable"));
        context.providers().register(
            PointsServiceProvider.KEY,
            new JdbcPointsService(database, gateway, context.executors())
        );
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> stop() {
        return CompletableFuture.completedFuture(null);
    }
}
