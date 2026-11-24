package cn.cctstudio.cctsystem.platform.paper;

import cn.cctstudio.cctsystem.core.CctRuntime;
import cn.cctstudio.cctsystem.contract.NodeRole;
import cn.cctstudio.cctsystem.exchange.CurrencyGatewayProvider;
import cn.cctstudio.cctsystem.membership.MembershipServiceProvider;
import cn.cctstudio.cctsystem.points.PointsServiceProvider;
import cn.cctstudio.cctsystem.points.PointsMutationDisposition;
import cn.cctstudio.cctsystem.storage.mysql.DatabaseProvider;
import cn.cctstudio.cctsystem.storage.mysql.PlayerMailbox;
import cn.cctstudio.cctsystem.storage.mysql.PlayerMailbox.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

final class PaperMailbox implements Listener {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        .withZone(ZoneId.of("Asia/Shanghai"));
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int PAGE_SIZE = 5;
    private static final String EMPTY_INBOX = "当前没有未读或未领取的邮件。";
    private final PaperBootstrap plugin;
    private final CctRuntime runtime;
    private final PaperTaskExecutor tasks;
    private final boolean lobby;
    private final EnumSet<RewardType> types = EnumSet.noneOf(RewardType.class);
    private final AtomicBoolean running = new AtomicBoolean();
    private final Map<UUID, InboxView> inboxViews = new HashMap<>();

    PaperMailbox(PaperBootstrap plugin, CctRuntime runtime, PaperTaskExecutor tasks) {
        this.plugin = plugin;
        this.runtime = runtime;
        this.tasks = tasks;
        this.lobby = runtime.config().serverId().equals("lobby");
        if (lobby) {
            if (runtime.config().roles().contains(NodeRole.BUSINESS_AUTHORITY)) types.add(RewardType.MEMBERSHIP);
            if (runtime.config().roles().contains(NodeRole.POINTS_AUTHORITY)) types.add(RewardType.POINTS);
        }
        if (runtime.config().serverId().equals("survival") && runtime.config().roles().contains(NodeRole.ECONOMY_SOURCE)) {
            types.add(RewardType.SURVIVAL_COINS);
        }
    }

    boolean enabled() {
        return runtime.config().database().enabled() && !runtime.config().roles().contains(NodeRole.AUTH_AUTHORITY);
    }

    void register() {
        var command = plugin.getCommand("mail");
        if (command != null) command.setExecutor((sender, ignored, label, arguments) -> {
            if (sender instanceof Player player) command(player, arguments);
            else sender.sendMessage(green("请在游戏中使用 /mail。"));
            return true;
        });
        if (!enabled()) return;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        if (lobby || !types.isEmpty()) plugin.getServer().getScheduler().runTaskTimer(plugin, this::poll, 100, 40);
    }

    private void command(Player player, String[] args) {
        if (!enabled()) {
            player.sendMessage(green("进入游戏子服后即可查看邮件。"));
            return;
        }
        if (args.length == 0) {
            open(player);
            return;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("claimall")) {
            claimAll(player);
            return;
        }
        if (args.length == 2) {
            try {
                switch (args[0].toLowerCase(Locale.ROOT)) {
                    case "page" -> {
                        int page = Integer.parseInt(args[1]);
                        if (page > 0) {
                            open(player, page - 1);
                            return;
                        }
                    }
                    case "read", "claim" -> {
                        UUID id = mailId(player, args[1]);
                        if (id == null) player.sendMessage(green("请先输入 /mail，再使用收件箱里的邮件编号。"));
                        else openMail(player, id, args[0].equalsIgnoreCase("claim"));
                        return;
                    }
                    default -> { }
                }
            } catch (IllegalArgumentException ignored) {
                // Invalid clicks and manually entered arguments share the help message.
            }
        }
        player.sendMessage(colored("输入 /mail 查看邮件，或输入 /mail claimall 一键领取。", NamedTextColor.GRAY));
    }

