package cn.cctstudio.cctsystem.membership;

import cn.cctstudio.cctsystem.core.provider.ProviderKey;

public final class MembershipAccessGatewayProvider {
    public static final String ID = "membership-access-gateway";
    public static final ProviderKey<MembershipAccessGateway> KEY = ProviderKey.of(
        ID,
        MembershipAccessGateway.class
    );

    private MembershipAccessGatewayProvider() {
    }
}
