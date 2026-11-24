package cn.cctstudio.cctsystem.platform.velocity;

import cn.cctstudio.cctsystem.bridge.BridgeRpcRouter;
import cn.cctstudio.cctsystem.core.CctRuntime;
import cn.cctstudio.cctsystem.bridge.RpcHandlingException;
import cn.cctstudio.cctsystem.storage.mysql.DatabaseProvider;
import cn.cctstudio.cctsystem.storage.mysql.PlayerMailbox;
import cn.cctstudio.cctsystem.storage.mysql.PlayerMailbox.LagSettings;
import cn.cctstudio.cctsystem.storage.mysql.PlayerMailbox.Reward;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

final class VelocityLagCompensation {
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final ObjectMapper JSON = new ObjectMapper();
    private final CctRuntime runtime;
    private final ProxyServer proxy;
    private final Path config;
    private final AtomicBoolean polling = new AtomicBoolean();

    VelocityLagCompensation(CctRuntime runtime, ProxyServer proxy, Path directory) throws IOException {
        this.runtime = runtime;
        this.proxy = proxy;
        this.config = directory.resolve("lag-compensation.yml");
        if (Files.notExists(config)) {
            try (var defaults = getClass().getResourceAsStream("/lag-compensation.yml")) {
                Files.copy(java.util.Objects.requireNonNull(defaults), config);
            }
        }
    }

    void register(BridgeRpcRouter router) {
        router.register("admin.lag-compensation.settings", ignored -> query(() -> JSON.valueToTree(store().settings(defaults()))));
        router.register("admin.lag-compensation.save", input -> query(() -> {
            try {
                LagSettings next = null;
                if (!input.path("reset").asBoolean()) {
                    if (!input.path("enabled").isBoolean() || !input.path("rewards").isArray()) {
                        throw new IllegalArgumentException("Invalid compensation settings");
                    }
                    List<Reward> rewards = JSON.convertValue(input.path("rewards"), new TypeReference<>() {});
                    next = new LagSettings(input.path("enabled").booleanValue(), rewards);
                }
                store().saveSettings(next);
                return JSON.valueToTree(store().settings(defaults()));
            } catch (IllegalArgumentException exception) {
                throw new RpcHandlingException("REQUEST_INVALID", "Invalid compensation settings", false);
            }
        }));
    }

    CompletableFuture<Void> observe(boolean recovered, Collection<UUID> affected) {
        return query(() -> {
            if (recovered) store().recovered();
            else if (!affected.isEmpty()) store().compensate(affected, defaults());
            return null;
        });
    }

    void notifyOnline() {
        if (proxy.getPlayerCount() == 0 || runtime.providers().find(DatabaseProvider.KEY).isEmpty()
            || !polling.compareAndSet(false, true)) return;
        var ids = proxy.getAllPlayers().stream().map(Player::getUniqueId).toList();
        query(() -> store().notices(ids)).whenComplete((notices, failure) -> {
            polling.set(false);
            if (failure != null) return;
            for (var mail : notices) {
                String rewards = mail.draft().rewards().stream().map(PlayerMailbox.Reward::display)
                    .collect(Collectors.joining("、"));
                proxy.getPlayer(mail.playerUuid()).ifPresent(player -> player.sendMessage(
                    Component.text("网络波动补偿已发放 · " + rewards, NamedTextColor.GREEN)
                ));
            }
        });
    }

    private LagSettings defaults() throws IOException {
        return YAML.readValue(config.toFile(), LagSettings.class);
    }

    private PlayerMailbox store() {
        return new PlayerMailbox(runtime.providers().find(DatabaseProvider.KEY).orElseThrow());
    }

    private <T> CompletableFuture<T> query(Callable<T> action) {
        return CompletableFuture.supplyAsync(() -> {
            try { return action.call(); }
            catch (Exception failure) { throw new CompletionException(failure); }
        }, runtime.executors().blocking());
    }
}
