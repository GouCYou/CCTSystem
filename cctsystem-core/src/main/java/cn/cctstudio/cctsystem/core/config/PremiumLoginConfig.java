package cn.cctstudio.cctsystem.core.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

public record PremiumLoginConfig(
    boolean enabled, String signingKey, String loginServer, String rewardServer, String titleServer,
    Titles titles, Rewards rewards, boolean requireSocialBinding
) {
    public static final String FILE = "premium-login.yml";
    private static final Pattern SERVER = Pattern.compile("[a-z0-9_-]{1,48}");

    public static PremiumLoginConfig load(Path directory) throws IOException {
        Files.createDirectories(directory);
        Path path = directory.resolve(FILE);
        if (Files.notExists(path)) {
            try (InputStream defaults = PremiumLoginConfig.class.getResourceAsStream("/" + FILE)) {
                Files.copy(Objects.requireNonNull(defaults), path);
            }
        }
        return new ObjectMapper(new YAMLFactory())
            .setPropertyNamingStrategy(PropertyNamingStrategies.KEBAB_CASE)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .readValue(path.toFile(), PremiumLoginConfig.class);
    }

    public PremiumLoginConfig {
        if (enabled) {
            if (signingKey == null || !signingKey.matches("[0-9a-fA-F]{64}")) {
                throw new IllegalArgumentException("Java login signing-key must be 32 bytes in hex");
            }
            requireServer(loginServer);
            requireServer(rewardServer);
            requireServer(titleServer);
            Objects.requireNonNull(titles, "Java login titles");
            Objects.requireNonNull(rewards, "Java login rewards");
        }
    }

    public byte[] keyBytes() {
        return HexFormat.of().parseHex(signingKey);
    }

    private static void requireServer(String server) {
        if (server == null || !SERVER.matcher(server).matches()) {
            throw new IllegalArgumentException("Invalid Java login server ID");
        }
    }

    public record Titles(
        boolean enabled, boolean showReturning, String title, String firstSubtitle, String returningSubtitle,
        String pendingSubtitle, int fadeInTicks, int stayTicks, int fadeOutTicks
    ) {
        public Titles {
            Objects.requireNonNull(title);
            Objects.requireNonNull(firstSubtitle);
            Objects.requireNonNull(returningSubtitle);
            Objects.requireNonNull(pendingSubtitle);
            validateTicks(fadeInTicks);
            validateTicks(stayTicks);
            validateTicks(fadeOutTicks);
        }

        private static void validateTicks(int ticks) {
            if (ticks < 0 || ticks > 12_000) {
                throw new IllegalArgumentException("Java login title times must be between 0 and 12000 ticks");
            }
        }
    }

    public record Rewards(String membershipTier, int membershipDays, int points, List<String> commands) {
        public Rewards {
            commands = commands == null ? List.of() : List.copyOf(commands);
            if (membershipDays < 0 || membershipDays > 3650 || points < 0
                || (membershipDays > 0 && (membershipTier == null || !SERVER.matcher(membershipTier).matches()))
                || commands.size() > 20 || commands.stream().anyMatch(command ->
                    command.isBlank() || command.length() > 256 || command.startsWith("/")
                        || command.chars().anyMatch(Character::isISOControl))) {
                throw new IllegalArgumentException("Invalid Java login reward configuration");
            }
        }
    }
}
