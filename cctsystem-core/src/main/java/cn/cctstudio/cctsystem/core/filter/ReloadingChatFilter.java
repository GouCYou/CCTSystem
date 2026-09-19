package cn.cctstudio.cctsystem.core.filter;

import cn.cctstudio.cctsystem.core.logging.CctLogger;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.text.Normalizer;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

public final class ReloadingChatFilter {
    private static final ObjectMapper JSON = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final Path file;
    private final CctLogger logger;
    private final AtomicLong nextCheckNanos = new AtomicLong();
    private volatile Snapshot snapshot = Snapshot.disabled();
    private volatile FileTime loadedModified;

    public ReloadingChatFilter(Path file, InputStream defaults, CctLogger logger) {
        this.file = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
        this.logger = Objects.requireNonNull(logger, "logger");
        prepare(defaults);
        reload(true);
    }

    public String filter(String input) {
        if (input == null || input.isEmpty()) return input;
        reloadIfDue();
        return snapshot.filter(input);
    }

    public String filterCommand(String commandLine) {
        if (commandLine == null || commandLine.isBlank() || commandLine.charAt(0) != '/') {
            return commandLine;
        }
        reloadIfDue();
        Snapshot current = snapshot;
        if (!current.settings.enabled()) return commandLine;
        List<Token> tokens = commandTokens(commandLine);
        if (tokens.isEmpty()) return commandLine;
        CommandMatch match = current.settings.commands().entrySet().stream()
            .map(entry -> match(tokens, entry.getKey(), entry.getValue()))
            .filter(Objects::nonNull)
            .max(java.util.Comparator.comparingInt(CommandMatch::commandWords))
            .orElse(null);
        if (match == null) return commandLine;
        int contentToken = match.commandWords() + match.skippedArguments();
        if (contentToken >= tokens.size()) return commandLine;
        int contentStart = tokens.get(contentToken).start();
        String filtered = current.filter(commandLine.substring(contentStart));
        if (filtered.equals(commandLine.substring(contentStart))) return commandLine;
        return commandLine.substring(0, contentStart) + filtered;
    }

    private static List<Token> commandTokens(String commandLine) {
        List<Token> tokens = new ArrayList<>();
        for (int offset = 1; offset < commandLine.length();) {
            while (offset < commandLine.length() && Character.isWhitespace(commandLine.charAt(offset))) {
                offset++;
            }
            if (offset >= commandLine.length()) break;
            int start = offset;
            while (offset < commandLine.length() && !Character.isWhitespace(commandLine.charAt(offset))) {
                offset++;
            }
            String value = commandLine.substring(start, offset).toLowerCase(Locale.ROOT);
            if (tokens.isEmpty()) {
                int namespace = value.indexOf(':');
                if (namespace >= 0) value = value.substring(namespace + 1);
            }
            tokens.add(new Token(value, start));
        }
        return tokens;
    }

    private static CommandMatch match(List<Token> tokens, String configured, int skippedArguments) {
        String[] words = configured.split("\\s+");
        if (words.length > tokens.size()) return null;
        for (int index = 0; index < words.length; index++) {
            if (!words[index].equals(tokens.get(index).value())) return null;
        }
        return new CommandMatch(words.length, skippedArguments);
    }

    public Path file() {
        return file;
    }

    private void reloadIfDue() {
        long now = System.nanoTime();
        long next = nextCheckNanos.get();
        if (now < next || !nextCheckNanos.compareAndSet(next, now + checkDelayNanos())) return;
        reload(false);
    }

    private long checkDelayNanos() {
        return Duration.ofSeconds(Math.max(1, snapshot.settings.reloadSeconds())).toNanos();
    }

    private synchronized void reload(boolean initial) {
        try {
            FileTime modified = Files.getLastModifiedTime(file);
            if (!initial && modified.equals(loadedModified)) return;
            ChatFilterSettings settings = JSON.readValue(file.toFile(), ChatFilterSettings.class);
            Snapshot loaded = Snapshot.compile(settings);
            snapshot = loaded;
            loadedModified = modified;
            nextCheckNanos.set(System.nanoTime() + checkDelayNanos());
            logger.info("Loaded chat filter from " + file + " with " + loaded.termCount + " blocked terms");
        } catch (IOException | RuntimeException exception) {
            logger.warn("Unable to reload chat filter; keeping the last valid rules: " + file, exception);
        }
    }

