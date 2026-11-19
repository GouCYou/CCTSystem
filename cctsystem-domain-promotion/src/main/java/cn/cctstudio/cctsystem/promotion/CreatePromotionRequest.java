package cn.cctstudio.cctsystem.promotion;

import java.time.Instant;

public record CreatePromotionRequest(
    String name,
    String targetTierKey,
    int percentOffBps,
    Instant startsAt,
    Instant endsAt,
    int priority,
    String actor,
    String reason,
    String discountCode,
    Integer maxUsesPerPlayer,
    Integer maxPlayers
) {
    public CreatePromotionRequest(String name, String tier, int off, Instant starts, Instant ends, int priority, String actor, String reason, String code) {
        this(name,tier,off,starts,ends,priority,actor,reason,code,null,null);
    }
    public CreatePromotionRequest(String name, String targetTierKey, int percentOffBps, Instant startsAt,
        Instant endsAt, int priority, String actor, String reason) {
        this(name, targetTierKey, percentOffBps, startsAt, endsAt, priority, actor, reason, null);
    }
}
