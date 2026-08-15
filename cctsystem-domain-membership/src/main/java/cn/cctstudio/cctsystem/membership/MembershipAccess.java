package cn.cctstudio.cctsystem.membership;

import java.util.Locale;
import java.util.Set;

public record MembershipAccess(Set<String> groups) {
    public MembershipAccess {
        groups = groups == null
            ? Set.of()
            : groups.stream()
                .filter(group -> group != null && !group.isBlank())
                .map(group -> group.trim().toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public boolean belongsToAny(Set<String> candidates) {
        return candidates.stream()
            .map(group -> group.toLowerCase(Locale.ROOT))
            .anyMatch(groups::contains);
    }
}
