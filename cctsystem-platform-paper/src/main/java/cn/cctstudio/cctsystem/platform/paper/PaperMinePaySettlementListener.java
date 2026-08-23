package cn.cctstudio.cctsystem.platform.paper;

import cn.cctstudio.cctsystem.core.logging.CctLogger;
import cn.cctstudio.cctsystem.core.provider.ProviderRegistry;
import cn.cctstudio.cctsystem.identity.IdentityProvider;
import cn.cctstudio.cctsystem.points.PointsMutationDisposition;
import cn.cctstudio.cctsystem.points.PointsServiceProvider;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import top.minepay.api.events.MinePaySuccessEvent;
import top.minepay.bean.TradeInfo;
import top.minepay.common.enums.TradeType;

final class PaperMinePaySettlementListener implements Listener {
    private static final String PLUGIN_NAME = "MinePay";
    private final ProviderRegistry providers;
    private final CctLogger logger;

    private PaperMinePaySettlementListener(ProviderRegistry providers, CctLogger logger) {
        this.providers = Objects.requireNonNull(providers, "providers");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    static void install(JavaPlugin plugin, ProviderRegistry providers, CctLogger logger) {
        if (!plugin.getServer().getPluginManager().isPluginEnabled(PLUGIN_NAME)) return;
        plugin.getServer().getPluginManager().registerEvents(
            new PaperMinePaySettlementListener(providers, logger),
            plugin
        );
        logger.info("CCTSystem immediate point-recharge settlement is enabled");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPaymentSucceeded(MinePaySuccessEvent event) {
        TradeInfo trade = event.getTradeInfo();
        if (trade == null || trade.getTradeType() != TradeType.POINT) return;
        String order = normalizeOrder(trade.getOrder());
        String playerName = trade.getPlayerName() == null ? "" : trade.getPlayerName().trim();
        int points = pointAmount(trade);
        if (order == null || playerName.isBlank() || points < 1) {
            logger.warn("Ignored invalid point recharge success event");
            return;
        }
        var identities = providers.find(IdentityProvider.KEY).orElse(null);
        var pointService = providers.find(PointsServiceProvider.KEY).orElse(null);
        if (identities == null || pointService == null) {
            logger.warn("Point recharge could not settle because CCTSystem services are not ready");
            return;
        }
        UUID operationId = UUID.nameUUIDFromBytes(
            ("cct-recharge:" + order).getBytes(StandardCharsets.UTF_8)
        );
        identities.findByName(playerName).thenCompose(identity -> {
            if (identity.isEmpty()) {
                throw new IllegalStateException("Unknown recharge player: " + playerName);
            }
            return pointService.credit(
                identity.orElseThrow().playerUuid(),
                points,
                operationId,
                "RECHARGE",
                order
            );
        }).whenComplete((result, failure) -> {
            if (failure != null) {
                logger.warn("Point recharge settlement failed for order " + order, failure);
            } else if (result.disposition() == PointsMutationDisposition.COMPLETED) {
                logger.info("Point recharge settled: order=" + order
                    + ", player=" + playerName + ", points=" + points);
            } else {
                logger.warn("Point recharge requires review: order=" + order
                    + ", disposition=" + result.disposition().name().toLowerCase(Locale.ROOT));
            }
        });
    }

    private static int pointAmount(TradeInfo trade) {
        try {
            Object kitItem = trade.getClass().getMethod("getKitItem").invoke(trade);
            if (kitItem != null) {
                Object points = kitItem.getClass().getMethod("getPoint").invoke(kitItem);
                if (points instanceof Number number && number.intValue() > 0) {
                    return number.intValue();
                }
            }
        } catch (ReflectiveOperationException ignored) {
            // Older API-only compile artifacts do not expose the web-order point item.
        }
        return trade.getCount();
    }

    private static String normalizeOrder(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        return normalized.isEmpty() || normalized.length() > 80 ? null : normalized;
    }
}
