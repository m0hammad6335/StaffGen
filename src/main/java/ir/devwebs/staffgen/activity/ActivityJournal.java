package ir.devwebs.staffgen.activity;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Predicate;

/** Access is serialized by ActivityMonitor's disk worker. Files are grouped by UTC day. */
final class ActivityJournal {
    private final Path directory;
    private final Clock clock;

    ActivityJournal(Path directory, Clock clock) {
        this.directory = directory;
        this.clock = clock;
    }

    void append(String actor, String category, String details) throws IOException {
        Files.createDirectories(directory);
        String line =
                String.join(
                        "\t", clock.instant().toString(), clean(actor), category, clean(details));
        Files.writeString(
                file(LocalDate.now(clock)),
                line + "\n",
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }

    List<String> tail(LocalDate day, Predicate<String> visibleCategory, int limit)
            throws IOException {
        Deque<String> result = new ArrayDeque<>();
        Path path = file(day);
        if (Files.exists(path)) {
            try (BufferedReader reader = Files.newBufferedReader(path)) {
                for (String line; (line = reader.readLine()) != null; ) {
                    String[] fields = line.split("\t", 4);
                    if (fields.length == 4 && visibleCategory.test(fields[2])) {
                        if (result.size() == limit) result.removeFirst();
                        result.addLast(String.join("  ", fields));
                    }
                }
            }
        }
        return new ArrayList<>(result);
    }

    int prune(int retentionDays) throws IOException {
        int removed = 0;
        if (!Files.isDirectory(directory)) return removed;
        LocalDate oldest = LocalDate.now(clock).minusDays(retentionDays - 1L);
        try (DirectoryStream<Path> paths = Files.newDirectoryStream(directory, "????-??-??.log")) {
            for (Path path : paths) {
                String name = path.getFileName().toString();
                try {
                    if (LocalDate.parse(name.substring(0, 10)).isBefore(oldest)
                            && Files.deleteIfExists(path)) removed++;
                } catch (DateTimeParseException ignored) {
                    // A matching filename alone is insufficient grounds to remove administrator
                    // files.
                }
            }
        }
        return removed;
    }

    private Path file(LocalDate day) {
        return directory.resolve(day + ".log");
    }

    private static String clean(String value) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < value.length() && text.length() < 512; i++) {
            char ch = value.charAt(i);
            if (ch == '\u00a7' && i + 1 < value.length()) {
                i++;
                continue;
            }
            if (!Character.isISOControl(ch) && Character.getType(ch) != Character.FORMAT)
                text.append(ch);
        }
        return text.toString();
    }
}
