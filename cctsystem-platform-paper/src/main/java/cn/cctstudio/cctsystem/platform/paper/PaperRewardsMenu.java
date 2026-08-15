package cn.cctstudio.cctsystem.platform.paper;

import cn.cctstudio.cctsystem.core.concurrent.PlatformTaskExecutor;
import cn.cctstudio.cctsystem.core.logging.CctLogger;
import cn.cctstudio.cctsystem.core.provider.ProviderRegistry;
import cn.cctstudio.cctsystem.storage.mysql.DatabaseAccess;
import cn.cctstudio.cctsystem.storage.mysql.DatabaseProvider;
import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;

/** Network-wide Delivery Man rewards backed by CCTSystem MySQL. */
final class PaperRewardsMenu implements Listener {
    private static final Duration RESERVATION_TIMEOUT = Duration.ofMinutes(2);
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private final JavaPlugin plugin;
    private final ProviderRegistry providers;
    private final PlatformTaskExecutor platformTasks;
    private final Executor blockingExecutor;
    private final CctLogger logger;
    private final PaperMessages messages;
    private final PaperMenus menus;
    private final PaperLuckPermsTitles titles;
    private final String serverId;
    private final Map<UUID, Map<String, RewardState>> cache = new ConcurrentHashMap<>();
    private final Map<UUID, CompletableFuture<Map<String, RewardState>>> loads =
        new ConcurrentHashMap<>();
    private final Map<UUID, CompletableFuture<?>> claims = new ConcurrentHashMap<>();

