package ir.devwebs.staffgen;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;

final class YamlCases implements CaseStore {
    private final Path directory;

    YamlCases(Path directory) throws Exception {
        this.directory = Files.createDirectories(directory);
    }

    @Override
    public void load(Consumer<CaseBook.Entry> accept) throws Exception {
        try (Stream<Path> paths = Files.list(directory)) {
            for (Path file : paths.filter(p -> p.toString().endsWith(".yml")).toList()) {
                CaseBook.Entry entry = CaseDocument.parse(Files.readString(file));
                if (!file.getFileName().toString().equals(entry.id() + ".yml")) {
                    throw new IllegalStateException(
                            "Mismatched case identifier in " + file.getFileName());
                }
                accept.accept(entry);
            }
        }
    }

    @Override
    public CaseBook.Entry find(UUID id) throws Exception {
        Path file = directory.resolve(id + ".yml");
        return Files.exists(file) ? CaseDocument.parse(Files.readString(file)) : null;
    }

    @Override
    public void insert(CaseBook.Entry entry) throws Exception {
        Path target = directory.resolve(entry.id() + ".yml");
        if (Files.exists(target)) throw new FileAlreadyExistsException(target.toString());
        Path tmp = Files.createTempFile(directory, ".writing-", ".tmp");
        try {
            // Force the complete payload to disk before making its final name visible to readers.
            try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
                ByteBuffer bytes = StandardCharsets.UTF_8.encode(CaseDocument.yaml(entry));
                while (bytes.hasRemaining()) channel.write(bytes);
                channel.force(true);
            }
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
