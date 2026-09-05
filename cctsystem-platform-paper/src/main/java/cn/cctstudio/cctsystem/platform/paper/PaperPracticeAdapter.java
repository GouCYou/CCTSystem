package cn.cctstudio.cctsystem.platform.paper;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/** Optional StrikePractice public API bridge; never loads its classes on other servers. */
final class PaperPracticeAdapter implements Listener {
    private final JavaPlugin plugin;
    private final Plugin practice;
    private final Object api;
    private final Method getFight;
    private final Method isEditing;
    private final Method getEditingKit;
    private final Method getPlayerKits;
    private final Method hasEnded;
    private final Method activeFighter;
    private final Map<UUID, Object> fights = new ConcurrentHashMap<>();
    private boolean warned;
    private Map<?, ?> legacyBots = Map.of();

    private PaperPracticeAdapter(JavaPlugin plugin, Plugin practice) throws ReflectiveOperationException {
        this.plugin = plugin;
        this.practice = practice;
        ClassLoader loader = practice.getClass().getClassLoader();
        Class<?> apiType = Class.forName("ga.strikepractice.api.StrikePracticeAPI", true, loader);
        api = practice.getClass().getMethod("getAPI").invoke(null);
        getFight = apiType.getMethod("getFight", Player.class);
        isEditing = apiType.getMethod("isEditingKit", Player.class);
        getEditingKit = apiType.getMethod("getEditingKit", Player.class);
        getPlayerKits = apiType.getMethod("getPlayerKits", Player.class);
        Class<?> fight = Class.forName("ga.strikepractice.fights.Fight", true, loader);
        hasEnded = fight.getMethod("hasEnded");
        activeFighter = fight.getMethod("isActiveFighter", Player.class);
    }

    static PaperPracticeAdapter install(JavaPlugin plugin) {
        Plugin practice = plugin.getServer().getPluginManager().getPlugin("StrikePractice");
        if (practice == null || !practice.isEnabled()) return null;
        try {
            PaperPracticeAdapter adapter = new PaperPracticeAdapter(plugin, practice);
            plugin.getServer().getPluginManager().registerEvents(adapter, plugin);
            adapter.installBotNavigation();
            plugin.getLogger().info("StrikePractice integration enabled: fight visibility and kit close-save");
            return adapter;
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unsupported StrikePractice API; refusing unsafe vanish integration", exception);
        }
    }

