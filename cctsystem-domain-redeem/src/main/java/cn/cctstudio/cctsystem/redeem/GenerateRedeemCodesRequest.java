package cn.cctstudio.cctsystem.redeem;

import java.time.Instant;
import java.util.List;

public record GenerateRedeemCodesRequest(
    int count,
    int maxUsesPerCode,
    Instant validFrom,
    Instant validUntil,
    String creator,
    String note,
    List<RedeemReward> rewards
) {
    public GenerateRedeemCodesRequest {
        rewards = rewards == null ? List.of() : List.copyOf(rewards);
    }
}
