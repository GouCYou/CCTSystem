package cn.cctstudio.cctsystem.core.module;

import java.util.concurrent.CompletableFuture;

public interface CctModule {
    ModuleDescriptor descriptor();

    CompletableFuture<Void> start(ModuleContext context);

    CompletableFuture<Void> stop();
}