    private void prepare(InputStream defaults) {
        try {
            Path parent = file.getParent();
            if (parent != null) Files.createDirectories(parent);
            if (Files.notExists(file)) {
                if (defaults == null) throw new IOException("Default chat-filter.json is missing");
                Files.copy(defaults, file, StandardCopyOption.COPY_ATTRIBUTES);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to prepare chat filter file: " + file, exception);
        } finally {
            if (defaults != null) {
                try {
                    defaults.close();
                } catch (IOException ignored) {
                    // Nothing useful can be done while closing a read-only bundled resource.
                }
            }
        }
    }

    private record Span(int start, int end) {
        boolean contains(int otherStart, int otherEnd) {
            return start <= otherStart && end >= otherEnd;
        }
    }

    private record Token(String value, int start) {
    }

    private record CommandMatch(int commandWords, int skippedArguments) {
    }

    private record Normalized(String value, int[] originalStarts, int[] originalEnds) {
    }

    private record Output(int length, boolean wholeAsciiWord) {
    }

    private static final class Snapshot {
        private final ChatFilterSettings settings;
        private final Matcher blocked;
        private final Matcher allowed;
        private final int termCount;

        private Snapshot(ChatFilterSettings settings, Matcher blocked, Matcher allowed, int termCount) {
            this.settings = settings;
            this.blocked = blocked;
            this.allowed = allowed;
            this.termCount = termCount;
        }

        static Snapshot disabled() {
            ChatFilterSettings settings = new ChatFilterSettings(
                false, "*", 5, true, true, List.of(), List.of(), Map.of()
            );
            return new Snapshot(settings, Matcher.empty(), Matcher.empty(), 0);
        }

        static Snapshot compile(ChatFilterSettings settings) {
            List<String> terms = settings.words().stream()
                .filter(Objects::nonNull)
                .map(String::strip)
                .filter(term -> !term.isEmpty())
                .distinct()
                .toList();
            return new Snapshot(
                settings,
                Matcher.compile(terms, settings.ignoreSeparators(), settings.normalizeLeetspeak()),
                Matcher.compile(settings.allowlist(), settings.ignoreSeparators(), settings.normalizeLeetspeak()),
                terms.size()
            );
        }

        String filter(String input) {
            if (!settings.enabled() || blocked.empty) return input;
            Normalized normalized = normalize(
                input, settings.ignoreSeparators(), settings.normalizeLeetspeak(), true
            );
            if (normalized.value().isEmpty()) return input;
            List<Span> allowedSpans = allowed.find(normalized.value());
            List<Span> blockedSpans = blocked.find(normalized.value()).stream()
                .filter(span -> allowedSpans.stream().noneMatch(allowed ->
                    allowed.contains(span.start(), span.end())
                ))
                .toList();
            if (blockedSpans.isEmpty()) return input;
            boolean[] masked = new boolean[input.length()];
            for (Span span : blockedSpans) {
                int start = normalized.originalStarts()[span.start()];
                int end = normalized.originalEnds()[span.end() - 1];
                for (int index = start; index < end; index++) masked[index] = true;
            }
            StringBuilder output = new StringBuilder(input.length());
            for (int offset = 0; offset < input.length();) {
                int codePoint = input.codePointAt(offset);
                int width = Character.charCount(codePoint);
                if (masked[offset]) output.append(settings.replacement());
                else output.appendCodePoint(codePoint);
                offset += width;
            }
            return output.toString();
        }
    }

    private static Normalized normalize(
        String input,
        boolean ignoreSeparators,
        boolean normalizeLeetspeak,
        boolean trackOriginal
    ) {
        StringBuilder normalized = new StringBuilder(input.length());
        List<Integer> starts = new ArrayList<>();
        List<Integer> ends = new ArrayList<>();
        for (int offset = 0; offset < input.length();) {
            int codePoint = input.codePointAt(offset);
            int width = Character.charCount(codePoint);
            String token = Normalizer.normalize(
                new String(Character.toChars(codePoint)), Normalizer.Form.NFKC
            ).toLowerCase(Locale.ROOT);
            for (int tokenOffset = 0; tokenOffset < token.length();) {
                int normalizedPoint = token.codePointAt(tokenOffset);
                tokenOffset += Character.charCount(normalizedPoint);
                if (normalizeLeetspeak) normalizedPoint = normalizeLeetspeak(normalizedPoint);
                if (ignoreSeparators && separator(normalizedPoint)) continue;
                normalized.appendCodePoint(normalizedPoint);
                if (trackOriginal) {
                    starts.add(offset);
                    ends.add(offset + width);
                }
            }
            offset += width;
        }
        return new Normalized(
            normalized.toString(),
            starts.stream().mapToInt(Integer::intValue).toArray(),
            ends.stream().mapToInt(Integer::intValue).toArray()
        );
    }

    private static int normalizeLeetspeak(int codePoint) {
        return switch (codePoint) {
            case '0' -> 'o';
            case '1', '!', '|' -> 'i';
            case '3' -> 'e';
            case '4', '@' -> 'a';
            case '5', '$' -> 's';
            case '7' -> 't';
            default -> codePoint;
        };
    }

    private static boolean asciiWord(int codePoint) {
        return codePoint >= 'a' && codePoint <= 'z'
            || codePoint >= '0' && codePoint <= '9'
            || codePoint == '_';
    }

    private static boolean separator(int codePoint) {
        if (Character.isWhitespace(codePoint)) return true;
        return switch (Character.getType(codePoint)) {
            case Character.CONNECTOR_PUNCTUATION,
                 Character.DASH_PUNCTUATION,
                 Character.START_PUNCTUATION,
                 Character.END_PUNCTUATION,
                 Character.INITIAL_QUOTE_PUNCTUATION,
                 Character.FINAL_QUOTE_PUNCTUATION,
                 Character.OTHER_PUNCTUATION,
                 Character.MATH_SYMBOL,
                 Character.CURRENCY_SYMBOL,
                 Character.MODIFIER_SYMBOL,
                 Character.OTHER_SYMBOL -> true;
            default -> false;
        };
    }

    private static final class Matcher {
        private final Node root;
        private final boolean empty;

        private Matcher(Node root, boolean empty) {
            this.root = root;
            this.empty = empty;
        }

        static Matcher empty() {
            return new Matcher(new Node(), true);
        }

        static Matcher compile(
            List<String> source,
            boolean ignoreSeparators,
            boolean normalizeLeetspeak
        ) {
            Node root = new Node();
            int count = 0;
            for (String raw : source == null ? List.<String>of() : source) {
                if (raw == null || raw.isBlank()) continue;
                String term = normalize(raw, ignoreSeparators, normalizeLeetspeak, false).value();
                int[] points = term.codePoints().toArray();
                if (points.length == 0) continue;
                Node node = root;
                for (int point : points) node = node.next.computeIfAbsent(point, ignored -> new Node());
                boolean wholeAsciiWord = points.length <= 2
                    && java.util.Arrays.stream(points).allMatch(ReloadingChatFilter::asciiWord);
                node.outputs.add(new Output(points.length, wholeAsciiWord));
                count++;
            }
            if (count == 0) return empty();
            ArrayDeque<Node> queue = new ArrayDeque<>();
            root.fail = root;
            root.next.values().forEach(child -> {
                child.fail = root;
                queue.add(child);
            });
            while (!queue.isEmpty()) {
                Node parent = queue.remove();
                parent.next.forEach((point, child) -> {
                    Node failure = parent.fail;
                    while (failure != root && !failure.next.containsKey(point)) failure = failure.fail;
                    if (failure.next.containsKey(point) && failure.next.get(point) != child) {
                        failure = failure.next.get(point);
                    }
                    child.fail = failure;
                    child.outputs.addAll(failure.outputs);
                    queue.add(child);
                });
            }
            return new Matcher(root, false);
        }

        List<Span> find(String text) {
            if (empty) return List.of();
            List<Span> result = new ArrayList<>();
            Node node = root;
            int[] points = text.codePoints().toArray();
            for (int position = 0; position < points.length; position++) {
                int point = points[position];
                while (node != root && !node.next.containsKey(point)) node = node.fail;
                node = node.next.getOrDefault(point, root);
                for (Output output : node.outputs) {
                    int start = position - output.length() + 1;
                    int end = position + 1;
                    if (output.wholeAsciiWord()
                        && (start > 0 && asciiWord(points[start - 1])
                            || end < points.length && asciiWord(points[end]))) {
                        continue;
                    }
                    result.add(new Span(start, end));
                }
            }
            return result;
        }
    }

    private static final class Node {
        private final Map<Integer, Node> next = new HashMap<>();
        private final List<Output> outputs = new ArrayList<>();
        private Node fail;
    }
}
