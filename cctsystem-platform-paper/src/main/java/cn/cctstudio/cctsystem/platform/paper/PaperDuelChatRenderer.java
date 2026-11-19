package cn.cctstudio.cctsystem.platform.paper;

import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/** Fallback for duel servers without a dedicated chat formatter. */
final class PaperDuelChatRenderer implements Listener {
    private final PaperNicknameService nicknames;

    PaperDuelChatRenderer(PaperNicknameService nicknames) {
        this.nicknames = nicknames;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        // ChatFormatter supplies a renderer at LOWEST. Replacing it here
        // discards configured formats and leaks the identity's root color.
        if (event.getPlayer().getServer().getPluginManager().isPluginEnabled("ChatFormatter")) {
            return;
        }
        event.renderer((source, sourceDisplayName, message, viewer) ->
            DuelChatFormatting.format(nicknames.identity(source), message)
        );
    }
}
