package cn.cctstudio.cctsystem.redeem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.cctstudio.cctsystem.core.concurrent.CctExecutors;
import cn.cctstudio.cctsystem.membership.AdminMembershipRequest;
import cn.cctstudio.cctsystem.membership.MembershipOrderResult;
import cn.cctstudio.cctsystem.membership.MembershipPurchaseRequest;
import cn.cctstudio.cctsystem.membership.MembershipQuote;
import cn.cctstudio.cctsystem.membership.MembershipService;
import cn.cctstudio.cctsystem.membership.MembershipSummary;
import cn.cctstudio.cctsystem.membership.MembershipTier;
import cn.cctstudio.cctsystem.membership.UpgradeMode;
import cn.cctstudio.cctsystem.points.PointsMutationDisposition;
import cn.cctstudio.cctsystem.points.PointsMutationResult;
import cn.cctstudio.cctsystem.points.PointsService;
import cn.cctstudio.cctsystem.storage.mysql.DatabaseAccess;
import java.sql.Connection;
import java.sql.DriverManager;
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
final class JdbcRedeemCodeServiceIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-08-15T06:00:00Z");
    private CctExecutors executors;
    private FakePointsService points;
    private JdbcRedeemStore store;
    private JdbcRedeemCodeService service;

    @BeforeEach
    void setUp() {
        DatabaseAccess database = new TestDatabase(
            System.getenv("CCTSYSTEM_TEST_MYSQL_URL"),
            System.getenv("CCTSYSTEM_TEST_MYSQL_USER"),
            System.getenv("CCTSYSTEM_TEST_MYSQL_PASSWORD")
        );
        executors = new CctExecutors();
        points = new FakePointsService();
        store = new JdbcRedeemStore(database, executors, "redeem-test-node");
        service = new JdbcRedeemCodeService(
            store,
            new RedeemCodeCodec("integration-secret-pepper-value-000000000000"),
            points,
            new FakeMembershipService(),
            Clock.fixed(NOW, ZoneOffset.UTC),
            12
        );
    }

    @AfterEach
    void tearDown() {
        executors.close();
    }

    @Test
    void samePlayerAndIdempotencyOnlyReceivePointsOnce() throws Exception {
        GeneratedRedeemBatch batch = generate(1).toCompletableFuture().get(5, TimeUnit.SECONDS);
        UUID player = UUID.randomUUID();
        RedeemRequest request = new RedeemRequest(
            player, batch.codes().getFirst(), "redeem-idem-" + player, "TEST"
        );

        RedeemResult first = service.redeem(request).toCompletableFuture().get(5, TimeUnit.SECONDS);
        RedeemResult duplicate = service.redeem(request).toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals("COMPLETED", first.status());
        assertEquals(first.useId(), duplicate.useId());
        assertEquals(1, points.credits.get());
    }

    @Test
    void aSingleUseCodeCannotBeClaimedByTwoPlayersConcurrently() throws Exception {
        String code = generate(1).toCompletableFuture().get(5, TimeUnit.SECONDS).codes().getFirst();
        UUID firstPlayer = UUID.randomUUID();
        UUID secondPlayer = UUID.randomUUID();
        assertNotEquals(firstPlayer, secondPlayer);

        CompletableFuture<String> first = service.redeem(new RedeemRequest(
            firstPlayer, code, "concurrent-a-" + firstPlayer, "TEST"
        )).handle((result, failure) -> failure == null ? result.status() : "REJECTED").toCompletableFuture();
        CompletableFuture<String> second = service.redeem(new RedeemRequest(
            secondPlayer, code, "concurrent-b-" + secondPlayer, "TEST"
        )).handle((result, failure) -> failure == null ? result.status() : "REJECTED").toCompletableFuture();

        CompletableFuture.allOf(first, second).get(10, TimeUnit.SECONDS);
        assertEquals(1, List.of(first.join(), second.join()).stream()
            .filter("COMPLETED"::equals).count());
        assertEquals(1, points.credits.get());
    }

    @Test
    void interruptedRequestedDeliveryIsNeverAutomaticallyRepeated() throws Exception {
        GeneratedRedeemBatch batch = generate(1).toCompletableFuture().get(5, TimeUnit.SECONDS);
        UUID player = UUID.randomUUID();
        String code = batch.codes().getFirst();
        byte[] hash = new RedeemCodeCodec("integration-secret-pepper-value-000000000000").hash(code);
        JdbcRedeemStore.Reservation reservation = store.reserve(
            new RedeemRequest(player, code, "recovery-" + player, "TEST"),
            hash,
            RedeemCodeCodec.fingerprint(hash),
            NOW
        ).toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertTrue(store.beginDelivery(reservation.rewards().getFirst().deliveryId())
            .toCompletableFuture().get(5, TimeUnit.SECONDS));

        store.recoverInterruptedUses().toCompletableFuture().get(5, TimeUnit.SECONDS);
        RedeemResult result = store.finishUse(reservation.useId(), NOW)
            .toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals("REVIEW_REQUIRED", result.status());
        assertEquals(0, points.credits.get());
    }

    private CompletionStage<GeneratedRedeemBatch> generate(int maxUses) {
        return service.generate(new GenerateRedeemCodesRequest(
            1, maxUses, NOW, NOW.plusSeconds(86_400), "integration-test", "test",
            List.of(RedeemReward.points(25))
        ));
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
        private final AtomicInteger credits = new AtomicInteger();

        @Override
        public CompletionStage<Integer> balance(UUID playerUuid) {
            return CompletableFuture.completedFuture(0);
        }

        @Override
        public CompletionStage<PointsMutationResult> credit(
            UUID playerUuid,
            int amount,
            UUID operationId,
            String sourceType,
            String sourceReference
        ) {
            credits.incrementAndGet();
            return CompletableFuture.completedFuture(new PointsMutationResult(
                operationId, PointsMutationDisposition.COMPLETED, 0, amount, null
            ));
        }

        @Override
        public CompletionStage<PointsMutationResult> debit(
            UUID playerUuid,
            int amount,
            UUID operationId,
            String sourceType,
            String sourceReference
        ) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class FakeMembershipService implements MembershipService {
        @Override
        public CompletionStage<List<MembershipTier>> catalog() {
            return CompletableFuture.completedFuture(List.of());
        }

        @Override
        public CompletionStage<MembershipSummary> summary(UUID playerUuid) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletionStage<MembershipQuote> quote(
            UUID playerUuid, String tierKey, int months, UpgradeMode upgradeMode
        ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletionStage<MembershipOrderResult> purchase(MembershipPurchaseRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletionStage<MembershipSummary> reconcile(UUID playerUuid) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletionStage<MembershipSummary> admin(AdminMembershipRequest request) {
            throw new UnsupportedOperationException();
        }
    }
}
