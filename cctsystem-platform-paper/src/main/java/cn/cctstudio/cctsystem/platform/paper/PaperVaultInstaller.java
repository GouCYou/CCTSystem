package cn.cctstudio.cctsystem.platform.paper;

import cn.cctstudio.cctsystem.core.CctRuntime;
import cn.cctstudio.cctsystem.exchange.CurrencyGatewayProvider;
import cn.cctstudio.cctsystem.vault.VaultCurrencyGateway;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Server;
import org.bukkit.plugin.RegisteredServiceProvider;

final class PaperVaultInstaller {
    private PaperVaultInstaller() {
    }

    static void install(CctRuntime runtime, Server server, PaperTaskExecutor platformTasks) {
        RegisteredServiceProvider<Economy> registration = server.getServicesManager()
            .getRegistration(Economy.class);
        if (registration == null || !registration.getProvider().isEnabled()) {
            return;
        }
        runtime.providers().register(
            CurrencyGatewayProvider.KEY,
            new VaultCurrencyGateway(registration.getProvider(), server, platformTasks)
        );
    }
}
