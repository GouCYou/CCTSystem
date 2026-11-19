package cn.cctstudio.cctsystem.platform.paper;

import cn.cctstudio.cctsystem.core.concurrent.PlatformTaskExecutor;
import cn.cctstudio.cctsystem.core.config.CctConfig;
import cn.cctstudio.cctsystem.core.logging.CctLogger;
import cn.cctstudio.cctsystem.core.provider.ProviderRegistry;
import cn.cctstudio.cctsystem.identity.IdentityProvider;
import cn.cctstudio.cctsystem.identity.IdentityService;
import cn.cctstudio.cctsystem.membership.AdminMembershipAction;
import cn.cctstudio.cctsystem.membership.AdminMembershipRequest;
import cn.cctstudio.cctsystem.membership.MembershipService;
import cn.cctstudio.cctsystem.membership.MembershipServiceProvider;
import cn.cctstudio.cctsystem.membership.MembershipSummary;
import cn.cctstudio.cctsystem.promotion.CreatePromotionRequest;
import cn.cctstudio.cctsystem.promotion.Promotion;
import cn.cctstudio.cctsystem.promotion.PromotionService;
import cn.cctstudio.cctsystem.promotion.PromotionServiceProvider;
import cn.cctstudio.cctsystem.redeem.GenerateRedeemCodesRequest;
import cn.cctstudio.cctsystem.redeem.RedeemCodeService;
import cn.cctstudio.cctsystem.redeem.RedeemCodeServiceProvider;
import cn.cctstudio.cctsystem.redeem.RedeemReward;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

final class PaperPromotionAdminCommand implements CommandExecutor, TabCompleter {
    private static final DateTimeFormatter INPUT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter OUTPUT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final CctConfig config;
    private final ProviderRegistry providers;
    private final PlatformTaskExecutor platformTasks;
    private final CctLogger logger;
    private final ZoneId timezone;
    private final PaperMessages messages;
    private final PaperMenus menus;
    private final PaperTitleService titles;

