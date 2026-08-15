package cn.cctstudio.cctsystem.auth;

import fr.xephi.authme.api.v3.AuthMeApi;
import fr.xephi.authme.api.v3.AuthMePlayer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class AuthMePasswordVerifier implements PasswordVerifier {
    private final AuthMeApi authMe;

    public AuthMePasswordVerifier(AuthMeApi authMe) {
        this.authMe = Objects.requireNonNull(authMe, "authMe");
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

    static UUID offlineUuid(String playerName) {
        return UUID.nameUUIDFromBytes(
            ("OfflinePlayer:" + playerName).getBytes(StandardCharsets.UTF_8)
        );
    }
}
