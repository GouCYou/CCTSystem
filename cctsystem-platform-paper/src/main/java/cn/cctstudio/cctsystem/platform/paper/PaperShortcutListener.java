package cn.cctstudio.cctsystem.platform.paper;

import java.util.Arrays;
import java.util.Locale;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

/** Ensures the CCT commands win aliases also registered by CMI or other plugins. */
final class PaperShortcutListener implements Listener {
    private final PaperNicknameService nicknames;
    private final PaperNetworkChat networkChat;
    private final PaperMessages messages;
    private final PaperRewardsMenu rewards;
    private final PaperLobbyConnector lobby;
    private final PaperVanishService vanish;
    private final String serverId;

    PaperShortcutListener(
        PaperNicknameService nicknames,
        PaperNetworkChat networkChat,
        PaperMessages messages,
        PaperRewardsMenu rewards,
        PaperLobbyConnector lobby,
        PaperVanishService vanish,
        String serverId
    ) {
        this.nicknames = nicknames;
        this.networkChat = networkChat;
        this.messages = messages;
        this.rewards = rewards;
        this.lobby = lobby;
        this.vanish = vanish;
        this.serverId = serverId;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        String[] input = event.getMessage().substring(1).strip().split("\\s+");
        if (input.length == 0) return;
        String command = input[0].toLowerCase(Locale.ROOT);
        if (!command.equals("nick") && !command.equals("unnick") && !command.equals("shout")
            && !command.equals("rewards") && !command.equals("lobby")
            && !command.equals("hub") && !command.equals("l")
            && !command.equals("v") && !command.equals("vanish")) return;
        // AuthMe owns /l <password> on the login node. Do not cancel or reroute it.
        if (command.equals("l") && serverId.equalsIgnoreCase("login")) return;
        if (command.equals("v") || command.equals("vanish")) {
            event.setCancelled(true);
            vanish.toggle(event.getPlayer());
            return;
        }
        event.setCancelled(true);
        switch (command) {
            case "nick" -> nicknames.command(
                event.getPlayer(), Arrays.copyOfRange(input, 1, input.length)
            );
            case "unnick" -> nicknames.disable(event.getPlayer());
            case "shout" -> {
                if (input.length < 2) {
                    event.getPlayer().sendMessage(messages.component("chat.shout-usage"));
                } else {
                    networkChat.shout(event.getPlayer(), String.join(" ", Arrays.copyOfRange(input, 1, input.length)));
                }
            }
            case "rewards" -> rewards.open(event.getPlayer());
            case "lobby", "hub", "l" -> lobby.connect(event.getPlayer());
            default -> { }
        }
    }
}
