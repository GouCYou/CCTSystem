package cn.cctstudio.cctsystem.exchange;

import cn.cctstudio.cctsystem.core.config.ExchangeSourceConfig;
import cn.cctstudio.cctsystem.points.PointsMutationDisposition;
import cn.cctstudio.cctsystem.points.PointsMutationResult;
import cn.cctstudio.cctsystem.points.PointsService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.regex.Pattern;

final class JdbcExchangeService implements ExchangeService {
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9._:-]{8,80}");

    private final Map<String, ExchangeSourceConfig> sources;
    private final int weeklyLimit;
    private final CurrencyGateway currency;
    private final PointsService points;
    private final JdbcExchangeStore store;
    private final Clock clock;

    JdbcExchangeService(
        Map<String, ExchangeSourceConfig> sources,
        int weeklyLimit,
        CurrencyGateway currency,
        PointsService points,
        JdbcExchangeStore store,
        Clock clock
    ) {
        this.sources = Map.copyOf(sources);
        this.weeklyLimit = weeklyLimit;
        this.currency = currency;
        this.points = points;
        this.store = store;
        this.clock = clock;
    }

    @Override
    public CompletionStage<ExchangeQuote> quote(UUID playerUuid, String sourceId) {
        ExchangeSourceConfig source = requireSource(sourceId);
        Instant now = clock.instant();
        CompletionStage<BigDecimal> currencyBalance = currency.balance(playerUuid);
        CompletionStage<Integer> pointsBalance = points.balance(playerUuid);
        CompletionStage<WeeklyUsage> usage = store.usage(playerUuid, source, now);
        return currencyBalance.thenCombine(pointsBalance, BalanceSnapshot::new)
            .thenCombine(usage, (balances, weekly) -> {
                int affordable = affordablePoints(
                    balances.currencyBalance(),
                    source.currencyUnitsPerPoint()
                );
                int maximum = Math.min(affordable, weekly.remainingPoints());
                return new ExchangeQuote(
                    source.sourceId(),
                    source.currencyDisplayName(),
                    source.currencyUnitsPerPoint(),
                    balances.currencyBalance(),
                    balances.pointsBalance(),
                    weekly.limitPoints(),
                    weekly.completedPoints(),
                    weekly.reservedPoints(),
                    weekly.remainingPoints(),
                    maximum,
                    weekly.resetsAt()
                );
            });
    }

    @Override
    public CompletionStage<ExchangeResult> execute(ExchangeRequest request) {
        ExchangeSourceConfig source = validate(request);
        Instant now = clock.instant();
        UUID transactionId = TimeOrderedUuid.create(now);
        return currency.balance(request.playerUuid())
            .thenCompose(balance -> store.prepare(
                request,
                source,
                transactionId,
                balance,
                now
            ))
            .thenCompose(prepared -> prepared.proceed()
                ? withdraw(request, prepared)
                : CompletableFuture.completedFuture(prepared.existingResult()));
    }

    private CompletionStage<ExchangeResult> withdraw(
        ExchangeRequest request,
        PreparedExchange prepared
    ) {
        UUID transactionId = prepared.transactionId();
        return store.moneyDebitRequested(transactionId, prepared.currencyCost())
            .thenCompose(ignored -> currency.withdraw(request.playerUuid(), prepared.currencyCost())
                .handle(GatewayOutcome<CurrencyMutationResult>::new))
            .thenCompose(outcome -> {
                if (outcome.failure() != null) {
                    return review(
                        transactionId,
                        ExchangeStatus.MONEY_DEBIT_REQUESTED,
                        "MONEY_DEBIT",
                        "VAULT_RESULT_UNKNOWN"
                    );
                }
                CurrencyMutationResult mutation = outcome.value();
                if (!mutation.successful()) {
                    return store.failMoneyDebit(transactionId, "INSUFFICIENT_CURRENCY")
                        .thenCompose(ignored -> store.result(transactionId));
                }
                return store.moneyDebited(transactionId, mutation)
                    .thenCompose(ignored -> creditPoints(request, prepared));
            });
    }

    private CompletionStage<ExchangeResult> creditPoints(
        ExchangeRequest request,
        PreparedExchange prepared
    ) {
        UUID transactionId = prepared.transactionId();
        return store.pointsCreditRequested(transactionId, request.requestedPoints())
            .thenCompose(ignored -> points.credit(
                request.playerUuid(),
                request.requestedPoints(),
                transactionId,
                "EXCHANGE",
                transactionId.toString()
            ).handle(GatewayOutcome<PointsMutationResult>::new))
            .thenCompose(outcome -> {
                if (outcome.failure() != null) {
                    return review(
                        transactionId,
                        ExchangeStatus.POINTS_CREDIT_REQUESTED,
                        "POINTS_CREDIT",
                        "POINTS_RESULT_UNKNOWN"
                    );
                }
                PointsMutationResult mutation = outcome.value();
                if (mutation.disposition() == PointsMutationDisposition.COMPLETED) {
                    return store.complete(transactionId, mutation)
                        .thenCompose(ignored -> store.result(transactionId));
                }
                if (mutation.disposition() == PointsMutationDisposition.AMBIGUOUS) {
                    return review(
                        transactionId,
                        ExchangeStatus.POINTS_CREDIT_REQUESTED,
                        "POINTS_CREDIT",
                        mutation.errorCode() == null ? "POINTS_RESULT_UNKNOWN" : mutation.errorCode()
                    );
                }
                return compensate(request, prepared, mutation);
            });
    }

    private CompletionStage<ExchangeResult> compensate(
        ExchangeRequest request,
        PreparedExchange prepared,
        PointsMutationResult pointsResult
    ) {
        UUID transactionId = prepared.transactionId();
        String pointsError = pointsResult.errorCode() == null
            ? "POINTS_CREDIT_REJECTED"
            : pointsResult.errorCode();
        return store.beginCompensation(transactionId, prepared.currencyCost(), pointsError)
            .thenCompose(ignored -> currency.deposit(request.playerUuid(), prepared.currencyCost())
                .handle(GatewayOutcome<CurrencyMutationResult>::new))
            .thenCompose(outcome -> {
                if (outcome.failure() != null) {
                    return store.compensationPending(
                        transactionId,
                        null,
                        "VAULT_REFUND_RESULT_UNKNOWN"
                    ).thenCompose(ignored -> store.result(transactionId));
                }
                CurrencyMutationResult refund = outcome.value();
                if (!refund.successful()) {
                    return store.compensationPending(
                        transactionId,
                        refund,
                        "VAULT_REFUND_REJECTED"
                    ).thenCompose(ignored -> store.result(transactionId));
                }
                return store.compensated(transactionId, refund)
                    .thenCompose(ignored -> store.result(transactionId));
            });
    }

    private CompletionStage<ExchangeResult> review(
        UUID transactionId,
        ExchangeStatus expected,
        String actionType,
        String errorCode
    ) {
        return store.reviewRequired(transactionId, expected, actionType, errorCode)
            .thenCompose(ignored -> store.result(transactionId));
    }

    private ExchangeSourceConfig validate(ExchangeRequest request) {
        ExchangeSourceConfig source = requireSource(request.sourceId());
        if (request.requestedPoints() < 1 || request.requestedPoints() > weeklyLimit) {
            throw new ExchangeException("EXCHANGE_AMOUNT_INVALID", "Invalid exchange amount", false);
        }
        if (!IDEMPOTENCY_KEY.matcher(request.idempotencyKey()).matches()) {
            throw new ExchangeException("IDEMPOTENCY_KEY_INVALID", "Invalid idempotency key", false);
        }
        return source;
    }

    private ExchangeSourceConfig requireSource(String sourceId) {
        if (sourceId == null) {
            throw new ExchangeException("EXCHANGE_SOURCE_INVALID", "Exchange source is unavailable", false);
        }
        ExchangeSourceConfig source = sources.get(sourceId.trim().toLowerCase(java.util.Locale.ROOT));
        if (source == null || !source.enabled()) {
            throw new ExchangeException("EXCHANGE_SOURCE_INVALID", "Exchange source is unavailable", false);
        }
        return source;
    }

    private static int affordablePoints(BigDecimal balance, BigDecimal rate) {
        if (balance.signum() <= 0) {
            return 0;
        }
        BigDecimal affordable = balance.divide(rate, 0, RoundingMode.FLOOR);
        return affordable.min(BigDecimal.valueOf(Integer.MAX_VALUE)).intValue();
    }

    private record BalanceSnapshot(BigDecimal currencyBalance, int pointsBalance) {
    }

    private record GatewayOutcome<T>(T value, Throwable failure) {
    }
}
