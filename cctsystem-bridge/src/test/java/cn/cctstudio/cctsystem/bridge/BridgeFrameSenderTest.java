package cn.cctstudio.cctsystem.bridge;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Proxy;
import java.net.http.WebSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

final class BridgeFrameSenderTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void concurrentFramesWaitForPreviousSendAndHaveOrderedUniqueSequences() throws Exception {
        List<String> sent = new ArrayList<>();
        List<CompletableFuture<WebSocket>> sends = new ArrayList<>();
        WebSocket socket = (WebSocket) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{WebSocket.class}, (proxy, method, args) -> {
            if (!method.getName().equals("sendText")) return null;
            synchronized (sent) {
                if (!sends.isEmpty() && !sends.getLast().isDone()) throw new IllegalStateException("Send pending");
                sent.add(args[0].toString());
                var next = new CompletableFuture<WebSocket>();
                sends.add(next);
                return next;
            }
        });
        BridgeFrameSender sender = new BridgeFrameSender(socket, mapper);
        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            List<java.util.concurrent.Future<?>> tasks = new ArrayList<>();
            for (int i = 0; i < 100; i++) tasks.add(threads.submit(() -> sender.send(mapper.createObjectNode().put("kind", "call"))));
            for (var task : tasks) task.get();
        }
        assertEquals(1, sent.size());
        for (int i = 0; i < 100; i++) {
            assertEquals(i + 1, mapper.readTree(sent.get(i)).path("sequence").asInt());
            sends.get(i).complete(socket);
        }
        assertEquals(100, sent.size());
    }

    @Test
    void failedSendStopsQueue() {
        var first = new CompletableFuture<WebSocket>();
        int[] count = {0};
        WebSocket socket = (WebSocket) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{WebSocket.class}, (proxy, method, args) -> { count[0]++; return first; });
        var sender = new BridgeFrameSender(socket, mapper);
        sender.send(mapper.createObjectNode());
        var queued = sender.send(mapper.createObjectNode());
        first.completeExceptionally(new java.io.IOException("network lost"));
        assertTrue(queued.isCompletedExceptionally());
        assertEquals(1, count[0]);
    }
}
