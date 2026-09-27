package cn.cctstudio.cctsystem.platform.paper;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

final class PaperMembershipAccessSynchronizerTest {
    private static final Set<String> BLOCKED_GROUPS = Set.of("helper", "mod", "admin", "owner");

    @Test
    void detectsRelevantGroupAddition() {
        assertTrue(MembershipAccessGroupChanges.affectsAccess(
            Set.of(),
            Set.of("admin"),
            BLOCKED_GROUPS
        ));
    }

    @Test
    void detectsRelevantGroupRemoval() {
        assertTrue(MembershipAccessGroupChanges.affectsAccess(
            Set.of("helper"),
            Set.of(),
            BLOCKED_GROUPS
        ));
    }

    @Test
    void ignoresManagedMembershipGroupMutation() {
        assertFalse(MembershipAccessGroupChanges.affectsAccess(
            Set.of(),
            Set.of("vip"),
            BLOCKED_GROUPS
        ));
    }

    @Test
    void ignoresRecalculationWithoutRelevantNodeChange() {
        assertFalse(MembershipAccessGroupChanges.affectsAccess(
            Set.of("admin"),
            Set.of("admin"),
            BLOCKED_GROUPS
        ));
    }
}
