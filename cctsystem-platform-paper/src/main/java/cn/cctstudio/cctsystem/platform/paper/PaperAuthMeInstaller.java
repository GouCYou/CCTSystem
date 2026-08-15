package cn.cctstudio.cctsystem.platform.paper;

import cn.cctstudio.cctsystem.auth.AuthMePasswordVerifier;
import cn.cctstudio.cctsystem.auth.AuthMeProvider;
import cn.cctstudio.cctsystem.core.CctRuntime;
import fr.xephi.authme.api.v3.AuthMeApi;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

final class PaperAuthMeInstaller {
    private PaperAuthMeInstaller() {
    }

    static void install(CctRuntime runtime) {
        runtime.providers().register(
            AuthMeProvider.KEY,
            new AuthMePasswordVerifier(AuthMeApi.getInstance(), PaperAuthMeInstaller::qqBound)
        );
    }

    private static CompletionStage<Boolean> qqBound(UUID playerUuid) {
        Plugin plugin = Bukkit.getPluginManager().getPlugin("QQBotAuth");
        if (plugin == null || !plugin.isEnabled()) {
            return CompletableFuture.completedFuture(false);
        }
        try {
            Method method = plugin.getClass().getMethod("isMinecraftBound", UUID.class);
            Object result = method.invoke(plugin, playerUuid);
            if (result instanceof CompletionStage<?> stage) {
                return stage.thenApply(Boolean.TRUE::equals);
            }
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException ignored) {
            // Older QQBotAuth versions do not expose the read-only integration point.
        }
        return CompletableFuture.completedFuture(false);
    }
}
