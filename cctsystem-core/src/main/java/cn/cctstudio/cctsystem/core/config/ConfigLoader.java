package cn.cctstudio.cctsystem.core.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ConfigLoader {
    private static final Pattern ENVIRONMENT_VARIABLE = Pattern.compile(
        "\\$\\{([A-Za-z_][A-Za-z0-9_]*)(?::-([^}]*))?}"
    );

    private final ObjectMapper mapper;
    private final Function<String, String> environment;

    public ConfigLoader() {
        this(System::getenv);
    }

    ConfigLoader(Function<String, String> environment) {
        this.environment = Objects.requireNonNull(environment, "environment");
        this.mapper = new ObjectMapper(new YAMLFactory())
            .setPropertyNamingStrategy(PropertyNamingStrategies.KEBAB_CASE);
    }

    public CctConfig load(Path path) {
        try {
            String source = Files.readString(path);
            CctConfig config = mapper.readValue(resolveEnvironment(source), CctConfig.class);
            ConfigValidator.validate(config);
            return config;
        } catch (IOException exception) {
            throw new ConfigException("Unable to read CCTSystem config: " + path, exception);
        }
    }

    String resolveEnvironment(String source) {
        Matcher matcher = ENVIRONMENT_VARIABLE.matcher(source);
        StringBuilder resolved = new StringBuilder(source.length());
        while (matcher.find()) {
            String value = environment.apply(matcher.group(1));
            if (value == null) {
                value = matcher.group(2);
            }
            if (value == null) {
                throw new ConfigException("Missing required environment variable: " + matcher.group(1));
            }
            matcher.appendReplacement(resolved, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(resolved);
        return resolved.toString();
    }
}
