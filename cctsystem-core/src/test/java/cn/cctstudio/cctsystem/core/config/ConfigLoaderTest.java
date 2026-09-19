package cn.cctstudio.cctsystem.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ConfigLoaderTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void resolvesSecretsWithoutPersistingThemInTheConfigModelSource() throws IOException {
        Path config = temporaryDirectory.resolve("config.yml");
        Files.writeString(config, """
            network-id: cct-main
            server-id: login
            node-id: login-1
            roles: [auth-authority]
            database:
              enabled: false
            bridge:
              enabled: true
              url: wss://api.cctstudio.cn/internal/minecraft/connect
              secret: ${BRIDGE_SECRET}
            """);

        Map<String, String> environment = Map.of(
            "BRIDGE_SECRET",
            "01234567890123456789012345678901"
        );
        CctConfig loaded = new ConfigLoader(environment::get).load(config);

        assertEquals("login", loaded.serverId());
        assertEquals(environment.get("BRIDGE_SECRET"), loaded.bridge().secret());
    }

    @Test
    void rejectsUnconfiguredNodeIdentity() throws IOException {
        Path config = temporaryDirectory.resolve("config.yml");
        Files.writeString(config, """
            network-id: cct-main
            server-id: change-me
            node-id: change-me
            roles: [gameplay]
            """);

        assertThrows(ConfigException.class, () -> new ConfigLoader(name -> null).load(config));
    }

    @Test
    void acceptsVanishInteractionOptionsAndIgnoresFutureFields() throws IOException {
        Path config = temporaryDirectory.resolve("config.yml");
        Files.writeString(config, """
            network-id: cct-main
            server-id: lobby
            node-id: lobby-1
            roles: [gameplay]
            vanish:
              block-break: true
              block-place: true
              container-interaction: true
              ender-chest-interaction: true
              workstation-interaction: true
              entity-interaction: false
              physical-interaction: false
              future-option: true
            """);

        VanishConfig vanish = new ConfigLoader(name -> null).load(config).vanish();

        assertEquals(true, vanish.blockBreak());
        assertEquals(true, vanish.blockPlace());
        assertEquals(true, vanish.containerInteraction());
        assertEquals(true, vanish.enderChestInteraction());
        assertEquals(true, vanish.workstationInteraction());
        assertEquals(false, vanish.entityInteraction());
        assertEquals(false, vanish.physicalInteraction());
    }
}
