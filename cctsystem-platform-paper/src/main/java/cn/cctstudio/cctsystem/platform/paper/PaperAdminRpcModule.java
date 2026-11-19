package cn.cctstudio.cctsystem.platform.paper;

import cn.cctstudio.cctsystem.bridge.BridgeProvider;
import cn.cctstudio.cctsystem.bridge.BridgeRpcCall;
import cn.cctstudio.cctsystem.bridge.BridgeRpcRouter;
import cn.cctstudio.cctsystem.bridge.RpcHandlingException;
import cn.cctstudio.cctsystem.contract.Capability;
import cn.cctstudio.cctsystem.contract.NodeRole;
import cn.cctstudio.cctsystem.contract.PlatformType;
import cn.cctstudio.cctsystem.core.module.CctModule;
import cn.cctstudio.cctsystem.core.module.ModuleContext;
import cn.cctstudio.cctsystem.core.module.ModuleDescriptor;
import cn.cctstudio.cctsystem.identity.UuidBinary;
import cn.cctstudio.cctsystem.points.PointsServiceProvider;
import cn.cctstudio.cctsystem.promotion.CreatePromotionRequest;
import cn.cctstudio.cctsystem.promotion.Promotion;
import cn.cctstudio.cctsystem.promotion.PromotionService;
import cn.cctstudio.cctsystem.promotion.PromotionServiceProvider;
import cn.cctstudio.cctsystem.membership.AdminMembershipAction;
import cn.cctstudio.cctsystem.membership.AdminMembershipRequest;
import cn.cctstudio.cctsystem.membership.MembershipException;
import cn.cctstudio.cctsystem.membership.MembershipService;
import cn.cctstudio.cctsystem.membership.MembershipServiceProvider;
import cn.cctstudio.cctsystem.redeem.GenerateRedeemCodesRequest;
import cn.cctstudio.cctsystem.redeem.GeneratedRedeemBatch;
import cn.cctstudio.cctsystem.redeem.RedeemCodeService;
import cn.cctstudio.cctsystem.redeem.RedeemCodeServiceProvider;
import cn.cctstudio.cctsystem.redeem.RedeemReward;
import cn.cctstudio.cctsystem.storage.mysql.DatabaseAccess;
import cn.cctstudio.cctsystem.storage.mysql.DatabaseProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import fr.xephi.authme.api.v3.AuthMeApi;
import fr.xephi.authme.api.v3.AuthMePlayer;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.NodeType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

final class PaperAdminRpcModule implements CctModule {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_LOG_BYTES = 256 * 1024;
    private static final ModuleDescriptor DESCRIPTOR = new ModuleDescriptor(
        "web-admin-paper",
        "admin",
        Set.of(PlatformType.PAPER),
        Set.of(
            NodeRole.AUTH_AUTHORITY,
            NodeRole.BUSINESS_AUTHORITY,
            NodeRole.POINTS_AUTHORITY,
            NodeRole.GAMEPLAY,
            NodeRole.ECONOMY_SOURCE
        ),
        Set.of(BridgeProvider.ID),
        Set.of(Capability.ADMIN_READ, Capability.ADMIN_MUTATE)
    );

    private final JavaPlugin plugin;
    private ModuleContext context;
    private BulkPointsAdmin bulkPoints;

