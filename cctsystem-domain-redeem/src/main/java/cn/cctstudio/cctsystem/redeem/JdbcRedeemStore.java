package cn.cctstudio.cctsystem.redeem;

import cn.cctstudio.cctsystem.core.concurrent.CctExecutors;
import cn.cctstudio.cctsystem.core.id.UuidV7;
import cn.cctstudio.cctsystem.identity.UuidBinary;
import cn.cctstudio.cctsystem.storage.mysql.DatabaseAccess;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

final class JdbcRedeemStore {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final DatabaseAccess database;
    private final CctExecutors executors;
    private final String nodeId;

    JdbcRedeemStore(DatabaseAccess database, CctExecutors executors, String nodeId) {
        this.database = database;
        this.executors = executors;
        this.nodeId = nodeId;
    }

    CompletionStage<GeneratedRedeemBatch> createBatch(
        GenerateRedeemCodesRequest request,
        int codeLength,
        List<GeneratedCode> generated,
        Instant now
    ) {
        return CompletableFuture.supplyAsync(() -> {
            UUID batchId = UuidV7.create(now);
            try (Connection connection = database.connection()) {
                boolean originalAutoCommit = connection.getAutoCommit();
                connection.setAutoCommit(false);
                try {
                    insertBatch(connection, batchId, request, codeLength, now);
                    for (int codeIndex = 0; codeIndex < generated.size(); codeIndex++) {
                        GeneratedCode code = generated.get(codeIndex);
                        UUID codeId = UuidV7.create(now.plusNanos(codeIndex + 1L));
                        insertCode(connection, codeId, batchId, code.hash(), request, now);
                        insertRewards(connection, codeId, request.rewards(), now.plusNanos(10_000L + codeIndex * 100L));
                    }
                    insertAudit(
                        connection,
                        "ADMIN",
                        request.creator(),
                        "REDEEM_BATCH_CREATED",
                        "REDEEM_BATCH",
                        batchId.toString(),
                        null,
                        batchId.toString(),
                        "GAME_ADMIN",
                        JSON.createObjectNode()
                            .put("count", request.count())
                            .put("maxUsesPerCode", request.maxUsesPerCode())
                            .put("rewardCount", request.rewards().size()),
                        now
                    );
                    connection.commit();
                    connection.setAutoCommit(originalAutoCommit);
                    return new GeneratedRedeemBatch(
                        batchId,
                        generated.stream().map(GeneratedCode::plainText).toList()
                    );
                } catch (SQLException exception) {
                    rollback(connection, exception);
                    throw exception;
                }
            } catch (SQLException exception) {
                throw new CompletionException("Unable to create redeem code batch", exception);
            }
        }, executors.blocking());
    }

    CompletionStage<Reservation> reserve(
        RedeemRequest request,
        byte[] codeHash,
        String fingerprint,
        Instant now
    ) {
        return CompletableFuture.supplyAsync(() -> reserveBlocking(request, codeHash, fingerprint, now), executors.blocking());
    }

