package cn.cctstudio.cctsystem.exchange;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class ExchangeClockTest {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    @Test
    void resetsAtShanghaiMondayInsteadOfUtcMonday() {
        Instant justBeforeReset = Instant.parse("2026-08-16T15:59:59Z");
        Instant atReset = Instant.parse("2026-08-16T16:00:00Z");

        assertEquals(LocalDate.parse("2026-08-10"), ExchangeClock.weekStart(justBeforeReset, SHANGHAI));
        assertEquals(LocalDate.parse("2026-08-17"), ExchangeClock.weekStart(atReset, SHANGHAI));
        assertEquals(atReset, ExchangeClock.nextReset(LocalDate.parse("2026-08-10"), SHANGHAI));
    }

    @Test
    void createsVersionSevenTransactionIds() {
        assertEquals(7, TimeOrderedUuid.create(Instant.now()).version());
    }
}
