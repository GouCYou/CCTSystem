package cn.cctstudio.cctsystem.core.id;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public final class UuidV7 {
    private UuidV7() {
    }

    public static UUID create(Instant instant) {
        long timestamp = instant.toEpochMilli() & 0x0000_FFFF_FFFF_FFFFL;
        long mostSignificant = (timestamp << 16)
            | 0x7000L
            | ThreadLocalRandom.current().nextLong(0x1000L);
        long leastSignificant = (ThreadLocalRandom.current().nextLong() & 0x3FFF_FFFF_FFFF_FFFFL)
            | 0x8000_0000_0000_0000L;
        return new UUID(mostSignificant, leastSignificant);
    }
}