    PaperAdminRpcModule(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public ModuleDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public CompletableFuture<Void> start(ModuleContext context) {
        this.context = context;
        BridgeRpcRouter router = context.providers().find(BridgeProvider.KEY).orElseThrow();
        router.registerCall("admin.redeem.generate", this::generateRedeemCodes);
        router.registerCall("admin.redeem.list", this::listRedeemData);
        router.registerCall("admin.reports.list", this::listReports);
        router.registerCall("admin.reports.update", this::updateReport);
        router.registerCall("admin.redstone.list", this::listRedstoneIncidents);
        router.registerCall("admin.chat-filter.list", this::listChatFilterIncidents);
        router.registerCall("admin.players.registered", this::listRegisteredPlayers);
        router.registerCall("admin.players.detail", this::playerDetail);
        router.registerCall("admin.players.membership", this::mutatePlayerMembership);
        router.registerCall("admin.server.logs", this::serverLogs);
        router.registerCall("admin.server.command", this::serverCommand);
        router.registerCall("admin.audit.record", this::recordAudit);
        router.registerCall("admin.promotions.list", this::listPromotions);
        router.registerCall("admin.promotions.mutate", this::mutatePromotion);
        router.registerCall("admin.discount-codes.list", call -> {
            requireStaff(objectPayload(call));
            return promotions().listCodes(Instant.now()).thenApply(items -> {
                ArrayNode array = JSON.createArrayNode(); items.forEach(item -> array.add(promotionJson(item)));
                return JSON.createObjectNode().set("items", array);
            });
        });
        router.registerCall("admin.discount-codes.mutate", this::mutateDiscountCode);
        cn.cctstudio.cctsystem.bridge.ChatFilterAdminRpc.register(router,
            () -> ((PaperBootstrap) plugin).chatRules(), context.executors().blocking());
        if (context.config().roles().contains(NodeRole.POINTS_AUTHORITY)
            && context.providers().find(PointsServiceProvider.KEY).isPresent()) {
            bulkPoints = new BulkPointsAdmin(context);
            bulkPoints.start();
        }
        router.registerCall("admin.points.grant", call -> {
            JsonNode payload = objectPayload(call);
            requireConsoleGroup(payload);
            if (bulkPoints == null) throw unavailable("POINTS_UNAVAILABLE", "Points authority is unavailable");
            UUID batchId = uuid(payload, "batchId");
            int points = integer(payload, "points", 1, 10_000_000);
            String scope = text(payload, "scope", 16);
            if (!Set.of("ALL", "BOUND").contains(scope)) throw invalid("Invalid points scope");
            return bulkPoints.create(batchId, points, scope, text(payload, "actorName", 64), text(payload, "reason", 255));
        });
        router.registerCall("admin.points.batches", call -> {
            requireStaff(objectPayload(call));
            if (bulkPoints == null) throw unavailable("POINTS_UNAVAILABLE", "Points authority is unavailable");
            return bulkPoints.list();
        });
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> stop() {
        if (bulkPoints != null) bulkPoints.stop();
        return CompletableFuture.completedFuture(null);
    }

    private PromotionService promotions() {
        return context.providers().find(PromotionServiceProvider.KEY)
            .orElseThrow(() -> unavailable("PROMOTION_UNAVAILABLE", "Promotion service is unavailable"));
    }

    private CompletionStage<JsonNode> listPromotions(BridgeRpcCall call) {
        requireStaff(objectPayload(call));
        return promotions().listCurrent(Instant.now()).thenApply(items -> {
            ArrayNode array = JSON.createArrayNode();
            items.forEach(item -> array.add(promotionJson(item)));
            return JSON.createObjectNode().set("items", array);
        });
    }

    private CompletionStage<JsonNode> mutatePromotion(BridgeRpcCall call) {
        JsonNode payload = objectPayload(call);
        requireStaff(payload);
        String actor = "web:" + text(payload, "actorName", 64);
        String action = text(payload, "action", 16);
        if (action.equals("STOP")) return promotions().stop(uuid(payload, "promotionId"), actor,
            "管理后台结束折扣", Instant.now()).thenApply(stopped -> JSON.createObjectNode().put("stopped", stopped));
        if (!action.equals("CREATE")) throw invalid("Invalid promotion action");
        String tier = optionalText(payload, "targetTierKey", 64, null);
        if (tier != null && context.config().membership().tiers().stream().noneMatch(value -> value.key().equals(tier))) {
            throw invalid("Invalid promotion tier");
        }
        Instant starts = optionalInstant(payload, "startsAt");
        Instant ends = optionalInstant(payload, "endsAt");
        if (starts == null || ends == null || !ends.isAfter(starts) || !ends.isAfter(Instant.now())) throw invalid("Invalid promotion dates");
        return promotions().create(new CreatePromotionRequest(text(payload, "name", 64), tier,
            integer(payload, "percentOffBps", 1, 10_000), starts, ends, 0, actor,
            "管理后台设置会员折扣")).thenApply(PaperAdminRpcModule::promotionJson);
    }

    private CompletionStage<JsonNode> mutateDiscountCode(BridgeRpcCall call) {
        JsonNode payload = objectPayload(call); requireStaff(payload);
        String action = text(payload,"action",16);
        if (action.equals("DELETE")) return promotions().deleteCode(uuid(payload,"promotionId"))
            .thenApply(deleted -> JSON.createObjectNode().put("deleted",deleted));
        if (!Set.of("CREATE","UPDATE").contains(action)) throw invalid("Invalid discount code action");
        String tier = optionalText(payload,"targetTierKey",64,null);
        if (tier != null && context.config().membership().tiers().stream().noneMatch(t -> t.key().equals(tier))) throw invalid("Invalid tier");
        Instant starts = optionalInstant(payload,"startsAt");
        Instant ends = optionalInstant(payload,"endsAt");
        if (starts == null) throw invalid("Invalid start date");
        if (ends == null) ends = Instant.parse("9999-12-31T00:00:00Z");
        if (!ends.isAfter(starts)) throw invalid("Invalid end date");
        CreatePromotionRequest request = new CreatePromotionRequest(text(payload,"name",64),tier,
            integer(payload,"percentOffBps",1,10000),starts,ends,0,"web:"+text(payload,"actorName",64),
            "管理折扣代码",text(payload,"discountCode",32),payload.path("maxUsesPerPlayer").isNull() || payload.path("maxUsesPerPlayer").isMissingNode() ? null : integer(payload,"maxUsesPerPlayer",1,1),
            payload.path("maxPlayers").isNull() || payload.path("maxPlayers").isMissingNode() ? null : integer(payload,"maxPlayers",1,1000000));
        return (action.equals("UPDATE") ? promotions().updateCode(uuid(payload,"promotionId"),request) : promotions().create(request))
            .handle((item,failure) -> {
                if (failure != null) {
                    Throwable cause=failure; while(cause.getCause()!=null) cause=cause.getCause();
                    if (cause instanceof IllegalArgumentException) throw new RpcHandlingException(
                        "DISCOUNT_CODE_DUPLICATE".equals(cause.getMessage()) ? "DISCOUNT_CODE_DUPLICATE" : "DISCOUNT_CODE_INVALID", "DISCOUNT_CODE_DUPLICATE".equals(cause.getMessage()) ? "Discount code already exists" : "Discount code cannot be saved",false);
                    throw new java.util.concurrent.CompletionException(failure);
                }
                return promotionJson(item);
            });
    }

    private static ObjectNode promotionJson(Promotion item) {
        ObjectNode result = JSON.createObjectNode().put("promotionId", item.promotionId().toString())
            .put("name", item.name()).put("percentOffBps", item.percentOffBps())
            .put("startsAt", item.startsAt().toString()).put("endsAt", item.endsAt().toString()).put("status", item.status());
        putNullable(result, "targetTierKey", item.targetTierKey());
        putNullable(result, "discountCode", item.discountCode());
        if (item.discountCode() != null) {
            result.put("exhausted",item.status().equals("EXHAUSTED")); result.put("maxUsesPerPlayer",item.maxUsesPerPlayer()); result.put("maxPlayers",item.maxPlayers()); result.put("usedPlayers",item.usedPlayers());
            Instant now = Instant.now();
            result.put("status", !item.status().equals("ACTIVE") || !item.endsAt().isAfter(now) || item.maxPlayers()!=null && item.usedPlayers()>=item.maxPlayers() ? "EXPIRED" : item.startsAt().isAfter(now) ? "SCHEDULED" : "ACTIVE");
            if (item.endsAt().equals(Instant.parse("9999-12-31T00:00:00Z"))) result.putNull("endsAt");
        }
        return result;
    }

    private CompletionStage<JsonNode> generateRedeemCodes(BridgeRpcCall call) {
        JsonNode payload = objectPayload(call);
        requireStaff(payload);
        RedeemCodeService service = context.providers().find(RedeemCodeServiceProvider.KEY)
            .orElseThrow(() -> unavailable("REDEEM_ADMIN_UNAVAILABLE", "Redeem service is unavailable"));
        GenerateRedeemCodesRequest request = new GenerateRedeemCodesRequest(
            integer(payload, "count", 1, 1_000),
            integer(payload, "maxUsesPerCode", 1, 1_000_000),
            Instant.now(),
            optionalInstant(payload, "validUntil"),
            "web:" + text(payload, "actorName", 64),
            optionalText(payload, "note", 255, ""),
            parseRewards(payload.path("rewards"))
        );
        return service.generate(request).thenApply(batch -> generatedBatchJson(batch, request));
    }

    private CompletionStage<JsonNode> listRedeemData(BridgeRpcCall call) {
        JsonNode payload = objectPayload(call);
        requireStaff(payload);
        String section = optionalText(payload, "section", 16, "batches");
        int page = integer(payload, "page", 1, 100_000);
        int pageSize = integer(payload, "pageSize", 1, 100);
        return databaseTask(database -> switch (section) {
            case "batches" -> redeemBatches(database, page, pageSize);
            case "uses" -> redeemUses(database, page, pageSize);
            default -> throw invalid("Unknown redeem section");
        });
    }

    private CompletionStage<JsonNode> listReports(BridgeRpcCall call) {
        JsonNode payload = objectPayload(call);
        requireStaff(payload);
        int page = integer(payload, "page", 1, 100_000);
        int pageSize = integer(payload, "pageSize", 1, 100);
        String status = optionalText(payload, "status", 24, "ALL").toUpperCase(Locale.ROOT);
        String query = optionalText(payload, "query", 80, "").toLowerCase(Locale.ROOT);
        if (!Set.of("ALL", "OPEN", "IN_PROGRESS", "CLOSED", "FALSE", "DELETED").contains(status)) {
            throw invalid("Invalid report status");
        }
        return CompletableFuture.supplyAsync(
            () -> reports(page, pageSize, status, query),
            context.executors().blocking()
        );
    }

    private CompletionStage<JsonNode> updateReport(BridgeRpcCall call) {
        JsonNode payload = objectPayload(call);
        requireStaff(payload);
        long reportId = longInteger(payload, "reportId", 1, Long.MAX_VALUE);
        String status = text(payload, "status", 24).toUpperCase(Locale.ROOT);
        if (!Set.of("OPEN", "IN_PROGRESS", "CLOSED", "FALSE").contains(status)) {
            throw invalid("Invalid report status");
        }
        String actorName = text(payload, "actorName", 64);
        String actorUuid = text(payload, "actorUuid", 36);
        return CompletableFuture.supplyAsync(
            () -> updatePlayerReport(reportId, status, actorName, actorUuid),
            context.executors().blocking()
        );
    }

    private CompletionStage<JsonNode> listRedstoneIncidents(BridgeRpcCall call) {
        JsonNode payload = objectPayload(call);
        requireStaff(payload);
        return databaseTask(database -> redstoneIncidents(
            database,
            integer(payload, "page", 1, 100_000),
            integer(payload, "pageSize", 1, 100)
        ));
    }

    private CompletionStage<JsonNode> listChatFilterIncidents(BridgeRpcCall call) {
        JsonNode payload = objectPayload(call);
        requireStaff(payload);
        int page = integer(payload, "page", 1, 100_000);
        int pageSize = integer(payload, "pageSize", 1, 100);
        String search = optionalText(payload, "query", 200, "");
        String server = optionalText(payload, "server", 64, "");
        String word = optionalText(payload, "word", 200, "");
        String from = optionalText(payload, "from", 40, "");
        String to = optionalText(payload, "to", 40, "");
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        if (!search.isEmpty()) { where.append(" AND (LOCATE(?,player_name)>0 OR LOCATE(?,original_text)>0)"); args.add(search); args.add(search); }
        if (!server.isEmpty()) { where.append(" AND server_id=?"); args.add(server); }
        if (!word.isEmpty()) { where.append(" AND LOCATE(?,CAST(matched_words AS CHAR))>0"); args.add(word); }
        try {
            if (!from.isEmpty()) { where.append(" AND detected_at>=?"); args.add(cn.cctstudio.cctsystem.storage.mysql.BeijingDateTime.fromInstant(Instant.parse(from))); }
            if (!to.isEmpty()) { where.append(" AND detected_at<=?"); args.add(cn.cctstudio.cctsystem.storage.mysql.BeijingDateTime.fromInstant(Instant.parse(to))); }
        } catch (java.time.format.DateTimeParseException failure) { throw invalid("Invalid chat filter date"); }
        return databaseTask(database -> {
            try (Connection connection = database.connection()) {
                long total;
                try (PreparedStatement count = connection.prepareStatement("SELECT COUNT(*) FROM cct_chat_filter_incidents" + where)) {
                    for (int i = 0; i < args.size(); i++) count.setObject(i + 1, args.get(i));
                    try (ResultSet rows = count.executeQuery()) { rows.next(); total = rows.getLong(1); }
                }
                ObjectNode root = pageRoot(page, pageSize, total);
                ArrayNode items = root.putArray("items");
                try (PreparedStatement query = connection.prepareStatement("SELECT * FROM cct_chat_filter_incidents" + where + " ORDER BY detected_at DESC, incident_id DESC LIMIT ? OFFSET ?")) {
                    for (int i = 0; i < args.size(); i++) query.setObject(i + 1, args.get(i));
                    query.setInt(args.size() + 1, pageSize);
                    query.setInt(args.size() + 2, (page - 1) * pageSize);
                    try (ResultSet rows = query.executeQuery()) {
                        while (rows.next()) {
                            ObjectNode item = items.addObject();
                            item.put("incidentId", UuidBinary.decode(rows.getBytes("incident_id")).toString());
                            item.put("playerUuid", UuidBinary.decode(rows.getBytes("player_uuid")).toString());
                            item.put("playerName", rows.getString("player_name"));
                            item.put("serverId", rows.getString("server_id"));
                            item.put("sourceType", rows.getString("source_type"));
                            item.put("originalText", rows.getString("original_text"));
                            item.put("filteredText", rows.getString("filtered_text"));
                            try { item.set("matchedWords", JSON.readTree(rows.getString("matched_words"))); }
                            catch (IOException failure) { item.putArray("matchedWords"); }
                            putBeijingInstant(item, "detectedAt", rows, "detected_at");
                        }
                    }
                }
                return root;
            }
        });
    }

    private CompletionStage<JsonNode> listRegisteredPlayers(BridgeRpcCall call) {
        JsonNode payload = objectPayload(call);
        requireStaff(payload);
        int page = integer(payload, "page", 1, 100_000);
        int pageSize = integer(payload, "pageSize", 1, 100);
        String query = optionalText(payload, "query", 36, "");
        if (!query.isEmpty() && !query.matches("[A-Za-z0-9_-]{1,36}")) {
            throw invalid("Invalid player query");
        }
        return databaseTask(database -> registeredPlayers(database, page, pageSize, query))
            .thenCompose(this::enrichPlayerGroups);
    }

    private CompletionStage<JsonNode> playerDetail(BridgeRpcCall call) {
        JsonNode payload = objectPayload(call);
        requireStaff(payload);
        UUID playerUuid = uuid(payload, "playerUuid");
        String playerName = text(payload, "playerName", 64);
        return databaseTask(database -> {
            ObjectNode root = registeredPlayers(database, 1, 1, playerUuid.toString());
            ArrayNode items = (ArrayNode) root.path("items");
            if (items.isEmpty()) {
                ObjectNode item = items.addObject();
                item.put("playerUuid", playerUuid.toString());
                item.put("playerName", playerName);
                item.put("online", false);
                item.putNull("serverId");
                for (String key : List.of("registeredAt", "lastSeenAt", "membershipTier", "membershipExpiresAt",
                    "lastLoginIp", "lastLoginLocation")) item.putNull(key);
            }
            return root;
        }).thenCompose(this::enrichPlayerGroups).thenApply(root -> root.path("items").get(0));
    }

    private CompletionStage<JsonNode> mutatePlayerMembership(BridgeRpcCall call) {
        JsonNode payload = objectPayload(call);
        requireStaff(payload);
        UUID playerUuid = uuid(payload, "playerUuid");
        AdminMembershipAction action;
        try {
            action = AdminMembershipAction.valueOf(text(payload, "action", 32));
        } catch (IllegalArgumentException exception) {
            throw invalid("Invalid membership action");
        }
        if (!Set.of(
            AdminMembershipAction.GRANT,
            AdminMembershipAction.EXTEND,
            AdminMembershipAction.REMOVE
        ).contains(action)) {
            throw invalid("Invalid membership action");
        }
        String tierKey = text(payload, "tierKey", 64).toLowerCase(Locale.ROOT);
        int days = payload.path("days").asInt(-1);
        if (!tierKey.matches("[a-z0-9][a-z0-9_-]{1,63}")
            || (action != AdminMembershipAction.REMOVE && (days < 1 || days > 365))) {
            throw invalid("Invalid membership values");
        }
        MembershipService service = context.providers().find(MembershipServiceProvider.KEY)
            .orElseThrow(() -> unavailable(
                "MEMBERSHIP_ADMIN_UNAVAILABLE", "Membership service is unavailable"
            ));
        return mapMembershipErrors(luckPerms().getUserManager().loadUser(playerUuid).thenCompose(user -> {
            if (isStaff(user)) {
                throw new RpcHandlingException(
                    "STAFF_TARGET_FORBIDDEN", "Staff accounts cannot be changed here", false
                );
            }
            return service.admin(new AdminMembershipRequest(
                action,
                playerUuid,
                tierKey,
                action == AdminMembershipAction.REMOVE ? 0 : days,
                null,
                text(payload, "actor", 80),
                text(payload, "reason", 255)
            ));
        }).thenApply(summary -> JSON.createObjectNode()
            .put("updated", true)
            .put("playerUuid", playerUuid.toString())
            .put("action", action.name())));
    }

    private CompletionStage<JsonNode> serverLogs(BridgeRpcCall call) {
        JsonNode payload = objectPayload(call);
        requireStaff(payload);
        int lineLimit = integer(payload, "lines", 20, 500);
        return CompletableFuture.supplyAsync(() -> {
            try {
                ObjectNode result = JSON.createObjectNode();
                result.put("serverId", context.config().serverId());
                ArrayNode lines = result.putArray("lines");
                tail(Path.of("logs", "latest.log"), lineLimit).forEach(lines::add);
                return result;
            } catch (IOException exception) {
                throw unavailable("SERVER_LOG_UNAVAILABLE", "Server log is unavailable");
            }
        }, context.executors().blocking());
    }

    private CompletionStage<JsonNode> serverCommand(BridgeRpcCall call) {
        JsonNode payload = objectPayload(call);
        requireConsoleGroup(payload);
        String command = command(payload);
        return context.platformTasks().callMain(() -> {
            boolean accepted = plugin.getServer().dispatchCommand(
                plugin.getServer().getConsoleSender(), command
            );
            return JSON.createObjectNode()
                .put("serverId", context.config().serverId())
                .put("accepted", accepted);
        });
    }

    private CompletionStage<JsonNode> recordAudit(BridgeRpcCall call) {
        JsonNode payload = objectPayload(call);
        requireStaff(payload);
        UUID actorUuid = uuid(payload, "actorUuid");
        String actorName = text(payload, "actorName", 64);
        String actorGroup = text(payload, "actorGroup", 32);
        String action = text(payload, "action", 64);
        String target = optionalText(payload, "target", 255, "");
        String outcome = optionalText(payload, "outcome", 24, "SUCCESS");
        JsonNode detail = payload.path("detail");
        return databaseTask(database -> {
            try (Connection connection = database.connection();
                 PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO cct_admin_audit(
                        audit_id, actor_uuid, actor_name, actor_group, action_type,
                        target_ref, detail_json, outcome, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(3))
                    """)) {
                insert.setBytes(1, UuidBinary.encode(UUID.randomUUID()));
                insert.setBytes(2, UuidBinary.encode(actorUuid));
                insert.setString(3, actorName);
                insert.setString(4, actorGroup);
                insert.setString(5, action);
                insert.setString(6, target);
                insert.setString(7, detail.isMissingNode() ? "{}" : detail.toString());
                insert.setString(8, outcome);
                insert.executeUpdate();
            }
            return JSON.createObjectNode().put("recorded", true);
        });
    }

    private ObjectNode redeemBatches(DatabaseAccess database, int page, int pageSize)
        throws SQLException {
        ObjectNode root = pageRoot(page, pageSize, count(database,
            "SELECT COUNT(*) FROM cct_redeem_batches"));
        ArrayNode items = root.putArray("items");
        try (Connection connection = database.connection();
             PreparedStatement query = connection.prepareStatement("""
                SELECT b.batch_id, b.code_count, b.code_length, b.max_uses_per_code,
                       b.valid_from, b.valid_until, b.creator, b.note, b.created_at,
                       COALESCE(SUM(c.used_uses), 0) AS used_uses,
                       COALESCE(SUM(c.max_uses), 0) AS max_uses
                FROM cct_redeem_batches b
                LEFT JOIN cct_redeem_codes c ON c.batch_id = b.batch_id
                GROUP BY b.batch_id, b.code_count, b.code_length, b.max_uses_per_code,
                         b.valid_from, b.valid_until, b.creator, b.note, b.created_at
                ORDER BY b.created_at DESC LIMIT ? OFFSET ?
                """)) {
            query.setInt(1, pageSize);
            query.setInt(2, (page - 1) * pageSize);
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) {
                    ObjectNode item = items.addObject();
                    item.put("batchId", UuidBinary.decode(result.getBytes("batch_id")).toString());
                    item.put("codeCount", result.getInt("code_count"));
                    item.put("codeLength", result.getInt("code_length"));
                    item.put("maxUsesPerCode", result.getInt("max_uses_per_code"));
                    putBeijingInstant(item, "validFrom", result, "valid_from");
                    putBeijingInstant(item, "validUntil", result, "valid_until");
                    item.put("creator", result.getString("creator"));
                    item.put("note", result.getString("note"));
                    putBeijingInstant(item, "createdAt", result, "created_at");
                    item.put("usedUses", result.getLong("used_uses"));
                    item.put("maxUses", result.getLong("max_uses"));
                }
            }
        }
        return root;
    }

    private ObjectNode redeemUses(DatabaseAccess database, int page, int pageSize)
        throws SQLException {
        ObjectNode root = pageRoot(page, pageSize, count(database,
            "SELECT COUNT(*) FROM cct_redeem_uses"));
        ArrayNode items = root.putArray("items");
        try (Connection connection = database.connection();
             PreparedStatement query = connection.prepareStatement("""
                SELECT u.use_id, u.code_id, u.player_uuid, u.origin, u.state,
                       u.error_code, u.created_at, u.updated_at,
                       HEX(SUBSTRING(c.code_hash, 1, 6)) AS code_fingerprint
                FROM cct_redeem_uses u JOIN cct_redeem_codes c ON c.code_id = u.code_id
                ORDER BY u.created_at DESC LIMIT ? OFFSET ?
                """)) {
            query.setInt(1, pageSize);
            query.setInt(2, (page - 1) * pageSize);
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) {
                    ObjectNode item = items.addObject();
                    item.put("useId", UuidBinary.decode(result.getBytes("use_id")).toString());
                    item.put("codeId", UuidBinary.decode(result.getBytes("code_id")).toString());
                    item.put("codeFingerprint", result.getString("code_fingerprint"));
                    item.put("playerUuid", UuidBinary.decode(result.getBytes("player_uuid")).toString());
                    item.put("origin", result.getString("origin"));
                    item.put("state", result.getString("state"));
                    putNullable(item, "errorCode", result.getString("error_code"));
                    putBeijingInstant(item, "createdAt", result, "created_at");
                    putBeijingInstant(item, "updatedAt", result, "updated_at");
                }
            }
        }
        return root;
    }

    private ObjectNode reports(int page, int pageSize, String status, String query) {
        try {
            Object storage = playerReportStorage();
            @SuppressWarnings("unchecked")
            List<Object> allReports = (List<Object>) storage.getClass()
                .getMethod("getReports").invoke(storage);
            List<Object> filtered = allReports.stream()
                .filter(entry -> status.equals("ALL")
                    || status.equals(invokeString(method(entry, "status"), entry)))
                .filter(entry -> query.isEmpty() || List.of(
                    "reporter", "reporterUuid", "target", "targetUuid", "reason"
                ).stream().map(field -> invokeString(method(entry, field), entry))
                    .anyMatch(value -> value.toLowerCase(Locale.ROOT).contains(query)))
                .toList();
            ObjectNode root = pageRoot(page, pageSize, filtered.size());
            ArrayNode items = root.putArray("items");
            int from = Math.min((page - 1) * pageSize, filtered.size());
            int to = Math.min(from + pageSize, filtered.size());
            for (Object entry : filtered.subList(from, to)) {
                Class<?> entryType = entry.getClass();
                Method statusMethod = entryType.getMethod("status");
                ObjectNode item = items.addObject();
                item.put("id", Long.parseLong(invokeString(entryType.getMethod("id"), entry)));
                for (String field : List.of(
                    "reporter", "reporterUuid", "target", "targetUuid", "reason", "timestamp",
                    "handler", "handlerUuid", "claimedAt", "reporterLocation", "targetLocation"
                )) {
                    item.put(field, invokeString(entryType.getMethod(field), entry));
                }
                item.put("status", invokeString(statusMethod, entry));
            }
            return root;
        } catch (RpcHandlingException exception) {
            throw exception;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            throw unavailable("REPORT_QUERY_FAILED", "PlayerReport data could not be read");
        }
    }

    private ObjectNode updatePlayerReport(
        long reportId,
        String status,
        String actorName,
        String actorUuid
    ) {
        try {
            Object storage = playerReportStorage();
            Method simpleStatus = java.util.Arrays.stream(storage.getClass().getMethods())
                .filter(method -> method.getName().equals("setStatus")
                    && method.getParameterCount() == 2)
                .findFirst()
                .orElseThrow(NoSuchMethodException::new);
            Class<?> statusType = simpleStatus.getParameterTypes()[1];
            @SuppressWarnings({"rawtypes", "unchecked"})
            Object reportStatus = Enum.valueOf((Class<? extends Enum>) statusType, status);
            boolean updated;
            if (status.equals("OPEN")) {
                updated = (Boolean) simpleStatus.invoke(
                    storage, String.valueOf(reportId), reportStatus
                );
                if (updated) {
                    storage.getClass().getMethod("clearHandler", String.class)
                        .invoke(storage, String.valueOf(reportId));
                }
            } else {
                updated = (Boolean) storage.getClass().getMethod(
                    "setStatus", String.class, statusType,
                    String.class, String.class, boolean.class
                ).invoke(storage, String.valueOf(reportId), reportStatus, actorName, actorUuid, true);
                if (updated && status.equals("FALSE")) {
                    storage.getClass().getMethod(
                        "setHandler", String.class, String.class, String.class
                    ).invoke(storage, String.valueOf(reportId), actorName, actorUuid);
                }
            }
            if (!updated) throw unavailable("REPORT_NOT_FOUND", "Report does not exist");
            return JSON.createObjectNode().put("reportId", reportId).put("status", status);
        } catch (RpcHandlingException exception) {
            throw exception;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            throw unavailable("REPORT_UPDATE_FAILED", "PlayerReport status could not be updated");
        }
    }

    private Object playerReportStorage() throws ReflectiveOperationException {
        Plugin playerReport = plugin.getServer().getPluginManager().getPlugin("PlayerReport");
        if (playerReport == null || !playerReport.isEnabled()) {
            throw unavailable("REPORT_PLUGIN_UNAVAILABLE", "PlayerReport is unavailable");
        }
        Field field = playerReport.getClass().getDeclaredField("reportStorage");
        field.setAccessible(true);
        Object storage = field.get(playerReport);
        if (storage == null) throw unavailable("REPORT_PLUGIN_UNAVAILABLE", "PlayerReport is unavailable");
        return storage;
    }

    private static String invokeString(Method method, Object target) {
        try {
            Object value = method.invoke(target);
            return value == null ? "" : String.valueOf(value);
        } catch (ReflectiveOperationException exception) {
            throw unavailable("REPORT_QUERY_FAILED", "PlayerReport data could not be read");
        }
    }

    private static Method method(Object target, String name) {
        try {
            return target.getClass().getMethod(name);
        } catch (NoSuchMethodException exception) {
            throw unavailable("REPORT_QUERY_FAILED", "PlayerReport data could not be read");
        }
    }

    private ObjectNode redstoneIncidents(DatabaseAccess database, int page, int pageSize)
        throws SQLException {
        ObjectNode root = pageRoot(page, pageSize, count(database,
            "SELECT COUNT(*) FROM cct_redstone_incidents"));
        ArrayNode items = root.putArray("items");
        try (Connection connection = database.connection();
             PreparedStatement query = connection.prepareStatement("""
                SELECT incident_id, server_id, world_name, block_x, block_y, block_z,
                       nearby_players, detected_at
                FROM cct_redstone_incidents ORDER BY detected_at DESC LIMIT ? OFFSET ?
                """)) {
            query.setInt(1, pageSize);
            query.setInt(2, (page - 1) * pageSize);
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) {
                    ObjectNode item = items.addObject();
                    item.put("incidentId", UuidBinary.decode(result.getBytes("incident_id")).toString());
                    item.put("serverId", result.getString("server_id"));
                    item.put("world", result.getString("world_name"));
                    item.put("x", result.getInt("block_x"));
                    item.put("y", result.getInt("block_y"));
                    item.put("z", result.getInt("block_z"));
                    item.put("nearbyPlayers", result.getString("nearby_players"));
                    putInstant(item, "detectedAt", result, "detected_at");
                }
            }
        }
        return root;
    }

    private ObjectNode registeredPlayers(
        DatabaseAccess database,
        int page,
        int pageSize,
        String search
    ) throws SQLException {
        UUID exactUuid = parseUuid(search);
        String where = search.isEmpty() ? ""
            : exactUuid == null ? " WHERE p.normalized_name LIKE ?" : " WHERE p.player_uuid = ?";
        long total;
        try (Connection connection = database.connection();
             PreparedStatement count = connection.prepareStatement(
                 "SELECT COUNT(*) FROM cct_players p" + where
             )) {
            bindPlayerSearch(count, exactUuid, search);
            try (ResultSet result = count.executeQuery()) {
                result.next();
                total = result.getLong(1);
            }
        }
        ObjectNode root = pageRoot(page, pageSize, total);
        ArrayNode items = root.putArray("items");
        try (Connection connection = database.connection();
             PreparedStatement query = connection.prepareStatement("""
                SELECT p.player_uuid, p.current_name, p.first_seen_at, p.last_seen_at,
                       e.tier_key AS membership_tier, e.expires_at AS membership_expires_at,
                       lp.primary_group
                FROM cct_players p
                LEFT JOIN cct_player_membership_state s ON s.player_uuid = p.player_uuid
                LEFT JOIN cct_membership_entitlements e ON e.entitlement_id = s.active_entitlement_id
                LEFT JOIN luckperms_players lp
                  ON LOWER(REPLACE(lp.uuid, '-', '')) = LOWER(HEX(p.player_uuid))
                """ + where + " ORDER BY CASE LOWER(COALESCE(lp.primary_group, 'default'))"
                + " WHEN 'owner' THEN 0 WHEN 'admin' THEN 1 WHEN 'mod' THEN 2"
                + " WHEN 'helper' THEN 3 WHEN 'mvpp' THEN 4 WHEN 'mvp_plus' THEN 4"
                + " WHEN 'mvp' THEN 5 WHEN 'vipp' THEN 6 WHEN 'vip_plus' THEN 6"
                + " WHEN 'vip' THEN 7 ELSE 8 END, p.current_name ASC LIMIT ? OFFSET ?")) {
            int index = bindPlayerSearch(query, exactUuid, search);
            query.setInt(index++, pageSize);
            query.setInt(index, (page - 1) * pageSize);
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) {
                    ObjectNode item = items.addObject();
                    item.put("playerUuid", UuidBinary.decode(result.getBytes("player_uuid")).toString());
                    item.put("playerName", result.getString("current_name"));
                    item.put("online", false);
                    item.putNull("serverId");
                    putBeijingInstant(item, "registeredAt", result, "first_seen_at");
                    putBeijingInstant(item, "lastSeenAt", result, "last_seen_at");
                    putNullable(item, "membershipTier", result.getString("membership_tier"));
                    putInstant(item, "membershipExpiresAt", result, "membership_expires_at");
                    item.put("group", result.getString("primary_group") == null
                        ? "default" : result.getString("primary_group").toLowerCase(Locale.ROOT));
                    item.put("staff", false);
                    item.putNull("lastLoginIp");
                    item.putNull("lastLoginLocation");
                }
            }
        }
        return root;
    }

    private CompletionStage<JsonNode> enrichPlayerGroups(JsonNode result) {
        if (!(result instanceof ObjectNode root) || !(root.path("items") instanceof ArrayNode items)) {
            return CompletableFuture.failedFuture(unavailable(
                "PLAYER_LIST_INVALID", "Player list could not be read"
            ));
        }
        LuckPerms luckPerms = luckPerms();
        List<CompletableFuture<Void>> lookups = new ArrayList<>();
        for (JsonNode value : items) {
            ObjectNode item = (ObjectNode) value;
            UUID playerUuid = UUID.fromString(item.path("playerUuid").asText());
            lookups.add(luckPerms.getUserManager().loadUser(playerUuid).thenAccept(user -> {
                String group = displayGroup(user);
                item.put("group", group);
                item.put("staff", isStaff(user));
                enrichAuthMe(item);
            }));
        }
        return CompletableFuture.allOf(lookups.toArray(CompletableFuture[]::new))
            .thenApply(ignored -> root);
    }

    private void enrichAuthMe(ObjectNode item) {
        try {
            AuthMePlayer player = AuthMeApi.getInstance()
                .getPlayerInfo(item.path("playerName").asText()).orElse(null);
            if (player == null) return;
            item.put("registeredAt", player.getRegistrationDate().toString());
            player.getLastLoginDate().ifPresent(value -> item.put("lastSeenAt", value.toString()));
            player.getLastLoginIpAddress().filter(value -> !value.isBlank())
                .ifPresentOrElse(
                    value -> item.put("lastLoginIp", value),
                    () -> item.putNull("lastLoginIp")
                );
        } catch (RuntimeException ignored) {
            // The registered-player RPC is routed to the AuthMe node. Keep base data if unavailable.
        }
    }

    private static CompletionStage<JsonNode> mapMembershipErrors(
        CompletionStage<? extends JsonNode> operation
    ) {
        CompletableFuture<JsonNode> mapped = new CompletableFuture<>();
        operation.whenComplete((value, failure) -> {
            if (failure == null) {
                mapped.complete(value);
                return;
            }
            Throwable cause = failure;
            while (cause instanceof CompletionException && cause.getCause() != null) {
                cause = cause.getCause();
            }
            if (cause instanceof MembershipException membership) {
                mapped.completeExceptionally(new RpcHandlingException(
                    membership.code(), membership.getMessage(), membership.retryable()
                ));
            } else {
                mapped.completeExceptionally(cause);
            }
        });
        return mapped;
    }

    private LuckPerms luckPerms() {
        LuckPerms luckPerms = plugin.getServer().getServicesManager().load(LuckPerms.class);
        if (luckPerms == null) {
            throw unavailable("LUCKPERMS_UNAVAILABLE", "LuckPerms is unavailable");
        }
        return luckPerms;
    }

    private static boolean isStaff(User user) {
        if (isStaffGroup(user.getPrimaryGroup())) return true;
        return user.getNodes(NodeType.INHERITANCE).stream()
            .filter(node -> node.getValue() && !node.hasExpired())
            .anyMatch(node -> isStaffGroup(node.getGroupName()));
    }

    private static String displayGroup(User user) {
        for (String staff : List.of("owner", "admin", "mod", "helper")) {
            if (user.getPrimaryGroup().equalsIgnoreCase(staff)
                || user.getNodes(NodeType.INHERITANCE).stream()
                    .filter(node -> node.getValue() && !node.hasExpired())
                    .anyMatch(node -> node.getGroupName().equalsIgnoreCase(staff))) {
                return staff;
            }
        }
        return user.getPrimaryGroup().toLowerCase(Locale.ROOT);
    }

    private static boolean isStaffGroup(String group) {
        return Set.of("owner", "admin", "mod", "helper").contains(group.toLowerCase(Locale.ROOT));
    }

    private static UUID parseUuid(String value) {
        try {
            return value.length() == 36 ? UUID.fromString(value) : null;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static int bindPlayerSearch(
        PreparedStatement statement,
        UUID exactUuid,
        String search
    ) throws SQLException {
        if (search.isEmpty()) return 1;
        if (exactUuid == null) statement.setString(1, "%" + search.toLowerCase(Locale.ROOT) + "%");
        else statement.setBytes(1, UuidBinary.encode(exactUuid));
        return 2;
    }

    private CompletionStage<JsonNode> databaseTask(DatabaseOperation operation) {
        DatabaseAccess database = context.providers().find(DatabaseProvider.KEY)
            .orElseThrow(() -> unavailable("ADMIN_DATABASE_UNAVAILABLE", "Admin database is unavailable"));
        return CompletableFuture.supplyAsync(() -> {
            try {
                return operation.run(database);
            } catch (RpcHandlingException exception) {
                throw exception;
            } catch (SQLException exception) {
                throw unavailable("ADMIN_DATABASE_FAILED", "Admin database request failed");
            }
        }, context.executors().blocking());
    }

    private static ObjectNode generatedBatchJson(GeneratedRedeemBatch batch, GenerateRedeemCodesRequest request) {
        ObjectNode root = JSON.createObjectNode();
        root.put("batchId", batch.batchId().toString());
        root.put("maxUsesPerCode", request.maxUsesPerCode());
        root.put("validFrom", request.validFrom().toString());
        if (request.validUntil() == null) root.putNull("validUntil");
        else root.put("validUntil", request.validUntil().toString());
        ArrayNode codes = root.putArray("codes");
        batch.codes().forEach(codes::add);
        return root;
    }

    private static List<RedeemReward> parseRewards(JsonNode rewards) {
        if (!rewards.isArray() || rewards.isEmpty() || rewards.size() > 16) {
            throw invalid("Invalid redeem rewards");
        }
        List<RedeemReward> result = new ArrayList<>();
        for (JsonNode reward : rewards) {
            String type = text(reward, "type", 24).toUpperCase(Locale.ROOT);
            result.add(switch (type) {
                case "POINTS" -> RedeemReward.points(integer(reward, "points", 1, 10_000_000));
                case "MEMBERSHIP" -> RedeemReward.membership(
                    text(reward, "tierKey", 64), integer(reward, "days", 1, 3_650)
                );
                default -> throw invalid("Invalid redeem reward type");
            });
        }
        return List.copyOf(result);
    }

    private static List<String> tail(Path path, int lineLimit) throws IOException {
        if (!Files.isRegularFile(path)) return List.of();
        long size = Files.size(path);
        int bytes = (int) Math.min(size, MAX_LOG_BYTES);
        ByteBuffer buffer = ByteBuffer.allocate(bytes);
        try (SeekableByteChannel channel = Files.newByteChannel(path, StandardOpenOption.READ)) {
            channel.position(Math.max(0, size - bytes));
            while (buffer.hasRemaining() && channel.read(buffer) >= 0) {
                // Read the selected tail.
            }
        }
        buffer.flip();
        String[] lines = StandardCharsets.UTF_8.decode(buffer).toString()
            .replaceAll("\\u001B\\[[;\\d]*m", "").split("\\R");
        int from = Math.max(size > bytes ? 1 : 0, lines.length - lineLimit);
        return List.of(lines).subList(Math.min(from, lines.length), lines.length);
    }

    private static String command(JsonNode payload) {
        String command = text(payload, "command", 512).trim();
        while (command.startsWith("/")) command = command.substring(1);
        if (command.isBlank() || command.contains("\n") || command.contains("\r")) {
            throw invalid("Invalid server command");
        }
        return command;
    }

    private static void requireStaff(JsonNode payload) {
        String group = text(payload, "actorGroup", 32).toLowerCase(Locale.ROOT);
        if (!Set.of("owner", "admin", "mod").contains(group)) {
            throw new RpcHandlingException("ADMIN_FORBIDDEN", "Admin access is forbidden", false);
        }
    }

    private static void requireConsoleGroup(JsonNode payload) {
        String group = text(payload, "actorGroup", 32).toLowerCase(Locale.ROOT);
        if (!Set.of("owner", "admin").contains(group)) {
            throw new RpcHandlingException("ADMIN_CONSOLE_FORBIDDEN", "Console access is forbidden", false);
        }
    }

    private static ObjectNode pageRoot(int page, int pageSize, long total) {
        return JSON.createObjectNode()
            .put("page", page)
            .put("pageSize", pageSize)
            .put("totalItems", total)
            .put("totalPages", Math.max(1L, (total + pageSize - 1L) / pageSize));
    }

    private static long count(DatabaseAccess database, String sql) throws SQLException {
        try (Connection connection = database.connection();
             PreparedStatement query = connection.prepareStatement(sql);
             ResultSet result = query.executeQuery()) {
            result.next();
            return result.getLong(1);
        }
    }

    private static void putBeijingInstant(ObjectNode target, String field, ResultSet source, String column)
        throws SQLException {
        LocalDateTime value = source.getObject(column, LocalDateTime.class);
        if (value == null) target.putNull(field);
        else target.put(field, cn.cctstudio.cctsystem.storage.mysql.BeijingDateTime.toInstant(value).toString());
    }

    private static void putInstant(ObjectNode target, String field, ResultSet source, String column)
        throws SQLException {
        LocalDateTime value = source.getObject(column, LocalDateTime.class);
        if (value == null) target.putNull(field);
        else target.put(field, value.toInstant(ZoneOffset.UTC).toString());
    }

    private static void putNullable(ObjectNode target, String field, String value) {
        if (value == null) target.putNull(field); else target.put(field, value);
    }

    private static JsonNode objectPayload(BridgeRpcCall call) {
        if (call.payload() == null || !call.payload().isObject()) throw invalid("Invalid admin request");
        return call.payload();
    }

    private static String text(JsonNode payload, String field, int max) {
        JsonNode value = payload.path(field);
        if (!value.isTextual() || value.textValue().isBlank() || value.textValue().length() > max) {
            throw invalid("Invalid field: " + field);
        }
        return value.textValue().trim();
    }

    private static String optionalText(JsonNode payload, String field, int max, String fallback) {
        JsonNode value = payload.path(field);
        if (value.isMissingNode() || value.isNull()) return fallback;
        if (!value.isTextual() || value.textValue().length() > max) throw invalid("Invalid field: " + field);
        return value.textValue().trim();
    }

    private static int integer(JsonNode payload, String field, int min, int max) {
        int value = payload.path(field).asInt(Integer.MIN_VALUE);
        if (value < min || value > max) throw invalid("Invalid field: " + field);
        return value;
    }

    private static long longInteger(JsonNode payload, String field, long min, long max) {
        long value = payload.path(field).asLong(Long.MIN_VALUE);
        if (value < min || value > max) throw invalid("Invalid field: " + field);
        return value;
    }

    private static UUID uuid(JsonNode payload, String field) {
        try {
            return UUID.fromString(text(payload, field, 36));
        } catch (IllegalArgumentException exception) {
            throw invalid("Invalid field: " + field);
        }
    }

    private static Instant optionalInstant(JsonNode payload, String field) {
        JsonNode value = payload.path(field);
        if (value.isMissingNode() || value.isNull() || value.asText().isBlank()) return null;
        try {
            return Instant.parse(value.asText());
        } catch (RuntimeException exception) {
            throw invalid("Invalid field: " + field);
        }
    }

    private static RpcHandlingException invalid(String message) {
        return new RpcHandlingException("ADMIN_REQUEST_INVALID", message, false);
    }

    private static RpcHandlingException unavailable(String code, String message) {
        return new RpcHandlingException(code, message, true);
    }

    @FunctionalInterface
    private interface DatabaseOperation {
        JsonNode run(DatabaseAccess database) throws SQLException;
    }
}
