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
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;
import net.kyori.adventure.inventory.Book;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** CCT-owned nickname state with the written-book flow used by Hypixel. */
final class PaperNicknameService implements Listener {
    private static final Pattern VALID_NAME = Pattern.compile("^[A-Za-z0-9_]{3,16}$");
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private final JavaPlugin plugin;
    private final ProviderRegistry providers;
    private final PlatformTaskExecutor platformTasks;
    private final Executor blockingExecutor;
    private final CctLogger logger;
    private final PaperMessages messages;
    private final PaperLuckPermsTitles titles;
    private final String serverId;
    private final PaperDisguiseProvider disguiseProvider;
    private final PaperTabIntegration tabIntegration;
    private final Map<UUID, NickProfile> active = new ConcurrentHashMap<>();
    private final Map<UUID, NickProfile> pendingLogin = new ConcurrentHashMap<>();
    private final Map<UUID, String> realNames = new ConcurrentHashMap<>();
    private final Map<UUID, NickDraft> drafts = new ConcurrentHashMap<>();
    private final Map<UUID, NickStep> steps = new ConcurrentHashMap<>();
    private final Map<UUID, CompletableFuture<?>> writes = new ConcurrentHashMap<>();

    PaperNicknameService(
        JavaPlugin plugin,
        ProviderRegistry providers,
        PlatformTaskExecutor platformTasks,
        Executor blockingExecutor,
        CctLogger logger,
        PaperMessages messages,
        PaperMenus ignoredMenus,
        PaperLuckPermsTitles titles,
        String serverId
    ) {
        this.plugin = plugin;
        this.providers = providers;
        this.platformTasks = platformTasks;
        this.blockingExecutor = blockingExecutor;
        this.logger = logger;
        this.messages = messages;
        this.titles = titles;
        this.serverId = serverId;
        this.disguiseProvider = new PaperDisguiseProvider(plugin, logger);
        this.tabIntegration = new PaperTabIntegration(plugin, logger);
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            realNames.put(player.getUniqueId(), player.getName());
            preload(player, 20L);
        }
        if (showsHealth()) {
            plugin.getServer().getScheduler().runTaskTimer(plugin, this::refreshDisguiseNames, 20L, 20L);
        }
    }

    void command(Player player, String[] arguments) {
        if (!player.hasPermission("cctsystem.nick")) {
            player.sendMessage(messages.component("commands.no-permission"));
            return;
        }
        if (arguments.length == 0) {
            openIntroduction(player);
            return;
        }
        String action = arguments[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "setup" -> startSetup(player);
            case "random" -> applyRandom(player);
            case "rank" -> selectRank(player, arguments);
            case "skin" -> selectSkin(player, arguments);
            case "reroll" -> reroll(player);
            case "apply" -> applyDraft(player, drafts.computeIfAbsent(
                player.getUniqueId(), ignored -> newDraft(player)
            ));
            case "reuse" -> reuse(player);
            case "reset", "off" -> disable(player);
            default -> player.sendMessage(messages.component("nickname.usage"));
        }
    }

    void open(Player player) {
        command(player, new String[0]);
    }

    void disable(Player player) {
        if (!player.hasPermission("cctsystem.nick")) {
            player.sendMessage(messages.component("commands.no-permission"));
            return;
        }
        if (writes.containsKey(player.getUniqueId())) {
            player.sendActionBar(messages.component("nickname.processing"));
            return;
        }
        CompletableFuture<Void> write = CompletableFuture.runAsync(
            () -> persistDisabled(player.getUniqueId()), blockingExecutor
        );
        writes.put(player.getUniqueId(), write);
        write.whenComplete((ignored, failure) -> main(() -> {
            writes.remove(player.getUniqueId(), write);
            if (failure != null) {
                logger.warn("Unable to disable nickname for " + player.getUniqueId(), unwrap(failure));
                if (player.isOnline()) player.sendMessage(messages.component("nickname.save-failed"));
                return;
            }
            active.remove(player.getUniqueId());
            drafts.remove(player.getUniqueId());
            steps.remove(player.getUniqueId());
            if (player.isOnline()) {
                restoreVisuals(player);
                player.sendMessage(messages.component("nickname.disabled"));
            }
        }));
    }

    Component identity(Player player) {
        NickProfile profile = active.get(player.getUniqueId());
        return profile == null
            ? titles.playerIdentity(player)
            : titles.rankIdentity(profile.displayRank(), profile.nickname());
    }

    String identityLegacy(Player player) {
        return LEGACY.serialize(identity(player));
    }

    Optional<NickProfile> profile(Player player) {
        return Optional.ofNullable(active.get(player.getUniqueId()));
    }

    String displayName(Player player) {
        NickProfile profile = active.get(player.getUniqueId());
        return profile == null ? player.getName() : profile.nickname();
    }

    String displayRank(Player player) {
        NickProfile profile = active.get(player.getUniqueId());
        return profile == null ? titles.primaryGroup(player) : profile.displayRank();
    }

    boolean anonymous(Player player) {
        return active.containsKey(player.getUniqueId());
    }

    String publicCompletion(String value) {
        if (value == null || value.isBlank()) return value;
        for (Map.Entry<UUID, NickProfile> entry : active.entrySet()) {
            String realName = realNames.get(entry.getKey());
            if (realName != null && realName.equalsIgnoreCase(value)) {
                return entry.getValue().nickname();
            }
        }
        return value;
    }

    String commandTarget(String value) {
        if (value == null || value.isBlank()) return value;
        for (Map.Entry<UUID, NickProfile> entry : active.entrySet()) {
            if (!entry.getValue().nickname().equalsIgnoreCase(value)) continue;
            String realName = realNames.get(entry.getKey());
            return realName == null ? value : realName;
        }
        return value;
    }

    String prefixLegacy(Player player) {
        NickProfile profile = active.get(player.getUniqueId());
        return profile == null
            ? titles.prefixLegacy(player)
            : titles.prefixLegacy(profile.displayRank());
    }

    String suffixLegacy(Player player) {
        NickProfile profile = active.get(player.getUniqueId());
        return profile == null
            ? titles.suffixLegacy(player)
            : titles.suffixLegacy(profile.displayRank());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        DatabaseAccess database = database();
        if (database == null) return;
        try {
            load(database, event.getUniqueId()).ifPresent(profile ->
                pendingLogin.put(event.getUniqueId(), profile)
            );
        } catch (RuntimeException exception) {
            logger.warn("Unable to preload nickname for " + event.getUniqueId(), exception);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        realNames.put(player.getUniqueId(), player.getName());
        NickProfile profile = pendingLogin.remove(player.getUniqueId());
        if (profile == null) {
            preload(player, 10L);
            return;
        }
        active.put(player.getUniqueId(), profile);
        applyVisuals(player, profile);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        active.remove(event.getPlayer().getUniqueId());
        pendingLogin.remove(event.getPlayer().getUniqueId());
        realNames.remove(event.getPlayer().getUniqueId());
        drafts.remove(event.getPlayer().getUniqueId());
        steps.remove(event.getPlayer().getUniqueId());
    }

    private void openIntroduction(Player player) {
        Component page = messages.component("nickname.book.intro")
            .append(Component.text("\n\n"))
            .append(button(messages.raw("nickname.book.start"), "/nick setup"));
        openBook(player, page);
    }

    private void startSetup(Player player) {
        drafts.put(player.getUniqueId(), newDraft(player));
        steps.put(player.getUniqueId(), NickStep.RANK);
        openRankBook(player);
    }

    private void openRankBook(Player player) {
        Component page = messages.component("nickname.book.choose-rank").append(Component.text("\n\n"));
        for (String rank : settings().ranks()) {
            page = page.append(button(rankLabel(rank), "/nick rank " + rank)).append(Component.newline());
        }
        openBook(player, page);
    }

    private void selectRank(Player player, String[] arguments) {
        String rank = arguments.length == 2 ? requestedRank(arguments[1]) : null;
        if (rank == null || !settings().ranks().contains(rank)) {
            player.sendMessage(messages.component("nickname.invalid-rank"));
            return;
        }
        NickDraft draft = drafts.computeIfAbsent(player.getUniqueId(), ignored -> newDraft(player))
            .withRank(rank);
        drafts.put(player.getUniqueId(), draft);
        if (steps.get(player.getUniqueId()) == NickStep.RANK) {
            steps.put(player.getUniqueId(), NickStep.SKIN);
            openSkinBook(player);
        } else if (active.containsKey(player.getUniqueId())) {
            applyDraft(player, draft);
        } else {
            steps.put(player.getUniqueId(), NickStep.SKIN);
            openSkinBook(player);
        }
    }

    private void openSkinBook(Player player) {
        Component page = messages.component("nickname.book.choose-skin")
            .append(Component.text("\n\n"))
            .append(button(messages.raw("nickname.book.skin-real"), "/nick skin REAL"))
            .append(Component.newline())
            .append(button(messages.raw("nickname.book.skin-steve"), "/nick skin STEVE"))
            .append(Component.newline())
            .append(button(messages.raw("nickname.book.skin-alex"), "/nick skin ALEX"))
            .append(Component.newline())
            .append(button(messages.raw("nickname.book.skin-random"), "/nick skin RANDOM"));
        openBook(player, page);
    }

    private void selectSkin(Player player, String[] arguments) {
        if (arguments.length != 2) {
            player.sendMessage(messages.component("nickname.invalid-skin"));
            return;
        }
        String requested = arguments[1].toUpperCase(Locale.ROOT);
        String skin = switch (requested) {
            case "REAL" -> player.getName();
            case "RESET", "STEVE" -> "Steve";
            case "ALEX" -> "Alex";
            case "RANDOM" -> pick(settings().randomSkins());
            default -> null;
        };
        if (skin == null) {
            player.sendMessage(messages.component("nickname.invalid-skin"));
            return;
        }
        NickDraft draft = drafts.computeIfAbsent(player.getUniqueId(), ignored -> newDraft(player))
            .withSkin(skin);
        drafts.put(player.getUniqueId(), draft);
        if (steps.get(player.getUniqueId()) == NickStep.SKIN) {
            steps.put(player.getUniqueId(), NickStep.NAME);
            openNameBook(player, draft);
        } else if (active.containsKey(player.getUniqueId())) {
            applyDraft(player, draft);
        } else {
            steps.put(player.getUniqueId(), NickStep.NAME);
            openNameBook(player, draft);
        }
    }

    private void openNameBook(Player player, NickDraft draft) {
        Component page = messages.component("nickname.book.choose-name")
            .append(Component.text("\n\n"))
            .append(Component.text(draft.nickname(), NamedTextColor.AQUA).decorate(TextDecoration.BOLD))
            .append(Component.text("\n\n"))
            .append(button(messages.raw("nickname.book.use-name"), "/nick apply"))
            .append(Component.newline())
            .append(button(messages.raw("nickname.book.reroll"), "/nick reroll"))
            .append(Component.newline())
            .append(button(messages.raw("nickname.book.reuse"), "/nick reuse"));
        openBook(player, page);
    }

    private void reroll(Player player) {
        NickDraft draft = drafts.computeIfAbsent(player.getUniqueId(), ignored -> newDraft(player))
            .withNickname(randomNickname(settings()));
        drafts.put(player.getUniqueId(), draft);
        steps.put(player.getUniqueId(), NickStep.NAME);
        openNameBook(player, draft);
    }

    private void applyRandom(Player player) {
        NickSettings settings = settings();
        NickProfile current = active.get(player.getUniqueId());
        String rank = current == null ? settings.ranks().getFirst() : current.displayRank();
        applyDraft(player, new NickDraft(
            randomNickname(settings), rank, pick(settings.randomSkins())
        ));
    }

    private void reuse(Player player) {
        if (writes.containsKey(player.getUniqueId())) {
            player.sendActionBar(messages.component("nickname.processing"));
            return;
        }
        player.sendActionBar(messages.component("nickname.processing"));
        CompletableFuture<Optional<NickProfile>> load = CompletableFuture.supplyAsync(
            () -> loadPrevious(player.getUniqueId()), blockingExecutor
        );
        writes.put(player.getUniqueId(), load);
        load.whenComplete((previous, failure) -> main(() -> {
            writes.remove(player.getUniqueId(), load);
            if (!player.isOnline()) return;
            if (failure != null) {
                logger.warn("Unable to load previous nickname for " + player.getUniqueId(), unwrap(failure));
                player.sendMessage(messages.component("nickname.save-failed"));
            } else if (previous.isEmpty()) {
                player.sendMessage(messages.component("nickname.no-previous"));
            } else {
                NickProfile profile = previous.orElseThrow();
                NickDraft base = drafts.get(player.getUniqueId());
                applyDraft(player, new NickDraft(
                    profile.nickname(),
                    base == null ? profile.displayRank() : base.displayRank(),
                    base == null ? profile.skinName() : base.skinName()
                ));
            }
        }));
    }

    private void applyDraft(Player player, NickDraft draft) {
        if (!VALID_NAME.matcher(draft.nickname()).matches()) {
            player.sendMessage(messages.component("nickname.invalid-name"));
            return;
        }
        if (writes.containsKey(player.getUniqueId())) {
            player.sendActionBar(messages.component("nickname.processing"));
            return;
        }
        NickProfile profile = new NickProfile(
            draft.nickname(), sanitizeRank(draft.displayRank()), sanitizeSkin(draft.skinName())
        );
        NickProfile current = active.get(player.getUniqueId());
        boolean nicknameChanged = current == null
            || !current.nickname().equalsIgnoreCase(profile.nickname());
        player.sendActionBar(messages.component("nickname.processing"));
        CompletableFuture<Void> write = CompletableFuture.runAsync(
            () -> persistEnabled(player.getUniqueId(), profile, nicknameChanged), blockingExecutor
        );
        writes.put(player.getUniqueId(), write);
        write.whenComplete((ignored, failure) -> main(() -> {
            writes.remove(player.getUniqueId(), write);
            if (!player.isOnline()) return;
            if (failure != null) {
                Throwable cause = unwrap(failure);
                if (cause instanceof DuplicateNicknameException) {
                    player.sendMessage(messages.component("nickname.name-in-use"));
                } else if (cause instanceof NickLimitException) {
                    player.sendMessage(messages.component("nickname.daily-limit"));
                } else {
                    logger.warn("Unable to enable nickname for " + player.getUniqueId(), cause);
                    player.sendMessage(messages.component("nickname.save-failed"));
                }
                return;
            }
            active.put(player.getUniqueId(), profile);
            drafts.remove(player.getUniqueId());
            steps.remove(player.getUniqueId());
            applyVisuals(player, profile);
            player.sendMessage(messages.component(
                "nickname.enabled", Map.of("identity", identityLegacy(player))
            ));
        }));
    }

    private void preload(Player player, long delay) {
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;
            DatabaseAccess database = database();
            if (database == null) {
                preload(player, Math.min(100L, delay + 20L));
                return;
            }
            CompletableFuture.supplyAsync(
                () -> load(database, player.getUniqueId()), blockingExecutor
            ).whenComplete((profile, failure) -> main(() -> {
                if (!player.isOnline()) return;
                if (failure != null) {
                    logger.warn("Unable to load nickname for " + player.getUniqueId(), unwrap(failure));
                    return;
                }
                if (profile.isPresent()) {
                    active.put(player.getUniqueId(), profile.orElseThrow());
                    applyVisuals(player, profile.orElseThrow());
                }
            }));
        }, delay);
    }

    private Optional<NickProfile> load(DatabaseAccess database, UUID uuid) {
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement("""
                 SELECT nickname, display_rank, skin_name FROM cct_nickname_profiles
                 WHERE player_uuid = ? AND enabled = TRUE
                 """)) {
            statement.setBytes(1, uuidBytes(uuid));
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return Optional.empty();
                return Optional.of(new NickProfile(
                    result.getString("nickname"),
                    sanitizeRank(result.getString("display_rank")),
                    sanitizeSkin(result.getString("skin_name"))
                ));
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to load nickname", exception);
        }
    }

    private Optional<NickProfile> loadPrevious(UUID uuid) {
        try (Connection connection = requireDatabase().connection();
             PreparedStatement statement = connection.prepareStatement("""
                 SELECT nickname, display_rank, skin_name FROM cct_nickname_audit
                 WHERE player_uuid = ? AND action = 'ENABLE' AND nickname IS NOT NULL
                 ORDER BY created_at DESC LIMIT 1
                 """)) {
            statement.setBytes(1, uuidBytes(uuid));
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return Optional.empty();
                return Optional.of(new NickProfile(
                    result.getString("nickname"),
                    sanitizeRank(result.getString("display_rank")),
                    sanitizeSkin(result.getString("skin_name"))
                ));
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to load previous nickname", exception);
        }
    }

    private void persistEnabled(UUID uuid, NickProfile profile, boolean nicknameChanged) {
        try (Connection connection = requireDatabase().connection()) {
            connection.setAutoCommit(false);
            try {
                if (nicknameChanged && dailyChanges(connection, uuid) >= settings().dailyLimit()) {
                    throw new NickLimitException();
                }
                if (registeredNameExists(connection, uuid, profile.nickname())) {
                    throw new DuplicateNicknameException();
                }
                int updated;
                try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE cct_nickname_profiles SET enabled = TRUE, nickname = ?,
                        display_rank = ?, skin_name = ? WHERE player_uuid = ?
                    """)) {
                    statement.setString(1, profile.nickname());
                    statement.setString(2, profile.displayRank());
                    statement.setString(3, profile.skinName());
                    statement.setBytes(4, uuidBytes(uuid));
                    updated = statement.executeUpdate();
                }
                if (updated == 0) {
                    try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO cct_nickname_profiles(
                            player_uuid, enabled, nickname, display_rank, skin_name
                        ) VALUES (?, TRUE, ?, ?, ?)
                        """)) {
                        statement.setBytes(1, uuidBytes(uuid));
                        statement.setString(2, profile.nickname());
                        statement.setString(3, profile.displayRank());
                        statement.setString(4, profile.skinName());
                        statement.executeUpdate();
                    }
                }
                audit(connection, uuid, "ENABLE", profile);
                connection.commit();
            } catch (SQLIntegrityConstraintViolationException | DuplicateNicknameException duplicate) {
                connection.rollback();
                throw new DuplicateNicknameException();
            } catch (NickLimitException limit) {
                connection.rollback();
                throw limit;
            } catch (SQLException failure) {
                connection.rollback();
                throw failure;
            }
        } catch (SQLException exception) {
            if ("23000".equals(exception.getSQLState())) throw new DuplicateNicknameException();
            throw new IllegalStateException("Unable to save nickname", exception);
        }
    }

    private int dailyChanges(Connection connection, UUID uuid) throws SQLException {
        Instant start = LocalDate.now(SHANGHAI).atStartOfDay(SHANGHAI).toInstant();
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT COUNT(*) FROM cct_nickname_audit
            WHERE player_uuid = ? AND action = 'ENABLE' AND created_at >= ?
            """)) {
            statement.setBytes(1, uuidBytes(uuid));
            statement.setTimestamp(2, Timestamp.from(start));
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    private boolean registeredNameExists(Connection connection, UUID uuid, String nickname)
        throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT 1 FROM cct_players
            WHERE normalized_name = LOWER(?) AND player_uuid <> ? LIMIT 1
            """)) {
            statement.setString(1, nickname);
            statement.setBytes(2, uuidBytes(uuid));
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private void persistDisabled(UUID uuid) {
        try (Connection connection = requireDatabase().connection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO cct_nickname_profiles(player_uuid, enabled, nickname)
                VALUES (?, FALSE, NULL)
                ON DUPLICATE KEY UPDATE enabled = FALSE, nickname = NULL,
                    display_rank = NULL, skin_name = NULL
                """)) {
                statement.setBytes(1, uuidBytes(uuid));
                statement.executeUpdate();
                audit(connection, uuid, "DISABLE", null);
                connection.commit();
            } catch (SQLException failure) {
                connection.rollback();
                throw failure;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to disable nickname", exception);
        }
    }

    private void audit(Connection connection, UUID uuid, String action, NickProfile profile)
        throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO cct_nickname_audit(
                audit_id, player_uuid, action, nickname, display_rank, skin_name, server_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            """)) {
            statement.setBytes(1, uuidBytes(UUID.randomUUID()));
            statement.setBytes(2, uuidBytes(uuid));
            statement.setString(3, action);
            statement.setString(4, profile == null ? null : profile.nickname());
            statement.setString(5, profile == null ? null : profile.displayRank());
            statement.setString(6, profile == null ? null : profile.skinName());
            statement.setString(7, serverId);
            statement.executeUpdate();
        }
    }

    private void applyVisuals(Player player, NickProfile profile) {
        Component identity = titles.rankIdentity(profile.displayRank(), profile.nickname());
        player.displayName(identity);
        player.playerListName(identity);
        disguiseProvider.apply(
            player, profile.nickname(), profile.skinName(), disguiseName(player, profile)
        );
        synchronizeTab(player, profile, 0);
    }

    private void refreshDisguiseNames() {
        for (Map.Entry<UUID, NickProfile> entry : active.entrySet()) {
            Player player = plugin.getServer().getPlayer(entry.getKey());
            if (player != null && player.isOnline()) {
                disguiseProvider.updateName(player, disguiseName(player, entry.getValue()));
            }
        }
    }

    private String disguiseName(Player player, NickProfile profile) {
        String identity = titles.rankIdentityLegacy(profile.displayRank(), profile.nickname());
        if (!showsHealth()) return identity.replace('&', '§');
        double health = Math.max(0.0D, player.getHealth());
        return (identity + " &f[&c" + String.format(Locale.ROOT, "%.1f", health) + "❤&f]")
            .replace('&', '§');
    }

    private boolean showsHealth() {
        return serverId.toLowerCase(Locale.ROOT).contains("survival");
    }

    private void restoreVisuals(Player player) {
        tabIntegration.restore(player);
        disguiseProvider.remove(player);
        player.displayName(Component.text(player.getName()));
        player.playerListName(null);
    }

    private void synchronizeTab(Player player, NickProfile profile, int attempt) {
        if (!player.isOnline() || !profile.equals(active.get(player.getUniqueId()))) return;
        boolean applied = tabIntegration.apply(
            player,
            profile.nickname(),
            titles.prefixLegacy(profile.displayRank()),
            titles.suffixLegacy(profile.displayRank())
        );
        if (!applied && attempt < 10) {
            plugin.getServer().getScheduler().runTaskLater(
                plugin, () -> synchronizeTab(player, profile, attempt + 1), 10L
            );
        }
    }

    private NickDraft newDraft(Player player) {
        NickSettings settings = settings();
        NickProfile current = active.get(player.getUniqueId());
        if (current != null) {
            return new NickDraft(current.nickname(), current.displayRank(), current.skinName());
        }
        return new NickDraft(randomNickname(settings), settings.ranks().getFirst(), player.getName());
    }

    private NickSettings settings() {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(
            new java.io.File(plugin.getDataFolder(), "menus/nickname.yml")
        );
        List<String> prefixes = validParts(yaml.getStringList("settings.name-prefixes"));
        List<String> suffixes = validParts(yaml.getStringList("settings.name-suffixes"));
        List<String> ranks = yaml.getStringList("settings.rank-options").stream()
            .map(PaperNicknameService::sanitizeRank).distinct().toList();
        List<String> skins = validParts(yaml.getStringList("settings.random-skins"));
        return new NickSettings(
            prefixes.isEmpty() ? List.of("Sky", "Nova", "Pixel", "Craft") : prefixes,
            suffixes.isEmpty() ? List.of("Fox", "Wolf", "Star", "Cloud") : suffixes,
            ranks.isEmpty() ? List.of("default", "vip", "vipp", "mvp", "mvpp") : ranks,
            skins.isEmpty() ? List.of("Steve", "Alex", "Notch", "jeb_") : skins,
            Math.max(1, yaml.getInt("settings.max-name-changes-per-day", 6))
        );
    }

    private static List<String> validParts(List<String> input) {
        List<String> result = new ArrayList<>();
        for (String value : input) {
            String part = value.replaceAll("[^A-Za-z0-9_]", "");
            if (!part.isBlank() && part.length() <= 16) result.add(part);
        }
        return List.copyOf(result);
    }

    private static String randomNickname(NickSettings settings) {
        for (int attempt = 0; attempt < 20; attempt++) {
            String value = pick(settings.prefixes()) + pick(settings.suffixes())
                + ThreadLocalRandom.current().nextInt(10, 100);
            if (value.length() <= 16) return value;
        }
        return "CCT" + ThreadLocalRandom.current().nextInt(100000, 999999);
    }

    private void openBook(Player player, Component page) {
        player.openBook(Book.book(
            messages.component("nickname.book.title"),
            Component.text("CCTSystem"),
            List.of(page)
        ));
    }

    private static Component button(String label, String command) {
        return LEGACY.deserialize(label)
            .decoration(TextDecoration.ITALIC, false)
            .clickEvent(ClickEvent.runCommand(command))
            .hoverEvent(HoverEvent.showText(Component.text(command, NamedTextColor.GRAY)));
    }

    private static String rankLabel(String rank) {
        return switch (rank) {
            case "vip" -> "&a[VIP]";
            case "vipp" -> "&a[VIP&e+&a]";
            case "mvp" -> "&b[MVP]";
            case "mvpp" -> "&6[MVP&e+&6]";
            default -> "&7[默认]";
        };
    }

    private static String pick(List<String> values) {
        return values.get(ThreadLocalRandom.current().nextInt(values.size()));
    }

    private static String sanitizeRank(String value) {
        String normalized = value == null ? "default" : value.toLowerCase(Locale.ROOT).trim()
            .replace("vip+", "vipp").replace("mvp+", "mvpp")
            .replace("vip_plus", "vipp").replace("mvp_plus", "mvpp");
        return switch (normalized) {
            case "vip", "vipp", "mvp", "mvpp" -> normalized;
            default -> "default";
        };
    }

    private static String requestedRank(String value) {
        if (value == null) return null;
        return switch (value.toLowerCase(Locale.ROOT).trim()) {
            case "default" -> "default";
            case "vip" -> "vip";
            case "vip+", "vipp", "vip_plus" -> "vipp";
            case "mvp" -> "mvp";
            case "mvp+", "mvpp", "mvp_plus" -> "mvpp";
            default -> null;
        };
    }

    private static String sanitizeSkin(String value) {
        String normalized = value == null ? "Steve" : value.replaceAll("[^A-Za-z0-9_]", "");
        return normalized.isBlank() || normalized.length() > 16 ? "Steve" : normalized;
    }

    private DatabaseAccess database() {
        return providers.find(DatabaseProvider.KEY).orElse(null);
    }

    private DatabaseAccess requireDatabase() {
        DatabaseAccess database = database();
        if (database == null) throw new IllegalStateException("CCTSystem database is unavailable");
        return database;
    }

    private void main(Runnable action) {
        platformTasks.callMain(() -> {
            action.run();
            return null;
        });
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

    record NickProfile(String nickname, String displayRank, String skinName) { }

    private record NickDraft(String nickname, String displayRank, String skinName) {
        NickDraft withNickname(String value) { return new NickDraft(value, displayRank, skinName); }
        NickDraft withRank(String value) { return new NickDraft(nickname, value, skinName); }
        NickDraft withSkin(String value) { return new NickDraft(nickname, displayRank, value); }
    }

    private record NickSettings(
        List<String> prefixes,
        List<String> suffixes,
        List<String> ranks,
        List<String> randomSkins,
        int dailyLimit
    ) { }

    private enum NickStep { RANK, SKIN, NAME }

    private static final class DuplicateNicknameException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    private static final class NickLimitException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
