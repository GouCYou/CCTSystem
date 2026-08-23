package cn.cctstudio.cctsystem.platform.paper;

import java.io.File;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

final class PaperRechargeMenu implements Listener {
    private static final int POINTS_PER_CNY = 20;

    private final JavaPlugin plugin;
    private final PaperMessages messages;
    private final PaperMenus menus;
    private final String paymentPage;

    PaperRechargeMenu(JavaPlugin plugin, PaperMessages messages, PaperMenus menus) {
        this.plugin = plugin;
        this.messages = messages;
        this.menus = menus;
        YamlConfiguration config = YamlConfiguration.loadConfiguration(
            new File(plugin.getDataFolder(), "menus/recharge.yml")
        );
        this.paymentPage = config.getString(
            "payment-page", "https://www.cctstudio.cn/account"
        );
    }

    void open(Player player) {
        PaperMenus.MenuDefinition layout = menus.get("recharge");
        RechargeHolder holder = new RechargeHolder(layout);
        holder.inventory = plugin.getServer().createInventory(
            holder, layout.size(), messages.component(layout.titleKey())
        );
        render(holder.inventory, layout);
        player.openInventory(holder.inventory);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder(false) instanceof RechargeHolder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)
            || event.getClickedInventory() != event.getView().getTopInventory()) return;
        String action = holder.layout.actionAt(event.getRawSlot());
        if ("CLOSE".equals(action)) {
            player.closeInventory();
            return;
        }
        if (!"TOP_UP".equals(action)) return;
        int amount;
        try {
            amount = Integer.parseInt(holder.layout.valueAt(event.getRawSlot()));
        } catch (NumberFormatException exception) {
            player.sendMessage(messages.component("menus.recharge.invalid-amount"));
            return;
        }
        if (amount < 1 || amount > 1000) {
            player.sendMessage(messages.component("menus.recharge.invalid-amount"));
            return;
        }
        player.closeInventory();
        String separator = paymentPage.contains("?") ? "&" : "?";
        String url = paymentPage + separator + "panel=recharge&amount=" + amount;
        Component link = messages.component(
            "menus.recharge.open-payment", Map.of("amount", amount)
        ).clickEvent(ClickEvent.openUrl(url)).hoverEvent(HoverEvent.showText(
            messages.component("menus.recharge.open-payment-hover")
        ));
        player.sendMessage(link);
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder(false) instanceof RechargeHolder) {
            event.setCancelled(true);
        }
    }

    private void render(Inventory inventory, PaperMenus.MenuDefinition layout) {
        layout.fill(inventory);
        for (PaperMenus.MenuItemDefinition definition : layout.items().values()) {
            switch (definition.action()) {
                case "PROFILE" -> inventory.setItem(definition.slot(), item(
                    definition.material(Material.GOLD_INGOT),
                    messages.component("menus.recharge.profile-name"),
                    messages.components("menus.recharge.profile-lore")
                ));
                case "TOP_UP" -> renderAmount(inventory, definition);
                case "CLOSE" -> inventory.setItem(definition.slot(), item(
                    definition.material(Material.BARRIER),
                    messages.component("menus.recharge.close-name"),
                    List.of()
                ));
                default -> { }
            }
        }
    }

    private void renderAmount(Inventory inventory, PaperMenus.MenuItemDefinition definition) {
        int amount;
        try {
            amount = Integer.parseInt(definition.value());
        } catch (NumberFormatException exception) {
            amount = 0;
        }
        Map<String, Object> placeholders = Map.of(
            "amount", amount,
            "points", amount * POINTS_PER_CNY
        );
        inventory.setItem(definition.slot(), item(
            definition.material(Material.GOLD_INGOT),
            messages.component("menus.recharge.amount-name", placeholders),
            messages.components("menus.recharge.amount-lore", placeholders)
        ));
    }

    private static ItemStack item(Material material, Component name, List<Component> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(name.decoration(TextDecoration.ITALIC, false));
        meta.lore(lore.stream()
            .map(line -> line.decoration(TextDecoration.ITALIC, false))
            .toList());
        stack.setItemMeta(meta);
        return stack;
    }

    private static final class RechargeHolder implements InventoryHolder {
        private final PaperMenus.MenuDefinition layout;
        private Inventory inventory;

        private RechargeHolder(PaperMenus.MenuDefinition layout) {
            this.layout = layout;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
