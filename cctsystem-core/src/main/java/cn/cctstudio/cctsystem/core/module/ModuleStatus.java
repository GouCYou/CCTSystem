package cn.cctstudio.cctsystem.core.module;

import java.util.Objects;

public record ModuleStatus(String moduleId, ModuleState state, String reason) {
    public ModuleStatus {
        Objects.requireNonNull(moduleId, "moduleId");
        Objects.requireNonNull(state, "state");
        reason = reason == null ? "" : reason;
    }
}
