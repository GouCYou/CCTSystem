package cn.cctstudio.cctsystem.membership;

import cn.cctstudio.cctsystem.core.provider.ProviderKey;

public final class MembershipServiceProvider {
    public static final String ID = "membership-service";
    public static final ProviderKey<MembershipService> KEY = ProviderKey.of(ID, MembershipService.class);

    private MembershipServiceProvider() {
    }
}
