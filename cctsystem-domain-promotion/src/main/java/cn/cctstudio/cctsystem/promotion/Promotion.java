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
    String status
) {
    public static Promotion none() {
        return new Promotion(null, "", null, 0, Instant.EPOCH, Instant.EPOCH, 0, "NONE");
    }

    public boolean active() {
        return promotionId != null && percentOffBps > 0;
    }
}
