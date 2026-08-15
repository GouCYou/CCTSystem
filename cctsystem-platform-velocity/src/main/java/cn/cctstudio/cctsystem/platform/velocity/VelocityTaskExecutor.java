package cn.cctstudio.cctsystem.platform.velocity;

import cn.cctstudio.cctsystem.core.concurrent.PlatformTaskExecutor;
import com.velocitypowered.api.proxy.ProxyServer;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

final class VelocityTaskExecutor implements PlatformTaskExecutor {
    private final Object plugin;
    private final ProxyServer proxy;

    VelocityTaskExecutor(Object plugin, ProxyServer proxy) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.proxy = Objects.requireNonNull(proxy, "proxy");
    }

    @Override
    public <T> CompletableFuture<T> callMain(Callable<T> task) {
        CompletableFuture<T> future = new CompletableFuture<>();
        proxy.getScheduler().buildTask(plugin, () -> {
            try {
                future.complete(task.call());
            } catch (Throwable throwable) {
                future.completeExceptionally(throwable);
            }
        }).schedule();
        return future;
    }

    @Override
    public boolean isMainThread() {
        return false;
    }

    @Override
    public Executor asyncExecutor() {
        return command -> proxy.getScheduler().buildTask(plugin, command).schedule();
    }
}
