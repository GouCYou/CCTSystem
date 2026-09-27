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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.NodeType;
import net.luckperms.api.node.types.PrefixNode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

/** Stores display-only titles without changing a player's LuckPerms inheritance or permissions. */
final class PaperTitleService implements Listener, AutoCloseable {
    static final int MANAGED_PREFIX_PRIORITY = 10_000;
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final Map<String, Integer> WEIGHTS = weights();
    private static final List<String> BUILT_INS = List.of(
        "owner", "admin", "mod", "helper", "mvpp", "mvp", "vipp", "vip", "default"
    );

    private final JavaPlugin plugin;
    private final ProviderRegistry providers;
    private final PlatformTaskExecutor platformTasks;
    private final Executor blockingExecutor;
    private final CctLogger logger;
    private final PaperMessages messages;
    private final PaperLuckPermsTitles titles;
    private final LuckPerms luckPerms;
    private final PaperTabIntegration tab;
    private PaperNicknameService nicknames;
    private final Map<UUID, TitleState> states = new ConcurrentHashMap<>();
    private volatile int expiryTask = -1;

    PaperTitleService(
        JavaPlugin plugin,
        ProviderRegistry providers,
        PlatformTaskExecutor platformTasks,
        Executor blockingExecutor,
        CctLogger logger,
        PaperMessages messages,
        PaperLuckPermsTitles titles,
        LuckPerms luckPerms
    ) {
        this.plugin = plugin;
        this.providers = providers;
        this.platformTasks = platformTasks;
        this.blockingExecutor = blockingExecutor;
        this.logger = logger;
        this.messages = messages;
        this.titles = titles;
        this.luckPerms = luckPerms;
        this.tab = new PaperTabIntegration(plugin, logger);
    }

    void start() {
        for (Player player : plugin.getServer().getOnlinePlayers()) load(player, 10L);
        expiryTask = plugin.getServer().getScheduler().scheduleSyncRepeatingTask(
            plugin,
            () -> plugin.getServer().getOnlinePlayers().forEach(player -> load(player, 0L)),
            1200L,
            1200L
        );
    }

    void setNicknameService(PaperNicknameService nicknames) {
        this.nicknames = nicknames;
    }

    boolean command(Player player, String[] arguments) {
        if (!isStaff(player)) {
            player.sendMessage(messages.component("titles.staff-only"));
            return true;
        }
        if (arguments.length == 0) {
            open(player);
            return true;
        }
        AffixType type = AffixType.from(arguments[0]);
        if (type == null) {
            player.sendMessage(messages.component("titles.not-available"));
            return true;
        }
        if (arguments.length == 1) {
            openLibrary(player, type);
            return true;
        }
        String wanted = normalizeKey(arguments[1]);
        TitleState state = states.get(player.getUniqueId());
        if (state == null) {
            player.sendMessage(messages.component("titles.loading"));
            load(player, 0L);
            return true;
        }
        Optional<TitleChoice> choice = choices(player, state, type).stream()
            .filter(candidate -> candidate.key().equalsIgnoreCase(wanted)
                || plain(candidate.displayName()).equalsIgnoreCase(plain(arguments[1])))
            .findFirst();
        if (choice.isEmpty()) {
            player.sendMessage(messages.component("titles.not-available"));
            return true;
        }
        select(player, choice.orElseThrow());
        return true;
    }

    List<String> completions(Player player) {
        TitleState state = states.get(player.getUniqueId());
        if (!isStaff(player) || state == null) return List.of();
        return List.of("prefix", "suffix");
    }

    List<String> completions(Player player, String typeName) {
        TitleState state = states.get(player.getUniqueId());
        AffixType type = AffixType.from(typeName);
        if (!isStaff(player) || state == null || type == null) return List.of();
        return choices(player, state, type).stream().map(TitleChoice::key).toList();
    }

    void open(Player player) {
        TitleState state = states.get(player.getUniqueId());
        if (state == null) {
            player.sendMessage(messages.component("titles.loading"));
            load(player, 0L);
            return;
        }
        AffixHubHolder holder = new AffixHubHolder();
        holder.inventory = plugin.getServer().createInventory(
            holder, 27, messages.component("titles.hub-title")
        );
        holder.inventory.setItem(11, simpleItem(
            Material.NAME_TAG, messages.component("titles.prefix-library"),
            messages.components("titles.prefix-library-lore")
        ));
        holder.inventory.setItem(15, simpleItem(
            Material.NAME_TAG, messages.component("titles.suffix-library"),
            messages.components("titles.suffix-library-lore")
        ));
        player.openInventory(holder.inventory);
    }

