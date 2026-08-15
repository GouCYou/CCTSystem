package cn.cctstudio.cctsystem.promotion;

import cn.cctstudio.cctsystem.core.concurrent.CctExecutors;
import cn.cctstudio.cctsystem.core.id.UuidV7;
import cn.cctstudio.cctsystem.identity.UuidBinary;
import cn.cctstudio.cctsystem.storage.mysql.DatabaseAccess;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

final class JdbcPromotionService implements PromotionService {
    private final DatabaseAccess database;
    private final CctExecutors executors;

    JdbcPromotionService(DatabaseAccess database, CctExecutors executors) {
        this.database = database;
        this.executors = executors;
    }

    @Override
    public CompletionStage<Promotion> activeForMembership(String tierKey, Instant now) {
        String normalizedTier = normalizeTier(tierKey);
        return async(() -> {
            try (Connection connection = database.connection();
                 PreparedStatement query = connection.prepareStatement("""
                    SELECT p.promotion_id, p.name, p.scope, p.percent_off_bps,
                           p.starts_at, p.ends_at, p.priority, p.status
                    FROM cct_promotions p
                    LEFT JOIN cct_promotion_targets t
                      ON t.promotion_id = p.promotion_id AND t.tier_key = ?
                    WHERE p.status = 'ACTIVE' AND p.starts_at <= ? AND p.ends_at > ?
                      AND (p.scope = 'MEMBERSHIP_ALL'
                           OR (p.scope = 'MEMBERSHIP_TIER' AND t.tier_key IS NOT NULL))
                    ORDER BY p.priority DESC, p.percent_off_bps DESC, p.ends_at ASC
                    LIMIT 1
                    """)) {
                query.setString(1, normalizedTier);
                setInstant(query, 2, now);
                setInstant(query, 3, now);
                try (ResultSet result = query.executeQuery()) {
                    return result.next() ? map(result, normalizedTier) : Promotion.none();
                }
            }
        });
    }

