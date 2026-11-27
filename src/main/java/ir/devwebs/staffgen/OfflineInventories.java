package ir.devwebs.staffgen;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;

/** Last-logout inspection never opens a player's live save file for writing. */
final class OfflineInventories implements Listener, AutoCloseable {
    private record Options(boolean enabled, int days, int maxBytes) {}

    private final StaffGen plugin;
    private final Path directory;
    private final ThreadPoolExecutor disk =
            new ThreadPoolExecutor(
                    1,
                    1,
                    0L,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(64),
                    r -> {
                        Thread t = new Thread(r, "staffgen-snapshots");
                        t.setDaemon(true);
                        return t;
                    });
    private final BukkitTask cleanup;
    private volatile Options options;
    private volatile boolean running = true;

    OfflineInventories(StaffGen plugin) {
        this.plugin = plugin;
        directory = plugin.getDataFolder().toPath().resolve("snapshots");
        if (!new File(plugin.getDataFolder(), "snapshots.yml").exists())
            plugin.saveResource("snapshots.yml", false);
        reload();
        cleanup = Bukkit.getScheduler().runTaskTimer(plugin, () -> submit(this::prune), 20, 72000);
    }

    void reload() {
        YamlConfiguration y = new YamlConfiguration();
        try {
            y.load(new File(plugin.getDataFolder(), "snapshots.yml"));
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid snapshots.yml", e);
        }
        int days = y.getInt("retention-days");
        int kib = y.getInt("max-kib");
        if (!y.isBoolean("enabled") || days < 1 || days > 365 || kib < 16 || kib > 2048)
            throw new IllegalArgumentException(
                    "Check enabled, retention-days (1–365) and max-kib (16–2048) in"
                            + " snapshots.yml.");
        options = new Options(y.getBoolean("enabled"), days, kib * 1024);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void quit(PlayerQuitEvent event) {
        if (!options.enabled()) return;
        Player p = event.getPlayer();
        YamlConfiguration data = new YamlConfiguration();
        // ItemStack serialization uses Bukkit item metadata; complete it before leaving the game
        // thread.
        data.set("rank", plugin.rank(p));
        data.set("protected", p.hasPermission("staffgen.protected"));
        capture(data, "inventory", p.getInventory());
        capture(data, "ender", p.getEnderChest());
        data.set("captured", Instant.now().toString());
        byte[] bytes = data.saveToString().getBytes(StandardCharsets.UTF_8);
        UUID owner = p.getUniqueId();
        submit(
                () -> {
                    if (!options.enabled()) return;
                    Path target = file(owner);
                    if (bytes.length > options.maxBytes()) {
                        Files.deleteIfExists(target);
                        return;
                    }
                    Files.createDirectories(directory);
                    Path tmp = Files.createTempFile(directory, "snapshot-", ".tmp");
                    try {
                        Files.write(tmp, bytes);
                        Files.move(
                                tmp,
                                target,
                                StandardCopyOption.ATOMIC_MOVE,
                                StandardCopyOption.REPLACE_EXISTING);
                    } finally {
                        Files.deleteIfExists(tmp);
                    }
                });
    }

    private static void capture(YamlConfiguration data, String key, Inventory inventory) {
        ItemStack[] contents = inventory.getContents();
        for (int slot = 0; slot < Math.min(contents.length, 54); slot++) {
            if (contents[slot] != null) data.set(key + "." + slot, contents[slot]);
        }
    }

    void inspect(Player viewer, OfflinePlayer target, boolean ender) {
        String action = ender ? "endersee" : "invsee";
        if (!options.enabled()
                || !plugin.allowed(viewer, "invsee.offline")
                || !plugin.allowed(viewer, action))
            throw new IllegalArgumentException("Offline inspection is restricted.");
        UUID owner = target.getUniqueId();
        plugin.audit(viewer, action + " offline", owner);
        if (!submit(
                () -> {
                    Path source = file(owner);
                    String text =
                            Files.exists(source)
                                            && Files.size(source) <= options.maxBytes()
                                            && fresh(source)
                                    ? Files.readString(source)
                                    : "";
                    if (!running) return;
                    Bukkit.getScheduler()
                            .runTask(
                                    plugin,
                                    () -> {
                                        if (!viewer.isOnline()
                                                || !options.enabled()
                                                || !plugin.allowed(viewer, "invsee.offline")
                                                || !plugin.allowed(viewer, action)) return;
                                        if (text.isEmpty() || target.isOnline()) {
                                            plugin.tell(
                                                    viewer,
                                                    "No current offline snapshot. Retry after the"
                                                            + " player logs out.");
                                            return;
                                        }
                                        YamlConfiguration data = new YamlConfiguration();
                                        try {
                                            data.loadFromString(text);
                                        } catch (Exception ex) {
                                            plugin.tell(viewer, "Unreadable inventory snapshot.");
                                            return;
                                        }
                                        if (data.getBoolean("protected", true)
                                                || plugin.rank(viewer)
                                                        < data.getInt("rank", Integer.MAX_VALUE)) {
                                            plugin.tell(viewer, "This player is protected.");
                                            return;
                                        }
                                        Inventory contents = Bukkit.createInventory(null, 54);
                                        String key = ender ? "ender." : "inventory.";
                                        for (int i = 0; i < 54; i++)
                                            contents.setItem(i, data.getItemStack(key + i));
                                        StaffMenu.inspect(
                                                viewer,
                                                contents,
                                                "Last logout: " + target.getName());
                                        plugin.tell(
                                                viewer,
                                                "Captured "
                                                        + data.getString("captured", "unknown"));
                                    });
                })) throw new IllegalArgumentException("Snapshot queue is busy.");
    }

    private Path file(UUID id) {
        return directory.resolve(id + ".yml");
    }

    private boolean fresh(Path file) throws IOException {
        return Files.getLastModifiedTime(file).toMillis()
                >= System.currentTimeMillis() - TimeUnit.DAYS.toMillis(options.days());
    }

    private void prune() throws IOException {
        if (!Files.isDirectory(directory)) return;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*.yml")) {
            for (Path file : files) {
                String filename = file.getFileName().toString();
                try {
                    UUID.fromString(filename.substring(0, filename.length() - 4));
                } catch (IllegalArgumentException otherFile) {
                    continue;
                }
                if (!fresh(file)) Files.deleteIfExists(file);
            }
        }
    }

    private interface Task {
        void run() throws IOException;
    }

    private boolean submit(Task task) {
        try {
            disk.execute(
                    () -> {
                        try {
                            task.run();
                        } catch (IOException failure) {
                            plugin.getLogger().warning("Inventory snapshot I/O failed.");
                        }
                    });
            return true;
        } catch (RejectedExecutionException busy) {
            return false;
        }
    }

    @Override
    public void close() {
        running = false;
        cleanup.cancel();
        disk.shutdown();
        try {
            if (!disk.awaitTermination(10, TimeUnit.SECONDS))
                plugin.getLogger().warning("Snapshots are still flushing.");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
