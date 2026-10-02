package cn.cctstudio.cctsystem.platform.paper;

import cn.cctstudio.cctsystem.bridge.RpcHandlingException;
import cn.cctstudio.cctsystem.core.module.ModuleContext;
import cn.cctstudio.cctsystem.identity.UuidBinary;
import cn.cctstudio.cctsystem.points.PointsService;
import cn.cctstudio.cctsystem.points.PointsServiceProvider;
import cn.cctstudio.cctsystem.storage.mysql.DatabaseAccess;
import cn.cctstudio.cctsystem.storage.mysql.DatabaseProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Durable recipient snapshot and deterministic ledger IDs make retries safe, including offline players. */
final class BulkPointsAdmin {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final ModuleContext context;
    private final DatabaseAccess database;
    private final PointsService points;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile boolean stopped;
    private ScheduledFuture<?> timer;

    BulkPointsAdmin(ModuleContext context) {
        this.context = context;
        database = context.providers().find(DatabaseProvider.KEY).orElseThrow();
        points = context.providers().find(PointsServiceProvider.KEY).orElseThrow();
    }

    void start() {
        timer = context.executors().scheduler().scheduleWithFixedDelay(this::pump, 1, 5, TimeUnit.SECONDS);
    }
    void stop() {
        stopped = true;
        if (timer != null) timer.cancel(false);
    }

    CompletableFuture<JsonNode> create(UUID batch, int amount, String scope, String actor, String reason) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = database.connection()) {
                connection.setAutoCommit(false);
                try {
                    int inserted;
                    try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT IGNORE INTO cct_admin_points_batches(batch_id, points, scope, actor, reason, server_id)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """)) {
                        statement.setBytes(1, UuidBinary.encode(batch));
                        statement.setInt(2, amount);
                        statement.setString(3, scope);
                        statement.setString(4, actor);
                        statement.setString(5, reason);
                        statement.setString(6, context.config().serverId());
                        inserted = statement.executeUpdate();
                    }
                    if (inserted == 0) {
                        try (PreparedStatement existing = connection.prepareStatement(
                            "SELECT points, scope, actor, reason FROM cct_admin_points_batches WHERE batch_id = ?")) {
                            existing.setBytes(1, UuidBinary.encode(batch));
                            try (ResultSet row = existing.executeQuery()) {
                                if (!row.next() || row.getInt(1) != amount || !row.getString(2).equals(scope)
                                    || !row.getString(3).equals(actor) || !row.getString(4).equals(reason)) {
                                    throw new RpcHandlingException("POINTS_BATCH_CONFLICT", "Batch ID already used", false);
                                }
                            }
                        }
                    } else {
                        String bound = scope.equals("BOUND") ? """
                            WHERE EXISTS (SELECT 1 FROM qqbotauth_bindings q
                              WHERE LOWER(REPLACE(q.minecraft_uuid, '-', '')) = LOWER(HEX(p.player_uuid))
                                AND q.verification_status = 'VERIFIED')
                              OR EXISTS (SELECT 1 FROM qqbotauth_discord_bindings d
                              WHERE LOWER(REPLACE(d.minecraft_uuid, '-', '')) = LOWER(HEX(p.player_uuid)))
                            """ : "";
                        try (PreparedStatement recipients = connection.prepareStatement(
                            "INSERT INTO cct_admin_points_recipients(batch_id, player_uuid) SELECT ?, p.player_uuid FROM cct_players p " + bound)) {
                            recipients.setBytes(1, UuidBinary.encode(batch));
                            recipients.executeUpdate();
                        }
                    }
                    connection.commit();
                } catch (SQLException | RuntimeException failure) {
                    connection.rollback();
                    throw failure;
                }
                pump();
                return JSON.createObjectNode().put("batchId", batch.toString()).put("accepted", true);
            } catch (SQLException exception) {
                throw new RpcHandlingException("POINTS_BATCH_FAILED", "Unable to save points batch", true);
            }
        }, context.executors().blocking());
    }

    CompletableFuture<JsonNode> list() {
        return CompletableFuture.supplyAsync(() -> {
            ArrayNode items = JSON.createArrayNode();
            try (Connection connection = database.connection(); PreparedStatement statement = connection.prepareStatement("""
                SELECT b.*, COUNT(r.player_uuid) AS total,
                  COALESCE(SUM(r.status = 'PENDING'), 0) AS pending,
                  COALESCE(SUM(r.status = 'COMPLETED'), 0) AS completed,
                  COALESCE(SUM(r.status IN ('REJECTED', 'AMBIGUOUS')), 0) AS failed
                FROM cct_admin_points_batches b LEFT JOIN cct_admin_points_recipients r ON r.batch_id = b.batch_id
                GROUP BY b.batch_id, b.server_id, b.points, b.scope, b.actor, b.reason, b.created_at
                ORDER BY b.created_at DESC LIMIT 20
                """); ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    items.addObject().put("batchId", UuidBinary.decode(rows.getBytes("batch_id")).toString())
                        .put("points", rows.getInt("points")).put("scope", rows.getString("scope"))
                        .put("actor", rows.getString("actor")).put("reason", rows.getString("reason"))
                        .put("createdAt", rows.getTimestamp("created_at").toInstant().toString())
                        .put("total", rows.getInt("total")).put("pending", rows.getInt("pending"))
                        .put("completed", rows.getInt("completed")).put("failed", rows.getInt("failed"));
                }
                return JSON.createObjectNode().set("items", items);
            } catch (SQLException exception) {
                throw new RpcHandlingException("POINTS_BATCH_FAILED", "Unable to read points batches", true);
            }
        }, context.executors().blocking());
    }

    private void pump() {
        if (stopped || !running.compareAndSet(false, true)) return;
        CompletableFuture.runAsync(() -> {
            try {
                while (!stopped) {
                    UUID batch, player;
                    int amount;
                    try (Connection connection = database.connection(); PreparedStatement query = connection.prepareStatement("""
                        SELECT r.batch_id, r.player_uuid, b.points FROM cct_admin_points_recipients r
                        JOIN cct_admin_points_batches b ON b.batch_id = r.batch_id
                        WHERE r.status = 'PENDING' AND b.server_id = ? ORDER BY b.created_at LIMIT 1
                        """)) {
                        query.setString(1, context.config().serverId());
                        try (ResultSet row = query.executeQuery()) {
                        if (!row.next()) return;
                        batch = UuidBinary.decode(row.getBytes(1));
                        player = UuidBinary.decode(row.getBytes(2));
                        amount = row.getInt(3);
                        }
                    }
                    UUID operation = UUID.nameUUIDFromBytes(("admin-points:" + batch + ":" + player).getBytes(StandardCharsets.UTF_8));
                    var result = points.credit(player, amount, operation, "ADMIN_BULK", batch.toString()).toCompletableFuture().join();
                    try (Connection connection = database.connection(); PreparedStatement update = connection.prepareStatement(
                        "UPDATE cct_admin_points_recipients SET status = ?, error_code = ? WHERE batch_id = ? AND player_uuid = ?")) {
                        update.setString(1, result.disposition().name());
                        update.setString(2, result.errorCode());
                        update.setBytes(3, UuidBinary.encode(batch));
                        update.setBytes(4, UuidBinary.encode(player));
                        update.executeUpdate();
                    }
                }
            } catch (RuntimeException | SQLException failure) {
                context.logger().warn("Bulk point delivery paused; pending recipients will retry", failure);
            } finally {
                running.set(false);
            }
        }, context.executors().blocking());
    }
}
