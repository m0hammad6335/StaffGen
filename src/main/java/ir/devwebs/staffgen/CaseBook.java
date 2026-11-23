package ir.devwebs.staffgen;

import org.bukkit.configuration.file.YamlConfiguration;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Stream;

/** Append-only cases. Closing a case preserves the original actor and reason. */
final class CaseBook implements AutoCloseable {
    record Entry(UUID id, UUID player, String actor, String kind, String reason,
                 long created, long expires, UUID closes) {
        boolean current() { return expires == 0 || expires > System.currentTimeMillis(); }
    }

    private final Path directory;
    private final SqlCases sql;
    private record Key(UUID player,String kind) {}
    private final Map<Key,Entry> activeIndex = new ConcurrentHashMap<>();
    private final Map<UUID, Entry> entries = new ConcurrentHashMap<>();
    private final Set<UUID> closed = ConcurrentHashMap.newKeySet();
    private final ExecutorService writer = new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(1024));

    CaseBook(Path directory, SqlCases sql) throws Exception {
        this.sql = sql;
        this.directory = Files.createDirectories(directory);
        if (sql != null) {
            try { sql.load(this::index); rebuildActive(); } catch (Exception ex) { sql.close(); throw ex; }
            return;
        }
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".yml")).toList()) {
                YamlConfiguration y = new YamlConfiguration();
                y.load(file.toFile());
                String parent = y.getString("closes");
                index(new Entry(UUID.fromString(y.getString("id")), UUID.fromString(y.getString("player")),
                    Objects.requireNonNull(y.getString("actor")), Objects.requireNonNull(y.getString("kind")),
                    y.getString("reason", ""), y.getLong("created"), y.getLong("expires"),
                    parent == null ? null : UUID.fromString(parent)));
            }
        }
        rebuildActive();
    }

    private void rebuildActive() {
        activeIndex.clear();
        entries.values().forEach(this::index);
    }

    private void index(Entry entry) {
        entries.put(entry.id(), entry);
        if (entry.closes()==null && Set.of("ban","mute","freeze","duty").contains(entry.kind()) && open(entry))
            activeIndex.merge(new Key(entry.player(),entry.kind()),entry,(a,b) -> a.created() >= b.created() ? a : b);
        if (entry.closes() != null) closed.add(entry.closes());
    }

    boolean open(Entry entry) { return entry.current() && !closed.contains(entry.id()); }

    Optional<Entry> active(UUID player, String kind) {
        return Optional.ofNullable(activeIndex.get(new Key(player,kind))).filter(this::open);
    }

    List<Entry> recent(java.util.function.Predicate<Entry> filter) {
        return entries.values().stream().filter(filter).sorted(Comparator.comparingLong(Entry::created)
            .reversed()).limit(20).toList();
    }

    Entry find(UUID id) { return entries.get(id); }

    CompletableFuture<Void> append(Entry e) {
        try { return CompletableFuture.runAsync(() -> {
            if (e.closes() != null && closed.contains(e.closes()))
                throw new IllegalStateException("Case is already closed");
            if (Set.of("ban","mute","freeze","duty").contains(e.kind()) && active(e.player(), e.kind()).isPresent())
                throw new IllegalStateException("An active case already exists");
            if (sql != null) {
                try { sql.insert(e); } catch (java.sql.SQLException ex) { throw new CompletionException(ex); }
                index(e);
                return;
            }
            YamlConfiguration y = new YamlConfiguration();
            y.set("id", e.id().toString());
            y.set("player", e.player().toString());
            y.set("actor", e.actor());
            y.set("kind", e.kind());
            y.set("reason", e.reason());
            y.set("created", e.created());
            y.set("expires", e.expires());
            if (e.closes() != null) y.set("closes", e.closes().toString());
            try {
                Path tmp = directory.resolve(e.id() + ".tmp");
                y.save(tmp.toFile());
                Files.move(tmp, directory.resolve(e.id() + ".yml"), StandardCopyOption.ATOMIC_MOVE);
                index(e);
            } catch (IOException ex) { throw new CompletionException(ex); }
        }, writer); } catch (RejectedExecutionException ex) { return CompletableFuture.failedFuture(ex); }
    }

    public void close() {
        writer.shutdown();
        try { while (!writer.awaitTermination(10, TimeUnit.SECONDS)) { /* Flush pending writes. */ }
            if (sql != null) sql.close(); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
        catch (Exception ex) { throw new IllegalStateException("Could not close case storage", ex); }
    }
}



