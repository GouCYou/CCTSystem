package cn.cctstudio.cctsystem.platform.paper;

import org.bukkit.GameRules;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.world.WorldInitEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** Enforces Survival2 inventory safety, including dynamically created dungeon worlds. */
final class PaperSurvivalWorldPolicy implements Listener {
    private static final String SURVIVAL_SERVER_ID = "survival";
    private static final String ELITE_MOBS_WORLD_PREFIX = "em_";

    private final PaperLogger logger;
    private final boolean enabled;

    private PaperSurvivalWorldPolicy(PaperLogger logger, String serverId) {
        this.logger = logger;
        this.enabled = SURVIVAL_SERVER_ID.equalsIgnoreCase(serverId);
    }

    static void install(JavaPlugin plugin, PaperLogger logger, String serverId) {
        PaperSurvivalWorldPolicy policy = new PaperSurvivalWorldPolicy(logger, serverId);
        if (!policy.enabled) return;
        plugin.getServer().getPluginManager().registerEvents(policy, plugin);
        plugin.getServer().getWorlds().forEach(policy::applyRules);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldInit(WorldInitEvent event) {
        applyRules(event.getWorld());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldLoad(WorldLoadEvent event) {
        applyRules(event.getWorld());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerDeath(PlayerDeathEvent event) {
        if (!enabled) return;

        // KeepInventory 1.5 only protects players with keepinventory.keep. This
        // fallback guarantees the same result if permissions or another plugin
        // are temporarily out of sync.
        event.setKeepInventory(true);
        event.getDrops().clear();
        event.setKeepLevel(true);
        event.setDroppedExp(0);
    }

    private void applyRules(World world) {
        if (!enabled) return;

        boolean changed = !Boolean.TRUE.equals(world.getGameRuleValue(GameRules.KEEP_INVENTORY));
        world.setGameRule(GameRules.KEEP_INVENTORY, true);

        // Dungeon templates must not be permanently carved up by dragons or
        // other mob griefing. Keep normal survival-world mob behavior unchanged.
        if (isEliteMobsWorld(world)) {
            changed |= !Boolean.FALSE.equals(world.getGameRuleValue(GameRules.MOB_GRIEFING));
            world.setGameRule(GameRules.MOB_GRIEFING, false);
        }

        if (changed) logger.info("Applied Survival2 world safety rules to " + world.getName());
    }

    static boolean isEliteMobsWorld(World world) {
        return world != null && world.getName().toLowerCase(java.util.Locale.ROOT)
            .startsWith(ELITE_MOBS_WORLD_PREFIX);
    }
}
