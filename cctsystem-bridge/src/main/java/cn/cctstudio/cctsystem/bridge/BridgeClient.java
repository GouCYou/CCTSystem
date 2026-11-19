package cn.cctstudio.cctsystem.bridge;

import cn.cctstudio.cctsystem.contract.Capability;
import cn.cctstudio.cctsystem.contract.NodeHello;
import cn.cctstudio.cctsystem.contract.PlatformType;
import cn.cctstudio.cctsystem.contract.Protocol;
import cn.cctstudio.cctsystem.contract.RpcError;
import cn.cctstudio.cctsystem.contract.RpcRequest;
import cn.cctstudio.cctsystem.contract.RpcResponse;
import cn.cctstudio.cctsystem.core.concurrent.CctExecutors;
import cn.cctstudio.cctsystem.core.config.BridgeConfig;
import cn.cctstudio.cctsystem.core.config.CctConfig;
import cn.cctstudio.cctsystem.core.lifecycle.LifecycleComponent;
import cn.cctstudio.cctsystem.core.logging.CctLogger;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

public final class BridgeClient implements LifecycleComponent, BridgeRpcClient {
    private final CctConfig nodeConfig;
    private final BridgeConfig bridgeConfig;
    private final PlatformType platform;
    private final String bootId;
    private final String pluginVersion;
    private final Supplier<Set<Capability>> capabilities;
    private final BridgeRpcRouter router;
    private final CctExecutors executors;
    private final CctLogger logger;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicReference<WebSocket> socket = new AtomicReference<>();
    private final java.util.Map<String, PendingCall> pendingCalls = new ConcurrentHashMap<>();
    private final AtomicBoolean stopped = new AtomicBoolean(true);
    private final AtomicBoolean connecting = new AtomicBoolean();
    private final AtomicInteger reconnectAttempts = new AtomicInteger();
    private volatile BridgeFrameSender sender;
    private final AtomicLong inboundSequence = new AtomicLong();
    private final AtomicLong lastServerActivityNanos = new AtomicLong();
    private volatile HttpClient httpClient;
    private volatile ScheduledFuture<?> pingTask;

    public BridgeClient(
        CctConfig nodeConfig,
        PlatformType platform,
        String bootId,
        String pluginVersion,
        Supplier<Set<Capability>> capabilities,
        BridgeRpcRouter router,
        CctExecutors executors,
        CctLogger logger
    ) {
        this.nodeConfig = Objects.requireNonNull(nodeConfig, "nodeConfig");
        this.bridgeConfig = nodeConfig.bridge();
        this.platform = Objects.requireNonNull(platform, "platform");
        this.bootId = Objects.requireNonNull(bootId, "bootId");
        this.pluginVersion = Objects.requireNonNull(pluginVersion, "pluginVersion");
        this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
        this.router = Objects.requireNonNull(router, "router");
        this.executors = Objects.requireNonNull(executors, "executors");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public String id() {
        return "worker-bridge";
    }

    @Override
    public CompletableFuture<Void> start() {
        if (!stopped.compareAndSet(true, false)) {
            return CompletableFuture.failedFuture(new IllegalStateException("Bridge client already started"));
        }
        httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(bridgeConfig.connectTimeoutSeconds()))
            .executor(executors.blocking())
            .build();
        connect();
        pingTask = executors.scheduler().scheduleAtFixedRate(this::heartbeat, 20, 20, TimeUnit.SECONDS);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> stop() {
        stopped.set(true);
        failPending(new BridgeCallException("BRIDGE_DISCONNECTED", "Worker bridge is disconnected", true));
        ScheduledFuture<?> existingPing = pingTask;
        if (existingPing != null) {
            existingPing.cancel(false);
        }
        WebSocket existingSocket = socket.getAndSet(null);
        if (existingSocket == null) {
            return CompletableFuture.completedFuture(null);
        }
        return existingSocket.sendClose(WebSocket.NORMAL_CLOSURE, "plugin stopping")
            .orTimeout(2, TimeUnit.SECONDS)
            .handle((ignored, throwable) -> {
                if (throwable != null) {
                    existingSocket.abort();
                }
                return null;
            });
    }

    public boolean connected() {
        return socket.get() != null;
    }

    @Override
    public CompletionStage<JsonNode> call(
        Capability capability,
        String operation,
        JsonNode payload,
        String idempotencyKey,
        Duration timeout
    ) {
        Objects.requireNonNull(capability, "capability");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(timeout, "timeout");
        WebSocket webSocket = socket.get();
        if (webSocket == null || stopped.get()) {
            return CompletableFuture.failedFuture(
                new BridgeCallException("BRIDGE_UNAVAILABLE", "Worker bridge is unavailable", true)
            );
        }
        long timeoutMillis = Math.min(Math.max(timeout.toMillis(), 500L), 15_000L);
        String requestId = UUID.randomUUID().toString();
        CompletableFuture<JsonNode> result = new CompletableFuture<>();
        ScheduledFuture<?> timer = executors.scheduler().schedule(() -> {
            PendingCall pending = pendingCalls.remove(requestId);
            if (pending != null) {
                pending.result().completeExceptionally(
                    new BridgeCallException("BRIDGE_TIMEOUT", "Worker response timed out (node=" + nodeConfig.nodeId()
                        + ", operation=" + operation + ", request=" + requestId + ")", true)
                );
            }
        }, timeoutMillis, TimeUnit.MILLISECONDS);
        pendingCalls.put(requestId, new PendingCall(result, timer));

        ObjectNode frame = mapper.createObjectNode();
        frame.put("version", Protocol.VERSION);
        frame.put("kind", "call");
        frame.put("requestId", requestId);
        frame.put("capability", capability.value());
        frame.put("operation", operation);
        frame.put("deadline", Instant.now().plusMillis(timeoutMillis).toString());
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            frame.putNull("idempotencyKey");
        } else {
            frame.put("idempotencyKey", idempotencyKey);
        }
        frame.set("payload", payload == null ? NullNode.getInstance() : payload);
        sendJson(webSocket, frame).whenComplete((ignored, failure) -> {
            if (failure != null) {
                PendingCall pending = pendingCalls.remove(requestId);
                if (pending != null) {
                    pending.timer().cancel(false);
                    pending.result().completeExceptionally(
                        new BridgeCallException("BRIDGE_DISCONNECTED", "Worker bridge is disconnected", true)
                    );
                }
            }
        });
        return result;
    }

