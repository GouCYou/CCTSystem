package cn.cctstudio.cctsystem.membership;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import cn.cctstudio.cctsystem.storage.mysql.PremiumLoginStore;
import cn.cctstudio.cctsystem.core.auth.PremiumLoginProof;
import cn.cctstudio.cctsystem.core.auth.PremiumLoginProof.Session;
import cn.cctstudio.cctsystem.core.config.PremiumLoginConfig;
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
    private volatile java.util.Set<String> accessGroups;

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
        accessGroups = java.util.Set.of("default");
        store = new JdbcMembershipStore(database, executors, "membership-integration-node");
        store.syncConfiguration(TIERS).toCompletableFuture().get(5, TimeUnit.SECONDS);
        service = new JdbcMembershipService(
            store,
            points,
            promotions,
            ignored -> CompletableFuture.completedFuture(new MembershipAccess(accessGroups)),
            Clock.fixed(NOW, ZoneOffset.UTC),
            60,
            365,
            java.util.Set.of("helper", "mod", "admin", "owner"),
            java.util.Set.of("vip", "vip_plus", "mvp", "mvp_plus"),
            false,
            projectionSignals::incrementAndGet
        );
    }

    @Test
    void javaRewardQueuesBehindHigherMembershipAndTwoWorkersGrantOnlyOnce() throws Exception {
        Session session = javaSession("RewardA");
        var rewardStore = new PremiumLoginStore(database);
        var config = javaReward();
        UUID player = session.playerUuid();
        service.admin(new AdminMembershipRequest(
            AdminMembershipAction.GRANT, player, "mvp", 30, null, "test", "seed"
        )).toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertTrue(rewardStore.link(session, 1L, config));
        var first = service.grantPremiumLoginReward(session.mojangUuid(), player).toCompletableFuture();
        var second = service.grantPremiumLoginReward(session.mojangUuid(), player).toCompletableFuture();
        CompletableFuture.allOf(first, second).get(5, TimeUnit.SECONDS);

        MembershipSummary summary = service.summary(player).toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals("mvp", summary.active().tier().key());
        assertEquals(1, summary.paused().size());
        assertEquals("vip_plus", summary.paused().getFirst().tier().key());
        assertEquals(14L * 86_400L, summary.paused().getFirst().remainingSeconds());
        assertEquals(PremiumLoginStore.Status.COMPLETED, rewardStore.find(session).orElseThrow().membership());
    }

    @Test
    void unlinkedJavaBindingNeedsAFreshRequestAndKeepsItsFirstReward() throws Exception {
        Session original = javaSession("Unlink");
        var rewards = new PremiumLoginStore(database);
        var config = javaReward();
        assertTrue(rewards.link(original, 41L, config));
        assertTrue(rewards.changeStatus(original.mojangUuid(), PremiumLoginStore.Step.POINTS,
            PremiumLoginStore.Status.PENDING, PremiumLoginStore.Status.COMPLETED));
        assertFalse(rewards.unlink(UUID.randomUUID(), original.name()));
        assertTrue(rewards.unlink(original.playerUuid(), original.name()));
        assertFalse(rewards.unlink(original.playerUuid(), original.name()));
        assertTrue(rewards.hasBindingHistory(original.name()));
        assertFalse(rewards.isLinked(original.playerUuid()));
        assertTrue(rewards.find(original).isEmpty());
        assertTrue(rewards.linkedMojangUuid(original.name()).isEmpty());
        assertFalse(rewards.link(original, 41L, config));
        Session fresh = new Session(original.mojangUuid(), UUID.randomUUID(), original.playerUuid(),
            original.name(), System.currentTimeMillis() + 1_000);
        assertFalse(rewards.link(fresh, 42L, config));
        assertTrue(rewards.link(fresh, 41L, config));
        assertTrue(rewards.isLinked(original.playerUuid()));
        var claim = rewards.find(fresh).orElseThrow();
        assertEquals(original.sessionId(), claim.firstSession());
        assertEquals(PremiumLoginStore.Status.COMPLETED, claim.pointsDelivery());
    }

    @Test
    void javaBindingRejectsMojangNameAndRegistrationChangesAndPromotesALowerTier() throws Exception {
        Session session = javaSession("RewardB");
        var rewards = new PremiumLoginStore(database);
        var config = javaReward();
        assertTrue(rewards.link(session, 31L, config));
        assertFalse(rewards.link(session, 32L, config));
        assertEquals(session.mojangUuid(), rewards.linkedMojangUuid(session.name()).orElseThrow());
        String otherCase = session.name().toLowerCase(java.util.Locale.ROOT);
        assertTrue(rewards.linkedMojangUuid(otherCase).isEmpty());
        assertFalse(rewards.link(new Session(UUID.randomUUID(), UUID.randomUUID(),
            PremiumLoginProof.offlineUuid(otherCase), otherCase, session.bindingRequestedAt()), 31L, config));
        assertFalse(rewards.link(new Session(session.mojangUuid(), UUID.randomUUID(),
            PremiumLoginProof.offlineUuid("OtherName"), "OtherName", session.bindingRequestedAt()), 31L, config));
        assertTrue(rewards.link(new Session(session.mojangUuid(), UUID.randomUUID(),
            session.playerUuid(), session.name(), session.bindingRequestedAt()), 31L, config));
        assertEquals(session.sessionId(), rewards.find(session).orElseThrow().firstSession());

        service.admin(new AdminMembershipRequest(
            AdminMembershipAction.GRANT, session.playerUuid(), "vip", 30, null, "test", "seed"
        )).toCompletableFuture().get(5, TimeUnit.SECONDS);
        service.grantPremiumLoginReward(session.mojangUuid(), session.playerUuid())
            .toCompletableFuture().get(5, TimeUnit.SECONDS);
        MembershipSummary summary = service.summary(session.playerUuid()).toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals("vip_plus", summary.active().tier().key());
        assertEquals(NOW.plusSeconds(14L * 86_400L), summary.active().expiresAt());
        assertEquals("vip", summary.paused().getFirst().tier().key());
    }

    private static Session javaSession(String name) {
        String uniqueName = name + UUID.randomUUID().toString().substring(0, 6);
        return new Session(UUID.randomUUID(), UUID.randomUUID(), PremiumLoginProof.offlineUuid(uniqueName), uniqueName, 1L);
    }

    private static PremiumLoginConfig javaReward() {
        return new PremiumLoginConfig(false, "", "login", "lobby", "lobby", null,
            new PremiumLoginConfig.Rewards("vip_plus", 14, 50, List.of()), false);
    }

    @Test
    void appliesCodeWithoutStackingAndRevalidatesBeforeCharging() throws Exception {
        UUID player=UUID.randomUUID();
        promotions.code=new Promotion(UUID.randomUUID(),"Code",null,2000,NOW.minusSeconds(60),NOW.plusSeconds(3600),0,"ACTIVE","TESTCODE");
        assertEquals(80,service.quote(player,"vip",1,UpgradeMode.NONE,"TESTCODE").toCompletableFuture().get().finalPricePoints());
        promotions.active=new Promotion(UUID.randomUUID(),"Better offer",null,5000,NOW.minusSeconds(60),NOW.plusSeconds(3600),0,"ACTIVE");
        assertEquals(50,service.quote(player,"vip",1,UpgradeMode.NONE,"TESTCODE").toCompletableFuture().get().finalPricePoints());
        promotions.code=null;
        org.junit.jupiter.api.Assertions.assertThrows(java.util.concurrent.ExecutionException.class,()->service.purchase(
            new MembershipPurchaseRequest(player,"vip",1,UpgradeMode.NONE,"code-revoked-"+player,"TEST","TESTCODE")).toCompletableFuture().get());
        assertEquals(0,points.debits.get());
    }

    @Test
    void purchasesWithCodeAndReplaysWithoutDoubleCharging() throws Exception {
        UUID player=UUID.randomUUID(); UUID promotion=UUID.randomUUID();
        promotions.code=new Promotion(promotion,"Code",null,2000,NOW.minusSeconds(60),NOW.plusSeconds(3600),0,"ACTIVE","TESTCODE");
        try (Connection connection=database.connection(); PreparedStatement insert=connection.prepareStatement(
            "INSERT INTO cct_promotions(promotion_id,name,scope,percent_off_bps,starts_at,ends_at,priority,status,created_by,reason) VALUES (?, 'Code', 'MEMBERSHIP_CODE_ALL', 2000, ?, ?, 0, 'ACTIVE', 'test', 'test')")) {
            insert.setBytes(1,UuidBinary.encode(promotion));
            insert.setObject(2,java.time.LocalDateTime.ofInstant(NOW.minusSeconds(60),ZoneOffset.UTC));
            insert.setObject(3,java.time.LocalDateTime.ofInstant(NOW.plusSeconds(3600),ZoneOffset.UTC)); insert.executeUpdate();
        }
        var request=new MembershipPurchaseRequest(player,"vip",1,UpgradeMode.NONE,"code-purchase-"+player,"TEST","TESTCODE");
        var first=service.purchase(request).toCompletableFuture().get();
        assertEquals(MembershipOrderStatus.COMPLETED,first.status()); assertEquals(80,first.finalPricePoints());
        assertEquals(first.orderId(),service.purchase(request).toCompletableFuture().get().orderId()); assertEquals(1,points.debits.get());
    }

    @Test
    void reservesLastCodeSlotAtomicallyAcrossConcurrentPlayers() throws Exception {
        UUID promotion=UUID.randomUUID();
        promotions.code=new Promotion(promotion,"Limited",null,2000,NOW.minusSeconds(60),NOW.plusSeconds(3600),0,"ACTIVE","LIMITED");
        try(Connection c=database.connection(); PreparedStatement insert=c.prepareStatement(
            "INSERT INTO cct_promotions(promotion_id,name,scope,percent_off_bps,starts_at,ends_at,priority,status,created_by,reason,discount_code,max_uses_per_player,max_players) VALUES (?, 'Limited', 'MEMBERSHIP_CODE_ALL', 2000, ?, ?, 0, 'ACTIVE', 'test', 'test', ?, 1, 1)")) {
            insert.setBytes(1,UuidBinary.encode(promotion)); insert.setObject(2,java.time.LocalDateTime.ofInstant(NOW.minusSeconds(60),ZoneOffset.UTC));
            insert.setObject(3,java.time.LocalDateTime.ofInstant(NOW.plusSeconds(3600),ZoneOffset.UTC));insert.setString(4,"LIMIT_"+promotion.toString().substring(0,8));insert.executeUpdate();
        }
        var first=service.purchase(new MembershipPurchaseRequest(UUID.randomUUID(),"vip",1,UpgradeMode.NONE,"limited-a-"+promotion,"TEST","LIMITED")).toCompletableFuture();
        var second=service.purchase(new MembershipPurchaseRequest(UUID.randomUUID(),"vip",1,UpgradeMode.NONE,"limited-b-"+promotion,"TEST","LIMITED")).toCompletableFuture();
        CompletableFuture.allOf(first,second).get(10,TimeUnit.SECONDS);
        assertEquals(1,List.of(first.get(),second.get()).stream().filter(o->o.status()==MembershipOrderStatus.COMPLETED).count());
        assertEquals(1,points.debits.get());
        assertTrue(List.of(first.get(),second.get()).stream().anyMatch(o->"DISCOUNT_CODE_LIMIT_REACHED".equals(o.errorCode())));
    }

    @Test
    void oncePerPlayerRejectsSecondOrderWithoutCharging() throws Exception {
        UUID promotion=UUID.randomUUID(),player=UUID.randomUUID();
        promotions.code=new Promotion(promotion,"Once",null,2000,NOW.minusSeconds(60),NOW.plusSeconds(3600),0,"ACTIVE","ONCE");
        try(Connection c=database.connection(); PreparedStatement insert=c.prepareStatement(
            "INSERT INTO cct_promotions(promotion_id,name,scope,percent_off_bps,starts_at,ends_at,priority,status,created_by,reason,discount_code,max_uses_per_player) VALUES (?, 'Once', 'MEMBERSHIP_CODE_ALL', 2000, ?, ?, 0, 'ACTIVE', 'test', 'test', ?, 1)")) {
            insert.setBytes(1,UuidBinary.encode(promotion)); insert.setObject(2,java.time.LocalDateTime.ofInstant(NOW.minusSeconds(60),ZoneOffset.UTC));
            insert.setObject(3,java.time.LocalDateTime.ofInstant(NOW.plusSeconds(3600),ZoneOffset.UTC));insert.setString(4,"ONCE_"+promotion.toString().substring(0,8));insert.executeUpdate();
        }
        assertEquals(MembershipOrderStatus.COMPLETED,service.purchase(new MembershipPurchaseRequest(player,"vip",1,UpgradeMode.NONE,"once-a-"+player,"TEST","ONCE")).toCompletableFuture().get().status());
        for(int i=0;i<3;i++) assertEquals(80,service.menu(player,"ONCE").toCompletableFuture().get().tiers().getFirst().quote().finalPricePoints());
        assertEquals("DISCOUNT_CODE_LIMIT_REACHED",service.purchase(new MembershipPurchaseRequest(player,"vip",1,UpgradeMode.NONE,"once-b-"+player,"TEST","ONCE")).toCompletableFuture().get().errorCode());
        assertEquals(1,points.debits.get());
    }

    @Test
    void unboundAndStaffCanPreviewButCannotPurchaseAndPreviewNeverConsumesUses() throws Exception {
        UUID player=UUID.randomUUID();
        promotions.code=new Promotion(UUID.randomUUID(),"Preview",null,2000,NOW.minusSeconds(60),NOW.plusSeconds(3600),0,"ACTIVE","PREVIEW");
        service=new JdbcMembershipService(store,points,promotions,
            ignored->CompletableFuture.completedFuture(new MembershipAccess(accessGroups)),Clock.fixed(NOW,ZoneOffset.UTC),60,365,
            java.util.Set.of("helper","mod","admin","owner"),java.util.Set.of("vip","vip_plus","mvp","mvp_plus"),true,projectionSignals::incrementAndGet);
        for(int i=0;i<3;i++) assertEquals(80,service.menu(player,"PREVIEW").toCompletableFuture().get().tiers().getFirst().quote().finalPricePoints());
        org.junit.jupiter.api.Assertions.assertThrows(java.util.concurrent.ExecutionException.class,()->service.purchase(
            new MembershipPurchaseRequest(player,"vip",1,UpgradeMode.NONE,"unbound-preview-"+player,"TEST","PREVIEW")).toCompletableFuture().get());
        try(Connection connection=database.connection();PreparedStatement insert=connection.prepareStatement("INSERT INTO qqbotauth_bindings(minecraft_uuid,minecraft_name,group_openid,member_openid,bound_at,verification_status) VALUES (?,'PreviewTest','preview-test',?,0,'VERIFIED')")) {
            insert.setString(1,player.toString());insert.setString(2,player.toString());insert.executeUpdate();
        }
        accessGroups=java.util.Set.of("admin");
        assertEquals(80,service.menu(player,"PREVIEW").toCompletableFuture().get().tiers().getFirst().quote().finalPricePoints());
        org.junit.jupiter.api.Assertions.assertThrows(java.util.concurrent.ExecutionException.class,()->service.purchase(
            new MembershipPurchaseRequest(player,"vip",1,UpgradeMode.NONE,"staff-preview-"+player,"TEST","PREVIEW")).toCompletableFuture().get());
        assertEquals(0,points.debits.get());
        assertEquals(new UUID(0,0),promotions.previewPlayer);
        try(Connection connection=database.connection();PreparedStatement query=connection.prepareStatement("SELECT COUNT(*) FROM cct_membership_orders WHERE player_uuid=?")) {
            query.setBytes(1,UuidBinary.encode(player));try(var result=query.executeQuery()) {result.next();assertEquals(0,result.getInt(1));}
        }
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
        assertEquals(40, quote.upgradeCreditPoints());
        assertEquals(280, quote.finalPricePoints());
    }

    @Test
    void chainedUpgradeKeepsPromotionalPriceBeforePriorRankCredit() throws Exception {
        UUID playerUuid = UUID.randomUUID();
        service.purchase(request(playerUuid, "vip", UpgradeMode.NONE, "chain-vip"))
            .toCompletableFuture().get(5, TimeUnit.SECONDS);
        promotions.active = new Promotion(
            UUID.randomUUID(), "八折", null, 2_000,
            NOW.minusSeconds(10), NOW.plusSeconds(3600), 10, "ACTIVE"
        );

        try (Connection connection=database.connection(); PreparedStatement insert=connection.prepareStatement(
            "INSERT INTO cct_promotions(promotion_id,name,scope,percent_off_bps,starts_at,ends_at,priority,status,created_by,reason) VALUES (?, 'Offer', 'MEMBERSHIP_ALL', 2000, ?, ?, 10, 'ACTIVE', 'test', 'test')")) {
            insert.setBytes(1,UuidBinary.encode(promotions.active.promotionId()));
            insert.setObject(2,java.time.LocalDateTime.ofInstant(NOW.minusSeconds(10),ZoneOffset.UTC));
            insert.setObject(3,java.time.LocalDateTime.ofInstant(NOW.plusSeconds(3600),ZoneOffset.UTC)); insert.executeUpdate();
        }

        MembershipOrderResult vipPlus = service.purchase(
            request(playerUuid, "vip_plus", UpgradeMode.CREDIT, "chain-vip-plus")
        ).toCompletableFuture().get(5, TimeUnit.SECONDS);
        MembershipQuote mvp = service.quote(playerUuid, "mvp", 1, UpgradeMode.CREDIT)
            .toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(80, vipPlus.finalPricePoints());
        assertEquals(320, mvp.discountedPricePoints());
        assertEquals(128, mvp.upgradeCreditPoints());
        assertEquals(192, mvp.finalPricePoints());
    }

    @Test
    void upgradeCreditOnlyConsumesTheCurrentlyActiveRank() throws Exception {
        UUID playerUuid = UUID.randomUUID();
        service.purchase(request(playerUuid, "vip", UpgradeMode.NONE, "single-credit-vip"))
            .toCompletableFuture().get(5, TimeUnit.SECONDS);
        service.purchase(request(playerUuid, "vip_plus", UpgradeMode.PAUSE, "single-credit-vip-plus"))
            .toCompletableFuture().get(5, TimeUnit.SECONDS);

        MembershipQuote quote = service.quote(playerUuid, "mvp", 1, UpgradeMode.CREDIT)
            .toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(160, quote.upgradeCreditPoints());
        assertEquals(240, quote.finalPricePoints());
        MembershipSummary beforePurchase = service.summary(playerUuid)
            .toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals(1, beforePurchase.paused().size());
        assertEquals("vip", beforePurchase.paused().getFirst().tier().key());
    }

    @Test
    void discountedUpgradeSucceedsWhenBalanceCoversFinalPriceButNotListPrice() throws Exception {
        UUID playerUuid = UUID.randomUUID();
        service.purchase(request(playerUuid, "vip", UpgradeMode.NONE, "final-price-vip"))
            .toCompletableFuture().get(5, TimeUnit.SECONDS);
        points.balance = 320;

        MembershipOrderResult result = service.purchase(
            request(playerUuid, "mvp", UpgradeMode.CREDIT, "final-price-mvp")
        ).toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(MembershipOrderStatus.COMPLETED, result.status());
        assertEquals(320, result.finalPricePoints());
        assertEquals(0, points.balance);
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

    @Test
    void socialBindingVipPausesForStaffAndResumesAfterStaffRoleIsRemoved() throws Exception {
        UUID playerUuid = UUID.randomUUID();
        MembershipSummary granted = service.grantSocialBindingReward(playerUuid)
            .toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals("vip", granted.active().tier().key());

        accessGroups = java.util.Set.of("helper");
        MembershipSummary paused = service.summary(playerUuid)
            .toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals(null, paused.active());
        assertEquals(1, paused.paused().size());
        assertEquals(7L * 86_400L, paused.paused().getFirst().remainingSeconds());

        accessGroups = java.util.Set.of("default");
        MembershipSummary resumed = service.summary(playerUuid)
            .toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals("vip", resumed.active().tier().key());
        assertEquals(NOW.plusSeconds(7L * 86_400L), resumed.active().expiresAt());
    }

    @Test
    void socialBindingVipStartsPausedWhenPlayerAlreadyBelongsToStaff() throws Exception {
        UUID playerUuid = UUID.randomUUID();
        accessGroups = java.util.Set.of("admin");

        MembershipSummary summary = service.grantSocialBindingReward(playerUuid)
            .toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(null, summary.active());
        assertEquals("vip", summary.paused().getFirst().tier().key());
        assertEquals(7L * 86_400L, summary.paused().getFirst().remainingSeconds());
    }

    @Test
    void concurrentSummaryReconciliationForTheSamePlayerCompletesWithoutDeadlock() throws Exception {
        UUID playerUuid = UUID.randomUUID();
        List<CompletableFuture<MembershipSummary>> requests = java.util.stream.IntStream.range(0, 24)
            .mapToObj(ignored -> service.summary(playerUuid).toCompletableFuture())
            .toList();

        CompletableFuture.allOf(requests.toArray(CompletableFuture[]::new))
            .get(10, TimeUnit.SECONDS);

        assertTrue(requests.stream().allMatch(request -> request.join().active() == null));
    }

    @Test
    void adminGrantedVipAlsoStartsPausedForStaff() throws Exception {
        UUID playerUuid = UUID.randomUUID();
        accessGroups = java.util.Set.of("owner");

        MembershipSummary summary = service.admin(new AdminMembershipRequest(
            AdminMembershipAction.GRANT,
            playerUuid,
            "vip",
            30,
            null,
            "console",
            "integration-test"
        )).toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(null, summary.active());
        assertEquals("vip", summary.paused().getFirst().tier().key());
        assertEquals(30L * 86_400L, summary.paused().getFirst().remainingSeconds());
    }

    @Test
    void staffSummaryRepairsLegacyMembershipGroupWithoutActiveEntitlement() throws Exception {
        UUID playerUuid = UUID.randomUUID();
        accessGroups = java.util.Set.of("admin", "vip");

        MembershipSummary summary = service.summary(playerUuid)
            .toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(null, summary.active());
        try (Connection connection = database.connection();
             PreparedStatement query = connection.prepareStatement("""
                 SELECT desired_group, status
                 FROM cct_luckperms_projections
                 WHERE player_uuid = ?
                 """)) {
            query.setBytes(1, UuidBinary.encode(playerUuid));
            try (java.sql.ResultSet result = query.executeQuery()) {
                assertTrue(result.next());
                assertEquals(null, result.getString("desired_group"));
                assertEquals("PENDING", result.getString("status"));
            }
        }
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
            key, name, priority, key, 30, price, 8_000,
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
        private volatile Promotion code;
        private volatile UUID previewPlayer;
        @Override
        public CompletionStage<Promotion> forCodeAny(String value,UUID playerUuid,Instant now) {
            previewPlayer=playerUuid; return forCode(value,"vip",now);
        }
        @Override
        public CompletionStage<Promotion> forCode(String value,String tier,Instant now) {
            return code != null && value.equals(code.discountCode()) ? CompletableFuture.completedFuture(code)
                : CompletableFuture.failedFuture(new IllegalArgumentException("Code unavailable"));
        }

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
