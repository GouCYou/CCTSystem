package cn.cctstudio.cctsystem.core.config;

public record ServerListConfig(String versionName) {
    public static ServerListConfig defaults() {
        return new ServerListConfig("CCTStudio 1.21 - 26.2");
    }

    public ServerListConfig {
        versionName = versionName == null ? "" : versionName.strip();
    }
}
