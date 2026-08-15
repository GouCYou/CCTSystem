package cn.cctstudio.cctsystem.membership;

import cn.cctstudio.cctsystem.core.concurrent.CctExecutors;
import cn.cctstudio.cctsystem.core.config.MembershipTierConfig;
import cn.cctstudio.cctsystem.core.id.UuidV7;
import cn.cctstudio.cctsystem.identity.UuidBinary;
import cn.cctstudio.cctsystem.points.PointsMutationResult;
import cn.cctstudio.cctsystem.storage.mysql.DatabaseAccess;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

final class JdbcMembershipStore {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ENTITLEMENT_COLUMNS = """
        e.entitlement_id, e.state, e.starts_at, e.expires_at,
        e.remaining_seconds, e.resume_sequence,
        t.tier_key, t.display_name, t.priority, t.luckperms_group,
        t.duration_days, t.price_points, t.upgrade_credit_rate_bps,
        t.display_material, t.benefits_json, t.enabled, t.config_version
        """;

    private final DatabaseAccess database;
    private final CctExecutors executors;
    private final String nodeId;

    JdbcMembershipStore(DatabaseAccess database, CctExecutors executors, String nodeId) {
        this.database = database;
        this.executors = executors;
        this.nodeId = nodeId;
    }

    CompletionStage<Void> syncConfiguration(Collection<MembershipTierConfig> tiers) {
        return async(() -> {
            try (Connection connection = database.connection()) {
                for (MembershipTierConfig tier : tiers) {
                    try (PreparedStatement upsert = connection.prepareStatement("""
                        INSERT INTO cct_membership_tiers(
                            tier_key, display_name, priority, luckperms_group, duration_days,
                            price_points, upgrade_credit_rate_bps, display_material,
                            benefits_json, enabled
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON DUPLICATE KEY UPDATE
                            display_name = VALUES(display_name),
                            priority = VALUES(priority),
                            luckperms_group = VALUES(luckperms_group),
                            duration_days = VALUES(duration_days),
                            price_points = VALUES(price_points),
                            upgrade_credit_rate_bps = VALUES(upgrade_credit_rate_bps),
                            display_material = VALUES(display_material),
                            benefits_json = VALUES(benefits_json),
                            enabled = VALUES(enabled)
                        """)) {
                        upsert.setString(1, tier.key());
                        upsert.setString(2, tier.displayName());
                        upsert.setInt(3, tier.priority());
                        upsert.setString(4, tier.luckPermsGroup());
                        upsert.setInt(5, tier.durationDays());
                        upsert.setInt(6, tier.pricePoints());
                        upsert.setInt(7, tier.upgradeCreditRateBps());
                        upsert.setString(8, tier.displayMaterial());
                        upsert.setString(9, json(tier.benefits()));
                        upsert.setBoolean(10, tier.enabled());
                        upsert.executeUpdate();
                    }
                }
            }
            return null;
        });
    }

    CompletionStage<List<MembershipTier>> catalog() {
        return async(() -> {
            try (Connection connection = database.connection();
                 PreparedStatement query = connection.prepareStatement("""
                    SELECT tier_key, display_name, priority, luckperms_group, duration_days,
                           price_points, upgrade_credit_rate_bps, display_material,
                           benefits_json, enabled, config_version
                    FROM cct_membership_tiers
                    WHERE enabled = TRUE
                    ORDER BY priority
                    """)) {
                List<MembershipTier> tiers = new ArrayList<>();
                try (ResultSet result = query.executeQuery()) {
                    while (result.next()) {
                        tiers.add(mapTier(result));
                    }
                }
                return List.copyOf(tiers);
            }
        });
    }

    CompletionStage<Optional<MembershipOrderResult>> existingResult(
        UUID playerUuid,
        String idempotencyKey
    ) {
        return async(() -> {
            try (Connection connection = database.connection()) {
                return findByIdempotency(connection, playerUuid, idempotencyKey);
            }
        });
    }

    CompletionStage<MembershipSummary> reconcileAndSummary(UUID playerUuid, Instant now) {
        return async(() -> {
            try (Connection connection = database.connection()) {
                return inTransaction(connection, () -> {
                    lockState(connection, playerUuid);
                    reconcileLocked(connection, playerUuid, now);
                    return readSummary(connection, playerUuid, now);
                });
            }
        });
    }

    CompletionStage<MembershipSummary> admin(AdminMembershipRequest request, Instant now) {
        return async(() -> {
            try (Connection connection = database.connection()) {
                return inTransaction(connection, () -> {
                    lockState(connection, request.playerUuid());
                    reconcileLocked(connection, request.playerUuid(), now);
                    MembershipEntitlement active = readActive(connection, request.playerUuid()).orElse(null);
                    MembershipEntitlement before = request.tierKey() == null
                        ? active
                        : readEntitlementByTier(
                            connection, request.playerUuid(), request.tierKey()
                        ).orElse(null);
                    switch (request.action()) {
                        case GRANT -> adminGrant(connection, request, active, now);
                        case EXTEND -> adminExtend(connection, request, before, now);
                        case RECLAIM, REMOVE -> adminReclaim(connection, request, before, now);
                        case PAUSE -> adminPause(connection, request, active, now);
                        case RESUME -> adminResume(connection, request, active, now);
                        case SET_EXPIRY -> adminSetExpiry(connection, request, active, now);
                    }
                    MembershipSummary summary = readSummary(connection, request.playerUuid(), now);
                    MembershipEntitlement after = request.tierKey() == null
                        ? summary.active()
                        : readEntitlementByTier(
                            connection, request.playerUuid(), request.tierKey()
                        ).orElse(null);
                    writeAdminEvent(connection, request, before, after, now);
                    return summary;
                });
            }
        });
    }

    CompletionStage<PreparedMembershipOrder> prepare(
        MembershipPurchaseRequest request,
        MembershipQuote quote,
        UUID orderId,
        UUID operationId,
        Instant now
    ) {
        return async(() -> {
            try (Connection connection = database.connection()) {
                Optional<MembershipOrderResult> existing = findByIdempotency(
                    connection, request.playerUuid(), request.idempotencyKey()
                );
                if (existing.isPresent()) {
                    return PreparedMembershipOrder.existing(existing.orElseThrow());
                }
                try {
                    return inTransaction(connection, () -> prepareLocked(
                        connection, request, quote, orderId, operationId, now
                    ));
                } catch (SQLException exception) {
                    if ("23000".equals(exception.getSQLState())) {
                        return findByIdempotency(connection, request.playerUuid(), request.idempotencyKey())
                            .map(PreparedMembershipOrder::existing)
                            .orElseThrow(() -> exception);
                    }
                    throw exception;
                }
            }
        });
    }

    CompletionStage<Void> pointsDebitRequested(UUID orderId) {
        return transition(orderId, MembershipOrderStatus.RESERVED,
            MembershipOrderStatus.POINTS_DEBIT_REQUESTED, null);
    }

