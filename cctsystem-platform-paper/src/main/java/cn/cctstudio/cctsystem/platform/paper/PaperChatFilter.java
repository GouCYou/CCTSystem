package cn.cctstudio.cctsystem.platform.paper;

import cn.cctstudio.cctsystem.core.filter.ReloadingChatFilter;
import cn.cctstudio.cctsystem.core.logging.CctLogger;
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

    PaperChatFilter(JavaPlugin plugin, CctLogger logger) {
        this.filter = new ReloadingChatFilter(
            plugin.getDataFolder().toPath().resolve("chat-filter.json"),
            plugin.getResource("chat-filter.json"),
            logger
        );
    }

    String filter(String value) {
        return filter.filter(value);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Component original = event.message();
        Component filtered = filtered(original);
        if (filtered != original) event.message(filtered);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        String original = event.getMessage();
        String filtered = filter.filterCommand(original);
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

    private Component filtered(Component original) {
        String plain = PLAIN.serialize(original);
        String value = filter.filter(plain);
        return value.equals(plain) ? original : Component.text(value);
    }
}
