package cn.cctstudio.cctsystem.storage.mysql;

import java.sql.SQLException;
import java.util.UUID;

public final class SocialBindingLookup {
    private SocialBindingLookup() {}
    public record Status(boolean qq, boolean discord) {}

    public static boolean isBound(DatabaseAccess database, UUID playerUuid) throws SQLException {
        Status status = status(database, playerUuid);
        return status.qq() || status.discord();
    }

    public static Status status(DatabaseAccess database, UUID playerUuid) throws SQLException {
        try (var connection = database.connection();
             var query = connection.prepareStatement("""
                 SELECT EXISTS(SELECT 1 FROM qqbotauth_bindings
                    WHERE minecraft_uuid = ? AND verification_status = 'VERIFIED'),
                 EXISTS(SELECT 1 FROM qqbotauth_discord_bindings WHERE minecraft_uuid = ?)
                 """)) {
            query.setString(1, playerUuid.toString());
            query.setString(2, playerUuid.toString());
            try (var rows = query.executeQuery()) {
                if (!rows.next()) throw new SQLException("Missing social binding status");
                return new Status(rows.getBoolean(1), rows.getBoolean(2));
            }
        }
    }
}
