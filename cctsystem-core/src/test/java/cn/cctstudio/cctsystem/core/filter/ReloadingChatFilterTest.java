package cn.cctstudio.cctsystem.core.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.cctstudio.cctsystem.core.logging.CctLogger;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ReloadingChatFilterTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void preservesVisibleLengthAndMatchesSeparatedText() throws Exception {
        ReloadingChatFilter filter = filter("""
            {
              "enabled": true,
              "replacement": "*",
              "reloadSeconds": 5,
              "ignoreSeparators": true,
              "normalizeLeetspeak": true,
              "words": ["坏话", "ＡＢＣ", "8964", "fuck", "penis", "dick", "pussy", "ez"],
              "allowlist": [],
              "commands": {"msg": 1, "cct shout": 0}
            }
            """);

        assertEquals("这句***需要处理", filter.filter("这句坏-话需要处理"));
        assertEquals("***", filter.filter("abc"));
        assertEquals("*******", filter.filter("8.9.6.4"));
        assertEquals("*******", filter.filter("f.u.c.k"));
        assertEquals("*****", filter.filter("penis"));
        assertEquals("****", filter.filter("d1ck"));
        assertEquals("*****", filter.filter("pu$$y"));
        assertEquals("太**了", filter.filter("太ez了"));
        assertEquals("freeze", filter.filter("freeze"));
        assertEquals("/msg Player 你说**", filter.filterCommand("/msg Player 你说坏话"));
        assertEquals("/cct shout 你说**", filter.filterCommand("/cct shout 你说坏话"));
    }

    @Test
    void allowlistProtectsContainedTerm() throws Exception {
        ReloadingChatFilter filter = filter("""
            {
              "enabled": true,
              "replacement": "*",
              "reloadSeconds": 5,
              "ignoreSeparators": true,
              "normalizeLeetspeak": true,
              "words": ["坏话"],
              "allowlist": ["不是坏话"],
              "commands": {}
            }
            """);

        assertEquals("这不是坏话", filter.filter("这不是坏话"));
        assertEquals("这是**", filter.filter("这是坏话"));
    }

    private ReloadingChatFilter filter(String json) throws Exception {
        Path file = temporaryDirectory.resolve("chat-filter.json");
        Files.writeString(file, json);
        return new ReloadingChatFilter(file, null, new SilentLogger());
    }

    private static final class SilentLogger implements CctLogger {
        @Override public void info(String message) { }
        @Override public void warn(String message) { }
        @Override public void warn(String message, Throwable throwable) { }
        @Override public void error(String message, Throwable throwable) { }
    }
}
