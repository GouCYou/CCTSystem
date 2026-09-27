package cn.cctstudio.cctsystem.platform.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class PaperTabIntegrationTest {
    @Test
    void defaultTitleKeepsExactlyOneSpaceAfterWorldTag() {
        assertEquals(
            "&f[&a主世界&f] &7",
            TabAffixFormatting.replaceAffix(
                "&f[&a主世界&f] %cctsystem_prefix%",
                "&7",
                true
            )
        );
    }

    @Test
    void visibleTitleKeepsOneSpaceBeforePlayerName() {
        assertEquals(
            "&f[&a主世界&f] &6[VIP] ",
            TabAffixFormatting.replaceAffix(
                "&f[&a主世界&f] %cctsystem_prefix%",
                "&6[VIP]",
                true
            )
        );
    }

    @Test
    void suffixStillKeepsOneSpaceAfterPlayerName() {
        assertEquals(
            " &d[群主]",
            TabAffixFormatting.replaceAffix(
                "%cctsystem_suffix%",
                "&d[群主]",
                false
            )
        );
    }
}
