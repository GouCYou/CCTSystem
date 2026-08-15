package cn.cctstudio.cctsystem.platform.paper;

import cn.cctstudio.cctsystem.core.concurrent.PlatformTaskExecutor;
import cn.cctstudio.cctsystem.core.config.CctConfig;
import cn.cctstudio.cctsystem.core.config.ExchangeSourceConfig;
import cn.cctstudio.cctsystem.core.logging.CctLogger;
import cn.cctstudio.cctsystem.core.provider.ProviderRegistry;
import cn.cctstudio.cctsystem.exchange.ExchangeOrigin;
import cn.cctstudio.cctsystem.exchange.ExchangeQuote;
import cn.cctstudio.cctsystem.exchange.ExchangeRequest;
import cn.cctstudio.cctsystem.exchange.ExchangeResult;
import cn.cctstudio.cctsystem.exchange.ExchangeService;
import cn.cctstudio.cctsystem.exchange.ExchangeServiceProvider;
import cn.cctstudio.cctsystem.exchange.ExchangeStatus;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;

final class PaperExchangeMenu implements Listener, CommandExecutor, TabCompleter {
    private static final DateTimeFormatter RESET_TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm");
    private static final NumberFormat INTEGER = NumberFormat.getIntegerInstance(Locale.CHINA);

    private final JavaPlugin plugin;
    private final ProviderRegistry providers;
    private final PlatformTaskExecutor platformTasks;
    private final CctLogger logger;
    private final ZoneId timezone;
    private final String sourceId;
    private final java.util.Set<UUID> inFlight = ConcurrentHashMap.newKeySet();
    private final PaperMessages messages;
    private final PaperMenus menus;
    private final PaperLuckPermsTitles titles;
    private final Map<UUID, CompletableFuture<ExchangeQuote>> loading =
        new ConcurrentHashMap<>();
    private final Map<UUID, ExchangeQuote> quoteCache = new ConcurrentHashMap<>();

    PaperExchangeMenu(
        JavaPlugin plugin,
        ProviderRegistry providers,
        CctConfig config,
        PlatformTaskExecutor platformTasks,
        CctLogger logger,
        PaperMessages messages,
        PaperMenus menus,
        PaperLuckPermsTitles titles
    ) {
        this.plugin = plugin;
        this.providers = providers;
        this.platformTasks = platformTasks;
        this.logger = logger;
        this.messages = messages;
        this.menus = menus;
        this.titles = titles;
        this.timezone = ZoneId.of(config.exchange().timezone());
        this.sourceId = config.exchange().sources().stream()
            .filter(ExchangeSourceConfig::enabled)
            .filter(source -> source.serverId().equals(config.serverId()))
            .map(ExchangeSourceConfig::sourceId)
            .sorted()
            .findFirst()
            .orElse(null);
    }

