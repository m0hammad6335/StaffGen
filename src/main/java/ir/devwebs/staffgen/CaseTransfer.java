package ir.devwebs.staffgen;

import org.bukkit.configuration.file.YamlConfiguration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Offline transfer utility. Each argument points to a StaffGen data directory containing
 * config.yml. Source and destination servers must be stopped so the comparison covers a stable
 * snapshot.
 */
public final class CaseTransfer {
    private CaseTransfer() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 2
                || args.length > 3
                || (args.length == 3 && !args[2].equals("--apply"))) {
            throw new IllegalArgumentException("Usage: CaseTransfer SOURCE_DIR DEST_DIR [--apply]");
        }
        Path from = Path.of(args[0]).toRealPath();
        Path to = Path.of(args[1]).toRealPath();
        if (from.equals(to))
            throw new IllegalArgumentException("Choose different configuration directories.");
        try (CaseStore source = open(from);
                CaseStore destination = open(to)) {
            transfer(source, destination, args.length == 3);
        }
    }

    private static CaseStore open(Path dir) throws Exception {
        Path file = dir.resolve("config.yml");
        if (!Files.isRegularFile(file))
            throw new IllegalArgumentException("Missing config.yml in " + dir);
        YamlConfiguration settings = new YamlConfiguration();
        settings.load(file.toFile());
        return StorageSettings.connect(dir, settings);
    }

    static void transfer(CaseStore source, CaseStore destination, boolean apply) throws Exception {
        Map<UUID, CaseBook.Entry> original = read(source);
        Map<UUID, CaseBook.Entry> existing = read(destination);
        List<CaseBook.Entry> missing = new ArrayList<>();
        for (CaseBook.Entry entry : original.values()) {
            CaseBook.Entry current = existing.get(entry.id());
            if (current == null) missing.add(entry);
            else if (!current.equals(entry))
                throw new IllegalStateException("Conflicting case " + entry.id());
        }
        System.out.printf(
                "%d source cases, %d matching, %d to copy.%n",
                original.size(), original.size() - missing.size(), missing.size());
        if (!apply) return;

        // Stable UUIDs and preflight conflict checks allow an interrupted copy to be resumed.
        for (CaseBook.Entry entry : missing) destination.insert(entry);
        Map<UUID, CaseBook.Entry> verification = read(destination);
        for (CaseBook.Entry entry : original.values()) {
            if (!entry.equals(verification.get(entry.id()))) {
                throw new IllegalStateException("Verification failed for " + entry.id());
            }
        }
        System.out.println("All source case fields and closure links verified.");
    }

    private static Map<UUID, CaseBook.Entry> read(CaseStore store) throws Exception {
        Map<UUID, CaseBook.Entry> cases = new LinkedHashMap<>();
        store.load(
                entry -> {
                    CaseBook.Entry previous = cases.putIfAbsent(entry.id(), entry);
                    if (previous != null && !previous.equals(entry))
                        throw new IllegalStateException("Duplicate case UUID.");
                });
        return cases;
    }
}
