package cn.cctstudio.cctsystem.bridge;

import static org.junit.jupiter.api.Assertions.*;
import cn.cctstudio.cctsystem.contract.PlatformType;
import cn.cctstudio.cctsystem.core.concurrent.CctExecutors;
import cn.cctstudio.cctsystem.core.config.*;
import cn.cctstudio.cctsystem.core.logging.CctLogger;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Proxy;
import java.net.http.WebSocket;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

final class BridgeClientTest {
    @Test
    void expiredRequestRepliesImmediatelyWithoutDispatchOrWarningAndOldSocketIsIgnored() throws Exception {
        List<String> sent = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        WebSocket socket = (WebSocket) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{WebSocket.class}, (proxy, method, args) -> {
            if (method.getName().equals("sendText")) sent.add(args[0].toString());
            return method.getReturnType() == CompletableFuture.class ? CompletableFuture.completedFuture(proxy) : null;
        });
        var logger = new CctLogger() {
            public void info(String m) { }
            public void warn(String m) { warnings.add(m); }
            public void warn(String m, Throwable t) { warnings.add(m); }
            public void error(String m, Throwable t) { warnings.add(m); }
        };
        var config = new CctConfig("test", "test", "test", Set.of(), null, new BridgeConfig(true, "", "", 10, 8), new LinkedHashMap<>());
        var router = new BridgeRpcRouter();
        boolean[] invoked = {false};
        router.register("test", payload -> { invoked[0] = true; return CompletableFuture.completedFuture(payload); });
        try (var executors = new CctExecutors()) {
            var client = new BridgeClient(config, PlatformType.PAPER, "boot", "test", Set::of, router, executors, logger);
            var stopped = BridgeClient.class.getDeclaredField("stopped"); stopped.setAccessible(true); ((AtomicBoolean) stopped.get(client)).set(false);
            var open = BridgeClient.class.getDeclaredMethod("onOpen", WebSocket.class); open.setAccessible(true); open.invoke(client, socket);
            var receive = BridgeClient.class.getDeclaredMethod("onText", WebSocket.class, String.class); receive.setAccessible(true);
            String request = "{\"version\":1,\"kind\":\"request\",\"requestId\":\"expired\",\"sequence\":1,\"operation\":\"test\",\"deadline\":\"" + Instant.now().minusSeconds(1) + "\",\"payload\":null}";
            receive.invoke(client, socket, request);
            assertFalse(invoked[0]);
            var response = new ObjectMapper().readTree(sent.getLast());
            assertEquals("REQUEST_EXPIRED", response.path("error").path("code").asText());
            assertEquals("expired", response.path("requestId").asText());
            assertTrue(warnings.isEmpty());
            open.invoke(client, socket);
            receive.invoke(client, null, request);
            assertTrue(warnings.isEmpty());
            client.stop().join();
        }
    }

    @Test
    void sendFailureClearsPendingCallAndConnectionWithoutWarningStack() throws Exception {
        List<String> warnings = new ArrayList<>();
        int[] sends = {0};
        int[] aborts = {0};
        WebSocket socket = (WebSocket) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{WebSocket.class}, (proxy, method, args) -> {
            if (method.getName().equals("abort")) aborts[0]++;
            if (method.getName().equals("sendText") && ++sends[0] > 1) {
                return CompletableFuture.failedFuture(new java.io.IOException("Connection reset"));
            }
            return method.getReturnType() == CompletableFuture.class ? CompletableFuture.completedFuture(proxy) : null;
        });
        var logger = new CctLogger() {
            public void info(String m) { }
            public void warn(String m) { warnings.add(m); }
            public void warn(String m, Throwable t) { warnings.add(m); }
            public void error(String m, Throwable t) { warnings.add(m); }
        };
        var config = new CctConfig("test", "test", "test", Set.of(), null, new BridgeConfig(true, "", "", 10, 8), new LinkedHashMap<>());
        try (var executors = new CctExecutors()) {
            var client = new BridgeClient(config, PlatformType.PAPER, "boot", "test", Set::of, new BridgeRpcRouter(), executors, logger);
            var stopped = BridgeClient.class.getDeclaredField("stopped"); stopped.setAccessible(true); ((AtomicBoolean) stopped.get(client)).set(false);
            var open = BridgeClient.class.getDeclaredMethod("onOpen", WebSocket.class); open.setAccessible(true); open.invoke(client, socket);
            var result = client.call(cn.cctstudio.cctsystem.contract.Capability.MEMBERSHIP_READ, "membership.catalog", null, null, java.time.Duration.ofSeconds(8)).toCompletableFuture();
            var failure = assertThrows(CompletionException.class, result::join);
            var cause = assertInstanceOf(BridgeCallException.class, failure.getCause());
            assertEquals("BRIDGE_DISCONNECTED", cause.code());
            assertTrue(cause.retryable());
            assertFalse(client.connected());
            assertEquals(1, aborts[0]);
            assertTrue(warnings.isEmpty());
            client.stop().join();
        }
    }

}
