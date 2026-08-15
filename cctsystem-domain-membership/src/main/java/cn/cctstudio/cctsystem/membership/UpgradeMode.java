package cn.cctstudio.cctsystem.membership;

import java.util.Locale;

public enum UpgradeMode {
    NONE,
    PAUSE,
    CREDIT;

    public static UpgradeMode parse(String value) {
        return value == null || value.isBlank()
            ? NONE
            : valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
