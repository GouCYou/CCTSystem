package cn.cctstudio.cctsystem.storage.mysql;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.TimeZone;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BeijingDateTimeTest {
    @Test void chatIncidentIsConvertedOnceInEveryJvmTimezone() {
        TimeZone original = TimeZone.getDefault();
        try {
            for (String zone : new String[]{"UTC", "Asia/Shanghai", "America/Los_Angeles"}) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone));
                Instant expected = Instant.parse("2026-10-05T07:53:10.280Z");
                LocalDateTime recorded = LocalDateTime.parse("2026-10-05T15:53:10.280");
                assertEquals(expected, BeijingDateTime.toInstant(recorded));
                assertEquals(recorded, BeijingDateTime.fromInstant(expected));
            }
        } finally { TimeZone.setDefault(original); }
    }
    @Test void midnightAndDateFilterBoundariesUseBeijing() {
        assertEquals(Instant.parse("2026-10-04T16:00:00Z"),
            BeijingDateTime.toInstant(LocalDateTime.parse("2026-10-05T00:00:00")));
    }
}
