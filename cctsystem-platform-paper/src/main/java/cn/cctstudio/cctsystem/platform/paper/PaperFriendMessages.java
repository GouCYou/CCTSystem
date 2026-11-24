package cn.cctstudio.cctsystem.platform.paper;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Locale;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.plugin.java.JavaPlugin;

final class PaperFriendMessages implements Listener {
    private final JavaPlugin plugin;

    PaperFriendMessages(JavaPlugin plugin) {
        this.plugin = plugin;
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, PaperNetworkChat.CHANNEL);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        String[] command = event.getMessage().split("\\s+", 2);
        if (!command[0].toLowerCase(Locale.ROOT).equals("/msg")) return;
        event.setCancelled(true);
        String arguments = command.length == 2 ? command[1] : "";
        if (arguments.length() > 512) return;
        var buffer = new ByteArrayOutputStream();
        try (var data = new DataOutputStream(buffer)) {
            data.writeUTF("FRIEND_MESSAGE");
            data.writeUTF(arguments);
        } catch (IOException exception) {
            throw new java.io.UncheckedIOException(exception);
        }
        event.getPlayer().sendPluginMessage(plugin, PaperNetworkChat.CHANNEL, buffer.toByteArray());
    }
}