    CompletionStage<PointsMutationResult> recordZeroPointDebit(
        UUID operationId,
        UUID playerUuid,
        UUID orderId
    ) {
        return async(() -> {
            try (Connection connection = database.connection();
                 PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO cct_point_operations(
                        operation_id, player_uuid, delta_points, status, source_type, source_ref
                    ) VALUES (?, ?, 0, 'COMPLETED', 'MEMBERSHIP_PURCHASE', ?)
                    ON DUPLICATE KEY UPDATE operation_id = operation_id
                    """)) {
                insert.setBytes(1, UuidBinary.encode(operationId));
                insert.setBytes(2, UuidBinary.encode(playerUuid));
                insert.setString(3, orderId.toString());
                insert.executeUpdate();
            }
            return new PointsMutationResult(
                operationId,
                cn.cctstudio.cctsystem.points.PointsMutationDisposition.COMPLETED,
                null,
                null,
                null
            );
        });
    }

    CompletionStage<Void> pointsDebited(UUID orderId, PointsMutationResult mutation) {
        return async(() -> {
            try (Connection connection = database.connection()) {
                return inTransaction(connection, () -> {
                    transition(connection, orderId, MembershipOrderStatus.POINTS_DEBIT_REQUESTED,
                        MembershipOrderStatus.POINTS_DEBITED, null);
                    try (PreparedStatement update = connection.prepareStatement("""
                        UPDATE cct_membership_orders
                        SET points_balance_before = ?, points_balance_after = ?
                        WHERE order_id = ?
                        """)) {
                        nullableInt(update, 1, mutation.balanceBefore());
                        nullableInt(update, 2, mutation.balanceAfter());
                        update.setBytes(3, UuidBinary.encode(orderId));
                        requireOne(update.executeUpdate(), "Membership order balance snapshot was not updated");
                    }
                    return null;
                });
            }
        });
    }

    CompletionStage<MembershipOrderResult> apply(UUID orderId, Instant now) {
        return async(() -> {
            try (Connection connection = database.connection()) {
                return inTransaction(connection, () -> applyLocked(connection, orderId, now));
            }
        });
    }

    CompletionStage<Void> failDebit(UUID orderId, String errorCode) {
        return terminal(orderId, MembershipOrderStatus.POINTS_DEBIT_REQUESTED,
            MembershipOrderStatus.FAILED, errorCode, true);
    }

    CompletionStage<Void> reviewRequired(
        UUID orderId,
        MembershipOrderStatus expected,
        String errorCode
    ) {
        return terminal(orderId, expected, MembershipOrderStatus.REVIEW_REQUIRED, errorCode, false);
    }

    CompletionStage<Void> beginCompensation(UUID orderId, String errorCode) {
        return transition(orderId, MembershipOrderStatus.POINTS_DEBITED,
            MembershipOrderStatus.COMPENSATING, errorCode);
    }

    CompletionStage<Void> compensated(UUID orderId) {
        return terminal(orderId, MembershipOrderStatus.COMPENSATING,
            MembershipOrderStatus.COMPENSATED, "MEMBERSHIP_APPLY_FAILED", true);
    }

    CompletionStage<Void> compensationPending(UUID orderId, String errorCode) {
        return terminal(orderId, MembershipOrderStatus.COMPENSATING,
            MembershipOrderStatus.COMPENSATION_PENDING, errorCode, false);
    }

    CompletionStage<MembershipOrderResult> result(UUID orderId) {
        return async(() -> {
            try (Connection connection = database.connection()) {
                return findOrder(connection, orderId)
                    .orElseThrow(() -> new SQLException("Membership order does not exist"));
            }
        });
    }

    CompletionStage<Void> recoverInterruptedOrders() {
        return async(() -> {
            try (Connection connection = database.connection()) {
                return inTransaction(connection, () -> {
                    List<OrderRecovery> orders = new ArrayList<>();
                    try (PreparedStatement query = connection.prepareStatement("""
                        SELECT order_id, status
                        FROM cct_membership_orders
                        WHERE node_id = ? AND status IN (
                            'CREATED', 'RESERVED', 'POINTS_DEBIT_REQUESTED',
                            'POINTS_DEBITED', 'APPLYING', 'COMPENSATING'
                        )
                        FOR UPDATE
                        """)) {
                        query.setString(1, nodeId);
                        try (ResultSet result = query.executeQuery()) {
                            while (result.next()) {
                                orders.add(new OrderRecovery(
                                    UuidBinary.decode(result.getBytes(1)),
                                    MembershipOrderStatus.valueOf(result.getString(2))
                                ));
                            }
                        }
                    }
                    for (OrderRecovery order : orders) {
                        if (order.status() == MembershipOrderStatus.CREATED
                            || order.status() == MembershipOrderStatus.RESERVED) {
                            transition(connection, order.orderId(), order.status(),
                                MembershipOrderStatus.FAILED, "NODE_RESTARTED_BEFORE_POINTS_CALL");
                            finishOperation(connection, order.orderId(), "COMPLETED",
                                MembershipOrderStatus.FAILED, "NODE_RESTARTED_BEFORE_POINTS_CALL");
                            deleteFence(connection, order.orderId());
                        } else {
                            transition(connection, order.orderId(), order.status(),
                                MembershipOrderStatus.REVIEW_REQUIRED,
                                "NODE_RESTARTED_WITH_AMBIGUOUS_OPERATION");
                            finishOperation(connection, order.orderId(), "REVIEW_REQUIRED",
                                MembershipOrderStatus.REVIEW_REQUIRED,
                                "NODE_RESTARTED_WITH_AMBIGUOUS_OPERATION");
                            updateFence(connection, order.orderId(), "REVIEW_REQUIRED");
                        }
                    }
                    return null;
                });
            }
        });
    }

    CompletionStage<Void> reconcileExpired(Instant now, int limit) {
        return async(() -> {
            List<UUID> players = new ArrayList<>();
            try (Connection connection = database.connection();
                 PreparedStatement query = connection.prepareStatement("""
                    SELECT s.player_uuid
                    FROM cct_player_membership_state s
                    JOIN cct_membership_entitlements e
                      ON e.entitlement_id = s.active_entitlement_id
                    WHERE e.state = 'ACTIVE' AND e.expires_at <= ?
                    ORDER BY e.expires_at
                    LIMIT ?
                    """)) {
                setInstant(query, 1, now);
                query.setInt(2, limit);
                try (ResultSet result = query.executeQuery()) {
                    while (result.next()) {
                        players.add(UuidBinary.decode(result.getBytes(1)));
                    }
                }
            }
            for (UUID playerUuid : players) {
                try (Connection connection = database.connection()) {
                    inTransaction(connection, () -> {
                        lockState(connection, playerUuid);
                        reconcileLocked(connection, playerUuid, now);
                        return null;
                    });
                }
            }
            return null;
        });
    }

    CompletionStage<List<MembershipProjection>> pendingProjections(Instant now, int limit) {
        return async(() -> {
            try (Connection connection = database.connection();
                 PreparedStatement query = connection.prepareStatement("""
                    SELECT player_uuid, desired_tier_key, desired_group,
                           desired_expires_at, desired_version
                    FROM cct_luckperms_projections
                    WHERE status IN ('PENDING', 'FAILED') AND next_attempt_at <= ?
                    ORDER BY next_attempt_at
                    LIMIT ?
                    """)) {
                setInstant(query, 1, now);
                query.setInt(2, limit);
                List<MembershipProjection> projections = new ArrayList<>();
                try (ResultSet result = query.executeQuery()) {
                    while (result.next()) {
                        projections.add(new MembershipProjection(
                            UuidBinary.decode(result.getBytes("player_uuid")),
                            result.getString("desired_tier_key"),
                            result.getString("desired_group"),
                            nullableInstant(result, "desired_expires_at"),
                            result.getLong("desired_version")
                        ));
                    }
                }
                return List.copyOf(projections);
            }
        });
    }

    CompletionStage<Void> projectionApplied(MembershipProjection projection) {
        return async(() -> {
            try (Connection connection = database.connection();
                 PreparedStatement update = connection.prepareStatement("""
                    UPDATE cct_luckperms_projections
                    SET applied_version = ?, status = 'APPLIED', attempts = 0,
                        last_error_code = NULL, next_attempt_at = UTC_TIMESTAMP(3)
                    WHERE player_uuid = ? AND desired_version = ?
                    """)) {
                update.setLong(1, projection.desiredVersion());
                update.setBytes(2, UuidBinary.encode(projection.playerUuid()));
                update.setLong(3, projection.desiredVersion());
                update.executeUpdate();
            }
            return null;
        });
    }

    CompletionStage<Void> projectionFailed(MembershipProjection projection, Instant retryAt) {
        return async(() -> {
            try (Connection connection = database.connection();
                 PreparedStatement update = connection.prepareStatement("""
                    UPDATE cct_luckperms_projections
                    SET status = 'FAILED', attempts = attempts + 1,
                        last_error_code = 'LUCKPERMS_SYNC_FAILED', next_attempt_at = ?
                    WHERE player_uuid = ? AND desired_version = ?
                    """)) {
                setInstant(update, 1, retryAt);
                update.setBytes(2, UuidBinary.encode(projection.playerUuid()));
                update.setLong(3, projection.desiredVersion());
                update.executeUpdate();
            }
            return null;
        });
    }

    private PreparedMembershipOrder prepareLocked(
        Connection connection,
        MembershipPurchaseRequest request,
        MembershipQuote quote,
        UUID orderId,
        UUID operationId,
        Instant now
    ) throws SQLException {
        insertOperation(connection, request, operationId);
        insertOrder(connection, request, quote, orderId, operationId);
        if (!insertFence(connection, request.playerUuid(), operationId)) {
            failInitial(connection, orderId, operationId, "OPERATION_IN_PROGRESS");
            return PreparedMembershipOrder.existing(findOrder(connection, orderId).orElseThrow());
        }
        lockState(connection, request.playerUuid());
        reconcileLocked(connection, request.playerUuid(), now);
        MembershipSummary current = readSummary(connection, request.playerUuid(), now);
        if (!sameEntitlement(current.active(), quote.currentEntitlement())) {
            failInitial(connection, orderId, operationId, "MEMBERSHIP_QUOTE_STALE");
            return PreparedMembershipOrder.existing(findOrder(connection, orderId).orElseThrow());
        }
        int highest = current.paused().stream()
            .mapToInt(entitlement -> entitlement.tier().priority())
            .max()
            .orElse(current.active() == null ? 0 : current.active().tier().priority());
        if (current.active() != null) {
            highest = Math.max(highest, current.active().tier().priority());
        }
        if (highest > quote.targetTier().priority()) {
            failInitial(connection, orderId, operationId, "MEMBERSHIP_DOWNGRADE_FORBIDDEN");
            return PreparedMembershipOrder.existing(findOrder(connection, orderId).orElseThrow());
        }
        if (!promotionStillValid(connection, quote, now)) {
            failInitial(connection, orderId, operationId, "MEMBERSHIP_QUOTE_STALE");
            return PreparedMembershipOrder.existing(findOrder(connection, orderId).orElseThrow());
        }
        transition(connection, orderId, MembershipOrderStatus.CREATED,
            MembershipOrderStatus.RESERVED, null);
        updateFence(connection, orderId, "RESERVED");
        return PreparedMembershipOrder.proceed(orderId, operationId, request.playerUuid(), quote);
    }

    private MembershipOrderResult applyLocked(Connection connection, UUID orderId, Instant now)
        throws SQLException {
        OrderRow order = orderForUpdate(connection, orderId);
        if (order.status() != MembershipOrderStatus.POINTS_DEBITED) {
            return findOrder(connection, orderId).orElseThrow();
        }
        transition(connection, orderId, MembershipOrderStatus.POINTS_DEBITED,
            MembershipOrderStatus.APPLYING, null);
        lockState(connection, order.playerUuid());
        MembershipEntitlement active = readActive(connection, order.playerUuid()).orElse(null);
        if (!Objects.equals(order.previousEntitlementId(), id(active))) {
            throw new SQLException("Membership state changed after points debit");
        }

        MembershipTier target = findTier(connection, order.tierKey(), true);
        long durationSeconds = Math.multiplyExact(
            (long) order.durationDaysSnapshot() * 86_400L,
            order.months()
        );
        UUID entitlementId;
        Instant expiry;
        if (active != null && active.tier().key().equals(order.tierKey())) {
            entitlementId = active.entitlementId();
            Instant base = active.expiresAt() != null && active.expiresAt().isAfter(now)
                ? active.expiresAt()
                : now;
            expiry = base.plusSeconds(durationSeconds);
            try (PreparedStatement update = connection.prepareStatement("""
                UPDATE cct_membership_entitlements
                SET state = 'ACTIVE', expires_at = ?, remaining_seconds = NULL,
                    resume_sequence = NULL
                WHERE entitlement_id = ?
                """)) {
                setInstant(update, 1, expiry);
                update.setBytes(2, UuidBinary.encode(entitlementId));
                requireOne(update.executeUpdate(), "Membership renewal could not be applied");
            }
        } else {
            if (active != null) {
                if (order.upgradeMode() == UpgradeMode.PAUSE) {
                    pauseActive(connection, order.playerUuid(), active, now);
                } else if (order.upgradeMode() == UpgradeMode.CREDIT) {
                    convertActive(connection, active, order, now);
                } else {
                    throw new SQLException("Upgrade mode was not persisted for membership upgrade");
                }
            }
            entitlementId = UuidV7.create(now);
            expiry = now.plusSeconds(durationSeconds);
            try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO cct_membership_entitlements(
                    entitlement_id, player_uuid, tier_key, state, starts_at, expires_at,
                    source_type, source_ref, created_by
                ) VALUES (?, ?, ?, 'ACTIVE', ?, ?, 'PURCHASE', ?, ?)
                """)) {
                insert.setBytes(1, UuidBinary.encode(entitlementId));
                insert.setBytes(2, UuidBinary.encode(order.playerUuid()));
                insert.setString(3, order.tierKey());
                setInstant(insert, 4, now);
                setInstant(insert, 5, expiry);
                insert.setString(6, orderId.toString());
                insert.setString(7, "player:" + order.playerUuid());
                insert.executeUpdate();
            }
        }

        long stateVersion = setActive(connection, order.playerUuid(), entitlementId);
        upsertProjection(connection, order.playerUuid(), target, expiry, stateVersion, now);
        writeEvent(connection, order, entitlementId, active, target, expiry);
        try (PreparedStatement update = connection.prepareStatement("""
            UPDATE cct_membership_orders
            SET status = 'COMPLETED', resulting_entitlement_id = ?,
                response_json = JSON_OBJECT(
                    'status', 'COMPLETED', 'tierKey', tier_key,
                    'finalPricePoints', final_price_points,
                    'permissionSyncStatus', 'PENDING'
                )
            WHERE order_id = ? AND status = 'APPLYING'
            """)) {
            update.setBytes(1, UuidBinary.encode(entitlementId));
            update.setBytes(2, UuidBinary.encode(orderId));
            requireOne(update.executeUpdate(), "Membership order could not be completed");
        }
        finishOperation(connection, orderId, "COMPLETED", MembershipOrderStatus.COMPLETED, null);
        deleteFence(connection, orderId);
        return new MembershipOrderResult(
            orderId, MembershipOrderStatus.COMPLETED, order.tierKey(), order.months(),
            order.finalPricePoints(), entitlementId, expiry, "PENDING", null
        );
    }

