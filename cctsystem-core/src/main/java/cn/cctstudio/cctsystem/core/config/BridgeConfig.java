package cn.cctstudio.cctsystem.core.config;

public record BridgeConfig(
    boolean enabled,
    String url,
    String secret,
    int connectTimeoutSeconds,
    int requestTimeoutSeconds
) {
    public BridgeConfig {
        url = url == null ? "" : url.trim();
        secret = secret == null ? "" : secret;
        connectTimeoutSeconds = connectTimeoutSeconds <= 0 ? 10 : connectTimeoutSeconds;
        requestTimeoutSeconds = requestTimeoutSeconds <= 0 ? 8 : requestTimeoutSeconds;
    }
}
