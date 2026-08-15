package cn.cctstudio.cctsystem.platform.paper;

import cn.cctstudio.cctsystem.core.concurrent.PlatformTaskExecutor;
import cn.cctstudio.cctsystem.core.config.CctConfig;
import cn.cctstudio.cctsystem.core.logging.CctLogger;
import cn.cctstudio.cctsystem.core.provider.ProviderRegistry;
import cn.cctstudio.cctsystem.membership.MembershipEntitlement;
import cn.cctstudio.cctsystem.membership.MembershipException;
import cn.cctstudio.cctsystem.membership.MembershipOrderResult;
import cn.cctstudio.cctsystem.membership.MembershipOrderStatus;
import cn.cctstudio.cctsystem.membership.MembershipPurchaseRequest;
import cn.cctstudio.cctsystem.membership.MembershipQuote;
import cn.cctstudio.cctsystem.membership.MembershipService;
import cn.cctstudio.cctsystem.membership.MembershipServiceProvider;
import cn.cctstudio.cctsystem.membership.MembershipSummary;
import cn.cctstudio.cctsystem.membership.MembershipTier;
import cn.cctstudio.cctsystem.membership.UpgradeMode;
import cn.cctstudio.cctsystem.points.PointsService;
import cn.cctstudio.cctsystem.points.PointsServiceProvider;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
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

final class PaperMembershipMenu implements Listener {
    private static final Component PERSONAL_TITLE = text("个人中心", NamedTextColor.DARK_GRAY);
    private static final Component MEMBERSHIP_TITLE = text("会员中心", NamedTextColor.DARK_GRAY);
    private static final Component UPGRADE_TITLE = text("选择升级方式", NamedTextColor.DARK_GRAY);
    private static final Component CONFIRM_TITLE = text("确认购买", NamedTextColor.DARK_GRAY);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final int[] TIER_SLOTS = {19, 21, 23, 25};

    private final JavaPlugin plugin;
    private final ProviderRegistry providers;
    private final PlatformTaskExecutor platformTasks;
    private final CctLogger logger;
    private final ZoneId timezone;
    private final PaperExchangeMenu exchangeMenu;
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();

    PaperMembershipMenu(
        JavaPlugin plugin,
        ProviderRegistry providers,
        CctConfig config,
        PlatformTaskExecutor platformTasks,
        CctLogger logger,
        PaperExchangeMenu exchangeMenu
    ) {
        this.plugin = plugin;
        this.providers = providers;
        this.platformTasks = platformTasks;
        this.logger = logger;
        this.timezone = ZoneId.of(config.promotion().timezone());
        this.exchangeMenu = exchangeMenu;
    }

    void openPersonal(Player player) {
        Services services = services(player);
        if (services == null) {
            return;
        }
        CompletableFuture<MembershipSummary> summary = services.memberships().summary(player.getUniqueId())
            .toCompletableFuture();
        CompletableFuture<Integer> points = services.points().balance(player.getUniqueId())
            .toCompletableFuture();
        CompletableFuture.allOf(summary, points).whenComplete((ignored, failure) -> main(() -> {
            if (!ready(player)) {
                return;
            }
            if (failure != null) {
                fail(player, "个人信息暂不可用", failure);
                return;
            }
            player.openInventory(personalInventory(player, summary.join(), points.join()));
        }));
    }

