package cn.cctstudio.cctsystem.storage.mysql;

import cn.cctstudio.cctsystem.core.logging.CctLogger;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;

final class MigrationRunner {
    private static final String LOCK_NAME = "cctsystem-schema-migrations";
    private static final List<Migration> MIGRATIONS = List.of(
        new Migration(1, "foundation", "/db/migration/V1__foundation.sql"),
        new Migration(2, "exchange", "/db/migration/V2__exchange.sql"),
        new Migration(3, "membership_and_promotions", "/db/migration/V3__membership_and_promotions.sql"),
        new Migration(4, "redeem_codes", "/db/migration/V4__redeem_codes.sql"),
        new Migration(5, "nickname_profiles", "/db/migration/V5__nickname_profiles.sql"),
        new Migration(6, "delivery_rewards", "/db/migration/V6__delivery_rewards.sql"),
        new Migration(7, "network_vanish", "/db/migration/V7__network_vanish.sql")
    );

    private final DataSource dataSource;
    private final CctLogger logger;

    MigrationRunner(DataSource dataSource, CctLogger logger) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    void migrate() {
        try (Connection connection = dataSource.getConnection()) {
            acquireLock(connection);
            try {
                createMigrationTable(connection);
                for (Migration migration : MIGRATIONS) {
                    apply(connection, migration);
                }
            } finally {
                releaseLock(connection);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to migrate CCTSystem database", exception);
        }
    }

    private void apply(Connection connection, Migration migration) throws SQLException {
        String script = readScript(migration.resource());
        String checksum = sha256(script);
        String existingChecksum = existingChecksum(connection, migration.version());
        if (existingChecksum != null) {
            if (!existingChecksum.equals(checksum)) {
                throw new IllegalStateException("Checksum mismatch for database migration V" + migration.version());
            }
            return;
        }

        logger.info("Applying database migration V" + migration.version() + "__" + migration.name());
        for (String statement : SqlScriptParser.parse(script)) {
            try (Statement sql = connection.createStatement()) {
                sql.execute(statement);
            }
        }
        try (PreparedStatement insert = connection.prepareStatement(
            "INSERT INTO cct_schema_migrations(version, name, checksum) VALUES (?, ?, ?)"
        )) {
            insert.setInt(1, migration.version());
            insert.setString(2, migration.name());
            insert.setString(3, checksum);
            insert.executeUpdate();
        }
    }

    private static void createMigrationTable(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                CREATE TABLE IF NOT EXISTS cct_schema_migrations (
                    version INT NOT NULL PRIMARY KEY,
                    name VARCHAR(128) NOT NULL,
                    checksum CHAR(64) NOT NULL,
                    applied_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """);
        }
    }

    private static String existingChecksum(Connection connection, int version) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
            "SELECT checksum FROM cct_schema_migrations WHERE version = ?"
        )) {
            query.setInt(1, version);
            try (ResultSet result = query.executeQuery()) {
                return result.next() ? result.getString(1) : null;
            }
        }
    }

    private static void acquireLock(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT GET_LOCK(?, 30)")) {
            statement.setString(1, LOCK_NAME);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || result.getInt(1) != 1) {
                    throw new SQLException("Timed out acquiring the CCTSystem migration lock");
                }
            }
        }
    }

    private static void releaseLock(Connection connection) {
        try (PreparedStatement statement = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
            statement.setString(1, LOCK_NAME);
            statement.execute();
        } catch (SQLException ignored) {
            // Closing the connection also releases the MySQL named lock.
        }
    }

    private static String readScript(String resource) {
        try (InputStream input = MigrationRunner.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException("Missing database migration resource: " + resource);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read database migration: " + resource, exception);
        }
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record Migration(int version, String name, String resource) {
    }
}
