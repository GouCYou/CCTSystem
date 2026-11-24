package cn.cctstudio.cctsystem.platform.velocity;

import cn.cctstudio.cctsystem.core.CctRuntime;
import cn.cctstudio.cctsystem.core.auth.PremiumLoginProof;
import cn.cctstudio.cctsystem.core.config.PremiumLoginConfig;
import cn.cctstudio.cctsystem.storage.mysql.DatabaseProvider;
import cn.cctstudio.cctsystem.storage.mysql.PremiumLoginStore;
import cn.cctstudio.cctsystem.storage.mysql.SocialBindingLookup;
import net.kyori.adventure.text.format.NamedTextColor;
import com.velocitypowered.api.proxy.ConsoleCommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.event.player.GameProfileRequestEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.util.GameProfile;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;

final class VelocityPremiumLogin implements SimpleCommand {
    private static final Component INVALID = Component.text("正版验证未完成。请检查启动器的正版登录状态后重连。"
        + "如果需要使用密码登录，请用离线模式连接，或联系管理员。");
    private final RegisteredServer loginServer;
    private final ProxyServer proxy;
    private final String lobby;
    private final PremiumLoginProof proofs;
    private final CctRuntime runtime;
    private final boolean requireSocialBinding;
    private final Map<String, BindingRequest> requests = new ConcurrentHashMap<>();
    private final Map<String, Instant> passwordOverrides = new ConcurrentHashMap<>();
    private final Map<InetSocketAddress, Challenge> challenges = new ConcurrentHashMap<>();
    private final Set<UUID> lobbyTransfers = ConcurrentHashMap.newKeySet();
    private final Map<UUID, UnlinkPrompt> unlinkPrompts = new ConcurrentHashMap<>();

    VelocityPremiumLogin(PremiumLoginConfig config, RegisteredServer loginServer, CctRuntime runtime, ProxyServer proxy) {
        this.loginServer = loginServer;
        this.proxy = proxy;
        this.lobby = config.titleServer();
        this.runtime = runtime;
        this.requireSocialBinding = config.requireSocialBinding();
        this.proofs = new PremiumLoginProof(config.keyBytes(), runtime.config().networkId());
    }

    @Override
    public void execute(Invocation invocation) {
        var source = invocation.source();
        String[] arguments = invocation.arguments();
        if (source instanceof ConsoleCommandSource && arguments.length == 2
            && arguments[0].equals("password") && PremiumLoginProof.isJavaName(arguments[1])) {
            String name = arguments[1].toLowerCase(Locale.ROOT);
            Instant now = Instant.now();
            passwordOverrides.values().removeIf(expiry -> !expiry.isAfter(now));
            passwordOverrides.put(name, now.plusSeconds(600));
            source.sendMessage(Component.text("已为 " + arguments[1] + " 开放 10 分钟密码登录。"));
            return;
        }
        if (!(source instanceof Player player)) {
            source.sendMessage(Component.text("控制台用法：premium password <玩家名>"));
            return;
        }
        if (!player.isActive()) return;
        var connection = player.getCurrentServer();
        if (!PremiumLoginProof.isJavaName(player.getUsername())
            || !PremiumLoginProof.offlineUuid(player.getUsername()).equals(player.getUniqueId())
            || connection.isEmpty() || loginServer.getServerInfo().getName().equalsIgnoreCase(
                connection.get().getServerInfo().getName())) {
            player.sendMessage(green("请先完成登录，再开启 Java 正版账号绑定。"));
            return;
        }
        if (arguments.length == 1 && arguments[0].equalsIgnoreCase("unbind")) {
            suggestUnlink(player);
            return;
        }
        if (arguments.length == 3 && arguments[0].equalsIgnoreCase("unbind") && arguments[1].equalsIgnoreCase("confirm")) {
            confirmUnlink(player, arguments[2]);
            return;
        }
        if (arguments.length != 0) {
            player.sendMessage(green("用法：/premium 或 /premium unbind"));
            return;
        }
        if (inLobby(player)) {
            if (!lobbyTransfers.contains(player.getUniqueId())) requestBinding(player);
        } else {
            transferForBinding(player);
        }
    }

    private void suggestUnlink(Player player) {
        Instant now = Instant.now();
        unlinkPrompts.values().removeIf(prompt -> !prompt.expiresAt().isAfter(now));
        UUID token = UUID.randomUUID();
        unlinkPrompts.put(player.getUniqueId(), new UnlinkPrompt(token, player, now.plusSeconds(60)));
        player.sendMessage(green("解绑后，下次登录需要输入 AuthMe 密码。"));
        player.sendMessage(green("[确认解绑 Java 正版账号]")
            .clickEvent(ClickEvent.runCommand("/premium unbind confirm " + token)));
    }

