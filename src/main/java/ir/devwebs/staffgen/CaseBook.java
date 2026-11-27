package ir.devwebs.staffgen;

import org.bukkit.configuration.ConfigurationSection;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Predicate;

/** Durable case history and the small in-memory indexes used by login, chat and movement events. */
final class CaseBook implements AutoCloseable {
    record Entry(
            UUID id,
            UUID player,
            String actor,
            String kind,
            String reason,
            long created,
            long expires,
            UUID closes) {
        Entry {
            Objects.requireNonNull(id);
            Objects.requireNonNull(player);
            Objects.requireNonNull(reason);
            if (actor == null
                    || actor.isBlank()
                    || actor.length() > 128
                    || kind == null
                    || kind.isBlank()
                    || kind.length() > 20
                    || reason.length() > 2048) {
                throw new IllegalArgumentException("Invalid case metadata.");
            }
            if (created < 0 || expires < 0 || id.equals(closes)) {
                throw new IllegalArgumentException("Invalid case time or parent.");
            }
        }

        boolean current() {
            return expires == 0 || expires > System.currentTimeMillis();
        }
    }

    private record Key(UUID player, String kind) {}

    private static final Set<String> EXCLUSIVE = Set.of("ban", "mute", "freeze", "duty");
    private static final Comparator<Entry> ORDER =
            Comparator.comparingLong(Entry::created).thenComparing(Entry::id);
    private final Map<Key, Entry> active = new ConcurrentHashMap<>();
    private final Map<Key, Entry> latest = new ConcurrentHashMap<>();
    private final Map<UUID, Entry> entries = new ConcurrentHashMap<>();
    private final Map<UUID, Entry> closures = new ConcurrentHashMap<>();
    private final NavigableSet<Entry> timeline = new ConcurrentSkipListSet<>(ORDER);
    private final CaseStore store;
    private final ExecutorService writer =
            new ThreadPoolExecutor(
                    1,
                    1,
                    0,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(1024),
                    task -> new Thread(task, "StaffGen case writer"));

    static CaseBook open(Path root, ConfigurationSection settings) throws Exception {
        return new CaseBook(StorageSettings.connect(root, settings));
    }

    CaseBook(Path directory, SqlCases sql) throws Exception {
        this(sql == null ? new YamlCases(directory) : sql);
    }

    CaseBook(CaseStore store) throws Exception {
        this.store = store;
        try {
            store.load(this::insertIndex);
            // Providers do not promise read order. Build the active index after all revocations.
            for (Entry entry : timeline) {
                if (EXCLUSIVE.contains(entry.kind()) && open(entry)) {
                    active.put(new Key(entry.player(), entry.kind()), entry);
                }
            }
        } catch (Exception failure) {
            try {
                store.close();
            } catch (Exception closing) {
                failure.addSuppressed(closing);
            }
            throw failure;
        }
    }

    private void insertIndex(Entry entry) {
        Entry previous = entries.putIfAbsent(entry.id(), entry);
        if (previous != null && !previous.equals(entry))
            throw new IllegalStateException("Conflicting case UUID.");
        timeline.add(entry);
        latest.merge(
                new Key(entry.player(), entry.kind()),
                entry,
                (left, right) -> ORDER.compare(left, right) >= 0 ? left : right);
        if (entry.closes() != null) closures.put(entry.closes(), entry);
    }

    boolean open(Entry entry) {
        return entry.current() && !closures.containsKey(entry.id());
    }

    Optional<Entry> latest(UUID player, String kind) {
        return Optional.ofNullable(latest.get(new Key(player, kind)));
    }

    Optional<Entry> active(UUID player, String kind) {
        return Optional.ofNullable(active.get(new Key(player, kind))).filter(this::open);
    }

    List<Entry> snapshot() {
        return List.copyOf(timeline);
    }

    List<Entry> recent(Predicate<Entry> filter) {
        return page(filter, 1, 20);
    }

    long count(Predicate<Entry> filter) {
        return entries.values().stream().filter(filter).count();
    }

    List<Entry> page(Predicate<Entry> filter, int page, int size) {
        if (page < 1 || size < 1) throw new IllegalArgumentException("Invalid page bounds.");
        return timeline.descendingSet().stream()
                .filter(filter)
                .skip((long) (page - 1) * size)
                .limit(size)
                .toList();
    }

    Entry closure(UUID id) {
        return closures.get(id);
    }

    Entry find(UUID id) {
        return entries.get(id);
    }

    CompletableFuture<Void> append(Entry entry) {
        try {
            return CompletableFuture.runAsync(() -> persist(entry), writer);
        } catch (RejectedExecutionException busy) {
            return CompletableFuture.failedFuture(busy);
        }
    }

    private void persist(Entry entry) {
        Entry existing = entries.get(entry.id());
        if (entry.equals(existing)) return;
        if (existing != null) throw new IllegalStateException("Conflicting case UUID.");
        if (EXCLUSIVE.contains(entry.kind()) && active(entry.player(), entry.kind()).isPresent()) {
            throw new IllegalStateException("An active case already exists.");
        }
        if (entry.closes() != null) {
            Entry parent = entries.get(entry.closes());
            if (parent == null || !parent.player().equals(entry.player()) || !open(parent)) {
                throw new IllegalStateException("The target case is unavailable.");
            }
        }

        try {
            store.insert(entry);
        } catch (Exception failure) {
            // A connection may drop after a successful commit. Reconcile before reporting failure.
            try {
                if (!entry.equals(store.find(entry.id()))) throw new CompletionException(failure);
            } catch (CompletionException confirmedFailure) {
                throw confirmedFailure;
            } catch (Exception lookup) {
                failure.addSuppressed(lookup);
                throw new CompletionException(failure);
            }
        }
        insertIndex(entry);
        if (EXCLUSIVE.contains(entry.kind()))
            active.put(new Key(entry.player(), entry.kind()), entry);
    }

    @Override
    public void close() {
        writer.shutdown();
        boolean interrupted = false;
        try {
            while (!writer.isTerminated()) {
                try {
                    writer.awaitTermination(5, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    interrupted = true;
                }
            }
            store.close();
        } catch (Exception failure) {
            throw new IllegalStateException("Could not close case storage.", failure);
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
}
