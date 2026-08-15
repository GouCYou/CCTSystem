package cn.cctstudio.cctsystem.exchange;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.cctstudio.cctsystem.core.concurrent.CctExecutors;
import cn.cctstudio.cctsystem.core.config.ExchangeSourceConfig;
import cn.cctstudio.cctsystem.points.PointsMutationDisposition;
import cn.cctstudio.cctsystem.points.PointsMutationResult;
import cn.cctstudio.cctsystem.points.PointsService;
import cn.cctstudio.cctsystem.storage.mysql.DatabaseAccess;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "CCTSYSTEM_TEST_MYSQL_URL", matches = ".+")
class JdbcExchangeServiceIntegrationTest {
    private static final ExchangeSourceConfig SOURCE = new ExchangeSourceConfig(
        "survival_coins",
        "survival",
        "生存金币",
        new BigDecimal("100.0000"),
        "global_currency_exchange",
        true
    );

    private CctExecutors executors;
    private FakeCurrencyGateway currency;
    private FakePointsService points;
    private JdbcExchangeStore store;
    private JdbcExchangeService service;

    @BeforeEach
    void setUp() throws Exception {
        String url = System.getenv("CCTSYSTEM_TEST_MYSQL_URL");
        String user = System.getenv("CCTSYSTEM_TEST_MYSQL_USER");
        String password = System.getenv("CCTSYSTEM_TEST_MYSQL_PASSWORD");
        DatabaseAccess database = new DatabaseAccess() {
            @Override
            public Connection connection() throws SQLException {
                return DriverManager.getConnection(url, user, password);
            }

            @Override
            public boolean healthy() {
                return true;
            }
        };
        executors = new CctExecutors();
        currency = new FakeCurrencyGateway(new BigDecimal("100000.0000"));
        points = new FakePointsService();
        store = new JdbcExchangeStore(
            database,
            executors,
            300,
            ZoneId.of("Asia/Shanghai"),
            "integration-node"
        );
        store.syncConfiguration(java.util.List.of(SOURCE)).toCompletableFuture().get(5, TimeUnit.SECONDS);
        service = new JdbcExchangeService(
            Map.of(SOURCE.sourceId(), SOURCE),
            300,
            currency,
            points,
            store,
            Clock.systemUTC()
        );
    }

    @Test
    void restartReleasesReservationWhenNoExternalCallWasRequested() throws Exception {
        UUID playerUuid = UUID.randomUUID();
        UUID transactionId = TimeOrderedUuid.create(java.time.Instant.now());
        PreparedExchange prepared = store.prepare(
            new ExchangeRequest(
                playerUuid,
                SOURCE.sourceId(),
                100,
                ExchangeOrigin.WEB,
                "integration-recovery-safe"
            ),
            SOURCE,
            transactionId,
            currency.balance,
            java.time.Instant.now()
        ).toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertTrue(prepared.proceed());

        store.recoverInterruptedTransactions().toCompletableFuture().get(5, TimeUnit.SECONDS);
        ExchangeResult recovered = store.result(transactionId).toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(ExchangeStatus.FAILED, recovered.status());
        assertEquals(300, recovered.weeklyRemainingPoints());
        assertEquals("NODE_RESTARTED_BEFORE_EXTERNAL_CALL", recovered.errorCode());
    }

    @Test
    void restartMarksRequestedExternalCallForReviewWithoutRepeatingIt() throws Exception {
        UUID playerUuid = UUID.randomUUID();
        UUID transactionId = TimeOrderedUuid.create(java.time.Instant.now());
        PreparedExchange prepared = store.prepare(
            new ExchangeRequest(
                playerUuid,
                SOURCE.sourceId(),
                50,
                ExchangeOrigin.WEB,
                "integration-recovery-ambiguous"
            ),
            SOURCE,
            transactionId,
            currency.balance,
            java.time.Instant.now()
        ).toCompletableFuture().get(5, TimeUnit.SECONDS);
        store.moneyDebitRequested(transactionId, prepared.currencyCost())
            .toCompletableFuture().get(5, TimeUnit.SECONDS);

        store.recoverInterruptedTransactions().toCompletableFuture().get(5, TimeUnit.SECONDS);
        ExchangeResult recovered = store.result(transactionId).toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(ExchangeStatus.REVIEW_REQUIRED, recovered.status());
        assertEquals(50, recovered.weeklyReservedPoints());
        assertEquals(0, currency.withdrawals.get());
    }

    @AfterEach
    void tearDown() {
        executors.close();
    }