    void openMemberships(Player player) {
        Services services = services(player);
        if (services == null) {
            return;
        }
        CompletableFuture<MembershipSummary> summary = services.memberships().summary(player.getUniqueId())
            .toCompletableFuture();
        CompletableFuture<List<MembershipTier>> catalog = services.memberships().catalog()
            .toCompletableFuture();
        CompletableFuture<Integer> points = services.points().balance(player.getUniqueId())
            .toCompletableFuture();
        CompletableFuture.allOf(summary, catalog, points).thenCompose(ignored -> {
            Map<MembershipTier, CompletableFuture<QuoteView>> quotes = new java.util.LinkedHashMap<>();
            for (MembershipTier tier : catalog.join()) {
                UpgradeMode mode = summary.join().active() != null
                    && tier.priority() > summary.join().active().tier().priority()
                    ? UpgradeMode.PAUSE
                    : UpgradeMode.NONE;
                quotes.put(tier, services.memberships().quote(
                    player.getUniqueId(), tier.key(), 1, mode
                ).handle((quote, failure) -> new QuoteView(tier, quote, unwrap(failure)))
                    .toCompletableFuture());
            }
            return CompletableFuture.allOf(quotes.values().toArray(CompletableFuture[]::new))
                .thenApply(nothing -> {
                    List<QuoteView> views = quotes.values().stream()
                        .map(CompletableFuture::join)
                        .toList();
                    return new CatalogView(summary.join(), points.join(), views);
                });
        }).whenComplete((view, failure) -> main(() -> {
            if (!ready(player)) {
                return;
            }
            if (failure != null) {
                fail(player, "会员中心暂不可用", failure);
                return;
            }
            player.openInventory(membershipInventory(player, view));
        }));
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        InventoryHolder holder = event.getInventory().getHolder(false);
        if (!(holder instanceof PersonalHolder
            || holder instanceof MembershipHolder
            || holder instanceof UpgradeHolder
            || holder instanceof ConfirmHolder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)
            || event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }
        int slot = event.getRawSlot();
        if (holder instanceof PersonalHolder) {
            if (slot == 11) {
                openMemberships(player);
            } else if (slot == 13) {
                exchangeMenu.open(player);
            } else if (slot == 22) {
                player.closeInventory();
            }
            return;
        }
        if (holder instanceof MembershipHolder memberships) {
            if (slot == 45) {
                openPersonal(player);
                return;
            }
            QuoteView view = memberships.quotes.get(slot);
            if (view == null) {
                return;
            }
            if (view.failure() != null || view.quote() == null) {
                player.sendActionBar(text(message(view.failure()), NamedTextColor.RED));
                return;
            }
            MembershipEntitlement active = memberships.summary.active();
            if (active != null && view.tier().priority() > active.tier().priority()) {
                openUpgradeChoice(player, view.tier());
            } else {
                openConfirmation(player, view.quote());
            }
            return;
        }
        if (holder instanceof UpgradeHolder upgrade) {
            if (slot == 11) {
                quoteAndConfirm(player, upgrade.tierKey, UpgradeMode.PAUSE);
            } else if (slot == 15) {
                quoteAndConfirm(player, upgrade.tierKey, UpgradeMode.CREDIT);
            } else if (slot == 22) {
                openMemberships(player);
            }
            return;
        }
        if (holder instanceof ConfirmHolder confirm) {
            if (slot == 13) {
                purchase(player, confirm);
            } else if (slot == 22) {
                openMemberships(player);
            }
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        InventoryHolder holder = event.getInventory().getHolder(false);
        if (holder instanceof PersonalHolder
            || holder instanceof MembershipHolder
            || holder instanceof UpgradeHolder
            || holder instanceof ConfirmHolder) {
            event.setCancelled(true);
        }
    }

    private Inventory personalInventory(Player player, MembershipSummary summary, int points) {
        PersonalHolder holder = new PersonalHolder();
        Inventory inventory = plugin.getServer().createInventory(holder, 27, PERSONAL_TITLE);
        holder.inventory = inventory;
        fill(inventory, Material.GRAY_STAINED_GLASS_PANE);
        List<Component> profileLore = new ArrayList<>();
        profileLore.add(line("点券", integer(points), NamedTextColor.GOLD));
        if (summary.active() == null) {
            profileLore.add(line("会员", "暂无会员", NamedTextColor.GRAY));
        } else {
            profileLore.add(line("会员", summary.active().tier().displayName(), NamedTextColor.AQUA));
            profileLore.add(line("到期", date(summary.active().expiresAt()), NamedTextColor.GRAY));
        }
        profileLore.add(line("权限", syncText(summary.permissionSyncStatus()), syncColor(summary.permissionSyncStatus())));
        inventory.setItem(4, playerHead(player, profileLore));
        inventory.setItem(11, item(
            Material.NETHER_STAR,
            text("会员中心", NamedTextColor.AQUA),
            List.of(text("查看等级与价格", NamedTextColor.GRAY))
        ));
        inventory.setItem(13, item(
            Material.EMERALD,
            text("金币兑换", NamedTextColor.GREEN),
            List.of(text("使用 /cct exchange", NamedTextColor.GRAY))
        ));
        inventory.setItem(15, item(
            Material.NAME_TAG,
            text("兑换码", NamedTextColor.GOLD),
            List.of(text("输入 /cct redeem <兑换码>", NamedTextColor.GRAY))
        ));
        inventory.setItem(22, item(Material.BARRIER, text("关闭", NamedTextColor.RED), List.of()));
        return inventory;
    }

    private Inventory membershipInventory(Player player, CatalogView view) {
        MembershipHolder holder = new MembershipHolder(view.summary());
        Inventory inventory = plugin.getServer().createInventory(holder, 54, MEMBERSHIP_TITLE);
        holder.inventory = inventory;
        fill(inventory, Material.BLACK_STAINED_GLASS_PANE);
        List<Component> account = new ArrayList<>();
        account.add(line("点券", integer(view.points()), NamedTextColor.GOLD));
        if (view.summary().active() == null) {
            account.add(line("会员", "暂无会员", NamedTextColor.GRAY));
        } else {
            account.add(line("当前", view.summary().active().tier().displayName(), NamedTextColor.AQUA));
            account.add(line("到期", date(view.summary().active().expiresAt()), NamedTextColor.GRAY));
        }
        inventory.setItem(4, playerHead(player, account));
        for (int index = 0; index < Math.min(TIER_SLOTS.length, view.quotes().size()); index++) {
            QuoteView quote = view.quotes().get(index);
            int slot = TIER_SLOTS[index];
            holder.quotes.put(slot, quote);
            inventory.setItem(slot, tierItem(quote, view.summary()));
        }
        inventory.setItem(45, item(Material.ARROW, text("返回", NamedTextColor.GRAY), List.of()));
        inventory.setItem(49, item(
            Material.CLOCK,
            text("30 天 / 月", NamedTextColor.WHITE),
            List.of(text("到期日会在确认页显示", NamedTextColor.GRAY))
        ));
        return inventory;
    }

    private ItemStack tierItem(QuoteView view, MembershipSummary summary) {
        MembershipTier tier = view.tier();
        Material material = Material.matchMaterial(tier.displayMaterial());
        if (material == null) {
            material = Material.GOLD_INGOT;
        }
        List<Component> lore = new ArrayList<>();
        for (String benefit : tier.benefits()) {
            lore.add(text("• " + benefit, NamedTextColor.GRAY));
        }
        if (!tier.benefits().isEmpty()) {
            lore.add(Component.empty());
        }
        if (view.quote() != null) {
            lore.add(price(view.quote()));
            if (view.quote().promotionEndsAt() != null) {
                lore.add(line("剩余", countdown(view.quote().promotionEndsAt()), NamedTextColor.GREEN));
                lore.add(line("结束", date(view.quote().promotionEndsAt()), NamedTextColor.DARK_GRAY));
            }
            lore.add(Component.empty());
            MembershipEntitlement active = summary.active();
            String action = active != null && active.tier().key().equals(tier.key())
                ? "点击续期 30 天"
                : "点击购买 30 天";
            lore.add(text(action, NamedTextColor.GREEN));
        } else {
            lore.add(text(message(view.failure()), NamedTextColor.RED));
            material = Material.GRAY_DYE;
        }
        return item(material, text(tier.displayName(), tierColor(tier.priority())), lore);
    }

    private void openUpgradeChoice(Player player, MembershipTier tier) {
        UpgradeHolder holder = new UpgradeHolder(tier.key());
        Inventory inventory = plugin.getServer().createInventory(holder, 27, UPGRADE_TITLE);
        holder.inventory = inventory;
        fill(inventory, Material.GRAY_STAINED_GLASS_PANE);
        inventory.setItem(11, item(
            Material.CHEST,
            text("暂停旧会员", NamedTextColor.AQUA),
            List.of(text("新会员到期后自动恢复", NamedTextColor.GRAY))
        ));
        inventory.setItem(15, item(
            Material.ANVIL,
            text("折价升级", NamedTextColor.GREEN),
            List.of(text("剩余时间抵扣本次价格", NamedTextColor.GRAY))
        ));
        inventory.setItem(22, item(Material.ARROW, text("返回", NamedTextColor.GRAY), List.of()));
        player.openInventory(inventory);
    }

    private void quoteAndConfirm(Player player, String tierKey, UpgradeMode mode) {
        Services services = services(player);
        if (services == null) {
            return;
        }
        services.memberships().quote(player.getUniqueId(), tierKey, 1, mode)
            .whenComplete((quote, failure) -> main(() -> {
                if (!ready(player)) {
                    return;
                }
                if (failure != null) {
                    fail(player, message(unwrap(failure)), failure);
                } else {
                    openConfirmation(player, quote);
                }
            }));
    }

    private void openConfirmation(Player player, MembershipQuote quote) {
        ConfirmHolder holder = new ConfirmHolder(
            quote,
            "menu-" + UUID.randomUUID()
        );
        Inventory inventory = plugin.getServer().createInventory(holder, 27, CONFIRM_TITLE);
        holder.inventory = inventory;
        fill(inventory, Material.BLACK_STAINED_GLASS_PANE);
        List<Component> lore = new ArrayList<>();
        lore.add(price(quote));
        if (quote.upgradeCreditPoints() > 0) {
            lore.add(line("折价", "-" + quote.upgradeCreditPoints(), NamedTextColor.AQUA));
        }
        lore.add(line("时长", "30 天", NamedTextColor.GRAY));
        lore.add(line("预计到期", date(Instant.now().plus(Duration.ofDays(30))), NamedTextColor.GRAY));
        lore.add(Component.empty());
        lore.add(text("点击确认", NamedTextColor.GREEN));
        inventory.setItem(13, item(Material.LIME_CONCRETE, text("确认购买", NamedTextColor.GREEN), lore));
        inventory.setItem(22, item(Material.ARROW, text("返回", NamedTextColor.GRAY), List.of()));
        player.openInventory(inventory);
    }

    private void purchase(Player player, ConfirmHolder holder) {
        if (!inFlight.add(player.getUniqueId())) {
            player.sendActionBar(text("正在处理", NamedTextColor.YELLOW));
            return;
        }
        Services services = services(player);
        if (services == null) {
            inFlight.remove(player.getUniqueId());
            return;
        }
        MembershipQuote quote = holder.quote;
        services.memberships().purchase(new MembershipPurchaseRequest(
            player.getUniqueId(),
            quote.targetTier().key(),
            quote.months(),
            quote.upgradeMode(),
            holder.idempotencyKey,
            "MENU"
        )).whenComplete((order, failure) -> main(() -> {
            inFlight.remove(player.getUniqueId());
            if (!ready(player)) {
                return;
            }
            if (failure != null) {
                fail(player, message(unwrap(failure)), failure);
                return;
            }
            showPurchaseResult(player, order);
        }));
    }

    private void showPurchaseResult(Player player, MembershipOrderResult order) {
        if (order.status() == MembershipOrderStatus.COMPLETED) {
            player.sendMessage(text("会员已生效，到期 " + date(order.expiresAt()), NamedTextColor.GREEN));
            openMemberships(player);
            return;
        }
        if (order.status() == MembershipOrderStatus.COMPENSATED) {
            player.sendMessage(text("购买未完成，点券已退回", NamedTextColor.YELLOW));
        } else if (order.status() == MembershipOrderStatus.REVIEW_REQUIRED
            || order.status() == MembershipOrderStatus.COMPENSATION_PENDING) {
            player.sendMessage(text("订单处理中，请联系管理员", NamedTextColor.YELLOW));
        } else {
            player.sendMessage(text(errorText(order.errorCode()), NamedTextColor.RED));
        }
    }

    private Services services(Player player) {
        MembershipService memberships = providers.find(MembershipServiceProvider.KEY).orElse(null);
        PointsService points = providers.find(PointsServiceProvider.KEY).orElse(null);
        if (memberships == null || points == null) {
            player.sendMessage(text("会员服务暂不可用", NamedTextColor.RED));
            return null;
        }
        return new Services(memberships, points);
    }

    private void main(Runnable action) {
        platformTasks.callMain(() -> {
            action.run();
            return null;
        });
    }

    private boolean ready(Player player) {
        return plugin.isEnabled() && player.isOnline();
    }

    private void fail(Player player, String message, Throwable failure) {
        logger.warn("Membership menu operation failed", failure);
        player.sendMessage(text(message, NamedTextColor.RED));
    }

    private Inventory fill(Inventory inventory, Material material) {
        ItemStack filler = item(material, Component.empty(), List.of());
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, filler);
        }
        return inventory;
    }

    private ItemStack playerHead(Player player, List<Component> lore) {
        ItemStack head = item(Material.PLAYER_HEAD, text(player.getName(), NamedTextColor.WHITE), lore);
        if (head.getItemMeta() instanceof SkullMeta skull) {
            skull.setOwningPlayer(player);
            head.setItemMeta(skull);
        }
        return head;
    }

    private static ItemStack item(Material material, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(name.decoration(TextDecoration.ITALIC, false));
        meta.lore(lore.stream().map(line -> line.decoration(TextDecoration.ITALIC, false)).toList());
        item.setItemMeta(meta);
        return item;
    }

    private static Component price(MembershipQuote quote) {
        if (quote.promotionOffBps() <= 0) {
            return line("价格", quote.finalPricePoints() + " 点券", NamedTextColor.GOLD);
        }
        return text("价格  ", NamedTextColor.GRAY)
            .append(text(quote.basePricePoints() + " 点券", NamedTextColor.GRAY)
                .decorate(TextDecoration.STRIKETHROUGH))
            .append(text("  " + quote.finalPricePoints() + " 点券", NamedTextColor.GREEN));
    }

    private String date(Instant instant) {
        return instant == null ? "—" : DATE.format(instant.atZone(timezone));
    }

    private static String countdown(Instant end) {
        long seconds = Math.max(0, Duration.between(Instant.now(), end).toSeconds());
        long days = seconds / 86_400;
        long hours = seconds % 86_400 / 3_600;
        long minutes = seconds % 3_600 / 60;
        if (days > 0) {
            return days + "天 " + hours + "小时";
        }
        if (hours > 0) {
            return hours + "小时 " + minutes + "分";
        }
        return minutes + "分";
    }

    private static NamedTextColor tierColor(int priority) {
        if (priority >= 400) {
            return NamedTextColor.LIGHT_PURPLE;
        }
        if (priority >= 300) {
            return NamedTextColor.AQUA;
        }
        if (priority >= 200) {
            return NamedTextColor.YELLOW;
        }
        return NamedTextColor.GOLD;
    }

    private static String syncText(String status) {
        return "APPLIED".equals(status) ? "已同步" : "同步中";
    }

    private static NamedTextColor syncColor(String status) {
        return "APPLIED".equals(status) ? NamedTextColor.GREEN : NamedTextColor.YELLOW;
    }

    private static Component line(String label, String value, NamedTextColor valueColor) {
        return text(label + "  ", NamedTextColor.GRAY).append(text(value, valueColor));
    }

    private static Component text(String value, NamedTextColor color) {
        return Component.text(value, color).decoration(TextDecoration.ITALIC, false);
    }

    private static String integer(int value) {
        return java.text.NumberFormat.getIntegerInstance(java.util.Locale.CHINA).format(value);
    }

    private static Throwable unwrap(Throwable failure) {
        if (failure == null) {
            return null;
        }
        Throwable current = failure;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static String message(Throwable failure) {
        Throwable cause = unwrap(failure);
        if (cause instanceof MembershipException membership) {
            return errorText(membership.code());
        }
        return "会员服务暂不可用";
    }

    private static String errorText(String code) {
        return switch (code == null ? "" : code) {
            case "MEMBERSHIP_DOWNGRADE_FORBIDDEN" -> "当前会员等级更高";
            case "MEMBERSHIP_UPGRADE_MODE_REQUIRED" -> "请选择升级方式";
            case "MEMBERSHIP_QUOTE_STALE" -> "价格已变化，请重新确认";
            case "POINTS_MUTATION_REJECTED", "INSUFFICIENT_POINTS" -> "点券不足";
            case "OPERATION_IN_PROGRESS" -> "已有订单正在处理";
            default -> "购买未完成";
        };
    }

    private record Services(MembershipService memberships, PointsService points) {
    }

    private record QuoteView(MembershipTier tier, MembershipQuote quote, Throwable failure) {
    }

    private record CatalogView(
        MembershipSummary summary,
        int points,
        List<QuoteView> quotes
    ) {
    }

    private static final class PersonalHolder implements InventoryHolder {
        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final class MembershipHolder implements InventoryHolder {
        private final MembershipSummary summary;
        private final Map<Integer, QuoteView> quotes = new HashMap<>();
        private Inventory inventory;

        private MembershipHolder(MembershipSummary summary) {
            this.summary = summary;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final class UpgradeHolder implements InventoryHolder {
        private final String tierKey;
        private Inventory inventory;

        private UpgradeHolder(String tierKey) {
            this.tierKey = tierKey;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final class ConfirmHolder implements InventoryHolder {
        private final MembershipQuote quote;
        private final String idempotencyKey;
        private Inventory inventory;

        private ConfirmHolder(MembershipQuote quote, String idempotencyKey) {
            this.quote = quote;
            this.idempotencyKey = idempotencyKey;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
