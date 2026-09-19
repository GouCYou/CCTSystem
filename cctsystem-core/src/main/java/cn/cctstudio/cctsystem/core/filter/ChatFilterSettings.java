package cn.cctstudio.cctsystem.core.filter;

import java.util.List;
import java.util.Locale;
import java.util.Map;

public record ChatFilterSettings(
    boolean enabled,
    String replacement,
    int reloadSeconds,
    boolean ignoreSeparators,
    boolean normalizeLeetspeak,
    List<String> words,
    List<String> allowlist,
    Map<String, Integer> commands
) {
    public ChatFilterSettings {
        replacement = replacement == null || replacement.isEmpty() ? "*" : replacement;
        reloadSeconds = Math.max(1, reloadSeconds);
        words = words == null ? List.of() : List.copyOf(words);
        allowlist = allowlist == null ? List.of() : List.copyOf(allowlist);
        commands = normalizeCommands(commands);
    }

    private static Map<String, Integer> normalizeCommands(Map<String, Integer> source) {
        if (source == null) return Map.of();
        java.util.LinkedHashMap<String, Integer> result = new java.util.LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key == null || key.isBlank()) return;
            result.put(key.trim().toLowerCase(Locale.ROOT), Math.max(0, value == null ? 0 : value));
        });
        return java.util.Collections.unmodifiableMap(result);
    }
}
