package cn.cctstudio.cctsystem.platform.paper;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class DuelChatFormattingTest {
    @Test void administratorIdentityDoesNotColorMessageRedOrMakeItBold() {
        var identity = Component.text("[服主] GouC", NamedTextColor.RED).decorate(TextDecoration.BOLD);
        var result = DuelChatFormatting.format(identity, Component.text("hello"));
        assertNull(result.color());
        assertEquals(TextDecoration.State.NOT_SET, result.decoration(TextDecoration.BOLD));
        assertEquals(identity, result.children().get(0));
        assertEquals(NamedTextColor.GRAY, result.children().get(1).color());
        assertEquals(NamedTextColor.WHITE, result.children().get(2).color());
    }
    @Test void defaultIdentityDoesNotColorMessageGray() {
        var result = DuelChatFormatting.format(Component.text("Player", NamedTextColor.GRAY), Component.text("hello"));
        assertEquals(NamedTextColor.WHITE, result.children().get(2).color());
    }
    @Test void explicitMessageColorIsPreserved() {
        var message = Component.text("hello", NamedTextColor.GREEN);
        var result = DuelChatFormatting.format(Component.text("Player", NamedTextColor.RED), message);
        assertEquals(message, result.children().get(2));
    }
}
