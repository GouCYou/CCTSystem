package cn.cctstudio.cctsystem.promotion;

import cn.cctstudio.cctsystem.contract.NodeRole;
import cn.cctstudio.cctsystem.contract.PlatformType;
import cn.cctstudio.cctsystem.core.module.CctModule;
import cn.cctstudio.cctsystem.core.module.ModuleContext;
import cn.cctstudio.cctsystem.core.module.ModuleDescriptor;
import cn.cctstudio.cctsystem.storage.mysql.DatabaseAccess;
import cn.cctstudio.cctsystem.storage.mysql.DatabaseProvider;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public final class PromotionRuntimeModule implements CctModule {
    private static final ModuleDescriptor DESCRIPTOR = new ModuleDescriptor(
        "promotion-runtime",
        "promotion",
        Set.of(PlatformType.PAPER),
        Set.of(NodeRole.BUSINESS_AUTHORITY),
        Set.of(DatabaseProvider.ID),
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
        context.providers().register(
            PromotionServiceProvider.KEY,
            new JdbcPromotionService(database, context.executors())
        );
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> stop() {
        return CompletableFuture.completedFuture(null);
    }
}
