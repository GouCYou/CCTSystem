package cn.cctstudio.cctsystem.contract;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;
import java.util.Locale;

public enum Capability {
    AUTH_VERIFY("auth.verify"),
    NETWORK_STATUS_READ("network.status.read"),
    SKIN_AVATAR_READ("skin.avatar.read"),
    PROFILE_READ("profile.read"),
    POINTS_READ("points.read"),
    POINTS_MUTATE("points.mutate"),
    EXCHANGE_EXECUTE("exchange.execute"),
    MEMBERSHIP_READ("membership.read"),
    MEMBERSHIP_MUTATE("membership.mutate"),
    REDEEM_EXECUTE("redeem.execute");

    private final String value;

    Capability(String value) {
        this.value = value;
    }

    @JsonCreator
    public static Capability fromValue(String value) {
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
            .filter(capability -> capability.value.equals(normalized))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown capability: " + value));
    }

    @JsonValue
    public String value() {
        return value;
    }
}
