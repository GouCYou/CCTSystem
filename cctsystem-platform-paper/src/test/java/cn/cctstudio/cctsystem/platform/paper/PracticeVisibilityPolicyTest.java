package cn.cctstudio.cctsystem.platform.paper;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class PracticeVisibilityPolicyTest {
    @Test void lobbyAndUnrelatedMatchesDoNotRevealVanishedPlayers() {
        Object fight = new Object();
        assertFalse(PracticeVisibilityPolicy.sameFight(null, null));
        assertFalse(PracticeVisibilityPolicy.sameFight(null, fight));
        assertFalse(PracticeVisibilityPolicy.sameFight(fight, null));
        assertFalse(PracticeVisibilityPolicy.sameFight(fight, new Object()));
    }

    @Test void sameMatchParticipantsSeeOneAnotherUntilMatchStateIsCleared() {
        Object fight = new Object();
        assertTrue(PracticeVisibilityPolicy.sameFight(fight, fight));
        assertFalse(PracticeVisibilityPolicy.sameFight(fight, null));
    }
}
