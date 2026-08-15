package cn.cctstudio.cctsystem.redeem;

import java.util.List;
import java.util.UUID;

public record RedeemResult(
    UUID useId,
    String status,
    List<RedeemRewardResult> rewards,
    String errorCode
) {
    public RedeemResult {
        rewards = rewards == null ? List.of() : List.copyOf(rewards);
    }
}
