package cn.cctstudio.cctsystem.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuthMePasswordVerifierTest {
    @Test
    void generatesVelocityCompatibleOfflineUuidFromCanonicalName() {
        assertEquals(
            UUID.fromString("23b0f97c-0dd3-35d7-805d-d7f018af7b7d"),
            AuthMePasswordVerifier.offlineUuid("GouC")
        );
        assertNotEquals(
            AuthMePasswordVerifier.offlineUuid("GouC"),
            AuthMePasswordVerifier.offlineUuid("gouc")
        );
    }
}
