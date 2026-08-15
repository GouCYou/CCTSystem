package cn.cctstudio.cctsystem.skins;

import cn.cctstudio.cctsystem.core.concurrent.CctExecutors;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import javax.imageio.ImageIO;
import net.skinsrestorer.api.PropertyUtils;
import net.skinsrestorer.api.SkinsRestorer;
import net.skinsrestorer.api.exception.DataRequestException;
import net.skinsrestorer.api.property.SkinProperty;

final class SkinsRestorerAvatarService implements SkinAvatarService {
    private static final int MAX_TEXTURE_BYTES = 128 * 1024;
    private static final int MAX_PROFILE_BYTES = 32 * 1024;
    private static final int AVATAR_SIZE = 128;
    private static final int MAX_CACHE_ENTRIES = 1_024;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final SkinsRestorer skinsRestorer;
    private final CctExecutors executors;
    private final HttpClient http;
    private final ConcurrentHashMap<String, byte[]> cache = new ConcurrentHashMap<>();

    SkinsRestorerAvatarService(SkinsRestorer skinsRestorer, CctExecutors executors) {
        this.skinsRestorer = skinsRestorer;
        this.executors = executors;
        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    }

    @Override
    public CompletionStage<SkinAvatar> avatar(UUID playerUuid, String playerName) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                TextureReference texture = resolveTexture(playerUuid, playerName);
                String hash = texture.hash();
                byte[] cached = cache.get(hash);
                if (cached != null) {
                    return new SkinAvatar(cached, hash);
                }
                byte[] rendered = render(download(texture.uri()));
                if (cache.size() >= MAX_CACHE_ENTRIES) {
                    cache.clear();
                }
                cache.put(hash, rendered);
                return new SkinAvatar(rendered, hash);
            } catch (SkinAvatarException exception) {
                throw exception;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new SkinAvatarException("SKIN_FETCH_INTERRUPTED", "Skin request was interrupted", true);
            } catch (IOException exception) {
                throw new SkinAvatarException("SKIN_FETCH_FAILED", "Skin texture is unavailable", true);
            } catch (RuntimeException exception) {
                throw new CompletionException(new SkinAvatarException(
                    "SKIN_PROVIDER_FAILED", "Skin provider is unavailable", true
                ));
            }
        }, executors.blocking());
    }

    private TextureReference resolveTexture(UUID playerUuid, String playerName)
        throws IOException, InterruptedException {
        try {
            Optional<SkinProperty> property = skinsRestorer.getPlayerStorage()
                .getSkinForPlayer(playerUuid, playerName);
            if (property.isPresent()) {
                SkinProperty skin = property.orElseThrow();
                String hash = PropertyUtils.getSkinTextureHash(skin).toLowerCase(Locale.ROOT);
                if (hash.matches("[0-9a-f]{32,128}")) {
                    return new TextureReference(hash, safeTextureUri(PropertyUtils.getSkinTextureUrl(skin)));
                }
            }
        } catch (DataRequestException | SkinAvatarException ignored) {
            // A missing or temporarily unavailable server skin falls through to the official profile.
        }
        return officialTexture(playerName).orElseThrow(() -> new SkinAvatarException(
            "SKIN_NOT_FOUND", "Player has no active or official skin", false
        ));
    }

    private Optional<TextureReference> officialTexture(String playerName)
        throws IOException, InterruptedException {
        if (playerName == null || !playerName.matches("[A-Za-z0-9_]{1,16}")) {
            return Optional.empty();
        }
        JsonNode profile = readJson(URI.create(
            "https://api.mojang.com/users/profiles/minecraft/" + playerName
        ), "api.mojang.com");
        if (profile == null || !profile.path("id").isTextual()) {
            return Optional.empty();
        }
        String profileId = profile.path("id").textValue();
        if (!profileId.matches("[0-9a-fA-F]{32}")) {
            return Optional.empty();
        }
        JsonNode session = readJson(URI.create(
            "https://sessionserver.mojang.com/session/minecraft/profile/" + profileId
        ), "sessionserver.mojang.com");
        if (session == null || !session.path("properties").isArray()) {
            return Optional.empty();
        }
        for (JsonNode property : session.path("properties")) {
            if (!"textures".equals(property.path("name").asText()) || !property.path("value").isTextual()) {
                continue;
            }
            byte[] decoded;
            try {
                decoded = Base64.getDecoder().decode(property.path("value").textValue());
            } catch (IllegalArgumentException exception) {
                return Optional.empty();
            }
            if (decoded.length == 0 || decoded.length > MAX_PROFILE_BYTES) {
                return Optional.empty();
            }
            JsonNode textures = JSON.readTree(decoded);
            if (!textures.path("textures").path("SKIN").path("url").isTextual()) {
                return Optional.empty();
            }
            URI texture = safeTextureUri(textures.path("textures").path("SKIN").path("url").textValue());
            String path = texture.getPath();
            String hash = path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
            return hash.matches("[0-9a-f]{32,128}")
                ? Optional.of(new TextureReference(hash, texture))
                : Optional.empty();
        }
        return Optional.empty();
    }

    private JsonNode readJson(URI uri, String expectedHost) throws IOException, InterruptedException {
        if (!"https".equalsIgnoreCase(uri.getScheme()) || !expectedHost.equalsIgnoreCase(uri.getHost())) {
            throw new IOException("Profile endpoint is not trusted");
        }
        HttpRequest request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(8))
            .header("accept", "application/json")
            .GET()
            .build();
        HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() == 204 || response.statusCode() == 404) {
            response.body().close();
            return null;
        }
        if (response.statusCode() != 200) {
            response.body().close();
            throw new IOException("Profile endpoint returned " + response.statusCode());
        }
        try (InputStream body = response.body()) {
            byte[] bytes = body.readNBytes(MAX_PROFILE_BYTES + 1);
            if (bytes.length == 0 || bytes.length > MAX_PROFILE_BYTES) {
                throw new IOException("Profile response size is invalid");
            }
            return JSON.readTree(bytes);
        }
    }

    private byte[] download(URI texture) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(texture)
            .timeout(Duration.ofSeconds(8))
            .header("accept", "image/png")
            .GET()
            .build();
        HttpResponse<InputStream> response = http.send(
            request, HttpResponse.BodyHandlers.ofInputStream()
        );
        if (response.statusCode() != 200) {
            throw new IOException("Texture endpoint returned " + response.statusCode());
        }
        try (InputStream body = response.body()) {
            byte[] bytes = body.readNBytes(MAX_TEXTURE_BYTES + 1);
            if (bytes.length == 0 || bytes.length > MAX_TEXTURE_BYTES) {
                throw new IOException("Texture response size is invalid");
            }
            return bytes;
        }
    }

    static URI safeTextureUri(String value) {
        URI source;
        try {
            source = URI.create(value);
        } catch (IllegalArgumentException exception) {
            throw new SkinAvatarException("SKIN_DATA_INVALID", "Skin texture URL is invalid", false);
        }
        if (!"textures.minecraft.net".equalsIgnoreCase(source.getHost())
            || !("https".equalsIgnoreCase(source.getScheme()) || "http".equalsIgnoreCase(source.getScheme()))
            || source.getUserInfo() != null || source.getPort() != -1) {
            throw new SkinAvatarException("SKIN_DATA_INVALID", "Skin texture URL is not trusted", false);
        }
        return URI.create("https://textures.minecraft.net" + source.getRawPath());
    }

    static byte[] render(byte[] texture) throws IOException {
        BufferedImage skin = ImageIO.read(new ByteArrayInputStream(texture));
        if (skin == null || skin.getWidth() < 48 || skin.getHeight() < 16
            || skin.getWidth() > 1_024 || skin.getHeight() > 1_024) {
            throw new IOException("Skin texture dimensions are invalid");
        }
        int scale = skin.getWidth() / 64;
        if (scale < 1 || skin.getWidth() % 64 != 0 || skin.getHeight() < 16 * scale) {
            throw new IOException("Skin texture layout is invalid");
        }
        BufferedImage avatar = new BufferedImage(AVATAR_SIZE, AVATAR_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = avatar.createGraphics();
        try {
            graphics.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR
            );
            drawLayer(graphics, skin, 8 * scale, 8 * scale, 8 * scale);
            drawLayer(graphics, skin, 40 * scale, 8 * scale, 8 * scale);
        } finally {
            graphics.dispose();
        }
        try (ByteArrayOutputStream output = new ByteArrayOutputStream(16 * 1024)) {
            if (!ImageIO.write(avatar, "png", output)) {
                throw new IOException("PNG writer is unavailable");
            }
            return output.toByteArray();
        }
    }

    private static void drawLayer(
        Graphics2D graphics,
        BufferedImage skin,
        int sourceX,
        int sourceY,
        int sourceSize
    ) {
        graphics.drawImage(
            skin,
            0,
            0,
            AVATAR_SIZE,
            AVATAR_SIZE,
            sourceX,
            sourceY,
            sourceX + sourceSize,
            sourceY + sourceSize,
            null
        );
    }

    private record TextureReference(String hash, URI uri) {
    }
}
