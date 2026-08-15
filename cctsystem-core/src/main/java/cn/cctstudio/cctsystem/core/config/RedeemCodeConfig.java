package cn.cctstudio.cctsystem.core.config;

public record RedeemCodeConfig(int length, String pepper) {
    public RedeemCodeConfig {
        length = length <= 0 ? 12 : length;
        pepper = pepper == null ? "" : pepper;
    }

    public static RedeemCodeConfig defaults() {
        return new RedeemCodeConfig(12, "");
    }
}