    private UUID mailId(Player player, String argument) {
        if (!argument.matches("[0-9]+")) return UUID.fromString(argument);
        int number = Integer.parseInt(argument);
        InboxView view = inboxViews.get(player.getUniqueId());
        return view != null && number > 0 && number <= view.displayed.size() ? view.displayed.get(number - 1) : null;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        query(() -> store().inboxCount(playerId)).thenAccept(count -> {
            if (count == 0) return;
            tasks.callMain(() -> {
                if (player.isOnline()) player.sendMessage(Component.empty()
                    .append(colored("邮件  ", NamedTextColor.AQUA).decorate(TextDecoration.BOLD))
                    .append(colored("你有 ", NamedTextColor.WHITE))
                    .append(colored(count + " 封", NamedTextColor.GOLD))
                    .append(colored("未读或未领取的邮件  ", NamedTextColor.GRAY))
                    .append(link("/mail 查看邮件", "", NamedTextColor.AQUA)));
                return null;
            });
        }).exceptionally(failure -> {
            plugin.getLogger().fine("Mailbox reminder temporarily unavailable");
            return null;
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        inboxViews.remove(event.getPlayer().getUniqueId());
    }

    void admin(CommandSender sender, String[] args) {
        if (args.length == 2 && args[1].equalsIgnoreCase("list")) {
            respond(sender, query(() -> store().list(null, 0, 15)), mails -> {
                if (mails.isEmpty()) sender.sendMessage(green("暂无邮件。"));
                for (Mail mail : mails) sender.sendMessage(green(mail.id() + " · " + mail.draft().title()
                    + " · " + mail.playerUuid() + " · " + state(mail)));
            });
            return;
        }
        if (args.length < 4 || !List.of("send", "edit").contains(args[1].toLowerCase(Locale.ROOT))) {
            sender.sendMessage(green("用法 /cctadmin mail send <玩家名> <邮件JSON>、mail edit <邮件编号> <邮件JSON>、mail list"));
            return;
        }
        String json = String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length));
        respond(sender, query(() -> {
            Draft draft = JSON.readValue(json, Draft.class);
            if (args[1].equalsIgnoreCase("send")) {
                UUID player = store().resolve(args[2]).orElseThrow(() -> new IllegalArgumentException("找不到该玩家。"));
                return "邮件已发送，编号 " + store().send(UUID.randomUUID(), player, draft, sender.getName());
            }
            return store().edit(UUID.fromString(args[2]), draft) ? "邮件已更新。" : "找不到可编辑的邮件。";
        }), result -> sender.sendMessage(green(result)));
    }

    void open(Player player) {
        open(player, 0);
    }

    private void open(Player player, int requestedPage) {
        UUID playerId = player.getUniqueId();
        InboxView view = inboxViews.computeIfAbsent(playerId, ignored -> new InboxView());
        long request = ++view.request;
        respond(player, query(() -> store().inbox(playerId, requestedPage, PAGE_SIZE)), inbox -> {
            if (request != view.request) return;
            List<Mail> mails = inbox.mails();
            view.page = inbox.page();
            player.sendMessage(panelHeader("收件箱"));
            if (mails.isEmpty()) {
                player.sendMessage(colored(EMPTY_INBOX, NamedTextColor.GRAY));
                return;
            }
            player.sendMessage(colored("待处理 ", NamedTextColor.GRAY)
                .append(colored(inbox.totalMails() + " 封", NamedTextColor.WHITE))
                .append(Component.space())
                .append(link("一键领取", " claimall", NamedTextColor.GOLD)));
            for (Mail mail : mails) {
                int index = view.displayed.indexOf(mail.id());
                if (index < 0) {
                    index = view.displayed.size();
                    view.displayed.add(mail.id());
                }
                player.sendMessage(inboxRow(mail, index + 1));
            }
            player.sendMessage(pageControls(inbox));
            player.sendMessage(colored("/mail read <编号> · /mail claim <编号> · /mail claimall", NamedTextColor.GRAY));
        });
    }

    private static Component inboxRow(Mail mail, int number) {
        Component line = colored(number + ". ", NamedTextColor.GRAY)
            .append(colored(mail.draft().title(), NamedTextColor.WHITE));
        if (mail.readAt() == null) line = line.append(colored("  未读", NamedTextColor.AQUA));
        boolean claimable = hasUnclaimedRewards(mail);
        if (claimable) line = line.append(colored("  待领取", NamedTextColor.GOLD));
        line = line.append(Component.space()).append(link("阅读", " read " + mail.id(), NamedTextColor.AQUA));
        if (claimable) {
            line = line.append(Component.space()).append(link("领取", " claim " + mail.id(), NamedTextColor.GOLD));
        }
        return line;
    }

    private static Component pageControls(InboxPage inbox) {
        int pageNumber = inbox.page() + 1;
        Component previous = pageNumber > 1 ? link("‹ 上一页", " page " + (pageNumber - 1), NamedTextColor.AQUA)
            : colored("[‹ 上一页]", NamedTextColor.GRAY);
        Component next = pageNumber < inbox.totalPages() ? link("下一页 ›", " page " + (pageNumber + 1), NamedTextColor.AQUA)
            : colored("[下一页 ›]", NamedTextColor.GRAY);
        Component line = Component.empty().append(previous)
            .append(colored("  第 " + pageNumber + " / " + inbox.totalPages() + " 页  ", NamedTextColor.WHITE))
            .append(next);
        if (inbox.totalPages() > 1) line = line.append(colored("  /mail page <页码>", NamedTextColor.GRAY));
        return line;
    }

