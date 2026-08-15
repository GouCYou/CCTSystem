package cn.cctstudio.cctsystem.auth;

import cn.cctstudio.cctsystem.bridge.BridgeProvider;
import cn.cctstudio.cctsystem.bridge.BridgeRpcRouter;
import cn.cctstudio.cctsystem.bridge.RpcHandlingException;
import cn.cctstudio.cctsystem.contract.Capability;
import cn.cctstudio.cctsystem.contract.NodeRole;
import cn.cctstudio.cctsystem.contract.PlatformType;
import cn.cctstudio.cctsystem.core.module.CctModule;
import cn.cctstudio.cctsystem.core.module.ModuleContext;
import cn.cctstudio.cctsystem.core.module.ModuleDescriptor;
import cn.cctstudio.cctsystem.identity.IdentityProvider;
import cn.cctstudio.cctsystem.identity.IdentityService;
import cn.cctstudio.cctsystem.identity.PlayerIdentity;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class AuthModule implements CctModule {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ModuleDescriptor DESCRIPTOR = new ModuleDescriptor(
        "auth",
        Set.of(PlatformType.PAPER),
        Set.of(NodeRole.AUTH_AUTHORITY),
        Set.of(BridgeProvider.ID, IdentityProvider.ID, AuthMeProvider.ID),
        Set.of(Capability.AUTH_VERIFY)
    );

    @Override
    public ModuleDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public CompletableFuture<Void> start(ModuleContext context) {
        BridgeRpcRouter router = context.providers().find(BridgeProvider.KEY)
            .orElseThrow(() -> new IllegalStateException("Bridge router provider is unavailable"));
        IdentityService identities = context.providers().find(IdentityProvider.KEY)
            .orElseThrow(() -> new IllegalStateException("Identity provider is unavailable"));
        PasswordVerifier passwords = context.providers().find(AuthMeProvider.KEY)
            .orElseThrow(() -> new IllegalStateException("AuthMe provider is unavailable"));
        router.register(
            Capability.AUTH_VERIFY.value(),
            payload -> authenticate(payload, identities, passwords, context)
        );
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> stop() {
        return CompletableFuture.completedFuture(null);
    }

    private CompletionStage<com.fasterxml.jackson.databind.JsonNode> authenticate(
        com.fasterxml.jackson.databind.JsonNode payload,
        IdentityService identities,
        PasswordVerifier passwords,
        ModuleContext context
    ) {
        AuthLoginRequest request = AuthLoginRequest.parse(payload);
        return CompletableFuture.supplyAsync(
            () -> passwords.verify(request.playerName(), request.password()),
            context.executors().blocking()
        ).thenCompose(verification -> {
            if (!verification.authenticated()) {
                return CompletableFuture.failedFuture(new RpcHandlingException(
                    "AUTH_INVALID_CREDENTIALS",
                    "Invalid username or password",
                    false
                ));
            }
            Optional<java.util.UUID> authMeUuid = verification.playerUuid();
            if (authMeUuid.isPresent()) {
                return identities.recordSeen(
                    authMeUuid.orElseThrow(),
                    verification.displayName(),
                    Instant.now()
                );
            }
            return identities.findByName(verification.displayName()).thenApply(identity -> identity.orElseThrow(
                () -> new RpcHandlingException(
                    "AUTH_IDENTITY_NOT_READY",
                    "Player identity is not available yet",
                    false
                )
            ));
        }).thenApply(AuthModule::response);
    }

    private static ObjectNode response(PlayerIdentity identity) {
        ObjectNode result = JSON.createObjectNode();
        result.put("playerUuid", identity.playerUuid().toString());
        result.put("displayName", identity.displayName());
        return result;
    }
}
