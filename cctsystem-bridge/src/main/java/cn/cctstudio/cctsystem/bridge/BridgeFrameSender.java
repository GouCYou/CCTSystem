package cn.cctstudio.cctsystem.bridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.http.WebSocket;
import java.util.concurrent.CompletableFuture;

/** One ordered text stream per connection, including sequence assignment. */
final class BridgeFrameSender {
    private final WebSocket socket;
    private final ObjectMapper mapper;
    private long sequence;
    private CompletableFuture<WebSocket> tail;

    BridgeFrameSender(WebSocket socket, ObjectMapper mapper) {
        this.socket = socket;
        this.mapper = mapper;
        this.tail = CompletableFuture.completedFuture(socket);
    }

    boolean matches(WebSocket candidate) {
        return socket == candidate;
    }

    synchronized CompletableFuture<WebSocket> send(ObjectNode frame) {
        final String text;
        try {
            frame.put("sequence", ++sequence);
            text = mapper.writeValueAsString(frame);
        } catch (Exception failure) {
            return CompletableFuture.failedFuture(failure);
        }
        // A failed connection poisons its queue. A new connection gets a fresh sender.
        tail = tail.thenCompose(ignored -> socket.sendText(text, true));
        return tail;
    }
}
