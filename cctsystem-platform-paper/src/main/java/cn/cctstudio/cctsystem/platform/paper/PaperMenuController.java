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
    private final PaperMessages messages;
    private final PaperNetworkChat networkChat;
    private final PaperRechargeMenu recharge;

    PaperMenuController(
        PaperMembershipMenu memberships,
        PaperExchangeMenu exchange,
        ProviderRegistry providers,
        PlatformTaskExecutor platformTasks,
        CctLogger logger,
        PaperMessages messages,
        PaperNetworkChat networkChat,
        PaperRechargeMenu recharge
    ) {
        this.memberships = memberships;
        this.exchange = exchange;
        this.providers = providers;
        this.platformTasks = platformTasks;
        this.logger = logger;
        this.messages = messages;
        this.networkChat = networkChat;
        this.recharge = recharge;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] arguments) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messages.component("commands.only-player"));
            return true;
        }
        if (command.getName().equalsIgnoreCase("shout")) {
            if (arguments.length == 0) {
                player.sendMessage(messages.component("chat.shout-usage"));
            } else {
                networkChat.shout(player, String.join(" ", arguments));
            }
            return true;
        }
        if (arguments.length == 0) {
            memberships.openPersonal(player);
            return true;
        }
        if (arguments[0].equalsIgnoreCase("help")) {
            messages.components("commands.player-help").forEach(player::sendMessage);
            return true;
        }
        if (arguments[0].equalsIgnoreCase("me")) {
            memberships.openPersonal(player);
            return true;
        }
        if (arguments[0].equalsIgnoreCase("vip")) {
            memberships.openMemberships(player);
            return true;
        }
        if (arguments[0].equalsIgnoreCase("recharge")) {
            recharge.open(player);
            return true;
        }
        if (arguments[0].equalsIgnoreCase("exchange")) {
            if (arguments.length == 1) {
                exchange.open(player);
            } else if (arguments.length == 2) {
                exchange.executeCommand(player, arguments[1]);
            } else {
                player.sendMessage(messages.component("menus.exchange.usage"));
            }
            return true;
        }
        if (arguments[0].equalsIgnoreCase("redeem")) {
            if (arguments.length != 2) {
                player.sendMessage(messages.component("redeem.usage"));
            } else {
                redeem(player, arguments[1]);
            }
            return true;
        }
        if (arguments[0].equalsIgnoreCase("shout")) {
            if (arguments.length < 2) {
                player.sendMessage(messages.component("chat.shout-usage"));
            } else {
                networkChat.shout(player, String.join(" ", java.util.Arrays.copyOfRange(
                    arguments, 1, arguments.length
                )));
            }
            return true;
        }
        messages.components("commands.player-help").forEach(player::sendMessage);
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
            return List.of("help", "me", "vip", "recharge", "exchange", "redeem", "shout");
        }
        if (arguments.length == 2 && arguments[0].equalsIgnoreCase("exchange")) {
            return List.of("1", "10", "100", "max");
        }
        return List.of();
    }

    private void redeem(Player player, String code) {
        RedeemCodeService service = providers.find(RedeemCodeServiceProvider.KEY).orElse(null);
        if (service == null) {
            player.sendMessage(messages.component("redeem.service-unavailable"));
            return;
        }
        player.sendMessage(messages.component("redeem.processing"));
        service.redeem(new RedeemRequest(
            player.getUniqueId(), code, "game:" + UUID.randomUUID(), "GAME_COMMAND"
        )).whenComplete((result, failure) -> platformTasks.callMain(() -> {
            if (!player.isOnline()) {
                return null;
            }
            if (failure != null) {
                logger.warn("Redeem command failed", failure);
                player.sendMessage(messages.component("redeem.failed"));
            } else {
                showResult(player, result);
            }
            return null;
        }));
    }

    private void showResult(Player player, RedeemResult result) {
        if ("COMPLETED".equals(result.status())) {
            player.sendMessage(messages.component("redeem.completed"));
            result.rewards().forEach(reward -> player.sendMessage(messages.component(
                "redeem.reward", java.util.Map.of("reward", reward.description())
            )));
        } else if ("REVIEW_REQUIRED".equals(result.status())) {
            player.sendMessage(messages.component("redeem.review-required"));
        } else {
            player.sendMessage(messages.component("redeem.incomplete"));
        }
    }
}
