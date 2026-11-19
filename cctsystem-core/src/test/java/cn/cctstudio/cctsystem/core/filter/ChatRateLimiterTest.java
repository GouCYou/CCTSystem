package cn.cctstudio.cctsystem.core.filter;
import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
final class ChatRateLimiterTest {
    @Test void rejectedMessagesDoNotExtendCooldownAndPlayersAreIndependent() {
        AtomicLong now = new AtomicLong();
        ChatRateLimiter limiter = new ChatRateLimiter(now::get);
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        assertEquals(0, limiter.remainingSeconds(first));
        now.set(900_000_000);
        assertEquals(1, limiter.remainingSeconds(first));
        assertEquals(0, limiter.remainingSeconds(second));
        now.set(1_000_000_000);
        assertEquals(0, limiter.remainingSeconds(first));
        assertEquals(1, limiter.remainingSeconds(second));
    }
    @Test void simultaneousMessagesOnlyAllowOne() throws Exception {
        ChatRateLimiter limiter = new ChatRateLimiter(() -> 0L);
        UUID id = UUID.randomUUID();
        var allowed = new java.util.concurrent.atomic.AtomicInteger();
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(8)) {
            var tasks = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 32; i++) tasks.add(executor.submit(() -> {
                if (limiter.remainingSeconds(id) == 0) allowed.incrementAndGet();
            }));
            for (var task : tasks) task.get();
        }
        assertEquals(1, allowed.get());
    }
}