    private void openMail(Player player, UUID id, boolean claim) {
        UUID playerId = player.getUniqueId();
        respond(player, query(() -> claim ? store().claim(playerId, id) : store().read(playerId, id)), result -> {
            if (result.isEmpty()) {
                player.sendMessage(colored(claim ? "此邮件的附件已领取、已过期或不可领取。"
                    : "此邮件已过期或不可查看。", NamedTextColor.YELLOW));
                return;
            }
            Mail mail = result.orElseThrow();
            player.sendMessage(panelHeader("邮件详情"));
            player.sendMessage(colored(mail.draft().title(), NamedTextColor.WHITE).decorate(TextDecoration.BOLD));
            String expiry = mail.draft().expiresAt() == null ? "长期有效"
                : "有效期至 " + DATE.format(Instant.ofEpochMilli(mail.draft().expiresAt()));
            player.sendMessage(colored("发件人 " + mail.sender() + "  ·  " + expiry, NamedTextColor.GRAY));
            mail.draft().body().lines().forEach(line -> player.sendMessage(colored(line, NamedTextColor.WHITE)));
            if (!mail.draft().rewards().isEmpty()) {
                String rewards = String.join("、", mail.draft().rewards().stream().map(Reward::display).toList());
                player.sendMessage(Component.empty().append(colored("附件  ", NamedTextColor.GOLD).decorate(TextDecoration.BOLD))
                    .append(colored(rewards, NamedTextColor.YELLOW)));
            }
            if (claim) player.sendMessage(green("附件已领取，将自动发放。神秘宝库与神秘之尘在大厅到账。"));
            Component actions = Component.empty().append(inboxLink(player, "返回收件箱"));
            if (hasUnclaimedRewards(mail)) {
                actions = actions.append(Component.space()).append(link("领取附件", " claim " + id, NamedTextColor.GOLD));
            }
            player.sendMessage(actions);
        });
    }

    private void claimAll(Player player) {
        UUID playerId = player.getUniqueId();
        respond(player, query(() -> store().claimAll(playerId)), count -> {
            String status = count == 0 ? "当前没有可领取的邮件附件。"
                : "已领取 " + count + " 封邮件的附件，将自动发放。神秘宝库与神秘之尘在大厅到账。";
            player.sendMessage(colored(status + "  ", count == 0 ? NamedTextColor.GRAY : NamedTextColor.GREEN)
                .append(inboxLink(player, "查看收件箱")));
        });
    }

    private Component inboxLink(Player player, String label) {
        InboxView view = inboxViews.get(player.getUniqueId());
        return link(label, view == null ? "" : " page " + (view.page + 1), NamedTextColor.AQUA);
    }

    private static boolean hasUnclaimedRewards(Mail mail) {
        return mail.claimedAt() == null && !mail.draft().rewards().isEmpty();
    }

    private static Component panelHeader(String title) {
        String divider = "━".repeat(12);
        return colored(divider + " ", NamedTextColor.DARK_AQUA)
            .append(colored(title, NamedTextColor.AQUA).decorate(TextDecoration.BOLD))
            .append(colored(" " + divider, NamedTextColor.DARK_AQUA));
    }

    private static Component link(String label, String arguments, NamedTextColor color) {
        return colored("[", NamedTextColor.GRAY)
            .append(colored(label, color).decorate(TextDecoration.UNDERLINED))
            .append(colored("]", NamedTextColor.GRAY))
            .hoverEvent(HoverEvent.showText(colored("点击" + label, color)))
            .clickEvent(ClickEvent.runCommand("/cctsystem:mail" + arguments));
    }

