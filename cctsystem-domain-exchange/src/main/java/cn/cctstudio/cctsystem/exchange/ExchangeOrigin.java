package cn.cctstudio.cctsystem.exchange;

import java.util.Locale;

public enum ExchangeOrigin {
    WEB,
    MENU,
    COMMAND,
    NPC,
    ADMIN;

    public static ExchangeOrigin parse(String value) {
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
