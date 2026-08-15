package cn.cctstudio.cctsystem.core.module;

import cn.cctstudio.cctsystem.contract.Capability;
import cn.cctstudio.cctsystem.core.config.ModuleMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;

public final class ModuleManager {
    private final ModuleContext context;
    private final Map<String, CctModule> modules = new LinkedHashMap<>();
    private final Map<String, ModuleStatus> statuses = new LinkedHashMap<>();

    public ModuleManager(ModuleContext context) {
        this.context = context;
    }

    public synchronized void register(CctModule module) {
        String id = module.descriptor().id();
        if (modules.putIfAbsent(id, module) != null) {
            throw new IllegalStateException("Duplicate module id: " + id);
        }
        statuses.put(id, new ModuleStatus(id, ModuleState.REGISTERED, "registered"));
    }

    public CompletableFuture<Void> startEligible() {
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (CctModule module : snapshotModules()) {
            chain = chain.thenCompose(ignored -> startOne(module));
        }
        return chain;
    }

    public CompletableFuture<Void> stopAll() {
        List<CctModule> reversed = snapshotModules();
        Collections.reverse(reversed);
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (CctModule module : reversed) {
            ModuleStatus current = status(module.descriptor().id());
            if (current.state() != ModuleState.ENABLED && current.state() != ModuleState.DEGRADED) {
                continue;
            }
            chain = chain.thenCompose(ignored -> module.stop().handle((result, throwable) -> {
                if (throwable != null) {
                    context.logger().warn("Failed to stop module " + module.descriptor().id(), throwable);
                }
                setStatus(module.descriptor().id(), ModuleState.STOPPED, "stopped");
                return null;
            }));
        }
        return chain;
    }

    public synchronized List<ModuleStatus> statuses() {
        return List.copyOf(statuses.values());
    }

    public synchronized Set<Capability> activeCapabilities() {
        return modules.values().stream()
            .filter(module -> {
                ModuleState state = statuses.get(module.descriptor().id()).state();
                return state == ModuleState.ENABLED || state == ModuleState.DEGRADED;
            })
            .flatMap(module -> module.descriptor().capabilities().stream())
            .collect(Collectors.toUnmodifiableSet());
    }

    private CompletableFuture<Void> startOne(CctModule module) {
        ModuleDescriptor descriptor = module.descriptor();
        String disabledReason = disabledReason(descriptor);
        if (disabledReason != null) {
            setStatus(descriptor.id(), ModuleState.DISABLED, disabledReason);
            context.logger().info("Module " + descriptor.id() + " disabled: " + disabledReason);
            return CompletableFuture.completedFuture(null);
        }

        setStatus(descriptor.id(), ModuleState.STARTING, "starting");
        return module.start(context).handle((result, throwable) -> {
            if (throwable != null) {
                Throwable cause = throwable instanceof CompletionException && throwable.getCause() != null
                    ? throwable.getCause()
                    : throwable;
                setStatus(descriptor.id(), ModuleState.FAILED, cause.getMessage());
                context.logger().error("Module " + descriptor.id() + " failed to start", cause);
            } else {
                setStatus(descriptor.id(), ModuleState.ENABLED, "enabled");
                context.logger().info("Module " + descriptor.id() + " enabled");
            }
            return null;
        });
    }

    private String disabledReason(ModuleDescriptor descriptor) {
        ModuleMode mode = context.config().moduleMode(descriptor.configKey());
        if (mode == ModuleMode.DISABLED) {
            return "disabled by config";
        }
        if (!descriptor.platforms().contains(context.platform())) {
            return "unsupported platform " + context.platform().value();
        }
        if (descriptor.roles().stream().noneMatch(context.config().roles()::contains)) {
            return "node role does not match";
        }
        Set<String> missing = descriptor.requiredProviders().stream()
            .filter(provider -> !context.providers().containsId(provider))
            .collect(Collectors.toUnmodifiableSet());
        if (!missing.isEmpty()) {
            return "missing providers " + String.join(",", missing);
        }
        return null;
    }

    private synchronized ModuleStatus status(String id) {
        return statuses.get(id);
    }

    private synchronized void setStatus(String id, ModuleState state, String reason) {
        statuses.put(id, new ModuleStatus(id, state, reason));
    }

    private synchronized List<CctModule> snapshotModules() {
        return new ArrayList<>(modules.values());
    }
}
