package cn.cctstudio.cctsystem.exchange;

import cn.cctstudio.cctsystem.core.concurrent.CctExecutors;
import cn.cctstudio.cctsystem.core.config.ExchangeSourceConfig;
import cn.cctstudio.cctsystem.identity.UuidBinary;
import cn.cctstudio.cctsystem.points.PointsMutationResult;
import cn.cctstudio.cctsystem.storage.mysql.DatabaseAccess;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

final class JdbcExchangeStore {
    private static final String FIND_TRANSACTION = """
        SELECT transaction_id, player_uuid, source_id, requested_points, currency_cost, status,
               week_start, cap_group, error_code
        FROM cct_exchange_transactions
        WHERE player_uuid = ? AND idempotency_key = ?
        LIMIT 1
        """;
    private static final String FIND_TRANSACTION_BY_ID = """
        SELECT transaction_id, player_uuid, source_id, requested_points, currency_cost,
               status, week_start, cap_group, idempotency_key, error_code
        FROM cct_exchange_transactions
        WHERE transaction_id = ?
        """;

    private final DatabaseAccess database;
    private final CctExecutors executors;
    private final int weeklyLimit;
    private final ZoneId timezone;
    private final String nodeId;

    JdbcExchangeStore(
        DatabaseAccess database,
        CctExecutors executors,
        int weeklyLimit,
        ZoneId timezone,
        String nodeId
    ) {
        this.database = database;
        this.executors = executors;
        this.weeklyLimit = weeklyLimit;
        this.timezone = timezone;
        this.nodeId = nodeId;
    }

    CompletionStage<Void> syncConfiguration(Collection<ExchangeSourceConfig> sources) {
        return runAsync(() -> {
            try (Connection connection = database.connection()) {
                inTransaction(connection, () -> {
                    for (ExchangeSourceConfig source : sources) {
                        ensureCapGroup(connection, source.capGroup());
                        upsertSource(connection, source);
                    }
                    return null;
                });
            }
            return null;
        });
    }

    CompletionStage<WeeklyUsage> usage(UUID playerUuid, ExchangeSourceConfig source, Instant now) {
        return runAsync(() -> {
            LocalDate weekStart = ExchangeClock.weekStart(now, timezone);
            try (Connection connection = database.connection()) {
                return readUsage(connection, playerUuid, weekStart, source.capGroup());
            }
        });
    }

    CompletionStage<Void> recoverInterruptedTransactions() {
        return runAsync(() -> {
            try (Connection connection = database.connection()) {
                return inTransaction(connection, () -> {
                    try (PreparedStatement query = connection.prepareStatement("""
                        SELECT transaction_id, status
                        FROM cct_exchange_transactions
                        WHERE node_id = ? AND status IN (
                            'CREATED', 'RESERVED', 'MONEY_DEBIT_REQUESTED', 'MONEY_DEBITED',
                            'POINTS_CREDIT_REQUESTED', 'COMPENSATING'
                        )
                        FOR UPDATE
                        """)) {
                        query.setString(1, nodeId);
                        try (ResultSet rows = query.executeQuery()) {
                            while (rows.next()) {
                                UUID transactionId = UuidBinary.decode(rows.getBytes(1));
                                ExchangeStatus status = ExchangeStatus.valueOf(rows.getString(2));
                                if (status == ExchangeStatus.CREATED) {
                                    transition(connection, transactionId, status, ExchangeStatus.FAILED,
                                        "NODE_RESTARTED_BEFORE_EXTERNAL_CALL");
                                    deleteFence(connection, transactionId);
                                    markOperation(connection, transactionId, "COMPLETED",
                                        ExchangeStatus.FAILED, "NODE_RESTARTED_BEFORE_EXTERNAL_CALL");
                                    continue;
                                }
                                if (status == ExchangeStatus.RESERVED) {
                                    releaseReservation(connection, transactionId);
                                    transition(connection, transactionId, status, ExchangeStatus.FAILED,
                                        "NODE_RESTARTED_BEFORE_EXTERNAL_CALL");
                                    deleteFence(connection, transactionId);
                                    markOperation(connection, transactionId, "COMPLETED",
                                        ExchangeStatus.FAILED, "NODE_RESTARTED_BEFORE_EXTERNAL_CALL");
                                    continue;
                                }
                                transition(connection, transactionId, status,
                                    ExchangeStatus.REVIEW_REQUIRED, "NODE_RESTARTED_WITH_AMBIGUOUS_OPERATION");
                                updateFence(connection, transactionId, "REVIEW_REQUIRED");
                                markOperation(connection, transactionId, "REVIEW_REQUIRED",
                                    ExchangeStatus.REVIEW_REQUIRED,
                                    "NODE_RESTARTED_WITH_AMBIGUOUS_OPERATION");
                                action(connection, transactionId, "RECOVERY", "AMBIGUOUS", null, null,
                                    null, null, "NODE_RESTARTED_WITH_AMBIGUOUS_OPERATION");
                            }
                        }
                    }
                    return null;
                });
            }
        });
    }

