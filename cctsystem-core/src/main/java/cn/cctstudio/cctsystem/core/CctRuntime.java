package cn.cctstudio.cctsystem.core;

import cn.cctstudio.cctsystem.contract.Capability;
import cn.cctstudio.cctsystem.contract.PlatformType;
import cn.cctstudio.cctsystem.core.concurrent.CctExecutors;
import cn.cctstudio.cctsystem.core.concurrent.PlatformTaskExecutor;
import cn.cctstudio.cctsystem.core.config.CctConfig;
import cn.cctstudio.cctsystem.core.lifecycle.LifecycleComponent;
import cn.cctstudio.cctsystem.core.logging.CctLogger;
import cn.cctstudio.cctsystem.core.module.CctModule;
import cn.cctstudio.cctsystem.core.module.ModuleContext;
import cn.cctstudio.cctsystem.core.module.ModuleManager;
import cn.cctstudio.cctsystem.core.provider.ProviderRegistry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CctRuntime implements AutoCloseable {
    private final String bootId = UUID.randomUUID().toString();
    private final CctConfig config;
    private final CctLogger logger;
    private final CctExecutors executors;
    private final ProviderRegistry providers;
    private final ModuleManager modules;
    private final List<LifecycleComponent> beforeModules = new ArrayList<>();
    private final List<LifecycleComponent> afterModules = new ArrayList<>();
    private final AtomicBoolean started = new AtomicBoolean();

    public CctRuntime(
        PlatformType platform,
        CctConfig config,
        PlatformTaskExecutor platformTasks,
        CctLogger logger
    ) {
        this.config = config;
        this.logger = logger;
        this.executors = new CctExecutors();
        this.providers = new ProviderRegistry();
        this.modules = new ModuleManager(
            new ModuleContext(platform, config, providers, executors, platformTasks, logger)
        );
    }

    public String bootId() {
        return bootId;
    }

    public CctConfig config() {
        return config;
    }

    public CctExecutors executors() {
        return executors;
    }

    public ProviderRegistry providers() {
        return providers;
    }

    public ModuleManager modules() {
        return modules;
    }

    public Set<Capability> activeCapabilities() {
        return modules.activeCapabilities();
    }

    public void registerModule(CctModule module) {
        modules.register(module);
    }

    public void addBeforeModules(LifecycleComponent component) {
        beforeModules.add(component);
    }

    public void addAfterModules(LifecycleComponent component) {
        afterModules.add(component);
    }

    public CompletableFuture<Void> start() {
        if (!started.compareAndSet(false, true)) {
            return CompletableFuture.failedFuture(new IllegalStateException("CCTSystem runtime already started"));
        }
        logger.info("Starting CCTSystem node " + config.nodeId() + " (boot " + bootId + ")");
        return startComponents(beforeModules)
            .thenCompose(ignored -> modules.startEligible())
            .thenCompose(ignored -> startComponents(afterModules));
    }

    public CompletableFuture<Void> stop() {
        if (!started.compareAndSet(true, false)) {
            return CompletableFuture.completedFuture(null);
        }
        List<LifecycleComponent> reversedAfter = reversed(afterModules);
        List<LifecycleComponent> reversedBefore = reversed(beforeModules);
        return stopComponents(reversedAfter)
            .thenCompose(ignored -> modules.stopAll())
            .thenCompose(ignored -> stopComponents(reversedBefore))
            .whenComplete((ignored, throwable) -> executors.close());
    }

    @Override
    public void close() {
        stop().join();
    }

    private static CompletableFuture<Void> startComponents(List<LifecycleComponent> components) {
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (LifecycleComponent component : components) {
            chain = chain.thenCompose(ignored -> component.start());
        }
        return chain;
    }

    private CompletableFuture<Void> stopComponents(List<LifecycleComponent> components) {
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (LifecycleComponent component : components) {
            chain = chain.thenCompose(ignored -> component.stop().exceptionally(throwable -> {
                logger.warn("Failed to stop component " + component.id(), throwable);
                return null;
            }));
        }
        return chain;
    }

    private static <T> List<T> reversed(List<T> values) {
        List<T> copy = new ArrayList<>(values);
        Collections.reverse(copy);
        return copy;
    }
}
