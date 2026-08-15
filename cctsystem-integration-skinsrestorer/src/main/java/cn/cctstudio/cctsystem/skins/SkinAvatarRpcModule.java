package cn.cctstudio.cctsystem.skins;

import cn.cctstudio.cctsystem.bridge.BridgeProvider;
import cn.cctstudio.cctsystem.bridge.BridgeRpcRouter;
import cn.cctstudio.cctsystem.bridge.RpcHandlingException;
import cn.cctstudio.cctsystem.contract.Capability;
import cn.cctstudio.cctsystem.contract.NodeRole;
import cn.cctstudio.cctsystem.contract.PlatformType;
import cn.cctstudio.cctsystem.core.module.CctModule;
import cn.cctstudio.cctsystem.core.module.ModuleContext;
import cn.cctstudio.cctsystem.core.module.ModuleDescriptor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

public final class SkinAvatarRpcModule implements CctModule {
    public static final String OPERATION = "skin.avatar.read";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ModuleDescriptor DESCRIPTOR = new ModuleDescriptor(
        "skin-avatar-rpc",
        "skins",
        Set.of(PlatformType.VELOCITY),
        Set.of(NodeRole.SKIN_AUTHORITY),
        Set.of(BridgeProvider.ID, SkinAvatarServiceProvider.ID),
        Set.of(Capability.SKIN_AVATAR_READ)
    );

    @Override
    public ModuleDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public CompletableFuture<Void> start(ModuleContext context) {
        BridgeRpcRouter router = context.providers().find(BridgeProvider.KEY).orElseThrow();
        SkinAvatarService service = context.providers().find(SkinAvatarServiceProvider.KEY).orElseThrow();
        router.register(OPERATION, payload -> mapErrors(read(payload, service)));
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> stop() {
        return CompletableFuture.completedFuture(null);
    }

    private static CompletionStage<JsonNode> read(JsonNode payload, SkinAvatarService service) {
        UUID playerUuid;
        try {
            playerUuid = UUID.fromString(requireText(payload, "playerUuid", 36));
        } catch (IllegalArgumentException exception) {
            throw invalidRequest();
        }
        String playerName = requireText(payload, "playerName", 16);
        if (!playerName.matches("[A-Za-z0-9_]{3,16}")) {
            throw invalidRequest();
        }
        return service.avatar(playerUuid, playerName).thenApply(avatar -> {
            ObjectNode result = JSON.createObjectNode();
            result.put("pngBase64", Base64.getEncoder().encodeToString(avatar.png()));
            result.put("textureHash", avatar.textureHash());
            return result;
        });
    }

    private static String requireText(JsonNode payload, String field, int maxLength) {
        if (payload == null || !payload.isObject() || !payload.path(field).isTextual()) {
            throw invalidRequest();
        }
        String value = payload.path(field).textValue();
        if (value.isBlank() || value.length() > maxLength) throw invalidRequest();
        return value;
    }

    private static CompletionStage<JsonNode> mapErrors(CompletionStage<? extends JsonNode> operation) {
        CompletableFuture<JsonNode> result = new CompletableFuture<>();
        operation.whenComplete((value, failure) -> {
            if (failure == null) {
                result.complete(value);
                return;
            }
            Throwable cause = unwrap(failure);
            if (cause instanceof SkinAvatarException skin) {
                result.completeExceptionally(new RpcHandlingException(
                    skin.code(), skin.getMessage(), skin.retryable()
                ));
            } else {
                result.completeExceptionally(cause);
            }
        });
        return result;
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static RpcHandlingException invalidRequest() {
        return new RpcHandlingException("SKIN_REQUEST_INVALID", "Invalid skin request", false);
    }
}
