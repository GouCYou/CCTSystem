package cn.cctstudio.cctsystem.bridge;

import cn.cctstudio.cctsystem.core.provider.ProviderKey;

public final class BridgeRpcClientProvider {
    public static final String ID = "bridge-rpc-client";
    public static final ProviderKey<BridgeRpcClient> KEY = ProviderKey.of(ID, BridgeRpcClient.class);

    private BridgeRpcClientProvider() {
    }
}
