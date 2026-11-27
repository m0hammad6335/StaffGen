package ir.devwebs.staffgen.activity;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiPredicate;

/**
 * Optional staff observation. Bukkit objects stay on the server thread; the disk queue receives
 * strings.
 */
public final class ActivityMonitor implements Listener, AutoCloseable {
    public static final List<String> COMMANDS =
            List.of("logs", "spy", "cmdspy", "alts", "cps", "reach");
    private static final Set<String> PRIVATE_ROOTS =
            Set.of(
                    "login",
                    "l",
                    "register",
                    "reg",
                    "changepassword",
                    "changepass",
                    "changepw",
                    "cp",
                    "log",
                    "captcha",
                    "authme",
                    "email",
                    "2fa",
                    "totp",
                    "recovery");

    private record Options(
            int days,
            boolean chat,
            boolean commands,
            boolean logins,
            boolean spy,
            boolean cmdspy,
            boolean alts,
            boolean combat,
            Set<String> privateRoots) {}

    private static final class Sample {
        final long started = System.nanoTime();
        int swings;
        int hits;
        double maximumDistance;
    }

    private final JavaPlugin plugin;
    private final BiPredicate<CommandSender, String> allowed;
    private final ActivityJournal journal;
    private final Set<UUID> socialObservers = new HashSet<>();
    private final Set<UUID> commandObservers = new HashSet<>();
    private final Map<UUID, Sample> samples = new HashMap<>();
    private final Map<UUID, Long> recentReads = new HashMap<>();
    private final AtomicLong dropped = new AtomicLong();
    private final ThreadPoolExecutor io =
            new ThreadPoolExecutor(
                    1,
                    1,
                    0,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(2048),
                    task -> {
                        Thread thread = new Thread(task, "staffgen-journal");
                        thread.setDaemon(true);
                        return thread;
                    });
    private final BukkitTask maintenance;
    private volatile Options options;

    public ActivityMonitor(JavaPlugin plugin, BiPredicate<CommandSender, String> allowed) {
        this.plugin = plugin;
        this.allowed = allowed;
        this.journal =
                new ActivityJournal(
                        plugin.getDataFolder().toPath().resolve("activity"), Clock.systemUTC());
        File config = new File(plugin.getDataFolder(), "activity.yml");
        if (!config.exists()) plugin.saveResource("activity.yml", false);
        reload();
        maintenance =
                Bukkit.getScheduler()
                        .runTaskTimer(
                                plugin,
                                () -> {
                                    int keep = options.days();
                                    disk(
                                            () -> {
                                                int removed = journal.prune(keep);
                                                if (removed > 0)
                                                    journal.append(
                                                            "console",
                                                            "audit",
                                                            "Expired "
                                                                    + removed
                                                                    + " activity files");
                                            });
                                },
                                1L,
                                20L * 3600);
    }

