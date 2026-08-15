package cn.cctstudio.cctsystem.membership;

import cn.cctstudio.cctsystem.core.provider.ProviderKey;

public final class MembershipPermissionGatewayProvider {
    public static final String ID = "membership-permission-gateway";
    public static final ProviderKey<MembershipPermissionGateway> KEY = ProviderKey.of(
        ID,
        MembershipPermissionGateway.class
    );

    private MembershipPermissionGatewayProvider() {
    }
}
