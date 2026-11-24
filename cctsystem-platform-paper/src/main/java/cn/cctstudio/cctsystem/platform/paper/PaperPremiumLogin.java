package cn.cctstudio.cctsystem.platform.paper;

import cn.cctstudio.cctsystem.core.CctRuntime;
import cn.cctstudio.cctsystem.core.auth.PremiumLoginProof;
import cn.cctstudio.cctsystem.core.auth.PremiumLoginProof.Session;
import cn.cctstudio.cctsystem.core.config.PremiumLoginConfig;
import cn.cctstudio.cctsystem.storage.mysql.DatabaseProvider;
import cn.cctstudio.cctsystem.storage.mysql.PremiumLoginStore;
import cn.cctstudio.cctsystem.storage.mysql.SocialBindingLookup;
import net.kyori.adventure.text.format.NamedTextColor;
import cn.cctstudio.cctsystem.storage.mysql.PremiumLoginStore.Claim;
import fr.xephi.authme.api.v3.AuthMeApi;
import fr.xephi.authme.api.v3.AuthMePlayer;
import fr.xephi.authme.datasource.DataSource;
import fr.xephi.authme.events.LoginEvent;
import fr.xephi.authme.events.RestoreSessionEvent;
import io.papermc.paper.connection.PlayerConnection;
import java.net.SocketAddress;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

final class PaperPremiumLogin implements Listener {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private final PaperBootstrap plugin;
    private final CctRuntime runtime;
    private final PremiumLoginConfig config;
    private final PremiumLoginProof proofs;
    private final Map<ConnectionKey, VerifiedConnection> verifiedConnections = new ConcurrentHashMap<>();
    private final Map<UUID, Instant> titlesShown = new HashMap<>();

