package cn.cctstudio.cctsystem.identity;

import cn.cctstudio.cctsystem.core.provider.ProviderKey;

public final class IdentityProvider {
    public static final String ID = "identity";
    public static final ProviderKey<IdentityService> KEY = ProviderKey.of(ID, IdentityService.class);

    private IdentityProvider() {
    }
}