    CompletionStage<PreparedExchange> prepare(
        ExchangeRequest request,
        ExchangeSourceConfig source,
        UUID transactionId,
        BigDecimal currencyBalance,
        Instant now
    ) {
        return runAsync(() -> {
            try (Connection connection = database.connection()) {
                try {
                    return inTransaction(connection, () -> prepareTransaction(
                        connection,
                        request,
                        source,
                        transactionId,
                        currencyBalance,
                        now
                    ));
                } catch (SQLException exception) {
                    if ("23000".equals(exception.getSQLState())) {
                        return findByIdempotency(connection, request.playerUuid(), request.idempotencyKey())
                            .map(PreparedExchange::existing)
                            .orElseThrow(() -> exception);
                    }
                    throw exception;
                }
            }
        });
    }

    CompletionStage<Void> moneyDebitRequested(UUID transactionId, BigDecimal amount) {
        return transitionWithAction(
            transactionId,
            ExchangeStatus.RESERVED,
            ExchangeStatus.MONEY_DEBIT_REQUESTED,
            "MONEY_DEBIT",
            "REQUESTED",
            amount,
            null,
            null,
            null,
            null
        );
    }

    CompletionStage<Void> moneyDebited(UUID transactionId, CurrencyMutationResult mutation) {
        return runAsync(() -> {
            try (Connection connection = database.connection()) {
                return inTransaction(connection, () -> {
                    transition(connection, transactionId, ExchangeStatus.MONEY_DEBIT_REQUESTED,
                        ExchangeStatus.MONEY_DEBITED, null);
                    try (PreparedStatement update = connection.prepareStatement("""
                        UPDATE cct_exchange_transactions
                        SET currency_balance_before = ?, currency_balance_after = ?
                        WHERE transaction_id = ?
                        """)) {
                        update.setBigDecimal(1, mutation.balanceBefore());
                        update.setBigDecimal(2, mutation.balanceAfter());
                        update.setBytes(3, UuidBinary.encode(transactionId));
                        update.executeUpdate();
                    }
                    action(connection, transactionId, "MONEY_DEBIT", "COMPLETED", null, null,
                        mutation.balanceBefore(), mutation.balanceAfter(), null);
                    return null;
                });
            }
        });
    }

    CompletionStage<Void> pointsCreditRequested(UUID transactionId, int points) {
        return transitionWithAction(
            transactionId,
            ExchangeStatus.MONEY_DEBITED,
            ExchangeStatus.POINTS_CREDIT_REQUESTED,
            "POINTS_CREDIT",
            "REQUESTED",
            null,
            points,
            null,
            null,
            null
        );
    }

    CompletionStage<Void> complete(UUID transactionId, PointsMutationResult points) {
        return terminalWithUsage(
            transactionId,
            ExchangeStatus.POINTS_CREDIT_REQUESTED,
            ExchangeStatus.COMPLETED,
            true,
            points,
            null,
            true
        );
    }

    CompletionStage<Void> failMoneyDebit(UUID transactionId, String errorCode) {
        return terminalWithUsage(
            transactionId,
            ExchangeStatus.MONEY_DEBIT_REQUESTED,
            ExchangeStatus.FAILED,
            false,
            null,
            errorCode,
            true
        );
    }

    CompletionStage<Void> beginCompensation(UUID transactionId, BigDecimal amount, String errorCode) {
        return runAsync(() -> {
            try (Connection connection = database.connection()) {
                return inTransaction(connection, () -> {
                    transition(connection, transactionId, ExchangeStatus.POINTS_CREDIT_REQUESTED,
                        ExchangeStatus.COMPENSATING, errorCode);
                    action(connection, transactionId, "POINTS_CREDIT", "FAILED", null, null,
                        null, null, errorCode);
                    action(connection, transactionId, "MONEY_REFUND", "REQUESTED", amount, null,
                        null, null, null);
                    return null;
                });
            }
        });
    }

