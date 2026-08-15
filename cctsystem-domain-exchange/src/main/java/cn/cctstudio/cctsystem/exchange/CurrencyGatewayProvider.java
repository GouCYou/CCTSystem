package cn.cctstudio.cctsystem.exchange;

import cn.cctstudio.cctsystem.core.provider.ProviderKey;

public final class CurrencyGatewayProvider {
    public static final String ID = "vault-economy";
    public static final ProviderKey<CurrencyGateway> KEY = ProviderKey.of(ID, CurrencyGateway.class);

    private CurrencyGatewayProvider() {
    }
}
