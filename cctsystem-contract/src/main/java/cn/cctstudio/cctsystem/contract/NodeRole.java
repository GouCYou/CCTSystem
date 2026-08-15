package cn.cctstudio.cctsystem.contract;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;
import java.util.Locale;

public enum NodeRole {
    PROXY("proxy"),
    NETWORK_AUTHORITY("network-authority"),
    SKIN_AUTHORITY("skin-authority"),
    AUTH_AUTHORITY("auth-authority"),
    BUSINESS_AUTHORITY("business-authority"),
    POINTS_AUTHORITY("points-authority"),
    GAMEPLAY("gameplay"),
    ECONOMY_SOURCE("economy-source");

    private final String value;

    NodeRole(String value) {
        this.value = value;
    }

    @JsonCreator
    public static NodeRole fromValue(String value) {
        String normalized = value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        return Arrays.stream(values())
            .filter(role -> role.value.equals(normalized))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown node role: " + value));
    }

    @JsonValue
    public String value() {
        return value;
    }
}