    private void connect() {
        if (stopped.get() || !connecting.compareAndSet(false, true)) {
            return;
        }
        URI uri = URI.create(bridgeConfig.url());
        long timestamp = Instant.now().getEpochSecond();
        String nonce = UUID.randomUUID().toString();
        String canonical = BridgeAuthenticator.canonicalRequest(
            uri.getPath(),
            nodeConfig.networkId(),
            nodeConfig.serverId(),
            nodeConfig.nodeId(),
            timestamp,
            nonce
        );
        String signature = BridgeAuthenticator.sign(bridgeConfig.secret(), canonical);

        httpClient.newWebSocketBuilder()
            .connectTimeout(Duration.ofSeconds(bridgeConfig.connectTimeoutSeconds()))
            .header("X-CCT-Network-Id", nodeConfig.networkId())
            .header("X-CCT-Server-Id", nodeConfig.serverId())
            .header("X-CCT-Node-Id", nodeConfig.nodeId())
            .header("X-CCT-Timestamp", Long.toString(timestamp))
            .header("X-CCT-Nonce", nonce)
            .header("X-CCT-Signature", signature)
            .buildAsync(uri, new Listener())
            .whenComplete((webSocket, throwable) -> {
                connecting.set(false);
                if (throwable != null) {
                    Throwable cause = unwrap(throwable);
                    if (temporaryConnectionFailure(cause)) {
                        logger.info("Worker bridge temporarily unavailable; reconnecting: " + rootMessage(cause));
                    } else {
                        logger.warn("Worker bridge connection failed", cause);
                    }
                    scheduleReconnect();
                }
            });
    }

    private synchronized void onOpen(WebSocket webSocket) {
        if (stopped.get()) {
            webSocket.abort();
            return;
        }
        sender = new BridgeFrameSender(webSocket, mapper);
        WebSocket previous = socket.getAndSet(webSocket);
        if (previous != null && previous != webSocket) {
            previous.abort();
        }
        reconnectAttempts.set(0);
        inboundSequence.set(0);
        lastServerActivityNanos.set(System.nanoTime());
        sendHello(webSocket);
        if (socket.get() == webSocket) logger.info("Worker bridge connected for node " + nodeConfig.nodeId());
    }

