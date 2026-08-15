package cn.cctstudio.cctsystem.core.config;

public record ModuleConfig(ModuleMode enabled) {
    public ModuleConfig {
        enabled = enabled == null ? ModuleMode.AUTO : enabled;
    }
}
