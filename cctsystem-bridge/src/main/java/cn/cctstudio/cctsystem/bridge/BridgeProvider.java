package cn.cctstudio.cctsystem.bridge;

import cn.cctstudio.cctsystem.core.provider.ProviderKey;

public final class BridgeProvider {
    public static final String ID = "bridge-router";
    public static final ProviderKey<BridgeRpcRouter> KEY = ProviderKey.of(ID, BridgeRpcRouter.class);

    private BridgeProvider() {
    }
}
