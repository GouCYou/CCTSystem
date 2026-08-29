package cn.cctstudio.cctsystem.platform.paper;

import cn.cctstudio.cctsystem.bridge.BridgeClient;
import cn.cctstudio.cctsystem.bridge.BridgeProvider;
import cn.cctstudio.cctsystem.bridge.BridgeRpcClientProvider;
import cn.cctstudio.cctsystem.bridge.BridgeRpcRouter;
import cn.cctstudio.cctsystem.contract.PlatformType;
import cn.cctstudio.cctsystem.core.CctRuntime;
import cn.cctstudio.cctsystem.core.config.CctConfig;
import cn.cctstudio.cctsystem.core.config.ConfigException;
import cn.cctstudio.cctsystem.core.config.ConfigLoader;
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
import cn.cctstudio.cctsystem.membership.MembershipRuntimeModule;
import cn.cctstudio.cctsystem.membership.RemoteMembershipModule;
import cn.cctstudio.cctsystem.redeem.RedeemRpcModule;
import cn.cctstudio.cctsystem.redeem.RedeemRuntimeModule;
import cn.cctstudio.cctsystem.storage.mysql.DatabaseManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.UUID;
import net.luckperms.api.LuckPerms;
import org.bukkit.plugin.java.JavaPlugin;

public final class PaperBootstrap extends JavaPlugin {
    private volatile CctRuntime runtime;
    private PaperIdentityExpansion identityExpansion;
    private PaperAntiRedstoneAdapter antiRedstoneAdapter;

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

    @Override
    public void onEnable() {
        PaperLogger logger = new PaperLogger(getLogger());
        try {
            Path configPath = prepareConfig();
            prepareCustomizationResources();
            CctConfig config = new ConfigLoader().load(configPath);
            PaperMessages messages = new PaperMessages(this);
            PaperMenus menuDefinitions = new PaperMenus(this);
            PaperTaskExecutor platformTasks = new PaperTaskExecutor(this);
            PaperSurvivalWorldPolicy.install(this, logger, config.serverId());
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
            getServer().getPluginManager().registerEvents(nicknames, this);
            getServer().getPluginManager().registerEvents(
                new PaperAnonymousCommandAdapter(nicknames), this
            );
            if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
                identityExpansion = new PaperIdentityExpansion(nicknames);
                identityExpansion.register();
            }
            PaperChatStyleProvider chatStyle = new PaperChatStyleProvider(
                this, messages, titles, nicknames, logger, config.serverId()
            );
            PaperNetworkChat networkChat = new PaperNetworkChat(this, messages, chatStyle);
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
                config.serverId()
            );
            antiRedstoneAdapter = PaperAntiRedstoneAdapter.install(
                this, logger, messages, networkChat, titles, vanish::isVanished,
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
            PaperMembershipMenu membershipMenu = new PaperMembershipMenu(
                this,
                created.providers(),
                config,
                platformTasks,
                logger,
                exchangeMenu,
                titles,
                messages,
                menuDefinitions
            );
            getServer().getPluginManager().registerEvents(membershipMenu, this);
            PaperMenuController menus = new PaperMenuController(
                membershipMenu,
                exchangeMenu,
                created.providers(),
                platformTasks,
                logger,
                messages,
                networkChat
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
                    messages, menuDefinitions
                );
                cctAdminCommand.setExecutor(admin);
                cctAdminCommand.setTabCompleter(admin);
            }
            getServer().getPluginManager().registerEvents(
                new PaperIdentityListener(this, created.providers(), logger),
                this
            );
            runtime = created;
            created.start().whenComplete((ignored, throwable) -> {
                if (throwable == null) {
                    logger.info("CCTSystem Paper node is ready");
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
                sender.sendMessage(messages.component("commands.only-player"));
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
                sender.sendMessage(messages.component("commands.only-player"));
                return true;
            }
            action.accept(player);
            return true;
        });
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
        created.registerModule(new MembershipRuntimeModule());
        created.registerModule(new MembershipRpcModule());
        created.registerModule(new RemoteMembershipModule());
        created.registerModule(new RedeemRuntimeModule());
        created.registerModule(new RedeemRpcModule());
    }
}
