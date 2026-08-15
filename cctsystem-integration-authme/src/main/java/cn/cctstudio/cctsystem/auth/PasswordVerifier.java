package cn.cctstudio.cctsystem.auth;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

public interface PasswordVerifier {
    AuthVerification verify(String playerName, String password);

    Optional<AccountDetails> accountDetails(String playerName);

    CompletionStage<Boolean> qqBound(UUID playerUuid);

    void changePassword(String playerName, String newPassword);
}
