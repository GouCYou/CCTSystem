package cn.cctstudio.cctsystem.distribution;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.cctstudio.cctsystem.core.config.CctConfig;
import cn.cctstudio.cctsystem.core.config.ConfigLoader;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DefaultConfigTest {
    @Test
    void bundledConfigurationMatchesTheTypedModel(@TempDir Path temporaryDirectory) throws Exception {
        String source;
        try (InputStream input = getClass().getResourceAsStream("/config.yml")) {
            if (input == null) {
                throw new IllegalStateException("Bundled config.yml is missing");
            }
            source = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        Path configPath = temporaryDirectory.resolve("config.yml");
        Files.writeString(configPath, source.replace("change-me", "config-test"));

        CctConfig config = new ConfigLoader().load(configPath);

        assertEquals(4, config.membership().tiers().size());
        assertEquals("vipp", config.membership().tiers().get(1).luckPermsGroup());
        assertEquals(365, config.membership().maxPurchaseDays());
        assertEquals(12, config.redeemCode().length());
        assertEquals("CCTStudio 1.21 - 26.2", config.serverList().versionName());
    }
}
