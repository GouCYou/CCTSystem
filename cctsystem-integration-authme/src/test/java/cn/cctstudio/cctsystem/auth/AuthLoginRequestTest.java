package cn.cctstudio.cctsystem.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.cctstudio.cctsystem.bridge.RpcHandlingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class AuthLoginRequestTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void acceptsMinecraftNameAndPassword() throws Exception {
        AuthLoginRequest request = AuthLoginRequest.parse(JSON.readTree(
            "{\"username\":\"Player_1\",\"password\":\"not-logged\"}"
        ));
        assertEquals("Player_1", request.playerName());
    }

    @Test
    void rejectsMalformedName() throws Exception {
        assertThrows(RpcHandlingException.class, () -> AuthLoginRequest.parse(JSON.readTree(
            "{\"username\":\"bad name\",\"password\":\"secret\"}"
        )));
    }
}
