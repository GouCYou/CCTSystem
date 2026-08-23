package cn.cctstudio.cctsystem.auth;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

public interface PasswordVerifier {
    AuthVerification verify(String playerName, String password);

    Optional<AccountDetails> accountDetails(String playerName);

    CompletionStage<Boolean> qqBound(UUID playerUuid);

    CompletionStage<SocialBindingStatus> socialBindings(UUID playerUuid);

    CompletionStage<String> bindDiscord(UUID playerUuid, String discordUserId, String discordUsername);

    CompletionStage<Boolean> unbindDiscord(UUID playerUuid);

    CompletionStage<QqBindingChallenge> startQqBinding(UUID playerUuid, String playerName);

    CompletionStage<Boolean> unbindQq(UUID playerUuid);

    void changePassword(String playerName, String newPassword);
}
