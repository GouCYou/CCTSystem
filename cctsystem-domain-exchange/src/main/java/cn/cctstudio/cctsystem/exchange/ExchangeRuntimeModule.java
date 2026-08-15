package cn.cctstudio.cctsystem.exchange;

import cn.cctstudio.cctsystem.contract.NodeRole;
import cn.cctstudio.cctsystem.contract.PlatformType;
import cn.cctstudio.cctsystem.core.config.ExchangeSourceConfig;
import cn.cctstudio.cctsystem.core.module.CctModule;
import cn.cctstudio.cctsystem.core.module.ModuleContext;
import cn.cctstudio.cctsystem.core.module.ModuleDescriptor;
import cn.cctstudio.cctsystem.points.PointsService;
import cn.cctstudio.cctsystem.points.PointsServiceProvider;
import cn.cctstudio.cctsystem.storage.mysql.DatabaseAccess;
import cn.cctstudio.cctsystem.storage.mysql.DatabaseProvider;
import java.time.Clock;
import java.time.ZoneId;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class ExchangeRuntimeModule implements CctModule {
    private static final ModuleDescriptor DESCRIPTOR = new ModuleDescriptor(
        "exchange-runtime",
        "exchange",
        Set.of(PlatformType.PAPER),
        Set.of(NodeRole.ECONOMY_SOURCE),
        Set.of(DatabaseProvider.ID, PointsServiceProvider.ID, CurrencyGatewayProvider.ID),
        Set.of()
    );

    @Override
    public ModuleDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public CompletableFuture<Void> start(ModuleContext context) {
        Map<String, ExchangeSourceConfig> sources = context.config().exchange().sources().stream()
            .filter(ExchangeSourceConfig::enabled)
            .filter(source -> source.serverId().equals(context.config().serverId()))
            .collect(Collectors.toUnmodifiableMap(ExchangeSourceConfig::sourceId, Function.identity()));
        if (sources.isEmpty()) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                "No enabled exchange source belongs to server-id " + context.config().serverId()
            ));
        }
        DatabaseAccess database = context.providers().find(DatabaseProvider.KEY)
            .orElseThrow(() -> new IllegalStateException("Database provider is unavailable"));
        PointsService points = context.providers().find(PointsServiceProvider.KEY)
            .orElseThrow(() -> new IllegalStateException("Points service provider is unavailable"));
        CurrencyGateway currency = context.providers().find(CurrencyGatewayProvider.KEY)
            .orElseThrow(() -> new IllegalStateException("Vault economy provider is unavailable"));
        ZoneId timezone = ZoneId.of(context.config().exchange().timezone());
        JdbcExchangeStore store = new JdbcExchangeStore(
            database,
            context.executors(),
            context.config().exchange().weeklyLimitPoints(),
            timezone,
            context.config().nodeId()
        );
        JdbcExchangeService service = new JdbcExchangeService(
            sources,
            context.config().exchange().weeklyLimitPoints(),
            currency,
            points,
            store,
            Clock.systemUTC()
        );
        return store.syncConfiguration(sources.values())
            .thenCompose(ignored -> store.recoverInterruptedTransactions())
            .thenRun(() -> context.providers().register(ExchangeServiceProvider.KEY, service))
            .toCompletableFuture();
    }

    @Override
    public CompletableFuture<Void> stop() {
        return CompletableFuture.completedFuture(null);
    }
}
