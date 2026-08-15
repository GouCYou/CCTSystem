package cn.cctstudio.cctsystem.core.config;

import java.math.BigDecimal;
import java.util.Locale;

public record ExchangeSourceConfig(
    String sourceId,
    String serverId,
    String currencyDisplayName,
    BigDecimal currencyUnitsPerPoint,
    String capGroup,
    boolean enabled
) {
    public ExchangeSourceConfig {
        sourceId = normalize(sourceId);
        serverId = normalize(serverId);
        currencyDisplayName = currencyDisplayName == null ? "" : currencyDisplayName.trim();
        currencyUnitsPerPoint = currencyUnitsPerPoint == null ? BigDecimal.ZERO : currencyUnitsPerPoint;
        capGroup = normalize(capGroup);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
