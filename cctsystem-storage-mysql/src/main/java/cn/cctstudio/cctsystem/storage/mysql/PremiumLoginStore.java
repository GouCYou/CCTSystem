package cn.cctstudio.cctsystem.storage.mysql;

import cn.cctstudio.cctsystem.core.auth.PremiumLoginProof.Session;
import cn.cctstudio.cctsystem.core.config.PremiumLoginConfig;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class PremiumLoginStore {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final DatabaseAccess database;

    public PremiumLoginStore(DatabaseAccess database) {
        this.database = database;
    }

    public boolean hasBindingHistory(String name) throws SQLException {
        try (var connection = database.connection();
             var query = connection.prepareStatement(
                 "SELECT 1 FROM cct_java_login_rewards WHERE player_name = ?"
             )) {
            query.setString(1, name);
            try (var rows = query.executeQuery()) {
                return rows.next();
            }
        }
    }

    public Optional<UUID> linkedMojangUuid(String name) throws SQLException {
        try (var connection = database.connection();
             var query = connection.prepareStatement(
                 "SELECT player_name, mojang_uuid FROM cct_java_login_rewards WHERE player_name = ? AND unlinked_at IS NULL"
             )) {
            query.setString(1, name);
            try (var rows = query.executeQuery()) {
                return rows.next() && name.equals(rows.getString(1))
                    ? Optional.of(uuid(rows.getBytes(2))) : Optional.empty();
            }
        }
    }

    public boolean isLinked(UUID playerUuid) throws SQLException {
        try (var connection = database.connection();
             var query = connection.prepareStatement(
                 "SELECT 1 FROM cct_java_login_rewards WHERE player_uuid = ? AND unlinked_at IS NULL"
             )) {
            query.setBytes(1, bytes(playerUuid));
            try (var rows = query.executeQuery()) {
                return rows.next();
            }
        }
    }

    public boolean unlink(UUID playerUuid, String exactName) throws SQLException {
        try (var connection = database.connection();
             var update = connection.prepareStatement("""
                 UPDATE cct_java_login_rewards SET unlinked_at = ?
                 WHERE player_uuid = ? AND BINARY player_name = ? AND unlinked_at IS NULL
                 """)) {
            update.setLong(1, System.currentTimeMillis());
            update.setBytes(2, bytes(playerUuid));
            update.setString(3, exactName);
            return update.executeUpdate() == 1;
        }
    }

    public Optional<Claim> find(Session session) throws SQLException {
        try (var connection = database.connection();
             var query = connection.prepareStatement(
                 "SELECT * FROM cct_java_login_rewards WHERE mojang_uuid = ? AND player_uuid = ? AND unlinked_at IS NULL"
             )) {
            query.setBytes(1, bytes(session.mojangUuid()));
            query.setBytes(2, bytes(session.playerUuid()));
            try (var rows = query.executeQuery()) {
                return rows.next() ? Optional.of(read(rows)).filter(claim -> claim.name().equals(session.name()))
                    : Optional.empty();
            }
        }
    }

    public boolean link(Session session, long registeredAt, PremiumLoginConfig config) throws SQLException {
        if (session.bindingRequestedAt() <= 0) return false;
        var rewards = config.rewards();
        try (var connection = database.connection();
             var insert = connection.prepareStatement("""
                 INSERT INTO cct_java_login_rewards(
                     mojang_uuid, player_uuid, player_name, first_session, auth_registered_at, reward_server, membership_tier,
                     membership_days, points, commands_json, membership_status, points_status, commands_status
                 ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                 ON DUPLICATE KEY UPDATE unlinked_at = IF(
                     mojang_uuid = ? AND player_uuid = ? AND BINARY player_name = ?
                     AND auth_registered_at = ? AND unlinked_at < ?, NULL, unlinked_at
                 )
                 """)) {
            insert.setBytes(1, bytes(session.mojangUuid()));
            insert.setBytes(2, bytes(session.playerUuid()));
            insert.setString(3, session.name());
            insert.setBytes(4, bytes(session.sessionId()));
            insert.setLong(5, registeredAt);
            insert.setString(6, config.rewardServer());
            insert.setString(7, rewards.membershipDays() == 0 ? "" : rewards.membershipTier());
            insert.setInt(8, rewards.membershipDays());
            insert.setInt(9, rewards.points());
            insert.setString(10, JSON.writeValueAsString(rewards.commands()));
            insert.setString(11, initialStatus(rewards.membershipDays() == 0));
            insert.setString(12, initialStatus(rewards.points() == 0));
            insert.setString(13, initialStatus(rewards.commands().isEmpty()));
            insert.setBytes(14, bytes(session.mojangUuid()));
            insert.setBytes(15, bytes(session.playerUuid()));
            insert.setString(16, session.name());
            insert.setLong(17, registeredAt);
            insert.setLong(18, session.bindingRequestedAt());
            insert.executeUpdate();
        } catch (JsonProcessingException exception) {
            throw new SQLException("Unable to serialize Java login rewards", exception);
        }
        // A collision may be on the Mojang UUID, offline UUID or case-insensitive name.
        return find(session).filter(claim -> claim.registeredAt() == registeredAt).isPresent();
    }

    public List<Claim> pending(String serverId) throws SQLException {
        try (var connection = database.connection();
             var query = connection.prepareStatement("""
                 SELECT * FROM cct_java_login_rewards
                 WHERE reward_server = ? AND ready = TRUE AND (
                    points_status = 'PENDING' OR membership_status = 'COMPLETED'
                    OR EXISTS (SELECT 1 FROM qqbotauth_bindings qq
                      WHERE qq.minecraft_uuid = LOWER(BIN_TO_UUID(cct_java_login_rewards.player_uuid))
                        AND qq.verification_status = 'VERIFIED')
                    OR EXISTS (SELECT 1 FROM qqbotauth_discord_bindings discord
                      WHERE discord.minecraft_uuid = LOWER(BIN_TO_UUID(cct_java_login_rewards.player_uuid)))
                 )
                 ORDER BY created_at LIMIT 20
                 """)) {
            query.setString(1, serverId);
            try (var rows = query.executeQuery()) {
                List<Claim> claims = new ArrayList<>();
                while (rows.next()) claims.add(read(rows));
                return claims;
            }
        }
    }

    public boolean changeStatus(UUID mojangUuid, Step step, Status from, Status to) throws SQLException {
        String column = step.column;
        try (var connection = database.connection();
             var query = connection.prepareStatement(
                 "UPDATE cct_java_login_rewards SET " + column + " = ? WHERE mojang_uuid = ? AND " + column + " = ?"
             )) {
            query.setString(1, to.name());
            query.setBytes(2, bytes(mojangUuid));
            query.setString(3, from.name());
            return query.executeUpdate() == 1;
        }
    }

    private static String initialStatus(boolean disabled) {
        return disabled ? Status.COMPLETED.name() : Status.PENDING.name();
    }

    private static Claim read(ResultSet rows) throws SQLException {
        try {
            return new Claim(
                uuid(rows.getBytes("mojang_uuid")), uuid(rows.getBytes("player_uuid")),
                rows.getString("player_name"), uuid(rows.getBytes("first_session")),
                rows.getLong("auth_registered_at"), rows.getInt("points"),
                List.copyOf(JSON.readValue(rows.getString("commands_json"), new TypeReference<List<String>>() {})),
                status(rows, Step.MEMBERSHIP), status(rows, Step.POINTS), status(rows, Step.COMMANDS)
            );
        } catch (JsonProcessingException exception) {
            throw new SQLException("Unable to read Java login rewards", exception);
        }
    }

    private static Status status(ResultSet row, Step step) throws SQLException {
        return Status.valueOf(row.getString(step.column));
    }

    private static byte[] bytes(UUID uuid) {
        return ByteBuffer.allocate(16).putLong(uuid.getMostSignificantBits()).putLong(uuid.getLeastSignificantBits())
            .array();
    }

    private static UUID uuid(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    public enum Status { PENDING, RUNNING, COMPLETED, REVIEW }

    public enum Step {
        MEMBERSHIP("membership_status"), POINTS("points_status"), COMMANDS("commands_status");

        private final String column;

        Step(String column) {
            this.column = column;
        }
    }

    public record Claim(
        UUID mojangUuid, UUID playerUuid, String name, UUID firstSession, long registeredAt, int points,
        List<String> commands,
        Status membership, Status pointsDelivery, Status commandDelivery
    ) {
        public boolean complete() {
            return membership == Status.COMPLETED && pointsDelivery == Status.COMPLETED
                && commandDelivery == Status.COMPLETED;
        }
    }
}
