package cn.cctstudio.cctsystem.platform.paper;

import cn.cctstudio.cctsystem.bridge.BridgeClient;
import cn.cctstudio.cctsystem.bridge.BridgeProvider;
import cn.cctstudio.cctsystem.bridge.BridgeRpcRouter;
import cn.cctstudio.cctsystem.contract.PlatformType;
import cn.cctstudio.cctsystem.core.CctRuntime;
import cn.cctstudio.cctsystem.core.config.CctConfig;
import cn.cctstudio.cctsystem.core.config.ConfigException;
import cn.cctstudio.cctsystem.core.config.ConfigLoader;
import cn.cctstudio.cctsystem.exchange.ExchangeRpcModule;
import cn.cctstudio.cctsystem.exchange.ExchangeRuntimeModule;
import cn.cctstudio.cctsystem.auth.AuthModule;
import cn.cctstudio.cctsystem.identity.IdentityModule;
import cn.cctstudio.cctsystem.points.PointsRpcModule;
import cn.cctstudio.cctsystem.points.PointsRuntimeModule;
import cn.cctstudio.cctsystem.promotion.PromotionRuntimeModule;
import cn.cctstudio.cctsystem.membership.MembershipRpcModule;
import cn.cctstudio.cctsystem.membership.MembershipRuntimeModule;
import cn.cctstudio.cctsystem.redeem.RedeemRpcModule;
import cn.cctstudio.cctsystem.redeem.RedeemRuntimeModule;
import cn.cctstudio.cctsystem.storage.mysql.DatabaseManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.bukkit.plugin.java.JavaPlugin;

public final class PaperBootstrap extends JavaPlugin {
    private volatile CctRuntime runtime;

    @Override
    public void onEnable() {
        PaperLogger logger = new PaperLogger(getLogger());
        try {
            Path configPath = prepareConfig();
            CctConfig config = new ConfigLoader().load(configPath);
            PaperTaskExecutor platformTasks = new PaperTaskExecutor(this);
            CctRuntime created = new CctRuntime(
                PlatformType.PAPER,
                config,
                platformTasks,
                logger
            );
            configureInfrastructure(created, logger, platformTasks);
            PaperExchangeMenu exchangeMenu = new PaperExchangeMenu(
                this,
                created.providers(),
                config,
                platformTasks,
                logger
            );
            getServer().getPluginManager().registerEvents(exchangeMenu, this);
            PaperMembershipMenu membershipMenu = new PaperMembershipMenu(
                this,
                created.providers(),
                config,
                platformTasks,
                logger,
                exchangeMenu
            );
            getServer().getPluginManager().registerEvents(membershipMenu, this);
            org.bukkit.command.PluginCommand cctCommand = getCommand("cct");
            if (cctCommand != null) {
                PaperMenuController menus = new PaperMenuController(
                    membershipMenu,
                    exchangeMenu,
                    created.providers(),
                    platformTasks,
                    logger
                );
                cctCommand.setExecutor(menus);
                cctCommand.setTabCompleter(menus);
            }
            org.bukkit.command.PluginCommand cctAdminCommand = getCommand("cctadmin");
            if (cctAdminCommand != null) {
                PaperPromotionAdminCommand admin = new PaperPromotionAdminCommand(
                    created.providers(), platformTasks, config, logger
                );
                cctAdminCommand.setExecutor(admin);
                cctAdminCommand.setTabCompleter(admin);
            }
            getServer().getPluginManager().registerEvents(
                new PaperIdentityListener(created.providers(), logger),
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
            created.addAfterModules(new BridgeClient(
                config,
                PlatformType.PAPER,
                created.bootId(),
                getPluginMeta().getVersion(),
                created::activeCapabilities,
                router,
                created.executors(),
                logger
            ));
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
        created.registerModule(new RedeemRuntimeModule());
        created.registerModule(new RedeemRpcModule());
    }
}
