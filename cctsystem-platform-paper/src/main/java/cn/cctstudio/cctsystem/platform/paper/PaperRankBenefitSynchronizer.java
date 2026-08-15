package cn.cctstudio.cctsystem.platform.paper;

import cn.cctstudio.cctsystem.core.logging.CctLogger;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.node.Node;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

final class PaperRankBenefitSynchronizer {
    private final JavaPlugin plugin;
    private final LuckPerms luckPerms;
    private final CctLogger logger;
    private final String serverId;

    PaperRankBenefitSynchronizer(
        JavaPlugin plugin,
        LuckPerms luckPerms,
        CctLogger logger,
        String serverId
    ) {
        this.plugin = plugin;
        this.luckPerms = luckPerms;
        this.logger = logger;
        this.serverId = serverId;
    }

    void synchronize() {
        if (luckPerms == null) return;
        File file = new File(plugin.getDataFolder(), "rank-benefits.yml");
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        if (!yaml.getBoolean("enabled", true)) return;
        List<String> serverIds = yaml.getStringList("apply-on-server-ids");
        if (!serverIds.isEmpty() && serverIds.stream().noneMatch(
            id -> id.equalsIgnoreCase(serverId)
        )) return;

        ConfigurationSection groups = yaml.getConfigurationSection("groups");
        if (groups == null) return;
        List<CompletableFuture<Void>> saves = new ArrayList<>();
        for (String groupName : groups.getKeys(false)) {
            List<String> additions = yaml.getStringList(
                "groups." + groupName + ".add-permissions"
            );
            List<String> removals = yaml.getStringList(
                "groups." + groupName + ".remove-permissions"
            );
            CompletableFuture<Void> save = luckPerms.getGroupManager()
                .loadGroup(groupName)
                .thenCompose(result -> {
                    Group group = result.orElse(null);
                    if (group == null) {
                        logger.warn("Rank benefit group does not exist: " + groupName);
                        return CompletableFuture.completedFuture(null);
                    }
                    for (String permission : removals) {
                        group.data().clear(node -> node.getKey().equalsIgnoreCase(permission));
                    }
                    for (String permission : additions) {
                        group.data().add(Node.builder(permission).value(true).build());
                    }
                    return luckPerms.getGroupManager().saveGroup(group);
                });
            saves.add(save);
        }
        CompletableFuture.allOf(saves.toArray(CompletableFuture[]::new))
            .whenComplete((ignored, failure) -> {
                if (failure == null) {
                    logger.info("Rank benefit permissions synchronized");
                } else {
                    logger.warn("Unable to synchronize rank benefit permissions", failure);
                }
            });
    }
}
