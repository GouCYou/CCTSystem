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
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class AuthModule implements CctModule {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ModuleDescriptor DESCRIPTOR = new ModuleDescriptor(
        "auth",
        Set.of(PlatformType.PAPER),
        Set.of(NodeRole.AUTH_AUTHORITY),
        Set.of(BridgeProvider.ID, IdentityProvider.ID, AuthMeProvider.ID),
        Set.of(Capability.AUTH_VERIFY, Capability.ACCOUNT_SECURITY_READ, Capability.ACCOUNT_SECURITY_MUTATE)
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
        router.register(
            Capability.ACCOUNT_SECURITY_READ.value(),
            payload -> readSecurity(payload, identities, passwords, context)
        );
        router.register(
            Capability.ACCOUNT_SECURITY_MUTATE.value(),
            payload -> changePassword(payload, identities, passwords, context)
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
        CompletionStage<String> resolvedName = request.playerUuid().isPresent()
            ? identities.findByUuid(request.playerUuid().orElseThrow()).thenApply(identity -> identity
                .map(PlayerIdentity::displayName)
                .orElseThrow(() -> invalidCredentials()))
            : CompletableFuture.completedFuture(request.identifier());
        return resolvedName.thenCompose(playerName -> CompletableFuture.supplyAsync(
            () -> passwords.verify(playerName, request.password()),
            context.executors().blocking()
        )).thenCompose(verification -> {
            if (!verification.authenticated()) {
                return CompletableFuture.failedFuture(invalidCredentials());
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

    private static RpcHandlingException invalidCredentials() {
        return new RpcHandlingException(
            "AUTH_INVALID_CREDENTIALS",
            "Invalid username or password",
            false
        );
    }

    private static ObjectNode response(PlayerIdentity identity) {
        ObjectNode result = JSON.createObjectNode();
        result.put("playerUuid", identity.playerUuid().toString());
        result.put("displayName", identity.displayName());
        return result;
    }

    private CompletionStage<com.fasterxml.jackson.databind.JsonNode> readSecurity(
        com.fasterxml.jackson.databind.JsonNode payload,
        IdentityService identities,
        PasswordVerifier passwords,
        ModuleContext context
    ) {
        UUID playerUuid = requireUuid(payload);
        return requireIdentity(identities, playerUuid).thenCompose(identity -> CompletableFuture.supplyAsync(
            () -> passwords.accountDetails(identity.displayName()).orElseThrow(AuthModule::invalidCredentials),
            context.executors().blocking()
        )).thenCompose(details -> passwords.qqBound(playerUuid).thenApply(qqBound -> {
            ObjectNode result = JSON.createObjectNode();
            details.email().ifPresentOrElse(
                email -> result.put("email", email),
                () -> result.putNull("email")
            );
            result.put("registeredAt", details.registeredAt().toString());
            details.lastLoginAt().ifPresentOrElse(
                value -> result.put("lastLoginAt", value.toString()),
                () -> result.putNull("lastLoginAt")
            );
            details.lastLoginIp().ifPresentOrElse(
                value -> result.put("lastLoginIp", value),
                () -> result.putNull("lastLoginIp")
            );
            result.put("qqBound", qqBound);
            return result;
        }));
    }

    private CompletionStage<com.fasterxml.jackson.databind.JsonNode> changePassword(
        com.fasterxml.jackson.databind.JsonNode payload,
        IdentityService identities,
        PasswordVerifier passwords,
        ModuleContext context
    ) {
        UUID playerUuid = requireUuid(payload);
        String currentPassword = requirePassword(payload, "currentPassword", 1);
        String newPassword = requirePassword(payload, "newPassword", 6);
        return requireIdentity(identities, playerUuid).thenCompose(identity -> CompletableFuture.supplyAsync(() -> {
            if (!passwords.verify(identity.displayName(), currentPassword).authenticated()) {
                throw invalidCredentials();
            }
            passwords.changePassword(identity.displayName(), newPassword);
            ObjectNode result = JSON.createObjectNode();
            result.put("changed", true);
            return result;
        }, context.executors().blocking()));
    }

    private static CompletionStage<PlayerIdentity> requireIdentity(IdentityService identities, UUID playerUuid) {
        return identities.findByUuid(playerUuid).thenApply(identity -> identity.orElseThrow(AuthModule::invalidCredentials));
    }

    private static UUID requireUuid(com.fasterxml.jackson.databind.JsonNode payload) {
        if (payload == null || !payload.path("playerUuid").isTextual()) {
            throw new RpcHandlingException("AUTH_REQUEST_INVALID", "Invalid account request", false);
        }
        try {
            return UUID.fromString(payload.path("playerUuid").textValue());
        } catch (IllegalArgumentException exception) {
            throw new RpcHandlingException("AUTH_REQUEST_INVALID", "Invalid account request", false);
        }
    }

    private static String requirePassword(com.fasterxml.jackson.databind.JsonNode payload, String field, int minimum) {
        if (!payload.path(field).isTextual()) {
            throw new RpcHandlingException("AUTH_REQUEST_INVALID", "Invalid account request", false);
        }
        String value = payload.path(field).textValue();
        if (value.length() < minimum || value.length() > 256) {
            throw new RpcHandlingException("AUTH_REQUEST_INVALID", "Invalid account request", false);
        }
        return value;
    }
}
