package cn.cctstudio.cctsystem.auth;

import fr.xephi.authme.api.v3.AuthMeApi;
import fr.xephi.authme.api.v3.AuthMePlayer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

public final class AuthMePasswordVerifier implements PasswordVerifier {
    private final AuthMeApi authMe;
    private final Function<UUID, CompletionStage<Boolean>> qqBindingLookup;

    public AuthMePasswordVerifier(AuthMeApi authMe) {
        this(authMe, ignored -> CompletableFuture.completedFuture(false));
    }

    public AuthMePasswordVerifier(
        AuthMeApi authMe,
        Function<UUID, CompletionStage<Boolean>> qqBindingLookup
    ) {
        this.authMe = Objects.requireNonNull(authMe, "authMe");
        this.qqBindingLookup = Objects.requireNonNull(qqBindingLookup, "qqBindingLookup");
    }

    @Override
    public AuthVerification verify(String playerName, String password) {
        if (!authMe.checkPassword(playerName, password)) {
            return AuthVerification.rejected(playerName);
        }
        Optional<AuthMePlayer> player = authMe.getPlayerInfo(playerName);
        if (player.isEmpty()) {
            return AuthVerification.rejected(playerName);
        }
        AuthMePlayer info = player.orElseThrow();
        UUID playerUuid = info.getUuid().orElseGet(() -> offlineUuid(info.getName()));
        return new AuthVerification(true, info.getName(), Optional.of(playerUuid));
    }

    @Override
    public Optional<AccountDetails> accountDetails(String playerName) {
        return authMe.getPlayerInfo(playerName).map(info -> new AccountDetails(
            info.getEmail(),
            info.getRegistrationDate(),
            info.getLastLoginDate(),
            info.getLastLoginIpAddress()
        ));
    }

    @Override
    public CompletionStage<Boolean> qqBound(UUID playerUuid) {
        return qqBindingLookup.apply(playerUuid).exceptionally(ignored -> false);
    }

    @Override
    public void changePassword(String playerName, String newPassword) {
        authMe.changePassword(playerName, newPassword);
    }

    static UUID offlineUuid(String playerName) {
        return UUID.nameUUIDFromBytes(
            ("OfflinePlayer:" + playerName).getBytes(StandardCharsets.UTF_8)
        );
    }
}
