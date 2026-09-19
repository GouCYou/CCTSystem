package cn.cctstudio.cctsystem.platform.velocity;

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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.velocitypowered.api.proxy.ProxyServer;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

final class VelocityAdminRpcModule implements CctModule {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_LOG_BYTES = 256 * 1024;
    private static final ModuleDescriptor DESCRIPTOR = new ModuleDescriptor(
        "web-admin-velocity",
        "admin",
        Set.of(PlatformType.VELOCITY),
        Set.of(NodeRole.NETWORK_AUTHORITY),
        Set.of(BridgeProvider.ID),
        Set.of(Capability.ADMIN_READ, Capability.ADMIN_MUTATE)
    );

    private final ProxyServer proxy;
    private final Path serverRoot;
    private ModuleContext context;

    VelocityAdminRpcModule(ProxyServer proxy, Path dataDirectory) {
        this.proxy = proxy;
        Path plugins = dataDirectory.getParent();
        this.serverRoot = plugins == null || plugins.getParent() == null
            ? Path.of(".") : plugins.getParent();
    }

    @Override
    public ModuleDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public CompletableFuture<Void> start(ModuleContext context) {
        this.context = context;
        BridgeRpcRouter router = context.providers().find(BridgeProvider.KEY).orElseThrow();
        router.registerCall("admin.punishments.list", this::listPunishments);
        router.registerCall("admin.punishments.execute", this::executePunishment);
        router.registerCall("admin.server.logs", this::serverLogs);
        router.registerCall("admin.server.command", this::serverCommand);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> stop() {
        return CompletableFuture.completedFuture(null);
    }

    private CompletionStage<JsonNode> listPunishments(BridgeRpcCall call) {
        JsonNode payload = objectPayload(call);
        requireStaff(payload);
        String type = optionalText(payload, "type", 16, "bans");
        if (!type.equals("bans") && !type.equals("kicks")) throw invalid("Invalid punishment type");
        int page = integer(payload, "page", 1, 100_000);
        int pageSize = integer(payload, "pageSize", 1, 100);
        return CompletableFuture.supplyAsync(
            () -> punishmentPage(type, page, pageSize),
            context.executors().blocking()
        );
    }

    private CompletionStage<JsonNode> executePunishment(BridgeRpcCall call) {
        JsonNode payload = objectPayload(call);
        requireStaff(payload);
        String action = text(payload, "action", 16).toUpperCase(Locale.ROOT);
        String player = text(payload, "player", 36);
        if (!player.matches("[A-Za-z0-9_]{3,16}")
            && !player.matches("[0-9a-fA-F-]{36}")) {
            throw invalid("Invalid player identifier");
        }
        String actor = text(payload, "actorName", 64);
        String reason = optionalText(payload, "reason", 200, "违反服务器规则");
        String annotatedReason = "[Web:" + actor + "] " + reason;
        String command = switch (action) {
            case "BAN" -> "litebans:ban " + player + " " + annotatedReason;
            case "TEMPBAN" -> "litebans:tempban " + player + " "
                + duration(payload) + " " + annotatedReason;
            case "UNBAN" -> "litebans:unban " + player + " " + annotatedReason;
            case "KICK" -> "litebans:kick " + player + " " + annotatedReason;
            default -> throw invalid("Invalid punishment action");
        };
        return proxy.getCommandManager().executeAsync(proxy.getConsoleCommandSource(), command)
            .thenApply(accepted -> JSON.createObjectNode()
                .put("action", action)
                .put("player", player)
                .put("accepted", accepted));
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
                tail(serverRoot.resolve("logs/latest.log"), lineLimit).forEach(lines::add);
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
        return proxy.getCommandManager().executeAsync(proxy.getConsoleCommandSource(), command)
            .thenApply(accepted -> JSON.createObjectNode()
                .put("serverId", context.config().serverId())
                .put("accepted", accepted));
    }

    private ObjectNode punishmentPage(String type, int page, int pageSize) {
        try {
            Class<?> databaseClass = liteBansClass("litebans.api.Database");
            Object database = databaseClass.getMethod("get").invoke(null);
            if (database == null) throw unavailable("LITEBANS_UNAVAILABLE", "LiteBans is unavailable");
            Method prepare = databaseClass.getMethod("prepareStatement", String.class);
            long total;
            try (PreparedStatement count = (PreparedStatement) prepare.invoke(
                    database, "SELECT COUNT(*) FROM litebans_" + type);
                 ResultSet result = count.executeQuery()) {
                result.next();
                total = result.getLong(1);
            }
            ObjectNode root = pageRoot(page, pageSize, total);
            ArrayNode items = root.putArray("items");
            try (PreparedStatement ids = (PreparedStatement) prepare.invoke(
                    database, "SELECT id FROM litebans_" + type + " ORDER BY id DESC LIMIT ? OFFSET ?")) {
                ids.setInt(1, pageSize);
                ids.setInt(2, (page - 1) * pageSize);
                try (ResultSet rows = ids.executeQuery()) {
                    Method getEntry = databaseClass.getMethod(
                        type.equals("bans") ? "getBan" : "getKick", long.class, String.class
                    );
                    while (rows.next()) {
                        Object entry = getEntry.invoke(database, rows.getLong(1), "*");
                        if (entry != null) items.add(entryJson(databaseClass, database, entry));
                    }
                }
            }
            return root;
        } catch (RpcHandlingException exception) {
            throw exception;
        } catch (ReflectiveOperationException | java.sql.SQLException exception) {
            throw unavailable("LITEBANS_QUERY_FAILED", "LiteBans history could not be read");
        }
    }

    private static ObjectNode entryJson(Class<?> databaseClass, Object database, Object entry)
        throws ReflectiveOperationException {
        Class<?> type = entry.getClass();
        ObjectNode item = JSON.createObjectNode();
        item.put("id", ((Number) type.getMethod("getId").invoke(entry)).longValue());
        item.put("type", String.valueOf(type.getMethod("getType").invoke(entry)));
        String uuid = nullable(type.getMethod("getUuid").invoke(entry));
        putNullable(item, "playerUuid", uuid);
        String name = null;
        if (uuid != null && !uuid.isBlank()) {
            try {
                name = (String) databaseClass.getMethod("getPlayerName", UUID.class)
                    .invoke(database, UUID.fromString(uuid));
            } catch (IllegalArgumentException ignored) {
                name = null;
            }
        }
        putNullable(item, "playerName", name);
        putNullable(item, "reason", nullable(type.getMethod("getReason").invoke(entry)));
        putNullable(item, "executorName", nullable(type.getMethod("getExecutorName").invoke(entry)));
        item.put("dateStart", ((Number) type.getMethod("getDateStart").invoke(entry)).longValue());
        item.put("dateEnd", ((Number) type.getMethod("getDateEnd").invoke(entry)).longValue());
        item.put("active", (Boolean) type.getMethod("isActive").invoke(entry));
        item.put("permanent", (Boolean) type.getMethod("isPermanent").invoke(entry));
        putNullable(item, "removedByName", nullable(type.getMethod("getRemovedByName").invoke(entry)));
        putNullable(item, "removalReason", nullable(type.getMethod("getRemovalReason").invoke(entry)));
        return item;
    }

    private Class<?> liteBansClass(String name) throws ClassNotFoundException {
        Object instance = proxy.getPluginManager().getPlugin("litebans")
            .flatMap(container -> container.getInstance())
            .orElseThrow(() -> new ClassNotFoundException("LiteBans is unavailable"));
        return Class.forName(name, true, instance.getClass().getClassLoader());
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

    private static String duration(JsonNode payload) {
        String duration = text(payload, "duration", 16);
        if (!duration.matches("[1-9][0-9]{0,6}[A-Za-z]{1,3}")) {
            throw invalid("Invalid punishment duration");
        }
        return duration;
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

    private static ObjectNode pageRoot(int page, int pageSize, long total) {
        return JSON.createObjectNode().put("page", page).put("pageSize", pageSize)
            .put("totalItems", total)
            .put("totalPages", Math.max(1L, (total + pageSize - 1L) / pageSize));
    }

    private static String nullable(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static void putNullable(ObjectNode target, String field, String value) {
        if (value == null) target.putNull(field); else target.put(field, value);
    }

    private static RpcHandlingException invalid(String message) {
        return new RpcHandlingException("ADMIN_REQUEST_INVALID", message, false);
    }

    private static RpcHandlingException unavailable(String code, String message) {
        return new RpcHandlingException(code, message, true);
    }
}