    PaperPremiumLogin(PaperBootstrap plugin, CctRuntime runtime, PremiumLoginConfig config) {
        this.plugin = plugin;
        this.runtime = runtime;
        this.config = config;
        this.proofs = new PremiumLoginProof(config.keyBytes(), runtime.config().networkId());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void verifyFreshForwardedProfile(AsyncPlayerPreLoginEvent event) {
        PlayerConnection connection = event.getConnection();
        ConnectionKey key = connectionKey(event.getUniqueId(), connection);
        if (key == null) return;
        verifiedConnections.remove(key);

        // Paper can merge cached profile properties after this event. Read only the properties
        // on this connection and keep the signed assertion out of the final player profile.
        var profile = event.getPlayerProfile();
        var properties = profile.getProperties().stream()
            .filter(property -> PremiumLoginProof.PROPERTY.equals(property.getName())).toList();
        profile.removeProperty(PremiumLoginProof.PROPERTY);
        if (properties.size() != 1) return;

        Instant now = Instant.now();
        proofs.verify(properties.getFirst().getValue(), event.getName(), event.getUniqueId(), now).ifPresent(session -> {
            verifiedConnections.values().removeIf(verified -> verified.expiresAt().isBefore(now));
            verifiedConnections.put(key, new VerifiedConnection(session, now.plusSeconds(600)));
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void playerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        ConnectionKey key = connectionKey(player.getUniqueId(), player.getConnection());
        if (key != null) verifiedConnections.remove(key);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void showTitleAfterJoin(PlayerJoinEvent event) {
        if (!config.titles().enabled() || !config.titleServer().equals(runtime.config().serverId())) return;
        Player player = event.getPlayer();
        proof(player).ifPresent(session -> plugin.getServer().getScheduler().runTaskLater(
            plugin, () -> titleAfterRewards(player, session, 12), 30L
        ));
    }

    private void titleAfterRewards(Player player, Session session, int retries) {
        if (!player.isOnline() || titlesShown.containsKey(session.sessionId())) return;
        query(() -> store().find(session)).whenComplete((claim, failure) ->
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (!player.isOnline() || titlesShown.containsKey(session.sessionId())) return;
                Claim known = failure == null ? claim.orElse(null) : null;
                if ((known == null || !known.complete()) && retries > 0) {
                    plugin.getServer().getScheduler().runTaskLater(
                        plugin, () -> titleAfterRewards(player, session, retries - 1), 20L
                    );
                    return;
                }
                if (known == null) return;
                var titles = config.titles();
                boolean first = known.firstSession().equals(session.sessionId());
                if (!first && !titles.showReturning()) return;
                String subtitle = !known.complete() ? titles.pendingSubtitle()
                    : first ? titles.firstSubtitle() : titles.returningSubtitle();
                Instant now = Instant.now();
                titlesShown.values().removeIf(expiry -> expiry.isBefore(now));
                titlesShown.put(session.sessionId(), now.plusSeconds(600));
                player.showTitle(Title.title(LEGACY.deserialize(titles.title()), LEGACY.deserialize(subtitle),
                    Title.Times.times(ticks(titles.fadeInTicks()), ticks(titles.stayTicks()), ticks(titles.fadeOutTicks()))
                ));
            })
        );
    }

    private static Duration ticks(int ticks) {
        return Duration.ofMillis(ticks * 50L);
    }

    private Optional<Session> proof(Player player) {
        ConnectionKey key = connectionKey(player.getUniqueId(), player.getConnection());
        if (key == null) return Optional.empty();
        VerifiedConnection verified = verifiedConnections.get(key);
        if (verified == null || verified.expiresAt().isBefore(Instant.now())
            || !verified.session().name().equals(player.getName())) return Optional.empty();
        return Optional.of(verified.session());
    }

    private static ConnectionKey connectionKey(UUID playerUuid, PlayerConnection connection) {
        if (connection == null) return null;
        SocketAddress address = connection.getAddress();
        return address == null ? null : new ConnectionKey(playerUuid, address);
    }

    private record ConnectionKey(UUID playerUuid, SocketAddress address) {
    }

    private record VerifiedConnection(Session session, Instant expiresAt) {
    }

    PremiumLoginStore store() {
        return new PremiumLoginStore(runtime.providers().find(DatabaseProvider.KEY).orElseThrow());
    }

    <T> CompletableFuture<T> query(Callable<T> task) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return task.call();
            } catch (Exception exception) {
                throw new CompletionException(exception);
            }
        }, runtime.executors().blocking());
    }

    Listener authListener() {
        return new AuthListener();
    }

    private final class AuthListener implements Listener {
        private final AuthMeApi auth = AuthMeApi.getInstance();
        private final DataSource accounts = authMeAccounts(auth);

        @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
        public void preventIpSessionBypass(RestoreSessionEvent event) {
            Player player = event.getPlayer();
            if (proof(player).isPresent()) {
                event.setCancelled(true);
                return;
            }
            // AuthMe forceLogin creates an IP session. Never let an offline connection replay it.
            try {
                if (store().hasBindingHistory(player.getName())) event.setCancelled(true);
            } catch (SQLException | RuntimeException exception) {
                event.setCancelled(true);
            }
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void autoLogin(PlayerJoinEvent event) {
            Player player = event.getPlayer();
            proof(player).ifPresent(session -> query(() -> canAutoLogin(session)).thenAccept(allowed -> {
                if (!allowed) return;
                plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                    if (player.isOnline() && !auth.isAuthenticated(player)) auth.forceLogin(player);
                }, 3L);
            }).exceptionally(failure -> {
                plugin.getLogger().warning("Java auto-login lookup failed for " + session.playerUuid());
                return null;
            }));
        }

        private boolean canAutoLogin(Session session) throws SQLException {
            if (accounts == null || session.bindingRequestedAt() > 0) return false;
            Claim claim = store().find(session).orElse(null);
            if (claim == null) return false;
            var account = accounts.getAuth(session.name().toLowerCase(Locale.ROOT));
            return account != null && session.name().equals(account.getRealName())
                && claim.registeredAt() == account.getRegistrationDate() && account.getTotpKey() == null;
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void confirmAuthMeLogin(LoginEvent event) {
            Player player = event.getPlayer();
            if (!auth.isAuthenticated(player)) return;
            proof(player).filter(session -> session.bindingRequestedAt() > 0).ifPresent(session -> query(() -> {
                Optional<AuthMePlayer> account = auth.getPlayerInfo(session.name());
                if (account.isEmpty() || !session.name().equals(account.get().getName())) return false;
                var claims = store();
                if (config.requireSocialBinding() && claims.find(session).isEmpty()
                    && !SocialBindingLookup.isBound(runtime.providers().find(DatabaseProvider.KEY).orElseThrow(),
                        session.playerUuid())) {
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        if (player.isOnline()) player.sendMessage(green("请先绑定 QQ 或 Discord，再绑定 Java 正版账号。"));
                    });
                    return true;
                }
                boolean linked = claims.link(session, account.get().getRegistrationDate().toEpochMilli(), config);
                if (linked) {
                    boolean membershipOnHold = claims.find(session).filter(claim -> claim.firstSession().equals(session.sessionId()))
                        .isPresent() && config.rewards().membershipDays() > 0
                        && !SocialBindingLookup.isBound(runtime.providers().find(DatabaseProvider.KEY).orElseThrow(),
                            session.playerUuid());
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        if (!player.isOnline()) return;
                        player.sendMessage(green("正版账号已绑定。以后使用正版客户端连接原地址即可免密登录。"));
                        if (membershipOnHold) {
                            player.sendMessage(green("绑定 QQ 或 Discord 后，正版赠送的会员会自动发放。"));
                        }
                    });
                }
                return linked;
            }).whenComplete((linked, failure) -> {
                if (failure == null && Boolean.TRUE.equals(linked)) return;
                if (failure != null) {
                    plugin.getLogger().warning("Java identity binding failed for " + session.playerUuid());
                }
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (player.isOnline()) player.sendMessage(green(failure != null
                        ? "正版账号绑定暂不可用，请稍后从正版入口重新登录。"
                        : "此正版账号或游戏账号已有绑定。请用原来的游戏名称登录，或联系管理员。"));
                });
            }));
        }
    }

    private static Component green(String text) {
        return Component.text(text, NamedTextColor.GREEN);
    }

    private DataSource authMeAccounts(AuthMeApi auth) {
        // AuthMe 6.0's public API does not expose TOTP status. If its read-only adapter changes,
        // keep the manual AuthMe login path for everyone instead of skipping a second factor.
        try {
            var field = AuthMeApi.class.getDeclaredField("dataSource");
            if (field.trySetAccessible() && field.get(auth) instanceof DataSource dataSource) return dataSource;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            plugin.getLogger().warning("AuthMe account inspection unavailable. Java players will enter their password.");
            return null;
        }
        plugin.getLogger().warning("AuthMe account inspection unavailable. Java players will enter their password.");
        return null;
    }
}
