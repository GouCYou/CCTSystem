package cn.cctstudio.cctsystem.redeem;

import cn.cctstudio.cctsystem.core.provider.ProviderKey;

public final class RedeemCodeServiceProvider {
    public static final String ID = "redeem-code-service";
    public static final ProviderKey<RedeemCodeService> KEY = ProviderKey.of(ID, RedeemCodeService.class);

    private RedeemCodeServiceProvider() {
    }
}
