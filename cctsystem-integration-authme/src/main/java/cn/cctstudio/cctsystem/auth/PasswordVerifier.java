package cn.cctstudio.cctsystem.auth;

public interface PasswordVerifier {
    AuthVerification verify(String playerName, String password);
}
