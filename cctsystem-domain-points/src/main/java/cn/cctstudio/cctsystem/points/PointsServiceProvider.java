package cn.cctstudio.cctsystem.points;

import cn.cctstudio.cctsystem.core.provider.ProviderKey;

public final class PointsServiceProvider {
    public static final String ID = "points-service";
    public static final ProviderKey<PointsService> KEY = ProviderKey.of(ID, PointsService.class);

    private PointsServiceProvider() {
    }
}
