package cn.cctstudio.cctsystem.platform.paper;

import cn.cctstudio.cctsystem.core.concurrent.PlatformTaskExecutor;
import cn.cctstudio.cctsystem.core.logging.CctLogger;
import cn.cctstudio.cctsystem.core.provider.ProviderRegistry;
import cn.cctstudio.cctsystem.storage.mysql.DatabaseAccess;
import cn.cctstudio.cctsystem.storage.mysql.DatabaseProvider;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/** Persistent network vanish. Paper owns gameplay visibility; Velocity owns global counts/tab. */
final class PaperVanishService implements Listener {
    private final JavaPlugin plugin;
    private final ProviderRegistry providers;
    private final PlatformTaskExecutor platformTasks;
    private final Executor blockingExecutor;
    private final CctLogger logger;
    private final PaperMessages messages;
    private final String serverId;
    private final Set<UUID> vanished = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Boolean> pendingLogin = new ConcurrentHashMap<>();
    private final Map<UUID, CompletableFuture<?>> writes = new ConcurrentHashMap<>();
    private final Map<UUID, BossBar> bossBars = new ConcurrentHashMap<>();
    private final Set<UUID> cctNightVision = ConcurrentHashMap.newKeySet();
    private final PaperCmiAfkAdapter cmiAfk;

    PaperVanishService(
        JavaPlugin plugin,
        ProviderRegistry providers,
        PlatformTaskExecutor platformTasks,
        Executor blockingExecutor,
        CctLogger logger,
        PaperMessages messages,
        String serverId
    ) {
        this.plugin = plugin;
        this.providers = providers;
        this.platformTasks = platformTasks;
        this.blockingExecutor = blockingExecutor;
        this.logger = logger;
        this.messages = messages;
        this.serverId = serverId;
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(
            plugin, PaperNetworkChat.CHANNEL
        );
        cmiAfk = PaperCmiAfkAdapter.install(plugin, vanished::contains, logger);
    }

    void toggle(Player player) {
        if (!player.hasPermission("cctsystem.vanish")) {
            player.sendMessage(messages.component("commands.no-permission"));
            return;
        }
        UUID uuid = player.getUniqueId();
        if (writes.containsKey(uuid)) {
            player.sendActionBar(messages.component("vanish.processing"));
            return;
        }
        boolean enabled = !vanished.contains(uuid);
        player.sendActionBar(messages.component("vanish.processing"));
        CompletableFuture<Void> write = CompletableFuture.runAsync(
            () -> persist(uuid, enabled, player.getName()), blockingExecutor
        );
        writes.put(uuid, write);
        write.whenComplete((ignored, failure) -> main(() -> {
            writes.remove(uuid, write);
            if (!player.isOnline()) return;
            if (failure != null) {
                logger.warn("Unable to update vanish state for " + uuid, unwrap(failure));
                player.sendMessage(messages.component("vanish.failed"));
                return;
            }
            apply(player, enabled, true);
        }));
    }

