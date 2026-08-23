package cn.cctstudio.cctsystem.platform.paper;

import cn.cctstudio.cctsystem.auth.AuthMePasswordVerifier;
import cn.cctstudio.cctsystem.auth.AuthMeProvider;
import cn.cctstudio.cctsystem.auth.SocialBindingGateway;
import cn.cctstudio.cctsystem.auth.SocialBindingStatus;
import cn.cctstudio.cctsystem.auth.QqBindingChallenge;
import java.time.Instant;
import java.util.Map;
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
            new AuthMePasswordVerifier(AuthMeApi.getInstance(), new ReflectiveSocialBindingGateway())
        );
    }

    private static final class ReflectiveSocialBindingGateway implements SocialBindingGateway {
        @Override
        public CompletionStage<SocialBindingStatus> status(UUID playerUuid) {
            Plugin plugin = plugin();
            if (plugin == null) {
                return CompletableFuture.completedFuture(new SocialBindingStatus(false, false, ""));
            }
            CompletionStage<Boolean> qq = invoke(plugin, "isMinecraftBound",
                new Class<?>[]{UUID.class}, playerUuid).thenApply(Boolean.TRUE::equals);
            CompletionStage<Boolean> discord = invoke(plugin, "isMinecraftDiscordBound",
                new Class<?>[]{UUID.class}, playerUuid).thenApply(Boolean.TRUE::equals);
            CompletionStage<String> username = invoke(plugin, "minecraftDiscordUsername",
                new Class<?>[]{UUID.class}, playerUuid).thenApply(value -> value instanceof String text ? text : "");
            return qq.thenCombine(discord, BindingFlags::new)
                .thenCombine(username, (flags, name) ->
                    new SocialBindingStatus(flags.qq(), flags.discord(), name));
        }

        @Override
        public CompletionStage<String> bindDiscord(UUID playerUuid, String userId, String username) {
            Plugin plugin = requirePlugin();
            return invoke(plugin, "bindMinecraftDiscord",
                new Class<?>[]{UUID.class, String.class, String.class}, playerUuid, userId, username)
                .thenApply(String::valueOf);
        }

        @Override
        public CompletionStage<Boolean> unbindDiscord(UUID playerUuid) {
            Plugin plugin = requirePlugin();
            return invoke(plugin, "unbindMinecraftDiscord", new Class<?>[]{UUID.class}, playerUuid)
                .thenApply(Boolean.TRUE::equals);
        }

        @Override
        public CompletionStage<QqBindingChallenge> startQqBinding(UUID playerUuid, String playerName) {
            Plugin plugin = requirePlugin();
            return invoke(plugin, "createMinecraftQqVerification",
                new Class<?>[]{UUID.class, String.class}, playerUuid, playerName)
                .thenApply(value -> {
                    if (!(value instanceof Map<?, ?> map)) {
                        throw new IllegalStateException("QQBotAuth returned an invalid QQ challenge");
                    }
                    Object code = map.get("code");
                    Object expiresAt = map.get("expiresAt");
                    Object groupNumber = map.get("groupNumber");
                    if (!(code instanceof String text) || !(expiresAt instanceof String expiry)) {
                        throw new IllegalStateException("QQBotAuth returned an incomplete QQ challenge");
                    }
                    return new QqBindingChallenge(
                        text, Instant.parse(expiry), groupNumber instanceof String group ? group : ""
                    );
                });
        }

        @Override
        public CompletionStage<Boolean> unbindQq(UUID playerUuid) {
            Plugin plugin = requirePlugin();
            return invoke(plugin, "unbindMinecraftQq", new Class<?>[]{UUID.class}, playerUuid)
                .thenApply(Boolean.TRUE::equals);
        }

        private static Plugin plugin() {
            Plugin plugin = Bukkit.getPluginManager().getPlugin("QQBotAuth");
            return plugin != null && plugin.isEnabled() ? plugin : null;
        }

        private static Plugin requirePlugin() {
            Plugin plugin = plugin();
            if (plugin == null) throw new IllegalStateException("QQBotAuth is unavailable");
            return plugin;
        }

        private static CompletionStage<?> invoke(
            Plugin plugin,
            String name,
            Class<?>[] parameterTypes,
            Object... arguments
        ) {
            try {
                Method method = plugin.getClass().getMethod(name, parameterTypes);
                Object result = method.invoke(plugin, arguments);
                if (result instanceof CompletionStage<?> stage) return stage;
                return CompletableFuture.failedFuture(new IllegalStateException(name + " returned an invalid result"));
            } catch (NoSuchMethodException | IllegalAccessException exception) {
                return CompletableFuture.failedFuture(exception);
            } catch (InvocationTargetException exception) {
                return CompletableFuture.failedFuture(exception.getCause());
            }
        }

        private record BindingFlags(boolean qq, boolean discord) {
        }
    }
}
