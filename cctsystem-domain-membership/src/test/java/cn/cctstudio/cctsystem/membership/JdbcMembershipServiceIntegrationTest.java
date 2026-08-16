package cn.cctstudio.cctsystem.membership;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.cctstudio.cctsystem.core.concurrent.CctExecutors;
import cn.cctstudio.cctsystem.core.config.MembershipTierConfig;
import cn.cctstudio.cctsystem.identity.UuidBinary;
import cn.cctstudio.cctsystem.points.PointsMutationDisposition;
import cn.cctstudio.cctsystem.points.PointsMutationResult;
import cn.cctstudio.cctsystem.points.PointsService;
import cn.cctstudio.cctsystem.promotion.CreatePromotionRequest;
import cn.cctstudio.cctsystem.promotion.Promotion;
import cn.cctstudio.cctsystem.promotion.PromotionService;
import cn.cctstudio.cctsystem.storage.mysql.DatabaseAccess;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
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
final class JdbcMembershipServiceIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-08-15T04:00:00Z");
    private static final List<MembershipTierConfig> TIERS = List.of(
        tier("vip", "VIP", 100, 100),
        tier("vip_plus", "VIP+", 200, 200),
        tier("mvp", "MVP", 300, 400),
        tier("mvp_plus", "MVP+", 400, 800)
    );

    private CctExecutors executors;
    private TestDatabase database;
    private FakePointsService points;
    private FakePromotionService promotions;
    private JdbcMembershipStore store;
    private JdbcMembershipService service;
    private AtomicInteger projectionSignals;

    @BeforeEach
    void setUp() throws Exception {
        database = new TestDatabase(
            System.getenv("CCTSYSTEM_TEST_MYSQL_URL"),
            System.getenv("CCTSYSTEM_TEST_MYSQL_USER"),
            System.getenv("CCTSYSTEM_TEST_MYSQL_PASSWORD")
        );
        executors = new CctExecutors();
        points = new FakePointsService();
        promotions = new FakePromotionService();
        projectionSignals = new AtomicInteger();
        store = new JdbcMembershipStore(database, executors, "membership-integration-node");
        store.syncConfiguration(TIERS).toCompletableFuture().get(5, TimeUnit.SECONDS);
        service = new JdbcMembershipService(
            store,
            points,
            promotions,
            ignored -> CompletableFuture.completedFuture(new MembershipAccess(java.util.Set.of("default"))),
            Clock.fixed(NOW, ZoneOffset.UTC),
            60,
            365,
            java.util.Set.of("helper", "mod", "admin", "owner"),
            projectionSignals::incrementAndGet
        );
    }

    @AfterEach
    void tearDown() {
        executors.close();
    }

    @Test
    void purchaseIsIdempotentAndCreatesTemporaryMembership() throws Exception {
        UUID playerUuid = UUID.randomUUID();
        MembershipPurchaseRequest request = request(playerUuid, "vip", UpgradeMode.NONE, "member-idem");

        MembershipOrderResult first = service.purchase(request).toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals(1, projectionSignals.get());
        MembershipOrderResult duplicate = service.purchase(request).toCompletableFuture().get(5, TimeUnit.SECONDS);
        MembershipSummary summary = service.summary(playerUuid).toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(MembershipOrderStatus.COMPLETED, first.status());
        assertEquals(first.orderId(), duplicate.orderId());
        assertEquals(1, points.debits.get());
        assertEquals("vip", summary.active().tier().key());
        assertEquals(NOW.plusSeconds(30L * 86_400L), summary.active().expiresAt());
        assertEquals("PENDING", summary.permissionSyncStatus());
    }

    @Test
    void upgradeCanPauseThenAutomaticallyResumeOldMembership() throws Exception {
        UUID playerUuid = UUID.randomUUID();
        service.purchase(request(playerUuid, "vip", UpgradeMode.NONE, "pause-base"))
            .toCompletableFuture().get(5, TimeUnit.SECONDS);

        MembershipOrderResult upgraded = service.purchase(
            request(playerUuid, "mvp", UpgradeMode.PAUSE, "pause-upgrade")
        ).toCompletableFuture().get(5, TimeUnit.SECONDS);
        MembershipSummary duringUpgrade = service.summary(playerUuid)
            .toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(MembershipOrderStatus.COMPLETED, upgraded.status());
        assertEquals("mvp", duringUpgrade.active().tier().key());
        assertEquals(1, duringUpgrade.paused().size());
        assertEquals("vip", duringUpgrade.paused().getFirst().tier().key());

        expireActive(playerUuid);
        MembershipSummary resumed = service.summary(playerUuid)
            .toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals("vip", resumed.active().tier().key());
        assertTrue(resumed.paused().isEmpty());
        assertNotNull(resumed.active().expiresAt());
    }

    @Test
    void upgradeCreditUsesDiscountedPriceAndConsumesOldMembership() throws Exception {
        UUID playerUuid = UUID.randomUUID();
        service.purchase(request(playerUuid, "vip", UpgradeMode.NONE, "credit-base"))
            .toCompletableFuture().get(5, TimeUnit.SECONDS);
        setActiveExpiry(playerUuid, NOW.plusSeconds(15L * 86_400L));
        promotions.active = new Promotion(
            UUID.randomUUID(), "八折", null, 2_000,
            NOW.minusSeconds(10), NOW.plusSeconds(3600), 10, "ACTIVE"
        );

        MembershipQuote quote = service.quote(playerUuid, "mvp", 1, UpgradeMode.CREDIT)
            .toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(320, quote.discountedPricePoints());
        assertEquals(50, quote.upgradeCreditPoints());
        assertEquals(270, quote.finalPricePoints());
    }

    @Test
    void requestedPointDebitBecomesReviewRequiredAfterRestart() throws Exception {
        UUID playerUuid = UUID.randomUUID();
        MembershipQuote quote = service.quote(playerUuid, "vip", 1, UpgradeMode.NONE)
            .toCompletableFuture().get(5, TimeUnit.SECONDS);
        UUID orderId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        PreparedMembershipOrder prepared = store.prepare(
            request(playerUuid, "vip", UpgradeMode.NONE, "membership-recovery"),
            quote,
            orderId,
            operationId,
            NOW
        ).toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertTrue(prepared.proceed());
        store.pointsDebitRequested(orderId).toCompletableFuture().get(5, TimeUnit.SECONDS);

        store.recoverInterruptedOrders().toCompletableFuture().get(5, TimeUnit.SECONDS);
        MembershipOrderResult recovered = store.result(orderId)
            .toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(MembershipOrderStatus.REVIEW_REQUIRED, recovered.status());
        assertEquals(0, points.debits.get());
    }

    private void expireActive(UUID playerUuid) throws SQLException {
        setActiveExpiry(playerUuid, NOW.minusSeconds(1));
    }

    private void setActiveExpiry(UUID playerUuid, Instant expiry) throws SQLException {
        try (Connection connection = database.connection();
             PreparedStatement update = connection.prepareStatement("""
                UPDATE cct_membership_entitlements
                SET expires_at = ?
                WHERE player_uuid = ? AND state = 'ACTIVE'
                """)) {
            update.setObject(1, java.time.LocalDateTime.ofInstant(expiry, ZoneOffset.UTC));
            update.setBytes(2, UuidBinary.encode(playerUuid));
            update.executeUpdate();
        }
    }

    private static MembershipPurchaseRequest request(
        UUID playerUuid,
        String tier,
        UpgradeMode mode,
        String suffix
    ) {
        return new MembershipPurchaseRequest(
            playerUuid, tier, 1, mode, suffix + "-" + playerUuid, "TEST"
        );
    }

    private static MembershipTierConfig tier(String key, String name, int priority, int price) {
        return new MembershipTierConfig(
            key, name, priority, key, 30, price, 10_000,
            "GOLD_INGOT", List.of("测试权益"), true
        );
    }

    private record TestDatabase(String url, String user, String password) implements DatabaseAccess {
        @Override
        public Connection connection() throws SQLException {
            return DriverManager.getConnection(url, user, password);
        }

        @Override
        public boolean healthy() {
            return true;
        }
    }

    private static final class FakePointsService implements PointsService {
        private final AtomicInteger debits = new AtomicInteger();
        private int balance = 10_000;

        @Override
        public CompletionStage<Integer> balance(UUID playerUuid) {
            return CompletableFuture.completedFuture(balance);
        }

        @Override
        public synchronized CompletionStage<PointsMutationResult> credit(
            UUID playerUuid,
            int amount,
            UUID operationId,
            String sourceType,
            String sourceReference
        ) {
            int before = balance;
            balance += amount;
            return CompletableFuture.completedFuture(new PointsMutationResult(
                operationId, PointsMutationDisposition.COMPLETED, before, balance, null
            ));
        }

        @Override
        public synchronized CompletionStage<PointsMutationResult> debit(
            UUID playerUuid,
            int amount,
            UUID operationId,
            String sourceType,
            String sourceReference
        ) {
            int before = balance;
            if (balance < amount) {
                return CompletableFuture.completedFuture(new PointsMutationResult(
                    operationId, PointsMutationDisposition.REJECTED, before, before, "INSUFFICIENT_POINTS"
                ));
            }
            balance -= amount;
            debits.incrementAndGet();
            return CompletableFuture.completedFuture(new PointsMutationResult(
                operationId, PointsMutationDisposition.COMPLETED, before, balance, null
            ));
        }
    }

    private static final class FakePromotionService implements PromotionService {
        private volatile Promotion active = Promotion.none();

        @Override
        public CompletionStage<Promotion> activeForMembership(String tierKey, Instant now) {
            return CompletableFuture.completedFuture(active);
        }

        @Override
        public CompletionStage<Promotion> create(CreatePromotionRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletionStage<Boolean> stop(UUID promotionId, String actor, String reason, Instant now) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletionStage<List<Promotion>> listCurrent(Instant now) {
            return CompletableFuture.completedFuture(List.of(active));
        }

        @Override
        public CompletionStage<Promotion> find(UUID promotionId) {
            return CompletableFuture.completedFuture(active);
        }
    }
}
