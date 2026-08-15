package cn.cctstudio.cctsystem.contract;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

public enum PlatformType {
    PAPER,
    VELOCITY;

    @JsonCreator
    public static PlatformType fromValue(String value) {
        return PlatformType.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }

    @JsonValue
    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }
}
