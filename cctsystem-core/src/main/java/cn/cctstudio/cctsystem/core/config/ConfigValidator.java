package cn.cctstudio.cctsystem.core.config;

import java.net.URI;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

public final class ConfigValidator {
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9_-]{1,63}");

    private ConfigValidator() {
    }

    public static void validate(CctConfig config) {
        requireId("network-id", config.networkId());
        requireId("server-id", config.serverId());
        requireId("node-id", config.nodeId());
        if (config.serverId().equals("change-me") || config.nodeId().equals("change-me")) {
            throw new ConfigException("server-id and node-id must be configured before CCTSystem can start");
        }
        if (config.roles().isEmpty()) {
            throw new ConfigException("At least one node role must be configured");
        }
        validateDatabase(config.database());
        validateBridge(config.bridge());
        validateExchange(config.exchange());
        validateMembership(config.membership());
        validatePromotion(config.promotion());
        if (config.redeemCode().length() < 8 || config.redeemCode().length() > 32) {
            throw new ConfigException("redeem-code.length must be between 8 and 32");
        }
    }

    private static void validateMembership(MembershipConfig membership) {
        if (membership.tiers().isEmpty()) {
            throw new ConfigException("membership.tiers must not be empty");
        }
        Set<String> tierKeys = new HashSet<>();
        Set<Integer> priorities = new HashSet<>();
        for (MembershipTierConfig tier : membership.tiers()) {
            requireId("membership.tiers.key", tier.key());
            if (!tierKeys.add(tier.key())) {
                throw new ConfigException("Duplicate membership tier key: " + tier.key());
            }
            if (!priorities.add(tier.priority())) {
                throw new ConfigException("Duplicate membership priority: " + tier.priority());
            }
            if (tier.displayName().isBlank() || tier.displayName().length() > 24
                || tier.luckPermsGroup().isBlank() || tier.luckPermsGroup().length() > 64) {
                throw new ConfigException("membership tier display-name or luckperms-group is invalid");
            }
            if (tier.priority() < 1 || tier.durationDays() < 1 || tier.durationDays() > 3650
                || tier.pricePoints() < 0 || tier.upgradeCreditRateBps() < 0
                || tier.upgradeCreditRateBps() > 10_000) {
                throw new ConfigException("membership tier numeric value is invalid: " + tier.key());
            }
        }
        if (membership.quoteTtlSeconds() < 5 || membership.quoteTtlSeconds() > 60) {
            throw new ConfigException("membership.quote-ttl-seconds must be between 5 and 60");
        }
        if (membership.maxPurchaseDays() < 30 || membership.maxPurchaseDays() > 3650) {
            throw new ConfigException("membership.max-purchase-days must be between 30 and 3650");
        }
        for (String group : membership.purchaseBlockedGroups()) {
            requireId("membership.purchase-blocked-groups", group);
        }
    }

    private static void validatePromotion(PromotionConfig promotion) {
        try {
            ZoneId.of(promotion.timezone());
        } catch (DateTimeException exception) {
            throw new ConfigException("promotion.timezone is invalid", exception);
        }
    }

    private static void validateExchange(ExchangeConfig exchange) {
        if (exchange.weeklyLimitPoints() < 1 || exchange.weeklyLimitPoints() > 100_000) {
            throw new ConfigException("exchange.weekly-limit-points must be between 1 and 100000");
        }
        try {
            ZoneId.of(exchange.timezone());
        } catch (DateTimeException exception) {
            throw new ConfigException("exchange.timezone is invalid", exception);
        }
        Set<String> sourceIds = new HashSet<>();
        for (ExchangeSourceConfig source : exchange.sources()) {
            requireId("exchange.sources.source-id", source.sourceId());
            requireId("exchange.sources.server-id", source.serverId());
            requireId("exchange.sources.cap-group", source.capGroup());
            if (!sourceIds.add(source.sourceId())) {
                throw new ConfigException("Duplicate exchange source-id: " + source.sourceId());
            }
            if (source.currencyDisplayName().isBlank() || source.currencyDisplayName().length() > 32) {
                throw new ConfigException("exchange source currency-display-name must contain 1-32 characters");
            }
            if (source.currencyUnitsPerPoint().signum() <= 0
                || source.currencyUnitsPerPoint().scale() > 4
                || source.currencyUnitsPerPoint().precision() > 18) {
                throw new ConfigException("exchange source currency-units-per-point is invalid");
            }
        }
    }

    private static void validateDatabase(DatabaseConfig database) {
        if (!database.enabled()) {
            return;
        }
        if (!database.jdbcUrl().startsWith("jdbc:mysql:")) {
            throw new ConfigException("database.jdbc-url must use jdbc:mysql when database is enabled");
        }
        if (database.username().isBlank() || database.password().isBlank()) {
            throw new ConfigException("database credentials are required when database is enabled");
        }
        if (database.maximumPoolSize() > 20) {
            throw new ConfigException("database.maximum-pool-size must not exceed 20 per node");
        }
    }

    private static void validateBridge(BridgeConfig bridge) {
        if (!bridge.enabled()) {
            return;
        }
        URI uri;
        try {
            uri = URI.create(bridge.url());
        } catch (IllegalArgumentException exception) {
            throw new ConfigException("bridge.url is invalid", exception);
        }
        if (!"wss".equalsIgnoreCase(uri.getScheme())) {
            throw new ConfigException("bridge.url must use wss");
        }
        if (bridge.secret().length() < 32) {
            throw new ConfigException("bridge.secret must contain at least 32 characters");
        }
    }

    private static void requireId(String field, String value) {
        if (!ID.matcher(value).matches()) {
            throw new ConfigException(field + " must match " + ID.pattern());
        }
    }
}
