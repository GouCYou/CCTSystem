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
import java.text.NumberFormat;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
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
    private static final Component TITLE = plain("金币兑换", NamedTextColor.DARK_GRAY);
    private static final DateTimeFormatter RESET_TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm");
    private static final NumberFormat INTEGER = NumberFormat.getIntegerInstance(Locale.CHINA);
    private static final Set<Integer> EXCHANGE_SLOTS = Set.of(11, 13, 15);

    private final JavaPlugin plugin;
    private final ProviderRegistry providers;
    private final PlatformTaskExecutor platformTasks;
    private final CctLogger logger;
    private final ZoneId timezone;
    private final String sourceId;
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();

    PaperExchangeMenu(
        JavaPlugin plugin,
        ProviderRegistry providers,
        CctConfig config,
        PlatformTaskExecutor platformTasks,
        CctLogger logger
    ) {
        this.plugin = plugin;
        this.providers = providers;
        this.platformTasks = platformTasks;
        this.logger = logger;
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
            sender.sendMessage("该命令只能由玩家使用");
            return true;
        }
        if (arguments.length == 0 || !arguments[0].equalsIgnoreCase("exchange")) {
            player.sendMessage(plain("使用 /cct exchange", NamedTextColor.GRAY));
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
        player.sendMessage(plain("使用 /cct exchange [1|10|100|max]", NamedTextColor.GRAY));
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
        if (slot == 22) {
            player.closeInventory();
            return;
        }
        if (!EXCHANGE_SLOTS.contains(slot)) {
            return;
        }
        int amount = switch (slot) {
            case 11 -> 1;
            case 13 -> 10;
            case 15 -> 100;
            default -> 0;
        };
        if (amount > holder.quote().maximumExchangeablePoints()) {
            player.sendActionBar(plain(unavailableReason(holder.quote(), amount), NamedTextColor.RED));
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
        service.quote(player.getUniqueId(), sourceId).whenComplete((quote, throwable) -> platformTasks
            .callMain(() -> {
                if (!plugin.isEnabled() || !player.isOnline()) {
                    return null;
                }
                if (throwable != null) {
                    logger.warn("Unable to prepare exchange menu", throwable);
                    player.sendMessage(plain("兑换暂不可用", NamedTextColor.RED));
                    return null;
                }
                player.openInventory(createInventory(player, quote));
                return null;
            }));
    }

    private Inventory createInventory(Player player, ExchangeQuote quote) {
        ExchangeHolder holder = new ExchangeHolder(quote);
        Inventory inventory = plugin.getServer().createInventory(holder, 27, TITLE);
        holder.inventory = inventory;
        ItemStack filler = item(Material.GRAY_STAINED_GLASS_PANE, Component.empty(), List.of());
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, filler);
        }
        inventory.setItem(4, summary(player, quote));
        inventory.setItem(11, exchangeButton(quote, 1, Material.LIME_DYE));
        inventory.setItem(13, exchangeButton(quote, 10, Material.EMERALD));
        inventory.setItem(15, exchangeButton(quote, 100, Material.EMERALD_BLOCK));
        inventory.setItem(22, item(
            Material.BARRIER,
            plain("关闭", NamedTextColor.RED),
            List.of()
        ));
        return inventory;
    }

    private ItemStack summary(Player player, ExchangeQuote quote) {
        ItemStack head = item(
            Material.PLAYER_HEAD,
            plain(player.getName(), NamedTextColor.WHITE),
            List.of(
                line("点券", format(quote.pointsBalance()), NamedTextColor.GOLD),
                line(quote.currencyDisplayName(), money(quote.currencyBalance()), NamedTextColor.YELLOW),
                line("本周", format(quote.weeklyUsedPoints()) + " / " + format(quote.weeklyLimitPoints()),
                    NamedTextColor.AQUA),
                line("剩余", format(quote.weeklyRemainingPoints()), NamedTextColor.GREEN),
                line("重置", RESET_TIME.format(quote.resetsAt().atZone(timezone)), NamedTextColor.GRAY)
            )
        );
        if (head.getItemMeta() instanceof SkullMeta skull) {
            skull.setOwningPlayer(player);
            head.setItemMeta(skull);
        }
        return head;
    }

    private ItemStack exchangeButton(ExchangeQuote quote, int points, Material availableMaterial) {
        boolean available = points <= quote.maximumExchangeablePoints();
        BigDecimal cost = quote.costFor(points);
        BigDecimal after = quote.currencyBalance().subtract(cost).max(BigDecimal.ZERO);
        List<Component> lore = new ArrayList<>();
        lore.add(line("需要", money(cost) + " " + quote.currencyDisplayName(), NamedTextColor.YELLOW));
        lore.add(line("兑换后", money(after), NamedTextColor.GRAY));
        lore.add(line("本周剩余", format(Math.max(0, quote.weeklyRemainingPoints() - points)),
            NamedTextColor.AQUA));
        lore.add(Component.empty());
        lore.add(plain(
            available ? "点击兑换" : unavailableReason(quote, points),
            available ? NamedTextColor.GREEN : NamedTextColor.RED
        ));
        return item(
            available ? availableMaterial : Material.GRAY_DYE,
            plain("兑换 " + points + " 点券", available ? NamedTextColor.GREEN : NamedTextColor.GRAY),
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
                    player.sendMessage(plain("兑换暂不可用", NamedTextColor.RED));
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
                    player.sendMessage(plain("仅支持 1、10、100 或 max", NamedTextColor.GRAY));
                    return null;
                }
                if (amount < 1 || amount > quote.maximumExchangeablePoints()) {
                    player.sendMessage(plain(unavailableReason(quote, Math.max(1, amount)), NamedTextColor.RED));
                    return null;
                }
                execute(player, amount, ExchangeOrigin.COMMAND);
                return null;
            }));
    }

    private void execute(Player player, int amount, ExchangeOrigin origin) {
        ExchangeService service = service(player);
        if (service == null || !inFlight.add(player.getUniqueId())) {
            player.sendActionBar(plain("操作处理中", NamedTextColor.YELLOW));
            return;
        }
        player.sendActionBar(plain("正在兑换…", NamedTextColor.YELLOW));
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
                player.sendMessage(plain("兑换暂不可用", NamedTextColor.RED));
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
            player.sendMessage(plain("当前服务器未开放兑换", NamedTextColor.GRAY));
            return null;
        }
        return providers.find(ExchangeServiceProvider.KEY).orElseGet(() -> {
            player.sendMessage(plain("兑换暂不可用", NamedTextColor.RED));
            return null;
        });
    }

    private static Component resultMessage(ExchangeResult result) {
        if (result.status() == ExchangeStatus.COMPLETED) {
            return plain("已兑换 " + result.requestedPoints() + " 点券", NamedTextColor.GREEN);
        }
        if (result.status() == ExchangeStatus.REVIEW_REQUIRED
            || result.status() == ExchangeStatus.COMPENSATION_PENDING) {
            return plain("交易待核对，请联系管理员", NamedTextColor.RED);
        }
        return plain(switch (result.errorCode() == null ? "" : result.errorCode()) {
            case "INSUFFICIENT_CURRENCY" -> "金币不足";
            case "WEEKLY_LIMIT_EXCEEDED" -> "本周额度不足";
            case "OPERATION_IN_PROGRESS" -> "已有操作处理中";
            default -> "兑换未完成";
        }, NamedTextColor.RED);
    }

    private static String unavailableReason(ExchangeQuote quote, int points) {
        if (points > quote.weeklyRemainingPoints()) {
            return "本周额度不足";
        }
        return "金币不足";
    }

    private static ItemStack item(Material material, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(name);
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private static Component line(String label, String value, NamedTextColor valueColor) {
        return plain(label + "  ", NamedTextColor.GRAY).append(plain(value, valueColor));
    }

    private static Component plain(String value, NamedTextColor color) {
        return Component.text(value, color).decoration(TextDecoration.ITALIC, false);
    }

    private static String money(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static String format(int value) {
        return INTEGER.format(value);
    }

    private static final class ExchangeHolder implements InventoryHolder {
        private final ExchangeQuote quote;
        private Inventory inventory;

        private ExchangeHolder(ExchangeQuote quote) {
            this.quote = quote;
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
