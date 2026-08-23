package cn.cctstudio.cctsystem.auth;

public record SocialBindingStatus(
    boolean qqBound,
    boolean discordBound,
    String discordUsername
) {
    public boolean anyBound() {
        return qqBound || discordBound;
    }
}
