package cn.cctstudio.cctsystem.platform.paper;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

final class PaperMessages {
    private static final LegacyComponentSerializer LEGACY =
        LegacyComponentSerializer.legacyAmpersand();
    private final File file;
    private volatile YamlConfiguration messages;

    PaperMessages(JavaPlugin plugin) {
        this.file = new File(plugin.getDataFolder(), "messages.yml");
        reload();
    }

    void reload() {
        YamlConfiguration loaded = YamlConfiguration.loadConfiguration(file);
        try (InputStream input = PaperMessages.class.getResourceAsStream("/messages.yml")) {
            if (input != null) {
                loaded.setDefaults(YamlConfiguration.loadConfiguration(
                    new InputStreamReader(input, StandardCharsets.UTF_8)
                ));
            }
        } catch (java.io.IOException ignored) {
            // The installed file remains authoritative; bundled defaults are a fallback only.
        }
        messages = loaded;
    }

    Component component(String path) {
        return component(path, Map.of());
    }

    Component component(String path, Map<String, ?> placeholders) {
        return LEGACY.deserialize(replace(raw(path), placeholders));
    }

    List<Component> components(String path) {
        return components(path, Map.of());
    }

    List<Component> components(String path, Map<String, ?> placeholders) {
        return messages.getStringList(path).stream()
            .map(line -> (Component) LEGACY.deserialize(replace(line, placeholders)))
            .toList();
    }

    String raw(String path) {
        return messages.getString(path, "&cMissing message: " + path);
    }

    String raw(String path, String fallback) {
        return messages.getString(path, fallback);
    }

    private static String replace(String source, Map<String, ?> placeholders) {
        String result = source == null ? "" : source.replace('§', '&');
        for (Map.Entry<String, ?> entry : placeholders.entrySet()) {
            result = result.replace(
                "{" + entry.getKey() + "}",
                String.valueOf(entry.getValue())
            );
        }
        return result;
    }
}