    public void reload() {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(new File(plugin.getDataFolder(), "activity.yml"));
        } catch (Exception ex) {
            throw new IllegalArgumentException("Cannot load activity.yml", ex);
        }
        int days = yaml.getInt("retention-days", -1);
        if (days < 1 || days > 3650)
            throw new IllegalArgumentException("retention-days must be 1 to 3650.");
        String[] flags = {
            "record-chat",
            "record-command-names",
            "record-logins",
            "allow-private-message-spy",
            "allow-command-spy",
            "allow-online-alt-check",
            "allow-combat-observation"
        };
        for (String flag : flags)
            if (!yaml.isBoolean(flag))
                throw new IllegalArgumentException(
                        "Expected true/false in activity.yml for " + flag);
        Set<String> hidden = new HashSet<>(PRIVATE_ROOTS);
        for (String label : yaml.getStringList("hidden-commands"))
            hidden.add(label.toLowerCase(Locale.ROOT));
        options =
                new Options(
                        days,
                        yaml.getBoolean(flags[0]),
                        yaml.getBoolean(flags[1]),
                        yaml.getBoolean(flags[2]),
                        yaml.getBoolean(flags[3]),
                        yaml.getBoolean(flags[4]),
                        yaml.getBoolean(flags[5]),
                        yaml.getBoolean(flags[6]),
                        Set.copyOf(hidden));
        if (!options.spy()) socialObservers.clear();
        if (!options.cmdspy()) commandObservers.clear();
        if (!options.combat()) samples.clear();
    }

    public void audit(CommandSender actor, String action, UUID target) {
        String id = actor instanceof Player p ? p.getUniqueId().toString() : actor.getName();
        write(id, "audit", action + (target == null ? "" : " " + target));
    }

    public void privateMessage(Player from, Player to, String text) {
        if (options.spy())
            observe(
                    socialObservers,
                    "spy",
                    from,
                    to,
                    "[StaffGen PM] " + from.getName() + " → " + to.getName() + ": " + text);
    }

    public void execute(CommandSender sender, String action, String[] args) {
        if (!allowed.test(sender, action)) throw new IllegalArgumentException("Permission denied.");
        switch (action) {
            case "logs" -> read(sender, args);
            case "spy", "cmdspy" -> {
                boolean social = action.equals("spy");
                require(social ? options.spy() : options.cmdspy());
                if (!(sender instanceof Player player))
                    throw new IllegalArgumentException("This command requires a player.");
                Set<UUID> observers = social ? socialObservers : commandObservers;
                boolean enabled = observers.add(player.getUniqueId());
                if (!enabled) observers.remove(player.getUniqueId());
                audit(sender, action + " " + (enabled ? "enabled" : "disabled"), null);
                sender.sendMessage(action + " " + (enabled ? "enabled" : "disabled"));
            }
            case "alts", "cps", "reach" -> {
                require(action.equals("alts") ? options.alts() : options.combat());
                Player target = args.length > 1 ? Bukkit.getPlayerExact(args[1]) : null;
                if (target == null || !visible(sender, target))
                    throw new IllegalArgumentException("Specify a visible online player.");
                audit(sender, action, target.getUniqueId());
                if (action.equals("alts")) sharedAddress(sender, target);
                else sample(sender, action, target);
            }
            default -> throw new IllegalArgumentException("Unknown observation command.");
        }
    }

    private void read(CommandSender sender, String[] args) {
        LocalDate day;
        try {
            day = args.length < 2 ? LocalDate.now(Clock.systemUTC()) : LocalDate.parse(args[1]);
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("Use /sg logs [YYYY-MM-DD]");
        }
        if (sender instanceof Player p) {
            long now = System.nanoTime();
            Long previous = recentReads.get(p.getUniqueId());
            if (previous != null && now - previous < TimeUnit.SECONDS.toNanos(3))
                throw new IllegalArgumentException("Please wait before reading another log.");
            recentReads.put(p.getUniqueId(), now);
        }
        audit(sender, "logs " + day, null);
        boolean queued =
                disk(
                        () -> {
                            var lines = journal.tail(day, category -> true, 20);
                            if (plugin.isEnabled())
                                Bukkit.getScheduler()
                                        .runTask(
                                                plugin,
                                                () -> {
                                                    if (sender instanceof Player p && !p.isOnline()
                                                            || !allowed.test(sender, "logs"))
                                                        return;
                                                    if (lines.isEmpty())
                                                        sender.sendMessage(
                                                                "No activity for " + day);
                                                    else lines.forEach(sender::sendMessage);
                                                });
                        });
        if (!queued) throw new IllegalArgumentException("Journal busy. Try again.");
    }

    private static void sharedAddress(CommandSender sender, Player target) {
        InetSocketAddress address = target.getAddress();
        if (address == null) throw new IllegalArgumentException("No address available.");
        List<String> names = new ArrayList<>();
        for (Player other : Bukkit.getOnlinePlayers()) {
            InetSocketAddress candidate = other.getAddress();
            if (!other.equals(target)
                    && visible(sender, other)
                    && candidate != null
                    && address.getAddress().equals(candidate.getAddress()))
                names.add(other.getName());
        }
        sender.sendMessage(
                names.isEmpty()
                        ? "No visible online players share this address."
                        : "Shared connection: " + String.join(", ", names));
    }

    private void sample(CommandSender sender, String action, Player target) {
        UUID id = target.getUniqueId();
        Sample sample = new Sample();
        if (samples.putIfAbsent(id, sample) != null)
            throw new IllegalArgumentException("Observation already in progress.");
        sender.sendMessage("Observing " + target.getName() + " for five seconds.");
        Bukkit.getScheduler()
                .runTaskLater(
                        plugin,
                        () -> {
                            if (!samples.remove(id, sample)
                                    || !allowed.test(sender, action)
                                    || !visible(sender, target)) return;
                            double seconds =
                                    Math.max(
                                            0.001,
                                            (System.nanoTime() - sample.started) / 1_000_000_000.0);
                            sender.sendMessage(
                                    action.equals("cps")
                                            ? String.format(
                                                    Locale.ROOT,
                                                    "Observed %.1f arm swings/s.",
                                                    sample.swings / seconds)
                                            : String.format(
                                                    Locale.ROOT,
                                                    "%d melee hits; maximum center distance %.2f"
                                                            + " blocks.",
                                                    sample.hits,
                                                    sample.maximumDistance));
                        },
                        100L);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void swing(PlayerAnimationEvent event) {
        Sample sample = samples.get(event.getPlayer().getUniqueId());
        if (sample != null) sample.swings++;
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void hit(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) return;
        Sample sample = samples.get(player.getUniqueId());
        if (sample != null && player.getWorld().equals(event.getEntity().getWorld())) {
            sample.hits++;
            sample.maximumDistance =
                    Math.max(
                            sample.maximumDistance,
                            player.getLocation().distance(event.getEntity().getLocation()));
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void command(PlayerCommandPreprocessEvent event) {
        Options policy = options;
        if (!policy.commands() && commandObservers.isEmpty()) return;
        String root = safeRoot(event.getMessage(), policy.privateRoots());
        if (root == null) return;
        Player actor = event.getPlayer();
        if (policy.commands()) write(actor.getUniqueId().toString(), "command", root);
        if (policy.cmdspy())
            observe(
                    commandObservers,
                    "cmdspy",
                    actor,
                    null,
                    "[StaffGen] " + actor.getName() + " /" + root);
    }

    static String safeRoot(String command, Set<String> hidden) {
        if (!command.startsWith("/")) return null;
        int end = 1;
        while (end < command.length() && !Character.isWhitespace(command.charAt(end))) end++;
        String label = command.substring(1, end).toLowerCase(Locale.ROOT);
        label = label.substring(label.lastIndexOf(':') + 1);
        if (label.length() > 64 || !label.matches("[a-z0-9_-]+") || hidden.contains(label))
            return null;
        return label;
    }

    private void observe(
            Set<UUID> observers, String action, Player actor, Player recipient, String message) {
        for (Iterator<UUID> it = observers.iterator(); it.hasNext(); ) {
            Player viewer = Bukkit.getPlayer(it.next());
            if (viewer == null || !allowed.test(viewer, action)) {
                it.remove();
                continue;
            }
            if (!viewer.equals(actor)
                    && !viewer.equals(recipient)
                    && visible(viewer, actor)
                    && (recipient == null || visible(viewer, recipient)))
                viewer.sendMessage(message);
        }
    }

    private static boolean visible(CommandSender viewer, Player target) {
        return !(viewer instanceof Player p) || p.canSee(target);
    }

    private static void require(boolean enabled) {
        if (!enabled)
            throw new IllegalArgumentException("This feature is disabled in activity.yml.");
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void chat(AsyncPlayerChatEvent event) {
        if (options.chat())
            write(event.getPlayer().getUniqueId().toString(), "chat", event.getMessage());
    }

    @EventHandler
    public void join(PlayerJoinEvent event) {
        if (options.logins()) write(event.getPlayer().getUniqueId().toString(), "login", "joined");
    }

    @EventHandler
    public void leave(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        socialObservers.remove(id);
        commandObservers.remove(id);
        samples.remove(id);
        recentReads.remove(id);
        if (options.logins()) write(id.toString(), "login", "left");
    }

    private void write(String actor, String kind, String message) {
        disk(() -> journal.append(actor, kind, message));
    }

    private interface DiskTask {
        void run() throws IOException;
    }

    private boolean disk(DiskTask task) {
        try {
            io.execute(
                    () -> {
                        try {
                            task.run();
                        } catch (IOException ex) {
                            reportLoss();
                        }
                    });
            return true;
        } catch (RejectedExecutionException ex) {
            reportLoss();
            return false;
        }
    }

    private void reportLoss() {
        long count = dropped.incrementAndGet();
        if (count == 1 || count % 100 == 0)
            plugin.getLogger().warning("Activity journal operations lost: " + count);
    }

    @Override
    public void close() {
        maintenance.cancel();
        io.shutdown();
        try {
            if (!io.awaitTermination(10, TimeUnit.SECONDS))
                plugin.getLogger().warning("Activity journal is still flushing.");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
