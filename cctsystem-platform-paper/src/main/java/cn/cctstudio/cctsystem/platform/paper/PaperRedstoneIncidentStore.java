package cn.cctstudio.cctsystem.platform.paper;

import cn.cctstudio.cctsystem.core.concurrent.CctExecutors;
import cn.cctstudio.cctsystem.core.logging.CctLogger;
import cn.cctstudio.cctsystem.core.provider.ProviderRegistry;
import cn.cctstudio.cctsystem.identity.UuidBinary;
import cn.cctstudio.cctsystem.storage.mysql.DatabaseAccess;
import cn.cctstudio.cctsystem.storage.mysql.DatabaseProvider;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.UUID;

final class PaperRedstoneIncidentStore {
    private final ProviderRegistry providers;
    private final CctExecutors executors;
    private final CctLogger logger;

    PaperRedstoneIncidentStore(
        ProviderRegistry providers,
        CctExecutors executors,
        CctLogger logger
    ) {
        this.providers = providers;
        this.executors = executors;
        this.logger = logger;
    }

    void record(
        String serverId,
        String world,
        int x,
        int y,
        int z,
        String nearbyPlayers
    ) {
        DatabaseAccess database = providers.find(DatabaseProvider.KEY).orElse(null);
        if (database == null) return;
        executors.blocking().execute(() -> {
            try (Connection connection = database.connection();
                 PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO cct_redstone_incidents(
                        incident_id, server_id, world_name, block_x, block_y, block_z,
                        nearby_players, detected_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(3))
                    """)) {
                insert.setBytes(1, UuidBinary.encode(UUID.randomUUID()));
                insert.setString(2, serverId);
                insert.setString(3, world);
                insert.setInt(4, x);
                insert.setInt(5, y);
                insert.setInt(6, z);
                insert.setString(7, nearbyPlayers);
                insert.executeUpdate();
            } catch (Exception exception) {
                logger.warn("Unable to persist high-frequency redstone incident", exception);
            }
        });
    }
}
