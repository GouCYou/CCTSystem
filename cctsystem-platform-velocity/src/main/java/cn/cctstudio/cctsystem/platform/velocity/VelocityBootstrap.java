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
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;

@Plugin(
    id = "cctsystem",
    name = "CCTSystem",
    version = "0.1.0-SNAPSHOT",
    description = "CCTStudio unified network core",
    authors = {"GouC"},
    dependencies = {@Dependency(id = "skinsrestorer", optional = true)}
)
public final class VelocityBootstrap {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ProxyServer proxy;
    private final Path dataDirectory;
    private final VelocityLogger logger;
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
            CctConfig config = new ConfigLoader().load(prepareConfig());
            CctRuntime created = new CctRuntime(
                PlatformType.VELOCITY,
                config,
                new VelocityTaskExecutor(this, proxy),
                logger
            );
            configureInfrastructure(created);
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
    }

    private CompletableFuture<com.fasterxml.jackson.databind.JsonNode> networkStatus() {
        ObjectNode root = JSON.createObjectNode();
        root.put("onlinePlayers", proxy.getPlayerCount());
        ArrayNode servers = root.putArray("servers");
        proxy.getAllServers().stream()
            .sorted(java.util.Comparator.comparing(server -> server.getServerInfo().getName()))
            .forEach(server -> {
                ObjectNode item = servers.addObject();
                item.put("id", server.getServerInfo().getName());
                item.put("onlinePlayers", server.getPlayersConnected().size());
                item.put("registered", true);
            });
        return CompletableFuture.completedFuture(root);
    }
}
