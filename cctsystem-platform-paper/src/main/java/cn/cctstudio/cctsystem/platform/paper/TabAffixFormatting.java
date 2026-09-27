package cn.cctstudio.cctsystem.platform.paper;

final class TabAffixFormatting {
    private TabAffixFormatting() {
    }

    static String replaceAffix(String template, String replacement, boolean prefix) {
        String value = template == null ? "" : template;
        String affix = replacement == null ? "" : replacement.strip();
        String inserted = prefix || !hasVisibleText(affix) ? affix : " " + affix;
        String replaced = value
            .replace("%vault_prefix%", inserted)
            .replace("%vault-prefix%", inserted)
            .replace("%luckperms_prefix%", inserted)
            .replace("%cctsystem_prefix%", inserted)
            .replace("%vault_suffix%", inserted)
            .replace("%vault-suffix%", inserted)
            .replace("%luckperms_suffix%", inserted)
            .replace("%cctsystem_suffix%", inserted);
        if (replaced.equals(value)
            && !value.contains("%vault_prefix%")
            && !value.contains("%vault-prefix%")
            && !value.contains("%luckperms_prefix%")
            && !value.contains("%cctsystem_prefix%")
            && !value.contains("%vault_suffix%")
            && !value.contains("%vault-suffix%")
            && !value.contains("%luckperms_suffix%")
            && !value.contains("%cctsystem_suffix%")) {
            replaced = prefix ? affix : inserted + value;
        }
        String result = replaced.replaceAll("](?=(?:&[0-9A-FK-ORX])*\\[)", "] ")
            .replaceAll("[ \\t]{2,}", " ")
            .stripTrailing();
        // Visible titles need a separator before the player ID. Formatting-only default
        // prefixes retain the single separator already present in the TAB template.
        return prefix && hasVisibleText(affix) ? result + " " : result;
    }

    private static boolean hasVisibleText(String value) {
        return !value.replaceAll("(?i)&[0-9A-FK-ORX]", "").isBlank();
    }
}