    private void openLibrary(Player player, AffixType type) {
        TitleState state = states.get(player.getUniqueId());
        if (state == null) {
            player.sendMessage(messages.component("titles.loading"));
            return;
        }
        List<TitleChoice> choices = choices(player, state, type);
        TitleHolder holder = new TitleHolder(choices, state.selection(type), type);
        holder.inventory = plugin.getServer().createInventory(
            holder, 54, messages.component(type == AffixType.PREFIX
                ? "titles.prefix-menu-title" : "titles.suffix-menu-title")
        );
        for (int index = 0; index < Math.min(choices.size(), 36); index++) {
            TitleChoice choice = choices.get(index);
            int slot = 9 + index;
            ItemStack item = new ItemStack(choice.personal() ? Material.PAPER : material(choice.key()));
            ItemMeta meta = item.getItemMeta();
            String itemName = plain(choice.value()).isBlank()
                ? choice.displayName() : choice.value();
            meta.displayName(LEGACY.deserialize(itemName)
                .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
            List<Component> lore = new ArrayList<>();
            lore.add(messages.component("titles.preview", Map.of(
                "identity", type == AffixType.PREFIX
                    ? identity(choice.value(), player.getName(), titles.suffixLegacy(player))
                    : identity(titles.prefixLegacy(player), player.getName(), choice.value())
            )));
            if (choice.personal()) {
                lore.add(messages.component("titles.personal"));
                lore.add(messages.component("titles.expiry", Map.of(
                    "expiry", choice.expiresAt() == null ? messages.raw("titles.permanent")
                        : choice.expiresAt().toString()
                )));
            }
            lore.add(messages.component(choice.key().equals(state.selection(type))
                ? "titles.selected" : "titles.click-select"));
            meta.lore(lore.stream().map(line -> line.decoration(
                net.kyori.adventure.text.format.TextDecoration.ITALIC, false
            )).toList());
            item.setItemMeta(meta);
            holder.inventory.setItem(slot, item);
            holder.bySlot.put(slot, choice);
        }
        holder.inventory.setItem(49, simpleItem(
            Material.ARROW, messages.component("titles.back"), List.of()
        ));
        player.openInventory(holder.inventory);
    }

    CompletableFuture<UUID> grant(
        UUID playerUuid,
        String playerName,
        AffixType type,
        String display,
        int durationDays,
        String actor
    ) {
        if (display == null || display.strip().isBlank() || display.length() > 200) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("invalid title"));
        }
        String normalized = display.replace('§', '&').stripTrailing();
        Instant expiry = durationDays == -1 ? null : Instant.now().plus(Duration.ofDays(durationDays));
        if (durationDays == 0 || durationDays < -1) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("invalid duration"));
        }
        return user(playerUuid).thenCompose(user -> CompletableFuture.supplyAsync(() -> {
            UUID id = UUID.randomUUID();
            insertTitle(id, playerUuid, type, normalized, weight(user.getPrimaryGroup()), expiry, actor);
            return id;
        }, blockingExecutor)).whenComplete((ignored, failure) -> {
            if (failure == null) onlineReload(playerUuid);
        });
    }

    CompletableFuture<Boolean> remove(UUID playerUuid, AffixType type, String query) {
        return CompletableFuture.supplyAsync(() -> removeTitle(playerUuid, type, query), blockingExecutor)
            .whenComplete((ignored, failure) -> {
                if (failure == null) onlineReload(playerUuid);
            });
    }

    List<PersonalTitle> cachedTitles(UUID playerUuid) {
        TitleState state = states.get(playerUuid);
        return state == null ? List.of() : state.personalTitles();
    }

    CompletableFuture<List<PersonalTitle>> list(UUID playerUuid, AffixType type) {
        return CompletableFuture.supplyAsync(
            () -> loadState(playerUuid).personalTitles().stream()
                .filter(title -> title.type() == type).toList(), blockingExecutor
        );
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        load(event.getPlayer(), 10L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        states.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        InventoryHolder inventoryHolder = event.getInventory().getHolder(false);
        if (!(inventoryHolder instanceof TitleHolder || inventoryHolder instanceof AffixHubHolder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)
            || event.getClickedInventory() != event.getView().getTopInventory()) return;
        if (inventoryHolder instanceof AffixHubHolder) {
            if (event.getRawSlot() == 11) openLibrary(player, AffixType.PREFIX);
            if (event.getRawSlot() == 15) openLibrary(player, AffixType.SUFFIX);
            return;
        }
        TitleHolder holder = (TitleHolder) inventoryHolder;
        if (event.getRawSlot() == 49) {
            open(player);
            return;
        }
        TitleChoice choice = holder.bySlot.get(event.getRawSlot());
        if (choice != null) select(player, choice);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder(false) instanceof TitleHolder
            || event.getInventory().getHolder(false) instanceof AffixHubHolder) event.setCancelled(true);
    }

    private void select(Player player, TitleChoice choice) {
        TitleState available = states.get(player.getUniqueId());
        if (!isStaff(player) || available == null
            || choices(player, available, choice.type()).stream()
                .noneMatch(candidate -> candidate.key().equals(choice.key()))) {
            player.sendMessage(messages.component("titles.not-available"));
            return;
        }
        player.closeInventory();
        player.sendMessage(messages.component("titles.saving"));
        CompletableFuture.runAsync(
            () -> saveSelection(player.getUniqueId(), choice.type(), choice.key()), blockingExecutor
        ).thenCompose(ignored -> applyLuckPerms(player.getUniqueId(), choice.type(),
            choice.type() == AffixType.SUFFIX && choice.value().isBlank()
                ? "&r" : choice.value()))
            .whenComplete((ignored, failure) -> main(() -> {
                if (!player.isOnline()) return;
                if (failure != null) {
                    logger.warn("Unable to select title for " + player.getUniqueId(), unwrap(failure));
                    player.sendMessage(messages.component("titles.save-failed"));
                    return;
                }
                TitleState old = states.get(player.getUniqueId());
                if (old != null) states.put(player.getUniqueId(), old.withSelection(choice.type(), choice.key()));
                TitleState current = states.get(player.getUniqueId());
                TitleChoice prefix = choice.type() == AffixType.PREFIX
                    ? choice : resolveCached(player, current, AffixType.PREFIX);
                refreshVisuals(player, prefix);
                player.sendMessage(messages.component("titles.changed", Map.of(
                    "identity", titles.playerIdentityLegacy(player)
                )));
            }));
    }

    private void load(Player player, long delay) {
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;
            CompletableFuture.supplyAsync(
                () -> loadState(player.getUniqueId()), blockingExecutor
            ).thenCompose(state -> importLegacyTitle(player.getUniqueId(), state))
                .thenCompose(state -> resolve(player.getUniqueId(), state))
                .whenComplete((loaded, failure) -> main(() -> {
                    if (!player.isOnline()) return;
                    if (failure != null) {
                        logger.warn("Unable to load titles for " + player.getUniqueId(), unwrap(failure));
                        return;
                    }
                    states.put(player.getUniqueId(), loaded.state());
                    refreshVisuals(player, loaded.prefix());
                }));
        }, delay);
    }

    private CompletableFuture<TitleState> importLegacyTitle(UUID uuid, TitleState state) {
        if (state.imported()) {
            return CompletableFuture.completedFuture(state);
        }
        return user(uuid).thenApplyAsync(user -> {
            String groupPrefix = titles.prefixLegacy(user.getPrimaryGroup()).strip();
            PrefixNode direct = user.data().toCollection().stream()
                .filter(NodeType.PREFIX::matches)
                .map(NodeType.PREFIX::cast)
                .filter(node -> node.getPriority() != MANAGED_PREFIX_PRIORITY)
                .filter(node -> !node.getMetaValue().strip().equals(groupPrefix))
                .max(Comparator.comparingInt(PrefixNode::getPriority))
                .orElse(null);
            String legacyMeta = user.data().toCollection().stream()
                .filter(NodeType.META::matches)
                .map(NodeType.META::cast)
                .filter(node -> node.getMetaKey().equalsIgnoreCase("cct-title"))
                .map(net.luckperms.api.node.types.MetaNode::getMetaValue)
                .filter(value -> !value.isBlank())
                .findFirst().orElse(null);
            String legacyPrefix = legacyMeta != null ? legacyMeta
                : direct == null ? null : direct.getMetaValue();
            TitleState imported = state;
            if (state.selection(AffixType.PREFIX) == null
                && state.personal(AffixType.PREFIX).isEmpty()
                && legacyPrefix != null && !plain(legacyPrefix).isBlank()) {
                UUID id = UUID.randomUUID();
                insertTitle(id, uuid, AffixType.PREFIX, legacyPrefix.stripTrailing(),
                    weight(user.getPrimaryGroup()), null, "legacy-import");
                String key = "personal:" + id;
                saveSelection(uuid, AffixType.PREFIX, key);
                imported = imported.withImportedTitle(new PersonalTitle(
                    id, AffixType.PREFIX, legacyPrefix.stripTrailing(),
                    weight(user.getPrimaryGroup()), null
                ), key);
            }
            net.luckperms.api.node.types.SuffixNode directSuffix = user.data().toCollection().stream()
                .filter(NodeType.SUFFIX::matches).map(NodeType.SUFFIX::cast)
                .filter(node -> node.getPriority() != MANAGED_PREFIX_PRIORITY)
                .max(Comparator.comparingInt(net.luckperms.api.node.types.SuffixNode::getPriority))
                .orElse(null);
            if (state.selection(AffixType.SUFFIX) == null
                && state.personal(AffixType.SUFFIX).isEmpty()
                && directSuffix != null && !plain(directSuffix.getMetaValue()).isBlank()) {
                UUID id = UUID.randomUUID();
                insertTitle(id, uuid, AffixType.SUFFIX, directSuffix.getMetaValue().stripTrailing(),
                    weight(user.getPrimaryGroup()), null, "legacy-import");
                String key = "personal:" + id;
                saveSelection(uuid, AffixType.SUFFIX, key);
                imported = imported.withImportedTitle(new PersonalTitle(
                    id, AffixType.SUFFIX, directSuffix.getMetaValue().stripTrailing(),
                    weight(user.getPrimaryGroup()), null
                ), key);
            }
            return imported.markImported();
        }, blockingExecutor);
    }

    private CompletableFuture<ResolvedTitle> resolve(UUID uuid, TitleState state) {
        return user(uuid).thenCompose(user -> {
            TitleChoice prefix = resolveChoice(user, state, AffixType.PREFIX);
            TitleChoice suffix = resolveChoice(user, state, AffixType.SUFFIX);
            return applyLuckPerms(uuid, AffixType.PREFIX, prefix.managed() ? prefix.value() : null)
                .thenCompose(ignored -> applyLuckPerms(
                    uuid, AffixType.SUFFIX,
                    suffix.managed() ? (suffix.value().isBlank() ? "&r" : suffix.value()) : null
                )).thenApply(ignored -> new ResolvedTitle(state, prefix, suffix));
        });
    }

    private TitleChoice resolveChoice(User user, TitleState state, AffixType type) {
        String key = state.selection(type);
        if (key != null && key.startsWith("personal:")) {
            String id = key.substring("personal:".length());
            for (PersonalTitle title : state.personal(type)) {
                if (title.id().toString().equalsIgnoreCase(id) && !title.expired()) {
                    return personalChoice(title);
                }
            }
            deleteSelection(user.getUniqueId(), type);
            key = null;
        }
        if (type == AffixType.PREFIX && key != null && key.startsWith("builtin:")) {
            String group = normalizeKey(key.substring("builtin:".length()));
            if (BUILT_INS.contains(group) && weight(group) <= weight(user.getPrimaryGroup())) {
                return builtInChoice(group, true);
            }
            deleteSelection(user.getUniqueId(), type);
        }
        if (type == AffixType.SUFFIX && "builtin:none".equals(key)) {
            return emptySuffix(true);
        }
        return type == AffixType.PREFIX
            ? builtInChoice(normalizeGroup(user.getPrimaryGroup()), false)
            : emptySuffix(false);
    }

    private List<TitleChoice> choices(Player player, TitleState state, AffixType type) {
        int ceiling = weight(titles.primaryGroup(player));
        List<TitleChoice> result = new ArrayList<>();
        if (type == AffixType.PREFIX) {
            for (String group : BUILT_INS) {
                if (weight(group) <= ceiling) result.add(builtInChoice(group, true));
            }
        } else {
            result.add(emptySuffix(true));
        }
        state.personal(type).stream().filter(title -> !title.expired())
            .map(PaperTitleService::personalChoice).forEach(result::add);
        return List.copyOf(result);
    }

    private TitleChoice builtInChoice(String group, boolean managed) {
        String prefix = group.equals("default") ? "&7" : titles.prefixLegacy(group).stripTrailing();
        if (prefix.isBlank()) prefix = fallbackPrefix(group);
        return new TitleChoice(AffixType.PREFIX, "builtin:" + group, prefix, displayName(group),
            weight(group), false, null, managed);
    }

    private static TitleChoice emptySuffix(boolean managed) {
        return new TitleChoice(AffixType.SUFFIX, "builtin:none", "", "&7无后缀",
            0, false, null, managed);
    }

    private static TitleChoice personalChoice(PersonalTitle title) {
        return new TitleChoice(title.type(), "personal:" + title.id(), title.display(), title.display(),
            title.sortWeight(), true, title.expiresAt(), true);
    }

    private void refreshVisuals(Player player, TitleChoice prefix) {
        if (nicknames != null && nicknames.anonymous(player)) return;
        Component identity = titles.playerIdentity(player);
        player.displayName(identity);
        player.playerListName(identity);
        tab.apply(player, player.getName(), titles.prefixLegacy(player), titles.suffixLegacy(player));
        tab.forceSort(player,
            Math.min(prefix.sortWeight(), weight(titles.primaryGroup(player))));
    }

    void refreshPlayer(Player player) {
        refreshVisuals(player, resolveCached(player, states.get(player.getUniqueId()), AffixType.PREFIX));
    }

    private TitleChoice resolveCached(Player player, TitleState state, AffixType type) {
        if (state != null) {
            String key = state.selection(type);
            if (key != null && key.startsWith("personal:")) {
                for (PersonalTitle title : state.personal(type)) {
                    if (!title.expired() && key.equalsIgnoreCase("personal:" + title.id())) {
                        return personalChoice(title);
                    }
                }
            }
            if (type == AffixType.PREFIX && key != null && key.startsWith("builtin:")) {
                String group = normalizeKey(key.substring("builtin:".length()));
                if (BUILT_INS.contains(group) && weight(group) <= weight(titles.primaryGroup(player))) {
                    return builtInChoice(group, true);
                }
            }
        }
        return type == AffixType.PREFIX
            ? builtInChoice(normalizeGroup(titles.primaryGroup(player)), false) : emptySuffix(false);
    }

    private CompletableFuture<Void> applyLuckPerms(UUID uuid, AffixType type, String value) {
        return user(uuid).thenCompose(user -> {
            List<Node> managed = user.data().toCollection().stream()
                .filter(node -> type == AffixType.PREFIX
                    ? NodeType.PREFIX.matches(node) : NodeType.SUFFIX.matches(node))
                .filter(node -> type == AffixType.PREFIX
                    ? NodeType.PREFIX.cast(node).getPriority() == MANAGED_PREFIX_PRIORITY
                    : NodeType.SUFFIX.cast(node).getPriority() == MANAGED_PREFIX_PRIORITY)
                .toList();
            String normalized = value == null ? null
                : type == AffixType.PREFIX ? value.stripTrailing() : value.stripLeading();
            if (managed.isEmpty() && (normalized == null || normalized.isBlank())) {
                return CompletableFuture.completedFuture(null);
            }
            if (managed.size() == 1 && normalized != null
                && normalized.equals(type == AffixType.PREFIX
                    ? NodeType.PREFIX.cast(managed.getFirst()).getMetaValue()
                    : NodeType.SUFFIX.cast(managed.getFirst()).getMetaValue())) {
                return CompletableFuture.completedFuture(null);
            }
            managed.forEach(user.data()::remove);
            if (normalized != null && !normalized.isBlank()) {
                Node node = type == AffixType.PREFIX
                    ? PrefixNode.builder(normalized, MANAGED_PREFIX_PRIORITY).build()
                    : net.luckperms.api.node.types.SuffixNode.builder(
                        normalized, MANAGED_PREFIX_PRIORITY
                    ).build();
                user.data().add(node);
            }
            return luckPerms.getUserManager().saveUser(user);
        });
    }

    private CompletableFuture<User> user(UUID uuid) {
        if (luckPerms == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("LuckPerms unavailable"));
        }
        User loaded = luckPerms.getUserManager().getUser(uuid);
        return loaded == null ? luckPerms.getUserManager().loadUser(uuid) : CompletableFuture.completedFuture(loaded);
    }

    private TitleState loadState(UUID uuid) {
        DatabaseAccess database = database();
        if (database == null) throw new IllegalStateException("CCTSystem database unavailable");
        Instant now = Instant.now();
        List<PersonalTitle> personal = new ArrayList<>();
        Map<AffixType, String> selections = new java.util.EnumMap<>(AffixType.class);
        try (Connection connection = database.connection()) {
            try (PreparedStatement cleanup = connection.prepareStatement(
                "DELETE FROM cct_player_titles WHERE player_uuid = ? AND expires_at IS NOT NULL AND expires_at <= ?")) {
                cleanup.setBytes(1, uuidBytes(uuid));
                cleanup.setTimestamp(2, Timestamp.from(now));
                cleanup.executeUpdate();
            }
            try (PreparedStatement query = connection.prepareStatement(
                "SELECT title_id, affix_type, display_value, sort_weight, expires_at FROM cct_player_titles "
                    + "WHERE player_uuid = ? ORDER BY created_at")) {
                query.setBytes(1, uuidBytes(uuid));
                try (ResultSet result = query.executeQuery()) {
                    while (result.next()) personal.add(new PersonalTitle(
                        bytesUuid(result.getBytes(1)), AffixType.valueOf(result.getString(2)),
                        result.getString(3), result.getInt(4),
                        result.getTimestamp(5) == null ? null : result.getTimestamp(5).toInstant()
                    ));
                }
            }
            try (PreparedStatement query = connection.prepareStatement(
                "SELECT affix_type, selection_key FROM cct_player_title_selection WHERE player_uuid = ?")) {
                query.setBytes(1, uuidBytes(uuid));
                try (ResultSet result = query.executeQuery()) {
                    while (result.next()) selections.put(
                        AffixType.valueOf(result.getString(1)), result.getString(2)
                    );
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to load player titles", exception);
        }
        return new TitleState(Map.copyOf(selections), List.copyOf(personal), false);
    }

    private void insertTitle(
        UUID id, UUID owner, AffixType type, String display,
        int sortWeight, Instant expiry, String actor
    ) {
        try (Connection connection = requireDatabase().connection();
             PreparedStatement statement = connection.prepareStatement(
                 "INSERT INTO cct_player_titles(title_id, player_uuid, affix_type, display_value, sort_weight, expires_at, granted_by) "
                     + "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            statement.setBytes(1, uuidBytes(id));
            statement.setBytes(2, uuidBytes(owner));
            statement.setString(3, type.name());
            statement.setString(4, display);
            statement.setInt(5, sortWeight);
            statement.setTimestamp(6, expiry == null ? null : Timestamp.from(expiry));
            statement.setString(7, actor);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to grant player title", exception);
        }
    }

    private boolean removeTitle(UUID owner, AffixType type, String query) {
        String normalized = query == null ? "" : query.strip();
        try (Connection connection = requireDatabase().connection()) {
            connection.setAutoCommit(false);
            UUID removedId = null;
            int changed;
            if (normalized.matches("(?i)(personal:)?[0-9a-f-]{36}")) {
                UUID id = UUID.fromString(normalized.replaceFirst("(?i)^personal:", ""));
                removedId = id;
                try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM cct_player_titles WHERE player_uuid = ? AND affix_type = ? AND title_id = ?")) {
                    delete.setBytes(1, uuidBytes(owner));
                    delete.setString(2, type.name());
                    delete.setBytes(3, uuidBytes(id));
                    changed = delete.executeUpdate();
                }
            } else {
                try (PreparedStatement find = connection.prepareStatement(
                    "SELECT title_id FROM cct_player_titles WHERE player_uuid = ? AND affix_type = ? AND display_value = ? LIMIT 1")) {
                    find.setBytes(1, uuidBytes(owner));
                    find.setString(2, type.name());
                    find.setString(3, normalized);
                    try (ResultSet result = find.executeQuery()) {
                        if (result.next()) removedId = bytesUuid(result.getBytes(1));
                    }
                }
                if (removedId == null) {
                    changed = 0;
                } else {
                    try (PreparedStatement delete = connection.prepareStatement(
                        "DELETE FROM cct_player_titles WHERE player_uuid = ? AND affix_type = ? AND title_id = ?")) {
                        delete.setBytes(1, uuidBytes(owner));
                        delete.setString(2, type.name());
                        delete.setBytes(3, uuidBytes(removedId));
                        changed = delete.executeUpdate();
                    }
                }
            }
            if (removedId != null) {
                try (PreparedStatement clear = connection.prepareStatement(
                    "DELETE FROM cct_player_title_selection WHERE player_uuid = ? AND affix_type = ? AND selection_key = ?")) {
                    clear.setBytes(1, uuidBytes(owner));
                    clear.setString(2, type.name());
                    clear.setString(3, "personal:" + removedId);
                    clear.executeUpdate();
                }
            }
            connection.commit();
            return changed > 0;
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to remove player title", exception);
        }
    }

    private void saveSelection(UUID uuid, AffixType type, String key) {
        try (Connection connection = requireDatabase().connection();
             PreparedStatement statement = connection.prepareStatement(
                 "INSERT INTO cct_player_title_selection(player_uuid, affix_type, selection_key) VALUES (?, ?, ?) "
                     + "ON DUPLICATE KEY UPDATE selection_key = VALUES(selection_key)")) {
            statement.setBytes(1, uuidBytes(uuid));
            statement.setString(2, type.name());
            statement.setString(3, key);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to save title selection", exception);
        }
    }

    private void deleteSelection(UUID uuid, AffixType type) {
        try (Connection connection = requireDatabase().connection();
             PreparedStatement statement = connection.prepareStatement(
                 "DELETE FROM cct_player_title_selection WHERE player_uuid = ? AND affix_type = ?")) {
            statement.setBytes(1, uuidBytes(uuid));
            statement.setString(2, type.name());
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to clear title selection", exception);
        }
    }

    private DatabaseAccess database() {
        return providers.find(DatabaseProvider.KEY).orElse(null);
    }

    private DatabaseAccess requireDatabase() {
        DatabaseAccess database = database();
        if (database == null) throw new IllegalStateException("CCTSystem database unavailable");
        return database;
    }

    private boolean isStaff(Player player) {
        return weight(titles.primaryGroup(player)) >= weight("helper");
    }

    private void onlineReload(UUID uuid) {
        main(() -> {
            Player player = plugin.getServer().getPlayer(uuid);
            if (player != null && player.isOnline()) load(player, 0L);
        });
    }

    private void main(Runnable action) {
        platformTasks.callMain(() -> {
            action.run();
            return null;
        });
    }

    private static String identity(String prefix, String name, String suffix) {
        String left = prefix == null ? "" : prefix.stripTrailing();
        String right = suffix == null ? "" : suffix.stripLeading();
        return (plain(left).isBlank() ? left : left + " ") + name
            + (plain(right).isBlank() ? "" : " " + right);
    }

    private static ItemStack simpleItem(Material material, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(name.decoration(
            net.kyori.adventure.text.format.TextDecoration.ITALIC, false
        ));
        meta.lore(lore.stream().map(line -> line.decoration(
            net.kyori.adventure.text.format.TextDecoration.ITALIC, false
        )).toList());
        item.setItemMeta(meta);
        return item;
    }

    private static String displayName(String group) {
        return switch (group) {
            case "owner" -> "&4[服主]";
            case "admin" -> "&c[管理员]";
            case "mod" -> "&6[客服]";
            case "helper" -> "&2[志愿者]";
            case "mvpp" -> "&6[MVP&e+&6]";
            case "mvp" -> "&b[MVP]";
            case "vipp" -> "&a[VIP&e+&a]";
            case "vip" -> "&a[VIP]";
            default -> "&7无称号";
        };
    }

    private static String fallbackPrefix(String group) {
        return displayName(group).replace("无称号", "");
    }

    private static Material material(String key) {
        return switch (key) {
            case "builtin:owner" -> Material.REDSTONE_BLOCK;
            case "builtin:admin" -> Material.REDSTONE;
            case "builtin:mod" -> Material.EMERALD_BLOCK;
            case "builtin:helper" -> Material.DIAMOND_BLOCK;
            case "builtin:mvpp" -> Material.EMERALD;
            case "builtin:mvp" -> Material.DIAMOND;
            case "builtin:vipp" -> Material.GOLD_INGOT;
            case "builtin:vip" -> Material.IRON_INGOT;
            default -> Material.GRAY_DYE;
        };
    }

    static int weight(String group) {
        return WEIGHTS.getOrDefault(normalizeGroup(group), 0);
    }

    private static String normalizeGroup(String group) {
        String value = normalizeKey(group);
        return WEIGHTS.containsKey(value) ? value : "default";
    }

    private static String normalizeKey(String value) {
        if (value == null) return "default";
        return value.toLowerCase(Locale.ROOT).strip()
            .replace("mvp+", "mvpp").replace("vip+", "vipp")
            .replace("mvp_plus", "mvpp").replace("vip_plus", "vipp");
    }

    private static Map<String, Integer> weights() {
        Map<String, Integer> values = new LinkedHashMap<>();
        values.put("default", 0);
        values.put("vip", 1);
        values.put("vipp", 2);
        values.put("mvp", 3);
        values.put("mvpp", 4);
        values.put("helper", 5);
        values.put("mod", 6);
        values.put("admin", 7);
        values.put("owner", 8);
        return Map.copyOf(values);
    }

    private static String plain(String value) {
        return value == null ? "" : value.replaceAll("(?i)&[0-9A-FK-ORX]", "")
            .replace("[", "").replace("]", "").strip();
    }

    private static byte[] uuidBytes(UUID uuid) {
        return ByteBuffer.allocate(16).putLong(uuid.getMostSignificantBits())
            .putLong(uuid.getLeastSignificantBits()).array();
    }

    private static UUID bytesUuid(byte[] value) {
        ByteBuffer buffer = ByteBuffer.wrap(value);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    private static Throwable unwrap(Throwable throwable) {
        return throwable instanceof java.util.concurrent.CompletionException
            && throwable.getCause() != null ? throwable.getCause() : throwable;
    }

    @Override
    public void close() {
        if (expiryTask >= 0) plugin.getServer().getScheduler().cancelTask(expiryTask);
        expiryTask = -1;
        states.clear();
    }

    enum AffixType {
        PREFIX,
        SUFFIX;

        static AffixType from(String value) {
            if (value == null) return null;
            return switch (value.toLowerCase(Locale.ROOT)) {
                case "prefix", "前缀" -> PREFIX;
                case "suffix", "后缀" -> SUFFIX;
                default -> null;
            };
        }
    }

    record PersonalTitle(UUID id, AffixType type, String display, int sortWeight, Instant expiresAt) {
        boolean expired() { return expiresAt != null && !expiresAt.isAfter(Instant.now()); }
    }

    private record TitleState(
        Map<AffixType, String> selections,
        List<PersonalTitle> personalTitles,
        boolean imported
    ) {
        String selection(AffixType type) { return selections.get(type); }
        List<PersonalTitle> personal(AffixType type) {
            return personalTitles.stream().filter(title -> title.type() == type).toList();
        }
        TitleState withSelection(AffixType type, String value) {
            Map<AffixType, String> updated = new java.util.EnumMap<>(AffixType.class);
            updated.putAll(selections);
            updated.put(type, value);
            return new TitleState(Map.copyOf(updated), personalTitles, true);
        }
        TitleState withImportedTitle(PersonalTitle title, String selection) {
            List<PersonalTitle> updatedTitles = new ArrayList<>(personalTitles);
            updatedTitles.add(title);
            Map<AffixType, String> updatedSelections = new java.util.EnumMap<>(AffixType.class);
            updatedSelections.putAll(selections);
            updatedSelections.put(title.type(), selection);
            return new TitleState(Map.copyOf(updatedSelections), List.copyOf(updatedTitles), true);
        }
        TitleState markImported() { return new TitleState(selections, personalTitles, true); }
    }

    private record TitleChoice(
        AffixType type, String key, String value, String displayName, int sortWeight,
        boolean personal, Instant expiresAt, boolean managed
    ) { }

    private record ResolvedTitle(TitleState state, TitleChoice prefix, TitleChoice suffix) { }

    private static final class TitleHolder implements InventoryHolder {
        private final List<TitleChoice> choices;
        private final String selected;
        private final AffixType type;
        private final Map<Integer, TitleChoice> bySlot = new ConcurrentHashMap<>();
        private Inventory inventory;

        private TitleHolder(List<TitleChoice> choices, String selected, AffixType type) {
            this.choices = choices;
            this.selected = selected;
            this.type = type;
        }

        @Override
        public Inventory getInventory() { return inventory; }
    }

    private static final class AffixHubHolder implements InventoryHolder {
        private Inventory inventory;

        @Override
        public Inventory getInventory() { return inventory; }
    }
}