    private void adminGrant(
        Connection connection,
        AdminMembershipRequest request,
        MembershipEntitlement active,
        Instant now
    ) throws SQLException {
        MembershipTier tier = findTier(connection, request.tierKey(), true);
        long seconds = Math.multiplyExact((long) request.days(), 86_400L);
        if (active != null && active.tier().key().equals(tier.key())) {
            Instant base = active.expiresAt().isAfter(now) ? active.expiresAt() : now;
            updateEntitlementExpiry(connection, active.entitlementId(), base.plusSeconds(seconds));
            long version = incrementStateVersion(connection, request.playerUuid());
            upsertProjection(connection, request.playerUuid(), tier, base.plusSeconds(seconds), version, now);
            return;
        }
        if (active != null) {
            pauseActive(connection, request.playerUuid(), active, now);
        }
        UUID entitlementId = UuidV7.create(now);
        Instant expiry = now.plusSeconds(seconds);
        try (PreparedStatement insert = connection.prepareStatement("""
            INSERT INTO cct_membership_entitlements(
                entitlement_id, player_uuid, tier_key, state, starts_at, expires_at,
                source_type, source_ref, created_by
            ) VALUES (?, ?, ?, 'ACTIVE', ?, ?, 'ADMIN', ?, ?)
            """)) {
            insert.setBytes(1, UuidBinary.encode(entitlementId));
            insert.setBytes(2, UuidBinary.encode(request.playerUuid()));
            insert.setString(3, tier.key());
            setInstant(insert, 4, now);
            setInstant(insert, 5, expiry);
            insert.setString(6, request.actor());
            insert.setString(7, request.actor());
            insert.executeUpdate();
        }
        long version = setActive(connection, request.playerUuid(), entitlementId);
        upsertProjection(connection, request.playerUuid(), tier, expiry, version, now);
    }

