package cn.cctstudio.cctsystem.platform.paper;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

final class PaperNetworkChat {
    static final String CHANNEL = "cctsystem:network";
    private static final int MAX_MESSAGE_LENGTH = 200;
    private final JavaPlugin plugin;
    private final PaperMessages messages;
    private final PaperChatStyleProvider chatStyle;

    PaperNetworkChat(
        JavaPlugin plugin,
        PaperMessages messages,
        PaperChatStyleProvider chatStyle
    ) {
        this.plugin = plugin;
        this.messages = messages;
        this.chatStyle = chatStyle;
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, CHANNEL);
    }

    void shout(Player player, String message) {
        String value = message == null ? "" : message.strip();
        if (!player.hasPermission("cctsystem.shout")) {
            player.sendMessage(messages.component("commands.no-permission"));
            return;
        }
        if (value.isEmpty() || value.length() > MAX_MESSAGE_LENGTH) {
            player.sendMessage(messages.component("chat.shout-usage"));
            return;
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeUTF("SHOUT_V2");
                output.writeUTF(chatStyle.format(player, value));
                output.writeUTF(messages.raw(
                    "chat.shout-cooldown", "&c全服喊话冷却中，请等待 &e{seconds} 秒"
                ));
            }
            player.sendPluginMessage(plugin, CHANNEL, bytes.toByteArray());
        } catch (IOException exception) {
            player.sendMessage(messages.component("chat.shout-failed"));
        }
    }

    void broadcastSecurityAlert(String message) {
        String value = message == null ? "" : message.strip();
        if (value.isEmpty() || value.length() > 4096) {
            plugin.getLogger().warning("Skipped invalid CCTSystem security alert");
            return;
        }
        Player carrier = plugin.getServer().getOnlinePlayers().stream().findFirst().orElse(null);
        if (carrier == null) {
            plugin.getLogger().warning("Security alert could not cross the proxy because no player is online");
            return;
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeUTF("SECURITY_ALERT_V1");
                output.writeUTF(value);
            }
            carrier.sendPluginMessage(plugin, CHANNEL, bytes.toByteArray());
        } catch (IOException exception) {
            plugin.getLogger().warning("Unable to send CCTSystem security alert: " + exception.getMessage());
        }
    }
}
