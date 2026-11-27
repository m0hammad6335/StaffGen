package ir.devwebs.staffgen.activity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Set;

/** Standalone regression check using only synthetic records and a temporary directory. */
public final class JournalCheck {
    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("staffgen-journal-check");
        LocalDate today = LocalDate.of(2026, 1, 2);
        Clock clock = Clock.fixed(Instant.parse("2026-01-02T12:00:00Z"), ZoneOffset.UTC);
        ActivityJournal journal = new ActivityJournal(directory, clock);
        try {
            journal.append("player", "command", "help");
            journal.append("player", "audit", "note\nsecond-line");
            journal.append("player", "audit", "finish");
            var lines = journal.tail(today, "audit"::equals, 2);
            check(
                    lines.size() == 2 && lines.get(0).endsWith("notesecond-line"),
                    "journal injection");
            check(journal.tail(today, category -> true, 1).get(0).endsWith("finish"), "tail order");
            Files.createFile(directory.resolve("2025-12-31.log"));
            Files.createFile(directory.resolve("2026-01-01.log"));
            check(journal.prune(2) == 1, "inclusive retention window");
            check(
                    ActivityMonitor.safeRoot("/auth:login secret", Set.of("login")) == null,
                    "namespaced credential");
            check(
                    "sg".equals(ActivityMonitor.safeRoot("/staffgen:sg msg user text", Set.of())),
                    "discard arguments");
            System.out.println(
                    "Journal retention, sanitization, filtering and credential exclusion passed.");
        } finally {
            try (var children = Files.list(directory)) {
                for (Path child : children.toList()) Files.delete(child);
            }
            Files.delete(directory);
        }
    }

    private static void check(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }
}
