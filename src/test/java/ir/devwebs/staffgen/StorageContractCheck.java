package ir.devwebs.staffgen;

import org.bukkit.configuration.file.YamlConfiguration;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CompletionException;

/** Run against an empty, disposable data folder. The config selects the actual storage provider. */
public final class StorageContractCheck {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]).toAbsolutePath();
        YamlConfiguration settings = new YamlConfiguration();
        settings.load(root.resolve("config.yml").toFile());
        UUID player = UUID.randomUUID();
        CaseBook.Entry ban =
                new CaseBook.Entry(
                        UUID.randomUUID(),
                        player,
                        "console",
                        "ban",
                        "Unicode «آزمایش»\nwith a newline",
                        123456789,
                        0,
                        null);
        CaseBook.Entry undo =
                new CaseBook.Entry(
                        UUID.randomUUID(),
                        player,
                        "console",
                        "unban",
                        "Reviewed",
                        123456790,
                        0,
                        ban.id());

        try (CaseBook cases = CaseBook.open(root, settings)) {
            cases.append(ban).join();
            cases.append(ban).join();
            require(cases.count(entry -> entry.id().equals(ban.id())) == 1, "Duplicate insertion");
            require(cases.active(player, "ban").orElseThrow().equals(ban), "Active lookup failed");
            CaseBook.Entry conflict =
                    new CaseBook.Entry(ban.id(), player, "other", "ban", "Conflict", 10, 0, null);
            try {
                cases.append(conflict).join();
                throw new AssertionError("Accepted a conflicting ID");
            } catch (CompletionException expected) {
                require(cases.find(ban.id()).equals(ban), "Conflict changed original record");
            }
        }
        try (CaseBook cases = CaseBook.open(root, settings)) {
            require(
                    cases.active(player, "ban").orElseThrow().equals(ban),
                    "Case did not survive restart");
            cases.append(undo).join();
        }
        try (CaseBook cases = CaseBook.open(root, settings)) {
            require(cases.active(player, "ban").isEmpty(), "Revocation did not survive restart");
            require(undo.equals(cases.closure(ban.id())), "Closure link was lost");
            require(
                    cases.page(entry -> entry.player().equals(player), 2, 1)
                            .equals(Arrays.asList(ban)),
                    "Pagination did not preserve case order");
        }
        System.out.println(settings.getString("storage", "yaml") + " storage checks passed.");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
