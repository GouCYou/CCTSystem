package cn.cctstudio.cctsystem.promotion;

import cn.cctstudio.cctsystem.core.provider.ProviderKey;

public final class PromotionServiceProvider {
    public static final String ID = "promotion-service";
    public static final ProviderKey<PromotionService> KEY = ProviderKey.of(ID, PromotionService.class);

    private PromotionServiceProvider() {
    }
}