    private Reservation reserveBlocking(
        RedeemRequest request,
        byte[] codeHash,
        String fingerprint,
        Instant now
    ) {
        try (Connection connection = database.connection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                Optional<UUID> idempotent = findUseByIdempotency(
                    connection, request.playerUuid(), request.idempotencyKey()
                );
                if (idempotent.isPresent()) {
                    RedeemResult existing = readResult(connection, idempotent.orElseThrow());
                    connection.commit();
                    connection.setAutoCommit(originalAutoCommit);
                    return Reservation.existing(existing);
                }

                CodeRow code = lockCode(connection, codeHash).orElseThrow(() -> new RedeemException(
                    "REDEEM_CODE_INVALID", "Redeem code does not exist", false
                ));
                validateCode(code, now);
                Optional<UUID> used = findUseByCodeAndPlayer(connection, code.codeId(), request.playerUuid());
                if (used.isPresent()) {
                    RedeemResult existing = readResult(connection, used.orElseThrow());
                    connection.commit();
                    connection.setAutoCommit(originalAutoCommit);
                    return Reservation.existing(existing);
                }

                UUID useId = UuidV7.create(now);
                List<StoredReward> rewards = rewards(connection, code.codeId(), useId, now);
                if (rewards.isEmpty()) {
                    throw new RedeemException("REDEEM_CODE_EMPTY", "Redeem code has no rewards", false);
                }
                insertUse(connection, useId, code.codeId(), request, now);
                insertDeliveries(connection, useId, rewards, now);
                incrementUse(connection, code.codeId());
                insertAudit(
                    connection,
                    "PLAYER",
                    request.playerUuid().toString(),
                    "REDEEM_CODE_CLAIMED",
                    "REDEEM_CODE",
                    fingerprint,
                    request.idempotencyKey(),
                    useId.toString(),
                    request.origin(),
                    JSON.createObjectNode().put("rewardCount", rewards.size()),
                    now
                );
                connection.commit();
                connection.setAutoCommit(originalAutoCommit);
                return Reservation.proceed(useId, rewards);
            } catch (SQLException | RuntimeException exception) {
                rollback(connection, exception);
                throw exception;
            }
        } catch (RedeemException exception) {
            throw exception;
        } catch (SQLException exception) {
            if ("23000".equals(exception.getSQLState())) {
                return readDuplicate(request);
            }
            throw new CompletionException("Unable to reserve redeem code", exception);
        }
    }

    private Reservation readDuplicate(RedeemRequest request) {
        try (Connection connection = database.connection()) {
            Optional<UUID> useId = findUseByIdempotency(
                connection, request.playerUuid(), request.idempotencyKey()
            );
            if (useId.isEmpty()) {
                throw new SQLException("Redeem reservation conflicted without an existing use");
            }
            return Reservation.existing(readResult(connection, useId.orElseThrow()));
        } catch (SQLException exception) {
            throw new CompletionException("Unable to resolve redeem code conflict", exception);
        }
    }

    CompletionStage<Boolean> beginDelivery(UUID deliveryId) {
        return updateDelivery(
            "UPDATE cct_reward_deliveries SET state = 'REQUESTED', attempts = attempts + 1 "
                + "WHERE delivery_id = ? AND state = 'PENDING'",
            deliveryId,
            null
        ).thenApply(rows -> rows == 1);
    }

    CompletionStage<Void> completeDelivery(UUID deliveryId, String externalReference) {
        return updateDelivery(
            "UPDATE cct_reward_deliveries SET state = 'COMPLETED', external_ref = ?, error_code = NULL "
                + "WHERE delivery_id = ? AND state = 'REQUESTED'",
            deliveryId,
            externalReference
        ).thenApply(ignored -> null);
    }

    CompletionStage<Void> failDelivery(UUID deliveryId, boolean ambiguous, String errorCode) {
        return CompletableFuture.runAsync(() -> {
            String sql = "UPDATE cct_reward_deliveries SET state = ?, error_code = ? "
                + "WHERE delivery_id = ? AND state = 'REQUESTED'";
            try (Connection connection = database.connection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, ambiguous ? "REVIEW_REQUIRED" : "FAILED");
                statement.setString(2, trim(errorCode, 64));
                statement.setBytes(3, UuidBinary.encode(deliveryId));
                statement.executeUpdate();
            } catch (SQLException exception) {
                throw new CompletionException("Unable to finish reward delivery", exception);
            }
        }, executors.blocking());
    }

    CompletionStage<RedeemResult> finishUse(UUID useId, Instant now) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = database.connection()) {
                List<RedeemRewardResult> rewards = readRewardResults(connection, useId);
                boolean allCompleted = !rewards.isEmpty() && rewards.stream()
                    .allMatch(reward -> reward.status().equals("COMPLETED"));
                boolean anyCompleted = rewards.stream().anyMatch(reward -> reward.status().equals("COMPLETED"));
                boolean anyUncertain = rewards.stream().anyMatch(reward ->
                    reward.status().equals("PENDING") || reward.status().equals("REQUESTED")
                        || reward.status().equals("REVIEW_REQUIRED")
                );
                String status = allCompleted ? "COMPLETED"
                    : (anyCompleted || anyUncertain ? "REVIEW_REQUIRED" : "FAILED");
                String error = rewards.stream().map(RedeemRewardResult::errorCode)
                    .filter(value -> value != null && !value.isBlank()).findFirst().orElse(null);
                try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE cct_redeem_uses SET state = ?, error_code = ? WHERE use_id = ?"
                )) {
                    update.setString(1, status);
                    update.setString(2, error);
                    update.setBytes(3, UuidBinary.encode(useId));
                    update.executeUpdate();
                }
                return new RedeemResult(useId, status, rewards, error);
            } catch (SQLException exception) {
                throw new CompletionException("Unable to finish redeem use", exception);
            }
        }, executors.blocking());
    }

    CompletionStage<Void> recoverInterruptedUses() {
        return CompletableFuture.runAsync(() -> {
            try (Connection connection = database.connection()) {
                boolean originalAutoCommit = connection.getAutoCommit();
                connection.setAutoCommit(false);
                try (PreparedStatement deliveries = connection.prepareStatement(
                         "UPDATE cct_reward_deliveries d JOIN cct_redeem_uses u ON u.use_id = d.use_id "
                             + "SET d.state = 'REVIEW_REQUIRED', d.error_code = 'REDEEM_INTERRUPTED' "
                             + "WHERE u.owner_node_id = ? AND d.state IN ('PENDING', 'REQUESTED')"
                     );
                     PreparedStatement uses = connection.prepareStatement(
                         "UPDATE cct_redeem_uses SET state = 'REVIEW_REQUIRED', "
                             + "error_code = 'REDEEM_INTERRUPTED' WHERE owner_node_id = ? AND state = 'CLAIMED'"
                     )) {
                    deliveries.setString(1, nodeId);
                    uses.setString(1, nodeId);
                    deliveries.executeUpdate();
                    uses.executeUpdate();
                    connection.commit();
                    connection.setAutoCommit(originalAutoCommit);
                } catch (SQLException exception) {
                    rollback(connection, exception);
                    throw exception;
                }
            } catch (SQLException exception) {
                throw new CompletionException("Unable to recover interrupted redeem uses", exception);
            }
        }, executors.blocking());
    }

    private CompletionStage<Integer> updateDelivery(String sql, UUID deliveryId, String externalReference) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = database.connection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                int index = 1;
                if (externalReference != null) {
                    statement.setString(index++, trim(externalReference, 80));
                }
                statement.setBytes(index, UuidBinary.encode(deliveryId));
                return statement.executeUpdate();
            } catch (SQLException exception) {
                throw new CompletionException("Unable to update reward delivery", exception);
            }
        }, executors.blocking());
    }

    private static void insertBatch(
        Connection connection,
        UUID batchId,
        GenerateRedeemCodesRequest request,
        int codeLength,
        Instant now
    ) throws SQLException {
        String sql = "INSERT INTO cct_redeem_batches(batch_id, code_count, code_length, "
            + "max_uses_per_code, valid_from, valid_until, creator, note, created_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setBytes(1, UuidBinary.encode(batchId));
            statement.setInt(2, request.count());
            statement.setInt(3, codeLength);
            statement.setInt(4, request.maxUsesPerCode());
            statement.setTimestamp(5, Timestamp.from(request.validFrom()));
            setInstant(statement, 6, request.validUntil());
            statement.setString(7, request.creator());
            statement.setString(8, request.note());
            statement.setTimestamp(9, Timestamp.from(now));
            statement.executeUpdate();
        }
    }

    private static void insertCode(
        Connection connection,
        UUID codeId,
        UUID batchId,
        byte[] hash,
        GenerateRedeemCodesRequest request,
        Instant now
    ) throws SQLException {
        String sql = "INSERT INTO cct_redeem_codes(code_id, batch_id, code_hash, max_uses, "
            + "valid_from, valid_until, state, note, created_at) VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setBytes(1, UuidBinary.encode(codeId));
            statement.setBytes(2, UuidBinary.encode(batchId));
            statement.setBytes(3, hash);
            statement.setInt(4, request.maxUsesPerCode());
            statement.setTimestamp(5, Timestamp.from(request.validFrom()));
            setInstant(statement, 6, request.validUntil());
            statement.setString(7, request.note());
            statement.setTimestamp(8, Timestamp.from(now));
            statement.executeUpdate();
        }
    }

    private static void insertRewards(
        Connection connection,
        UUID codeId,
        List<RedeemReward> rewards,
        Instant seed
    ) throws SQLException {
        String sql = "INSERT INTO cct_redeem_rewards(reward_id, code_id, ordinal, reward_type, "
            + "payload_version, payload_json, created_at) VALUES (?, ?, ?, ?, 1, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < rewards.size(); index++) {
                RedeemReward reward = rewards.get(index);
                statement.setBytes(1, UuidBinary.encode(UuidV7.create(seed.plusNanos(index))));
                statement.setBytes(2, UuidBinary.encode(codeId));
                statement.setInt(3, index);
                statement.setString(4, reward.type().name());
                statement.setString(5, payload(reward).toString());
                statement.setTimestamp(6, Timestamp.from(seed));
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static ObjectNode payload(RedeemReward reward) {
        ObjectNode payload = JSON.createObjectNode();
        if (reward.type() == RedeemRewardType.POINTS) {
            payload.put("points", reward.points());
        } else {
            payload.put("tierKey", reward.tierKey());
            payload.put("days", reward.days());
        }
        return payload;
    }

    private void insertUse(
        Connection connection,
        UUID useId,
        UUID codeId,
        RedeemRequest request,
        Instant now
    ) throws SQLException {
        String sql = "INSERT INTO cct_redeem_uses(use_id, code_id, player_uuid, owner_node_id, "
            + "idempotency_key, origin, state, created_at, updated_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, 'CLAIMED', ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setBytes(1, UuidBinary.encode(useId));
            statement.setBytes(2, UuidBinary.encode(codeId));
            statement.setBytes(3, UuidBinary.encode(request.playerUuid()));
            statement.setString(4, nodeId);
            statement.setString(5, request.idempotencyKey());
            statement.setString(6, request.origin());
            statement.setTimestamp(7, Timestamp.from(now));
            statement.setTimestamp(8, Timestamp.from(now));
            statement.executeUpdate();
        }
    }

    private static List<StoredReward> rewards(
        Connection connection,
        UUID codeId,
        UUID useId,
        Instant now
    ) throws SQLException {
        String sql = "SELECT reward_id, reward_type, payload_json FROM cct_redeem_rewards "
            + "WHERE code_id = ? ORDER BY ordinal";
        List<StoredReward> rewards = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setBytes(1, UuidBinary.encode(codeId));
            try (ResultSet result = statement.executeQuery()) {
                int index = 0;
                while (result.next()) {
                    UUID rewardId = UuidBinary.decode(result.getBytes("reward_id"));
                    rewards.add(new StoredReward(
                        UuidV7.create(now.plusNanos(50_000L + index++)),
                        rewardId,
                        parseReward(result.getString("reward_type"), result.getString("payload_json"))
                    ));
                }
            }
        }
        return rewards;
    }

    private static RedeemReward parseReward(String type, String payload) throws SQLException {
        try {
            JsonNode json = JSON.readTree(payload);
            return switch (RedeemRewardType.valueOf(type)) {
                case POINTS -> RedeemReward.points(json.path("points").asInt());
                case MEMBERSHIP -> RedeemReward.membership(
                    json.path("tierKey").asText(), json.path("days").asInt()
                );
            };
        } catch (RuntimeException | java.io.IOException exception) {
            throw new SQLException("Invalid persisted redeem reward", exception);
        }
    }

    private static void insertDeliveries(
        Connection connection,
        UUID useId,
        List<StoredReward> rewards,
        Instant now
    ) throws SQLException {
        String sql = "INSERT INTO cct_reward_deliveries(delivery_id, use_id, reward_id, "
            + "reward_type, state, created_at, updated_at) VALUES (?, ?, ?, ?, 'PENDING', ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (StoredReward reward : rewards) {
                statement.setBytes(1, UuidBinary.encode(reward.deliveryId()));
                statement.setBytes(2, UuidBinary.encode(useId));
                statement.setBytes(3, UuidBinary.encode(reward.rewardId()));
                statement.setString(4, reward.reward().type().name());
                statement.setTimestamp(5, Timestamp.from(now));
                statement.setTimestamp(6, Timestamp.from(now));
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static void incrementUse(Connection connection, UUID codeId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "UPDATE cct_redeem_codes SET used_uses = used_uses + 1 "
                + "WHERE code_id = ? AND state = 'ACTIVE' AND used_uses < max_uses"
        )) {
            statement.setBytes(1, UuidBinary.encode(codeId));
            if (statement.executeUpdate() != 1) {
                throw new RedeemException("REDEEM_CODE_EXHAUSTED", "Redeem code has no remaining uses", false);
            }
        }
    }

    private static Optional<CodeRow> lockCode(Connection connection, byte[] hash) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT code_id, state, used_uses, max_uses, valid_from, valid_until "
                + "FROM cct_redeem_codes WHERE code_hash = ? FOR UPDATE"
        )) {
            statement.setBytes(1, hash);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                Timestamp until = result.getTimestamp("valid_until");
                return Optional.of(new CodeRow(
                    UuidBinary.decode(result.getBytes("code_id")),
                    result.getString("state"),
                    result.getInt("used_uses"),
                    result.getInt("max_uses"),
                    result.getTimestamp("valid_from").toInstant(),
                    until == null ? null : until.toInstant()
                ));
            }
        }
    }

    private static void validateCode(CodeRow code, Instant now) {
        if (!"ACTIVE".equals(code.state())) {
            throw new RedeemException("REDEEM_CODE_DISABLED", "Redeem code is disabled", false);
        }
        if (now.isBefore(code.validFrom())) {
            throw new RedeemException("REDEEM_CODE_NOT_ACTIVE", "Redeem code is not active", false);
        }
        if (code.validUntil() != null && !now.isBefore(code.validUntil())) {
            throw new RedeemException("REDEEM_CODE_EXPIRED", "Redeem code has expired", false);
        }
        if (code.usedUses() >= code.maxUses()) {
            throw new RedeemException("REDEEM_CODE_EXHAUSTED", "Redeem code has no remaining uses", false);
        }
    }

    private static Optional<UUID> findUseByIdempotency(
        Connection connection,
        UUID playerUuid,
        String idempotencyKey
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT use_id FROM cct_redeem_uses WHERE player_uuid = ? AND idempotency_key = ?"
        )) {
            statement.setBytes(1, UuidBinary.encode(playerUuid));
            statement.setString(2, idempotencyKey);
            try (ResultSet result = statement.executeQuery()) {
                return result.next()
                    ? Optional.of(UuidBinary.decode(result.getBytes(1)))
                    : Optional.empty();
            }
        }
    }

    private static Optional<UUID> findUseByCodeAndPlayer(
        Connection connection,
        UUID codeId,
        UUID playerUuid
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT use_id FROM cct_redeem_uses WHERE code_id = ? AND player_uuid = ?"
        )) {
            statement.setBytes(1, UuidBinary.encode(codeId));
            statement.setBytes(2, UuidBinary.encode(playerUuid));
            try (ResultSet result = statement.executeQuery()) {
                return result.next()
                    ? Optional.of(UuidBinary.decode(result.getBytes(1)))
                    : Optional.empty();
            }
        }
    }

    private static RedeemResult readResult(Connection connection, UUID useId) throws SQLException {
        String status;
        String error;
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT state, error_code FROM cct_redeem_uses WHERE use_id = ?"
        )) {
            statement.setBytes(1, UuidBinary.encode(useId));
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("Redeem use does not exist");
                }
                status = result.getString("state");
                error = result.getString("error_code");
            }
        }
        return new RedeemResult(useId, status, readRewardResults(connection, useId), error);
    }

    private static List<RedeemRewardResult> readRewardResults(Connection connection, UUID useId)
        throws SQLException {
        String sql = "SELECT d.reward_type, d.state, d.error_code, r.payload_json "
            + "FROM cct_reward_deliveries d JOIN cct_redeem_rewards r ON r.reward_id = d.reward_id "
            + "WHERE d.use_id = ? ORDER BY r.ordinal";
        List<RedeemRewardResult> rewards = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setBytes(1, UuidBinary.encode(useId));
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    RedeemReward reward = parseReward(
                        result.getString("reward_type"), result.getString("payload_json")
                    );
                    rewards.add(new RedeemRewardResult(
                        reward.type(), description(reward), result.getString("state"),
                        result.getString("error_code")
                    ));
                }
            }
        }
        return List.copyOf(rewards);
    }

    private static String description(RedeemReward reward) {
        return reward.type() == RedeemRewardType.POINTS
            ? reward.points() + " 点券"
            : reward.tierKey() + " " + reward.days() + " 天";
    }

    private void insertAudit(
        Connection connection,
        String actorType,
        String actorId,
        String action,
        String targetType,
        String targetId,
        String requestId,
        String transactionId,
        String origin,
        JsonNode metadata,
        Instant now
    ) throws SQLException {
        String sql = "INSERT INTO cct_audit_logs(audit_id, actor_type, actor_id, action, target_type, "
            + "target_id, request_id, transaction_id, origin, metadata_json, created_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setBytes(1, UuidBinary.encode(UuidV7.create(now.plusNanos(90_000L))));
            statement.setString(2, actorType);
            statement.setString(3, actorId);
            statement.setString(4, action);
            statement.setString(5, targetType);
            statement.setString(6, targetId);
            statement.setString(7, requestId);
            statement.setString(8, transactionId);
            statement.setString(9, trim(origin, 32));
            statement.setString(10, metadata.toString());
            statement.setTimestamp(11, Timestamp.from(now));
            statement.executeUpdate();
        }
    }

    private static void setInstant(PreparedStatement statement, int index, Instant value) throws SQLException {
        if (value == null) {
            statement.setNull(index, java.sql.Types.TIMESTAMP);
        } else {
            statement.setTimestamp(index, Timestamp.from(value));
        }
    }

    private static String trim(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private static void rollback(Connection connection, Throwable original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    record GeneratedCode(String plainText, byte[] hash) {
    }

    record StoredReward(UUID deliveryId, UUID rewardId, RedeemReward reward) {
    }

    record Reservation(UUID useId, boolean proceed, List<StoredReward> rewards, RedeemResult existing) {
        static Reservation proceed(UUID useId, List<StoredReward> rewards) {
            return new Reservation(useId, true, List.copyOf(rewards), null);
        }

        static Reservation existing(RedeemResult result) {
            return new Reservation(result.useId(), false, List.of(), result);
        }
    }

    private record CodeRow(
        UUID codeId,
        String state,
        int usedUses,
        int maxUses,
        Instant validFrom,
        Instant validUntil
    ) {
    }
}
