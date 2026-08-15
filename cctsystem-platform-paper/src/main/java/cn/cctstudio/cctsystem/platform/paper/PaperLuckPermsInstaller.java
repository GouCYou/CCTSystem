package cn.cctstudio.cctsystem.platform.paper;

import cn.cctstudio.cctsystem.core.CctRuntime;
import cn.cctstudio.cctsystem.luckperms.LuckPermsMembershipAccessGateway;
import cn.cctstudio.cctsystem.luckperms.LuckPermsMembershipGateway;
import cn.cctstudio.cctsystem.membership.MembershipAccessGatewayProvider;
import cn.cctstudio.cctsystem.membership.MembershipPermissionGatewayProvider;
import net.luckperms.api.LuckPerms;
import org.bukkit.Server;
import org.bukkit.plugin.RegisteredServiceProvider;

final class PaperLuckPermsInstaller {
    private PaperLuckPermsInstaller() {
    }

    static void install(CctRuntime runtime, Server server) {
        RegisteredServiceProvider<LuckPerms> registration = server.getServicesManager()
            .getRegistration(LuckPerms.class);
        if (registration == null) {
            return;
        }
        runtime.providers().register(
            MembershipPermissionGatewayProvider.KEY,
            new LuckPermsMembershipGateway(registration.getProvider())
        );
        runtime.providers().register(
            MembershipAccessGatewayProvider.KEY,
            new LuckPermsMembershipAccessGateway(registration.getProvider())
        );
    }
}
