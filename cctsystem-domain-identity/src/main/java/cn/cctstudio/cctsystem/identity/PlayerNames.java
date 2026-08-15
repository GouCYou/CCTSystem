package cn.cctstudio.cctsystem.identity;

import java.util.Locale;
import java.util.regex.Pattern;

public final class PlayerNames {
    private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9_]{3,16}");

    private PlayerNames() {
    }

    public static String requireValid(String value) {
        if (value == null || !VALID_NAME.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid Minecraft player name");
        }
        return value;
    }

    public static String normalize(String value) {
        return requireValid(value).toLowerCase(Locale.ROOT);
    }
}
