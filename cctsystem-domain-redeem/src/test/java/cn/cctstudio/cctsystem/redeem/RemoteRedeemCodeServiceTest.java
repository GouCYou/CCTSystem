package cn.cctstudio.cctsystem.redeem;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.cctstudio.cctsystem.bridge.BridgeRpcClient;
import cn.cctstudio.cctsystem.contract.Capability;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

final class RemoteRedeemCodeServiceTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void forwardsRedeemToBusinessAuthority() {
        UUID playerUuid = UUID.randomUUID();
        UUID transactionId = UUID.randomUUID();
        BridgeRpcClient bridge = (capability, operation, payload, idempotencyKey, timeout) -> {
            assertEquals(Capability.REDEEM_EXECUTE, capability);
            assertEquals(RedeemRpcModule.OPERATION, operation);
            assertEquals(playerUuid.toString(), payload.path("playerUuid").asText());
            assertEquals("ABC-123", payload.path("code").asText());
            assertEquals("GAME_COMMAND", payload.path("origin").asText());
            assertEquals("request-1", idempotencyKey);
            assertEquals(Duration.ofSeconds(8), timeout);
            var response = JSON.createObjectNode();
            response.put("transactionId", transactionId.toString());
            response.put("status", "COMPLETED");
            response.putNull("errorCode");
            var reward = response.putArray("rewards").addObject();
            reward.put("type", "MEMBERSHIP");
            reward.put("description", "MVP 7 days");
            reward.put("status", "DELIVERED");
            reward.putNull("errorCode");
            return CompletableFuture.completedFuture(response);
        };

        RedeemResult result = new RemoteRedeemCodeService(bridge).redeem(new RedeemRequest(
            playerUuid, "ABC-123", "request-1", "GAME_COMMAND"
        )).toCompletableFuture().join();

        assertEquals(transactionId, result.useId());
        assertEquals("COMPLETED", result.status());
        assertEquals(RedeemRewardType.MEMBERSHIP, result.rewards().getFirst().type());
    }
}
