package cn.cctstudio.cctsystem.promotion;

import cn.cctstudio.cctsystem.core.concurrent.CctExecutors;
import cn.cctstudio.cctsystem.storage.mysql.DatabaseAccess;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="CCTSYSTEM_TEST_MYSQL_URL",matches=".+")
class DiscountCodeIntegrationTest {
    private final DatabaseAccess database = new DatabaseAccess() {
        public Connection connection() throws SQLException { return DriverManager.getConnection(System.getenv("CCTSYSTEM_TEST_MYSQL_URL"),System.getenv("CCTSYSTEM_TEST_MYSQL_USER"),System.getenv("CCTSYSTEM_TEST_MYSQL_PASSWORD")); }
        public boolean healthy() { return true; }
    };
    @Test
    void codesStayPrivateRespectDatesAndTargetsAndCanBeEditedAndDeleted() throws Exception {
        try (Connection c=database.connection(); var statement=c.createStatement()) {
            statement.executeUpdate("INSERT IGNORE INTO cct_membership_tiers(tier_key,display_name,priority,luckperms_group,duration_days,price_points,upgrade_credit_rate_bps,display_material,benefits_json,enabled) VALUES ('vip','VIP',100,'vip',30,100,8000,'GOLD_INGOT','[]',1)");
        }
        try (var executors = new CctExecutors()) {
            var service = new JdbcPromotionService(database,executors);
            Instant now = Instant.now(); String code="测试_"+UUID.randomUUID().toString().substring(0,8).toUpperCase(java.util.Locale.ROOT);
            var request = new CreatePromotionRequest("Test code","vip",2000,now.minusSeconds(60),Instant.parse("9999-12-31T00:00:00Z"),0,"test","test",code);
            Promotion created=service.create(request).toCompletableFuture().join();
            assertEquals(code,created.discountCode());
            assertThrows(CompletionException.class,()->service.create(new CreatePromotionRequest("Duplicate","vip",2000,now.minusSeconds(60),now.plusSeconds(3600),0,"test","test",code.toLowerCase())).toCompletableFuture().join());
            assertEquals(created.promotionId(),service.forCode(code.toLowerCase(),"vip",now).toCompletableFuture().join().promotionId());
            assertTrue(service.listCurrent(now).toCompletableFuture().join().stream().noneMatch(p->created.promotionId().equals(p.promotionId())));
            assertNotEquals(created.promotionId(),service.activeForMembership("vip",now).toCompletableFuture().join().promotionId());
            assertThrows(CompletionException.class,()->service.forCode(code,"mvp",now).toCompletableFuture().join());
            assertThrows(CompletionException.class,()->service.forCode(code,"vip",now.minusSeconds(120)).toCompletableFuture().join());
            var updated=new CreatePromotionRequest("Updated",null,4000,now.minusSeconds(60),now.plusSeconds(60),0,"test","test",code);
            service.updateCode(created.promotionId(),updated).toCompletableFuture().join();
            assertEquals(4000,service.forCode(code,"mvp",now).toCompletableFuture().join().percentOffBps());
            assertThrows(CompletionException.class,()->service.forCode(code,"vip",now.plusSeconds(61)).toCompletableFuture().join());
            assertTrue(service.deleteCode(created.promotionId()).toCompletableFuture().join());
            assertThrows(CompletionException.class,()->service.forCode(code,"vip",now).toCompletableFuture().join());
            assertTrue(service.listCodes(now).toCompletableFuture().join().stream().noneMatch(p->created.promotionId().equals(p.promotionId())));
            var replacement=service.create(request).toCompletableFuture().join();
            assertNotEquals(created.promotionId(),replacement.promotionId());
        }
    }
}
