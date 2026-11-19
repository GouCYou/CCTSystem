package cn.cctstudio.cctsystem.core.filter;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/** One cooldown per player, shared by ordinary chat and chat commands. */
public final class ChatRateLimiter {
    private final ConcurrentHashMap<UUID, Long> deadlines = new ConcurrentHashMap<>();
    private final LongSupplier clock;
    public ChatRateLimiter() { this(System::nanoTime); }
    public ChatRateLimiter(LongSupplier clock) { this.clock = clock; }
    public long remainingSeconds(UUID player) {
        long now = clock.getAsLong();
        long[] remaining = {0};
        deadlines.compute(player, (id, deadline) -> {
            if (deadline != null && deadline - now > 0) {
                remaining[0] = (deadline - now + 999_999_999L) / 1_000_000_000L;
                return deadline;
            }
            return now + 1_000_000_000L;
        });
        return remaining[0];
    }
    public void clear(UUID player) { deadlines.remove(player); }
}