    private void adminExtend(
        Connection connection,
        AdminMembershipRequest request,
        MembershipEntitlement entitlement,
        Instant now
    ) throws SQLException {
        if (entitlement == null) {
            throw new MembershipException(
                "MEMBERSHIP_ENTITLEMENT_NOT_FOUND", "The selected rank has no remaining time", false
            );
        }
        if (request.days() < 1) {
            throw new MembershipException("MEMBERSHIP_ADMIN_REQUEST_INVALID", "Extension days are required", false);
        }
        long seconds = Math.multiplyExact((long) request.days(), 86_400L);
        if (entitlement.state() == EntitlementState.PAUSED) {
            updatePausedRemaining(
                connection, entitlement.entitlementId(), entitlement.remainingSeconds() + seconds
            );
            return;
        }
        Instant base = entitlement.expiresAt().isAfter(now) ? entitlement.expiresAt() : now;
        Instant expiry = base.plusSeconds(seconds);
        updateEntitlementExpiry(connection, entitlement.entitlementId(), expiry);
        long version = incrementStateVersion(connection, request.playerUuid());
        upsertProjection(connection, request.playerUuid(), entitlement.tier(), expiry, version, now);
    }

    private void adminReclaim(
        Connection connection,
        AdminMembershipRequest request,
        MembershipEntitlement entitlement,
        Instant now
    ) throws SQLException {
        if (entitlement == null) {
            throw new MembershipException(
                "MEMBERSHIP_ENTITLEMENT_NOT_FOUND", "The selected rank has no remaining time", false
            );
        }
        setEntitlementState(connection, entitlement.entitlementId(), "CANCELLED");
        if (entitlement.state() == EntitlementState.ACTIVE) {
            resumeNextOrClear(connection, request.playerUuid(), now);
        }
    }

    private void adminPause(
        Connection connection,
        AdminMembershipRequest request,
        MembershipEntitlement active,
        Instant now
    ) throws SQLException {
        requireActive(active);
        pauseActive(connection, request.playerUuid(), active, now);
        long version = setActive(connection, request.playerUuid(), null);
        upsertProjection(connection, request.playerUuid(), null, null, version, now);
    }

    private void adminResume(
        Connection connection,
        AdminMembershipRequest request,
        MembershipEntitlement active,
        Instant now
    ) throws SQLException {
        if (active != null) {
            throw new MembershipException("MEMBERSHIP_ACTIVE_EXISTS", "An active membership already exists", false);
        }
        MembershipEntitlement paused = readNextPaused(connection, request.playerUuid()).orElseThrow(() ->
            new MembershipException("MEMBERSHIP_PAUSED_NOT_FOUND", "No paused membership exists", false)
        );
        resumeEntitlement(connection, request.playerUuid(), paused, now);
    }

    private void adminSetExpiry(
        Connection connection,
        AdminMembershipRequest request,
        MembershipEntitlement active,
        Instant now
    ) throws SQLException {
        requireActive(active);
        if (!request.expiresAt().isAfter(now)) {
            throw new MembershipException("MEMBERSHIP_EXPIRY_INVALID", "Expiry must be in the future", false);
        }
        updateEntitlementExpiry(connection, active.entitlementId(), request.expiresAt());
        long version = incrementStateVersion(connection, request.playerUuid());
        upsertProjection(connection, request.playerUuid(), active.tier(), request.expiresAt(), version, now);
    }

    private void resumeNextOrClear(Connection connection, UUID playerUuid, Instant now) throws SQLException {
        MembershipEntitlement paused = readNextPaused(connection, playerUuid).orElse(null);
        if (paused == null) {
            long version = setActive(connection, playerUuid, null);
            upsertProjection(connection, playerUuid, null, null, version, now);
        } else {
            resumeEntitlement(connection, playerUuid, paused, now);
        }
    }

    private void resumeEntitlement(
        Connection connection,
        UUID playerUuid,
        MembershipEntitlement paused,
        Instant now
    ) throws SQLException {
        Instant expiry = now.plusSeconds(Math.max(0, paused.remainingSeconds()));
        try (PreparedStatement resume = connection.prepareStatement("""
            UPDATE cct_membership_entitlements
            SET state = 'ACTIVE', starts_at = ?, expires_at = ?,
                remaining_seconds = NULL, resume_sequence = NULL
            WHERE entitlement_id = ? AND state = 'PAUSED'
            """)) {
            setInstant(resume, 1, now);
            setInstant(resume, 2, expiry);
            resume.setBytes(3, UuidBinary.encode(paused.entitlementId()));
            requireOne(resume.executeUpdate(), "Paused membership could not be resumed");
        }
        long version = setActive(connection, playerUuid, paused.entitlementId());
        upsertProjection(connection, playerUuid, paused.tier(), expiry, version, now);
    }

