package cn.cctstudio.cctsystem.platform.paper;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

final class PaperLobbyConnector {
    private final JavaPlugin plugin;
    private final PaperMessages messages;
    private final String serverId;

    PaperLobbyConnector(JavaPlugin plugin, PaperMessages messages, String serverId) {
        this.plugin = plugin;
        this.messages = messages;
        this.serverId = serverId;
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(
            plugin, PaperNetworkChat.CHANNEL
        );
    }

    void connect(Player player) {
        if ("lobby".equalsIgnoreCase(serverId)) {
            player.sendActionBar(messages.component("lobby.already-there"));
            return;
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeUTF("CONNECT_LOBBY");
            }
            player.sendPluginMessage(plugin, PaperNetworkChat.CHANNEL, bytes.toByteArray());
            player.sendActionBar(messages.component("lobby.connecting"));
        } catch (IOException exception) {
            player.sendMessage(messages.component("lobby.failed"));
        }
    }
}