    CompletionStage<Void> compensated(UUID transactionId, CurrencyMutationResult refund) {
        return terminalWithUsage(
            transactionId,
            ExchangeStatus.COMPENSATING,
            ExchangeStatus.COMPENSATED,
            false,
            null,
            "POINTS_CREDIT_REJECTED",
            true,
            refund
        );
    }

    CompletionStage<Void> compensationPending(
        UUID transactionId,
        CurrencyMutationResult refund,
        String errorCode
    ) {
        return runAsync(() -> {
            try (Connection connection = database.connection()) {
                return inTransaction(connection, () -> {
                    transition(connection, transactionId, ExchangeStatus.COMPENSATING,
                        ExchangeStatus.COMPENSATION_PENDING, errorCode);
                    action(connection, transactionId, "MONEY_REFUND", "FAILED", null, null,
                        refund == null ? null : refund.balanceBefore(),
                        refund == null ? null : refund.balanceAfter(), errorCode);
                    markOperation(connection, transactionId, "REVIEW_REQUIRED",
                        ExchangeStatus.COMPENSATION_PENDING, errorCode);
                    updateFence(connection, transactionId, "COMPENSATION_PENDING");
                    return null;
                });
            }
        });
    }

    CompletionStage<Void> reviewRequired(
        UUID transactionId,
        ExchangeStatus expected,
        String actionType,
        String errorCode
    ) {
        return runAsync(() -> {
            try (Connection connection = database.connection()) {
                return inTransaction(connection, () -> {
                    transition(connection, transactionId, expected, ExchangeStatus.REVIEW_REQUIRED, errorCode);
                    action(connection, transactionId, actionType, "AMBIGUOUS", null, null,
                        null, null, errorCode);
                    markOperation(connection, transactionId, "REVIEW_REQUIRED",
                        ExchangeStatus.REVIEW_REQUIRED, errorCode);
                    updateFence(connection, transactionId, "REVIEW_REQUIRED");
                    return null;
                });
            }
        });
    }

    CompletionStage<ExchangeResult> result(UUID transactionId) {
        return runAsync(() -> {
            try (Connection connection = database.connection();
                 PreparedStatement query = connection.prepareStatement(FIND_TRANSACTION_BY_ID)) {
                query.setBytes(1, UuidBinary.encode(transactionId));
                try (ResultSet row = query.executeQuery()) {
                    if (!row.next()) {
                        throw new SQLException("Exchange transaction does not exist");
                    }
                    return mapResult(connection, row);
                }
            }
        });
    }

    private PreparedExchange prepareTransaction(
        Connection connection,
        ExchangeRequest request,
        ExchangeSourceConfig source,
        UUID transactionId,
        BigDecimal currencyBalance,
        Instant now
    ) throws SQLException {
        Optional<ExchangeResult> existing = findByIdempotency(
            connection,
            request.playerUuid(),
            request.idempotencyKey()
        );
        if (existing.isPresent()) {
            return PreparedExchange.existing(existing.orElseThrow());
        }

        LocalDate weekStart = ExchangeClock.weekStart(now, timezone);
        BigDecimal cost = source.currencyUnitsPerPoint().multiply(
            BigDecimal.valueOf(request.requestedPoints())
        );
        insertOperation(connection, transactionId, request);
        insertTransaction(connection, transactionId, request, source, weekStart, cost, currencyBalance);
        ensureUsage(connection, request.playerUuid(), weekStart, source.capGroup());
        if (!insertFence(connection, request.playerUuid(), transactionId)) {
            failInitial(connection, transactionId, "OPERATION_IN_PROGRESS");
            return PreparedExchange.existing(readById(connection, transactionId));
        }
        WeeklyUsage usage = readUsageForUpdate(
            connection,
            request.playerUuid(),
            weekStart,
            source.capGroup()
        );

        if (currencyBalance.compareTo(cost) < 0) {
            deleteFence(connection, transactionId);
            failInitial(connection, transactionId, "INSUFFICIENT_CURRENCY");
            return PreparedExchange.existing(readById(connection, transactionId));
        }
        if (request.requestedPoints() > usage.remainingPoints()) {
            deleteFence(connection, transactionId);
            failInitial(connection, transactionId, "WEEKLY_LIMIT_EXCEEDED");
            return PreparedExchange.existing(readById(connection, transactionId));
        }
        try (PreparedStatement reserve = connection.prepareStatement("""
            UPDATE cct_exchange_weekly_usage
            SET reserved_points = reserved_points + ?, version = version + 1
            WHERE player_uuid = ? AND week_start = ? AND cap_group = ?
            """)) {
            reserve.setInt(1, request.requestedPoints());
            reserve.setBytes(2, UuidBinary.encode(request.playerUuid()));
            reserve.setDate(3, Date.valueOf(weekStart));
            reserve.setString(4, source.capGroup());
            requireOne(reserve.executeUpdate(), "Unable to reserve weekly exchange quota");
        }
        transition(connection, transactionId, ExchangeStatus.CREATED, ExchangeStatus.RESERVED, null);
        action(connection, transactionId, "WEEKLY_QUOTA", "RESERVED", null,
            request.requestedPoints(), null, null, null);
        return PreparedExchange.proceed(transactionId, cost);
    }

