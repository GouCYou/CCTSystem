package cn.cctstudio.cctsystem.platform.paper;

import cn.cctstudio.cctsystem.core.filter.ReloadingChatFilter;
import cn.cctstudio.cctsystem.core.logging.CctLogger;
import cn.cctstudio.cctsystem.core.filter.ChatRateLimiter;
import cn.cctstudio.cctsystem.core.provider.ProviderRegistry;
import cn.cctstudio.cctsystem.storage.mysql.DatabaseProvider;
import cn.cctstudio.cctsystem.identity.UuidBinary;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.UUID;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerQuitEvent;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerEditBookEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

final class PaperChatFilter implements Listener {
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private final ReloadingChatFilter filter;
    private final ChatRateLimiter limiter = new ChatRateLimiter();
    private final Semaphore pending = new Semaphore(128);
    private final ProviderRegistry providers;
    private final Executor executor;
    private final String serverId;
    private final JavaPlugin plugin;
    private static final ObjectMapper JSON = new ObjectMapper();

    PaperChatFilter(JavaPlugin plugin, CctLogger logger, ProviderRegistry providers, Executor executor, String serverId) {
        this.plugin = plugin;
        this.providers = providers;
        this.executor = executor;
        this.serverId = serverId;
        this.filter = new ReloadingChatFilter(
            plugin.getDataFolder().toPath().resolve("chat-filter.json"),
            plugin.getResource("chat-filter.json"),
            logger
        );
    }

    ReloadingChatFilter rules() { return filter; }

    String filter(String value) {
        return filter.filter(value);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (!acquire(event.getPlayer())) { event.setCancelled(true); return; }
        Component original = event.message();
        String raw = PLAIN.serialize(original);
        ReloadingChatFilter.Result result = filter.analyze(raw);
        record(event.getPlayer(), "CHAT", raw, result);
        Component filtered = result.filtered().equals(raw) ? original : Component.text(result.filtered());
        if (filtered != original) event.message(filtered);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        String original = event.getMessage();
        if (filter.isChatCommand(original) && !acquire(event.getPlayer())) { event.setCancelled(true); return; }
        ReloadingChatFilter.Result result = filter.analyzeCommand(original);
        record(event.getPlayer(), "COMMAND", original, result);
        String filtered = result.filtered();
        if (!filtered.equals(original)) event.setMessage(filtered);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onSign(SignChangeEvent event) {
        for (int line = 0; line < 4; line++) {
            Component original = event.line(line);
            Component filtered = filtered(original);
            if (filtered != original) event.line(line, filtered);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBook(PlayerEditBookEvent event) {
        BookMeta book = event.getNewBookMeta();
        List<Component> pages = book.pages();
        List<Component> filteredPages = pages.stream().map(this::filtered).toList();
        if (!filteredPages.equals(pages)) book.pages(filteredPages);
        Component title = book.title();
        if (title != null) {
            Component filteredTitle = filtered(title);
            if (filteredTitle != title) book.title(filteredTitle);
        }
        event.setNewBookMeta(book);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onAnvil(PrepareAnvilEvent event) {
        ItemStack result = event.getResult();
        if (result == null || result.getType().isAir()) return;
        ItemMeta meta = result.getItemMeta();
        if (meta == null || !meta.hasDisplayName() || meta.displayName() == null) return;
        Component original = meta.displayName();
        Component filtered = filtered(original);
        if (filtered == original) return;
        ItemStack updated = result.clone();
        ItemMeta updatedMeta = updated.getItemMeta();
        updatedMeta.displayName(filtered);
        updated.setItemMeta(updatedMeta);
        event.setResult(updated);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) { limiter.clear(event.getPlayer().getUniqueId()); }

    private boolean acquire(Player player) {
        long seconds = limiter.remainingSeconds(player.getUniqueId());
        if (seconds == 0) return true;
        player.sendMessage(Component.text("说话太快啦，稍慢一些！还需等待 " + seconds + " 秒。",
            net.kyori.adventure.text.format.NamedTextColor.RED));
        return false;
    }

    private void record(Player player, String source, String original, ReloadingChatFilter.Result result) {
        if (result.matchedWords().isEmpty()) return;
        var database = providers.find(DatabaseProvider.KEY);
        if (database.isEmpty()) { plugin.getLogger().warning("Chat filter audit database unavailable"); return; }
        if (!pending.tryAcquire()) { plugin.getLogger().warning("Chat filter audit queue full"); return; }
        UUID playerId = player.getUniqueId();
        String playerName = player.getName();
        java.time.Instant detectedAt = java.time.Instant.now();
        try {
            executor.execute(() -> {
                try (Connection connection = database.get().connection();
                     PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO cct_chat_filter_incidents
                        (incident_id, player_uuid, player_name, server_id, source_type, original_text,
                         filtered_text, matched_words, detected_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)) {
                    insert.setBytes(1, UuidBinary.encode(UUID.randomUUID()));
                    insert.setBytes(2, UuidBinary.encode(playerId));
                    insert.setString(3, playerName);
                    insert.setString(4, serverId);
                    insert.setString(5, source);
                    insert.setString(6, original);
                    insert.setString(7, result.filtered());
                    insert.setString(8, JSON.writeValueAsString(result.matchedWords()));
                    insert.setObject(9, cn.cctstudio.cctsystem.storage.mysql.BeijingDateTime.fromInstant(detectedAt));
                    insert.executeUpdate();
                } catch (Exception failure) {
                    plugin.getLogger().log(java.util.logging.Level.WARNING, "Unable to save chat filter incident", failure);
                } finally { pending.release(); }
            });
        } catch (RuntimeException failure) {
            pending.release();
            plugin.getLogger().log(java.util.logging.Level.WARNING, "Chat filter audit executor unavailable", failure);
        }
    }

    private Component filtered(Component original) {
        String plain = PLAIN.serialize(original);
        String value = filter.filter(plain);
        return value.equals(plain) ? original : Component.text(value);
    }
}