    PaperPromotionAdminCommand(
        ProviderRegistry providers,
        PlatformTaskExecutor platformTasks,
        CctConfig config,
        CctLogger logger,
        PaperMessages messages,
        PaperMenus menus,
        PaperTitleService titles
    ) {
        this.config = config;
        this.providers = providers;
        this.platformTasks = platformTasks;
        this.logger = logger;
        this.timezone = ZoneId.of(config.promotion().timezone());
        this.messages = messages;
        this.menus = menus;
        this.titles = titles;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("cctsystem.admin")) {
            sender.sendMessage(messages.component("commands.no-permission"));
            return true;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            usage(sender);
            return true;
        }
        if (args[0].equalsIgnoreCase("reload")) {
            try {
                messages.reload();
                menus.reload();
                sender.sendMessage(messages.component("commands.reloaded"));
            } catch (RuntimeException exception) {
                logger.warn("Unable to reload menu configuration", exception);
                sender.sendMessage(messages.component("commands.reload-failed"));
            }
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("discountcode")) {
            discountCode(sender,args); return true;
        }
        if (args.length >= 2 && args[0].equalsIgnoreCase("member")) {
            member(sender, args);
            return true;
        }
        if (args.length >= 2 && args[0].equalsIgnoreCase("redeem")) {
            redeem(sender, args);
            return true;
        }
        if (args.length >= 2 && (args[0].equalsIgnoreCase("prefix")
            || args[0].equalsIgnoreCase("suffix"))) {
            prefix(sender, args);
            return true;
        }
        if (args.length < 2 || !args[0].equalsIgnoreCase("discount")) {
            usage(sender);
            return true;
        }
        PromotionService service = providers.find(PromotionServiceProvider.KEY).orElse(null);
        if (service == null) {
            sender.sendMessage(messages.component("admin.promotion-service-unavailable"));
            return true;
        }
        try {
            switch (args[1].toLowerCase(java.util.Locale.ROOT)) {
                case "create" -> create(sender, args, service);
                case "stop" -> stop(sender, args, service);
                case "list" -> list(sender, service);
                case "info" -> info(sender, args, service);
                default -> usage(sender);
            }
        } catch (IllegalArgumentException | DateTimeParseException exception) {
            sender.sendMessage(messages.component("commands.invalid-arguments"));
            usage(sender);
        }
        return true;
    }

    private void discountCode(CommandSender sender, String[] args) {
        PromotionService service = providers.find(PromotionServiceProvider.KEY).orElse(null);
        if (service == null) { sender.sendMessage(messages.component("admin.promotion-service-unavailable")); return; }
        try {
            String action = args.length > 1 ? args[1].toLowerCase(java.util.Locale.ROOT) : "help";
            if (action.equals("list")) {
                service.listCodes(Instant.now()).whenComplete((items,failure) -> main(() -> {
                    if (failure != null) { sender.sendMessage(messages.component("discount-code.failed")); return; }
                    if (items.isEmpty()) sender.sendMessage(messages.component("discount-code.empty"));
                    for (Promotion p : items) sender.sendMessage(messages.component("discount-code.entry",Map.of(
                        "id",p.promotionId(),"code",p.discountCode(),"off",p.percentOffBps()/100.0,
                        "state",!p.status().equals("ACTIVE") || !p.endsAt().isAfter(Instant.now()) ? "已失效" : p.startsAt().isAfter(Instant.now()) ? "未开始" : "生效中")));
                })); return;
            }
            if (action.equals("delete") && args.length == 3) {
                service.deleteCode(UUID.fromString(args[2])).whenComplete((deleted,failure) -> main(() ->
                    sender.sendMessage(messages.component(failure == null && deleted ? "discount-code.deleted" : "discount-code.failed")))); return;
            }
            boolean update = action.equals("edit"); int offset = update ? 1 : 0;
            if (!(action.equals("create") || update) || args.length < 7+offset) throw new IllegalArgumentException();
            String code = args[2+offset].equalsIgnoreCase("random") ? UUID.randomUUID().toString().replace("-", "").substring(0,12).toUpperCase(java.util.Locale.ROOT) : args[2+offset];
            int off = new BigDecimal(args[3+offset].replace("%", "")).multiply(BigDecimal.valueOf(100)).intValueExact();
            String tier = args[4+offset].equalsIgnoreCase("all") ? null : args[4+offset];
            if (tier != null && config.membership().tiers().stream().noneMatch(t -> t.key().equals(tier))) throw new IllegalArgumentException();
            Instant starts = args[5+offset].equalsIgnoreCase("now") ? Instant.now() : LocalDateTime.parse(args[5+offset]).atZone(timezone).toInstant();
            Instant ends = args[6+offset].equalsIgnoreCase("permanent") ? Instant.parse("9999-12-31T00:00:00Z") : LocalDateTime.parse(args[6+offset]).atZone(timezone).toInstant();
            CreatePromotionRequest request = new CreatePromotionRequest(code,tier,off,starts,ends,0,sender.getName(),"管理折扣代码",code, args.length>7+offset && !args[7+offset].equalsIgnoreCase("unlimited") ? Integer.valueOf(args[7+offset]) : null,
                args.length>8+offset && !args[8+offset].equalsIgnoreCase("unlimited") ? Integer.valueOf(args[8+offset]) : null);
            (update ? service.updateCode(UUID.fromString(args[2]),request) : service.create(request)).whenComplete((p,failure) -> main(() ->
                sender.sendMessage(failure == null ? messages.component("discount-code.saved",Map.of("code",p.discountCode(),"id",p.promotionId())) : messages.component("discount-code.failed"))));
        } catch (IllegalArgumentException failure) {
            sender.sendMessage(messages.component("discount-code.usage"));
        }
    }

    private void redeem(CommandSender sender, String[] args) {
        RedeemCodeService service = providers.find(RedeemCodeServiceProvider.KEY).orElse(null);
        if (service == null) {
            sender.sendMessage(messages.component("admin.redeem-service-unavailable"));
            return;
        }
        if (args.length < 7 || !args[1].equalsIgnoreCase("generate")) {
            redeemUsage(sender);
            return;
        }
        try {
            String type = args[2].toLowerCase(java.util.Locale.ROOT);
            int count = Integer.parseInt(args[3]);
            int maxUses = Integer.parseInt(args[4]);
            if (count < 1 || count > 100) {
                throw new IllegalArgumentException();
            }
            RedeemReward reward;
            int validityIndex;
            int noteIndex;
            if (type.equals("points")) {
                reward = RedeemReward.points(Integer.parseInt(args[5]));
                validityIndex = 6;
                noteIndex = 7;
            } else if (type.equals("membership")) {
                if (args.length < 8) throw new IllegalArgumentException();
                reward = RedeemReward.membership(args[5], Integer.parseInt(args[6]));
                validityIndex = 7;
                noteIndex = 8;
            } else {
                throw new IllegalArgumentException();
            }
            Instant now = Instant.now();
            Instant until = validity(args[validityIndex], now);
            String note = args.length > noteIndex
                ? String.join(" ", Arrays.copyOfRange(args, noteIndex, args.length))
                : messages.raw("admin.default-redeem-note");
            service.generate(new GenerateRedeemCodesRequest(
                count, maxUses, now, until, sender.getName(), note, List.of(reward)
            )).whenComplete((batch, failure) -> main(() -> {
                if (failure != null) {
                    logger.warn("Unable to generate redeem codes", failure);
                    sender.sendMessage(messages.component("admin.redeem-generate-failed"));
                    return;
                }
                sender.sendMessage(messages.component("admin.redeem-generated", Map.of(
                    "count", batch.codes().size()
                )));
                batch.codes().forEach(code -> sender.sendMessage(messages.component(
                    "admin.redeem-code", Map.of("code", code)
                )));
                sender.sendMessage(messages.component("admin.redeem-save-now"));
            }));
        } catch (IllegalArgumentException exception) {
            redeemUsage(sender);
        }
    }

    private static Instant validity(String value, Instant now) {
        if (value.equalsIgnoreCase("never")) {
            return null;
        }
        int days = Integer.parseInt(value);
        if (days < 1 || days > 3650) {
            throw new IllegalArgumentException();
        }
        return now.plus(Duration.ofDays(days));
    }

    private void member(CommandSender sender, String[] args) {
        IdentityService identities = providers.find(IdentityProvider.KEY).orElse(null);
        MembershipService memberships = providers.find(MembershipServiceProvider.KEY).orElse(null);
        if (identities == null || memberships == null) {
            sender.sendMessage(messages.component("admin.membership-service-unavailable"));
            return;
        }
        String action = args[1].toLowerCase(java.util.Locale.ROOT);
        if (args.length < 3) {
            memberUsage(sender);
            return;
        }
        String playerName = args[2];
        identities.findByName(playerName).whenComplete((identity, identityFailure) -> {
            if (identityFailure != null || identity.isEmpty()) {
                main(() -> sender.sendMessage(messages.component("admin.player-not-found")));
                return;
            }
            try {
                if (action.equals("info")) {
                    memberships.summary(identity.orElseThrow().playerUuid())
                        .whenComplete((summary, failure) -> memberResult(sender, playerName, summary, failure));
                    return;
                }
                AdminMembershipRequest request = adminRequest(
                    action,
                    identity.orElseThrow().playerUuid(),
                    sender.getName(),
                    args
                );
                memberships.admin(request)
                    .whenComplete((summary, failure) -> memberResult(sender, playerName, summary, failure));
            } catch (IllegalArgumentException exception) {
                main(() -> memberUsage(sender));
            }
        });
    }

    private AdminMembershipRequest adminRequest(
        String action,
        UUID playerUuid,
        String actor,
        String[] args
    ) {
        return switch (action) {
            case "grant" -> {
                if (args.length < 6) throw new IllegalArgumentException();
                yield new AdminMembershipRequest(
                    AdminMembershipAction.GRANT,
                    playerUuid,
                    tierKey(args[3]),
                    Integer.parseInt(args[4]),
                    null,
                    actor,
                    String.join(" ", Arrays.copyOfRange(args, 5, args.length))
                );
            }
            case "extend" -> {
                if (args.length < 6) throw new IllegalArgumentException();
                yield new AdminMembershipRequest(
                    AdminMembershipAction.EXTEND,
                    playerUuid,
                    tierKey(args[3]),
                    Integer.parseInt(args[4]),
                    null,
                    actor,
                    String.join(" ", Arrays.copyOfRange(args, 5, args.length))
                );
            }
            case "reclaim", "remove" -> {
                if (args.length < 5) throw new IllegalArgumentException();
                yield new AdminMembershipRequest(
                    AdminMembershipAction.RECLAIM,
                    playerUuid,
                    tierKey(args[3]),
                    0,
                    null,
                    actor,
                    String.join(" ", Arrays.copyOfRange(args, 4, args.length))
                );
            }
            case "pause", "resume" -> {
                if (args.length < 4) throw new IllegalArgumentException();
                AdminMembershipAction mapped = switch (action) {
                    case "pause" -> AdminMembershipAction.PAUSE;
                    default -> AdminMembershipAction.RESUME;
                };
                yield new AdminMembershipRequest(
                    mapped,
                    playerUuid,
                    null,
                    0,
                    null,
                    actor,
                    String.join(" ", Arrays.copyOfRange(args, 3, args.length))
                );
            }
            case "expiry" -> {
                if (args.length < 6) throw new IllegalArgumentException();
                Instant expiry = LocalDateTime.parse(args[3] + " " + args[4], INPUT)
                    .atZone(timezone).toInstant();
                yield new AdminMembershipRequest(
                    AdminMembershipAction.SET_EXPIRY,
                    playerUuid,
                    null,
                    0,
                    expiry,
                    actor,
                    String.join(" ", Arrays.copyOfRange(args, 5, args.length))
                );
            }
            default -> throw new IllegalArgumentException();
        };
    }

    private void memberResult(
        CommandSender sender,
        String playerName,
        MembershipSummary summary,
        Throwable failure
    ) {
        main(() -> {
            if (failure != null) {
                logger.warn("Membership admin operation failed", failure);
                sender.sendMessage(messages.component("admin.member-operation-failed"));
                return;
            }
            if (summary.active() == null) {
                sender.sendMessage(messages.component("admin.member-none", Map.of(
                    "player", playerName
                )));
            } else {
                sender.sendMessage(messages.component("admin.member-active", Map.of(
                    "player", playerName,
                    "tier", summary.active().tier().displayName(),
                    "expiry", date(summary.active().expiresAt())
                )));
            }
            if (!summary.paused().isEmpty()) {
                sender.sendMessage(messages.component("admin.member-paused", Map.of(
                    "count", summary.paused().size()
                )));
            }
            if (!"APPLIED".equals(summary.permissionSyncStatus())) {
                sender.sendMessage(messages.component("admin.member-syncing"));
            }
        });
    }

    private void create(CommandSender sender, String[] args, PromotionService service) {
        if (args.length < 7 || !args[2].equalsIgnoreCase("membership")) {
            throw new IllegalArgumentException("Invalid discount command");
        }
        int offBps = new BigDecimal(args[3].replace("%", ""))
            .multiply(BigDecimal.valueOf(100))
            .setScale(0, RoundingMode.UNNECESSARY)
            .intValueExact();
        Instant end = LocalDateTime.parse(args[4] + " " + args[5], INPUT)
            .atZone(timezone)
            .toInstant();
        Instant now = Instant.now();
        if (!end.isAfter(now) || offBps < 1 || offBps > 10_000) {
            throw new IllegalArgumentException("Invalid discount value");
        }
        String target = args[6].equalsIgnoreCase("all") ? null : args[6].toLowerCase(java.util.Locale.ROOT);
        String reason = args.length > 7
            ? String.join(" ", Arrays.copyOfRange(args, 7, args.length))
            : messages.raw("admin.default-promotion-note");
        String name = trimName((offBps / 100.0) + "% OFF");
        service.create(new CreatePromotionRequest(
            name, target, offBps, now, end, 0, sender.getName(), reason
        )).whenComplete((promotion, failure) -> main(() -> {
            if (failure != null) {
                logger.warn("Unable to create membership promotion", failure);
                sender.sendMessage(messages.component("admin.promotion-create-failed"));
            } else {
                sender.sendMessage(messages.component("admin.promotion-created", Map.of(
                    "id", shortId(promotion),
                    "percent", percent(promotion),
                    "expiry", date(promotion.endsAt())
                )));
            }
        }));
    }

    private void stop(CommandSender sender, String[] args, PromotionService service) {
        if (args.length < 4) {
            throw new IllegalArgumentException("Stop reason is required");
        }
        UUID id = UUID.fromString(args[2]);
        String reason = String.join(" ", Arrays.copyOfRange(args, 3, args.length));
        service.stop(id, sender.getName(), reason, Instant.now()).whenComplete((stopped, failure) -> main(() -> {
            if (failure != null) {
                logger.warn("Unable to stop membership promotion", failure);
                sender.sendMessage(messages.component("admin.promotion-stop-failed"));
            } else {
                sender.sendMessage(messages.component(stopped
                    ? "admin.promotion-stopped" : "admin.promotion-not-active"));
            }
        }));
    }

    private void list(CommandSender sender, PromotionService service) {
        service.listCurrent(Instant.now()).whenComplete((promotions, failure) -> main(() -> {
            if (failure != null) {
                logger.warn("Unable to list membership promotions", failure);
                sender.sendMessage(messages.component("admin.promotion-list-failed"));
            } else if (promotions.isEmpty()) {
                sender.sendMessage(messages.component("admin.promotion-list-empty"));
            } else {
                promotions.forEach(promotion -> sender.sendMessage(messages.component(
                    "admin.promotion-list-line", Map.of(
                        "id", promotion.promotionId(),
                        "percent", percent(promotion),
                        "target", target(promotion),
                        "expiry", date(promotion.endsAt())
                    )
                )));
            }
        }));
    }

    private void info(CommandSender sender, String[] args, PromotionService service) {
        if (args.length != 3) {
            throw new IllegalArgumentException("Promotion id is required");
        }
        service.find(UUID.fromString(args[2])).whenComplete((promotion, failure) -> main(() -> {
            if (failure != null) {
                sender.sendMessage(messages.component("admin.promotion-not-found"));
            } else {
                sender.sendMessage(messages.component("admin.promotion-info-id", Map.of(
                    "id", promotion.promotionId()
                )));
                sender.sendMessage(messages.component("admin.promotion-info-summary", Map.of(
                    "target", target(promotion), "percent", percent(promotion)
                )));
                sender.sendMessage(messages.component("admin.promotion-info-state", Map.of(
                    "expiry", date(promotion.endsAt()), "status", promotion.status()
                )));
            }
        }));
    }

    @Override
    public List<String> onTabComplete(
        CommandSender sender,
        Command command,
        String alias,
        String[] args
    ) {
        if (args.length == 1) {
            return List.of("help", "reload", "discountcode", "discount", "member", "redeem", "prefix", "suffix");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("discountcode")) return List.of("create","edit","delete","list");
        if (args.length == 2 && (args[0].equalsIgnoreCase("prefix")
            || args[0].equalsIgnoreCase("suffix"))) {
            return List.of("add", "remove", "list");
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("prefix")) {
            return pluginPlayerNames();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("redeem")) {
            return List.of("generate");
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("redeem")) {
            return List.of("points", "membership");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("member")) {
            return List.of(
                "info", "grant", "extend", "reclaim", "remove", "pause", "resume", "expiry"
            );
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("member")
            && List.of("grant", "extend", "reclaim", "remove")
                .contains(args[1].toLowerCase(java.util.Locale.ROOT))) {
            return List.of("vip", "vip_plus", "mvp", "mvp_plus");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("discount")) {
            return List.of("create", "stop", "list", "info");
        }
        if (args.length == 3 && args[1].equalsIgnoreCase("create")) {
            return List.of("membership");
        }
        if (args.length == 7 && args[1].equalsIgnoreCase("create")) {
            return List.of("all", "vip", "vip_plus", "mvp", "mvp_plus");
        }
        return List.of();
    }

    private void main(Runnable action) {
        platformTasks.callMain(() -> {
            action.run();
            return null;
        });
    }

    private String date(Instant instant) {
        return OUTPUT.format(instant.atZone(timezone));
    }

    private static String percent(Promotion promotion) {
        return BigDecimal.valueOf(promotion.percentOffBps(), 2).stripTrailingZeros().toPlainString();
    }

    private String target(Promotion promotion) {
        return promotion.targetTierKey() == null
            ? messages.raw("admin.all-tiers") : promotion.targetTierKey();
    }

    private static String shortId(Promotion promotion) {
        return promotion.promotionId().toString().substring(0, 8);
    }

    private static String trimName(String value) {
        return value.length() <= 64 ? value : value.substring(0, 64);
    }

    private static String tierKey(String value) {
        return switch (value.toLowerCase(java.util.Locale.ROOT)) {
            case "vip+", "vipp", "vip_plus" -> "vip_plus";
            case "mvp+", "mvpp", "mvp_plus" -> "mvp_plus";
            case "vip" -> "vip";
            case "mvp" -> "mvp";
            default -> throw new IllegalArgumentException("Unknown membership tier");
        };
    }

    private void usage(CommandSender sender) {
        messages.components("commands.admin-help").forEach(sender::sendMessage);
    }

    private void prefix(CommandSender sender, String[] args) {
        PaperTitleService.AffixType type = PaperTitleService.AffixType.from(args[0]);
        String action = args[1].toLowerCase(java.util.Locale.ROOT);
        if (args.length < 3 || !List.of("add", "remove", "list").contains(action)) {
            usage(sender);
            return;
        }
        IdentityService identities = providers.find(IdentityProvider.KEY).orElse(null);
        if (identities == null) {
            sender.sendMessage(messages.component("admin.player-not-found"));
            return;
        }
        String playerName = args[2];
        identities.findByName(playerName).whenComplete((identity, lookupFailure) -> {
            if (lookupFailure != null || identity.isEmpty()) {
                main(() -> sender.sendMessage(messages.component("admin.player-not-found")));
                return;
            }
            UUID uuid = identity.orElseThrow().playerUuid();
            if (action.equals("add")) {
                if (args.length < 5) {
                    main(() -> usage(sender));
                    return;
                }
                try {
                    int days = Integer.parseInt(args[3]);
                    String title = String.join(" ", Arrays.copyOfRange(args, 4, args.length));
                    titles.grant(uuid, playerName, type, title, days, sender.getName())
                        .whenComplete((id, failure) -> main(() -> {
                            if (failure != null) {
                                logger.warn("Unable to grant personal title", failure);
                                sender.sendMessage(messages.component("admin.prefix-failed"));
                            } else {
                                sender.sendMessage(messages.component("admin.prefix-added", Map.of(
                                    "player", playerName, "title", title, "id", id
                                )));
                            }
                        }));
                } catch (NumberFormatException exception) {
                    main(() -> usage(sender));
                }
                return;
            }
            if (action.equals("remove")) {
                if (args.length < 4) {
                    main(() -> usage(sender));
                    return;
                }
                String query = String.join(" ", Arrays.copyOfRange(args, 3, args.length));
                titles.remove(uuid, type, query).whenComplete((removed, failure) -> main(() -> {
                    if (failure != null) {
                        logger.warn("Unable to remove personal title", failure);
                        sender.sendMessage(messages.component("admin.prefix-failed"));
                    } else {
                        sender.sendMessage(messages.component(removed
                            ? "admin.prefix-removed" : "admin.prefix-not-found", Map.of(
                                "player", playerName, "title", query
                            )));
                    }
                }));
                return;
            }
            titles.list(uuid, type).whenComplete((entries, failure) -> main(() -> {
                if (failure != null) {
                    logger.warn("Unable to list personal titles", failure);
                    sender.sendMessage(messages.component("admin.prefix-failed"));
                    return;
                }
                sender.sendMessage(messages.component("admin.prefix-list", Map.of(
                    "player", playerName, "count", entries.size()
                )));
                entries.forEach(entry -> sender.sendMessage(messages.component(
                    "admin.prefix-entry", Map.of(
                        "id", entry.id(), "title", entry.display(),
                        "expiry", entry.expiresAt() == null
                            ? messages.raw("titles.permanent") : entry.expiresAt().toString()
                    )
                )));
            }));
        });
    }

    private List<String> pluginPlayerNames() {
        return org.bukkit.Bukkit.getOnlinePlayers().stream().map(org.bukkit.entity.Player::getName).toList();
    }

    private void memberUsage(CommandSender sender) {
        usage(sender);
    }

    private void redeemUsage(CommandSender sender) {
        usage(sender);
    }
}
