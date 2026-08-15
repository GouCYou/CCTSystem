package cn.cctstudio.cctsystem.skins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

final class SkinsRestorerAvatarServiceTest {
    @Test
    void rendersFaceAndOuterHatLayerWithNearestNeighbourScaling() throws Exception {
        BufferedImage skin = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        fill(skin, 8, 8, 8, Color.BLUE.getRGB());
        fill(skin, 40, 8, 8, Color.GREEN.getRGB());
        ByteArrayOutputStream texture = new ByteArrayOutputStream();
        ImageIO.write(skin, "png", texture);

        byte[] rendered = SkinsRestorerAvatarService.render(texture.toByteArray());
        BufferedImage avatar = ImageIO.read(new ByteArrayInputStream(rendered));

        assertEquals(128, avatar.getWidth());
        assertEquals(128, avatar.getHeight());
        assertEquals(Color.GREEN.getRGB(), avatar.getRGB(64, 64));
    }

    @Test
    void onlyAllowsMinecraftTextureHostAndUpgradesHttp() {
        URI safe = SkinsRestorerAvatarService.safeTextureUri(
            "http://textures.minecraft.net/texture/abcdef"
        );
        assertEquals("https", safe.getScheme());
        assertEquals("textures.minecraft.net", safe.getHost());
        assertThrows(
            SkinAvatarException.class,
            () -> SkinsRestorerAvatarService.safeTextureUri(
                "https://textures.minecraft.net.evil.example/texture/abcdef"
            )
        );
    }

    private static void fill(BufferedImage image, int x, int y, int size, int color) {
        for (int dx = 0; dx < size; dx++) {
            for (int dy = 0; dy < size; dy++) {
                image.setRGB(x + dx, y + dy, color);
            }
        }
    }
}
