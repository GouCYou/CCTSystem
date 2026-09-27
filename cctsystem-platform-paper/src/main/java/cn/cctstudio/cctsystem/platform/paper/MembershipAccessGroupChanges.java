package cn.cctstudio.cctsystem.platform.paper;

import java.util.Set;

final class MembershipAccessGroupChanges {
    private MembershipAccessGroupChanges() {
    }

    static boolean affectsAccess(
        Set<String> before,
        Set<String> after,
        Set<String> relevantGroups
    ) {
        return before.stream().anyMatch(group -> !after.contains(group) && relevantGroups.contains(group))
            || after.stream().anyMatch(group -> !before.contains(group) && relevantGroups.contains(group));
    }
}
