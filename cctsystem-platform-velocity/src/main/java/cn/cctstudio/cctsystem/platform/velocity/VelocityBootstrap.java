package cn.cctstudio.cctsystem.platform.velocity;

import cn.cctstudio.cctsystem.bridge.BridgeClient;
import cn.cctstudio.cctsystem.bridge.BridgeProvider;
import cn.cctstudio.cctsystem.bridge.BridgeRpcRouter;
import cn.cctstudio.cctsystem.contract.Capability;
import cn.cctstudio.cctsystem.contract.NodeRole;
import cn.cctstudio.cctsystem.contract.PlatformType;
import cn.cctstudio.cctsystem.core.CctRuntime;
import cn.cctstudio.cctsystem.core.config.CctConfig;
import cn.cctstudio.cctsystem.core.config.ConfigLoader;
import cn.cctstudio.cctsystem.core.config.MembershipTierConfig;
import cn.cctstudio.cctsystem.core.filter.ReloadingChatFilter;
import cn.cctstudio.cctsystem.identity.IdentityModule;
import cn.cctstudio.cctsystem.identity.IdentityProvider;
import cn.cctstudio.cctsystem.storage.mysql.DatabaseManager;
import cn.cctstudio.cctsystem.skins.SkinAvatarRpcModule;
import cn.cctstudio.cctsystem.skins.SkinsRestorerInstaller;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyPingEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.ServerPing;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.Duration;
import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.NodeType;
import org.slf4j.Logger;

@Plugin(
    id = "cctsystem",
    name = "CCTSystem",
    version = "0.1.0-SNAPSHOT",
    description = "CCTStudio unified network core",
    authors = {"GouC"},
    dependencies = {
        @Dependency(id = "skinsrestorer", optional = true),
        @Dependency(id = "luckperms", optional = true)
    }
)
public final class VelocityBootstrap {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final MinecraftChannelIdentifier NETWORK_CHANNEL =
        MinecraftChannelIdentifier.from("cctsystem:network");
    private static final LegacyComponentSerializer LEGACY =
        LegacyComponentSerializer.legacyAmpersand();

    private final ProxyServer proxy;
    private final Path dataDirectory;
    private final VelocityLogger logger;
    private final Set<UUID> vanished = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Map<UUID, Instant> shoutCooldowns = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile ReloadingChatFilter chatFilter;
    private volatile CctRuntime runtime;

