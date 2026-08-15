package cn.cctstudio.cctsystem.platform.paper;

import cn.cctstudio.cctsystem.auth.AuthMePasswordVerifier;
import cn.cctstudio.cctsystem.auth.AuthMeProvider;
import cn.cctstudio.cctsystem.core.CctRuntime;
import fr.xephi.authme.api.v3.AuthMeApi;

final class PaperAuthMeInstaller {
    private PaperAuthMeInstaller() {
    }

    static void install(CctRuntime runtime) {
        runtime.providers().register(
            AuthMeProvider.KEY,
            new AuthMePasswordVerifier(AuthMeApi.getInstance())
        );
    }
}
