package cn.cctstudio.cctsystem.platform.paper;

import cn.cctstudio.cctsystem.core.concurrent.PlatformTaskExecutor;
import cn.cctstudio.cctsystem.core.config.CctConfig;
import cn.cctstudio.cctsystem.core.config.MembershipTierConfig;
import cn.cctstudio.cctsystem.core.logging.CctLogger;
import cn.cctstudio.cctsystem.core.provider.ProviderRegistry;
import cn.cctstudio.cctsystem.membership.MembershipEntitlement;
import cn.cctstudio.cctsystem.membership.MembershipException;
import cn.cctstudio.cctsystem.membership.MembershipFailures;
import cn.cctstudio.cctsystem.membership.MembershipMenuSnapshot;
import cn.cctstudio.cctsystem.membership.MembershipMenuTier;
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
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
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
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final JavaPlugin plugin;
    private final ProviderRegistry providers;
    private final PlatformTaskExecutor platformTasks;
    private final CctLogger logger;
    private final ZoneId timezone;
    private final PaperExchangeMenu exchangeMenu;
    private final PaperLuckPermsTitles titles;
    private final PaperMessages messages;
    private final PaperMenus menus;
    private final PaperTitleService titleService;
    private final PaperNicknameService nicknameService;
    private final PaperMailbox mailbox;
    private final List<MembershipTier> configuredTiers;
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();
    private final Map<UUID, CompletableFuture<PersonalData>> personalLoads =
        new ConcurrentHashMap<>();
    private final Map<UUID, CompletableFuture<MembershipData>> membershipLoads =
        new ConcurrentHashMap<>();
    private final Map<UUID, MembershipSummary> summaryCache = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> pointsCache = new ConcurrentHashMap<>();
    private final Map<UUID, List<MembershipMenuTier>> menuCache = new ConcurrentHashMap<>();

    PaperMembershipMenu(
        JavaPlugin plugin,
        ProviderRegistry providers,
        CctConfig config,
        PlatformTaskExecutor platformTasks,
        CctLogger logger,
        PaperExchangeMenu exchangeMenu,
        PaperLuckPermsTitles titles,
        PaperMessages messages,
        PaperMenus menus,
        PaperTitleService titleService,
        PaperNicknameService nicknameService,
        PaperMailbox mailbox
    ) {
        this.plugin = plugin;
        this.providers = providers;
        this.platformTasks = platformTasks;
        this.logger = logger;
        this.timezone = ZoneId.of(config.promotion().timezone());
        this.exchangeMenu = exchangeMenu;
        this.titles = titles;
        this.messages = messages;
        this.menus = menus;
        this.titleService = titleService;
        this.nicknameService = nicknameService;
        this.mailbox = mailbox;
        this.configuredTiers = config.membership().tiers().stream()
            .filter(MembershipTierConfig::enabled)
            .sorted(java.util.Comparator.comparingInt(MembershipTierConfig::priority))
            .map(PaperMembershipMenu::tier)
            .toList();
    }

    void openPersonal(Player player) {
        Services services = services(player);
        if (services == null) return;
        UUID uuid = player.getUniqueId();
        PaperMenus.MenuDefinition layout = menus.get("personal");
        PersonalHolder holder = new PersonalHolder(
            summaryCache.get(uuid), pointsCache.get(uuid), layout
        );
        holder.inventory = plugin.getServer().createInventory(
            holder, layout.size(), messages.component(layout.titleKey())
        );
        renderPersonal(player, holder);
        player.openInventory(holder.inventory);
        refreshPersonal(player, holder, services);
    }

    void openMemberships(Player player) {
        Services services = services(player);
        if (services == null) return;
        UUID uuid = player.getUniqueId();
        PaperMenus.MenuDefinition layout = menus.get("membership");
        MembershipHolder holder = new MembershipHolder(
            summaryCache.get(uuid),
            pointsCache.get(uuid),
            menuCache.getOrDefault(uuid, loadingTiers()),
            layout
        );
        holder.inventory = plugin.getServer().createInventory(
            holder, layout.size(), messages.component(layout.titleKey())
        );
        renderMembership(player, holder);
        player.openInventory(holder.inventory);
        refreshMembership(player, holder, services);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (handleDiscountAnvilClick(event)) return;
        InventoryHolder holder = event.getInventory().getHolder(false);
        if (!(holder instanceof PersonalHolder
            || holder instanceof MembershipHolder
            || holder instanceof UpgradeHolder
            || holder instanceof ConfirmHolder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)
            || event.getClickedInventory() != event.getView().getTopInventory()) return;
        int slot = event.getRawSlot();
        if (holder instanceof PersonalHolder personal) {
            switch (personal.layout.actionAt(slot)) {
                case "OPEN_MEMBERSHIP" -> openMemberships(player);
                case "OPEN_EXCHANGE" -> exchangeMenu.open(player);
                case "OPEN_PREFIX" -> titleService.command(player, new String[0]);
                case "OPEN_NICKNAME" -> nicknameService.open(player);
                case "OPEN_MAIL" -> {
                    if (mailbox.enabled()) plugin.getServer().getScheduler().runTask(plugin, () -> {
                        if (player.getOpenInventory().getTopInventory() != personal.inventory) return;
                        player.closeInventory();
                        mailbox.open(player);
                    });
                }
                case "REDEEM_HINT" -> player.sendMessage(messages.chat("menus.personal.redeem-hint"));
                case "CLOSE" -> player.closeInventory();
                default -> { }
            }
            return;
        }
        if (holder instanceof MembershipHolder memberships) {
            if (memberships.applyingDiscount && (slot == memberships.inventory.getSize() - 1
                || "PURCHASE_MEMBERSHIP".equals(memberships.layout.actionAt(slot)))) {
                player.sendMessage(messages.chat("discount-code.applying"));
                return;
            }
            if(slot==memberships.inventory.getSize()-1) {
                plugin.getServer().getScheduler().runTask(plugin,() -> openDiscountAnvil(player,memberships)); return;
            }
            String action = memberships.layout.actionAt(slot);
            if ("OPEN_PERSONAL".equals(action)) {
                openPersonal(player);
                return;
            }
            if (!"PURCHASE_MEMBERSHIP".equals(action)) return;
            MembershipMenuTier view = memberships.tiersBySlot.get(slot);
            if (view == null) return;
            if (!view.available()) {
                boolean loading = view.unavailableCode() == null;
                player.sendActionBar(loading
                    ? messages.component("menus.membership.data-loading")
                    : messages.component(errorPath(view.unavailableCode())));
                return;
            }
            MembershipEntitlement active = memberships.summary == null
                ? null : memberships.summary.active();
            if (active != null && view.tier().priority() > active.tier().priority()) {
                openUpgradeChoice(player, view.tier(), memberships.discountCode);
            } else {
                quoteAndConfirm(player,view.tier().key(),UpgradeMode.NONE,memberships.discountCode);
            }
            return;
        }
        if (holder instanceof UpgradeHolder upgrade) {
            switch (upgrade.layout.actionAt(slot)) {
                case "UPGRADE_PAUSE" -> quoteAndConfirm(player, upgrade.tierKey, UpgradeMode.PAUSE,upgrade.discountCode);
                case "UPGRADE_CREDIT" -> quoteAndConfirm(player, upgrade.tierKey, UpgradeMode.CREDIT,upgrade.discountCode);
                case "OPEN_MEMBERSHIP" -> openMemberships(player);
                default -> { }
            }
            return;
        }
        if (holder instanceof ConfirmHolder confirm) {
            String action = confirm.layout.actionAt(slot);
            if ("CONFIRM_PURCHASE".equals(action)) {
                if (confirm.quote == null) {
                    player.sendActionBar(messages.component("menus.membership.price-loading"));
                } else {
                    purchase(player, confirm);
                }
            } else if ("OPEN_MEMBERSHIP".equals(action)) {
                openMemberships(player);
            }
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        DiscountAnvil anvil=discountAnvils.get(event.getWhoClicked().getUniqueId());
        if(anvil!=null && anvil.view==event.getView()) { event.setCancelled(true); return; }
        InventoryHolder holder = event.getInventory().getHolder(false);
        if (holder instanceof PersonalHolder
            || holder instanceof MembershipHolder
            || holder instanceof UpgradeHolder
            || holder instanceof ConfirmHolder) {
            event.setCancelled(true);
        }
    }

    private void refreshPersonal(Player player, PersonalHolder holder, Services services) {
        UUID uuid = player.getUniqueId();
        CompletableFuture<PersonalData> load = personalLoads.computeIfAbsent(uuid, ignored -> {
            CompletableFuture<MembershipSummary> summary =
                services.memberships().summary(uuid).toCompletableFuture();
            CompletableFuture<Integer> points = services.points().balance(uuid).toCompletableFuture();
            return CompletableFuture.allOf(summary, points)
                .thenApply(nothing -> new PersonalData(summary.join(), points.join()));
        });
        load.whenComplete((data, failure) -> main(() -> {
            personalLoads.remove(uuid, load);
            if (!ready(player)) return;
            if (failure != null) {
                logFailure("Unable to refresh personal menu", failure);
                return;
            }
            holder.summary = data.summary();
            holder.points = data.points();
            summaryCache.put(uuid, holder.summary);
            pointsCache.put(uuid, holder.points);
            if (player.getOpenInventory().getTopInventory() == holder.inventory) {
                renderPersonal(player, holder);
            }
        }));
    }

    private void refreshMembership(Player player, MembershipHolder holder, Services services) {
        UUID uuid = player.getUniqueId();
        int revision=holder.discountRevision;
        CompletableFuture<MembershipData> load = membershipLoads.computeIfAbsent(uuid, ignored -> {
            CompletableFuture<MembershipMenuSnapshot> snapshot =
                services.memberships().menu(uuid).toCompletableFuture();
            CompletableFuture<Integer> points = services.points().balance(uuid).toCompletableFuture();
            return CompletableFuture.allOf(snapshot, points)
                .thenApply(nothing -> new MembershipData(snapshot.join(), points.join()));
        });
        load.whenComplete((data, failure) -> main(() -> {
            membershipLoads.remove(uuid, load);
            if (!ready(player)) return;
            if (failure != null) {
                logFailure("Unable to refresh membership menu", failure);
                if (player.getOpenInventory().getTopInventory() == holder.inventory) {
                    player.sendActionBar(messages.component("menus.membership.load-failed"));
                }
                return;
            }
            if(holder.discountRevision!=revision) return;
            MembershipMenuSnapshot value = data.snapshot();
            holder.summary = value.summary();
            holder.points = data.points();
            holder.tiers = value.tiers();
            summaryCache.put(uuid, holder.summary);
            pointsCache.put(uuid, holder.points);
            menuCache.put(uuid, holder.tiers);
            if (player.getOpenInventory().getTopInventory() == holder.inventory) {
                renderMembership(player, holder);
            }
        }));
    }

    private void renderPersonal(Player player, PersonalHolder holder) {
        Inventory inventory = holder.inventory;
        holder.layout.fill(inventory);
        PaperMenus.MenuItemDefinition profile = holder.layout.item("profile");
        inventory.setItem(profile.slot(), playerHead(
            player, accountLore(player, holder.summary, holder.points)
        ));
        putConfigured(inventory, holder.layout.item("membership"),
            messages.component("menus.personal.membership-name"),
            messages.components("menus.personal.membership-lore"), Material.NETHER_STAR);
        putConfigured(inventory, holder.layout.item("exchange"),
            messages.component("menus.personal.exchange-name"),
            messages.components("menus.personal.exchange-lore"), Material.EMERALD);
        putConfigured(inventory, holder.layout.item("redeem"),
            messages.component("menus.personal.redeem-name"),
            messages.components("menus.personal.redeem-lore"), Material.NAME_TAG);
        putConfigured(inventory, holder.layout.item("prefix"),
            messages.component("menus.personal.prefix-name"),
            messages.components("menus.personal.prefix-lore"), Material.ANVIL);
        putConfigured(inventory, holder.layout.item("nickname"),
            messages.component("menus.personal.nickname-name"),
            messages.components("menus.personal.nickname-lore"), Material.ENDER_EYE);
        var mail = holder.layout.items().get("mail");
        if (mailbox.enabled() && mail != null) {
            putConfigured(inventory, mail, messages.component("menus.personal.mail-name"),
                messages.components("menus.personal.mail-lore"), Material.WRITABLE_BOOK);
        }
        putConfigured(inventory, holder.layout.item("close"),
            messages.component("menus.personal.close-name"), List.of(), Material.BARRIER);
    }

    private void renderMembership(Player player, MembershipHolder holder) {
        Inventory inventory = holder.inventory;
        holder.layout.fill(inventory);
        holder.tiersBySlot.clear();
        PaperMenus.MenuItemDefinition profile = holder.layout.item("profile");
        inventory.setItem(profile.slot(), playerHead(
            player, accountLore(player, holder.summary, holder.points)
        ));
        for (MembershipMenuTier view : holder.tiers) {
            PaperMenus.MenuItemDefinition purchase = findItem(
                holder.layout, "PURCHASE_MEMBERSHIP", view.tier().key()
            );
            if (purchase != null) {
                holder.tiersBySlot.put(purchase.slot(), view);
                inventory.setItem(purchase.slot(), tierItem(view, holder.summary, purchase,holder.discountCode!=null));
            }
            PaperMenus.MenuItemDefinition benefits = findItem(
                holder.layout, "VIEW_BENEFITS", view.tier().key()
            );
            if (benefits != null) {
                inventory.setItem(benefits.slot(), benefitsBook(view.tier(), benefits));
            }
        }
        inventory.setItem(inventory.getSize() - 1, item(holder.applyingDiscount ? Material.CLOCK : Material.NAME_TAG,
            messages.component(holder.applyingDiscount ? "discount-code.applying" : "discount-code.title"),
            holder.discountCode == null ? List.of() : List.of(Component.text(holder.discountCode))));
        putConfigured(inventory, holder.layout.item("back"),
            messages.component("menus.membership.back-name"), List.of(), Material.ARROW);
    }

    private List<Component> accountLore(Player player, MembershipSummary summary, Integer points) {
        List<Component> lore = new ArrayList<>();
        lore.add(messages.component("menus.account.title-label").append(titles.playerTitle(player)));
        lore.add(messages.component("menus.account.points", Map.of(
            "value", points == null ? messages.raw("menus.account.loading") : integer(points)
        )));
        if (summary == null) {
            lore.add(messages.component("menus.account.membership", Map.of(
                "value", messages.raw("menus.account.loading")
            )));
        } else if (summary.active() == null) {
            lore.add(messages.component("menus.account.membership", Map.of(
                "value", messages.raw("menus.account.no-membership")
            )));
        } else {
            lore.add(messages.component("menus.account.membership", Map.of(
                "value", summary.active().tier().displayName()
            )));
            lore.add(messages.component("menus.account.expiry", Map.of(
                "value", date(summary.active().expiresAt())
            )));
            lore.add(messages.component("menus.account.remaining", Map.of(
                "value", remaining(summary.active())
            )));
            lore.add(messages.component("menus.account.permission", Map.of(
                "value", messages.raw("APPLIED".equals(summary.permissionSyncStatus())
                    ? "menus.account.sync-applied" : "menus.account.sync-pending")
            )));
        }
        return lore;
    }

    private ItemStack tierItem(
        MembershipMenuTier view,
        MembershipSummary summary,
        PaperMenus.MenuItemDefinition definition, boolean hideCodeDetails
    ) {
        MembershipTier tier = view.tier();
        Material material = Material.matchMaterial(tier.displayMaterial());
        if (material == null) material = Material.GOLD_INGOT;
        material = definition.material(material);
        List<Component> lore = new ArrayList<>();
        if (view.quote() == null) {
            boolean loading = view.unavailableCode() == null;
            lore.add(loading
                ? messages.component("menus.membership.price-loading")
                : messages.component(errorPath(view.unavailableCode())));
        } else {
            lore.add(price(view.quote()));
            lore.add(messages.component("menus.membership.duration", Map.of(
                "days", tier.durationDays()
            )));
            if (!hideCodeDetails && view.quote().promotionEndsAt() != null) {
                lore.add(messages.component("menus.membership.promotion-left", Map.of(
                    "time", countdown(view.quote().promotionEndsAt())
                )));
            }
        }
        MembershipEntitlement owned = entitlement(summary, tier.key());
        if (owned != null) {
            lore.add(Component.empty());
            boolean paused = "PAUSED".equals(owned.state().name());
            lore.add(messages.component(paused
                ? "menus.membership.state-paused" : "menus.membership.state-active"));
            lore.add(messages.component("menus.membership.remaining", Map.of(
                "time", remaining(owned)
            )));
        }
        if (view.quote() != null) {
            lore.add(Component.empty());
            MembershipEntitlement active = summary == null ? null : summary.active();
            String action = active != null && active.tier().key().equals(tier.key())
                ? "menus.membership.renew"
                : active != null && tier.priority() > active.tier().priority()
                    ? "menus.membership.upgrade"
                    : "menus.membership.purchase";
            lore.add(messages.component(action));
        }
        return item(material, titles.tierTitle(tier), lore);
    }

    private ItemStack benefitsBook(
        MembershipTier tier,
        PaperMenus.MenuItemDefinition definition
    ) {
        List<Component> lore = tier.benefits().stream()
            .map(this::benefitLine)
            .toList();
        if (lore.isEmpty()) lore = List.of(messages.component("menus.membership.no-benefits"));
        return item(
            definition.material(Material.BOOK),
            titles.tierTitle(tier).append(messages.component("menus.membership.benefits-suffix")),
            lore
        );
    }

    private Component benefitLine(String benefit) {
        String normalized = benefit == null ? "" : benefit.trim();
        if (normalized.startsWith("包含") && normalized.endsWith("全部权益")) {
            String displayName = normalized.substring(
                "包含".length(), normalized.length() - "全部权益".length()
            ).trim();
            MembershipTier included = configuredTiers.stream()
                .filter(tier -> tier.displayName().equalsIgnoreCase(displayName))
                .findFirst()
                .orElse(null);
            if (included != null) {
                return messages.component("menus.membership.benefit-includes-prefix")
                    .append(titles.tierTitle(included))
                    .append(messages.component("menus.membership.benefit-includes-suffix"));
            }
        }
        return messages.component("menus.membership.benefit-line", Map.of(
            "benefit", normalized
        ));
    }

    private void openUpgradeChoice(Player player, MembershipTier tier, String discountCode) {
        PaperMenus.MenuDefinition layout = menus.get("upgrade");
        UpgradeHolder holder = new UpgradeHolder(tier.key(), layout, discountCode);
        holder.inventory = plugin.getServer().createInventory(
            holder, layout.size(), messages.component(layout.titleKey())
        );
        layout.fill(holder.inventory);
        putConfigured(holder.inventory, layout.item("pause"),
            messages.component("menus.upgrade.pause-name"),
            messages.components("menus.upgrade.pause-lore"), Material.CHEST);
        putConfigured(holder.inventory, layout.item("credit"),
            messages.component("menus.upgrade.credit-name"),
            messages.components("menus.upgrade.credit-lore"), Material.ANVIL);
        putConfigured(holder.inventory, layout.item("back"),
            messages.component("menus.upgrade.back-name"), List.of(), Material.ARROW);
        player.openInventory(holder.inventory);
    }

    private void quoteAndConfirm(Player player, String tierKey, UpgradeMode mode,String discountCode) {
        Services services = services(player);
        if (services == null) return;
        PaperMenus.MenuDefinition layout = menus.get("confirmation");
        ConfirmHolder holder = new ConfirmHolder(
            null, "menu-" + UUID.randomUUID(), layout
        );
        holder.inventory = plugin.getServer().createInventory(
            holder, layout.size(), messages.component(layout.titleKey())
        );
        holder.discountCode=discountCode;
        renderConfirmation(holder);
        player.openInventory(holder.inventory);
        services.memberships().quote(player.getUniqueId(), tierKey, 1, mode,discountCode)
            .whenComplete((quote, failure) -> main(() -> {
                if (!ready(player)) return;
                if (failure != null) {
                    logFailure("Unable to prepare membership quote", failure);
                    if (player.getOpenInventory().getTopInventory() == holder.inventory) {
                        player.sendActionBar(messages.component(errorPath(failure)));
                        openMemberships(player);
                    }
                    return;
                }
                holder.quote = quote;
                if (player.getOpenInventory().getTopInventory() == holder.inventory) {
                    renderConfirmation(holder);
                }
            }));
    }

    private void openConfirmation(Player player, MembershipQuote quote,String discountCode) {
        PaperMenus.MenuDefinition layout = menus.get("confirmation");
        ConfirmHolder holder = new ConfirmHolder(
            quote, "menu-" + UUID.randomUUID(), layout
        );
        holder.discountCode=discountCode;
        holder.inventory = plugin.getServer().createInventory(
            holder, layout.size(), messages.component(layout.titleKey())
        );
        renderConfirmation(holder);
        player.openInventory(holder.inventory);
    }

    private void renderConfirmation(ConfirmHolder holder) {
        Inventory inventory = holder.inventory;
        holder.layout.fill(inventory);
        PaperMenus.MenuItemDefinition confirm = holder.layout.item("confirm");
        if (holder.quote == null) {
            inventory.setItem(confirm.slot(), item(
                Material.CLOCK, messages.component("menus.confirmation.loading-name"),
                messages.components("menus.confirmation.loading-lore")
            ));
        } else {
            List<Component> lore = new ArrayList<>();
            lore.add(price(holder.quote));
            if (holder.quote.upgradeCreditPoints() > 0) {
                lore.add(messages.component("menus.confirmation.credit", Map.of(
                    "points", holder.quote.upgradeCreditPoints()
                )));
            }
            lore.add(messages.component("menus.confirmation.duration", Map.of(
                "days", holder.quote.targetTier().durationDays()
            )));
            lore.add(Component.empty());
            lore.add(messages.component("menus.confirmation.confirm-lore"));
            inventory.setItem(confirm.slot(), item(
                confirm.material(Material.LIME_CONCRETE),
                messages.component("menus.confirmation.confirm-name"), lore
            ));
        }
        putConfigured(inventory, holder.layout.item("back"),
            messages.component("menus.confirmation.back-name"), List.of(), Material.ARROW);
    }

    private void purchase(Player player, ConfirmHolder holder) {
        if (!inFlight.add(player.getUniqueId())) {
            player.sendActionBar(messages.component("menus.confirmation.processing"));
            return;
        }
        Services services = services(player);
        if (services == null) {
            inFlight.remove(player.getUniqueId());
            return;
        }
        MembershipQuote quote = holder.quote;
        services.memberships().purchase(new MembershipPurchaseRequest(
            player.getUniqueId(), quote.targetTier().key(), quote.months(),
            quote.upgradeMode(), holder.idempotencyKey, "MENU", holder.discountCode
        )).whenComplete((order, failure) -> main(() -> {
            inFlight.remove(player.getUniqueId());
            if (!ready(player)) return;
            if (failure != null) {
                logFailure("Membership purchase failed", failure);
                player.sendMessage(messages.chat(errorPath(failure)));
                return;
            }
            showPurchaseResult(player, order);
        }));
    }

    private void showPurchaseResult(Player player, MembershipOrderResult order) {
        if (order.status() == MembershipOrderStatus.COMPLETED) {
            summaryCache.remove(player.getUniqueId());
            menuCache.remove(player.getUniqueId());
            player.sendMessage(messages.chat("menus.confirmation.completed", Map.of(
                "expiry", date(order.expiresAt())
            )));
            openMemberships(player);
        } else if (order.status() == MembershipOrderStatus.COMPENSATED) {
            player.sendMessage(messages.chat("menus.confirmation.compensated"));
        } else if (order.status() == MembershipOrderStatus.REVIEW_REQUIRED
            || order.status() == MembershipOrderStatus.COMPENSATION_PENDING) {
            player.sendMessage(messages.chat("menus.confirmation.review-required"));
        } else {
            player.sendMessage(messages.chat(errorPath(order.errorCode())));
        }
    }

    private Services services(Player player) {
        MembershipService memberships = providers.find(MembershipServiceProvider.KEY).orElse(null);
        PointsService points = providers.find(PointsServiceProvider.KEY).orElse(null);
        if (memberships == null || points == null) {
            player.sendMessage(messages.chat("menus.confirmation.service-unavailable"));
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

    private void logFailure(String message, Throwable failure) {
        MembershipFailures.log(logger, message, failure);
    }

    private ItemStack playerHead(Player player, List<Component> lore) {
        ItemStack head = item(Material.PLAYER_HEAD, titles.playerIdentity(player), lore);
        if (head.getItemMeta() instanceof SkullMeta skull) {
            skull.setPlayerProfile(player.getPlayerProfile());
            head.setItemMeta(skull);
        }
        return head;
    }

    private static ItemStack item(Material material, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(name.decoration(TextDecoration.ITALIC, false));
        meta.lore(lore.stream().map(line ->
            line.decoration(TextDecoration.ITALIC, false)).toList());
        item.setItemMeta(meta);
        return item;
    }

    private Component price(MembershipQuote quote) {
        if (quote.promotionOffBps() <= 0) {
            return messages.component("menus.price.regular", Map.of(
                "price", quote.finalPricePoints()
            ));
        }
        return messages.component("menus.price.label")
            .append(messages.component("menus.price.base", Map.of(
                "price", quote.basePricePoints()
            ))
                .decorate(TextDecoration.STRIKETHROUGH))
            .append(messages.component("menus.price.discounted", Map.of(
                "price", quote.finalPricePoints()
            )));
    }

    private String date(Instant instant) {
        return instant == null ? "—" : DATE.format(instant.atZone(timezone));
    }

    private String countdown(Instant end) {
        long seconds = Math.max(0, Duration.between(Instant.now(), end).toSeconds());
        long days = seconds / 86_400;
        long hours = seconds % 86_400 / 3_600;
        long minutes = seconds % 3_600 / 60;
        if (days > 0) return renderRaw("time.days-hours", Map.of(
            "days", days, "hours", hours
        ));
        return hours > 0 ? renderRaw("time.hours-minutes", Map.of(
            "hours", hours, "minutes", minutes
        )) : renderRaw("time.minutes", Map.of("minutes", minutes));
    }

    private static MembershipEntitlement entitlement(MembershipSummary summary, String tierKey) {
        if (summary == null) return null;
        if (summary.active() != null && summary.active().tier().key().equals(tierKey)) {
            return summary.active();
        }
        return summary.paused().stream()
            .filter(value -> value.tier().key().equals(tierKey))
            .findFirst().orElse(null);
    }

    private String remaining(MembershipEntitlement entitlement) {
        long seconds;
        if (entitlement.remainingSeconds() != null) {
            seconds = Math.max(0, entitlement.remainingSeconds());
        } else if (entitlement.expiresAt() != null) {
            seconds = Math.max(0, Duration.between(Instant.now(), entitlement.expiresAt()).toSeconds());
        } else {
            return "—";
        }
        long days = seconds / 86_400;
        long hours = seconds % 86_400 / 3_600;
        return days > 0 ? renderRaw("time.remaining-days-hours", Map.of(
            "days", days, "hours", hours
        )) : renderRaw("time.remaining-hours", Map.of("hours", hours));
    }

    private String renderRaw(String path, Map<String, ?> placeholders) {
        String value = messages.raw(path);
        for (Map.Entry<String, ?> entry : placeholders.entrySet()) {
            value = value.replace("{" + entry.getKey() + "}", String.valueOf(entry.getValue()));
        }
        return value;
    }

    private static String integer(int value) {
        return java.text.NumberFormat.getIntegerInstance(java.util.Locale.CHINA).format(value);
    }

    private static Throwable unwrap(Throwable failure) {
        if (failure == null) return null;
        Throwable current = failure;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static String errorPath(Throwable failure) {
        Throwable cause = unwrap(failure);
        if (cause instanceof MembershipException membership) return errorPath(membership.code());
        return "menus.confirmation.service-unavailable";
    }

    private static String errorPath(String code) {
        if ("DISCOUNT_CODE_UNAVAILABLE".equals(code) || "DISCOUNT_CODE_LIMIT_REACHED".equals(code)) return "discount-code.invalid";
        return switch (code == null ? "" : code) {
            case "MEMBERSHIP_DOWNGRADE_FORBIDDEN" -> "menus.errors.downgrade";
            case "MEMBERSHIP_UPGRADE_MODE_REQUIRED" -> "menus.errors.upgrade-mode-required";
            case "MEMBERSHIP_DURATION_LIMIT" -> "menus.errors.duration-limit";
            case "MEMBERSHIP_PURCHASE_FORBIDDEN" -> "menus.errors.forbidden";
            case "MEMBERSHIP_BINDING_REQUIRED" -> "menus.errors.binding-required";
            case "MEMBERSHIP_QUOTE_STALE" -> "menus.errors.stale-quote";
            case "MEMBERSHIP_TIER_UNAVAILABLE" -> "menus.errors.tier-unavailable";
            case "POINTS_MUTATION_REJECTED", "INSUFFICIENT_POINTS" -> "menus.errors.insufficient-points";
            case "OPERATION_IN_PROGRESS" -> "menus.errors.operation-processing";
            default -> "menus.errors.incomplete";
        };
    }

    private static PaperMenus.MenuItemDefinition findItem(
        PaperMenus.MenuDefinition layout,
        String action,
        String value
    ) {
        return layout.items().values().stream()
            .filter(item -> action.equals(item.action()))
            .filter(item -> value.equalsIgnoreCase(item.value()))
            .findFirst()
            .orElse(null);
    }

    private static void putConfigured(
        Inventory inventory,
        PaperMenus.MenuItemDefinition definition,
        Component name,
        List<Component> lore,
        Material fallback
    ) {
        inventory.setItem(
            definition.slot(), item(definition.material(fallback), name, lore)
        );
    }

    private List<MembershipMenuTier> loadingTiers() {
        return configuredTiers.stream()
            .map(value -> new MembershipMenuTier(value, null, null))
            .toList();
    }

    private static MembershipTier tier(MembershipTierConfig config) {
        return new MembershipTier(
            config.key(), config.displayName(), config.priority(), config.luckPermsGroup(),
            config.durationDays(), config.pricePoints(), config.upgradeCreditRateBps(),
            config.displayMaterial(), config.benefits(), config.enabled(), 0
        );
    }

    private record Services(MembershipService memberships, PointsService points) {
    }

    private record PersonalData(MembershipSummary summary, int points) {
    }

    private record MembershipData(MembershipMenuSnapshot snapshot, int points) {
    }

    private static final class PersonalHolder implements InventoryHolder {
        private MembershipSummary summary;
        private Integer points;
        private final PaperMenus.MenuDefinition layout;
        private Inventory inventory;

        private PersonalHolder(
            MembershipSummary summary,
            Integer points,
            PaperMenus.MenuDefinition layout
        ) {
            this.summary = summary;
            this.points = points;
            this.layout = layout;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final class MembershipHolder implements InventoryHolder {
        private MembershipSummary summary;
        private String discountCode;
        private String discountInput;
        private boolean applyingDiscount;
        private int discountRevision;
        private Integer points;
        private List<MembershipMenuTier> tiers;
        private final PaperMenus.MenuDefinition layout;
        private final Map<Integer, MembershipMenuTier> tiersBySlot = new HashMap<>();
        private Inventory inventory;

        private MembershipHolder(
            MembershipSummary summary,
            Integer points,
            List<MembershipMenuTier> tiers,
            PaperMenus.MenuDefinition layout
        ) {
            this.summary = summary;
            this.points = points;
            this.tiers = tiers;
            this.layout = layout;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final class UpgradeHolder implements InventoryHolder {
        private final String tierKey;
        private final PaperMenus.MenuDefinition layout;
        private Inventory inventory;

        private final String discountCode;
        private UpgradeHolder(String tierKey, PaperMenus.MenuDefinition layout,String discountCode) {
            this.discountCode=discountCode;
            this.tierKey = tierKey;
            this.layout = layout;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final Map<UUID, DiscountAnvil> discountAnvils = new HashMap<>();
    private static final class DiscountAnvil {
        final MembershipHolder holder;
        final org.bukkit.inventory.view.AnvilView view;
        boolean applying;
        DiscountAnvil(MembershipHolder holder, org.bukkit.inventory.view.AnvilView view) { this.holder=holder; this.view=view; }
    }

    private void openDiscountAnvil(Player player, MembershipHolder holder) {
        if (!ready(player) || inFlight.contains(player.getUniqueId())
            || player.getOpenInventory().getTopInventory()!=holder.inventory) return;
        var view=org.bukkit.inventory.MenuType.ANVIL.builder().title(messages.component("discount-code.title"))
            .checkReachable(false).build(player);
        var session=new DiscountAnvil(holder,view);
        discountAnvils.put(player.getUniqueId(),session);
        view.open();
        if (player.getOpenInventory()!=view) { discountAnvils.remove(player.getUniqueId()); view.getTopInventory().clear(); return; }
        view.getTopInventory().setItem(0, item(Material.NAME_TAG,
            Component.text(holder.discountInput == null ? "" : holder.discountInput), List.of()));
        view.setRepairCost(0); view.setRepairItemCountCost(0); view.setMaximumRepairCost(Integer.MAX_VALUE);
        view.getTopInventory().setItem(2,item(Material.NAME_TAG,messages.component("discount-code.apply"),List.of()));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDiscountAnvilPrepare(org.bukkit.event.inventory.PrepareAnvilEvent event) {
        DiscountAnvil session=discountAnvils.get(event.getView().getPlayer().getUniqueId());
        if (session==null || session.view!=event.getView()) return;
        event.setResult(item(Material.NAME_TAG,messages.component(session.applying ? "discount-code.applying" : "discount-code.apply"),List.of()));
        event.getView().setRepairCost(0); event.getView().setRepairItemCountCost(0);
        plugin.getServer().getScheduler().runTask(plugin,() -> {
            if(discountAnvils.get(event.getView().getPlayer().getUniqueId())==session) session.view.setRepairCost(0);
        });
    }

    private boolean handleDiscountAnvilClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return false;
        DiscountAnvil session=discountAnvils.get(player.getUniqueId());
        if(session==null || session.view!=event.getView()) return false;
        event.setCancelled(true);
        if(event.getRawSlot()!=2 || session.applying) return true;
        String input=session.view.getRenameText();
        String code=input==null ? "" : java.text.Normalizer.normalize(input.trim(),java.text.Normalizer.Form.NFC);
        if(!code.isEmpty() && !code.matches("[\\p{L}\\p{N}_-]{1,32}")) {
            player.sendMessage(messages.chat("discount-code.invalid")); return true;
        }
        session.applying = true;
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!ready(player) || discountAnvils.get(player.getUniqueId()) != session
                || player.getOpenInventory() != session.view) return;
            MembershipHolder holder = session.holder;
            holder.discountInput = code;
            holder.applyingDiscount = true;
            holder.discountRevision++;
            renderMembership(player, holder);
            // The close listener clears the synthetic anvil slots before they can be returned.
            player.openInventory(holder.inventory);
            if (player.getOpenInventory().getTopInventory() != holder.inventory) {
                holder.applyingDiscount = false;
                session.applying = false;
                player.sendMessage(messages.chat("discount-code.service-unavailable"));
                return;
            }
            Services services = services(player);
            if (services == null) {
                holder.applyingDiscount = false;
                renderMembership(player, holder);
                return;
            }
            applyDiscount(player, holder, services.memberships(), true);
        });
        return true;
    }

    private void applyDiscount(Player player, MembershipHolder holder, MembershipService memberships, boolean canRetry) {
        String code = holder.discountInput.isEmpty() ? null : holder.discountInput;
        CompletableFuture.completedFuture(code)
            .thenCompose(value -> memberships.menu(player.getUniqueId(), value))
            .whenComplete((snapshot, failure) -> main(() -> {
                if (!ready(player) || player.getOpenInventory().getTopInventory() != holder.inventory) return;
                boolean bridgeUnavailable = MembershipFailures.temporaryTransport(failure);
                if (canRetry && bridgeUnavailable) {
                    plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                        if (ready(player) && player.getOpenInventory().getTopInventory() == holder.inventory) {
                            applyDiscount(player, holder, memberships, false);
                        }
                    }, 20L);
                    return;
                }
                holder.applyingDiscount = false;
                if (failure == null) {
                    holder.summary = snapshot.summary();
                    holder.tiers = snapshot.tiers();
                    holder.discountCode = code;
                    player.sendMessage(messages.chat(code == null ? "discount-code.cleared" : "discount-code.applied"));
                } else {
                    String error = bridgeUnavailable ? "discount-code.service-unavailable" : errorPath(failure);
                    if (!error.equals("discount-code.invalid")) logFailure("Unable to apply membership discount", failure);
                    player.sendMessage(messages.chat(error));
                }
                renderMembership(player, holder);
            }));
    }

    @EventHandler
    public void onDiscountAnvilClose(org.bukkit.event.inventory.InventoryCloseEvent event) {
        DiscountAnvil session=discountAnvils.get(event.getPlayer().getUniqueId());
        if(session!=null && session.view==event.getView()) {
            discountAnvils.remove(event.getPlayer().getUniqueId());
            event.getInventory().clear();
        }
    }

    @EventHandler
    public void onDiscountQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        DiscountAnvil session=discountAnvils.remove(event.getPlayer().getUniqueId());
        if(session!=null) session.view.getTopInventory().clear();
    }

    private static final class ConfirmHolder implements InventoryHolder {
        private MembershipQuote quote;
        private String discountCode;
        private String idempotencyKey;
        private final PaperMenus.MenuDefinition layout;
        private Inventory inventory;

        private ConfirmHolder(
            MembershipQuote quote,
            String idempotencyKey,
            PaperMenus.MenuDefinition layout
        ) {
            this.quote = quote;
            this.idempotencyKey = idempotencyKey;
            this.layout = layout;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
