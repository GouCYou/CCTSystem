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
import java.util.UUID;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

final class PaperPromotionAdminCommand implements CommandExecutor, TabCompleter {
    private static final DateTimeFormatter INPUT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter OUTPUT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final ProviderRegistry providers;
    private final PlatformTaskExecutor platformTasks;
    private final CctLogger logger;
    private final ZoneId timezone;

    PaperPromotionAdminCommand(
        ProviderRegistry providers,
        PlatformTaskExecutor platformTasks,
        CctConfig config,
        CctLogger logger
    ) {
        this.providers = providers;
        this.platformTasks = platformTasks;
        this.logger = logger;
        this.timezone = ZoneId.of(config.promotion().timezone());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("cctsystem.admin")) {
            sender.sendMessage("§c没有权限");
            return true;
        }
        if (args.length >= 2 && args[0].equalsIgnoreCase("member")) {
            member(sender, args);
            return true;
        }
        if (args.length >= 2 && args[0].equalsIgnoreCase("redeem")) {
            redeem(sender, args);
            return true;
        }
        if (args.length < 2 || !args[0].equalsIgnoreCase("discount")) {
            usage(sender);
            return true;
        }
        PromotionService service = providers.find(PromotionServiceProvider.KEY).orElse(null);
        if (service == null) {
            sender.sendMessage("§c折扣服务仅在业务节点可用");
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
            sender.sendMessage("§c参数无效");
            usage(sender);
        }
        return true;
    }

    private void redeem(CommandSender sender, String[] args) {
        RedeemCodeService service = providers.find(RedeemCodeServiceProvider.KEY).orElse(null);
        if (service == null) {
            sender.sendMessage("§c兑换码服务仅在业务节点可用");
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
                : "管理员生成";
            service.generate(new GenerateRedeemCodesRequest(
                count, maxUses, now, until, sender.getName(), note, List.of(reward)
            )).whenComplete((batch, failure) -> main(() -> {
                if (failure != null) {
                    logger.warn("Unable to generate redeem codes", failure);
                    sender.sendMessage("§c生成兑换码失败");
                    return;
                }
                sender.sendMessage("§a已生成 " + batch.codes().size() + " 个兑换码");
                batch.codes().forEach(code -> sender.sendMessage("§f" + code));
                sender.sendMessage("§7兑换码仅在这里显示一次，请立即保存");
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
            sender.sendMessage("§c会员服务仅在业务节点可用");
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
                main(() -> sender.sendMessage("§c未找到该玩家"));
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
                    args[3].toLowerCase(java.util.Locale.ROOT),
                    Integer.parseInt(args[4]),
                    null,
                    actor,
                    String.join(" ", Arrays.copyOfRange(args, 5, args.length))
                );
            }
            case "extend" -> {
                if (args.length < 5) throw new IllegalArgumentException();
                yield new AdminMembershipRequest(
                    AdminMembershipAction.EXTEND,
                    playerUuid,
                    null,
                    Integer.parseInt(args[3]),
                    null,
                    actor,
                    String.join(" ", Arrays.copyOfRange(args, 4, args.length))
                );
            }
            case "remove", "pause", "resume" -> {
                if (args.length < 4) throw new IllegalArgumentException();
                AdminMembershipAction mapped = switch (action) {
                    case "remove" -> AdminMembershipAction.REMOVE;
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
                sender.sendMessage("§c会员操作失败");
                return;
            }
            if (summary.active() == null) {
                sender.sendMessage("§f" + playerName + " §7暂无生效会员");
            } else {
                sender.sendMessage("§f" + playerName + " §a" + summary.active().tier().displayName()
                    + " §7到期 " + date(summary.active().expiresAt()));
            }
            if (!summary.paused().isEmpty()) {
                sender.sendMessage("§7已暂停 " + summary.paused().size() + " 项");
            }
            if (!"APPLIED".equals(summary.permissionSyncStatus())) {
                sender.sendMessage("§eLuckPerms 权限同步中");
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
            : "管理员活动";
        String name = trimName((offBps / 100.0) + "% OFF");
        service.create(new CreatePromotionRequest(
            name, target, offBps, now, end, 0, sender.getName(), reason
        )).whenComplete((promotion, failure) -> main(() -> {
            if (failure != null) {
                logger.warn("Unable to create membership promotion", failure);
                sender.sendMessage("§c创建折扣失败，请检查等级与时间");
            } else {
                sender.sendMessage("§a折扣已开启 §7" + shortId(promotion)
                    + " §f减" + percent(promotion) + "% §7至 " + date(promotion.endsAt()));
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
                sender.sendMessage("§c停止折扣失败");
            } else {
                sender.sendMessage(stopped ? "§a折扣已停止" : "§e折扣不存在或已经停止");
            }
        }));
    }

    private void list(CommandSender sender, PromotionService service) {
        service.listCurrent(Instant.now()).whenComplete((promotions, failure) -> main(() -> {
            if (failure != null) {
                logger.warn("Unable to list membership promotions", failure);
                sender.sendMessage("§c读取折扣失败");
            } else if (promotions.isEmpty()) {
                sender.sendMessage("§7当前没有折扣");
            } else {
                promotions.forEach(promotion -> sender.sendMessage(
                    "§f" + promotion.promotionId() + " §a减" + percent(promotion)
                        + "% §7" + target(promotion) + " → " + date(promotion.endsAt())
                ));
            }
        }));
    }

    private void info(CommandSender sender, String[] args, PromotionService service) {
        if (args.length != 3) {
            throw new IllegalArgumentException("Promotion id is required");
        }
        service.find(UUID.fromString(args[2])).whenComplete((promotion, failure) -> main(() -> {
            if (failure != null) {
                sender.sendMessage("§c未找到折扣");
            } else {
                sender.sendMessage("§f" + promotion.promotionId());
                sender.sendMessage("§7范围 §f" + target(promotion)
                    + " §7折扣 §a减" + percent(promotion) + "%");
                sender.sendMessage("§7结束 §f" + date(promotion.endsAt())
                    + " §7状态 §f" + promotion.status());
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
            return List.of("discount", "member", "redeem");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("redeem")) {
            return List.of("generate");
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("redeem")) {
            return List.of("points", "membership");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("member")) {
            return List.of("info", "grant", "extend", "remove", "pause", "resume", "expiry");
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

    private static String target(Promotion promotion) {
        return promotion.targetTierKey() == null ? "全部等级" : promotion.targetTierKey();
    }

    private static String shortId(Promotion promotion) {
        return promotion.promotionId().toString().substring(0, 8);
    }

    private static String trimName(String value) {
        return value.length() <= 64 ? value : value.substring(0, 64);
    }

    private static void usage(CommandSender sender) {
        sender.sendMessage("§7/cctadmin discount create membership <off%> <yyyy-MM-dd> <HH:mm> <all|等级> [备注]");
        sender.sendMessage("§7/cctadmin discount <stop|info> <完整ID> [原因]");
        sender.sendMessage("§7/cctadmin discount list");
        memberUsage(sender);
        redeemUsage(sender);
    }

    private static void memberUsage(CommandSender sender) {
        sender.sendMessage("§7/cctadmin member info <玩家>");
        sender.sendMessage("§7/cctadmin member grant <玩家> <等级> <天数> <原因>");
        sender.sendMessage("§7/cctadmin member extend <玩家> <天数> <原因>");
        sender.sendMessage("§7/cctadmin member <remove|pause|resume> <玩家> <原因>");
        sender.sendMessage("§7/cctadmin member expiry <玩家> <yyyy-MM-dd> <HH:mm> <原因>");
    }

    private static void redeemUsage(CommandSender sender) {
        sender.sendMessage("§7/cctadmin redeem generate points <数量> <每码次数> <点券> <有效天数|never> [备注]");
        sender.sendMessage("§7/cctadmin redeem generate membership <数量> <每码次数> <等级> <会员天数> <有效天数|never> [备注]");
    }
}
