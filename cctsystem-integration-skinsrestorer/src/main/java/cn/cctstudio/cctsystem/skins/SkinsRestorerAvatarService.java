package cn.cctstudio.cctsystem.skins;

import cn.cctstudio.cctsystem.core.concurrent.CctExecutors;
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
    private static final int AVATAR_SIZE = 128;
    private static final int MAX_CACHE_ENTRIES = 1_024;

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
                Optional<SkinProperty> property = skinsRestorer.getPlayerStorage()
                    .getSkinForPlayer(playerUuid, playerName);
                if (property.isEmpty()) {
                    throw new SkinAvatarException("SKIN_NOT_FOUND", "Player has no active skin", false);
                }
                String hash = PropertyUtils.getSkinTextureHash(property.orElseThrow())
                    .toLowerCase(Locale.ROOT);
                if (!hash.matches("[0-9a-f]{32,128}")) {
                    throw new SkinAvatarException("SKIN_DATA_INVALID", "Skin texture hash is invalid", false);
                }
                byte[] cached = cache.get(hash);
                if (cached != null) {
                    return new SkinAvatar(cached, hash);
                }
                URI texture = safeTextureUri(PropertyUtils.getSkinTextureUrl(property.orElseThrow()));
                byte[] rendered = render(download(texture));
                if (cache.size() >= MAX_CACHE_ENTRIES) {
                    cache.clear();
                }
                cache.put(hash, rendered);
                return new SkinAvatar(rendered, hash);
            } catch (SkinAvatarException exception) {
                throw exception;
            } catch (DataRequestException exception) {
                throw new SkinAvatarException("SKIN_PROVIDER_FAILED", "Skin provider is unavailable", true);
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
}