    private void onText(WebSocket webSocket, String message) {
        if (socket.get() != webSocket || stopped.get()) return;
        try {
            JsonNode tree = mapper.readTree(message);
            validateEnvelope(tree);
            lastServerActivityNanos.set(System.nanoTime());
            if ("heartbeat_ack".equals(tree.path("kind").asText())) {
                return;
            }
            if ("response".equals(tree.path("kind").asText())) {
                handleCallResponse(mapper.treeToValue(tree, RpcResponse.class));
                return;
            }
            if (!"request".equals(tree.path("kind").asText())) {
                return;
            }
            RpcRequest request = mapper.treeToValue(tree, RpcRequest.class);
            try {
                validateRequest(request);
            } catch (RpcHandlingException rejection) {
                sendResponse(webSocket, request.requestId(), NullNode.getInstance(),
                    new RpcError(rejection.code(), rejection.getMessage(), rejection.retryable()));
                if (!"REQUEST_EXPIRED".equals(rejection.code())) {
                    logger.warn("Rejected invalid worker bridge request: " + rejection.code());
                }
                return;
            }
            router.handle(new BridgeRpcCall(
                request.requestId(),
                request.operation(),
                request.idempotencyKey(),
                request.payload()
            )).whenComplete((payload, throwable) -> {
                if (throwable == null) {
                    sendResponse(webSocket, request.requestId(), payload, null);
                } else {
                    Throwable cause = unwrap(throwable);
                    RpcError error = cause instanceof RpcHandlingException rpc
                        ? new RpcError(rpc.code(), rpc.getMessage(), rpc.retryable())
                        : new RpcError("INTERNAL_ERROR", "Request could not be completed", true);
                    if (!(cause instanceof RpcHandlingException)) {
                        logger.warn("Unhandled bridge RPC error for " + request.operation(), cause);
                    }
                    sendResponse(webSocket, request.requestId(), NullNode.getInstance(), error);
                }
            });
        } catch (RuntimeException | JsonProcessingException exception) {
            logger.warn("Rejected invalid worker bridge message: " + exception.getMessage());
        }
    }

    private void validateRequest(RpcRequest request) {
        try {
            if (Instant.parse(request.deadline()).isBefore(Instant.now())) {
                throw new RpcHandlingException("REQUEST_EXPIRED", "Request deadline has passed", true);
            }
        } catch (DateTimeParseException exception) {
            throw new RpcHandlingException("REQUEST_INVALID", "Request deadline is invalid", false);
        }
    }

    private void validateEnvelope(JsonNode tree) {
        if (tree.path("version").asInt(-1) != Protocol.VERSION) {
            throw new RpcHandlingException("PROTOCOL_VERSION_UNSUPPORTED", "Unsupported protocol version", false);
        }
        long sequence = tree.path("sequence").asLong(0);
        long previous = inboundSequence.getAndUpdate(current -> Math.max(current, sequence));
        if (sequence <= previous) {
            throw new RpcHandlingException("REPLAY_REJECTED", "Message sequence was already processed", false);
        }
    }

    private void handleCallResponse(RpcResponse response) {
        PendingCall pending = pendingCalls.remove(response.requestId());
        if (pending == null) {
            return;
        }
        pending.timer().cancel(false);
        if (response.ok()) {
            pending.result().complete(response.payload() == null ? NullNode.getInstance() : response.payload());
            return;
        }
        RpcError error = response.error();
        pending.result().completeExceptionally(new BridgeCallException(
            error == null ? "BRIDGE_REQUEST_FAILED" : error.code(),
            error == null ? "Minecraft request failed" : error.message(),
            error != null && error.retryable()
        ));
    }

    private void sendHello(WebSocket webSocket) {
        NodeHello hello = new NodeHello(
            Protocol.VERSION,
            nodeConfig.networkId(),
            nodeConfig.serverId(),
            nodeConfig.nodeId(),
            platform,
            nodeConfig.roles(),
            capabilities.get(),
            bootId,
            pluginVersion
        );
        ObjectNode frame = mapper.createObjectNode();
        frame.put("version", Protocol.VERSION);
        frame.put("kind", "hello");
        frame.set("payload", mapper.valueToTree(hello));
        sendJson(webSocket, frame);
    }

    private void sendResponse(WebSocket webSocket, String requestId, JsonNode payload, RpcError error) {
        RpcResponse response = new RpcResponse(
            Protocol.VERSION,
            "response",
            requestId,
            0L,
            error == null,
            payload == null ? NullNode.getInstance() : payload,
            error
        );
        sendJson(webSocket, response);
    }

