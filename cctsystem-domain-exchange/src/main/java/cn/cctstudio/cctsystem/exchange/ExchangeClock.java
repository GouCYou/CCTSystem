package cn.cctstudio.cctsystem.exchange;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;

final class ExchangeClock {
    private ExchangeClock() {
    }

    static LocalDate weekStart(Instant instant, ZoneId timezone) {
        return instant.atZone(timezone).toLocalDate()
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    static Instant nextReset(LocalDate weekStart, ZoneId timezone) {
        return weekStart.plusWeeks(1).atStartOfDay(timezone).toInstant();
    }
}
