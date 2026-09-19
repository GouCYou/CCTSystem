package cn.cctstudio.cctsystem.redeem;

import cn.cctstudio.cctsystem.bridge.BridgeRpcClient;
import cn.cctstudio.cctsystem.bridge.BridgeRpcClientProvider;
import cn.cctstudio.cctsystem.contract.NodeRole;
import cn.cctstudio.cctsystem.contract.PlatformType;
import cn.cctstudio.cctsystem.core.module.CctModule;
import cn.cctstudio.cctsystem.core.module.ModuleContext;
import cn.cctstudio.cctsystem.core.module.ModuleDescriptor;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public final class RemoteRedeemModule implements CctModule {
    private static final ModuleDescriptor DESCRIPTOR = new ModuleDescriptor(
        "redeem-remote",
        "redeem",
        Set.of(PlatformType.PAPER),
        EnumSet.allOf(NodeRole.class),
        Set.of(BridgeRpcClientProvider.ID),
        Set.of()
    );

    @Override
    public ModuleDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public CompletableFuture<Void> start(ModuleContext context) {
        if (context.providers().find(RedeemCodeServiceProvider.KEY).isPresent()) {
            return CompletableFuture.completedFuture(null);
        }
        BridgeRpcClient bridge = context.providers().find(BridgeRpcClientProvider.KEY).orElseThrow();
        context.providers().register(
            RedeemCodeServiceProvider.KEY,
            new RemoteRedeemCodeService(bridge)
        );
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> stop() {
        return CompletableFuture.completedFuture(null);
    }
}
