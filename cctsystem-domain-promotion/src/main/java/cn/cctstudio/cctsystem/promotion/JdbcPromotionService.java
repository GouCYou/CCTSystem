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
                           p.starts_at, p.ends_at, p.priority, p.status, p.discount_code, p.max_uses_per_player, p.max_players
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
                    retireCodes(connection,Instant.now());
                    try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO cct_promotions(
                            promotion_id, name, scope, percent_off_bps, starts_at, ends_at,
                            priority, status, created_by, reason, discount_code, max_uses_per_player, max_players
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, ?, ?)
                        """)) {
                        insert.setBytes(1, UuidBinary.encode(promotionId));
                        insert.setString(2, request.name().trim());
                        insert.setString(3, scope(target, request.discountCode()));
                        insert.setInt(4, request.percentOffBps());
                        setInstant(insert, 5, request.startsAt());
                        setInstant(insert, 6, request.endsAt());
                        insert.setInt(7, request.priority());
                        insert.setString(8, request.actor().trim());
                        insert.setString(9, request.reason().trim());
                        insert.setString(10, normalizedCode(request.discountCode()));
                        insert.setObject(11,request.maxUsesPerPlayer()); insert.setObject(12,request.maxPlayers());
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
                        "ACTIVE", normalizedCode(request.discountCode()), request.maxUsesPerPlayer(), request.maxPlayers(), 0
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
                           p.starts_at, p.ends_at, p.priority, p.status, p.discount_code, p.max_uses_per_player, p.max_players,
                           MIN(t.tier_key) AS target_tier
                    FROM cct_promotions p
                    LEFT JOIN cct_promotion_targets t ON t.promotion_id = p.promotion_id
                    WHERE p.status = 'ACTIVE' AND p.discount_code IS NULL AND p.ends_at > ?
                    GROUP BY p.promotion_id, p.name, p.scope, p.percent_off_bps,
                             p.starts_at, p.ends_at, p.priority, p.status, p.discount_code, p.max_uses_per_player, p.max_players
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
                           p.starts_at, p.ends_at, p.priority, p.status, p.discount_code, p.max_uses_per_player, p.max_players,
                           MIN(t.tier_key) AS target_tier
                    FROM cct_promotions p
                    LEFT JOIN cct_promotion_targets t ON t.promotion_id = p.promotion_id
                    WHERE p.promotion_id = ?
                    GROUP BY p.promotion_id, p.name, p.scope, p.percent_off_bps,
                             p.starts_at, p.ends_at, p.priority, p.status, p.discount_code, p.max_uses_per_player, p.max_players
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

    private static void retireCodes(Connection connection, Instant now) throws SQLException {
        try (PreparedStatement update=connection.prepareStatement("""
            UPDATE cct_promotions p SET status=CASE WHEN ends_at<=? THEN 'EXPIRED' ELSE 'EXHAUSTED' END
            WHERE discount_code IS NOT NULL AND status='ACTIVE' AND (ends_at<=? OR
              (max_players IS NOT NULL AND (SELECT COUNT(DISTINCT o.player_uuid) FROM cct_membership_orders o
                WHERE o.promotion_id=p.promotion_id AND o.status='COMPLETED')>=max_players))
            """)) { setInstant(update,1,now); setInstant(update,2,now); update.executeUpdate(); }
    }

    private static String scope(String tier, String code) {
        return (code == null ? "MEMBERSHIP_" : "MEMBERSHIP_CODE_") + (tier == null ? "ALL" : "TIER");
    }

    static String normalizedCode(String code) {
        if (code == null) return null;
        String value = java.text.Normalizer.normalize(code.trim(), java.text.Normalizer.Form.NFC).toUpperCase(Locale.ROOT);
        if (!value.matches("[\\p{L}\\p{N}_-]{1,32}")) throw new IllegalArgumentException("Invalid discount code");
        return value;
    }

    @Override
    public CompletionStage<Promotion> forCode(String code, String tierKey, Instant now) {
        String value = normalizedCode(code);
        return async(() -> {
            try (Connection connection = database.connection(); PreparedStatement query = connection.prepareStatement("""
                SELECT p.*, t.tier_key AS target_tier FROM cct_promotions p
                LEFT JOIN cct_promotion_targets t ON t.promotion_id = p.promotion_id
                WHERE p.discount_code=? AND p.status='ACTIVE' AND p.starts_at<=? AND p.ends_at>?
                  AND (p.scope='MEMBERSHIP_CODE_ALL' OR (p.scope='MEMBERSHIP_CODE_TIER' AND t.tier_key=?))
                LIMIT 1
                """)) {
                retireCodes(connection,now);
                query.setString(1,value); setInstant(query,2,now); setInstant(query,3,now); query.setString(4,normalizeTier(tierKey));
                try (ResultSet rows = query.executeQuery()) {
                    if (!rows.next()) throw new IllegalArgumentException("DISCOUNT_CODE_UNAVAILABLE");
                    return map(rows,rows.getString("target_tier"));
                }
            }
        });
    }

    @Override
    public CompletionStage<Promotion> forCode(String code, String tierKey, UUID playerUuid, Instant now) {
        return forCode(code,tierKey,now).thenCompose(promotion -> async(() -> {
            try (Connection connection=database.connection(); PreparedStatement query=connection.prepareStatement("""
                SELECT player_uuid FROM cct_membership_orders WHERE promotion_id=? AND status NOT IN ('FAILED','COMPENSATED')
                """)) {
                query.setBytes(1,UuidBinary.encode(promotion.promotionId()));
                java.util.Set<UUID> players=new java.util.HashSet<>(); int personal=0;
                try (ResultSet rows=query.executeQuery()) { while(rows.next()) { UUID player=UuidBinary.decode(rows.getBytes(1)); players.add(player); if(player.equals(playerUuid)) personal++; } }
                if (promotion.maxUsesPerPlayer()!=null && personal>=promotion.maxUsesPerPlayer()) throw new IllegalArgumentException("DISCOUNT_CODE_LIMIT_REACHED");
                if (promotion.maxPlayers()!=null && players.size()>=promotion.maxPlayers()) throw new IllegalArgumentException("DISCOUNT_CODE_LIMIT_REACHED");
                return promotion;
            }
        }));
    }

    @Override
    public CompletionStage<Promotion> forCodeAny(String code, UUID playerUuid, Instant now) {
        String value=normalizedCode(code);
        return async(() -> {
            try(Connection connection=database.connection(); PreparedStatement query=connection.prepareStatement("""
                SELECT p.*, t.tier_key AS target_tier FROM cct_promotions p
                LEFT JOIN cct_promotion_targets t ON t.promotion_id=p.promotion_id
                WHERE p.discount_code=? AND p.status='ACTIVE' AND p.starts_at<=? AND p.ends_at>? LIMIT 1
                """)) {
                retireCodes(connection,now); query.setString(1,value); setInstant(query,2,now); setInstant(query,3,now);
                try(ResultSet rows=query.executeQuery()) {
                    if(!rows.next()) throw new IllegalArgumentException("DISCOUNT_CODE_UNAVAILABLE");
                    return map(rows,rows.getString("target_tier"));
                }
            }
        }).thenCompose(selected -> forCode(value,selected.targetTierKey()==null ? "vip" : selected.targetTierKey(),playerUuid,now));
    }

    @Override
    public CompletionStage<List<Promotion>> listCodes(Instant now) {
        return async(() -> {
            try (Connection connection = database.connection(); PreparedStatement query = connection.prepareStatement("""
                SELECT p.*, t.tier_key AS target_tier, (SELECT COUNT(DISTINCT o.player_uuid) FROM cct_membership_orders o WHERE o.promotion_id=p.promotion_id AND o.status NOT IN ('FAILED','COMPENSATED')) AS used_players FROM cct_promotions p
                LEFT JOIN cct_promotion_targets t ON t.promotion_id = p.promotion_id
                WHERE p.discount_code IS NOT NULL AND p.status <> 'DELETED'
                ORDER BY p.created_at DESC LIMIT 1000
                """)) {
                retireCodes(connection,now);
                List<Promotion> items = new ArrayList<>();
                try (ResultSet rows = query.executeQuery()) { while (rows.next()) {
                    Promotion p=map(rows,rows.getString("target_tier"));
                    items.add(new Promotion(p.promotionId(),p.name(),p.targetTierKey(),p.percentOffBps(),p.startsAt(),p.endsAt(),p.priority(),p.status(),p.discountCode(),p.maxUsesPerPlayer(),p.maxPlayers(),rows.getInt("used_players")));
                } }
                return List.copyOf(items);
            }
        });
    }

    @Override
    public CompletionStage<Promotion> updateCode(UUID id, CreatePromotionRequest request) {
        validate(request);
        if (request.discountCode() == null) throw new IllegalArgumentException("Discount code required");
        return async(() -> {
            try (Connection connection = database.connection()) {
                connection.setAutoCommit(false);
                try {
                    retireCodes(connection,Instant.now());
                    try (PreparedStatement update = connection.prepareStatement("""
                        UPDATE cct_promotions SET name=?, scope=?, percent_off_bps=?, starts_at=?, ends_at=?,
                            discount_code=?, created_by=?, reason=?, max_uses_per_player=?, max_players=?, status='ACTIVE'
                        WHERE promotion_id=? AND discount_code IS NOT NULL AND status NOT IN ('DELETED','EXHAUSTED')
                        """)) {
                        update.setString(1, request.name().trim()); update.setString(2, scope(request.targetTierKey(), request.discountCode()));
                        update.setInt(3, request.percentOffBps()); setInstant(update,4,request.startsAt()); setInstant(update,5,request.endsAt());
                        update.setString(6,normalizedCode(request.discountCode())); update.setString(7,request.actor()); update.setString(8,request.reason());
                        update.setObject(9,request.maxUsesPerPlayer()); update.setObject(10,request.maxPlayers());
                        update.setBytes(11,UuidBinary.encode(id));
                        if (update.executeUpdate()!=1) throw new IllegalArgumentException("Discount code not found");
                    }
                    try (PreparedStatement delete = connection.prepareStatement("DELETE FROM cct_promotion_targets WHERE promotion_id=?")) {
                        delete.setBytes(1,UuidBinary.encode(id)); delete.executeUpdate();
                    }
                    if (request.targetTierKey()!=null) try (PreparedStatement insert = connection.prepareStatement("INSERT INTO cct_promotion_targets(promotion_id,tier_key) VALUES (?,?)")) {
                        insert.setBytes(1,UuidBinary.encode(id)); insert.setString(2,request.targetTierKey()); insert.executeUpdate();
                    }
                    connection.commit();
                    return new Promotion(id,request.name().trim(),request.targetTierKey(),request.percentOffBps(),request.startsAt(),request.endsAt(),request.priority(),"ACTIVE",normalizedCode(request.discountCode()),request.maxUsesPerPlayer(),request.maxPlayers(),0);
                } catch (SQLException | RuntimeException failure) { connection.rollback(); throw failure; }
            }
        });
    }

    @Override
    public CompletionStage<Boolean> deleteCode(UUID id) {
        return async(() -> {
            try (Connection connection = database.connection(); PreparedStatement update = connection.prepareStatement(
                "UPDATE cct_promotions SET status='DELETED' WHERE promotion_id=? AND discount_code IS NOT NULL AND status <> 'DELETED'")) {
                update.setBytes(1,UuidBinary.encode(id)); return update.executeUpdate()==1;
            }
        });
    }

    private <T> CompletionStage<T> async(SqlSupplier<T> action) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return action.get();
            } catch (SQLException exception) {
                if (exception.getErrorCode()==1062) throw new CompletionException(new IllegalArgumentException("DISCOUNT_CODE_DUPLICATE"));
                throw new CompletionException("Promotion database operation failed", exception);
            }
        }, executors.blocking());
    }

    private static Promotion map(ResultSet result, String targetTier) throws SQLException {
        return new Promotion(
            UuidBinary.decode(result.getBytes("promotion_id")),
            result.getString("name"),
            result.getString("scope").endsWith("_ALL") ? null : targetTier,
            result.getInt("percent_off_bps"),
            instant(result, "starts_at"),
            instant(result, "ends_at"),
            result.getInt("priority"),
            result.getString("status"),
            result.getString("discount_code"),
            (Integer) result.getObject("max_uses_per_player"), (Integer) result.getObject("max_players"), 0
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
        normalizedCode(request.discountCode());
        if ((request.maxUsesPerPlayer()!=null && request.maxUsesPerPlayer()!=1)
            || (request.maxPlayers()!=null && (request.maxPlayers()<1 || request.maxPlayers()>1000000))) throw new IllegalArgumentException("Invalid usage limits");
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