    PaperRewardsMenu(
        JavaPlugin plugin,
        ProviderRegistry providers,
        PlatformTaskExecutor platformTasks,
        Executor blockingExecutor,
        CctLogger logger,
        PaperMessages messages,
        PaperMenus menus,
        PaperLuckPermsTitles titles,
        String serverId
    ) {
        this.plugin = plugin;
        this.providers = providers;
        this.platformTasks = platformTasks;
        this.blockingExecutor = blockingExecutor;
        this.logger = logger;
        this.messages = messages;
        this.menus = menus;
        this.titles = titles;
        this.serverId = serverId;
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::refreshOpenCountdowns, 20L, 20L);
        for (Player player : plugin.getServer().getOnlinePlayers()) preload(player);
    }

    void open(Player player) {
        Map<String, RewardDefinition> definitions = definitions();
        PaperMenus.MenuDefinition layout = menus.get("rewards");
        Map<String, RewardState> states = cache.get(player.getUniqueId());
        RewardsHolder holder = new RewardsHolder(
            layout, definitions, states == null ? Map.of() : states, states != null
        );
        holder.inventory = plugin.getServer().createInventory(
            holder, layout.size(), messages.component(layout.titleKey())
        );
        render(player, holder);
        player.openInventory(holder.inventory);
        refresh(player, holder);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> preload(event.getPlayer()), 20L);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder(false) instanceof RewardsHolder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)
            || event.getClickedInventory() != event.getView().getTopInventory()) return;
        String action = holder.layout.actionAt(event.getRawSlot());
        if ("CLOSE".equals(action)) {
            player.closeInventory();
            return;
        }
        if (!"CLAIM_REWARD".equals(action)) return;
        RewardDefinition reward = holder.definitions.get(holder.layout.valueAt(event.getRawSlot()));
        if (reward == null) return;
        if (!holder.loaded) {
            player.sendActionBar(messages.component("rewards.loading"));
            return;
        }
        claim(player, holder, reward);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder(false) instanceof RewardsHolder) event.setCancelled(true);
    }

    private void preload(Player player) {
        if (!player.isOnline() || database() == null) return;
        load(player.getUniqueId()).whenComplete((states, failure) -> {
            if (failure == null) cache.put(player.getUniqueId(), states);
        });
    }

    private void refresh(Player player, RewardsHolder holder) {
        UUID uuid = player.getUniqueId();
        CompletableFuture<Map<String, RewardState>> load = loads.computeIfAbsent(uuid, this::load);
        load.whenComplete((states, failure) -> main(() -> {
            loads.remove(uuid, load);
            if (!player.isOnline()) return;
            if (failure != null) {
                logger.warn("Unable to load rewards for " + uuid, unwrap(failure));
                player.sendActionBar(messages.component("rewards.service-unavailable"));
                return;
            }
            cache.put(uuid, states);
            holder.states = states;
            holder.loaded = true;
            if (player.getOpenInventory().getTopInventory() == holder.inventory) render(player, holder);
        }));
    }

    private CompletableFuture<Map<String, RewardState>> load(UUID uuid) {
        return CompletableFuture.supplyAsync(() -> {
            DatabaseAccess database = database();
            if (database == null) throw new IllegalStateException("CCTSystem database is unavailable");
            Map<String, RewardDefinition> definitions = definitions();
            Map<String, RewardState> states = new LinkedHashMap<>();
            try (Connection connection = database.connection();
                 PreparedStatement statement = connection.prepareStatement("""
                     SELECT reward_key, state, next_available_at, reserved_at, last_claimed_at
                     FROM cct_delivery_reward_claims WHERE player_uuid = ?
                     """)) {
                statement.setBytes(1, uuidBytes(uuid));
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        String rewardKey = result.getString("reward_key");
                        String state = result.getString("state");
                        Timestamp reserved = result.getTimestamp("reserved_at");
                        Timestamp claimed = result.getTimestamp("last_claimed_at");
                        Instant storedNext = result.getTimestamp("next_available_at").toInstant();
                        Instant next = normalizedNext(
                            definitions.get(rewardKey),
                            state,
                            storedNext,
                            claimed == null ? null : claimed.toInstant()
                        );
                        states.put(rewardKey, new RewardState(
                            next,
                            state,
                            reserved == null ? null : reserved.toInstant()
                        ));
                    }
                }
                return Map.copyOf(states);
            } catch (SQLException exception) {
                throw new IllegalStateException("Unable to load delivery rewards", exception);
            }
        }, blockingExecutor);
    }

    private void claim(Player player, RewardsHolder holder, RewardDefinition reward) {
        if (!reward.permission().isBlank() && !player.hasPermission(reward.permission())) {
            player.sendActionBar(messages.component("rewards.no-permission"));
            return;
        }
        RewardState current = holder.states.get(reward.key());
        if (current != null && !current.available(Instant.now())) {
            player.sendActionBar(messages.component("rewards.already-claimed"));
            return;
        }
        if (claims.containsKey(player.getUniqueId())) {
            player.sendActionBar(messages.component("rewards.processing"));
            return;
        }
        UUID transactionId = UUID.randomUUID();
        player.sendActionBar(messages.component("rewards.processing"));
        CompletableFuture<Reservation> reservation = CompletableFuture.supplyAsync(
            () -> reserve(player.getUniqueId(), reward, transactionId), blockingExecutor
        );
        claims.put(player.getUniqueId(), reservation);
        reservation.whenComplete((reserved, failure) -> main(() -> {
            if (failure != null) {
                finishClaim(player, holder, reservation);
                logger.warn("Unable to reserve reward " + reward.key(), unwrap(failure));
                player.sendMessage(messages.component("rewards.failed"));
                return;
            }
            if (!reserved.accepted()) {
                finishClaim(player, holder, reservation);
                player.sendActionBar(messages.component("rewards.already-claimed"));
                refresh(player, holder);
                return;
            }
            boolean delivered = deliver(player, reward);
            CompletableFuture<Void> completion = CompletableFuture.runAsync(() -> {
                if (delivered) complete(player.getUniqueId(), reward.key(), transactionId);
                else release(player.getUniqueId(), reward.key(), transactionId, "PROVIDER_UNAVAILABLE");
            }, blockingExecutor);
            claims.put(player.getUniqueId(), completion);
            completion.whenComplete((ignored, completionFailure) -> main(() -> {
                finishClaim(player, holder, completion);
                if (completionFailure != null || !delivered) {
                    if (completionFailure != null) logger.warn(
                        "Unable to finalize reward " + reward.key(), unwrap(completionFailure)
                    );
                    player.sendMessage(messages.component(delivered
                        ? "rewards.failed" : "rewards.provider-unavailable"));
                } else {
                    player.sendMessage(messages.component("rewards.completed"));
                }
                refresh(player, holder);
            }));
        }));
    }

    private Reservation reserve(UUID uuid, RewardDefinition reward, UUID transactionId) {
        DatabaseAccess database = requireDatabase();
        Instant now = Instant.now();
        try (Connection connection = database.connection()) {
            connection.setAutoCommit(false);
            try {
                RewardState current = lock(connection, uuid, reward);
                if (current != null && !current.available(now)) {
                    connection.rollback();
                    return new Reservation(false);
                }
                Instant next = nextAvailableAt(reward, now);
                try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO cct_delivery_reward_claims(
                        player_uuid, reward_key, state, transaction_id,
                        next_available_at, reserved_at
                    ) VALUES (?, ?, 'RESERVED', ?, ?, ?)
                    ON DUPLICATE KEY UPDATE state = 'RESERVED',
                        transaction_id = VALUES(transaction_id),
                        next_available_at = VALUES(next_available_at),
                        reserved_at = VALUES(reserved_at)
                    """)) {
                    statement.setBytes(1, uuidBytes(uuid));
                    statement.setString(2, reward.key());
                    statement.setBytes(3, uuidBytes(transactionId));
                    statement.setTimestamp(4, Timestamp.from(next));
                    statement.setTimestamp(5, Timestamp.from(now));
                    statement.executeUpdate();
                }
                audit(connection, transactionId, uuid, reward.key(), "RESERVED", null);
                connection.commit();
                return new Reservation(true);
            } catch (SQLException failure) {
                connection.rollback();
                throw failure;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to reserve delivery reward", exception);
        }
    }

    private RewardState lock(Connection connection, UUID uuid, RewardDefinition reward)
        throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT state, next_available_at, reserved_at, last_claimed_at
            FROM cct_delivery_reward_claims
            WHERE player_uuid = ? AND reward_key = ? FOR UPDATE
            """)) {
            statement.setBytes(1, uuidBytes(uuid));
            statement.setString(2, reward.key());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return null;
                String state = result.getString("state");
                Timestamp reserved = result.getTimestamp("reserved_at");
                Timestamp claimed = result.getTimestamp("last_claimed_at");
                Instant storedNext = result.getTimestamp("next_available_at").toInstant();
                return new RewardState(
                    normalizedNext(
                        reward,
                        state,
                        storedNext,
                        claimed == null ? null : claimed.toInstant()
                    ),
                    state,
                    reserved == null ? null : reserved.toInstant()
                );
            }
        }
    }

    private void complete(UUID uuid, String rewardKey, UUID transactionId) {
        updateReservation(uuid, rewardKey, transactionId, true, null);
    }

    private void release(UUID uuid, String rewardKey, UUID transactionId, String detail) {
        updateReservation(uuid, rewardKey, transactionId, false, detail);
    }

    private void updateReservation(
        UUID uuid,
        String rewardKey,
        UUID transactionId,
        boolean completed,
        String detail
    ) {
        DatabaseAccess database = requireDatabase();
        try (Connection connection = database.connection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(completed ? """
                UPDATE cct_delivery_reward_claims
                SET state = 'COMPLETED', reserved_at = NULL, last_claimed_at = CURRENT_TIMESTAMP(3)
                WHERE player_uuid = ? AND reward_key = ? AND transaction_id = ? AND state = 'RESERVED'
                """ : """
                UPDATE cct_delivery_reward_claims
                SET state = 'FAILED', reserved_at = NULL, next_available_at = CURRENT_TIMESTAMP(3)
                WHERE player_uuid = ? AND reward_key = ? AND transaction_id = ? AND state = 'RESERVED'
                """)) {
                statement.setBytes(1, uuidBytes(uuid));
                statement.setString(2, rewardKey);
                statement.setBytes(3, uuidBytes(transactionId));
                if (statement.executeUpdate() != 1) {
                    throw new SQLException("Reward reservation is stale");
                }
                audit(connection, transactionId, uuid, rewardKey,
                    completed ? "COMPLETED" : "FAILED", detail);
                connection.commit();
            } catch (SQLException failure) {
                connection.rollback();
                throw failure;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to update delivery reward", exception);
        }
    }

    private boolean deliver(Player player, RewardDefinition reward) {
        if (!plugin.getServer().getPluginManager().isPluginEnabled("GadgetsMenu")) return false;
        for (String command : reward.commands()) {
            String rendered = command.replace("{player}", player.getName());
            if (!plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), rendered)) {
                return false;
            }
        }
        return true;
    }

    private void audit(
        Connection connection,
        UUID transactionId,
        UUID playerUuid,
        String rewardKey,
        String action,
        String detail
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO cct_delivery_reward_audit(
                audit_id, transaction_id, player_uuid, reward_key, action, server_id, detail
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            """)) {
            statement.setBytes(1, uuidBytes(UUID.randomUUID()));
            statement.setBytes(2, uuidBytes(transactionId));
            statement.setBytes(3, uuidBytes(playerUuid));
            statement.setString(4, rewardKey);
            statement.setString(5, action);
            statement.setString(6, serverId);
            statement.setString(7, detail);
            statement.executeUpdate();
        }
    }

    private void render(Player player, RewardsHolder holder) {
        holder.layout.fill(holder.inventory);
        setProfile(player, holder);
        for (RewardDefinition reward : holder.definitions.values()) {
            PaperMenus.MenuItemDefinition item = itemForValue(holder.layout, reward.key());
            if (item == null) continue;
            RewardState state = holder.states.get(reward.key());
            boolean permitted = reward.permission().isBlank() || player.hasPermission(reward.permission());
            boolean available = holder.loaded && permitted && (state == null || state.available(Instant.now()));
            Material material = available
                ? item.material(Material.CHEST_MINECART)
                : reward.claimedMaterial();
            ItemStack stack = new ItemStack(material);
            ItemMeta meta = stack.getItemMeta();
            meta.displayName(noItalic(messages.component("rewards.menu." + reward.key() + ".name")));
            List<Component> lore = new ArrayList<>(messages.components(
                "rewards.menu." + reward.key() + ".lore"
            ));
            lore.add(Component.empty());
            if (!holder.loaded) {
                lore.add(messages.component("rewards.loading"));
            } else if (!permitted) {
                lore.add(messages.component("rewards.no-permission"));
            } else if (available) {
                lore.add(messages.component("rewards.available-now"));
                lore.add(messages.component("rewards.click"));
            } else {
                lore.add(messages.component("rewards.claimed"));
                lore.add(messages.component("rewards.available-in", Map.of(
                    "time", remaining(state.nextAvailableAt())
                )));
            }
            meta.lore(lore.stream().map(PaperRewardsMenu::noItalic).toList());
            stack.setItemMeta(meta);
            holder.inventory.setItem(item.slot(), stack);
        }
        PaperMenus.MenuItemDefinition close = holder.layout.item("close");
        ItemStack closeItem = new ItemStack(close.material(Material.BARRIER));
        ItemMeta closeMeta = closeItem.getItemMeta();
        closeMeta.displayName(noItalic(messages.component("rewards.menu.close")));
        closeItem.setItemMeta(closeMeta);
        holder.inventory.setItem(close.slot(), closeItem);
    }

    private void setProfile(Player player, RewardsHolder holder) {
        PaperMenus.MenuItemDefinition profile = holder.layout.item("profile");
        ItemStack head = new ItemStack(profile.material(Material.PLAYER_HEAD));
        ItemMeta meta = head.getItemMeta();
        meta.displayName(noItalic(titles.playerIdentity(player)));
        meta.lore(messages.components("rewards.menu.profile-lore").stream()
            .map(PaperRewardsMenu::noItalic).toList());
        if (meta instanceof SkullMeta skull) skull.setPlayerProfile(player.getPlayerProfile());
        head.setItemMeta(meta);
        holder.inventory.setItem(profile.slot(), head);
    }

    private void refreshOpenCountdowns() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            Inventory top = player.getOpenInventory().getTopInventory();
            if (top.getHolder(false) instanceof RewardsHolder holder) render(player, holder);
        }
    }

    private static Component noItalic(Component component) {
        return component.decoration(TextDecoration.ITALIC, false);
    }

    private Map<String, RewardDefinition> definitions() {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(
            new java.io.File(plugin.getDataFolder(), "menus/rewards.yml")
        );
        Map<String, RewardDefinition> result = new LinkedHashMap<>();
        ConfigurationSection section = yaml.getConfigurationSection("rewards");
        if (section == null) return Map.of();
        for (String key : section.getKeys(false)) {
            String path = "rewards." + key + ".";
            Material claimed = Material.matchMaterial(yaml.getString(path + "claimed-material", "MINECART"));
            if (claimed == null || !claimed.isItem()) claimed = Material.MINECART;
            long cooldown = Math.max(60L, yaml.getLong(path + "cooldown-seconds", 86400L));
            result.put(key, new RewardDefinition(
                key,
                resetPolicy(yaml.getString(path + "reset-policy"), key, cooldown),
                cooldown,
                yaml.getString(path + "permission", "").trim(),
                claimed,
                List.copyOf(yaml.getStringList(path + "commands"))
            ));
        }
        return Map.copyOf(result);
    }

    private static PaperMenus.MenuItemDefinition itemForValue(
        PaperMenus.MenuDefinition layout, String value
    ) {
        return layout.items().values().stream()
            .filter(item -> "CLAIM_REWARD".equals(item.action()) && value.equals(item.value()))
            .findFirst().orElse(null);
    }

    private DatabaseAccess database() {
        return providers.find(DatabaseProvider.KEY).orElse(null);
    }

    private DatabaseAccess requireDatabase() {
        DatabaseAccess database = database();
        if (database == null) throw new IllegalStateException("CCTSystem database is unavailable");
        return database;
    }

    private void finishClaim(Player player, RewardsHolder holder, CompletableFuture<?> operation) {
        claims.remove(player.getUniqueId(), operation);
        if (player.isOnline() && player.getOpenInventory().getTopInventory() == holder.inventory) {
            render(player, holder);
        }
    }

    private void main(Runnable action) {
        platformTasks.callMain(() -> {
            action.run();
            return null;
        });
    }

    private static String remaining(Instant target) {
        long seconds = Math.max(0L, Duration.between(Instant.now(), target).toSeconds());
        long days = seconds / 86400;
        long hours = seconds % 86400 / 3600;
        long minutes = seconds % 3600 / 60;
        long remainder = seconds % 60;
        if (days > 0) return days + "天 " + hours + "小时";
        if (hours > 0) return hours + "小时 " + minutes + "分";
        if (minutes > 0) return minutes + "分 " + remainder + "秒";
        return remainder + "秒";
    }

    private static Instant normalizedNext(
        RewardDefinition reward,
        String state,
        Instant storedNext,
        Instant lastClaimedAt
    ) {
        if (reward == null || lastClaimedAt == null || !"COMPLETED".equals(state)) {
            return storedNext;
        }
        return nextAvailableAt(reward, lastClaimedAt);
    }

    private static Instant nextAvailableAt(RewardDefinition reward, Instant reference) {
        ZonedDateTime local = reference.atZone(SHANGHAI);
        return switch (reward.resetPolicy()) {
            case DAILY -> local.toLocalDate().plusDays(1).atStartOfDay(SHANGHAI).toInstant();
            case MONTHLY -> YearMonth.from(local).plusMonths(1).atDay(1)
                .atStartOfDay(SHANGHAI).toInstant();
            case DURATION -> reference.plusSeconds(reward.cooldownSeconds());
        };
    }

    private static ResetPolicy resetPolicy(String configured, String key, long cooldownSeconds) {
        if (configured != null && !configured.isBlank()) {
            try {
                return ResetPolicy.valueOf(configured.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                // Fall through to compatibility inference for existing menu configurations.
            }
        }
        if ("daily".equalsIgnoreCase(key)) return ResetPolicy.DAILY;
        return cooldownSeconds >= 2_419_200L ? ResetPolicy.MONTHLY : ResetPolicy.DURATION;
    }

    private static byte[] uuidBytes(UUID uuid) {
        return ByteBuffer.allocate(16)
            .putLong(uuid.getMostSignificantBits())
            .putLong(uuid.getLeastSignificantBits())
            .array();
    }

    private static Throwable unwrap(Throwable throwable) {
        return throwable instanceof java.util.concurrent.CompletionException
            && throwable.getCause() != null ? throwable.getCause() : throwable;
    }

    private record RewardDefinition(
        String key,
        ResetPolicy resetPolicy,
        long cooldownSeconds,
        String permission,
        Material claimedMaterial,
        List<String> commands
    ) { }

    private enum ResetPolicy {
        DAILY,
        MONTHLY,
        DURATION
    }

    private record RewardState(Instant nextAvailableAt, String state, Instant reservedAt) {
        boolean available(Instant now) {
            if ("RESERVED".equals(state)) {
                return reservedAt == null || !reservedAt.plus(RESERVATION_TIMEOUT).isAfter(now);
            }
            return !nextAvailableAt.isAfter(now) || "FAILED".equals(state);
        }
    }

    private record Reservation(boolean accepted) { }

    private static final class RewardsHolder implements InventoryHolder {
        private final PaperMenus.MenuDefinition layout;
        private final Map<String, RewardDefinition> definitions;
        private Map<String, RewardState> states;
        private boolean loaded;
        private Inventory inventory;

        private RewardsHolder(
            PaperMenus.MenuDefinition layout,
            Map<String, RewardDefinition> definitions,
            Map<String, RewardState> states,
            boolean loaded
        ) {
            this.layout = layout;
            this.definitions = definitions;
            this.states = states;
            this.loaded = loaded;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
