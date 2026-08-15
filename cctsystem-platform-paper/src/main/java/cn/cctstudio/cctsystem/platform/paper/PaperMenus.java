package cn.cctstudio.cctsystem.platform.paper;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

final class PaperMenus {
    private static final List<String> MENU_IDS = List.of(
        "personal", "membership", "exchange", "upgrade", "confirmation", "nickname", "rewards"
    );
    private final JavaPlugin plugin;
    private volatile Map<String, MenuDefinition> definitions = Map.of();

    PaperMenus(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    void reload() {
        Map<String, MenuDefinition> loaded = new LinkedHashMap<>();
        for (String id : MENU_IDS) loaded.put(id, load(id));
        definitions = Map.copyOf(loaded);
    }

    MenuDefinition get(String id) {
        MenuDefinition definition = definitions.get(id);
        if (definition == null) throw new IllegalArgumentException("Unknown menu: " + id);
        return definition;
    }

    private MenuDefinition load(String id) {
        File file = new File(plugin.getDataFolder(), "menus/" + id + ".yml");
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        int rows = Math.max(1, Math.min(6, yaml.getInt("rows", 3)));
        int size = rows * 9;
        String titleKey = yaml.getString("title-key", "menus." + id + ".title");
        String fillerMaterial = yaml.getString(
            "fill.material", "GRAY_STAINED_GLASS_PANE"
        );
        boolean fillEnabled = yaml.getBoolean("fill.enabled", false);
        List<Integer> fillerSlots = parseSlots(yaml.getList("fill.slots"), size);
        if (fillEnabled && fillerSlots.isEmpty()) {
            for (int slot = 0; slot < size; slot++) fillerSlots.add(slot);
        }
        Map<String, MenuItemDefinition> items = new LinkedHashMap<>();
        ConfigurationSection section = yaml.getConfigurationSection("items");
        if (section != null) {
            for (String itemId : section.getKeys(false)) {
                String base = "items." + itemId + ".";
                int slot = yaml.getInt(base + "slot", -1);
                if (slot < 0 || slot >= size) {
                    throw new IllegalArgumentException(
                        id + " menu item has invalid slot: " + itemId
                    );
                }
                if (items.values().stream().anyMatch(item -> item.slot() == slot)) {
                    throw new IllegalArgumentException(
                        id + " menu contains duplicate slot: " + slot
                    );
                }
                items.put(itemId, new MenuItemDefinition(
                    itemId,
                    slot,
                    yaml.getString(base + "material", "STONE"),
                    yaml.getString(base + "action", "NONE")
                        .trim().toUpperCase(Locale.ROOT),
                    yaml.getString(base + "value", "")
                ));
            }
        }
        return new MenuDefinition(
            id, rows, titleKey, fillEnabled, fillerMaterial,
            List.copyOf(fillerSlots), Map.copyOf(items)
        );
    }

    private static List<Integer> parseSlots(List<?> values, int size) {
        List<Integer> slots = new ArrayList<>();
        if (values == null) return slots;
        for (Object value : values) {
            String raw = String.valueOf(value).trim();
            if (raw.matches("\\d+")) {
                addSlot(slots, Integer.parseInt(raw), size);
            } else if (raw.matches("\\d+-\\d+")) {
                String[] parts = raw.split("-", 2);
                int start = Integer.parseInt(parts[0]);
                int end = Integer.parseInt(parts[1]);
                for (int slot = Math.min(start, end); slot <= Math.max(start, end); slot++) {
                    addSlot(slots, slot, size);
                }
            }
        }
        return slots;
    }

    private static void addSlot(List<Integer> slots, int slot, int size) {
        if (slot >= 0 && slot < size && !slots.contains(slot)) slots.add(slot);
    }

    record MenuDefinition(
        String id,
        int rows,
        String titleKey,
        boolean fillEnabled,
        String fillerMaterial,
        List<Integer> fillerSlots,
        Map<String, MenuItemDefinition> items
    ) {
        int size() {
            return rows * 9;
        }

        MenuItemDefinition item(String id) {
            MenuItemDefinition item = items.get(id);
            if (item == null) throw new IllegalArgumentException(
                "Missing " + this.id + " menu item: " + id
            );
            return item;
        }

        String actionAt(int slot) {
            return items.values().stream()
                .filter(item -> item.slot() == slot)
                .map(MenuItemDefinition::action)
                .findFirst()
                .orElse("NONE");
        }

        String valueAt(int slot) {
            return items.values().stream()
                .filter(item -> item.slot() == slot)
                .map(MenuItemDefinition::value)
                .findFirst()
                .orElse("");
        }

        void fill(Inventory inventory) {
            if (!fillEnabled) return;
            Material material = material(fillerMaterial, Material.GRAY_STAINED_GLASS_PANE);
            ItemStack filler = new ItemStack(material);
            ItemMeta meta = filler.getItemMeta();
            meta.displayName(Component.empty().decoration(TextDecoration.ITALIC, false));
            filler.setItemMeta(meta);
            for (int slot : fillerSlots) inventory.setItem(slot, filler);
        }
    }

    record MenuItemDefinition(
        String id,
        int slot,
        String materialName,
        String action,
        String value
    ) {
        Material material(Material fallback) {
            if ("AUTO".equalsIgnoreCase(materialName)) return fallback;
            return PaperMenus.material(materialName, fallback);
        }
    }

    private static Material material(String value, Material fallback) {
        Material material = Material.matchMaterial(value == null ? "" : value);
        return material == null || material.isAir() || !material.isItem() ? fallback : material;
    }
}
