package cn.cctstudio.cctsystem.platform.paper;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/** Keeps duel public chat independent from StrikePractice's world-prefixed display name. */
final class PaperDuelChatRenderer implements Listener {
    private final PaperNicknameService nicknames;

    PaperDuelChatRenderer(PaperNicknameService nicknames) {
        this.nicknames = nicknames;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        event.renderer((source, sourceDisplayName, message, viewer) ->
            nicknames.identity(source)
                .append(Component.text(" : ", NamedTextColor.GRAY))
                .append(message)
        );
    }
}
