package cn.cctstudio.cctsystem.core.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

public enum ModuleMode {
    ENABLED,
    DISABLED,
    AUTO;

    @JsonCreator
    public static ModuleMode fromValue(String value) {
        return ModuleMode.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }

    @JsonValue
    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }
}
