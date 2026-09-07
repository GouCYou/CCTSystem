package cn.cctstudio.cctsystem.platform.paper;

import cn.cctstudio.cctsystem.core.logging.CctLogger;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Adapts AntiRedstoneClock-Remastered through its published 2.9.1 service layout.
 * The upstream plugin does not currently expose a detection event, so this wrapper
 * decorates its notification service while leaving the original JAR untouched.
 */
final class PaperAntiRedstoneAdapter implements AutoCloseable {
    private final JavaPlugin plugin;
    private final CctLogger logger;
    private final PaperMessages messages;
    private final PaperNetworkChat networkChat;
    private final PaperLuckPermsTitles titles;
    private final Predicate<UUID> vanished;
    private final String serverId;
    private final double radius;
    private final List<String> broadcastLines;
    private final Object decisionService;
    private final Field notificationField;
    private final Object originalNotificationService;

    private PaperAntiRedstoneAdapter(
        JavaPlugin plugin,
        CctLogger logger,
        PaperMessages messages,
        PaperNetworkChat networkChat,
        PaperLuckPermsTitles titles,
        Predicate<UUID> vanished,
        String serverId,
        double radius,
        List<String> broadcastLines,
        Object decisionService,
        Field notificationField,
        Object originalNotificationService
    ) {
        this.plugin = plugin;
        this.logger = logger;
        this.messages = messages;
        this.networkChat = networkChat;
        this.titles = titles;
        this.vanished = vanished;
        this.serverId = serverId;
        this.radius = radius;
        this.broadcastLines = broadcastLines;
        this.decisionService = decisionService;
        this.notificationField = notificationField;
        this.originalNotificationService = originalNotificationService;
    }

    static PaperAntiRedstoneAdapter install(
        JavaPlugin plugin,
        CctLogger logger,
        PaperMessages messages,
        PaperNetworkChat networkChat,
        PaperLuckPermsTitles titles,
        Predicate<UUID> vanished,
        String serverId
    ) {
        File configFile = new File(plugin.getDataFolder(), "anti-redstone.yml");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(configFile);
        if (!config.getBoolean("enabled", true)) return null;

        Plugin antiRedstone = java.util.Arrays.stream(plugin.getServer().getPluginManager().getPlugins())
            .filter(candidate -> candidate.getClass().getName().equals(
                "net.onelitefeather.antiredstoneclockremastered.AntiRedstoneClockRemastered"
            ))
            .findFirst()
            .orElse(null);
        if (antiRedstone == null || !antiRedstone.isEnabled()) return null;

        try {
            Field injectorField = antiRedstone.getClass().getDeclaredField("injector");
            injectorField.setAccessible(true);
            Object injector = injectorField.get(antiRedstone);
            ClassLoader loader = antiRedstone.getClass().getClassLoader();
            Class<?> decisionInterface = Class.forName(
                "net.onelitefeather.antiredstoneclockremastered.service.api.DecisionService",
                true,
                loader
            );
            // Guice's concrete InjectorImpl is package-private. Reflecting a public
            // method from that implementation still fails Java access checks, so invoke
            // the method as declared by Guice's public Injector interface instead.
            Class<?> injectorInterface = Class.forName("com.google.inject.Injector", true, loader);
            Method getInstance = injectorInterface.getMethod("getInstance", Class.class);
            Object decisionService = getInstance.invoke(injector, decisionInterface);
            Field notificationField = decisionService.getClass().getDeclaredField("notificationService");
            notificationField.setAccessible(true);
            Object original = notificationField.get(decisionService);
            Class<?> notificationInterface = Class.forName(
                "net.onelitefeather.antiredstoneclockremastered.service.api.NotificationService",
                true,
                loader
            );
            double radius = Math.max(1.0, Math.min(128.0, config.getDouble("nearby-radius", 20.0)));
            List<String> lines = config.getStringList("broadcast");
            if (lines.isEmpty()) lines = List.of(
                "&c&l检测到高频红石装置，已自动清除并上报管理员。",
                "&7服务器：&f{server}  &7世界：&f{world}  &7坐标：&f{x}, {y}, {z}",
                "&7附近玩家：&f{players}",
                "&e请勿搭建高频红石装置，再次发现可能导致账户被封停。"
            );
            final PaperAntiRedstoneAdapter[] installed = new PaperAntiRedstoneAdapter[1];
            Object decorated = Proxy.newProxyInstance(
                loader,
                new Class<?>[]{notificationInterface},
                (proxy, method, arguments) -> {
                    Object result;
                    try {
                        result = method.invoke(original, arguments);
                    } catch (InvocationTargetException exception) {
                        throw exception.getCause();
                    }
                    if (method.getName().equals("sendNotificationMessage")
                        && arguments != null && arguments.length == 1
                        && arguments[0] instanceof Location location) {
                        PaperAntiRedstoneAdapter adapter = installed[0];
                        if (adapter != null) adapter.report(location.clone());
                    }
                    return result;
                }
            );
            PaperAntiRedstoneAdapter adapter = new PaperAntiRedstoneAdapter(
                plugin, logger, messages, networkChat, titles, vanished, serverId, radius,
                List.copyOf(lines), decisionService, notificationField, original
            );
            installed[0] = adapter;
            notificationField.set(decisionService, decorated);
            logger.info("AntiRedstoneClock integration enabled (nearby radius " + radius + ")");
            return adapter;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            logger.warn("Unable to install AntiRedstoneClock integration", exception);
            return null;
        }
    }

    private void report(Location location) {
        Runnable task = () -> {
            String players = nearbyPlayers(location);
            Map<String, String> values = Map.of(
                "server", messages.raw("chat.servers." + serverId, serverId),
                "world", location.getWorld() == null ? "未知" : location.getWorld().getName(),
                "x", Integer.toString(location.getBlockX()),
                "y", Integer.toString(location.getBlockY()),
                "z", Integer.toString(location.getBlockZ()),
                "radius", Integer.toString((int) Math.round(radius)),
                "players", players
            );
            String rendered = broadcastLines.stream()
                .map(line -> replace(line, values))
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
            networkChat.broadcastSecurityAlert(rendered);
            logger.warn("High-frequency redstone removed at "
                + values.get("server") + "/" + values.get("world") + " "
                + values.get("x") + "," + values.get("y") + "," + values.get("z")
                + "; nearby players: " + players);
        };
        if (plugin.getServer().isPrimaryThread()) task.run();
        else plugin.getServer().getScheduler().runTask(plugin, task);
    }

    private String nearbyPlayers(Location location) {
        if (location.getWorld() == null) return "无";
        double maximum = radius * radius;
        List<String> names = location.getWorld().getPlayers().stream()
            .filter(Player::isOnline)
            .filter(player -> !vanished.test(player.getUniqueId()))
            .filter(player -> player.getLocation().distanceSquared(location) <= maximum)
            .map(titles::playerIdentityLegacy)
            .sorted(Comparator.naturalOrder())
            .toList();
        return names.isEmpty() ? "无" : String.join("&7, ", names);
    }

    private static String replace(String source, Map<String, String> values) {
        String result = source == null ? "" : source;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return result;
    }

    @Override
    public void close() {
        try {
            notificationField.set(decisionService, originalNotificationService);
        } catch (IllegalAccessException exception) {
            logger.warn("Unable to restore AntiRedstoneClock notification service", exception);
        }
    }
}