    private void confirmUnlink(Player player, String token) {
        UUID playerId = player.getUniqueId();
        UnlinkPrompt prompt = unlinkPrompts.get(playerId);
        if (prompt == null || prompt.player() != player || !prompt.token().toString().equals(token)
            || !prompt.expiresAt().isAfter(Instant.now()) || !unlinkPrompts.remove(playerId, prompt)) {
            player.sendMessage(green("确认已失效，请重新打开导航或输入 /premium unbind。"));
            return;
        }
        CompletableFuture.runAsync(() -> {
            if (!player.isActive() || player.getCurrentServer().filter(server ->
                !loginServer.getServerInfo().getName().equalsIgnoreCase(server.getServerInfo().getName())).isEmpty()) return;
            try {
                var database = runtime.providers().find(DatabaseProvider.KEY).orElseThrow();
                if (new PremiumLoginStore(database).unlink(playerId, player.getUsername())) {
                    requests.remove(player.getUsername());
                    player.sendMessage(green("Java 正版账号已解绑，下次登录请使用原来的 AuthMe 密码。"));
                } else player.sendMessage(green("此游戏账号当前没有绑定 Java 正版。"));
            } catch (SQLException | RuntimeException failure) {
                player.sendMessage(green("暂时无法解绑 Java 正版账号，请稍后再试。"));
            }
        }, runtime.executors().blocking());
    }

    private void transferForBinding(Player player) {
        var destination = proxy.getServer(lobby);
        if (destination.isEmpty()) {
            player.sendMessage(green("暂时无法进入大厅，请稍后重新发起正版绑定。"));
            return;
        }
        UUID playerId = player.getUniqueId();
        if (!lobbyTransfers.add(playerId)) return;
        player.sendMessage(green("正在前往大厅，抵达后将自动开始 Java 正版账号绑定。"));
        try {
            player.createConnectionRequest(destination.get()).connect().whenComplete((result, failure) -> {
                if (failure != null || ((result == null || !result.isSuccessful()) && !inLobby(player))) {
                    lobbyTransfers.remove(playerId);
                    if (player.isActive()) player.sendMessage(green("暂时无法进入大厅，请稍后重新发起正版绑定。"));
                    return;
                }
                // Let the destination finish its join before sending the binding prompt.
                runtime.executors().scheduler().schedule(() -> {
                    lobbyTransfers.remove(playerId);
                    if (inLobby(player)) requestBinding(player);
                }, 1, TimeUnit.SECONDS);
            });
        } catch (RuntimeException failure) {
            lobbyTransfers.remove(playerId);
            if (player.isActive()) player.sendMessage(green("暂时无法进入大厅，请稍后重新发起正版绑定。"));
        }
    }

    private boolean inLobby(Player player) {
        return player.isActive() && player.getCurrentServer()
            .filter(server -> lobby.equalsIgnoreCase(server.getServerInfo().getName())).isPresent();
    }

    private void requestBinding(Player player) {
        var address = player.getRemoteAddress().getAddress();
        CompletableFuture.runAsync(() -> {
            var database = runtime.providers().find(DatabaseProvider.KEY).orElse(null);
            if (!inLobby(player)) return;
            try {
                if (database != null && new PremiumLoginStore(database).linkedMojangUuid(player.getUsername()).isPresent()) {
                    player.sendMessage(green("此账号已完成正版绑定。使用 Java 正版客户端连接原地址即可免密登录。"));
                    return;
                }
                if (database == null) throw new SQLException("Java login database is not ready");
                if (requireSocialBinding && !SocialBindingLookup.isBound(database, player.getUniqueId())) {
                    player.sendMessage(green("请先绑定 QQ 或 Discord，再绑定 Java 正版账号。"));
                    return;
                }
            } catch (SQLException exception) {
                player.sendMessage(green("暂时无法开启正版绑定，请稍后再试。"));
                return;
            }
            if (!inLobby(player)) return;
            Instant now = Instant.now();
            requests.values().removeIf(request -> !request.expiresAt().isAfter(now));
            requests.put(player.getUsername(), new BindingRequest(address, now.toEpochMilli(), now.plusSeconds(180)));
            player.sendMessage(green("正版绑定已申请。请在 3 分钟内使用 Java 正版客户端重连当前地址，"
                + "再输入原来的登录密码完成绑定。以后使用离线启动器仍可用密码登录。"
                + "若客户端提示会话无效，请切换启动器的离线模式或联系管理员。"));
        }, runtime.executors().blocking());
    }

