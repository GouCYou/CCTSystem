package cn.cctstudio.cctsystem.membership;

import cn.cctstudio.cctsystem.bridge.BridgeCallException;
import cn.cctstudio.cctsystem.bridge.BridgeRpcCall;
import cn.cctstudio.cctsystem.bridge.BridgeRpcRouter;
import cn.cctstudio.cctsystem.bridge.RpcHandlingException;
import cn.cctstudio.cctsystem.contract.RpcError;
import cn.cctstudio.cctsystem.core.CctRuntime;
import cn.cctstudio.cctsystem.core.lifecycle.LifecycleComponent;
import cn.cctstudio.cctsystem.core.logging.CctLogger;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

/** Membership RPC between Paper instances running on the same machine. */
public final class LoopbackMembership implements LifecycleComponent {
    private static final String ADDRESS = "127.0.0.1";
    private static final String PATH = "/membership/rpc";
    private static final String AUTHORIZATION = "Authorization";
    private static final int MAX_REQUEST_BYTES = 16_384;
    private static final ObjectMapper JSON = new ObjectMapper();
    private final CctRuntime runtime;
    private final CctLogger logger;
    private final boolean authority;
    private final int port;
    private final String authorization;
    private final HttpClient client;
    private volatile HttpServer server;

    public LoopbackMembership(CctRuntime runtime, CctLogger logger, boolean authority, int port, String secret) {
        if (port < 1024 || port > 65535 || !secret.matches("[A-Za-z0-9_-]{43,128}")) {
            throw new IllegalArgumentException("Invalid membership-local.yml port or secret");
        }
        this.runtime = runtime;
        this.logger = logger;
        this.authority = authority;
        this.port = port;
        authorization = "Bearer " + secret;
        client = authority ? null : HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .proxy(ProxySelector.of(null))
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    }

    public void install() {
        if (!authority) {
            runtime.providers().register(MembershipServiceProvider.KEY, new RemoteMembershipService(
                (capability, operation, payload, idempotencyKey, timeout) ->
                    call(new Request(operation, payload, idempotencyKey), timeout)
            ));
        }
        runtime.addAfterModules(this);
    }

    @Override
    public String id() {
        return "membership-loopback";
    }

    @Override
    public CompletableFuture<Void> start() {
        if (!authority) return CompletableFuture.completedFuture(null);
        MembershipService service = runtime.providers().find(MembershipServiceProvider.KEY).orElseThrow();
        BridgeRpcRouter router = new BridgeRpcRouter();
        MembershipRpcModule.registerHandlers(router, service);
        try {
            HttpServer listener = HttpServer.create(new InetSocketAddress(ADDRESS, port), 32);
            listener.setExecutor(runtime.executors().blocking());
            listener.createContext(PATH, exchange -> handle(exchange, router));
            listener.start();
            server = listener;
            logger.info("Membership loopback service listening on " + ADDRESS + ":" + port);
            return CompletableFuture.completedFuture(null);
        } catch (IOException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    @Override
    public CompletableFuture<Void> stop() {
        if (server != null) server.stop(1);
        if (client != null) client.shutdownNow();
        return CompletableFuture.completedFuture(null);
    }

    private CompletionStage<JsonNode> call(Request call, Duration timeout) {
        final byte[] body;
        try {
            body = JSON.writeValueAsBytes(call);
        } catch (IOException failure) {
            return CompletableFuture.failedFuture(failure);
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://" + ADDRESS + ":" + port + PATH))
            .timeout(timeout)
            .header(AUTHORIZATION, authorization)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build();
        return client.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray()).handle((response, failure) -> {
            if (failure != null) {
                String code = unwrap(failure) instanceof HttpTimeoutException ? "BRIDGE_TIMEOUT" : "BRIDGE_UNAVAILABLE";
                throw new BridgeCallException(code, "Local membership service did not respond", true);
            }
            if (response.statusCode() != 200) {
                throw new BridgeCallException("MEMBERSHIP_LOCAL_REJECTED", "Local membership request was rejected", false);
            }
            try {
                Result result = JSON.readValue(response.body(), Result.class);
                if (result.error() != null) {
                    throw new BridgeCallException(result.error().code(), result.error().message(), result.error().retryable());
                }
                return result.payload();
            } catch (IOException invalid) {
                throw new BridgeCallException("MEMBERSHIP_RESPONSE_INVALID", "Invalid local membership response", false);
            }
        });
    }

    private void handle(HttpExchange exchange, BridgeRpcRouter router) throws IOException {
        try (exchange) {
            String supplied = exchange.getRequestHeaders().getFirst(AUTHORIZATION);
            if (supplied == null || !MessageDigest.isEqual(authorization.getBytes(StandardCharsets.UTF_8),
                supplied.getBytes(StandardCharsets.UTF_8))) {
                exchange.sendResponseHeaders(401, -1);
                return;
            }
            if (!"POST".equals(exchange.getRequestMethod()) || !PATH.equals(exchange.getRequestURI().getPath())) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            byte[] body = exchange.getRequestBody().readNBytes(MAX_REQUEST_BYTES + 1);
            if (body.length > MAX_REQUEST_BYTES) {
                exchange.sendResponseHeaders(413, -1);
                return;
            }
            Request request;
            try {
                request = JSON.readValue(body, Request.class);
            } catch (IOException invalid) {
                exchange.sendResponseHeaders(400, -1);
                return;
            }
            if (request == null || request.operation() == null || request.payload() == null) {
                exchange.sendResponseHeaders(400, -1);
                return;
            }
            String requestId = UUID.randomUUID().toString();
            Result result = router.handle(new BridgeRpcCall(requestId, request.operation(),
                request.idempotencyKey(), request.payload())).handle((payload, failure) ->
                    failure == null ? new Result(payload, null) : failed(request.operation(), failure)
                ).toCompletableFuture().join();
            byte[] response = JSON.writeValueAsBytes(result);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
        }
    }

    private Result failed(String operation, Throwable failure) {
        Throwable cause = unwrap(failure);
        if (cause instanceof RpcHandlingException rpc) {
            return new Result(null, new RpcError(rpc.code(), rpc.getMessage(), rpc.retryable()));
        }
        logger.warn("Local membership operation failed: " + operation, cause);
        return new Result(null, new RpcError("INTERNAL_ERROR", "Membership request could not be completed", true));
    }

    private static Throwable unwrap(Throwable failure) {
        while (failure instanceof CompletionException && failure.getCause() != null) {
            failure = failure.getCause();
        }
        return failure;
    }

    private record Request(String operation, JsonNode payload, String idempotencyKey) {}
    private record Result(JsonNode payload, RpcError error) {}
}
