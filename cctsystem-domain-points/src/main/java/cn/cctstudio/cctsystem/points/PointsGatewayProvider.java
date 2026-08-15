package cn.cctstudio.cctsystem.points;

import cn.cctstudio.cctsystem.core.provider.ProviderKey;

public final class PointsGatewayProvider {
    public static final String ID = "playerpoints";
    public static final ProviderKey<PointsGateway> KEY = ProviderKey.of(ID, PointsGateway.class);

    private PointsGatewayProvider() {
    }
}
