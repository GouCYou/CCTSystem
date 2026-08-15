package cn.cctstudio.cctsystem.core.config;

public record DatabaseConfig(
    boolean enabled,
    String jdbcUrl,
    String username,
    String password,
    int maximumPoolSize,
    long connectionTimeoutMs
) {
    public DatabaseConfig {
        jdbcUrl = jdbcUrl == null ? "" : jdbcUrl.trim();
        username = username == null ? "" : username.trim();
        password = password == null ? "" : password;
        maximumPoolSize = maximumPoolSize <= 0 ? 6 : maximumPoolSize;
        connectionTimeoutMs = connectionTimeoutMs <= 0 ? 5_000L : connectionTimeoutMs;
    }
}
