package cn.cctstudio.cctsystem.core.config;

import java.util.List;

public record ExchangeConfig(
    int weeklyLimitPoints,
    String timezone,
    List<ExchangeSourceConfig> sources
) {
    public ExchangeConfig {
        weeklyLimitPoints = weeklyLimitPoints <= 0 ? 300 : weeklyLimitPoints;
        timezone = timezone == null || timezone.isBlank() ? "Asia/Shanghai" : timezone.trim();
        sources = sources == null ? List.of() : List.copyOf(sources);
    }
}
