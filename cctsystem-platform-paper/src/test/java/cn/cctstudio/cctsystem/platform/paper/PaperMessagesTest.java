package cn.cctstudio.cctsystem.platform.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PaperMessagesTest {
    @TempDir Path directory;

    @Test
    void oldLanguageFileUsesBundledMessagesForNewKeys() throws Exception {
        Path file=directory.resolve("messages.yml");
        Files.writeString(file,"commands:\n  reloaded: '&a原有重载提示'\n");
        PaperMessages messages=new PaperMessages(file.toFile());
        assertEquals("&6折扣代码",messages.raw("discount-code.title"));
        assertEquals("&6折扣代码",messages.raw("discount-code.title","wrong fallback"));
        assertEquals("&a原有重载提示",messages.raw("commands.reloaded"));
        assertEquals("&cMissing message: absent.key",messages.raw("absent.key"));
        assertEquals("custom fallback",messages.raw("absent.key","custom fallback"));
    }

    @Test
    void installedCustomizationStillOverridesBundledDefaultAfterReload() throws Exception {
        Path file=directory.resolve("messages.yml");
        Files.writeString(file,"discount-code:\n  title: '&e我的折扣入口'\n");
        PaperMessages messages=new PaperMessages(file.toFile());
        assertEquals("&e我的折扣入口",messages.raw("discount-code.title"));
        assertEquals("&a应用",messages.raw("discount-code.apply"));
        Files.writeString(file,"discount-code:\n  title: '&6更新后的入口'\n");
        messages.reload();
        assertEquals("&6更新后的入口",messages.raw("discount-code.title"));
        assertEquals("&a应用",messages.raw("discount-code.apply"));
    }
}
