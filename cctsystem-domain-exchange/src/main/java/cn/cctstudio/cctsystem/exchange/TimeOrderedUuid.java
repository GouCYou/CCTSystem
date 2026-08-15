package cn.cctstudio.cctsystem.exchange;

import java.time.Instant;
import java.util.UUID;
import cn.cctstudio.cctsystem.core.id.UuidV7;

final class TimeOrderedUuid {
    private TimeOrderedUuid() {
    }

    static UUID create(Instant instant) {
        return UuidV7.create(instant);
    }
}