    @Subscribe(priority = Short.MIN_VALUE)
    public EventTask preLogin(PreLoginEvent event) {
        String name = event.getUsername();
        UUID offered = event.getUniqueId();
        if (!event.getResult().isAllowed() || !PremiumLoginProof.isJavaName(name)
            || offered == null || offered.equals(PremiumLoginProof.offlineUuid(name))) return null;

        InetSocketAddress remote = event.getConnection().getRemoteAddress();
        Instant now = Instant.now();
        Instant override = passwordOverrides.get(name.toLowerCase(Locale.ROOT));
        if (override != null && override.isAfter(now)) return null;

        return EventTask.resumeWhenComplete(CompletableFuture.<Long>supplyAsync(() -> {
            var database = runtime.providers().find(DatabaseProvider.KEY).orElse(null);
            if (database == null) return null;
            try {
                var linked = new PremiumLoginStore(database).linkedMojangUuid(name);
                if (linked.isPresent()) return linked.get().equals(offered) ? 0L : null;
                var request = requests.get(name);
                return request != null && request.expiresAt().isAfter(now) && request.address().equals(remote.getAddress())
                    ? request.requestedAt() : null;
            } catch (SQLException exception) {
                return null;
            }
        }, runtime.executors().blocking()).completeOnTimeout(null, 1500, TimeUnit.MILLISECONDS).thenAccept(bindingRequestedAt -> {
            if (bindingRequestedAt == null) return;
            challenges.values().removeIf(challenge -> !challenge.expiresAt().isAfter(now));
            challenges.put(remote, new Challenge(name, offered, bindingRequestedAt, now.plusSeconds(60)));
            event.setResult(PreLoginEvent.PreLoginComponentResult.forceOnlineMode());
        }));
    }

    @Subscribe(priority = Short.MIN_VALUE)
    public void profile(GameProfileRequestEvent event) {
        Challenge challenge = challenges.get(event.getConnection().getRemoteAddress());
        if (challenge == null || !event.isOnlineMode() || !challenge.expiresAt().isAfter(Instant.now())) return;
        GameProfile original = event.getOriginalProfile();
        GameProfile profile = event.getGameProfile();
        String name = original.getName();
        if (!challenge.playerName().equals(name) || !challenge.mojangUuid().equals(original.getId())
            || !profile.getName().equals(name) || !profile.getId().equals(original.getId())) return;

        var properties = new ArrayList<>(profile.getProperties());
        properties.removeIf(property -> PremiumLoginProof.PROPERTY.equals(property.getName()));
        properties.add(new GameProfile.Property(
            PremiumLoginProof.PROPERTY, proofs.issue(name, original.getId(), Instant.now(), challenge.bindingRequestedAt()), ""
        ));
        event.setGameProfile(profile.withId(PremiumLoginProof.offlineUuid(name)).withProperties(properties));
    }

    @Subscribe(priority = Short.MIN_VALUE)
    public void login(LoginEvent event) {
        Player player = event.getPlayer();
        Challenge challenge = challenges.remove(player.getRemoteAddress());
        if (challenge == null || !event.getResult().isAllowed()) return;
        if (!verified(player)) event.setResult(LoginEvent.ComponentResult.denied(INVALID));
    }

    @Subscribe(priority = Short.MIN_VALUE)
    public void initialServer(PlayerChooseInitialServerEvent event) {
        if (verified(event.getPlayer())) event.setInitialServer(loginServer);
    }

    private boolean verified(Player player) {
        return player.isOnlineMode() && player.getGameProfileProperties().stream()
            .filter(property -> PremiumLoginProof.PROPERTY.equals(property.getName()))
            .anyMatch(property -> proofs.verify(
                property.getValue(), player.getUsername(), player.getUniqueId(), Instant.now()
            ).isPresent());
    }

    private static Component green(String text) {
        return Component.text(text, NamedTextColor.GREEN);
    }

    private record BindingRequest(InetAddress address, long requestedAt, Instant expiresAt) {
    }

    private record Challenge(String playerName, UUID mojangUuid, long bindingRequestedAt, Instant expiresAt) {
    }

    private record UnlinkPrompt(UUID token, Player player, Instant expiresAt) {}
}
