package cn.cctstudio.cctsystem.skins;

import cn.cctstudio.cctsystem.core.CctRuntime;
import net.skinsrestorer.api.SkinsRestorer;
import net.skinsrestorer.api.SkinsRestorerProvider;

public final class SkinsRestorerInstaller {
    private SkinsRestorerInstaller() {
    }

    public static void install(CctRuntime runtime) {
        SkinsRestorer api = SkinsRestorerProvider.get();
        runtime.providers().register(
            SkinAvatarServiceProvider.KEY,
            new SkinsRestorerAvatarService(api, runtime.executors())
        );
    }
}
