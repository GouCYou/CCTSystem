package cn.cctstudio.cctsystem.identity;

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

public final class IdentityModule implements CctModule {
    private static final ModuleDescriptor DESCRIPTOR = new ModuleDescriptor(
        "identity",
        EnumSet.allOf(PlatformType.class),
        EnumSet.allOf(NodeRole.class),
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
            IdentityProvider.KEY,
            new JdbcIdentityService(database, context.executors())
        );
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> stop() {
        return CompletableFuture.completedFuture(null);
    }
}
