package cn.cctstudio.cctsystem.auth;

import cn.cctstudio.cctsystem.core.provider.ProviderKey;

public final class AuthMeProvider {
    public static final String ID = "authme";
    public static final ProviderKey<PasswordVerifier> KEY = ProviderKey.of(ID, PasswordVerifier.class);

    private AuthMeProvider() {
    }
}
