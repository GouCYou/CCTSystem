package cn.cctstudio.cctsystem.core.module;

import cn.cctstudio.cctsystem.contract.PlatformType;
import cn.cctstudio.cctsystem.core.concurrent.CctExecutors;
import cn.cctstudio.cctsystem.core.concurrent.PlatformTaskExecutor;
import cn.cctstudio.cctsystem.core.config.CctConfig;
import cn.cctstudio.cctsystem.core.logging.CctLogger;
import cn.cctstudio.cctsystem.core.provider.ProviderRegistry;
import java.util.Objects;

public record ModuleContext(
    PlatformType platform,
    CctConfig config,
    ProviderRegistry providers,
    CctExecutors executors,
    PlatformTaskExecutor platformTasks,
    CctLogger logger
) {
    public ModuleContext {
        Objects.requireNonNull(platform, "platform");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(providers, "providers");
        Objects.requireNonNull(executors, "executors");
        Objects.requireNonNull(platformTasks, "platformTasks");
        Objects.requireNonNull(logger, "logger");
    }
}
