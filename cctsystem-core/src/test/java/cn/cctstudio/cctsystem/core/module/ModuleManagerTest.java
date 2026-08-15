package cn.cctstudio.cctsystem.core.module;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.cctstudio.cctsystem.contract.Capability;
import cn.cctstudio.cctsystem.contract.NodeRole;
import cn.cctstudio.cctsystem.contract.PlatformType;
import cn.cctstudio.cctsystem.core.concurrent.CctExecutors;
import cn.cctstudio.cctsystem.core.concurrent.PlatformTaskExecutor;
import cn.cctstudio.cctsystem.core.config.BridgeConfig;
import cn.cctstudio.cctsystem.core.config.CctConfig;
import cn.cctstudio.cctsystem.core.config.DatabaseConfig;
import cn.cctstudio.cctsystem.core.logging.CctLogger;
import cn.cctstudio.cctsystem.core.provider.ProviderKey;
import cn.cctstudio.cctsystem.core.provider.ProviderRegistry;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

final class ModuleManagerTest {
    @Test
    void enablesOnlyWhenPlatformRoleAndProviderAllMatch() {
        CctExecutors executors = new CctExecutors();
        try {
            ProviderRegistry providers = new ProviderRegistry();
            providers.register(ProviderKey.of("vault", Object.class), new Object());
            ModuleContext context = new ModuleContext(
                PlatformType.PAPER,
                config(),
                providers,
                executors,
                new DirectTasks(),
                new NoopLogger()
            );
            AtomicBoolean started = new AtomicBoolean();
            ModuleManager manager = new ModuleManager(context);
            manager.register(new TestModule(started));

            manager.startEligible().join();

            assertTrue(started.get());
            assertEquals(ModuleState.ENABLED, manager.statuses().getFirst().state());
            assertTrue(manager.activeCapabilities().contains(Capability.EXCHANGE_EXECUTE));
        } finally {
            executors.close();
        }
    }

    private static CctConfig config() {
        return new CctConfig(
            "cct-main",
            "survival",
            "survival-1",
            Set.of(NodeRole.ECONOMY_SOURCE),
            new DatabaseConfig(false, "", "", "", 6, 5_000),
            new BridgeConfig(false, "", "", 10, 8),
            new LinkedHashMap<>()
        );
    }

    private static final class TestModule implements CctModule {
        private final AtomicBoolean started;

        private TestModule(AtomicBoolean started) {
            this.started = started;
        }

        @Override
        public ModuleDescriptor descriptor() {
            return new ModuleDescriptor(
                "exchange",
                Set.of(PlatformType.PAPER),
                Set.of(NodeRole.ECONOMY_SOURCE),
                Set.of("vault"),
                Set.of(Capability.EXCHANGE_EXECUTE)
            );
        }

        @Override
        public CompletableFuture<Void> start(ModuleContext context) {
            started.set(true);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> stop() {
            return CompletableFuture.completedFuture(null);
        }
    }

    private static final class DirectTasks implements PlatformTaskExecutor {
        @Override
        public <T> CompletableFuture<T> callMain(Callable<T> task) {
            try {
                return CompletableFuture.completedFuture(task.call());
            } catch (Exception exception) {
                return CompletableFuture.failedFuture(exception);
            }
        }

        @Override
        public boolean isMainThread() {
            return true;
        }

        @Override
        public Executor asyncExecutor() {
            return Runnable::run;
        }
    }

    private static final class NoopLogger implements CctLogger {
        @Override public void info(String message) { }
        @Override public void warn(String message) { }
        @Override public void warn(String message, Throwable throwable) { }
        @Override public void error(String message, Throwable throwable) { }
    }
}
