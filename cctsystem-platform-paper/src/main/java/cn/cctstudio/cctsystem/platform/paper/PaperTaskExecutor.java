package cn.cctstudio.cctsystem.platform.paper;

import cn.cctstudio.cctsystem.core.concurrent.PlatformTaskExecutor;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

final class PaperTaskExecutor implements PlatformTaskExecutor {
    private final JavaPlugin plugin;

    PaperTaskExecutor(JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @Override
    public <T> CompletableFuture<T> callMain(Callable<T> task) {
        CompletableFuture<T> future = new CompletableFuture<>();
        Runnable invocation = () -> {
            try {
                future.complete(task.call());
            } catch (Throwable throwable) {
                future.completeExceptionally(throwable);
            }
        };
        if (isMainThread()) {
            invocation.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, invocation);
        }
        return future;
    }

    @Override
    public boolean isMainThread() {
        return Bukkit.isPrimaryThread();
    }

    @Override
    public Executor asyncExecutor() {
        return command -> Bukkit.getScheduler().runTaskAsynchronously(plugin, command);
    }
}
