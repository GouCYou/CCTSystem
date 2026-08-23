package cn.cctstudio.cctsystem.auth;

import cn.cctstudio.cctsystem.bridge.BridgeRpcClient;
import cn.cctstudio.cctsystem.contract.Capability;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class SocialBindingRewards {
    private static final ObjectMapper JSON = new ObjectMapper();

    private SocialBindingRewards() {
    }

    public static CompletionStage<Void> deliver(BridgeRpcClient bridge, UUID playerUuid) {
        ObjectNode payload = JSON.createObjectNode().put("playerUuid", playerUuid.toString());
        String prefix = "social-binding:" + playerUuid + ":";
        CompletableFuture<?> vip = bridge.call(
            Capability.MEMBERSHIP_MUTATE,
            "membership.social-binding-reward",
            payload,
            prefix + "vip",
            Duration.ofSeconds(8)
        ).toCompletableFuture();
        CompletableFuture<?> coins = bridge.call(
            Capability.EXCHANGE_EXECUTE,
            "economy.social-binding-reward",
            payload,
            prefix + "coins",
            Duration.ofSeconds(8)
        ).toCompletableFuture();
        return CompletableFuture.allOf(vip, coins);
    }
}
