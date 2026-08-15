package cn.cctstudio.cctsystem.platform.paper;

import cn.cctstudio.cctsystem.core.concurrent.PlatformTaskExecutor;
import cn.cctstudio.cctsystem.core.logging.CctLogger;
import cn.cctstudio.cctsystem.core.provider.ProviderRegistry;
import cn.cctstudio.cctsystem.redeem.RedeemCodeService;
import cn.cctstudio.cctsystem.redeem.RedeemCodeServiceProvider;
import cn.cctstudio.cctsystem.redeem.RedeemRequest;
import cn.cctstudio.cctsystem.redeem.RedeemResult;
import java.util.List;
import java.util.UUID;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

final class PaperMenuController implements CommandExecutor, TabCompleter {
    private final PaperMembershipMenu memberships;
    private final PaperExchangeMenu exchange;
    private final ProviderRegistry providers;
    private final PlatformTaskExecutor platformTasks;
    private final CctLogger logger;

    PaperMenuController(
        PaperMembershipMenu memberships,
        PaperExchangeMenu exchange,
        ProviderRegistry providers,
        PlatformTaskExecutor platformTasks,
        CctLogger logger
    ) {
        this.memberships = memberships;
        this.exchange = exchange;
        this.providers = providers;
        this.platformTasks = platformTasks;
        this.logger = logger;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] arguments) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("该命令只能由玩家使用");
            return true;
        }
        if (arguments.length == 0 || arguments[0].equalsIgnoreCase("me")) {
            memberships.openPersonal(player);
            return true;
        }
        if (arguments[0].equalsIgnoreCase("vip")) {
            memberships.openMemberships(player);
            return true;
        }
        if (arguments[0].equalsIgnoreCase("exchange")) {
            if (arguments.length == 1) {
                exchange.open(player);
            } else if (arguments.length == 2) {
                exchange.executeCommand(player, arguments[1]);
            } else {
                player.sendMessage("§7使用 /cct exchange [1|10|100|max]");
            }
            return true;
        }
        if (arguments[0].equalsIgnoreCase("redeem")) {
            if (arguments.length != 2) {
                player.sendMessage("§7使用 /cct redeem <兑换码>");
            } else {
                redeem(player, arguments[1]);
            }
            return true;
        }
        player.sendMessage("§7使用 /cct [me|vip|exchange|redeem]");
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
            return List.of("me", "vip", "exchange", "redeem");
        }
        if (arguments.length == 2 && arguments[0].equalsIgnoreCase("exchange")) {
            return List.of("1", "10", "100", "max");
        }
        return List.of();
    }

    private void redeem(Player player, String code) {
        RedeemCodeService service = providers.find(RedeemCodeServiceProvider.KEY).orElse(null);
        if (service == null) {
            player.sendMessage("§c兑换码服务暂不可用");
            return;
        }
        player.sendMessage("§7正在兑换…");
        service.redeem(new RedeemRequest(
            player.getUniqueId(), code, "game:" + UUID.randomUUID(), "GAME_COMMAND"
        )).whenComplete((result, failure) -> platformTasks.callMain(() -> {
            if (!player.isOnline()) {
                return null;
            }
            if (failure != null) {
                logger.warn("Redeem command failed", failure);
                player.sendMessage("§c兑换失败，请检查兑换码");
            } else {
                showResult(player, result);
            }
            return null;
        }));
    }

    private static void showResult(Player player, RedeemResult result) {
        if ("COMPLETED".equals(result.status())) {
            player.sendMessage("§a兑换成功");
            result.rewards().forEach(reward -> player.sendMessage("§7获得 §f" + reward.description()));
        } else if ("REVIEW_REQUIRED".equals(result.status())) {
            player.sendMessage("§e兑换处理中，请勿重复提交");
        } else {
            player.sendMessage("§c兑换未完成");
        }
    }
}
