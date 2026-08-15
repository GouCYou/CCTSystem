package cn.cctstudio.cctsystem.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

final class BridgeAuthenticatorTest {
    @Test
    void signatureIsStableAndBindsTheNodeIdentity() {
        String first = BridgeAuthenticator.sign(
            "01234567890123456789012345678901",
            BridgeAuthenticator.canonicalRequest(
                "/internal/minecraft/connect",
                "cct-main",
                "login",
                "login-1",
                1_700_000_000L,
                "9cc85376-4725-486c-a24c-7a50aa04ce21"
            )
        );
        String second = BridgeAuthenticator.sign(
            "01234567890123456789012345678901",
            BridgeAuthenticator.canonicalRequest(
                "/internal/minecraft/connect",
                "cct-main",
                "login",
                "login-1",
                1_700_000_000L,
                "9cc85376-4725-486c-a24c-7a50aa04ce21"
            )
        );
        String anotherNode = BridgeAuthenticator.sign(
            "01234567890123456789012345678901",
            BridgeAuthenticator.canonicalRequest(
                "/internal/minecraft/connect",
                "cct-main",
                "login",
                "login-2",
                1_700_000_000L,
                "9cc85376-4725-486c-a24c-7a50aa04ce21"
            )
        );

        assertEquals(first, second);
        assertEquals("411070512855325f2fecf57925b0878b4bb3e90834a5ae46c76838244e455945", first);
        assertNotEquals(first, anotherNode);
    }
}
