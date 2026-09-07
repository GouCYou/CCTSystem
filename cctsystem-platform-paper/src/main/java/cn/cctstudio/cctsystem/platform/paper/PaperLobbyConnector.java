package cn.cctstudio.cctsystem.platform.paper;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
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
            teleportToDeluxeHubSpawn(player);
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

    private void teleportToDeluxeHubSpawn(Player player) {
        Location spawn;
        try {
            spawn = deluxeHubSpawn();
        } catch (ReflectiveOperationException | LinkageError exception) {
            plugin.getLogger().warning(
                "Unable to read the DeluxeHub lobby spawn: " + exception.getMessage()
            );
            player.sendMessage(messages.component("lobby.failed"));
            return;
        }
        if (spawn == null || spawn.getWorld() == null) {
            player.sendMessage(messages.component("lobby.failed"));
            return;
        }
        player.teleportAsync(spawn.clone()).thenAccept(success -> {
            if (success || !player.isOnline()) return;
            plugin.getServer().getScheduler().runTask(
                plugin,
                () -> player.sendMessage(messages.component("lobby.failed"))
            );
        });
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Location deluxeHubSpawn() throws ReflectiveOperationException {
        Plugin deluxeHub = plugin.getServer().getPluginManager().getPlugin("DeluxeHub");
        if (deluxeHub == null || !deluxeHub.isEnabled()) return null;
        ClassLoader loader = deluxeHub.getClass().getClassLoader();
        Class<?> moduleType = Class.forName(
            "net.zithium.deluxehub.module.ModuleType", true, loader
        );
        Object lobbyType = Enum.valueOf((Class<? extends Enum>) moduleType, "LOBBY");
        Object moduleManager = deluxeHub.getClass().getMethod("getModuleManager").invoke(deluxeHub);
        Method getModule = moduleManager.getClass().getMethod("getModule", moduleType);
        Object lobbyModule = getModule.invoke(moduleManager, lobbyType);
        if (lobbyModule == null) return null;
        try {
            return (Location) lobbyModule.getClass().getMethod("getLocation").invoke(lobbyModule);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof ReflectiveOperationException reflective) throw reflective;
            throw exception;
        }
    }
}