    @Override
    public boolean onCommand(
        CommandSender sender,
        Command command,
        String label,
        String[] arguments
    ) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messages.component("commands.only-player"));
            return true;
        }
        if (arguments.length == 0 || !arguments[0].equalsIgnoreCase("exchange")) {
            player.sendMessage(messages.component("menus.exchange.usage"));
            return true;
        }
        if (arguments.length == 1) {
            open(player);
            return true;
        }
        if (arguments.length == 2) {
            executeCommand(player, arguments[1]);
            return true;
        }
        player.sendMessage(messages.component("menus.exchange.usage"));
        return true;
    }

    @Override
    public List<String> onTabComplete(
        CommandSender sender,
        Command command,
        String alias,
        String[] arguments
    ) {
        if (arguments.length == 1) {
            return List.of("exchange");
        }
        if (arguments.length == 2 && arguments[0].equalsIgnoreCase("exchange")) {
            return List.of("1", "10", "100", "max");
        }
        return List.of();
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder(false) instanceof ExchangeHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)
            || event.getClickedInventory() == null
            || event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }
        int slot = event.getRawSlot();
        String action = holder.layout.actionAt(slot);
        if ("CLOSE".equals(action)) {
            player.closeInventory();
            return;
        }
        if (!"EXCHANGE".equals(action)) return;
        int amount;
        try {
            amount = Integer.parseInt(holder.layout.valueAt(slot));
        } catch (NumberFormatException exception) {
            return;
        }
        if (holder.quote() == null) {
            player.sendActionBar(messages.component("menus.exchange.data-loading"));
            return;
        }
        if (amount > holder.quote().maximumExchangeablePoints()) {
            player.sendActionBar(messages.component(unavailableReasonPath(holder.quote(), amount)));
            return;
        }
        execute(player, amount, ExchangeOrigin.MENU);
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder(false) instanceof ExchangeHolder) {
            event.setCancelled(true);
        }
    }

    void open(Player player) {
        ExchangeService service = service(player);
        if (service == null) {
            return;
        }
        UUID playerUuid = player.getUniqueId();
        PaperMenus.MenuDefinition layout = menus.get("exchange");
        ExchangeHolder holder = new ExchangeHolder(quoteCache.get(playerUuid), layout);
        holder.inventory = plugin.getServer().createInventory(
            holder, layout.size(), messages.component(layout.titleKey())
        );
        renderInventory(player, holder);
        player.openInventory(holder.inventory);
        CompletableFuture<ExchangeQuote> load = loading.computeIfAbsent(
            playerUuid,
            ignored -> service.quote(playerUuid, sourceId).toCompletableFuture()
        );
        load.whenComplete((quote, throwable) -> platformTasks
            .callMain(() -> {
                loading.remove(playerUuid, load);
                if (!plugin.isEnabled() || !player.isOnline()) {
                    return null;
                }
                if (throwable != null) {
                    logger.warn("Unable to prepare exchange menu", throwable);
                    if (player.getOpenInventory().getTopInventory() == holder.inventory) {
                        player.sendActionBar(messages.component("menus.exchange.load-failed"));
                    }
                    return null;
                }
                holder.quote = quote;
                quoteCache.put(playerUuid, quote);
                if (player.getOpenInventory().getTopInventory() == holder.inventory) {
                    renderInventory(player, holder);
                }
                return null;
            }));
    }

    private void renderInventory(Player player, ExchangeHolder holder) {
        Inventory inventory = holder.inventory;
        holder.layout.fill(inventory);
        PaperMenus.MenuItemDefinition profile = holder.layout.item("profile");
        inventory.setItem(profile.slot(), summary(player, holder.quote));
        for (PaperMenus.MenuItemDefinition definition : holder.layout.items().values()) {
            if (!"EXCHANGE".equals(definition.action())) continue;
            int points;
            try {
                points = Integer.parseInt(definition.value());
            } catch (NumberFormatException exception) {
                continue;
            }
            inventory.setItem(definition.slot(), holder.quote == null
                ? loadingButton(points, definition.material(Material.GRAY_DYE))
                : exchangeButton(holder.quote, points, definition.material(Material.EMERALD)));
        }
        PaperMenus.MenuItemDefinition close = holder.layout.item("close");
        inventory.setItem(close.slot(), item(
            close.material(Material.BARRIER), messages.component("menus.personal.close-name"), List.of()
        ));
    }

    private ItemStack summary(Player player, ExchangeQuote quote) {
        List<Component> lore = quote == null
            ? List.of(messages.component("menus.exchange.data-loading"))
            : List.of(
                messages.component("menus.exchange.points", Map.of("value", format(quote.pointsBalance()))),
                messages.component("menus.exchange.currency", Map.of(
                    "currency", quote.currencyDisplayName(), "value", money(quote.currencyBalance())
                )),
                messages.component("menus.exchange.weekly", Map.of(
                    "used", format(quote.weeklyUsedPoints()), "limit", format(quote.weeklyLimitPoints())
                )),
                messages.component("menus.exchange.remaining", Map.of("value", format(quote.weeklyRemainingPoints()))),
                messages.component("menus.exchange.reset", Map.of(
                    "value", RESET_TIME.format(quote.resetsAt().atZone(timezone))
                ))
            );
        ItemStack head = item(
            Material.PLAYER_HEAD,
            titles.playerIdentity(player),
            lore
        );
        if (head.getItemMeta() instanceof SkullMeta skull) {
            skull.setPlayerProfile(player.getPlayerProfile());
            head.setItemMeta(skull);
        }
        return head;
    }

    private ItemStack loadingButton(int points, Material material) {
        return item(
            material,
            messages.component("menus.exchange.button-disabled", Map.of("points", points)),
            List.of(messages.component("menus.exchange.data-loading"))
        );
    }

    private ItemStack exchangeButton(ExchangeQuote quote, int points, Material availableMaterial) {
        boolean available = points <= quote.maximumExchangeablePoints();
        BigDecimal cost = quote.costFor(points);
        BigDecimal after = quote.currencyBalance().subtract(cost).max(BigDecimal.ZERO);
        List<Component> lore = new ArrayList<>();
        lore.add(messages.component("menus.exchange.need", Map.of(
            "cost", money(cost), "currency", quote.currencyDisplayName()
        )));
        lore.add(messages.component("menus.exchange.after", Map.of("value", money(after))));
        lore.add(messages.component("menus.exchange.weekly-after", Map.of(
            "value", format(Math.max(0, quote.weeklyRemainingPoints() - points))
        )));
        lore.add(Component.empty());
        lore.add(messages.component(available
            ? "menus.exchange.click" : unavailableReasonPath(quote, points)));
        return item(
            available ? availableMaterial : Material.GRAY_DYE,
            messages.component(available
                ? "menus.exchange.button" : "menus.exchange.button-disabled", Map.of("points", points)),
            lore
        );
    }

    void executeCommand(Player player, String rawAmount) {
        ExchangeService service = service(player);
        if (service == null) {
            return;
        }
        service.quote(player.getUniqueId(), sourceId).whenComplete((quote, throwable) -> platformTasks
            .callMain(() -> {
                if (!plugin.isEnabled() || !player.isOnline()) {
                    return null;
                }
                if (throwable != null) {
                    player.sendMessage(messages.component("menus.exchange.unavailable"));
                    return null;
                }
                int amount;
                if (rawAmount.equalsIgnoreCase("max")) {
                    amount = quote.maximumExchangeablePoints();
                } else {
                    try {
                        amount = Integer.parseInt(rawAmount);
                    } catch (NumberFormatException exception) {
                        amount = 0;
                    }
                }
                if ((amount != 1 && amount != 10 && amount != 100)
                    && !rawAmount.equalsIgnoreCase("max")) {
                    player.sendMessage(messages.component("menus.exchange.invalid-amount"));
                    return null;
                }
                if (amount < 1 || amount > quote.maximumExchangeablePoints()) {
                    player.sendMessage(messages.component(unavailableReasonPath(quote, Math.max(1, amount))));
                    return null;
                }
                execute(player, amount, ExchangeOrigin.COMMAND);
                return null;
            }));
    }

    private void execute(Player player, int amount, ExchangeOrigin origin) {
        ExchangeService service = service(player);
        if (service == null || !inFlight.add(player.getUniqueId())) {
            player.sendActionBar(messages.component("menus.exchange.operation-processing"));
            return;
        }
        player.sendActionBar(messages.component("menus.exchange.processing"));
        service.execute(new ExchangeRequest(
            player.getUniqueId(),
            sourceId,
            amount,
            origin,
            UUID.randomUUID().toString()
        )).whenComplete((result, throwable) -> platformTasks.callMain(() -> {
            inFlight.remove(player.getUniqueId());
            if (!player.isOnline()) {
                return null;
            }
            if (throwable != null) {
                logger.warn("Player exchange request failed", throwable);
                player.sendMessage(messages.component("menus.exchange.unavailable"));
                return null;
            }
            player.sendMessage(resultMessage(result));
            if (player.getOpenInventory().getTopInventory().getHolder(false) instanceof ExchangeHolder) {
                open(player);
            }
            return null;
        }));
    }

    private ExchangeService service(Player player) {
        if (sourceId == null) {
            player.sendMessage(messages.component("menus.exchange.unavailable-server"));
            return null;
        }
        return providers.find(ExchangeServiceProvider.KEY).orElseGet(() -> {
            player.sendMessage(messages.component("menus.exchange.unavailable"));
            return null;
        });
    }

    private Component resultMessage(ExchangeResult result) {
        if (result.status() == ExchangeStatus.COMPLETED) {
            return messages.component("menus.exchange.completed", Map.of("points", result.requestedPoints()));
        }
        if (result.status() == ExchangeStatus.REVIEW_REQUIRED
            || result.status() == ExchangeStatus.COMPENSATION_PENDING) {
            return messages.component("menus.exchange.review-required");
        }
        return messages.component(switch (result.errorCode() == null ? "" : result.errorCode()) {
            case "INSUFFICIENT_CURRENCY" -> "menus.exchange.insufficient-currency";
            case "WEEKLY_LIMIT_EXCEEDED" -> "menus.exchange.insufficient-limit";
            case "OPERATION_IN_PROGRESS" -> "menus.exchange.operation-processing";
            default -> "menus.exchange.incomplete";
        });
    }

    private static String unavailableReasonPath(ExchangeQuote quote, int points) {
        if (points > quote.weeklyRemainingPoints()) {
            return "menus.exchange.insufficient-limit";
        }
        return "menus.exchange.insufficient-currency";
    }

    private static ItemStack item(Material material, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(name.decoration(TextDecoration.ITALIC, false));
        meta.lore(lore.stream()
            .map(line -> line.decoration(TextDecoration.ITALIC, false)).toList());
        item.setItemMeta(meta);
        return item;
    }

    private static String money(BigDecimal value) {
        return value.setScale(2, RoundingMode.DOWN).stripTrailingZeros().toPlainString();
    }

    private static String format(int value) {
        return INTEGER.format(value);
    }

    private static final class ExchangeHolder implements InventoryHolder {
        private ExchangeQuote quote;
        private final PaperMenus.MenuDefinition layout;
        private Inventory inventory;

        private ExchangeHolder(ExchangeQuote quote, PaperMenus.MenuDefinition layout) {
            this.quote = quote;
            this.layout = layout;
        }

        ExchangeQuote quote() {
            return quote;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
