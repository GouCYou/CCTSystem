package cn.cctstudio.cctsystem.storage.mysql;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class PlayerMailbox {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<Reward>> REWARD_LIST = new TypeReference<>() {};
    private static final String ACTIVE = "automatic=FALSE AND (expires_at IS NULL OR expires_at>?)";
    private static final String CLAIMABLE = "claimed_at IS NULL AND JSON_LENGTH(rewards)>0";
    private static final String INBOX = "player_uuid=? AND " + ACTIVE + " AND (read_at IS NULL OR (" + CLAIMABLE + "))";
    private static final String SELECT_MAIL = """
        SELECT m.*,
          (SELECT COUNT(*) FROM cct_mail_attachments a WHERE a.mail_id=m.mail_id
              AND a.status IN ('PENDING','RUNNING')) AS pending,
          (SELECT COUNT(*) FROM cct_mail_attachments a WHERE a.mail_id=m.mail_id AND a.status='REVIEW') AS review
        FROM cct_player_mail m
        """;
    private final DatabaseAccess database;

    public PlayerMailbox(DatabaseAccess database) {
        this.database = database;
    }

    public enum RewardType { MYSTERY_BOX, MYSTERY_DUST, POINTS, SURVIVAL_COINS, MEMBERSHIP }

    public record Reward(RewardType type, int amount, String tier) {
        public Reward {
            if (type == null || amount < 1 || amount > 10_000_000
                || type == RewardType.MEMBERSHIP && (amount > 365 || tier == null || !tier.matches("[a-z0-9_-]{1,48}"))) {
                throw new IllegalArgumentException("奖励数量或会员等级无效。");
            }
            if (type != RewardType.MEMBERSHIP) tier = null;
        }

        public String display() {
            return switch (type) {
                case MYSTERY_BOX -> amount + " 个神秘宝库";
                case MYSTERY_DUST -> amount + " 神秘之尘";
                case POINTS -> amount + " 点券";
                case SURVIVAL_COINS -> amount + " 生存金币";
                case MEMBERSHIP -> tier.toUpperCase(Locale.ROOT).replace("_PLUS", "+") + " " + amount + " 天";
            };
        }
    }

    public record Draft(String title, String body, Long expiresAt, List<Reward> rewards) {
        public Draft {
            if (title == null || title.isBlank() || title.length() > 96 || body == null || body.length() > 1000
                || rewards == null || rewards.size() > 10) {
                throw new IllegalArgumentException("邮件标题、正文或附件超出允许范围。");
            }
            rewards = List.copyOf(rewards);
        }

        private void validateExpiry(long now) {
            if (expiresAt != null && (expiresAt <= now || expiresAt > now + 315_360_000_000L)) throw new IllegalArgumentException("过期时间须在未来十年以内。");
        }
    }

    public record Mail(UUID id, UUID playerUuid, Draft draft, String sender, long createdAt, Long claimedAt, Long readAt,
                       boolean automatic, int pending, int review) {}
    public record InboxPage(List<Mail> mails, int page, int totalPages, int totalMails) {}
    public record Attachment(UUID id, UUID playerUuid, Reward reward) {}
    public record LagSettings(boolean enabled, List<Reward> rewards) {
        public LagSettings {
            if (rewards == null || rewards.size() > 10 || enabled && rewards.isEmpty()) {
                throw new IllegalArgumentException("开启补偿时至少要配置一个奖励。");
            }
            rewards = List.copyOf(rewards);
        }
    }

    public Optional<UUID> resolve(String target) throws SQLException {
        UUID id;
        try {
            id = UUID.fromString(target);
        } catch (IllegalArgumentException ignored) {
            id = null;
        }
        try (var connection = database.connection();
             var query = connection.prepareStatement("SELECT player_uuid FROM cct_players WHERE "
                 + (id == null ? "normalized_name" : "player_uuid") + " = ?")) {
            if (id == null) query.setString(1, target.toLowerCase(Locale.ROOT));
            else query.setBytes(1, bytes(id));
            try (var rows = query.executeQuery()) {
                return rows.next() ? Optional.of(uuid(rows.getBytes(1))) : Optional.empty();
            }
        }
    }

    public UUID send(UUID id, UUID player, Draft draft, String sender) throws SQLException {
        long now = System.currentTimeMillis();
        draft.validateExpiry(now);
        transaction(connection -> {
            insert(connection, id, player, draft, sender, false, now);
            return null;
        });
        return id;
    }

    public boolean edit(UUID id, Draft draft) throws SQLException {
        long now = System.currentTimeMillis();
        draft.validateExpiry(now);
        return transaction(connection -> {
            try (var update = connection.prepareStatement("""
                    UPDATE cct_player_mail SET title=?, body=?, expires_at=?, rewards=?
                    WHERE mail_id=? AND claimed_at IS NULL AND automatic=FALSE
                        AND (read_at IS NULL OR JSON_LENGTH(rewards)>0)
                    """)) {
                setDraft(update, draft);
                update.setBytes(5, bytes(id));
                if (update.executeUpdate() != 1) return false;
            }
            try (var delete = connection.prepareStatement("DELETE FROM cct_mail_attachments WHERE mail_id=?")) {
                delete.setBytes(1, bytes(id));
                delete.executeUpdate();
            }
            insertAttachments(connection, id, draft.rewards(), now);
            return true;
        });
    }

    public Optional<Mail> read(UUID player, UUID id) throws SQLException {
        long now = System.currentTimeMillis();
        return transaction(connection -> {
            try (var update = connection.prepareStatement("""
                UPDATE cct_player_mail SET read_at=? WHERE player_uuid=? AND mail_id=? AND read_at IS NULL AND
                """ + ACTIVE)) {
                update.setLong(1, now);
                update.setBytes(2, bytes(player));
                update.setBytes(3, bytes(id));
                update.setLong(4, now);
                update.executeUpdate();
            }
            return findActive(connection, player, id, now);
        });
    }

    public Optional<Mail> claim(UUID player, UUID id) throws SQLException {
        long now = System.currentTimeMillis();
        return transaction(connection -> {
            try (var update = connection.prepareStatement("""
                UPDATE cct_player_mail SET claimed_at=?, read_at=COALESCE(read_at, ?)
                WHERE player_uuid=? AND mail_id=? AND
                """ + ACTIVE + " AND " + CLAIMABLE)) {
                update.setLong(1, now);
                update.setLong(2, now);
                update.setBytes(3, bytes(player));
                update.setBytes(4, bytes(id));
                update.setLong(5, now);
                if (update.executeUpdate() == 0) return Optional.empty();
            }
            return findActive(connection, player, id, now);
        });
    }

    public int claimAll(UUID player) throws SQLException {
        long now = System.currentTimeMillis();
        try (var connection = database.connection(); var update = connection.prepareStatement(
            "UPDATE cct_player_mail SET claimed_at=? WHERE player_uuid=? AND " + ACTIVE + " AND " + CLAIMABLE
        )) {
            update.setLong(1, now);
            update.setBytes(2, bytes(player));
            update.setLong(3, now);
            return update.executeUpdate();
        }
    }

    public int inboxCount(UUID player) throws SQLException {
        try (var connection = database.connection()) {
            return inboxCount(connection, player, System.currentTimeMillis());
        }
    }

    private static int inboxCount(Connection connection, UUID player, long now) throws SQLException {
        try (var query = connection.prepareStatement("SELECT COUNT(*) FROM cct_player_mail WHERE " + INBOX)) {
            query.setBytes(1, bytes(player));
            query.setLong(2, now);
            try (var rows = query.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    public InboxPage inbox(UUID player, int requestedPage, int size) throws SQLException {
        long now = System.currentTimeMillis();
        return transaction(connection -> {
            int count = inboxCount(connection, player, now);
            if (count == 0) return new InboxPage(List.of(), 0, 0, 0);
            int totalPages = (count - 1) / size + 1;
            int page = Math.clamp(requestedPage, 0, totalPages - 1);
            try (var query = connection.prepareStatement(
                SELECT_MAIL + "WHERE " + INBOX + " ORDER BY created_at DESC, mail_id DESC LIMIT ? OFFSET ?"
            )) {
                query.setBytes(1, bytes(player));
                query.setLong(2, now);
                query.setInt(3, size);
                query.setLong(4, (long) page * size);
                return new InboxPage(readMails(query), page, totalPages, count);
            }
        });
    }

    public List<Mail> list(UUID player, int page, int size) throws SQLException {
        String filter = player == null ? "" : "WHERE m.player_uuid=?";
        try (var connection = database.connection(); var query = connection.prepareStatement(
            SELECT_MAIL + filter + " ORDER BY created_at DESC, mail_id DESC LIMIT ? OFFSET ?"
        )) {
            int index = 1;
            if (player != null) query.setBytes(index++, bytes(player));
            query.setInt(index++, size);
            query.setLong(index, (long) page * size);
            return readMails(query);
        }
    }

    private static List<Mail> readMails(PreparedStatement query) throws SQLException {
        try (var rows = query.executeQuery()) {
            List<Mail> result = new ArrayList<>();
            while (rows.next()) result.add(read(rows));
            return result;
        }
    }

    private static Optional<Mail> findActive(Connection connection, UUID player, UUID id, long now) throws SQLException {
        try (var query = connection.prepareStatement(SELECT_MAIL + "WHERE player_uuid=? AND mail_id=? AND " + ACTIVE)) {
            query.setBytes(1, bytes(player));
            query.setBytes(2, bytes(id));
            query.setLong(3, now);
            try (var rows = query.executeQuery()) {
                return rows.next() ? Optional.of(read(rows)) : Optional.empty();
            }
        }
    }

    public List<Attachment> pending(Set<RewardType> types, Collection<UUID> onlineGadgets) throws SQLException {
        var standalone = types.stream().filter(type ->
            type != RewardType.MYSTERY_BOX && type != RewardType.MYSTERY_DUST).toList();
        if (standalone.isEmpty() && onlineGadgets.isEmpty()) return List.of();
        List<String> conditions = new ArrayList<>();
        if (!standalone.isEmpty()) conditions.add("a.reward_type IN (" + slots(standalone.size()) + ")");
        if (!onlineGadgets.isEmpty()) conditions.add("""
            (a.reward_type IN ('MYSTERY_BOX','MYSTERY_DUST') AND m.player_uuid IN (%s))
            """.formatted(slots(onlineGadgets.size())));
        try (var connection = database.connection(); var query = connection.prepareStatement("""
                SELECT a.*, m.player_uuid FROM cct_mail_attachments a
                JOIN cct_player_mail m ON m.mail_id=a.mail_id
                WHERE a.status='PENDING' AND m.claimed_at IS NOT NULL AND (%s)
                ORDER BY a.updated_at LIMIT 30
                """.formatted(String.join(" OR ", conditions)))) {
            int index = 1;
            for (var type : standalone) query.setString(index++, type.name());
            for (var player : onlineGadgets) query.setBytes(index++, bytes(player));
            try (var rows = query.executeQuery()) {
                List<Attachment> result = new ArrayList<>();
                while (rows.next()) result.add(new Attachment(uuid(rows.getBytes("attachment_id")),
                    uuid(rows.getBytes("player_uuid")), new Reward(RewardType.valueOf(rows.getString("reward_type")),
                        rows.getInt("amount"), rows.getString("membership_tier"))));
                return result;
            }
        }
    }

    public enum Status { PENDING, RUNNING, DELIVERED, REVIEW }

    public boolean change(UUID id, Status from, Status to) throws SQLException {
        try (var connection = database.connection(); var update = connection.prepareStatement("""
                UPDATE cct_mail_attachments SET status=?, updated_at=? WHERE attachment_id=? AND status=?
                """)) {
            update.setString(1, to.name());
            update.setLong(2, System.currentTimeMillis());
            update.setBytes(3, bytes(id));
            update.setString(4, from.name());
            return update.executeUpdate() == 1;
        }
    }

    public void flagInterrupted() throws SQLException {
        try (var connection = database.connection(); var update = connection.prepareStatement("""
                UPDATE cct_mail_attachments SET status='REVIEW' WHERE status='RUNNING' AND updated_at<?
                """)) {
            update.setLong(1, System.currentTimeMillis() - 3_600_000);
            update.executeUpdate();
        }
    }

    public LagSettings settings(LagSettings defaults) throws SQLException {
        try (var connection = database.connection()) {
            return lagSettings(connection, defaults, false);
        }
    }

    public void saveSettings(LagSettings settings) throws SQLException {
        try (var connection = database.connection(); var update = connection.prepareStatement(
            "UPDATE cct_lag_compensation SET enabled=?, rewards=? WHERE config_id=1"
        )) {
            if (settings == null) {
                update.setNull(1, Types.BOOLEAN);
                update.setNull(2, Types.VARCHAR);
            } else {
                update.setBoolean(1, settings.enabled());
                update.setString(2, json(settings.rewards()));
            }
            update.executeUpdate();
        }
    }

    public void compensate(Collection<UUID> affected, LagSettings defaults) throws SQLException {
        if (affected.isEmpty()) return;
        long now = System.currentTimeMillis();
        transaction(connection -> {
            LagSettings rules = lagSettings(connection, defaults, true);
            if (!rules.enabled()) return null;
            UUID incident;
            try (var query = connection.prepareStatement("SELECT incident_id FROM cct_lag_compensation WHERE config_id=1");
                 var rows = query.executeQuery()) {
                if (!rows.next()) throw new SQLException("Compensation settings row is missing");
                byte[] existing = rows.getBytes(1);
                incident = existing == null ? UUID.randomUUID() : uuid(existing);
                if (existing == null) {
                    try (var update = connection.prepareStatement("UPDATE cct_lag_compensation SET incident_id=? WHERE config_id=1")) {
                        update.setBytes(1, bytes(incident));
                        update.executeUpdate();
                    }
                }
            }
            Draft draft = new Draft("网络波动补偿", "你在游戏入口波动期间受到影响。", null, rules.rewards());
            for (UUID player : affected) {
                UUID id = UUID.nameUUIDFromBytes(("cct:lag:" + incident + ":" + player).getBytes(StandardCharsets.UTF_8));
                insert(connection, id, player, draft, "服务器", true, now);
            }
            return null;
        });
    }

    public void recovered() throws SQLException {
        try (var connection = database.connection();
             var update = connection.prepareStatement("UPDATE cct_lag_compensation SET incident_id=NULL WHERE config_id=1 AND incident_id IS NOT NULL")) {
            update.executeUpdate();
        }
    }

    public List<Mail> notices(Collection<UUID> online) throws SQLException {
        if (online.isEmpty()) return List.of();
        return transaction(connection -> {
            try (var query = connection.prepareStatement("""
                    SELECT m.*, 0 AS pending, 0 AS review FROM cct_player_mail m
                    WHERE automatic=TRUE AND notified=FALSE AND player_uuid IN (%s)
                    AND NOT EXISTS(SELECT 1 FROM cct_mail_attachments a WHERE a.mail_id=m.mail_id AND a.status<>'DELIVERED')
                    ORDER BY created_at LIMIT 50 FOR UPDATE
                    """.formatted(slots(online.size())))) {
                int index = 1;
                for (UUID player : online) query.setBytes(index++, bytes(player));
                List<Mail> result = new ArrayList<>();
                try (var rows = query.executeQuery()) {
                    while (rows.next()) result.add(read(rows));
                }
                try (var update = connection.prepareStatement("UPDATE cct_player_mail SET notified=TRUE WHERE mail_id=?")) {
                    for (Mail mail : result) {
                        update.setBytes(1, bytes(mail.id()));
                        update.addBatch();
                    }
                    if (!result.isEmpty()) update.executeBatch();
                }
                return result;
            }
        });
    }

    private static LagSettings lagSettings(Connection connection, LagSettings defaults, boolean lock) throws SQLException {
        try (var query = connection.prepareStatement("SELECT enabled,rewards FROM cct_lag_compensation WHERE config_id=1"
            + (lock ? " FOR UPDATE" : ""));
             var rows = query.executeQuery()) {
            if (!rows.next() || rows.getObject(1) == null) return defaults;
            return new LagSettings(rows.getBoolean(1), rewards(rows.getString(2)));
        }
    }

    private static void insert(Connection connection, UUID id, UUID player, Draft draft, String sender,
                               boolean auto, long now) throws SQLException {
        try (var insert = connection.prepareStatement("""
                INSERT INTO cct_player_mail(title,body,expires_at,rewards,mail_id,player_uuid,sender,created_at,claimed_at,automatic)
                VALUES(?,?,?,?,?,?,?,?,?,?)
                """)) {
            setDraft(insert, draft);
            insert.setBytes(5, bytes(id));
            insert.setBytes(6, bytes(player));
            insert.setString(7, sender);
            insert.setLong(8, now);
            if (auto) insert.setLong(9, now); else insert.setNull(9, Types.BIGINT);
            insert.setBoolean(10, auto);
            try {
                insert.executeUpdate();
            } catch (java.sql.SQLIntegrityConstraintViolationException conflict) {
                if (conflict.getErrorCode() == 1062) return;
                throw conflict;
            }
        }
        insertAttachments(connection, id, draft.rewards(), now);
    }

    private static void insertAttachments(Connection connection, UUID id, List<Reward> rewards, long now) throws SQLException {
        if (rewards.isEmpty()) return;
        try (var insert = connection.prepareStatement("""
                INSERT INTO cct_mail_attachments(attachment_id,mail_id,reward_type,amount,membership_tier,updated_at)
                VALUES(?,?,?,?,?,?)
                """)) {
            for (Reward reward : rewards) {
                insert.setBytes(1, bytes(UUID.randomUUID()));
                insert.setBytes(2, bytes(id));
                insert.setString(3, reward.type().name());
                insert.setInt(4, reward.amount());
                insert.setString(5, reward.tier());
                insert.setLong(6, now);
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    private static void setDraft(PreparedStatement statement, Draft draft) throws SQLException {
        statement.setString(1, draft.title());
        statement.setString(2, draft.body());
        if (draft.expiresAt() == null) statement.setNull(3, Types.BIGINT);
        else statement.setLong(3, draft.expiresAt());
        statement.setString(4, json(draft.rewards()));
    }

    private static Mail read(ResultSet rows) throws SQLException {
        return new Mail(uuid(rows.getBytes("mail_id")), uuid(rows.getBytes("player_uuid")),
            new Draft(rows.getString("title"), rows.getString("body"), rows.getObject("expires_at", Long.class),
                rewards(rows.getString("rewards"))), rows.getString("sender"), rows.getLong("created_at"),
            rows.getObject("claimed_at", Long.class), rows.getObject("read_at", Long.class), rows.getBoolean("automatic"),
            rows.getInt("pending"), rows.getInt("review"));
    }

    private static List<Reward> rewards(String json) throws SQLException {
        try {
            return JSON.readValue(json, REWARD_LIST);
        } catch (JsonProcessingException exception) {
            throw new SQLException("Invalid stored reward list", exception);
        }
    }

    private static String json(List<Reward> rewards) throws SQLException {
        try {
            return JSON.writeValueAsString(rewards);
        } catch (JsonProcessingException exception) {
            throw new SQLException("Unable to serialize reward list", exception);
        }
    }

    private <T> T transaction(SqlAction<T> action) throws SQLException {
        try (var connection = database.connection()) {
            connection.setAutoCommit(false);
            try {
                T result = action.run(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    private static String slots(int size) {
        return String.join(",", Collections.nCopies(size, "?"));
    }

    private static byte[] bytes(UUID value) {
        return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits()).array();
    }

    private static UUID uuid(byte[] bytes) {
        var buffer = ByteBuffer.wrap(bytes);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    @FunctionalInterface private interface SqlAction<T> { T run(Connection connection) throws SQLException; }
}
