package cn.cctstudio.cctsystem.platform.paper;

import cn.cctstudio.cctsystem.bridge.BridgeClient;
import cn.cctstudio.cctsystem.bridge.BridgeProvider;
import cn.cctstudio.cctsystem.bridge.BridgeRpcClientProvider;
import cn.cctstudio.cctsystem.bridge.BridgeRpcRouter;
import cn.cctstudio.cctsystem.contract.NodeRole;
import cn.cctstudio.cctsystem.contract.PlatformType;
import cn.cctstudio.cctsystem.core.CctRuntime;
import cn.cctstudio.cctsystem.core.config.CctConfig;
import cn.cctstudio.cctsystem.core.config.ConfigException;
import cn.cctstudio.cctsystem.core.config.ConfigLoader;
import cn.cctstudio.cctsystem.core.config.PremiumLoginConfig;
import cn.cctstudio.cctsystem.exchange.ExchangeRpcModule;
import cn.cctstudio.cctsystem.exchange.ExchangeRuntimeModule;
import cn.cctstudio.cctsystem.auth.AuthModule;
import cn.cctstudio.cctsystem.auth.SocialBindingRewards;
import cn.cctstudio.cctsystem.bridge.BridgeRpcClient;
import cn.cctstudio.cctsystem.identity.IdentityModule;
import cn.cctstudio.cctsystem.points.PointsRpcModule;
import cn.cctstudio.cctsystem.points.PointsRuntimeModule;
import cn.cctstudio.cctsystem.promotion.PromotionRuntimeModule;
import cn.cctstudio.cctsystem.membership.MembershipRpcModule;
import cn.cctstudio.cctsystem.membership.LoopbackMembership;
import cn.cctstudio.cctsystem.membership.MembershipRuntimeModule;
import cn.cctstudio.cctsystem.membership.RemoteMembershipModule;
import cn.cctstudio.cctsystem.redeem.RedeemRpcModule;
import cn.cctstudio.cctsystem.redeem.RedeemRuntimeModule;
import cn.cctstudio.cctsystem.redeem.RemoteRedeemModule;
import cn.cctstudio.cctsystem.storage.mysql.DatabaseManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.UUID;
import net.luckperms.api.LuckPerms;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public final class PaperBootstrap extends JavaPlugin {
    private volatile CctRuntime runtime;
    private PaperIdentityExpansion identityExpansion;
    private PaperAntiRedstoneAdapter antiRedstoneAdapter;
    private PaperMembershipAccessSynchronizer membershipAccessSynchronizer;
    private PaperTitleService titleService;

    public CompletionStage<Void> rewardSocialBinding(UUID playerUuid) {
        CctRuntime current = runtime;
        if (current == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("CCTSystem is not ready"));
        }
        BridgeRpcClient bridge = current.providers().find(BridgeRpcClientProvider.KEY)
            .orElse(null);
        if (bridge == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("CCT bridge is unavailable"));
        }
        return SocialBindingRewards.deliver(bridge, playerUuid);
    }

    private PaperChatFilter chatFilter;
    cn.cctstudio.cctsystem.core.filter.ReloadingChatFilter chatRules() {
        if (chatFilter == null) throw new IllegalStateException("Chat filter unavailable");
        return chatFilter.rules();
    }

    @Override
    public void onEnable() {
        PaperLogger logger = new PaperLogger(getLogger());
        try {
            Path configPath = prepareConfig();
            prepareCustomizationResources();
            CctConfig config = new ConfigLoader().load(configPath);
            PaperMessages messages = new PaperMessages(this);
            if (!config.roles().contains(NodeRole.AUTH_AUTHORITY)) {
                getServer().getPluginManager().registerEvents(new PaperFriendMessages(this), this);
            }
            PaperMenus menuDefinitions = new PaperMenus(this);
            PaperTaskExecutor platformTasks = new PaperTaskExecutor(this);
            CctRuntime created = new CctRuntime(
                PlatformType.PAPER,
                config,
                platformTasks,
                logger
            );
            configureInfrastructure(created, logger, platformTasks);
            org.bukkit.plugin.RegisteredServiceProvider<LuckPerms> luckPermsRegistration =
                getServer().getServicesManager().getRegistration(LuckPerms.class);
            PaperLuckPermsTitles titles = new PaperLuckPermsTitles(
                luckPermsRegistration == null ? null : luckPermsRegistration.getProvider()
            );
            titleService = new PaperTitleService(
                this,
                created.providers(),
                platformTasks,
                created.executors().blocking(),
                logger,
                messages,
                titles,
                luckPermsRegistration == null ? null : luckPermsRegistration.getProvider()
            );
            getServer().getPluginManager().registerEvents(titleService, this);
            new PaperRankBenefitSynchronizer(
                this,
                luckPermsRegistration == null ? null : luckPermsRegistration.getProvider(),
                logger,
                config.serverId()
            ).synchronize();
            PaperNicknameService nicknames = new PaperNicknameService(
                this,
                created.providers(),
                platformTasks,
                created.executors().blocking(),
                logger,
                messages,
                menuDefinitions,
                titles,
                config.serverId()
            );
            titleService.setNicknameService(nicknames);
            nicknames.setTitleService(titleService);
            getServer().getPluginManager().registerEvents(nicknames, this);
            getServer().getPluginManager().registerEvents(
                new PaperAnonymousCommandAdapter(nicknames), this
            );
            if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
                identityExpansion = new PaperIdentityExpansion(nicknames, config.serverId());
                identityExpansion.register();
            }
            PaperChatStyleProvider chatStyle = new PaperChatStyleProvider(
                this, messages, titles, nicknames, logger, config.serverId()
            );
            chatFilter = new PaperChatFilter(this, logger, created.providers(), created.executors().blocking(), config.serverId());
            getServer().getPluginManager().registerEvents(chatFilter, this);
            if ("duel".equalsIgnoreCase(config.serverId())
                || "duels".equalsIgnoreCase(config.serverId())) {
                getServer().getPluginManager().registerEvents(
                    new PaperDuelChatRenderer(nicknames), this
                );
            }
            PaperNetworkChat networkChat = new PaperNetworkChat(
                this, messages, chatStyle, chatFilter
            );
            PaperRewardsMenu rewards = new PaperRewardsMenu(
                this,
                created.providers(),
                platformTasks,
                created.executors().blocking(),
                logger,
                messages,
                menuDefinitions,
                titles,
                config.serverId()
            );
            getServer().getPluginManager().registerEvents(rewards, this);
            PaperLobbyConnector lobby = new PaperLobbyConnector(
                this, messages, config.serverId()
            );
            PaperVanishService vanish = new PaperVanishService(
                this,
                created.providers(),
                platformTasks,
                created.executors().blocking(),
                logger,
                messages,
                config.serverId(),
                config.vanish()
            );
            antiRedstoneAdapter = PaperAntiRedstoneAdapter.install(
                this, logger, messages, networkChat, titles, vanish::isVanished,
                new PaperRedstoneIncidentStore(created.providers(), created.executors(), logger),
                config.serverId()
            );
            getServer().getPluginManager().registerEvents(vanish, this);
            getServer().getPluginManager().registerEvents(
                new PaperShortcutListener(
                    nicknames, networkChat, messages, rewards, lobby, vanish,
                    config.serverId()
                ), this
            );
            PaperExchangeMenu exchangeMenu = new PaperExchangeMenu(
                this,
                created.providers(),
                config,
                platformTasks,
                logger,
                messages,
                menuDefinitions,
                titles
            );
            getServer().getPluginManager().registerEvents(exchangeMenu, this);
            var mailbox = new PaperMailbox(this, created, platformTasks);
            mailbox.register();
            PaperMembershipMenu membershipMenu = new PaperMembershipMenu(
                this,
                created.providers(),
                config,
                platformTasks,
                logger,
                exchangeMenu,
                titles,
                messages,
                menuDefinitions,
                titleService,
                nicknames,
                mailbox
            );
            getServer().getPluginManager().registerEvents(membershipMenu, this);
            PaperMenuController menus = new PaperMenuController(
                membershipMenu,
                exchangeMenu,
                created.providers(),
                platformTasks,
                logger,
                messages,
                networkChat,
                titleService
            );
            org.bukkit.command.PluginCommand cctCommand = getCommand("cct");
            if (cctCommand != null) {
                cctCommand.setDescription(messages.raw("commands.player-description"));
                cctCommand.setUsage(messages.raw("commands.player-usage"));
                cctCommand.setExecutor(menus);
                cctCommand.setTabCompleter(menus);
            }
            org.bukkit.command.PluginCommand shoutCommand = getCommand("shout");
            if (shoutCommand != null) shoutCommand.setExecutor(menus);
            registerNicknameCommand("nick", nicknames, messages, true);
            registerNicknameCommand("unnick", nicknames, messages, false);
            registerPlayerCommand("rewards", messages, rewards::open);
            registerPlayerCommand("lobby", messages, lobby::connect);
            registerPlayerCommand("vanish", messages, vanish::toggle);
            org.bukkit.command.PluginCommand cctAdminCommand = getCommand("cctadmin");
            if (cctAdminCommand != null) {
                cctAdminCommand.setDescription(messages.raw("commands.admin-description"));
                cctAdminCommand.setUsage(messages.raw("commands.admin-usage"));
                PaperPromotionAdminCommand admin = new PaperPromotionAdminCommand(
                    created.providers(), platformTasks, config, logger,
                    messages, menuDefinitions, titleService, mailbox
                );
                cctAdminCommand.setExecutor(admin);
                cctAdminCommand.setTabCompleter(admin);
            }
            getServer().getPluginManager().registerEvents(
                new PaperIdentityListener(this, created.providers(), logger),
                this
            );
            if (luckPermsRegistration != null) {
                membershipAccessSynchronizer = new PaperMembershipAccessSynchronizer(
                    this,
                    created.providers(),
                    logger,
                    luckPermsRegistration.getProvider(),
                    config.membership().purchaseBlockedGroups()
                );
            }
            configurePremiumLogin(created, platformTasks);
            runtime = created;
            created.start().whenComplete((ignored, throwable) -> {
                if (throwable == null) {
                    logger.info("CCTSystem Paper node is ready");
                    getServer().getScheduler().runTask(this, titleService::start);
                } else {
                    logger.error("CCTSystem failed to initialize", throwable);
                    getServer().getScheduler().runTask(this, () -> getServer().getPluginManager().disablePlugin(this));
                }
            });
        } catch (ConfigException exception) {
            logger.error(exception.getMessage(), exception);
            getServer().getPluginManager().disablePlugin(this);
        } catch (RuntimeException exception) {
            logger.error("CCTSystem Paper bootstrap failed", exception);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        PaperIdentityExpansion expansion = identityExpansion;
        identityExpansion = null;
        if (expansion != null) expansion.unregister();
        PaperAntiRedstoneAdapter redstoneAdapter = antiRedstoneAdapter;
        antiRedstoneAdapter = null;
        if (redstoneAdapter != null) redstoneAdapter.close();
        PaperMembershipAccessSynchronizer accessSynchronizer = membershipAccessSynchronizer;
        membershipAccessSynchronizer = null;
        if (accessSynchronizer != null) accessSynchronizer.close();
        PaperTitleService titles = titleService;
        titleService = null;
        if (titles != null) titles.close();
        CctRuntime existing = runtime;
        runtime = null;
        if (existing == null) {
            return;
        }
        try {
            existing.stop().get(5, TimeUnit.SECONDS);
        } catch (Exception exception) {
            getLogger().warning("CCTSystem did not stop cleanly: " + exception.getMessage());
        }
    }

    private Path prepareConfig() {
        Path configPath = getDataFolder().toPath().resolve("config.yml");
        if (Files.notExists(configPath)) {
            saveResource("config.yml", false);
        }
        return configPath;
    }

    private void prepareCustomizationResources() {
        saveResourceIfMissing("messages.yml");
        saveResourceIfMissing("rank-benefits.yml");
        saveResourceIfMissing("anti-redstone.yml");
        saveResourceIfMissing("membership-local.yml");
        for (String menu : java.util.List.of(
            "personal", "membership", "exchange", "upgrade", "confirmation", "nickname", "rewards"
        )) {
            saveResourceIfMissing("menus/" + menu + ".yml");
        }
    }

    private void saveResourceIfMissing(String resourcePath) {
        Path target = getDataFolder().toPath().resolve(resourcePath);
        if (Files.notExists(target)) saveResource(resourcePath, false);
    }

    private void registerNicknameCommand(
        String name,
        PaperNicknameService nicknames,
        PaperMessages messages,
        boolean open
    ) {
        org.bukkit.command.PluginCommand command = getCommand(name);
        if (command == null) return;
        command.setExecutor((sender, ignored, label, arguments) -> {
            if (!(sender instanceof org.bukkit.entity.Player player)) {
                sender.sendMessage(messages.chat("commands.only-player"));
                return true;
            }
            if (open) nicknames.command(player, arguments); else nicknames.disable(player);
            return true;
        });
    }

    private void registerPlayerCommand(
        String name,
        PaperMessages messages,
        java.util.function.Consumer<org.bukkit.entity.Player> action
    ) {
        org.bukkit.command.PluginCommand command = getCommand(name);
        if (command == null) return;
        command.setExecutor((sender, ignored, label, arguments) -> {
            if (!(sender instanceof org.bukkit.entity.Player player)) {
                sender.sendMessage(messages.chat("commands.only-player"));
                return true;
            }
            action.accept(player);
            return true;
        });
    }

    private void configurePremiumLogin(CctRuntime created, PaperTaskExecutor tasks) {
        try {
            PremiumLoginConfig premium = PremiumLoginConfig.load(getDataFolder().toPath());
            if (!premium.enabled()) return;
            CctConfig config = created.config();
            if (!config.database().enabled()) {
                getLogger().warning("Java login requires the shared CCTSystem database on this Paper server");
                return;
            }
            PaperPremiumLogin login = new PaperPremiumLogin(this, created, premium);
            if (premium.loginServer().equals(config.serverId()) || premium.titleServer().equals(config.serverId())) {
                getServer().getPluginManager().registerEvents(login, this);
            }
            if (premium.loginServer().equals(config.serverId())) {
                var authMe = getServer().getPluginManager().getPlugin("AuthMe");
                if (authMe == null || !authMe.isEnabled() || authMe.getConfig().getBoolean("settings.enablePremium")) {
                    getLogger().warning("Java login requires AuthMe with its own premium mode disabled");
                } else {
                    getServer().getPluginManager().registerEvents(login.authListener(), this);
                }
            }
            if (premium.rewardServer().equals(config.serverId())) {
                if (!config.roles().containsAll(java.util.Set.of(NodeRole.BUSINESS_AUTHORITY, NodeRole.POINTS_AUTHORITY))) {
                    getLogger().warning("Java login reward server requires business-authority and points-authority roles");
                    return;
                }
                PaperPremiumRewards worker = new PaperPremiumRewards(this, created, tasks, login);
                getServer().getScheduler().runTaskTimer(this, worker::poll, 100L, 40L);
            }
        } catch (java.io.IOException | RuntimeException exception) {
            getLogger().warning("Java login was disabled on this Paper server. Check premium-login.yml and AuthMe.");
        }
    }

    private void configureInfrastructure(
        CctRuntime created,
        PaperLogger logger,
        PaperTaskExecutor platformTasks
    ) {
        CctConfig config = created.config();
        if (config.database().enabled()) {
            created.addBeforeModules(new DatabaseManager(
                config.nodeId(),
                config.database(),
                created.providers(),
                created.executors(),
                logger
            ));
        }
        created.registerModule(new IdentityModule());
        if (config.bridge().enabled()) {
            BridgeRpcRouter router = new BridgeRpcRouter();
            created.providers().register(BridgeProvider.KEY, router);
            BridgeClient bridgeClient = new BridgeClient(
                config,
                PlatformType.PAPER,
                created.bootId(),
                getPluginMeta().getVersion(),
                created::activeCapabilities,
                router,
                created.executors(),
                logger
            );
            created.providers().register(BridgeRpcClientProvider.KEY, bridgeClient);
            created.addAfterModules(bridgeClient);
        }
        org.bukkit.plugin.Plugin authMe = getServer().getPluginManager().getPlugin("AuthMe");
        if (authMe != null && authMe.isEnabled()) {
            PaperAuthMeInstaller.install(created);
        }
        created.registerModule(new AuthModule());

        org.bukkit.plugin.Plugin playerPoints = getServer().getPluginManager().getPlugin("PlayerPoints");
        if (playerPoints != null && playerPoints.isEnabled()) {
            PaperPlayerPointsInstaller.install(created, playerPoints, platformTasks);
        }
        org.bukkit.plugin.Plugin vault = getServer().getPluginManager().getPlugin("Vault");
        if (vault != null && vault.isEnabled()) {
            PaperVaultInstaller.install(created, getServer(), platformTasks);
        }
        org.bukkit.plugin.Plugin luckPerms = getServer().getPluginManager().getPlugin("LuckPerms");
        if (luckPerms != null && luckPerms.isEnabled()) {
            PaperLuckPermsInstaller.install(created, getServer());
        }
        created.registerModule(new PointsRuntimeModule());
        created.registerModule(new PointsRpcModule());
        created.registerModule(new ExchangeRuntimeModule());
        created.registerModule(new ExchangeRpcModule());
        created.registerModule(new PromotionRuntimeModule());
        configureMembership(created, logger);
        created.registerModule(new RedeemRuntimeModule());
        created.registerModule(new RedeemRpcModule());
        created.registerModule(new RemoteRedeemModule());
        created.registerModule(new PaperAdminRpcModule(this));
    }

    private void configureMembership(CctRuntime runtime, PaperLogger logger) {
        var local = YamlConfiguration.loadConfiguration(getDataFolder().toPath().resolve("membership-local.yml").toFile());
        if (!local.getBoolean("enabled")) {
            runtime.registerModule(new MembershipRuntimeModule());
            runtime.registerModule(new MembershipRpcModule());
            runtime.registerModule(new RemoteMembershipModule());
            return;
        }
        CctConfig config = runtime.config();
        String authorityId = local.getString("authority-server-id", "lobby");
        if (authorityId == null || !authorityId.matches("[a-z0-9][a-z0-9_-]{0,63}")) {
            throw new IllegalArgumentException("Invalid membership-local.yml authority-server-id");
        }
        boolean authority = config.serverId().equals(authorityId);
        if (authority) {
            if (!config.roles().contains(NodeRole.BUSINESS_AUTHORITY)) {
                throw new IllegalArgumentException("The local membership authority requires the business-authority role");
            }
            runtime.registerModule(new MembershipRuntimeModule());
            runtime.registerModule(new MembershipRpcModule());
        }
        new LoopbackMembership(runtime, logger, authority, local.getInt("port", 28471),
            local.getString("secret", "")).install();
    }
}
