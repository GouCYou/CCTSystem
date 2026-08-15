package cn.cctstudio.cctsystem.core.concurrent;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class CctExecutors implements AutoCloseable {
    private final ExecutorService blockingExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
        Thread.ofPlatform().name("cctsystem-scheduler").factory()
    );

    public ExecutorService blocking() {
        return blockingExecutor;
    }

    public ScheduledExecutorService scheduler() {
        return scheduler;
    }

    @Override
    public void close() {
        scheduler.shutdown();
        blockingExecutor.shutdown();
        try {
            scheduler.awaitTermination(3, TimeUnit.SECONDS);
            blockingExecutor.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } finally {
            scheduler.shutdownNow();
            blockingExecutor.shutdownNow();
        }
    }
}
