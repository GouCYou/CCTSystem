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
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

public final class BridgeClient implements LifecycleComponent {
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
    private final AtomicBoolean stopped = new AtomicBoolean(true);
    private final AtomicBoolean connecting = new AtomicBoolean();
    private final AtomicInteger reconnectAttempts = new AtomicInteger();
    private final AtomicLong outboundSequence = new AtomicLong();
    private final AtomicLong inboundSequence = new AtomicLong();
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
        pingTask = executors.scheduler().scheduleAtFixedRate(this::ping, 20, 20, TimeUnit.SECONDS);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> stop() {
        stopped.set(true);
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
                    logger.warn("Worker bridge connection failed: " + rootMessage(throwable));
                    scheduleReconnect();
                }
            });
    }

    private void onOpen(WebSocket webSocket) {
        WebSocket previous = socket.getAndSet(webSocket);
        if (previous != null && previous != webSocket) {
            previous.abort();
        }
        reconnectAttempts.set(0);
        inboundSequence.set(0);
        outboundSequence.set(0);
        sendHello(webSocket);
        logger.info("Worker bridge connected for node " + nodeConfig.nodeId());
    }

    private void onText(WebSocket webSocket, String message) {
        try {
            JsonNode tree = mapper.readTree(message);
            if (!"request".equals(tree.path("kind").asText())) {
                return;
            }
            RpcRequest request = mapper.treeToValue(tree, RpcRequest.class);
            validateRequest(request);
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
        if (request.version() != Protocol.VERSION) {
            throw new RpcHandlingException("PROTOCOL_VERSION_UNSUPPORTED", "Unsupported protocol version", false);
        }
        long previous = inboundSequence.getAndUpdate(current -> Math.max(current, request.sequence()));
        if (request.sequence() <= previous) {
            throw new RpcHandlingException("REPLAY_REJECTED", "Message sequence was already processed", false);
        }
        try {
            if (Instant.parse(request.deadline()).isBefore(Instant.now())) {
                throw new RpcHandlingException("REQUEST_EXPIRED", "Request deadline has passed", true);
            }
        } catch (DateTimeParseException exception) {
            throw new RpcHandlingException("REQUEST_INVALID", "Request deadline is invalid", false);
        }
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
        frame.put("sequence", outboundSequence.incrementAndGet());
        frame.set("payload", mapper.valueToTree(hello));
        sendJson(webSocket, frame);
    }

    private void sendResponse(WebSocket webSocket, String requestId, JsonNode payload, RpcError error) {
        RpcResponse response = new RpcResponse(
            Protocol.VERSION,
            "response",
            requestId,
            outboundSequence.incrementAndGet(),
            error == null,
            payload == null ? NullNode.getInstance() : payload,
            error
        );
        sendJson(webSocket, response);
    }

    private void sendJson(WebSocket webSocket, Object value) {
        try {
            webSocket.sendText(mapper.writeValueAsString(value), true);
        } catch (JsonProcessingException exception) {
            logger.error("Unable to serialize worker bridge message", exception);
        }
    }

    private void disconnected(WebSocket webSocket, String reason) {
        if (socket.compareAndSet(webSocket, null) && !stopped.get()) {
            logger.warn("Worker bridge disconnected: " + reason);
            scheduleReconnect();
        }
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

    private void ping() {
        WebSocket existing = socket.get();
        if (existing != null && !stopped.get()) {
            existing.sendPing(ByteBuffer.wrap(new byte[]{0x43, 0x43, 0x54}));
        }
    }

    private static Throwable unwrap(Throwable throwable) {
        if ((throwable instanceof CompletionException) && throwable.getCause() != null) {
            return throwable.getCause();
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
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public java.util.concurrent.CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            disconnected(webSocket, statusCode + " " + reason);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            disconnected(webSocket, rootMessage(error));
        }
    }
}