    @SuppressWarnings("unchecked")
    private void installBotNavigation() throws ReflectiveOperationException {
        Plugin citizens = plugin.getServer().getPluginManager().getPlugin("Citizens");
        if (citizens == null || !citizens.isEnabled()) return;
        ClassLoader loader = citizens.getClass().getClassLoader();
        Class<?> npcType = Class.forName("net.citizensnpcs.api.npc.NPC", true, loader);
        Class<?> navigatorType = Class.forName("net.citizensnpcs.api.ai.Navigator", true, loader);
        Class<?> parameterType = Class.forName("net.citizensnpcs.api.ai.NavigatorParameters", true, loader);
        Class<? extends org.bukkit.event.Event> eventType = (Class<? extends org.bukkit.event.Event>)
            Class.forName("net.citizensnpcs.api.ai.event.NavigationBeginEvent", true, loader);
        Method getNpc = eventType.getMethod("getNPC");
        Method uuid = npcType.getMethod("getUniqueId");
        Method navigator = npcType.getMethod("getNavigator");
        Method defaults = navigatorType.getMethod("getDefaultParameters");
        Method local = navigatorType.getMethod("getLocalParameters");
        Method setSpeed = parameterType.getMethod("speedModifier", float.class);
        Method pathfinder = parameterType.getMethod("useNewPathfinder", boolean.class);
        Method updateRate = parameterType.getMethod("updatePathRate", int.class);
        Class<?> bots = Class.forName("ga.strikepractice.npc.CitizensNPC", true, practice.getClass().getClassLoader());
        legacyBots = (Map<?, ?>) bots.getField("npcMap").get(null);
        java.io.File file = new java.io.File(plugin.getDataFolder(), "practice.yml");
        org.bukkit.configuration.file.YamlConfiguration config = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file);
        float multiplier = (float) Math.max(0.5, Math.min(2.0, config.getDouble("bot-speed-multiplier", 1.3)));
        plugin.getServer().getPluginManager().registerEvent(eventType, this, EventPriority.MONITOR,
            (listener, event) -> {
                try {
                    Object npc = getNpc.invoke(event);
                    if (!legacyBots.containsKey(uuid.invoke(npc))) return;
                    Object nav = navigator.invoke(npc);
                    for (Object parameters : new Object[]{defaults.invoke(nav), local.invoke(nav)}) {
                        setSpeed.invoke(parameters, multiplier);
                        pathfinder.invoke(parameters, true);
                        updateRate.invoke(parameters, 10);
                    }
                } catch (ReflectiveOperationException exception) { warn(exception); }
            }, plugin, true);
        plugin.getLogger().info("StrikePractice bot navigation: persistent speed " + multiplier + ", Citizens pathfinder");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBotDamage(org.bukkit.event.entity.EntityDamageByEntityEvent event) {
        if (!legacyBots.containsKey(event.getEntity().getUniqueId()) || event.getFinalDamage() <= 0
            || !(event.getEntity() instanceof Player bot)) return;
        org.bukkit.entity.Entity source = event.getDamager();
        if (source instanceof org.bukkit.entity.Projectile projectile
            && projectile.getShooter() instanceof org.bukkit.entity.Entity shooter) source = shooter;
        if (!(source instanceof Player attacker)) return;
        // Replace StrikePractice's asynchronous one-tick compensation with a main-thread
        // correction, after all damage cancellation. Preserve ordinary player knockback.
        org.bukkit.util.Vector direction = bot.getLocation().toVector().subtract(attacker.getLocation().toVector());
        direction.setY(0);
        if (direction.lengthSquared() < 1.0e-8) return;
        direction.normalize().multiply(0.27);
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!bot.isValid() || bot.isDead() || !legacyBots.containsKey(bot.getUniqueId())) return;
            org.bukkit.util.Vector velocity = bot.getVelocity().add(direction);
            double horizontal = Math.hypot(velocity.getX(), velocity.getZ());
            if (horizontal > 0.65) {
                velocity.setX(velocity.getX() * 0.65 / horizontal);
                velocity.setZ(velocity.getZ() * 0.65 / horizontal);
            }
            velocity.setY(Math.max(0.27, Math.min(0.38, velocity.getY())));
            bot.setVelocity(velocity);
        });
    }

    void refresh() {
        fights.clear();
        if (!practice.isEnabled()) return;
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (player.hasMetadata("NPC")) continue;
            try {
                Object fight = getFight.invoke(api, player);
                if (fight != null && !Boolean.TRUE.equals(hasEnded.invoke(fight))
                    && Boolean.TRUE.equals(activeFighter.invoke(fight, player))) {
                    fights.put(player.getUniqueId(), fight);
                }
            } catch (ReflectiveOperationException exception) {
                warn(exception);
            }
        }
    }

    boolean isFighting(Player player) { return fights.containsKey(player.getUniqueId()); }

    boolean canSeeFighter(Player viewer, Player target) {
        return PracticeVisibilityPolicy.sameFight(fights.get(viewer.getUniqueId()), fights.get(target.getUniqueId()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        // Kit selectors, anvils, saved-layout menus and chest refills also close inventories.
        // Only a real close of the player's own inventory should save the edited layout.
        if (!(event.getPlayer() instanceof Player player)
            || event.getInventory().getType() != InventoryType.CRAFTING
            || event.getReason() != InventoryCloseEvent.Reason.PLAYER) return;
        try {
            if (!Boolean.TRUE.equals(isEditing.invoke(api, player))) return;
            Object kit = getEditingKit.invoke(api, player);
            if (kit == null) return;
            plugin.getServer().getScheduler().runTask(plugin, () -> saveAndLeave(player, kit));
        } catch (ReflectiveOperationException exception) { warn(exception); }
    }

    private void saveAndLeave(Player player, Object kit) {
        if (!player.isOnline() || !practice.isEnabled()) return;
        try {
            if (!Boolean.TRUE.equals(isEditing.invoke(api, player)) || getEditingKit.invoke(api, player) != kit) return;
            Class<?> kitType = Class.forName("ga.strikepractice.battlekit.BattleKit", true, practice.getClass().getClassLoader());
            String name = kitType.getMethod("getName").invoke(kit) + "-1";
            Object playerKits = getPlayerKits.invoke(api, player);
            Method edited = playerKits.getClass().getMethod("getEditedKit", String.class);
            Object previous = edited.invoke(playerKits, name);
            org.bukkit.command.PluginCommand command = ((JavaPlugin) practice).getCommand("kiteditor");
            if (command == null) throw new IllegalStateException("StrikePractice kiteditor command is unavailable");
            // Keep the plugin's item validation, per-player storage and merged-kit behavior.
            command.execute(player, "kiteditor", new String[]{"save"});
            Object saved = edited.invoke(playerKits, name);
            if (saved != null && saved != previous) command.execute(player, "kiteditor", new String[]{"leave"});
        } catch (ReflectiveOperationException | RuntimeException exception) { warn(exception); }
    }

    private void warn(Exception exception) {
        if (!warned) {
            warned = true;
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "StrikePractice integration failed", exception);
        }
    }
}