    private synchronized CompletableFuture<WebSocket> sendJson(WebSocket webSocket, Object value) {
        BridgeFrameSender current = sender;
        if (socket.get() != webSocket || current == null || !current.matches(webSocket) || stopped.get()) {
            return CompletableFuture.failedFuture(
                new BridgeCallException("BRIDGE_DISCONNECTED", "Worker bridge is disconnected", true));
        }
        ObjectNode frame = mapper.valueToTree(value);
        return current.send(frame).orTimeout(bridgeConfig.connectTimeoutSeconds(), TimeUnit.SECONDS)
            .whenComplete((ignored, failure) -> {
                if (failure != null) {
                    if (unwrap(failure) instanceof JsonProcessingException) {
                        logger.error("Unable to serialize worker bridge message", unwrap(failure));
                    }
                    disconnected(webSocket, "send failed: " + rootMessage(failure), !temporaryConnectionFailure(unwrap(failure)));
                    webSocket.abort();
                }
            });
    }

    private void disconnected(WebSocket webSocket, String reason) {
        disconnected(webSocket, reason, false);
    }

    private void disconnected(WebSocket webSocket, String reason, boolean unexpected) {
        if (socket.compareAndSet(webSocket, null) && !stopped.get()) {
            failPending(new BridgeCallException("BRIDGE_DISCONNECTED", "Worker bridge disconnected", true));
            if (unexpected) logger.warn("Worker bridge connection rejected or failed unexpectedly: " + reason);
            else logger.info("Worker bridge connection interrupted; reconnecting: " + reason);
            scheduleReconnect();
        }
    }

    private void failPending(Throwable failure) {
        pendingCalls.forEach((requestId, pending) -> {
            if (pendingCalls.remove(requestId, pending)) {
                pending.timer().cancel(false);
                pending.result().completeExceptionally(failure);
            }
        });
    }

    private void scheduleReconnect() {
        if (stopped.get()) {
            return;
        }
        int attempt = reconnectAttempts.getAndIncrement();
        long ceiling = Math.min(60_000L, 1_000L << Math.min(attempt, 6));
        long delay = ThreadLocalRandom.current().nextLong(Math.max(500L, ceiling / 2), ceiling + 1);
        executors.scheduler().schedule(this::connect, delay, TimeUnit.MILLISECONDS);
    }

    private void heartbeat() {
        WebSocket existing = socket.get();
        if (existing == null || stopped.get()) {
            return;
        }
        long lastActivity = lastServerActivityNanos.get();
        if (lastActivity != 0L
            && System.nanoTime() - lastActivity > TimeUnit.SECONDS.toNanos(65)) {
            existing.abort();
            disconnected(existing, "application heartbeat timed out");
            return;
        }
        ObjectNode frame = mapper.createObjectNode();
        frame.put("version", Protocol.VERSION);
        frame.put("kind", "heartbeat");
        sendJson(existing, frame);
    }

    private static boolean temporaryConnectionFailure(Throwable cause) {
        return cause instanceof java.util.concurrent.TimeoutException
            || (cause instanceof java.io.IOException
                && !(cause instanceof java.net.http.WebSocketHandshakeException)
                && !(cause instanceof javax.net.ssl.SSLException))
            || (cause instanceof BridgeCallException bridge && bridge.retryable());
    }

    private static Throwable unwrap(Throwable throwable) {
        while (throwable instanceof CompletionException && throwable.getCause() != null) {
            throwable = throwable.getCause();
        }
        return throwable;
    }

    private static String rootMessage(Throwable throwable) {
        Throwable cause = unwrap(throwable);
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    private final class Listener implements WebSocket.Listener {
        private final StringBuilder text = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            BridgeClient.this.onOpen(webSocket);
            webSocket.request(1);
        }

        @Override
        public java.util.concurrent.CompletionStage<?> onText(
            WebSocket webSocket,
            CharSequence data,
            boolean last
        ) {
            text.append(data);
            if (last) {
                String complete = text.toString();
                text.setLength(0);
                BridgeClient.this.onText(webSocket, complete);
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public java.util.concurrent.CompletionStage<?> onPong(WebSocket webSocket, ByteBuffer message) {
            if (socket.get() == webSocket) lastServerActivityNanos.set(System.nanoTime());
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public java.util.concurrent.CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            disconnected(webSocket, statusCode + " " + reason,
                statusCode == 4002 || statusCode == 4003 || statusCode == 1003 || statusCode == 1007 || statusCode == 1009);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            disconnected(webSocket, rootMessage(error), !temporaryConnectionFailure(unwrap(error)));
        }
    }

    private record PendingCall(CompletableFuture<JsonNode> result, ScheduledFuture<?> timer) {
    }
}
