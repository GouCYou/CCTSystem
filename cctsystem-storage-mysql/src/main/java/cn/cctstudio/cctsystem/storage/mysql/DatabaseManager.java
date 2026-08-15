package cn.cctstudio.cctsystem.storage.mysql;

import cn.cctstudio.cctsystem.core.concurrent.CctExecutors;
import cn.cctstudio.cctsystem.core.config.DatabaseConfig;
import cn.cctstudio.cctsystem.core.lifecycle.LifecycleComponent;
import cn.cctstudio.cctsystem.core.logging.CctLogger;
import cn.cctstudio.cctsystem.core.provider.ProviderRegistry;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

public final class DatabaseManager implements LifecycleComponent, DatabaseAccess {
    private final String nodeId;
    private final DatabaseConfig config;
    private final ProviderRegistry providers;
    private final CctExecutors executors;
    private final CctLogger logger;
    private final AtomicReference<HikariDataSource> dataSource = new AtomicReference<>();

    public DatabaseManager(
        String nodeId,
        DatabaseConfig config,
        ProviderRegistry providers,
        CctExecutors executors,
        CctLogger logger
    ) {
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
        this.config = Objects.requireNonNull(config, "config");
        this.providers = Objects.requireNonNull(providers, "providers");
        this.executors = Objects.requireNonNull(executors, "executors");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public String id() {
        return "mysql";
    }

    @Override
    public CompletableFuture<Void> start() {
        return CompletableFuture.runAsync(() -> {
            HikariDataSource created = new HikariDataSource(createHikariConfig());
            if (!dataSource.compareAndSet(null, created)) {
                created.close();
                throw new IllegalStateException("Database manager is already started");
            }
            try {
                new MigrationRunner(created, logger).migrate();
                providers.register(DatabaseProvider.KEY, this);
                logger.info("MySQL connection pool ready for node " + nodeId);
            } catch (RuntimeException exception) {
                dataSource.compareAndSet(created, null);
                created.close();
                throw exception;
            }
        }, executors.blocking());
    }

    @Override
    public CompletableFuture<Void> stop() {
        return CompletableFuture.runAsync(() -> {
            HikariDataSource existing = dataSource.getAndSet(null);
            if (existing != null) {
                existing.close();
            }
        }, executors.blocking());
    }

    @Override
    public Connection connection() throws SQLException {
        HikariDataSource existing = dataSource.get();
        if (existing == null) {
            throw new SQLException("CCTSystem database is not available");
        }
        return existing.getConnection();
    }

    @Override
    public boolean healthy() {
        try (Connection connection = connection()) {
            return connection.isValid(2);
        } catch (SQLException exception) {
            return false;
        }
    }

    private HikariConfig createHikariConfig() {
        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("CCTSystem-" + nodeId);
        hikari.setJdbcUrl(config.jdbcUrl());
        hikari.setUsername(config.username());
        hikari.setPassword(config.password());
        hikari.setMaximumPoolSize(config.maximumPoolSize());
        hikari.setMinimumIdle(0);
        hikari.setConnectionTimeout(config.connectionTimeoutMs());
        hikari.setValidationTimeout(Math.min(3_000L, config.connectionTimeoutMs()));
        hikari.setInitializationFailTimeout(config.connectionTimeoutMs());
        hikari.setAutoCommit(true);
        hikari.addDataSourceProperty("cachePrepStmts", "true");
        hikari.addDataSourceProperty("prepStmtCacheSize", "250");
        hikari.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        hikari.addDataSourceProperty("useServerPrepStmts", "true");
        hikari.addDataSourceProperty("rewriteBatchedStatements", "true");
        return hikari;
    }
}
