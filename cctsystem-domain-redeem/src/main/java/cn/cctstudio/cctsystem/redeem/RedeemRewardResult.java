package cn.cctstudio.cctsystem.redeem;

public record RedeemRewardResult(
    RedeemRewardType type,
    String description,
    String status,
    String errorCode
) {
}
