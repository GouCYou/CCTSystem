package cn.cctstudio.cctsystem.storage.mysql;

import java.util.ArrayList;
import java.util.List;

final class SqlScriptParser {
    private SqlScriptParser() {
    }

    static List<String> parse(String script) {
        String withoutComments = script.lines()
            .filter(line -> !line.stripLeading().startsWith("--"))
            .reduce(new StringBuilder(), (builder, line) -> builder.append(line).append('\n'), StringBuilder::append)
            .toString();

        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean singleQuoted = false;
        boolean doubleQuoted = false;
        boolean backtickQuoted = false;

        for (int index = 0; index < withoutComments.length(); index++) {
            char character = withoutComments.charAt(index);
            char previous = index == 0 ? '\0' : withoutComments.charAt(index - 1);
            if (character == '\'' && previous != '\\' && !doubleQuoted && !backtickQuoted) {
                singleQuoted = !singleQuoted;
            } else if (character == '"' && previous != '\\' && !singleQuoted && !backtickQuoted) {
                doubleQuoted = !doubleQuoted;
            } else if (character == '`' && !singleQuoted && !doubleQuoted) {
                backtickQuoted = !backtickQuoted;
            }

            if (character == ';' && !singleQuoted && !doubleQuoted && !backtickQuoted) {
                addStatement(statements, current);
            } else {
                current.append(character);
            }
        }
        addStatement(statements, current);
        return List.copyOf(statements);
    }

    private static void addStatement(List<String> statements, StringBuilder current) {
        String value = current.toString().trim();
        if (!value.isEmpty()) {
            statements.add(value);
        }
        current.setLength(0);
    }
}
