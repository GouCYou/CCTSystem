package cn.cctstudio.cctsystem.auth;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public interface SocialBindingGateway {
    CompletionStage<SocialBindingStatus> status(UUID playerUuid);

    CompletionStage<String> bindDiscord(UUID playerUuid, String discordUserId, String discordUsername);

    CompletionStage<Boolean> unbindDiscord(UUID playerUuid);

    CompletionStage<QqBindingChallenge> startQqBinding(UUID playerUuid, String playerName);

    CompletionStage<Boolean> unbindQq(UUID playerUuid);

    static SocialBindingGateway unavailable() {
        return new SocialBindingGateway() {
            @Override
            public CompletionStage<SocialBindingStatus> status(UUID playerUuid) {
                return CompletableFuture.completedFuture(new SocialBindingStatus(false, false, ""));
            }

            @Override
            public CompletionStage<String> bindDiscord(UUID playerUuid, String userId, String username) {
                return CompletableFuture.failedFuture(new IllegalStateException("Social binding service unavailable"));
            }

            @Override
            public CompletionStage<Boolean> unbindDiscord(UUID playerUuid) {
                return CompletableFuture.failedFuture(new IllegalStateException("Social binding service unavailable"));
            }

            @Override
            public CompletionStage<QqBindingChallenge> startQqBinding(UUID playerUuid, String playerName) {
                return CompletableFuture.failedFuture(new IllegalStateException("QQ binding unavailable"));
            }

            @Override
            public CompletionStage<Boolean> unbindQq(UUID playerUuid) {
                return CompletableFuture.failedFuture(new IllegalStateException("QQ binding unavailable"));
            }
        };
    }
}
