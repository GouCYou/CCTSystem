package cn.cctstudio.cctsystem.core.lifecycle;

import java.util.concurrent.CompletableFuture;

public interface LifecycleComponent {
    String id();

    CompletableFuture<Void> start();

    CompletableFuture<Void> stop();
}
