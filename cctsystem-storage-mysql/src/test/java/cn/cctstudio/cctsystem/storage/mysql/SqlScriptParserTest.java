package cn.cctstudio.cctsystem.storage.mysql;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

final class SqlScriptParserTest {
    @Test
    void keepsSemicolonsInsideQuotedValues() {
        List<String> statements = SqlScriptParser.parse("""
            -- comment
            INSERT INTO example(value) VALUES ('a;b');
            CREATE TABLE another (id INT);
            """);

        assertEquals(2, statements.size());
        assertEquals("INSERT INTO example(value) VALUES ('a;b')", statements.getFirst());
    }
}
