package cn.cctstudio.cctsystem.auth;

import cn.cctstudio.cctsystem.bridge.RpcHandlingException;
import cn.cctstudio.cctsystem.identity.PlayerNames;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;
import java.util.UUID;

record AuthLoginRequest(String identifier, Optional<UUID> playerUuid, String password) {
    private static final int MAX_PASSWORD_LENGTH = 256;

    static AuthLoginRequest parse(JsonNode payload) {
        if (payload == null || !payload.isObject()) {
            throw invalidRequest();
        }
        JsonNode playerNameNode = payload.get("username");
        JsonNode passwordNode = payload.get("password");
        if (playerNameNode == null || !playerNameNode.isTextual()
            || passwordNode == null || !passwordNode.isTextual()) {
            throw invalidRequest();
        }
        String identifier = playerNameNode.textValue();
        Optional<UUID> playerUuid = parseUuid(identifier);
        if (playerUuid.isEmpty()) {
            try {
                identifier = PlayerNames.requireValid(identifier);
            } catch (IllegalArgumentException exception) {
                throw invalidRequest();
            }
        }
        String password = passwordNode.textValue();
        if (password.isEmpty() || password.length() > MAX_PASSWORD_LENGTH) {
            throw invalidRequest();
        }
        return new AuthLoginRequest(identifier, playerUuid, password);
    }

    private static Optional<UUID> parseUuid(String value) {
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private static RpcHandlingException invalidRequest() {
        return new RpcHandlingException("AUTH_REQUEST_INVALID", "Invalid login request", false);
    }
}