    private static void updateEntitlementExpiry(
        Connection connection,
        UUID entitlementId,
        Instant expiry
    ) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("""
            UPDATE cct_membership_entitlements SET expires_at = ?
            WHERE entitlement_id = ? AND state = 'ACTIVE'
            """)) {
            setInstant(update, 1, expiry);
            update.setBytes(2, UuidBinary.encode(entitlementId));
            requireOne(update.executeUpdate(), "Membership expiry could not be updated");
        }
    }

    private static void updatePausedRemaining(
        Connection connection,
        UUID entitlementId,
        long remainingSeconds
    ) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("""
            UPDATE cct_membership_entitlements SET remaining_seconds = ?
            WHERE entitlement_id = ? AND state = 'PAUSED'
            """)) {
            update.setLong(1, remainingSeconds);
            update.setBytes(2, UuidBinary.encode(entitlementId));
            requireOne(update.executeUpdate(), "Paused membership could not be extended");
        }
    }

    private static void setEntitlementState(Connection connection, UUID entitlementId, String state)
        throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("""
            UPDATE cct_membership_entitlements
            SET state = ?, expires_at = NULL, remaining_seconds = NULL, resume_sequence = NULL
            WHERE entitlement_id = ? AND state IN ('ACTIVE', 'PAUSED')
            """)) {
            update.setString(1, state);
            update.setBytes(2, UuidBinary.encode(entitlementId));
            requireOne(update.executeUpdate(), "Membership state could not be changed");
        }
    }

    private long incrementStateVersion(Connection connection, UUID playerUuid) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("""
            UPDATE cct_player_membership_state SET version = version + 1 WHERE player_uuid = ?
            """)) {
            update.setBytes(1, UuidBinary.encode(playerUuid));
            requireOne(update.executeUpdate(), "Membership version could not be incremented");
        }
        try (PreparedStatement query = connection.prepareStatement(
            "SELECT version FROM cct_player_membership_state WHERE player_uuid = ?"
        )) {
            query.setBytes(1, UuidBinary.encode(playerUuid));
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) throw new SQLException("Membership version is unavailable");
                return result.getLong(1);
            }
        }
    }

    private void writeAdminEvent(
        Connection connection,
        AdminMembershipRequest request,
        MembershipEntitlement before,
        MembershipEntitlement after,
        Instant now
    ) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
            INSERT INTO cct_membership_events(
                event_id, player_uuid, entitlement_id, event_type,
                actor_type, actor_id, reason, before_json, after_json
            ) VALUES (?, ?, ?, ?, 'ADMIN', ?, ?, ?, ?)
            """)) {
            insert.setBytes(1, UuidBinary.encode(UuidV7.create(now)));
            insert.setBytes(2, UuidBinary.encode(request.playerUuid()));
            nullableUuid(insert, 3, after == null ? id(before) : id(after));
            insert.setString(4, "ADMIN_" + request.action().name());
            insert.setString(5, request.actor());
            insert.setString(6, request.reason());
            insert.setString(7, entitlementSnapshot(before));
            insert.setString(8, entitlementSnapshot(after));
            insert.executeUpdate();
        }
    }

    private static String entitlementSnapshot(MembershipEntitlement entitlement) throws SQLException {
        if (entitlement == null) {
            return "{}";
        }
        return json(java.util.Map.of(
            "tierKey", entitlement.tier().key(),
            "state", entitlement.state().name(),
            "expiresAt", String.valueOf(entitlement.expiresAt())
        ));
    }

    private static void requireActive(MembershipEntitlement active) {
        if (active == null) {
            throw new MembershipException("MEMBERSHIP_ACTIVE_NOT_FOUND", "No active membership exists", false);
        }
    }

    private void reconcileLocked(Connection connection, UUID playerUuid, Instant now) throws SQLException {
        MembershipEntitlement active = readActive(connection, playerUuid).orElse(null);
        if (active == null || active.expiresAt() == null || active.expiresAt().isAfter(now)) {
            return;
        }
        try (PreparedStatement expire = connection.prepareStatement("""
            UPDATE cct_membership_entitlements SET state = 'EXPIRED'
            WHERE entitlement_id = ? AND state = 'ACTIVE'
            """)) {
            expire.setBytes(1, UuidBinary.encode(active.entitlementId()));
            expire.executeUpdate();
        }
        MembershipEntitlement paused = readNextPaused(connection, playerUuid).orElse(null);
        if (paused == null) {
            long version = setActive(connection, playerUuid, null);
            upsertProjection(connection, playerUuid, null, null, version, now);
            return;
        }
        Instant expiry = now.plusSeconds(Math.max(0, paused.remainingSeconds()));
        try (PreparedStatement resume = connection.prepareStatement("""
            UPDATE cct_membership_entitlements
            SET state = 'ACTIVE', starts_at = ?, expires_at = ?,
                remaining_seconds = NULL, resume_sequence = NULL
            WHERE entitlement_id = ? AND state = 'PAUSED'
            """)) {
            setInstant(resume, 1, now);
            setInstant(resume, 2, expiry);
            resume.setBytes(3, UuidBinary.encode(paused.entitlementId()));
            requireOne(resume.executeUpdate(), "Paused membership could not be resumed");
        }
        long version = setActive(connection, playerUuid, paused.entitlementId());
        upsertProjection(connection, playerUuid, paused.tier(), expiry, version, now);
    }

    private MembershipSummary readSummary(Connection connection, UUID playerUuid, Instant now)
        throws SQLException {
        MembershipEntitlement active = readActive(connection, playerUuid).orElse(null);
        List<MembershipEntitlement> paused = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement("""
            SELECT %s
            FROM cct_membership_entitlements e
            JOIN cct_membership_tiers t ON t.tier_key = e.tier_key
            WHERE e.player_uuid = ? AND e.state = 'PAUSED'
            ORDER BY t.priority DESC, e.resume_sequence
            """.formatted(ENTITLEMENT_COLUMNS))) {
            query.setBytes(1, UuidBinary.encode(playerUuid));
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) {
                    paused.add(mapEntitlement(result));
                }
            }
        }
        String sync = "APPLIED";
        try (PreparedStatement query = connection.prepareStatement(
            "SELECT status FROM cct_luckperms_projections WHERE player_uuid = ?"
        )) {
            query.setBytes(1, UuidBinary.encode(playerUuid));
            try (ResultSet result = query.executeQuery()) {
                if (result.next()) {
                    sync = result.getString(1);
                }
            }
        }
        return new MembershipSummary(active, paused, sync, now);
    }

    private Optional<MembershipEntitlement> readActive(Connection connection, UUID playerUuid)
        throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
            SELECT %s
            FROM cct_membership_entitlements e
            JOIN cct_membership_tiers t ON t.tier_key = e.tier_key
            WHERE e.player_uuid = ? AND e.state = 'ACTIVE'
            LIMIT 1
            """.formatted(ENTITLEMENT_COLUMNS))) {
            query.setBytes(1, UuidBinary.encode(playerUuid));
            try (ResultSet result = query.executeQuery()) {
                return result.next() ? Optional.of(mapEntitlement(result)) : Optional.empty();
            }
        }
    }

    private Optional<MembershipEntitlement> readEntitlementByTier(
        Connection connection,
        UUID playerUuid,
        String tierKey
    ) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
            SELECT %s
            FROM cct_membership_entitlements e
            JOIN cct_membership_tiers t ON t.tier_key = e.tier_key
            WHERE e.player_uuid = ? AND e.tier_key = ?
                AND e.state IN ('ACTIVE', 'PAUSED')
            ORDER BY CASE e.state WHEN 'ACTIVE' THEN 0 ELSE 1 END, e.created_at DESC
            LIMIT 1 FOR UPDATE
            """.formatted(ENTITLEMENT_COLUMNS))) {
            query.setBytes(1, UuidBinary.encode(playerUuid));
            query.setString(2, tierKey);
            try (ResultSet result = query.executeQuery()) {
                return result.next() ? Optional.of(mapEntitlement(result)) : Optional.empty();
            }
        }
    }

    private Optional<MembershipEntitlement> readNextPaused(Connection connection, UUID playerUuid)
        throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
            SELECT %s
            FROM cct_membership_entitlements e
            JOIN cct_membership_tiers t ON t.tier_key = e.tier_key
            WHERE e.player_uuid = ? AND e.state = 'PAUSED'
            ORDER BY t.priority DESC, e.resume_sequence
            LIMIT 1 FOR UPDATE
            """.formatted(ENTITLEMENT_COLUMNS))) {
            query.setBytes(1, UuidBinary.encode(playerUuid));
            try (ResultSet result = query.executeQuery()) {
                return result.next() ? Optional.of(mapEntitlement(result)) : Optional.empty();
            }
        }
    }

    private MembershipTier findTier(Connection connection, String tierKey, boolean requireEnabled)
        throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
            SELECT tier_key, display_name, priority, luckperms_group, duration_days,
                   price_points, upgrade_credit_rate_bps, display_material,
                   benefits_json, enabled, config_version
            FROM cct_membership_tiers WHERE tier_key = ?
            """)) {
            query.setString(1, tierKey);
            try (ResultSet result = query.executeQuery()) {
                if (!result.next() || requireEnabled && !result.getBoolean("enabled")) {
                    throw new MembershipException(
                        "MEMBERSHIP_TIER_UNAVAILABLE", "Membership tier is unavailable", false
                    );
                }
                return mapTier(result);
            }
        }
    }

    private void lockState(Connection connection, UUID playerUuid) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
            INSERT IGNORE INTO cct_player_membership_state(player_uuid) VALUES (?)
            """)) {
            insert.setBytes(1, UuidBinary.encode(playerUuid));
            insert.executeUpdate();
        }
        try (PreparedStatement lock = connection.prepareStatement("""
            SELECT version FROM cct_player_membership_state WHERE player_uuid = ? FOR UPDATE
            """)) {
            lock.setBytes(1, UuidBinary.encode(playerUuid));
            try (ResultSet result = lock.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("Membership state row could not be locked");
                }
            }
        }
    }

    private long setActive(Connection connection, UUID playerUuid, UUID entitlementId) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("""
            UPDATE cct_player_membership_state
            SET active_entitlement_id = ?, version = version + 1
            WHERE player_uuid = ?
            """)) {
            nullableUuid(update, 1, entitlementId);
            update.setBytes(2, UuidBinary.encode(playerUuid));
            requireOne(update.executeUpdate(), "Membership state could not be updated");
        }
        try (PreparedStatement query = connection.prepareStatement(
            "SELECT version FROM cct_player_membership_state WHERE player_uuid = ?"
        )) {
            query.setBytes(1, UuidBinary.encode(playerUuid));
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("Membership state version is unavailable");
                }
                return result.getLong(1);
            }
        }
    }

    private void pauseActive(
        Connection connection,
        UUID playerUuid,
        MembershipEntitlement active,
        Instant now
    ) throws SQLException {
        long remaining = Math.max(0, active.expiresAt().getEpochSecond() - now.getEpochSecond());
        long sequence;
        try (PreparedStatement query = connection.prepareStatement("""
            SELECT next_resume_sequence FROM cct_player_membership_state
            WHERE player_uuid = ? FOR UPDATE
            """)) {
            query.setBytes(1, UuidBinary.encode(playerUuid));
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("Membership resume sequence is unavailable");
                }
                sequence = result.getLong(1);
            }
        }
        try (PreparedStatement update = connection.prepareStatement("""
            UPDATE cct_membership_entitlements
            SET state = 'PAUSED', expires_at = NULL, remaining_seconds = ?, resume_sequence = ?
            WHERE entitlement_id = ? AND state = 'ACTIVE'
            """)) {
            update.setLong(1, remaining);
            update.setLong(2, sequence);
            update.setBytes(3, UuidBinary.encode(active.entitlementId()));
            requireOne(update.executeUpdate(), "Active membership could not be paused");
        }
        try (PreparedStatement update = connection.prepareStatement("""
            UPDATE cct_player_membership_state SET next_resume_sequence = next_resume_sequence + 1
            WHERE player_uuid = ?
            """)) {
            update.setBytes(1, UuidBinary.encode(playerUuid));
            update.executeUpdate();
        }
    }

    private void convertActive(
        Connection connection,
        MembershipEntitlement active,
        OrderRow order,
        Instant now
    )
        throws SQLException {
        long remaining = Math.max(0, active.expiresAt().getEpochSecond() - now.getEpochSecond());
        try (PreparedStatement update = connection.prepareStatement("""
            UPDATE cct_membership_entitlements
            SET state = 'CONVERTED', expires_at = NULL, remaining_seconds = ?, resume_sequence = NULL
            WHERE entitlement_id = ? AND state = 'ACTIVE'
            """)) {
            update.setLong(1, remaining);
            update.setBytes(2, UuidBinary.encode(active.entitlementId()));
            requireOne(update.executeUpdate(), "Active membership could not be converted");
        }
        if (order.upgradeCreditPoints() > 0) {
            try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO cct_membership_upgrade_credits(
                    order_id, previous_entitlement_id, old_tier_key, old_price_points,
                    old_duration_seconds, remaining_seconds, credit_rate_bps,
                    raw_credit_points, applied_credit_points, rounding_mode
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'FLOOR')
                """)) {
                insert.setBytes(1, UuidBinary.encode(order.orderId()));
                insert.setBytes(2, UuidBinary.encode(active.entitlementId()));
                insert.setString(3, active.tier().key());
                insert.setInt(4, active.tier().pricePoints());
                insert.setLong(5, active.tier().durationSeconds());
                insert.setLong(6, remaining);
                insert.setInt(7, active.tier().upgradeCreditRateBps());
                insert.setBigDecimal(8, java.math.BigDecimal.valueOf(order.upgradeCreditPoints()));
                insert.setInt(9, order.upgradeCreditPoints());
                insert.executeUpdate();
            }
        }
    }

    private void upsertProjection(
        Connection connection,
        UUID playerUuid,
        MembershipTier tier,
        Instant expiry,
        long version,
        Instant now
    ) throws SQLException {
        try (PreparedStatement upsert = connection.prepareStatement("""
            INSERT INTO cct_luckperms_projections(
                player_uuid, desired_tier_key, desired_group, desired_expires_at,
                desired_version, status, next_attempt_at
            ) VALUES (?, ?, ?, ?, ?, 'PENDING', ?)
            ON DUPLICATE KEY UPDATE
                desired_tier_key = VALUES(desired_tier_key),
                desired_group = VALUES(desired_group),
                desired_expires_at = VALUES(desired_expires_at),
                desired_version = VALUES(desired_version),
                status = 'PENDING', attempts = 0,
                last_error_code = NULL, next_attempt_at = VALUES(next_attempt_at)
            """)) {
            upsert.setBytes(1, UuidBinary.encode(playerUuid));
            upsert.setString(2, tier == null ? null : tier.key());
            upsert.setString(3, tier == null ? null : tier.luckPermsGroup());
            nullableInstant(upsert, 4, expiry);
            upsert.setLong(5, version);
            setInstant(upsert, 6, now);
            upsert.executeUpdate();
        }
    }

    private void writeEvent(
        Connection connection,
        OrderRow order,
        UUID entitlementId,
        MembershipEntitlement old,
        MembershipTier target,
        Instant expiry
    ) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
            INSERT INTO cct_membership_events(
                event_id, player_uuid, entitlement_id, order_id, event_type,
                actor_type, actor_id, reason, before_json, after_json
            ) VALUES (?, ?, ?, ?, ?, 'PLAYER', ?, 'purchase', ?, ?)
            """)) {
            insert.setBytes(1, UuidBinary.encode(UuidV7.create(expiry)));
            insert.setBytes(2, UuidBinary.encode(order.playerUuid()));
            insert.setBytes(3, UuidBinary.encode(entitlementId));
            insert.setBytes(4, UuidBinary.encode(order.orderId()));
            insert.setString(5, old == null ? "PURCHASED" :
                old.tier().key().equals(target.key()) ? "EXTENDED" : "UPGRADED");
            insert.setString(6, order.playerUuid().toString());
            insert.setString(7, json(old == null ? java.util.Map.of() : java.util.Map.of(
                "tierKey", old.tier().key(), "expiresAt", String.valueOf(old.expiresAt())
            )));
            insert.setString(8, json(java.util.Map.of(
                "tierKey", target.key(), "expiresAt", expiry.toString()
            )));
            insert.executeUpdate();
        }
    }

    private void insertOperation(
        Connection connection,
        MembershipPurchaseRequest request,
        UUID operationId
    ) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
            INSERT INTO cct_operation_requests(
                operation_id, player_uuid, operation_type, idempotency_key, status
            ) VALUES (?, ?, 'MEMBERSHIP_PURCHASE', ?, 'CREATED')
            """)) {
            insert.setBytes(1, UuidBinary.encode(operationId));
            insert.setBytes(2, UuidBinary.encode(request.playerUuid()));
            insert.setString(3, request.idempotencyKey());
            insert.executeUpdate();
        }
    }

    private void insertOrder(
        Connection connection,
        MembershipPurchaseRequest request,
        MembershipQuote quote,
        UUID orderId,
        UUID operationId
    ) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
            INSERT INTO cct_membership_orders(
                order_id, operation_id, player_uuid, node_id, tier_key, months,
                upgrade_mode, status, idempotency_key, tier_version,
                duration_days_snapshot, base_price_points, promotion_id,
                promotion_off_bps, discounted_price_points, upgrade_credit_points,
                final_price_points, previous_entitlement_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?, 'CREATED', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """)) {
            insert.setBytes(1, UuidBinary.encode(orderId));
            insert.setBytes(2, UuidBinary.encode(operationId));
            insert.setBytes(3, UuidBinary.encode(request.playerUuid()));
            insert.setString(4, nodeId);
            insert.setString(5, quote.targetTier().key());
            insert.setInt(6, quote.months());
            insert.setString(7, quote.upgradeMode().name());
            insert.setString(8, request.idempotencyKey());
            insert.setLong(9, quote.targetTier().version());
            insert.setInt(10, quote.targetTier().durationDays());
            insert.setInt(11, quote.basePricePoints());
            nullableUuid(insert, 12, quote.promotionId());
            insert.setInt(13, quote.promotionOffBps());
            insert.setInt(14, quote.discountedPricePoints());
            insert.setInt(15, quote.upgradeCreditPoints());
            insert.setInt(16, quote.finalPricePoints());
            nullableUuid(insert, 17, id(quote.currentEntitlement()));
            insert.executeUpdate();
        }
    }

    private boolean insertFence(Connection connection, UUID playerUuid, UUID operationId)
        throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
            INSERT IGNORE INTO cct_player_operation_fences(
                player_uuid, operation_id, operation_type, state, acquired_at, updated_at
            ) VALUES (?, ?, 'MEMBERSHIP_PURCHASE', 'CREATED', UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
            """)) {
            insert.setBytes(1, UuidBinary.encode(playerUuid));
            insert.setBytes(2, UuidBinary.encode(operationId));
            return insert.executeUpdate() == 1;
        }
    }

    private void failInitial(Connection connection, UUID orderId, UUID operationId, String errorCode)
        throws SQLException {
        transition(connection, orderId, MembershipOrderStatus.CREATED,
            MembershipOrderStatus.FAILED, errorCode);
        finishOperation(connection, orderId, "COMPLETED", MembershipOrderStatus.FAILED, errorCode);
        deleteFenceByOperation(connection, operationId);
    }

    private boolean promotionStillValid(Connection connection, MembershipQuote quote, Instant now)
        throws SQLException {
        if (quote.promotionId() == null) {
            return quote.promotionOffBps() == 0;
        }
        try (PreparedStatement query = connection.prepareStatement("""
            SELECT percent_off_bps
            FROM cct_promotions
            WHERE promotion_id = ? AND status = 'ACTIVE'
              AND starts_at <= ? AND ends_at > ?
            FOR UPDATE
            """)) {
            query.setBytes(1, UuidBinary.encode(quote.promotionId()));
            setInstant(query, 2, now);
            setInstant(query, 3, now);
            try (ResultSet result = query.executeQuery()) {
                return result.next() && result.getInt(1) == quote.promotionOffBps();
            }
        }
    }

    private CompletionStage<Void> transition(
        UUID orderId,
        MembershipOrderStatus expected,
        MembershipOrderStatus next,
        String errorCode
    ) {
        return async(() -> {
            try (Connection connection = database.connection()) {
                transition(connection, orderId, expected, next, errorCode);
            }
            return null;
        });
    }

    private CompletionStage<Void> terminal(
        UUID orderId,
        MembershipOrderStatus expected,
        MembershipOrderStatus next,
        String errorCode,
        boolean releaseFence
    ) {
        return async(() -> {
            try (Connection connection = database.connection()) {
                return inTransaction(connection, () -> {
                    transition(connection, orderId, expected, next, errorCode);
                    finishOperation(connection, orderId,
                        releaseFence ? "COMPLETED" : "REVIEW_REQUIRED", next, errorCode);
                    if (releaseFence) {
                        deleteFence(connection, orderId);
                    } else {
                        updateFence(connection, orderId, next.name());
                    }
                    return null;
                });
            }
        });
    }

    private static void transition(
        Connection connection,
        UUID orderId,
        MembershipOrderStatus expected,
        MembershipOrderStatus next,
        String errorCode
    ) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("""
            UPDATE cct_membership_orders SET status = ?, error_code = ?
            WHERE order_id = ? AND status = ?
            """)) {
            update.setString(1, next.name());
            update.setString(2, errorCode);
            update.setBytes(3, UuidBinary.encode(orderId));
            update.setString(4, expected.name());
            requireOne(update.executeUpdate(), "Unexpected membership order state transition");
        }
    }

    private static void finishOperation(
        Connection connection,
        UUID orderId,
        String operationStatus,
        MembershipOrderStatus orderStatus,
        String errorCode
    ) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("""
            UPDATE cct_operation_requests r
            JOIN cct_membership_orders o ON o.operation_id = r.operation_id
            SET r.status = ?, r.error_code = ?,
                r.response_json = JSON_OBJECT('orderId', BIN_TO_UUID(o.order_id), 'status', ?)
            WHERE o.order_id = ?
            """)) {
            update.setString(1, operationStatus);
            update.setString(2, errorCode);
            update.setString(3, orderStatus.name());
            update.setBytes(4, UuidBinary.encode(orderId));
            requireOne(update.executeUpdate(), "Membership operation could not be completed");
        }
    }

    private static void deleteFence(Connection connection, UUID orderId) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement("""
            DELETE f FROM cct_player_operation_fences f
            JOIN cct_membership_orders o ON o.operation_id = f.operation_id
            WHERE o.order_id = ?
            """)) {
            delete.setBytes(1, UuidBinary.encode(orderId));
            delete.executeUpdate();
        }
    }

    private static void deleteFenceByOperation(Connection connection, UUID operationId) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement(
            "DELETE FROM cct_player_operation_fences WHERE operation_id = ?"
        )) {
            delete.setBytes(1, UuidBinary.encode(operationId));
            delete.executeUpdate();
        }
    }

    private static void updateFence(Connection connection, UUID orderId, String status)
        throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("""
            UPDATE cct_player_operation_fences f
            JOIN cct_membership_orders o ON o.operation_id = f.operation_id
            SET f.state = ?, f.updated_at = UTC_TIMESTAMP(3)
            WHERE o.order_id = ?
            """)) {
            update.setString(1, status);
            update.setBytes(2, UuidBinary.encode(orderId));
            update.executeUpdate();
        }
    }

    private Optional<MembershipOrderResult> findByIdempotency(
        Connection connection,
        UUID playerUuid,
        String key
    ) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
            SELECT order_id FROM cct_membership_orders
            WHERE player_uuid = ? AND idempotency_key = ? LIMIT 1
            """)) {
            query.setBytes(1, UuidBinary.encode(playerUuid));
            query.setString(2, key);
            try (ResultSet result = query.executeQuery()) {
                return result.next()
                    ? findOrder(connection, UuidBinary.decode(result.getBytes(1)))
                    : Optional.empty();
            }
        }
    }

    private Optional<MembershipOrderResult> findOrder(Connection connection, UUID orderId)
        throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
            SELECT order_id, status, tier_key, months, final_price_points,
                   resulting_entitlement_id, error_code
            FROM cct_membership_orders WHERE order_id = ?
            """)) {
            query.setBytes(1, UuidBinary.encode(orderId));
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                UUID entitlementId = nullableUuid(result, "resulting_entitlement_id");
                Instant expiresAt = null;
                if (entitlementId != null) {
                    try (PreparedStatement entitlement = connection.prepareStatement(
                        "SELECT expires_at FROM cct_membership_entitlements WHERE entitlement_id = ?"
                    )) {
                        entitlement.setBytes(1, UuidBinary.encode(entitlementId));
                        try (ResultSet expiry = entitlement.executeQuery()) {
                            if (expiry.next()) {
                                expiresAt = nullableInstant(expiry, "expires_at");
                            }
                        }
                    }
                }
                MembershipOrderStatus status = MembershipOrderStatus.valueOf(result.getString("status"));
                return Optional.of(new MembershipOrderResult(
                    orderId, status, result.getString("tier_key"), result.getInt("months"),
                    result.getInt("final_price_points"), entitlementId, expiresAt,
                    status == MembershipOrderStatus.COMPLETED ? "PENDING" : "NONE",
                    result.getString("error_code")
                ));
            }
        }
    }

    private OrderRow orderForUpdate(Connection connection, UUID orderId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
            SELECT order_id, player_uuid, tier_key, months, upgrade_mode, status,
                   duration_days_snapshot, final_price_points, upgrade_credit_points,
                   previous_entitlement_id
            FROM cct_membership_orders WHERE order_id = ? FOR UPDATE
            """)) {
            query.setBytes(1, UuidBinary.encode(orderId));
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("Membership order does not exist");
                }
                return new OrderRow(
                    orderId,
                    UuidBinary.decode(result.getBytes("player_uuid")),
                    result.getString("tier_key"),
                    result.getInt("months"),
                    UpgradeMode.valueOf(result.getString("upgrade_mode")),
                    MembershipOrderStatus.valueOf(result.getString("status")),
                    result.getInt("duration_days_snapshot"),
                    result.getInt("final_price_points"),
                    result.getInt("upgrade_credit_points"),
                    nullableUuid(result, "previous_entitlement_id")
                );
            }
        }
    }

    private static MembershipEntitlement mapEntitlement(ResultSet result) throws SQLException {
        return new MembershipEntitlement(
            UuidBinary.decode(result.getBytes("entitlement_id")),
            mapTier(result),
            EntitlementState.valueOf(result.getString("state")),
            nullableInstant(result, "starts_at"),
            nullableInstant(result, "expires_at"),
            nullableLong(result, "remaining_seconds"),
            nullableLong(result, "resume_sequence")
        );
    }

    private static MembershipTier mapTier(ResultSet result) throws SQLException {
        try {
            return new MembershipTier(
                result.getString("tier_key"),
                result.getString("display_name"),
                result.getInt("priority"),
                result.getString("luckperms_group"),
                result.getInt("duration_days"),
                result.getInt("price_points"),
                result.getInt("upgrade_credit_rate_bps"),
                result.getString("display_material"),
                JSON.readValue(
                    result.getString("benefits_json"),
                    JSON.getTypeFactory().constructCollectionType(List.class, String.class)
                ),
                result.getBoolean("enabled"),
                result.getLong("config_version")
            );
        } catch (JsonProcessingException exception) {
            throw new SQLException("Membership tier benefits are invalid", exception);
        }
    }

    private static boolean sameEntitlement(MembershipEntitlement first, MembershipEntitlement second) {
        return Objects.equals(id(first), id(second))
            && (first == null || Objects.equals(first.expiresAt(), second.expiresAt()));
    }

    private static UUID id(MembershipEntitlement entitlement) {
        return entitlement == null ? null : entitlement.entitlementId();
    }

    private <T> CompletionStage<T> async(SqlSupplier<T> action) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return action.get();
            } catch (MembershipException exception) {
                throw exception;
            } catch (SQLException exception) {
                throw new CompletionException("Membership database operation failed", exception);
            }
        }, executors.blocking());
    }

    private static <T> T inTransaction(Connection connection, SqlSupplier<T> action) throws SQLException {
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            T result = action.get();
            connection.commit();
            return result;
        } catch (SQLException | RuntimeException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }

    private static String json(Object value) throws SQLException {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new SQLException("Unable to encode membership JSON", exception);
        }
    }

    private static void setInstant(PreparedStatement statement, int index, Instant value)
        throws SQLException {
        statement.setObject(index, LocalDateTime.ofInstant(value, ZoneOffset.UTC));
    }

    private static void nullableInstant(PreparedStatement statement, int index, Instant value)
        throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.TIMESTAMP);
        } else {
            setInstant(statement, index, value);
        }
    }

    private static Instant nullableInstant(ResultSet result, String column) throws SQLException {
        LocalDateTime value = result.getObject(column, LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private static Long nullableLong(ResultSet result, String column) throws SQLException {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }

    private static void nullableInt(PreparedStatement statement, int index, Integer value)
        throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.INTEGER);
        } else {
            statement.setInt(index, value);
        }
    }

    private static void nullableUuid(PreparedStatement statement, int index, UUID value)
        throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.BINARY);
        } else {
            statement.setBytes(index, UuidBinary.encode(value));
        }
    }

    private static UUID nullableUuid(ResultSet result, String column) throws SQLException {
        byte[] value = result.getBytes(column);
        return value == null ? null : UuidBinary.decode(value);
    }

    private static void requireOne(int affected, String message) throws SQLException {
        if (affected != 1) {
            throw new SQLException(message);
        }
    }

    @FunctionalInterface
    private interface SqlSupplier<T> {
        T get() throws SQLException;
    }

    private record OrderRecovery(UUID orderId, MembershipOrderStatus status) {
    }

    private record OrderRow(
        UUID orderId,
        UUID playerUuid,
        String tierKey,
        int months,
        UpgradeMode upgradeMode,
        MembershipOrderStatus status,
        int durationDaysSnapshot,
        int finalPricePoints,
        int upgradeCreditPoints,
        UUID previousEntitlementId
    ) {
    }
}