    private void poll() {
        if (!running.compareAndSet(false, true)) return;
        var online = lobby && plugin.getServer().getPluginManager().isPluginEnabled("GadgetsMenu")
            ? plugin.getServer().getOnlinePlayers().stream().map(Player::getUniqueId).toList() : List.<UUID>of();
        query(() -> {
            var store = store();
            store.flagInterrupted();
            var available = types.clone();
            if (runtime.providers().find(CurrencyGatewayProvider.KEY).isEmpty()) available.remove(RewardType.SURVIVAL_COINS);
            if (runtime.providers().find(PointsServiceProvider.KEY).isEmpty()) available.remove(RewardType.POINTS);
            if (runtime.providers().find(MembershipServiceProvider.KEY).isEmpty()) available.remove(RewardType.MEMBERSHIP);
            return store.pending(available, online);
        }).thenCompose(jobs -> {
            CompletionStage<Void> work = CompletableFuture.completedFuture(null);
            for (Attachment job : jobs) {
                work = work.thenCompose(ignored -> dispatch(job).exceptionally(failure -> {
                    plugin.getLogger().warning("Mail attachment requires attention " + job.id() + ": " + failure.getMessage());
                    return null;
                }));
            }
            return work;
        }).whenComplete((ignored, failure) -> {
            running.set(false);
            if (failure != null) plugin.getLogger().fine("Mailbox delivery queue temporarily unavailable");
        });
    }

    private CompletionStage<Void> dispatch(Attachment job) {
        return query(() -> store().change(job.id(), Status.PENDING, Status.RUNNING)).thenCompose(reserved -> {
            if (!reserved) return CompletableFuture.completedFuture(null);
            return CompletableFuture.completedFuture(job).thenCompose(this::deliver)
                .handle((state, failure) -> failure == null ? state : Status.REVIEW)
                .thenCompose(result -> query(() -> store().change(job.id(), Status.RUNNING, result)))
                .thenAccept(ignored -> {});
        });
    }

    private CompletionStage<Status> deliver(Attachment job) {
        Reward reward = job.reward();
        return switch (reward.type()) {
            case POINTS -> runtime.providers().find(PointsServiceProvider.KEY).orElseThrow()
                .credit(job.playerUuid(), reward.amount(), job.id(), "PLAYER_MAIL", job.id().toString())
                .thenApply(result -> status(result.disposition() == PointsMutationDisposition.COMPLETED));
            case SURVIVAL_COINS -> runtime.providers().find(CurrencyGatewayProvider.KEY).orElseThrow()
                .deposit(job.playerUuid(), BigDecimal.valueOf(reward.amount())).thenApply(result -> status(result.successful()));
            case MEMBERSHIP -> runtime.providers().find(MembershipServiceProvider.KEY).orElseThrow()
                .grantMailReward(job.id(), job.playerUuid())
                .thenApply(ignored -> Status.DELIVERED);
            case MYSTERY_BOX, MYSTERY_DUST -> tasks.callMain(() -> {
                Player player = plugin.getServer().getPlayer(job.playerUuid());
                if (player == null) return Status.PENDING;
                String command = reward.type() == RewardType.MYSTERY_BOX ? "gmysterybox give " : "mysterydust add ";
                return status(plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(),
                    command + player.getName() + " " + reward.amount()));
            });
        };
    }

    private static Status status(boolean delivered) {
        return delivered ? Status.DELIVERED : Status.REVIEW;
    }

    private PlayerMailbox store() {
        return new PlayerMailbox(runtime.providers().find(DatabaseProvider.KEY).orElseThrow());
    }

    private <T> CompletableFuture<T> query(Callable<T> task) {
        return CompletableFuture.supplyAsync(() -> {
            try { return task.call(); }
            catch (Exception exception) { throw new CompletionException(exception); }
        }, runtime.executors().blocking());
    }

    private <T> void respond(CommandSender sender, CompletionStage<T> result, java.util.function.Consumer<T> success) {
        result.whenComplete((value, failure) -> tasks.callMain(() -> {
            if (sender instanceof Player player && !player.isOnline()) return null;
            if (failure == null) success.accept(value);
            else {
                Throwable cause = failure.getCause() == null ? failure : failure.getCause();
                sender.sendMessage(green(cause instanceof IllegalArgumentException ? cause.getMessage() : "邮件服务暂不可用。"));
            }
            return null;
        }));
    }

    private static Component green(String text) {
        return colored(text, NamedTextColor.GREEN);
    }

    private static Component colored(String text, NamedTextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }

    private static final class InboxView {
        private final List<UUID> displayed = new ArrayList<>();
        private long request;
        private int page;
    }

    private static String state(Mail mail) {
        if (mail.review() > 0) return "附件待核对";
        if (!mail.draft().rewards().isEmpty() && mail.claimedAt() != null) {
            return mail.pending() > 0 ? "附件发放中" : "已领取";
        }
        if (mail.draft().expiresAt() != null && mail.draft().expiresAt() <= System.currentTimeMillis()) return "已过期";
        if (mail.draft().rewards().isEmpty()) return mail.readAt() == null ? "未读" : "已读";
        return "待领取";
    }
}