    private void ensureCapGroup(Connection connection, String capGroup) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
            INSERT IGNORE INTO cct_exchange_cap_groups(cap_group, weekly_limit_points, timezone)
            VALUES (?, ?, ?)
            """)) {
            insert.setString(1, capGroup);
            insert.setInt(2, weeklyLimit);
            insert.setString(3, timezone.getId());
            insert.executeUpdate();
        }
        try (PreparedStatement query = connection.prepareStatement("""
            SELECT weekly_limit_points, timezone
            FROM cct_exchange_cap_groups
            WHERE cap_group = ?
            FOR UPDATE
            """)) {
            query.setString(1, capGroup);
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()
                    || result.getInt(1) != weeklyLimit
                    || !timezone.getId().equals(result.getString(2))) {
                    throw new SQLException("Exchange cap group configuration does not match this node");
                }
            }
        }
    }

    private static void upsertSource(Connection connection, ExchangeSourceConfig source) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO cct_exchange_sources(
                source_id, server_id, currency_display_name, currency_units_per_point,
                cap_group, enabled
            ) VALUES (?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                server_id = VALUES(server_id),
                currency_display_name = VALUES(currency_display_name),
                currency_units_per_point = VALUES(currency_units_per_point),
                cap_group = VALUES(cap_group),
                enabled = VALUES(enabled)
            """)) {
            statement.setString(1, source.sourceId());
            statement.setString(2, source.serverId());
            statement.setString(3, source.currencyDisplayName());
            statement.setBigDecimal(4, source.currencyUnitsPerPoint());
            statement.setString(5, source.capGroup());
            statement.setBoolean(6, source.enabled());
            statement.executeUpdate();
        }
    }

    private static void insertOperation(
        Connection connection,
        UUID transactionId,
        ExchangeRequest request
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO cct_operation_requests(
                operation_id, player_uuid, operation_type, idempotency_key, status
            ) VALUES (?, ?, 'EXCHANGE', ?, 'PENDING')
            """)) {
            statement.setBytes(1, UuidBinary.encode(transactionId));
            statement.setBytes(2, UuidBinary.encode(request.playerUuid()));
            statement.setString(3, request.idempotencyKey());
            statement.executeUpdate();
        }
    }

    private void insertTransaction(
        Connection connection,
        UUID transactionId,
        ExchangeRequest request,
        ExchangeSourceConfig source,
        LocalDate weekStart,
        BigDecimal cost,
        BigDecimal balance
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO cct_exchange_transactions(
                transaction_id, player_uuid, source_id, server_id, node_id, cap_group, week_start,
                requested_points, currency_units_per_point, currency_cost, origin,
                idempotency_key, status, currency_balance_before
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'CREATED', ?)
            """)) {
            statement.setBytes(1, UuidBinary.encode(transactionId));
            statement.setBytes(2, UuidBinary.encode(request.playerUuid()));
            statement.setString(3, source.sourceId());
            statement.setString(4, source.serverId());
            statement.setString(5, nodeId);
            statement.setString(6, source.capGroup());
            statement.setDate(7, Date.valueOf(weekStart));
            statement.setInt(8, request.requestedPoints());
            statement.setBigDecimal(9, source.currencyUnitsPerPoint());
            statement.setBigDecimal(10, cost);
            statement.setString(11, request.origin().name());
            statement.setString(12, request.idempotencyKey());
            statement.setBigDecimal(13, balance);
            statement.executeUpdate();
        }
    }

    private static void releaseReservation(Connection connection, UUID transactionId)
        throws SQLException {
        TransactionRow row = lockTransaction(connection, transactionId);
        try (PreparedStatement update = connection.prepareStatement("""
            UPDATE cct_exchange_weekly_usage
            SET reserved_points = reserved_points - ?, version = version + 1
            WHERE player_uuid = ? AND week_start = ? AND cap_group = ?
              AND reserved_points >= ?
            """)) {
            update.setInt(1, row.requestedPoints());
            update.setBytes(2, UuidBinary.encode(row.playerUuid()));
            update.setDate(3, Date.valueOf(row.weekStart()));
            update.setString(4, row.capGroup());
            update.setInt(5, row.requestedPoints());
            requireOne(update.executeUpdate(), "Exchange reservation is missing during recovery");
        }
    }

    private static boolean insertFence(Connection connection, UUID playerUuid, UUID operationId)
        throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT IGNORE INTO cct_player_operation_fences(
                player_uuid, operation_id, operation_type, state, acquired_at, updated_at
            ) VALUES (?, ?, 'EXCHANGE', 'ACTIVE', UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
            """)) {
            statement.setBytes(1, UuidBinary.encode(playerUuid));
            statement.setBytes(2, UuidBinary.encode(operationId));
            return statement.executeUpdate() == 1;
        }
    }

    private void ensureUsage(
        Connection connection,
        UUID playerUuid,
        LocalDate weekStart,
        String capGroup
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT IGNORE INTO cct_exchange_weekly_usage(
                player_uuid, week_start, cap_group, completed_points, reserved_points
            ) VALUES (?, ?, ?, 0, 0)
            """)) {
            statement.setBytes(1, UuidBinary.encode(playerUuid));
            statement.setDate(2, Date.valueOf(weekStart));
            statement.setString(3, capGroup);
            statement.executeUpdate();
        }
    }

    private WeeklyUsage readUsageForUpdate(
        Connection connection,
        UUID playerUuid,
        LocalDate weekStart,
        String capGroup
    ) throws SQLException {
        return readUsage(connection, playerUuid, weekStart, capGroup, true);
    }

    private WeeklyUsage readUsage(
        Connection connection,
        UUID playerUuid,
        LocalDate weekStart,
        String capGroup
    ) throws SQLException {
        return readUsage(connection, playerUuid, weekStart, capGroup, false);
    }

    private WeeklyUsage readUsage(
        Connection connection,
        UUID playerUuid,
        LocalDate weekStart,
        String capGroup,
        boolean lock
    ) throws SQLException {
        String sql = """
            SELECT completed_points, reserved_points
            FROM cct_exchange_weekly_usage
            WHERE player_uuid = ? AND week_start = ? AND cap_group = ?
            """ + (lock ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setBytes(1, UuidBinary.encode(playerUuid));
            statement.setDate(2, Date.valueOf(weekStart));
            statement.setString(3, capGroup);
            try (ResultSet result = statement.executeQuery()) {
                int completed = 0;
                int reserved = 0;
                if (result.next()) {
                    completed = result.getInt(1);
                    reserved = result.getInt(2);
                }
                return new WeeklyUsage(
                    weekStart,
                    completed,
                    reserved,
                    weeklyLimit,
                    ExchangeClock.nextReset(weekStart, timezone)
                );
            }
        }
    }

    private CompletionStage<Void> transitionWithAction(
        UUID transactionId,
        ExchangeStatus expected,
        ExchangeStatus next,
        String actionType,
        String actionState,
        BigDecimal amount,
        Integer points,
        BigDecimal balanceBefore,
        BigDecimal balanceAfter,
        String errorCode
    ) {
        return runAsync(() -> {
            try (Connection connection = database.connection()) {
                return inTransaction(connection, () -> {
                    transition(connection, transactionId, expected, next, errorCode);
                    action(connection, transactionId, actionType, actionState, amount, points,
                        balanceBefore, balanceAfter, errorCode);
                    return null;
                });
            }
        });
    }

    private CompletionStage<Void> terminalWithUsage(
        UUID transactionId,
        ExchangeStatus expected,
        ExchangeStatus terminal,
        boolean consumeReservation,
        PointsMutationResult points,
        String errorCode,
        boolean releaseFence
    ) {
        return terminalWithUsage(
            transactionId,
            expected,
            terminal,
            consumeReservation,
            points,
            errorCode,
            releaseFence,
            null
        );
    }

    private CompletionStage<Void> terminalWithUsage(
        UUID transactionId,
        ExchangeStatus expected,
        ExchangeStatus terminal,
        boolean consumeReservation,
        PointsMutationResult points,
        String errorCode,
        boolean releaseFence,
        CurrencyMutationResult refund
    ) {
        return runAsync(() -> {
            try (Connection connection = database.connection()) {
                return inTransaction(connection, () -> {
                    TransactionRow row = lockTransaction(connection, transactionId);
                    if (row.status() != expected) {
                        throw new SQLException("Unexpected exchange transaction state " + row.status());
                    }
                    ensureUsage(connection, row.playerUuid(), row.weekStart(), row.capGroup());
                    readUsageForUpdate(connection, row.playerUuid(), row.weekStart(), row.capGroup());
                    try (PreparedStatement updateUsage = connection.prepareStatement("""
                        UPDATE cct_exchange_weekly_usage
                        SET reserved_points = reserved_points - ?,
                            completed_points = completed_points + ?,
                            version = version + 1
                        WHERE player_uuid = ? AND week_start = ? AND cap_group = ?
                          AND reserved_points >= ?
                        """)) {
                        int completed = consumeReservation ? row.requestedPoints() : 0;
                        updateUsage.setInt(1, row.requestedPoints());
                        updateUsage.setInt(2, completed);
                        updateUsage.setBytes(3, UuidBinary.encode(row.playerUuid()));
                        updateUsage.setDate(4, Date.valueOf(row.weekStart()));
                        updateUsage.setString(5, row.capGroup());
                        updateUsage.setInt(6, row.requestedPoints());
                        requireOne(updateUsage.executeUpdate(), "Exchange reservation is missing");
                    }
                    transition(connection, transactionId, expected, terminal, errorCode);
                    if (points != null) {
                        try (PreparedStatement update = connection.prepareStatement("""
                            UPDATE cct_exchange_transactions
                            SET points_balance_before = ?, points_balance_after = ?
                            WHERE transaction_id = ?
                            """)) {
                            setNullableInt(update, 1, points.balanceBefore());
                            setNullableInt(update, 2, points.balanceAfter());
                            update.setBytes(3, UuidBinary.encode(transactionId));
                            update.executeUpdate();
                        }
                        action(connection, transactionId, "POINTS_CREDIT", "COMPLETED", null,
                            row.requestedPoints(), null, null, null);
                    }
                    if (refund != null) {
                        action(connection, transactionId, "MONEY_REFUND", "COMPLETED", null, null,
                            refund.balanceBefore(), refund.balanceAfter(), null);
                    }
                    if (releaseFence) {
                        deleteFence(connection, transactionId);
                    }
                    markOperation(connection, transactionId, "COMPLETED", terminal, errorCode);
                    return null;
                });
            }
        });
    }

    private static void transition(
        Connection connection,
        UUID transactionId,
        ExchangeStatus expected,
        ExchangeStatus next,
        String errorCode
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            UPDATE cct_exchange_transactions
            SET status = ?, error_code = ?
            WHERE transaction_id = ? AND status = ?
            """)) {
            statement.setString(1, next.name());
            statement.setString(2, errorCode);
            statement.setBytes(3, UuidBinary.encode(transactionId));
            statement.setString(4, expected.name());
            requireOne(statement.executeUpdate(), "Exchange transaction state changed concurrently");
        }
    }

    private static void action(
        Connection connection,
        UUID transactionId,
        String type,
        String state,
        BigDecimal amount,
        Integer points,
        BigDecimal balanceBefore,
        BigDecimal balanceAfter,
        String errorCode
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO cct_exchange_actions(
                transaction_id, action_type, state, amount, points,
                balance_before, balance_after, error_code
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """)) {
            statement.setBytes(1, UuidBinary.encode(transactionId));
            statement.setString(2, type);
            statement.setString(3, state);
            statement.setBigDecimal(4, amount);
            if (points == null) {
                statement.setNull(5, Types.INTEGER);
            } else {
                statement.setInt(5, points);
            }
            statement.setBigDecimal(6, balanceBefore);
            statement.setBigDecimal(7, balanceAfter);
            statement.setString(8, errorCode);
            statement.executeUpdate();
        }
    }

    private static void failInitial(Connection connection, UUID transactionId, String errorCode)
        throws SQLException {
        transition(connection, transactionId, ExchangeStatus.CREATED, ExchangeStatus.FAILED, errorCode);
        markOperation(connection, transactionId, "COMPLETED", ExchangeStatus.FAILED, errorCode);
    }

    private static void markOperation(
        Connection connection,
        UUID operationId,
        String status,
        ExchangeStatus exchangeStatus,
        String errorCode
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            UPDATE cct_operation_requests
            SET status = ?, error_code = ?,
                response_json = JSON_OBJECT(
                    'transactionId', ?,
                    'status', ?,
                    'errorCode', ?
                )
            WHERE operation_id = ?
            """)) {
            statement.setString(1, status);
            statement.setString(2, errorCode);
            statement.setString(3, operationId.toString());
            statement.setString(4, exchangeStatus.name());
            statement.setString(5, errorCode);
            statement.setBytes(6, UuidBinary.encode(operationId));
            statement.executeUpdate();
        }
    }

    private static void deleteFence(Connection connection, UUID operationId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            DELETE FROM cct_player_operation_fences
            WHERE operation_id = ?
            """)) {
            statement.setBytes(1, UuidBinary.encode(operationId));
            statement.executeUpdate();
        }
    }

    private static void updateFence(Connection connection, UUID operationId, String state)
        throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            UPDATE cct_player_operation_fences
            SET state = ?, updated_at = UTC_TIMESTAMP(3)
            WHERE operation_id = ?
            """)) {
            statement.setString(1, state);
            statement.setBytes(2, UuidBinary.encode(operationId));
            statement.executeUpdate();
        }
    }

    private Optional<ExchangeResult> findByIdempotency(
        Connection connection,
        UUID playerUuid,
        String idempotencyKey
    ) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(FIND_TRANSACTION)) {
            query.setBytes(1, UuidBinary.encode(playerUuid));
            query.setString(2, idempotencyKey);
            try (ResultSet row = query.executeQuery()) {
                return row.next() ? Optional.of(mapResult(connection, row)) : Optional.empty();
            }
        }
    }

    private ExchangeResult readById(Connection connection, UUID transactionId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(FIND_TRANSACTION_BY_ID)) {
            query.setBytes(1, UuidBinary.encode(transactionId));
            try (ResultSet row = query.executeQuery()) {
                if (!row.next()) {
                    throw new SQLException("Exchange transaction does not exist");
                }
                return mapResult(connection, row);
            }
        }
    }

    private ExchangeResult mapResult(Connection connection, ResultSet row) throws SQLException {
        UUID playerUuid;
        try {
            playerUuid = UuidBinary.decode(row.getBytes("player_uuid"));
        } catch (SQLException missingColumn) {
            playerUuid = findPlayerUuid(connection, UuidBinary.decode(row.getBytes("transaction_id")));
        }
        LocalDate weekStart = row.getDate("week_start").toLocalDate();
        String capGroup = row.getString("cap_group");
        WeeklyUsage usage = readUsage(connection, playerUuid, weekStart, capGroup);
        return new ExchangeResult(
            UuidBinary.decode(row.getBytes("transaction_id")),
            ExchangeStatus.valueOf(row.getString("status")),
            row.getString("source_id"),
            row.getInt("requested_points"),
            row.getBigDecimal("currency_cost"),
            usage.completedPoints(),
            usage.reservedPoints(),
            usage.remainingPoints(),
            usage.resetsAt(),
            row.getString("error_code")
        );
    }

    private static UUID findPlayerUuid(Connection connection, UUID transactionId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
            SELECT player_uuid FROM cct_exchange_transactions WHERE transaction_id = ?
            """)) {
            query.setBytes(1, UuidBinary.encode(transactionId));
            try (ResultSet row = query.executeQuery()) {
                if (!row.next()) {
                    throw new SQLException("Exchange transaction does not exist");
                }
                return UuidBinary.decode(row.getBytes(1));
            }
        }
    }

    private static TransactionRow lockTransaction(Connection connection, UUID transactionId)
        throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
            SELECT player_uuid, requested_points, week_start, cap_group, status
            FROM cct_exchange_transactions
            WHERE transaction_id = ?
            FOR UPDATE
            """)) {
            query.setBytes(1, UuidBinary.encode(transactionId));
            try (ResultSet row = query.executeQuery()) {
                if (!row.next()) {
                    throw new SQLException("Exchange transaction does not exist");
                }
                return new TransactionRow(
                    UuidBinary.decode(row.getBytes(1)),
                    row.getInt(2),
                    row.getDate(3).toLocalDate(),
                    row.getString(4),
                    ExchangeStatus.valueOf(row.getString(5))
                );
            }
        }
    }

    CompletionStage<Boolean> claimSocialCoinReward(UUID playerUuid) {
        return runAsync(() -> {
            try (Connection connection = database.connection()) {
                return inTransaction(connection, () -> {
                    try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO cct_social_binding_rewards(player_uuid)
                        VALUES (?) ON DUPLICATE KEY UPDATE player_uuid = player_uuid
                        """)) {
                        insert.setBytes(1, UuidBinary.encode(playerUuid));
                        insert.executeUpdate();
                    }
                    try (PreparedStatement update = connection.prepareStatement("""
                        UPDATE cct_social_binding_rewards SET coins_status = 'PROCESSING'
                        WHERE player_uuid = ? AND coins_status = 'PENDING'
                        """)) {
                        update.setBytes(1, UuidBinary.encode(playerUuid));
                        return update.executeUpdate() == 1;
                    }
                });
            }
        });
    }

    CompletionStage<Void> completeSocialCoinReward(UUID playerUuid) {
        return socialCoinRewardStatus(playerUuid, "COMPLETED", true);
    }

    CompletionStage<Void> retrySocialCoinReward(UUID playerUuid) {
        return socialCoinRewardStatus(playerUuid, "PENDING", false);
    }

    private CompletionStage<Void> socialCoinRewardStatus(
        UUID playerUuid,
        String status,
        boolean completed
    ) {
        return runAsync(() -> {
            String sql = completed
                ? "UPDATE cct_social_binding_rewards SET coins_status = ?, coins_delivered_at = UTC_TIMESTAMP(3) WHERE player_uuid = ? AND coins_status = 'PROCESSING'"
                : "UPDATE cct_social_binding_rewards SET coins_status = ?, coins_delivered_at = NULL WHERE player_uuid = ? AND coins_status = 'PROCESSING'";
            try (Connection connection = database.connection();
                 PreparedStatement update = connection.prepareStatement(sql)) {
                update.setString(1, status);
                update.setBytes(2, UuidBinary.encode(playerUuid));
                update.executeUpdate();
            }
            return null;
        });
    }

    private static void setNullableInt(PreparedStatement statement, int index, Integer value)
        throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.INTEGER);
        } else {
            statement.setInt(index, value);
        }
    }

    private static void requireOne(int affected, String message) throws SQLException {
        if (affected != 1) {
            throw new SQLException(message);
        }
    }

    private <T> CompletionStage<T> runAsync(SqlSupplier<T> supplier) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return supplier.get();
            } catch (SQLException exception) {
                throw new CompletionException("Exchange persistence failed", exception);
            }
        }, executors.blocking());
    }

    private static <T> T inTransaction(Connection connection, SqlSupplier<T> operation)
        throws SQLException {
        boolean originalAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            T result = operation.get();
            connection.commit();
            return result;
        } catch (SQLException | RuntimeException exception) {
            try {
                connection.rollback();
            } catch (SQLException rollbackFailure) {
                exception.addSuppressed(rollbackFailure);
            }
            throw exception;
        } finally {
            connection.setAutoCommit(originalAutoCommit);
        }
    }

    @FunctionalInterface
    private interface SqlSupplier<T> {
        T get() throws SQLException;
    }

    private record TransactionRow(
        UUID playerUuid,
        int requestedPoints,
        LocalDate weekStart,
        String capGroup,
        ExchangeStatus status
    ) {
    }
}
