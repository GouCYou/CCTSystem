package cn.cctstudio.cctsystem.identity;

import cn.cctstudio.cctsystem.core.concurrent.CctExecutors;
import cn.cctstudio.cctsystem.storage.mysql.DatabaseAccess;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

final class JdbcIdentityService implements IdentityService {
    private static final String UPSERT_PLAYER = """
        INSERT INTO cct_players(
            player_uuid, current_name, normalized_name, first_seen_at, last_seen_at
        ) VALUES (?, ?, ?, ?, ?)
        ON DUPLICATE KEY UPDATE
            current_name = VALUES(current_name),
            normalized_name = VALUES(normalized_name),
            last_seen_at = GREATEST(last_seen_at, VALUES(last_seen_at))
        """;
    private static final String LOCK_NAME_OWNER = """
        SELECT player_uuid
        FROM cct_players
        WHERE normalized_name = ?
        FOR UPDATE
        """;
    private static final String UPSERT_NAME_HISTORY = """
        INSERT INTO cct_player_name_history(
            player_uuid, player_name, normalized_name, first_used_at, last_used_at
        ) VALUES (?, ?, ?, ?, ?)
        ON DUPLICATE KEY UPDATE
            player_name = VALUES(player_name),
            last_used_at = GREATEST(last_used_at, VALUES(last_used_at))
        """;
    private static final String FIND_BY_NAME = """
        SELECT player_uuid, current_name
        FROM cct_players
        WHERE normalized_name = ?
        LIMIT 1
        """;

    private final DatabaseAccess database;
    private final CctExecutors executors;

    JdbcIdentityService(DatabaseAccess database, CctExecutors executors) {
        this.database = database;
        this.executors = executors;
    }

    @Override
    public CompletionStage<PlayerIdentity> recordSeen(UUID playerUuid, String displayName, Instant seenAt) {
        String validName = PlayerNames.requireValid(displayName);
        String normalizedName = PlayerNames.normalize(validName);
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = database.connection()) {
                boolean originalAutoCommit = connection.getAutoCommit();
                connection.setAutoCommit(false);
                try {
                    Timestamp timestamp = Timestamp.from(seenAt);
                    ensureNameOwner(connection, normalizedName, playerUuid);
                    try (PreparedStatement statement = connection.prepareStatement(UPSERT_PLAYER)) {
                        statement.setBytes(1, UuidBinary.encode(playerUuid));
                        statement.setString(2, validName);
                        statement.setString(3, normalizedName);
                        statement.setTimestamp(4, timestamp);
                        statement.setTimestamp(5, timestamp);
                        statement.executeUpdate();
                    }
                    try (PreparedStatement statement = connection.prepareStatement(UPSERT_NAME_HISTORY)) {
                        statement.setBytes(1, UuidBinary.encode(playerUuid));
                        statement.setString(2, validName);
                        statement.setString(3, normalizedName);
                        statement.setTimestamp(4, timestamp);
                        statement.setTimestamp(5, timestamp);
                        statement.executeUpdate();
                    }
                    connection.commit();
                    connection.setAutoCommit(originalAutoCommit);
                    return new PlayerIdentity(playerUuid, validName);
                } catch (SQLException exception) {
                    rollback(connection, exception);
                    throw exception;
                }
            } catch (SQLException exception) {
                throw new CompletionException("Unable to persist player identity", exception);
            }
        }, executors.blocking());
    }

    private static void ensureNameOwner(
        Connection connection,
        String normalizedName,
        UUID playerUuid
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(LOCK_NAME_OWNER)) {
            statement.setString(1, normalizedName);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next() && !UuidBinary.decode(result.getBytes(1)).equals(playerUuid)) {
                    throw new SQLException("Player name is already assigned to another identity", "23000");
                }
            }
        }
    }

    @Override
    public CompletionStage<Optional<PlayerIdentity>> findByName(String playerName) {
        String normalizedName = PlayerNames.normalize(playerName);
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = database.connection();
                 PreparedStatement statement = connection.prepareStatement(FIND_BY_NAME)) {
                statement.setString(1, normalizedName);
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(new PlayerIdentity(
                        UuidBinary.decode(result.getBytes("player_uuid")),
                        result.getString("current_name")
                    ));
                }
            } catch (SQLException exception) {
                throw new CompletionException("Unable to read player identity", exception);
            }
        }, executors.blocking());
    }

    private static void rollback(Connection connection, SQLException original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }
}