    boolean isVanished(UUID uuid) {
        return vanished.contains(uuid);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        DatabaseAccess database = database();
        if (database == null) return;
        try {
            pendingLogin.put(event.getUniqueId(), load(database, event.getUniqueId()));
        } catch (RuntimeException exception) {
            logger.warn("Unable to preload vanish state for " + event.getUniqueId(), exception);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        boolean enabled = pendingLogin.remove(player.getUniqueId()) == Boolean.TRUE;
        if (enabled) {
            event.joinMessage(null);
            apply(player, true, false);
        }
        for (Player target : plugin.getServer().getOnlinePlayers()) {
            if (vanished.contains(target.getUniqueId())) hideFrom(player, target);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onQuit(PlayerQuitEvent event) {
        if (vanished.contains(event.getPlayer().getUniqueId())) event.quitMessage(null);
        BossBar bar = bossBars.remove(event.getPlayer().getUniqueId());
        if (bar != null) event.getPlayer().hideBossBar(bar);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onContainer(PlayerInteractEvent event) {
        if (!vanished.contains(event.getPlayer().getUniqueId())
            || event.getAction() != Action.RIGHT_CLICK_BLOCK
            || event.getClickedBlock() == null
            || !(event.getClickedBlock().getState() instanceof Container container)) return;
        Inventory source = container.getInventory();
        if (source.getSize() < 9 || source.getSize() % 9 != 0) return;
        event.setCancelled(true);
        SilentHolder holder = new SilentHolder();
        holder.inventory = plugin.getServer().createInventory(
            holder,
            source.getSize(),
            messages.component("vanish.silent-container")
        );
        ItemStack[] contents = source.getContents();
        ItemStack[] copies = new ItemStack[contents.length];
        for (int index = 0; index < contents.length; index++) {
            copies[index] = contents[index] == null ? null : contents[index].clone();
        }
        holder.inventory.setContents(copies);
        event.getPlayer().openInventory(holder.inventory);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSilentClick(InventoryClickEvent event) {
        if (event.getInventory().getHolder(false) instanceof SilentHolder) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSilentDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder(false) instanceof SilentHolder) event.setCancelled(true);
    }

    private void apply(Player player, boolean enabled, boolean notify) {
        if (enabled) {
            vanished.add(player.getUniqueId());
            cmiAfk.leaveAfkSilently(player);
            for (Player viewer : plugin.getServer().getOnlinePlayers()) hideFrom(viewer, player);
            BossBar bar = BossBar.bossBar(
                messages.component("vanish.bossbar").decoration(TextDecoration.ITALIC, false),
                1.0f,
                BossBar.Color.WHITE,
                BossBar.Overlay.PROGRESS
            );
            BossBar previous = bossBars.put(player.getUniqueId(), bar);
            if (previous != null) player.hideBossBar(previous);
            player.showBossBar(bar);
            if (!player.hasPotionEffect(PotionEffectType.NIGHT_VISION)) {
                player.addPotionEffect(new PotionEffect(
                    PotionEffectType.NIGHT_VISION,
                    PotionEffect.INFINITE_DURATION,
                    0,
                    false,
                    false,
                    false
                ));
                cctNightVision.add(player.getUniqueId());
            }
        } else {
            vanished.remove(player.getUniqueId());
            for (Player viewer : plugin.getServer().getOnlinePlayers()) {
                viewer.showPlayer(plugin, player);
            }
            BossBar bar = bossBars.remove(player.getUniqueId());
            if (bar != null) player.hideBossBar(bar);
            if (cctNightVision.remove(player.getUniqueId())) {
                player.removePotionEffect(PotionEffectType.NIGHT_VISION);
            }
        }
        sendState(player, enabled);
        if (notify) player.sendMessage(messages.component(
            enabled ? "vanish.enabled" : "vanish.disabled"
        ));
    }

    private void hideFrom(Player viewer, Player target) {
        if (viewer.getUniqueId().equals(target.getUniqueId())) return;
        if (!viewer.hasPermission("cctsystem.vanish.see")) viewer.hidePlayer(plugin, target);
    }

    private void sendState(Player player, boolean enabled) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeUTF("VANISH_STATE");
                output.writeUTF(player.getUniqueId().toString());
                output.writeBoolean(enabled);
                output.writeUTF(serverId);
            }
            player.sendPluginMessage(plugin, PaperNetworkChat.CHANNEL, bytes.toByteArray());
        } catch (IOException exception) {
            logger.warn("Unable to publish vanish state for " + player.getUniqueId(), exception);
        }
    }

    private boolean load(DatabaseAccess database, UUID uuid) {
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(
                 "SELECT enabled FROM cct_vanish_profiles WHERE player_uuid = ?"
             )) {
            statement.setBytes(1, uuidBytes(uuid));
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to load vanish state", exception);
        }
    }

    private void persist(UUID uuid, boolean enabled, String actor) {
        DatabaseAccess database = database();
        if (database == null) throw new IllegalStateException("CCTSystem database is unavailable");
        try (Connection connection = database.connection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement update = connection.prepareStatement("""
                INSERT INTO cct_vanish_profiles(player_uuid, enabled, updated_by)
                VALUES (?, ?, ?)
                ON DUPLICATE KEY UPDATE enabled = VALUES(enabled), updated_by = VALUES(updated_by)
                """);
                 PreparedStatement audit = connection.prepareStatement("""
                INSERT INTO cct_vanish_audit(
                    audit_id, player_uuid, enabled, actor, server_id
                ) VALUES (?, ?, ?, ?, ?)
                """)) {
                update.setBytes(1, uuidBytes(uuid));
                update.setBoolean(2, enabled);
                update.setString(3, actor);
                update.executeUpdate();
                audit.setBytes(1, uuidBytes(UUID.randomUUID()));
                audit.setBytes(2, uuidBytes(uuid));
                audit.setBoolean(3, enabled);
                audit.setString(4, actor);
                audit.setString(5, serverId);
                audit.executeUpdate();
                connection.commit();
            } catch (SQLException failure) {
                connection.rollback();
                throw failure;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to persist vanish state", exception);
        }
    }

    private DatabaseAccess database() {
        return providers.find(DatabaseProvider.KEY).orElse(null);
    }

    private void main(Runnable action) {
        platformTasks.callMain(() -> {
            action.run();
            return null;
        });
    }

    private static byte[] uuidBytes(UUID uuid) {
        return ByteBuffer.allocate(16)
            .putLong(uuid.getMostSignificantBits())
            .putLong(uuid.getLeastSignificantBits())
            .array();
    }

    private static Throwable unwrap(Throwable throwable) {
        return throwable instanceof java.util.concurrent.CompletionException
            && throwable.getCause() != null ? throwable.getCause() : throwable;
    }

    private static final class SilentHolder implements InventoryHolder {
        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
