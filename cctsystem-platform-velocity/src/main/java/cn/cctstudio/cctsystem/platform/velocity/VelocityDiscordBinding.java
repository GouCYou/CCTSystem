package cn.cctstudio.cctsystem.platform.velocity;

import cn.cctstudio.cctsystem.bridge.BridgeCallException;
import cn.cctstudio.cctsystem.bridge.BridgeRpcClient;
import cn.cctstudio.cctsystem.contract.Capability;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;

final class VelocityDiscordBinding implements SimpleCommand {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final BridgeRpcClient bridge;
    private final Set<UUID> pending = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Instant> unbindPrompts = new ConcurrentHashMap<>();

    VelocityDiscordBinding(BridgeRpcClient bridge) {
        this.bridge = bridge;
    }

    @Override
    public void execute(Invocation invocation) {
        if (!(invocation.source() instanceof Player player)) return;
        if (!canBind(player)) {
            player.sendMessage(green("请先登录并进入游戏子服，再绑定 Discord。"));
            return;
        }
        String[] args = invocation.arguments();
        UUID id = player.getUniqueId();
        if (args.length == 1 && "unbind".equalsIgnoreCase(args[0])) {
            Instant now = Instant.now();
            unbindPrompts.values().removeIf(expiry -> !expiry.isAfter(now));
            unbindPrompts.put(id, now.plusSeconds(60));
            player.sendMessage(green("解除最后一种社交绑定后，聊天或购买会员可能受限。"));
            player.sendMessage(green("[确认解绑 Discord]").clickEvent(ClickEvent.runCommand("/discordbind unbind confirm")));
            return;
        }
        boolean unbinding = args.length == 2 && "unbind".equalsIgnoreCase(args[0])
            && "confirm".equalsIgnoreCase(args[1]);
        boolean confirming = args.length == 2 && "confirm".equalsIgnoreCase(args[0])
            && args[1].matches("[A-Fa-f0-9]{12}");
        if (args.length != 0 && !confirming && !unbinding) {
            player.sendMessage(green("输入 /discordbind 开始绑定。授权后，请使用浏览器页面显示的确认指令。"));
            return;
        }
        if (unbinding) {
            Instant expiry = unbindPrompts.remove(id);
            if (expiry == null || !expiry.isAfter(Instant.now())) {
                player.sendMessage(green("确认已过期，请重新输入 /discordbind unbind。"));
                return;
            }
        }
        if (!pending.add(id)) {
            player.sendMessage(green("Discord 绑定请求正在处理，请稍候。"));
            return;
        }
        var payload = JSON.createObjectNode().put("playerUuid", id.toString());
        if (confirming) payload.put("code", args[1].toUpperCase(Locale.ROOT));
        String operation = unbinding ? "discord.game.unbind" : confirming ? "discord.game.confirm" : "discord.game.start";
        bridge.call(Capability.ACCOUNT_SECURITY_MUTATE, operation, payload, null, Duration.ofSeconds(15))
            .whenComplete((result, failure) -> {
                pending.remove(id);
                if (!canBind(player)) return;
                if (failure != null) {
                    player.sendMessage(green(errorMessage(failure)));
                    return;
                }
                if (unbinding) {
                    player.sendMessage(green(result.path("unbound").asBoolean(false)
                        ? "Discord 已解绑。" : "当前没有绑定 Discord。"));
                } else if (confirming && result.path("bound").asBoolean(false)) {
                    player.sendMessage(green("Discord 绑定成功。首次绑定奖励将自动发放。"));
                } else if (!confirming) {
                    String url = result.path("url").asText();
                    if (!discordUrl(url)) {
                        player.sendMessage(green("暂时无法打开 Discord 授权，请稍后重试。"));
                        return;
                    }
                    player.sendMessage(green("[打开 Discord 授权]").clickEvent(ClickEvent.openUrl(url))
                        .append(green(" ").clickEvent(null))
                        .append(green("[复制授权链接]").clickEvent(ClickEvent.copyToClipboard(url))));
                    player.sendMessage(green("授权链接 10 分钟内有效。授权后，请按网页提示回到游戏确认。"));
                } else {
                    player.sendMessage(green("暂未确认绑定，请稍后再试。"));
                }
            });
    }

    private static boolean canBind(Player player) {
        return player.isActive() && player.getCurrentServer()
            .map(connection -> !connection.getServerInfo().getName().equalsIgnoreCase("login")).orElse(false);
    }

    private static boolean discordUrl(String value) {
        try {
            URI uri = URI.create(value);
            return "https".equals(uri.getScheme()) && "discord.com".equals(uri.getHost())
                && uri.getUserInfo() == null && uri.getPort() == -1 && "/oauth2/authorize".equals(uri.getPath());
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static String errorMessage(Throwable failure) {
        while (failure instanceof CompletionException && failure.getCause() != null) failure = failure.getCause();
        String code = failure instanceof BridgeCallException bridgeFailure ? bridgeFailure.code() : "";
        return switch (code) {
            case "DISCORD_GAME_INVALID" -> "确认码无效或已过期，请检查网页上的指令，或重新开始绑定。";
            case "DISCORD_GAME_BUSY" -> "Discord 绑定请求正在处理，请稍候。";
            case "DISCORD_ALREADY_BOUND" -> "该 Discord 账号已绑定其他游戏账号。";
            default -> "Discord 绑定服务暂未响应，请稍后重试。";
        };
    }

    private static Component green(String text) {
        return Component.text(text, NamedTextColor.GREEN);
    }
}
