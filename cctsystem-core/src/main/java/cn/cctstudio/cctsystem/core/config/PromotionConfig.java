package cn.cctstudio.cctsystem.core.config;

public record PromotionConfig(String timezone) {
    public PromotionConfig {
        timezone = timezone == null || timezone.isBlank() ? "Asia/Shanghai" : timezone.trim();
    }

    public static PromotionConfig defaults() {
        return new PromotionConfig("Asia/Shanghai");
    }
}
