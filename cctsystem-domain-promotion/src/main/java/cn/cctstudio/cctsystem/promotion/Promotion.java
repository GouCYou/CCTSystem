package cn.cctstudio.cctsystem.promotion;

import java.time.Instant;
import java.util.UUID;

public record Promotion(
    UUID promotionId,
    String name,
    String targetTierKey,
    int percentOffBps,
    Instant startsAt,
    Instant endsAt,
    int priority,
    String status,
    String discountCode,
    Integer maxUsesPerPlayer,
    Integer maxPlayers,
    int usedPlayers
) {
    public Promotion(UUID id, String name, String tier, int off, Instant starts, Instant ends, int priority, String status, String code) {
        this(id,name,tier,off,starts,ends,priority,status,code,null,null,0);
    }
    public Promotion(UUID id, String name, String tier, int off, Instant starts, Instant ends, int priority, String status) {
        this(id, name, tier, off, starts, ends, priority, status, null);
    }
    public static Promotion none() {
        return new Promotion(null, "", null, 0, Instant.EPOCH, Instant.EPOCH, 0, "NONE");
    }

    public boolean active() {
        return promotionId != null && percentOffBps > 0;
    }
}
