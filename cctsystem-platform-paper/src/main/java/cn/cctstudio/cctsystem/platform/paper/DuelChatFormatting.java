package cn.cctstudio.cctsystem.platform.paper;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

final class DuelChatFormatting {
    private DuelChatFormatting() {}

    static Component format(Component identity, Component message) {
        return Component.empty()
            .append(identity)
            .append(Component.text(" : ", NamedTextColor.GRAY))
            .append(message.colorIfAbsent(NamedTextColor.WHITE));
    }
}
