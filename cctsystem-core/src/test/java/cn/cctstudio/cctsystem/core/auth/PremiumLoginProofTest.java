package cn.cctstudio.cctsystem.core.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class PremiumLoginProofTest {
    @Test
    void acceptsOnlyTheSignedOfflineProfileWithinTheLoginWindow() {
        byte[] key = new byte[32];
        PremiumLoginProof proofs = new PremiumLoginProof(key, "test");
        Instant now = Instant.parse("2026-11-21T08:00:00Z");
        String name = "TestPlayer";
        UUID mojang = UUID.randomUUID();
        UUID offline = PremiumLoginProof.offlineUuid(name);
        String proof = proofs.issue(name, mojang, now);

        assertEquals(mojang, proofs.verify(proof, name, offline, now).orElseThrow().mojangUuid());
        assertEquals(0, proofs.verify(proof, name, offline, now).orElseThrow().bindingRequestedAt());
        assertTrue(proofs.verify(proof, name, mojang, now).isEmpty());
        assertTrue(proofs.verify(proof, "testplayer", PremiumLoginProof.offlineUuid("testplayer"), now).isEmpty());
        assertTrue(proofs.verify(proof, name, offline, now.plusSeconds(601)).isEmpty());
        assertTrue(proofs.verify(mojang + "." + proof, name, offline, now).isEmpty());
        assertTrue(new PremiumLoginProof(key, "another-network").verify(proof, name, offline, now).isEmpty());
        key[0] = 1;
        assertTrue(new PremiumLoginProof(key, "test").verify(proof, name, offline, now).isEmpty());
    }

    @Test
    void bindingRequestTimeIsSignedSeparatelyFromTheSessionIssueTime() {
        var proofs = new PremiumLoginProof(new byte[32], "test");
        Instant now = Instant.parse("2026-11-24T08:00:00Z");
        String name = "TestPlayer";
        UUID offline = PremiumLoginProof.offlineUuid(name);
        long startedAt = now.minusSeconds(10).toEpochMilli();
        String proof = proofs.issue(name, UUID.randomUUID(), now, startedAt);
        assertEquals(startedAt, proofs.verify(proof, name, offline, now).orElseThrow().bindingRequestedAt());
        assertTrue(proofs.verify(proof.replace("." + startedAt + ".", "." + (startedAt + 1) + "."),
            name, offline, now).isEmpty());
    }
}
