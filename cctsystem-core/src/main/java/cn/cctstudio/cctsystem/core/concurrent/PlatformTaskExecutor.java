package cn.cctstudio.cctsystem.core.concurrent;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

public interface PlatformTaskExecutor {
    <T> CompletableFuture<T> callMain(Callable<T> task);

    boolean isMainThread();

    Executor asyncExecutor();
}
