package cn.cctstudio.cctsystem.exchange;

import cn.cctstudio.cctsystem.core.provider.ProviderKey;

public final class ExchangeServiceProvider {
    public static final String ID = "exchange-service";
    public static final ProviderKey<ExchangeService> KEY = ProviderKey.of(ID, ExchangeService.class);

    private ExchangeServiceProvider() {
    }
}
