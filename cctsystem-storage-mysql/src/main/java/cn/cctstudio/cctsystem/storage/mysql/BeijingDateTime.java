package cn.cctstudio.cctsystem.storage.mysql;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/** Conversion for legacy DATETIME columns written in server Beijing wall time.
 * Membership, promotion and redstone columns explicitly store UTC and must keep UTC conversion. */
public final class BeijingDateTime {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private BeijingDateTime() { }
    public static LocalDateTime fromInstant(Instant value) { return LocalDateTime.ofInstant(value, ZONE); }
    public static Instant toInstant(LocalDateTime value) { return value.atZone(ZONE).toInstant(); }
}
