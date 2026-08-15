package cn.cctstudio.cctsystem.vault;

import cn.cctstudio.cctsystem.core.concurrent.PlatformTaskExecutor;
import cn.cctstudio.cctsystem.exchange.CurrencyGateway;
import cn.cctstudio.cctsystem.exchange.CurrencyMutationResult;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;

public final class VaultCurrencyGateway implements CurrencyGateway {
    private final Economy economy;
    private final Server server;
    private final PlatformTaskExecutor platformTasks;

    public VaultCurrencyGateway(Economy economy, Server server, PlatformTaskExecutor platformTasks) {
        this.economy = Objects.requireNonNull(economy, "economy");
        this.server = Objects.requireNonNull(server, "server");
        this.platformTasks = Objects.requireNonNull(platformTasks, "platformTasks");
    }

    @Override
    public CompletionStage<BigDecimal> balance(UUID playerUuid) {
        return platformTasks.callMain(() -> decimal(economy.getBalance(player(playerUuid))));
    }

    @Override
    public CompletionStage<CurrencyMutationResult> withdraw(UUID playerUuid, BigDecimal amount) {
        return mutate(playerUuid, amount, true);
    }

    @Override
    public CompletionStage<CurrencyMutationResult> deposit(UUID playerUuid, BigDecimal amount) {
        return mutate(playerUuid, amount, false);
    }

    private CompletionStage<CurrencyMutationResult> mutate(
        UUID playerUuid,
        BigDecimal amount,
        boolean withdraw
    ) {
        double value = requireAmount(amount);
        return platformTasks.callMain(() -> {
            OfflinePlayer player = player(playerUuid);
            BigDecimal before = decimal(economy.getBalance(player));
            EconomyResponse response = withdraw
                ? economy.withdrawPlayer(player, value)
                : economy.depositPlayer(player, value);
            return new CurrencyMutationResult(
                response.transactionSuccess(),
                before,
                decimal(response.balance),
                response.transactionSuccess() ? null : "VAULT_MUTATION_REJECTED"
            );
        });
    }

    private OfflinePlayer player(UUID playerUuid) {
        return server.getOfflinePlayer(playerUuid);
    }

    private static double requireAmount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Currency amount must be positive");
        }
        double value = amount.doubleValue();
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("Currency amount is outside Vault range");
        }
        return value;
    }

    private static BigDecimal decimal(double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalStateException("Vault returned a non-finite balance");
        }
        return BigDecimal.valueOf(value);
    }
}
