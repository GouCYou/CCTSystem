package cn.cctstudio.cctsystem.storage.mysql;

import cn.cctstudio.cctsystem.core.provider.ProviderKey;

public final class DatabaseProvider {
    public static final String ID = "database";
    public static final ProviderKey<DatabaseAccess> KEY = ProviderKey.of(ID, DatabaseAccess.class);

    private DatabaseProvider() {
    }
}
