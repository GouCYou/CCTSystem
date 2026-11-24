package cn.cctstudio.cctsystem.storage.mysql;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import cn.cctstudio.cctsystem.storage.mysql.PlayerMailbox.*;

@EnabledIfEnvironmentVariable(named = "CCTSYSTEM_TEST_MYSQL_URL", matches = ".+")
final class PlayerMailboxIntegrationTest {
    private final PlayerMailbox store = new PlayerMailbox(new DatabaseAccess() {
        @Override public Connection connection() throws SQLException {
            return DriverManager.getConnection(System.getenv("CCTSYSTEM_TEST_MYSQL_URL"),
                System.getenv("CCTSYSTEM_TEST_MYSQL_USER"), System.getenv("CCTSYSTEM_TEST_MYSQL_PASSWORD"));
        }
        @Override public boolean healthy() { return true; }
    });

    @Test void editsBeforeClaimAndAllowsOnlyTheRecipientToClaimOnce() throws Exception {
        UUID player = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        var coins = new Reward(RewardType.SURVIVAL_COINS, 1000, null);
        var points = new Reward(RewardType.POINTS, 20, null);
        store.send(id, player, new Draft("邮件", "补给", Instant.now().plusSeconds(60).toEpochMilli(), List.of(coins)), "test");
        assertTrue(store.edit(id, new Draft("新标题", "补给", null, List.of(points))));
        assertTrue(store.claim(UUID.randomUUID(), id).isEmpty());
        var claimed = store.claim(player, id).orElseThrow();
        assertNotNull(claimed.readAt());
        assertNotNull(claimed.claimedAt());
        assertTrue(store.claim(player, id).isEmpty());
        assertFalse(store.edit(id, new Draft("已领取的邮件", "", null, List.of(coins))));
        assertEquals(List.of(points), store.list(player, 0, 20).getFirst().draft().rewards());
    }

    @Test void expiredMailRemainsVisibleAndCannotBeClaimed() throws Exception {
        UUID player = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        var points = new Reward(RewardType.POINTS, 20, null);
        store.send(id, player, new Draft("限时邮件", "", Instant.now().plusMillis(600).toEpochMilli(), List.of(points)), "test");
        Thread.sleep(650);
        assertTrue(store.claim(player, id).isEmpty());
        assertTrue(store.read(player, id).isEmpty());
        assertEquals(0, store.claimAll(player));
        assertEquals(0, store.inboxCount(player));
        assertNull(store.list(player, 0, 20).getFirst().claimedAt());
    }

    @Test void readOnlyMailIsRemovedFromTheInboxAfterTheRecipientReadsIt() throws Exception {
        UUID player = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        var draft = new Draft("通知", "活动即将开始", null, List.of());
        store.send(id, player, draft, "test");
        assertEquals(1, store.inboxCount(player));
        assertTrue(store.read(UUID.randomUUID(), id).isEmpty());
        assertTrue(store.claim(player, id).isEmpty());
        assertEquals(0, store.claimAll(player));
        var mail = store.read(player, id).orElseThrow();
        assertEquals(draft, mail.draft());
        assertNotNull(mail.readAt());
        assertNull(mail.claimedAt());
        assertEquals(mail.readAt(), store.read(player, id).orElseThrow().readAt());
        assertEquals(0, store.inboxCount(player));
        assertEquals(new InboxPage(List.of(), 0, 0, 0), store.inbox(player, 5, 5));
    }

    @Test void bulkClaimPreservesUnreadMailAndOnlyAwardsTheRecipientsAttachmentsOnce() throws Exception {
        UUID player = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        var letter = new Draft("通知", "活动时间", null, List.of());
        var reward = new Draft("点券", "活动奖励", null, List.of(new Reward(RewardType.POINTS, 20, null)));
        UUID readReward = store.send(UUID.randomUUID(), player, reward, "test");
        UUID unreadReward = store.send(UUID.randomUUID(), player, reward, "test");
        UUID unreadLetter = store.send(UUID.randomUUID(), player, letter, "test");
        UUID otherReward = store.send(UUID.randomUUID(), other, reward, "test");
        store.read(player, readReward).orElseThrow();
        assertEquals(3, store.inboxCount(player));
        var firstPage = store.inbox(player, 0, 2);
        assertEquals(2, firstPage.totalPages());
        assertEquals(3, firstPage.totalMails());
        assertEquals(2, firstPage.mails().size());
        var lastPage = store.inbox(player, 9, 2);
        assertEquals(1, lastPage.page());
        assertEquals(1, lastPage.mails().size());
        try (var pool = Executors.newFixedThreadPool(2)) {
            var bulk = pool.submit(() -> store.claimAll(player));
            var single = pool.submit(() -> store.claim(player, readReward));
            assertEquals(2, bulk.get() + (single.get().isPresent() ? 1 : 0));
        }
        assertEquals(0, store.claimAll(player));
        assertEquals(2, store.inboxCount(player));
        assertEquals(1, store.inbox(player, 0, 2).totalPages());
        assertEquals(0, store.inbox(player, 1, 2).page());
        assertEquals(Set.of(unreadReward, unreadLetter),
            Set.copyOf(store.inbox(player, 0, 5).mails().stream().map(Mail::id).toList()));
        assertNull(store.list(other, 0, 5).getFirst().claimedAt());
        assertTrue(store.claim(other, otherReward).isPresent());
    }

    @Test void compensationCreatesOnePrizePerAffectedPlayerPerIncident() throws Exception {
        UUID affected = UUID.randomUUID();
        var config = new LagSettings(true, List.of(new Reward(RewardType.SURVIVAL_COINS, 1000, null)));
        store.saveSettings(null);
        store.recovered();
        store.compensate(Set.of(affected), config);
        store.compensate(Set.of(affected), config);
        var inbox = store.list(affected, 0, 20);
        assertEquals(1, inbox.size());
        assertNotNull(inbox.getFirst().claimedAt());
        assertEquals(0, store.inboxCount(affected));
        store.recovered();
        store.compensate(Set.of(affected), config);
        assertEquals(2, store.list(affected, 0, 20).size());
        store.recovered();
    }
}
