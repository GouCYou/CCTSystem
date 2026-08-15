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
    String reason
) {
}
