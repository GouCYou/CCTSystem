package cn.cctstudio.cctsystem.skins;

import cn.cctstudio.cctsystem.core.provider.ProviderKey;

public final class SkinAvatarServiceProvider {
    public static final String ID = "skin-avatar-service";
    public static final ProviderKey<SkinAvatarService> KEY = ProviderKey.of(
        ID, SkinAvatarService.class
    );

    private SkinAvatarServiceProvider() {
    }
}