    @Test
    void completesOnceAndReturnsSameTransactionForDuplicateRequest() throws Exception {
        UUID playerUuid = UUID.randomUUID();
        ExchangeRequest request = new ExchangeRequest(
            playerUuid,
            SOURCE.sourceId(),
            10,
            ExchangeOrigin.WEB,
            "integration-idempotency-1"
        );

        ExchangeResult first = service.execute(request).toCompletableFuture().get(5, TimeUnit.SECONDS);
        ExchangeResult duplicate = service.execute(request).toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(ExchangeStatus.COMPLETED, first.status());
        assertEquals(first.transactionId(), duplicate.transactionId());
        assertEquals(new BigDecimal("1000.0000"), first.currencyCost());
        assertEquals(10, first.weeklyUsedPoints());
        assertEquals(1, currency.withdrawals.get());
    }

    @Test
    void refundsCurrencyWhenPlayerPointsExplicitlyRejectsCredit() throws Exception {
        points.rejectCredits = true;
        BigDecimal startingBalance = currency.balance;
        ExchangeResult result = service.execute(new ExchangeRequest(
            UUID.randomUUID(),
            SOURCE.sourceId(),
            10,
            ExchangeOrigin.MENU,
            "integration-compensation-1"
        )).toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(ExchangeStatus.COMPENSATED, result.status());
        assertEquals(startingBalance, currency.balance);
        assertEquals(1, currency.refunds.get());
        assertEquals(0, result.weeklyUsedPoints());
    }

    @Test
    void globalWeeklyLimitCannotBeExceededByConcurrentRequests() throws Exception {
        UUID playerUuid = UUID.randomUUID();
        CompletionStage<ExchangeResult> first = service.execute(new ExchangeRequest(
            playerUuid,
            SOURCE.sourceId(),
            200,
            ExchangeOrigin.WEB,
            "integration-concurrent-a"
        ));
        CompletionStage<ExchangeResult> second = service.execute(new ExchangeRequest(
            playerUuid,
            SOURCE.sourceId(),
            150,
            ExchangeOrigin.MENU,
            "integration-concurrent-b"
        ));

        ExchangeResult a = first.toCompletableFuture().get(10, TimeUnit.SECONDS);
        ExchangeResult b = second.toCompletableFuture().get(10, TimeUnit.SECONDS);
        int completed = (a.completed() ? a.requestedPoints() : 0)
            + (b.completed() ? b.requestedPoints() : 0);

        assertTrue(completed <= 300);
        assertTrue(a.errorCode() == null || "WEEKLY_LIMIT_EXCEEDED".equals(a.errorCode())
            || "OPERATION_IN_PROGRESS".equals(a.errorCode()));
        assertTrue(b.errorCode() == null || "WEEKLY_LIMIT_EXCEEDED".equals(b.errorCode())
            || "OPERATION_IN_PROGRESS".equals(b.errorCode()));
    }

    private static final class FakeCurrencyGateway implements CurrencyGateway {
        private BigDecimal balance;
        private final AtomicInteger withdrawals = new AtomicInteger();
        private final AtomicInteger refunds = new AtomicInteger();

        private FakeCurrencyGateway(BigDecimal balance) {
            this.balance = balance;
        }

        @Override
        public synchronized CompletionStage<BigDecimal> balance(UUID playerUuid) {
            return CompletableFuture.completedFuture(balance);
        }

        @Override
        public synchronized CompletionStage<CurrencyMutationResult> withdraw(
            UUID playerUuid,
            BigDecimal amount
        ) {
            BigDecimal before = balance;
            if (balance.compareTo(amount) < 0) {
                return CompletableFuture.completedFuture(new CurrencyMutationResult(
                    false, before, before, "INSUFFICIENT_CURRENCY"
                ));
            }
            balance = balance.subtract(amount);
            withdrawals.incrementAndGet();
            return CompletableFuture.completedFuture(new CurrencyMutationResult(
                true, before, balance, null
            ));
        }

        @Override
        public synchronized CompletionStage<CurrencyMutationResult> deposit(
            UUID playerUuid,
            BigDecimal amount
        ) {
            BigDecimal before = balance;
            balance = balance.add(amount);
            refunds.incrementAndGet();
            return CompletableFuture.completedFuture(new CurrencyMutationResult(
                true, before, balance, null
            ));
        }
    }

    private static final class FakePointsService implements PointsService {
        private volatile boolean rejectCredits;

        @Override
        public CompletionStage<Integer> balance(UUID playerUuid) {
            return CompletableFuture.completedFuture(100);
        }

        @Override
        public CompletionStage<PointsMutationResult> credit(
            UUID playerUuid,
            int amount,
            UUID operationId,
            String sourceType,
            String sourceReference
        ) {
            return CompletableFuture.completedFuture(new PointsMutationResult(
                operationId,
                rejectCredits ? PointsMutationDisposition.REJECTED : PointsMutationDisposition.COMPLETED,
                100,
                rejectCredits ? 100 : 100 + amount,
                rejectCredits ? "POINTS_MUTATION_REJECTED" : null
            ));
        }

        @Override
        public CompletionStage<PointsMutationResult> debit(
            UUID playerUuid,
            int points,
            UUID operationId,
            String sourceType,
            String sourceReference
        ) {
            throw new UnsupportedOperationException();
        }
    }
}