    @Inject
    public VelocityBootstrap(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = new VelocityLogger(logger);
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        try {
            proxy.getChannelRegistrar().register(NETWORK_CHANNEL);
            CctConfig config = new ConfigLoader().load(prepareConfig());
            chatFilter = new ReloadingChatFilter(
                dataDirectory.resolve("chat-filter.json"),
                getClass().getResourceAsStream("/chat-filter.json"),
                logger
            );
            CctRuntime created = new CctRuntime(
                PlatformType.VELOCITY,
                config,
                new VelocityTaskExecutor(this, proxy),
                logger
            );
            configureInfrastructure(created);
            proxy.getScheduler().buildTask(this, this::refreshVanishTab)
                .delay(Duration.ofSeconds(1))
                .repeat(Duration.ofSeconds(1))
                .schedule();
            runtime = created;
            created.start().whenComplete((ignored, throwable) -> {
                if (throwable == null) {
                    logger.info("CCTSystem Velocity node is ready");
                } else {
                    logger.error("CCTSystem failed to initialize", throwable);
                }
            });
        } catch (RuntimeException exception) {
            logger.error("CCTSystem Velocity bootstrap failed", exception);
        }
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!NETWORK_CHANNEL.equals(event.getIdentifier())) return;
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        if (!(event.getSource() instanceof ServerConnection connection)) return;
        try (DataInputStream input = new DataInputStream(
            new ByteArrayInputStream(event.getData())
        )) {
            String type = input.readUTF();
            if ("VANISH_STATE".equals(type)) {
                UUID playerUuid = UUID.fromString(input.readUTF());
                boolean enabled = input.readBoolean();
                input.readUTF(); // Source server id is retained in Paper audit records.
                Player source = connection.getPlayer();
                if (!source.getUniqueId().equals(playerUuid)
                    || !source.hasPermission("cctsystem.vanish")) return;
                if (enabled) {
                    vanished.add(playerUuid);
                } else {
                    vanished.remove(playerUuid);
                }
                refreshVanishTab();
                return;
            }
            if ("CONNECT_LOBBY".equals(type)) {
                proxy.getServer("lobby").ifPresentOrElse(
                    server -> connection.getPlayer().createConnectionRequest(server).fireAndForget(),
                    () -> logger.warn("CCTSystem lobby server is not registered")
                );
                return;
            }
            if ("SHOUT_V2".equals(type)) {
                String rendered = input.readUTF();
                String cooldownMessage = input.available() > 0
                    ? input.readUTF()
                    : "&c全服喊话冷却中，请等待 &e{seconds} 秒";
                if (rendered.isBlank() || rendered.length() > 4096) return;
                if (!connection.getPlayer().hasPermission("cctsystem.shout")) return;
                if (!acquireShoutCooldown(connection.getPlayer(), cooldownMessage)) return;
                ReloadingChatFilter currentFilter = chatFilter;
                String filtered = currentFilter == null ? rendered : currentFilter.filter(rendered);
                Component component = LEGACY.deserialize(filtered);
                proxy.getAllPlayers().forEach(player -> player.sendMessage(component));
                return;
            }
            if ("SECURITY_ALERT_V1".equals(type)) {
                String rendered = input.readUTF();
                if (rendered.isBlank() || rendered.length() > 4096) return;
                Component component = LEGACY.deserialize(rendered);
                proxy.getAllPlayers().forEach(player -> player.sendMessage(component));
                return;
            }
            if (!"SHOUT".equals(type)) return;
            String prefix = input.readUTF();
            String identity = input.readUTF();
            String separator = input.readUTF();
            String message = input.readUTF().strip();
            ReloadingChatFilter currentFilter = chatFilter;
            if (currentFilter != null) message = currentFilter.filter(message);
            if (message.isEmpty() || message.length() > 200) return;
            if (!connection.getPlayer().hasPermission("cctsystem.shout")) return;
            if (!acquireShoutCooldown(
                connection.getPlayer(), "&c全服喊话冷却中，请等待 &e{seconds} 秒"
            )) return;
            Component rendered = LEGACY.deserialize(prefix)
                .append(LEGACY.deserialize(identity))
                .append(LEGACY.deserialize(separator))
                .append(Component.text(message));
            proxy.getAllPlayers().forEach(player -> player.sendMessage(rendered));
        } catch (IOException exception) {
            logger.warn("Invalid CCTSystem network message", exception);
        }
    }

    private boolean acquireShoutCooldown(Player player, String message) {
        Instant now = Instant.now();
        Instant[] blockedUntil = new Instant[1];
        shoutCooldowns.compute(player.getUniqueId(), (ignored, current) -> {
            if (current != null && current.isAfter(now)) {
                blockedUntil[0] = current;
                return current;
            }
            return now.plusSeconds(60L);
        });
        if (blockedUntil[0] != null) {
            long millis = Math.max(1L, Duration.between(now, blockedUntil[0]).toMillis());
            long seconds = Math.max(1L, (millis + 999L) / 1000L);
            player.sendMessage(LEGACY.deserialize(message.replace("{seconds}", Long.toString(seconds))));
            return false;
        }
        return true;
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        CctRuntime existing = runtime;
        runtime = null;
        if (existing == null) {
            return;
        }
        try {
            existing.stop().get(5, TimeUnit.SECONDS);
        } catch (Exception exception) {
            logger.warn("CCTSystem did not stop cleanly", exception);
        }
    }

    @Subscribe
    public void onPlayerLogin(PostLoginEvent event) {
        CctRuntime existing = runtime;
        if (existing == null) {
            return;
        }
        existing.providers().find(IdentityProvider.KEY).ifPresent(identities -> identities.recordSeen(
            event.getPlayer().getUniqueId(),
            event.getPlayer().getUsername(),
            Instant.now()
        ).exceptionally(throwable -> {
            logger.warn("Unable to update connected player identity", throwable);
            return null;
        }));
        refreshVanishTab();
    }

    @Subscribe
    public void onProxyPing(ProxyPingEvent event) {
        int visible = (int) proxy.getAllPlayers().stream()
            .filter(player -> !vanished.contains(player.getUniqueId()))
            .count();
        ServerPing current = event.getPing();
        ServerPing.Builder updated = current.asBuilder().onlinePlayers(visible);
        CctRuntime existing = runtime;
        if (existing != null) {
            String versionName = existing.config().serverList().versionName();
            if (!versionName.isBlank()) {
                updated.version(new ServerPing.Version(
                    current.getVersion().getProtocol(),
                    versionName
                ));
            }
        }
        event.setPing(updated.build());
    }

    private Path prepareConfig() {
        try {
            Files.createDirectories(dataDirectory);
            Path config = dataDirectory.resolve("config.yml");
            if (Files.notExists(config)) {
                try (InputStream input = getClass().getResourceAsStream("/config.yml")) {
                    if (input == null) {
                        throw new IllegalStateException("Default CCTSystem config is missing from the JAR");
                    }
                    Files.copy(input, config);
                }
            }
            return config;
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to prepare CCTSystem config", exception);
        }
    }

    private void configureInfrastructure(CctRuntime created) {
        CctConfig config = created.config();
        if (config.database().enabled()) {
            created.addBeforeModules(new DatabaseManager(
                config.nodeId(),
                config.database(),
                created.providers(),
                created.executors(),
                logger
            ));
        }
        created.registerModule(new IdentityModule());
        if (config.bridge().enabled()) {
            BridgeRpcRouter router = new BridgeRpcRouter();
            created.providers().register(BridgeProvider.KEY, router);
            Set<Capability> baseCapabilities = new HashSet<>();
            if (config.roles().contains(NodeRole.NETWORK_AUTHORITY)) {
                baseCapabilities.add(Capability.NETWORK_STATUS_READ);
                router.register(Capability.NETWORK_STATUS_READ.value(), ignored -> networkStatus());
                if (proxy.getPluginManager().getPlugin("luckperms").isPresent()) {
                    baseCapabilities.add(Capability.PROFILE_READ);
                    router.register(Capability.PROFILE_READ.value(), this::playerProfile);
                }
            }
            created.addAfterModules(new BridgeClient(
                config,
                PlatformType.VELOCITY,
                created.bootId(),
                "0.1.0-SNAPSHOT",
                () -> {
                    Set<Capability> result = new HashSet<>(baseCapabilities);
                    result.addAll(created.activeCapabilities());
                    return Set.copyOf(result);
                },
                router,
                created.executors(),
                logger
            ));
        }
        if (proxy.getPluginManager().getPlugin("skinsrestorer").isPresent()) {
            SkinsRestorerInstaller.install(created);
        }
        created.registerModule(new SkinAvatarRpcModule());
        created.registerModule(new VelocityAdminRpcModule(proxy, dataDirectory));
    }

    private CompletableFuture<com.fasterxml.jackson.databind.JsonNode> networkStatus() {
        ObjectNode root = JSON.createObjectNode();
        root.put("onlinePlayers", proxy.getAllPlayers().stream()
            .filter(player -> !vanished.contains(player.getUniqueId())).count());
        ArrayNode servers = root.putArray("servers");
        proxy.getAllServers().stream()
            .sorted(java.util.Comparator.comparing(server -> server.getServerInfo().getName()))
            .forEach(server -> {
                ObjectNode item = servers.addObject();
                item.put("id", server.getServerInfo().getName());
                item.put("onlinePlayers", server.getPlayersConnected().stream()
                    .filter(player -> !vanished.contains(player.getUniqueId())).count());
                item.put("registered", true);
            });
        return CompletableFuture.completedFuture(root);
    }

    private CompletableFuture<com.fasterxml.jackson.databind.JsonNode> playerProfile(
        com.fasterxml.jackson.databind.JsonNode payload
    ) {
        if (payload == null || !payload.path("playerUuid").isTextual()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid player profile request"));
        }
        final UUID playerUuid;
        try {
            playerUuid = UUID.fromString(payload.path("playerUuid").textValue());
        } catch (IllegalArgumentException exception) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid player profile request"));
        }
        LuckPerms luckPerms = LuckPermsProvider.get();
        return luckPerms.getUserManager().loadUser(playerUuid).thenApply(user -> {
            ObjectNode result = JSON.createObjectNode();
            result.put("online", proxy.getPlayer(playerUuid).isPresent()
                && !vanished.contains(playerUuid));
            String primaryGroup = profilePrimaryGroup(luckPerms, user, membershipGroups());
            result.put("primaryGroup", primaryGroup);
            String customTitle = user.getCachedData().getMetaData().getMetaValue("cct-title");
            Group group = luckPerms.getGroupManager().getGroup(primaryGroup);
            String prefix = customTitle == null || customTitle.isBlank()
                ? group == null ? null : group.getCachedData().getMetaData().getPrefix()
                : customTitle;
            if (prefix == null || prefix.isBlank()) {
                result.putNull("title");
            } else {
                result.put("title", prefix);
            }
            return result;
        });
    }

    private Set<String> membershipGroups() {
        CctRuntime existing = runtime;
        if (existing == null) return Set.of();
        return existing.config().membership().tiers().stream()
            .map(MembershipTierConfig::luckPermsGroup)
            .map(group -> group.toLowerCase(Locale.ROOT))
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    static String profilePrimaryGroup(LuckPerms luckPerms, User user, Set<String> membershipGroups) {
        Set<String> managed = membershipGroups.stream()
            .map(group -> group.toLowerCase(Locale.ROOT))
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Set<String> candidates = new HashSet<>();
        String reportedPrimary = user.getPrimaryGroup().toLowerCase(Locale.ROOT);
        if (!managed.contains(reportedPrimary)) {
            candidates.add(reportedPrimary);
        }
        user.getNodes(NodeType.INHERITANCE).stream()
            .filter(node -> node.getValue() && !node.hasExpired())
            .map(node -> node.getGroupName().toLowerCase(Locale.ROOT))
            .filter(group -> !managed.contains(group))
            .forEach(candidates::add);
        return candidates.stream()
            .max(Comparator.<String>comparingInt(group -> groupWeight(luckPerms, group))
                .thenComparing(Comparator.naturalOrder()))
            .orElse("default");
    }

    private static int groupWeight(LuckPerms luckPerms, String groupName) {
        Group group = luckPerms.getGroupManager().getGroup(groupName);
        return group == null ? Integer.MIN_VALUE : group.getWeight().orElse(0);
    }

    private void refreshVanishTab() {
        for (Player viewer : proxy.getAllPlayers()) {
            for (UUID hidden : vanished) {
                if (!viewer.getUniqueId().equals(hidden)
                    && !viewer.hasPermission("cctsystem.vanish.see")) {
                    viewer.getTabList().removeEntry(hidden);
                }
            }
        }
    }
}
