package cn.cctstudio.cctsystem.platform.paper;

import cn.cctstudio.cctsystem.core.CctRuntime;
import cn.cctstudio.cctsystem.playerpoints.PlayerPointsGateway;
import cn.cctstudio.cctsystem.points.PointsGatewayProvider;
import org.black_ixx.playerpoints.PlayerPoints;
import org.bukkit.plugin.Plugin;

final class PaperPlayerPointsInstaller {
    private PaperPlayerPointsInstaller() {
    }

    static void install(CctRuntime runtime, Plugin plugin, PaperTaskExecutor platformTasks) {
        if (!(plugin instanceof PlayerPoints playerPoints)) {
            throw new IllegalStateException("Installed PlayerPoints plugin has an incompatible main class");
        }
        runtime.providers().register(
            PointsGatewayProvider.KEY,
            new PlayerPointsGateway(playerPoints.getAPI(), platformTasks)
        );
    }
}