    @Override
    public CompletionStage<Promotion> create(CreatePromotionRequest request) {
        validate(request);
        return async(() -> {
            UUID promotionId = UuidV7.create(request.startsAt());
            String target = request.targetTierKey() == null ? null : normalizeTier(request.targetTierKey());
            try (Connection connection = database.connection()) {
                boolean autoCommit = connection.getAutoCommit();
                connection.setAutoCommit(false);
                try {
                    try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO cct_promotions(
                            promotion_id, name, scope, percent_off_bps, starts_at, ends_at,
                            priority, status, created_by, reason
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?)
                        """)) {
                        insert.setBytes(1, UuidBinary.encode(promotionId));
                        insert.setString(2, request.name().trim());
                        insert.setString(3, target == null ? "MEMBERSHIP_ALL" : "MEMBERSHIP_TIER");
                        insert.setInt(4, request.percentOffBps());
                        setInstant(insert, 5, request.startsAt());
                        setInstant(insert, 6, request.endsAt());
                        insert.setInt(7, request.priority());
                        insert.setString(8, request.actor().trim());
                        insert.setString(9, request.reason().trim());
                        insert.executeUpdate();
                    }
                    if (target != null) {
                        try (PreparedStatement insertTarget = connection.prepareStatement(
                            "INSERT INTO cct_promotion_targets(promotion_id, tier_key) VALUES (?, ?)"
                        )) {
                            insertTarget.setBytes(1, UuidBinary.encode(promotionId));
                            insertTarget.setString(2, target);
                            insertTarget.executeUpdate();
                        }
                    }
                    connection.commit();
                    return new Promotion(
                        promotionId,
                        request.name().trim(),
                        target,
                        request.percentOffBps(),
                        request.startsAt(),
                        request.endsAt(),
                        request.priority(),
                        "ACTIVE"
                    );
                } catch (SQLException exception) {
                    connection.rollback();
                    throw exception;
                } finally {
                    connection.setAutoCommit(autoCommit);
                }
            }
        });
    }

    @Override
    public CompletionStage<Boolean> stop(UUID promotionId, String actor, String reason, Instant now) {
        if (promotionId == null || actor == null || actor.isBlank() || actor.length() > 80
            || reason == null || reason.isBlank() || reason.length() > 255) {
            throw new IllegalArgumentException("Invalid promotion stop request");
        }
        return async(() -> {
            try (Connection connection = database.connection();
                 PreparedStatement update = connection.prepareStatement("""
                    UPDATE cct_promotions
                    SET status = 'STOPPED', stopped_by = ?, stopped_reason = ?, stopped_at = ?
                    WHERE promotion_id = ? AND status = 'ACTIVE'
                    """)) {
                update.setString(1, actor.trim());
                update.setString(2, reason.trim());
                setInstant(update, 3, now);
                update.setBytes(4, UuidBinary.encode(promotionId));
                return update.executeUpdate() == 1;
            }
        });
    }

    @Override
    public CompletionStage<List<Promotion>> listCurrent(Instant now) {
        return async(() -> {
            try (Connection connection = database.connection();
                 PreparedStatement query = connection.prepareStatement("""
                    SELECT p.promotion_id, p.name, p.scope, p.percent_off_bps,
                           p.starts_at, p.ends_at, p.priority, p.status,
                           MIN(t.tier_key) AS target_tier
                    FROM cct_promotions p
                    LEFT JOIN cct_promotion_targets t ON t.promotion_id = p.promotion_id
                    WHERE p.status = 'ACTIVE' AND p.ends_at > ?
                    GROUP BY p.promotion_id, p.name, p.scope, p.percent_off_bps,
                             p.starts_at, p.ends_at, p.priority, p.status
                    ORDER BY p.starts_at, p.priority DESC
                    """)) {
                setInstant(query, 1, now);
                List<Promotion> promotions = new ArrayList<>();
                try (ResultSet result = query.executeQuery()) {
                    while (result.next()) {
                        promotions.add(map(result, result.getString("target_tier")));
                    }
                }
                return List.copyOf(promotions);
            }
        });
    }

    @Override
    public CompletionStage<Promotion> find(UUID promotionId) {
        if (promotionId == null) {
            throw new IllegalArgumentException("promotionId is required");
        }
        return async(() -> {
            try (Connection connection = database.connection();
                 PreparedStatement query = connection.prepareStatement("""
                    SELECT p.promotion_id, p.name, p.scope, p.percent_off_bps,
                           p.starts_at, p.ends_at, p.priority, p.status,
                           MIN(t.tier_key) AS target_tier
                    FROM cct_promotions p
                    LEFT JOIN cct_promotion_targets t ON t.promotion_id = p.promotion_id
                    WHERE p.promotion_id = ?
                    GROUP BY p.promotion_id, p.name, p.scope, p.percent_off_bps,
                             p.starts_at, p.ends_at, p.priority, p.status
                    """)) {
                query.setBytes(1, UuidBinary.encode(promotionId));
                try (ResultSet result = query.executeQuery()) {
                    if (!result.next()) {
                        throw new IllegalArgumentException("Promotion does not exist");
                    }
                    return map(result, result.getString("target_tier"));
                }
            }
        });
    }

    private <T> CompletionStage<T> async(SqlSupplier<T> action) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return action.get();
            } catch (SQLException exception) {
                throw new CompletionException("Promotion database operation failed", exception);
            }
        }, executors.blocking());
    }

    private static Promotion map(ResultSet result, String targetTier) throws SQLException {
        return new Promotion(
            UuidBinary.decode(result.getBytes("promotion_id")),
            result.getString("name"),
            "MEMBERSHIP_ALL".equals(result.getString("scope")) ? null : targetTier,
            result.getInt("percent_off_bps"),
            instant(result, "starts_at"),
            instant(result, "ends_at"),
            result.getInt("priority"),
            result.getString("status")
        );
    }

    private static void validate(CreatePromotionRequest request) {
        if (request == null || request.name() == null || request.name().isBlank()
            || request.name().length() > 64 || request.percentOffBps() < 1
            || request.percentOffBps() > 10_000 || request.startsAt() == null
            || request.endsAt() == null || !request.endsAt().isAfter(request.startsAt())
            || request.actor() == null || request.actor().isBlank() || request.actor().length() > 80
            || request.reason() == null || request.reason().isBlank() || request.reason().length() > 255) {
            throw new IllegalArgumentException("Invalid promotion request");
        }
        if (request.targetTierKey() != null) {
            normalizeTier(request.targetTierKey());
        }
    }

    private static String normalizeTier(String tierKey) {
        if (tierKey == null || !tierKey.matches("[a-z0-9][a-z0-9_-]{1,63}")) {
            throw new IllegalArgumentException("Invalid membership tier key");
        }
        return tierKey.toLowerCase(Locale.ROOT);
    }

    private static void setInstant(PreparedStatement statement, int index, Instant value) throws SQLException {
        statement.setObject(index, LocalDateTime.ofInstant(value, ZoneOffset.UTC));
    }

    private static Instant instant(ResultSet result, String column) throws SQLException {
        return result.getObject(column, LocalDateTime.class).toInstant(ZoneOffset.UTC);
    }

    @FunctionalInterface
    private interface SqlSupplier<T> {
        T get() throws SQLException;
    }
}
