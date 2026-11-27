package ir.devwebs.staffgen;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;

import java.util.*;
import java.util.concurrent.*;

/** Team communication and read-only reports derived from committed moderation records. */
final class TeamExtras implements Listener, AutoCloseable {
    private static final UUID SERVER = new UUID(0, 0);
    private static final Set<String> ACTIONS =
            Set.of(
                    "ban",
                    "mute",
                    "warn",
                    "kick",
                    "unban",
                    "unmute",
                    "unwarn",
                    "freeze",
                    "unfreeze",
                    "resolve");
    private final StaffGroups groups;
    private final StaffGen plugin;
    private final CaseBook book;
    private final ThreadPoolExecutor reports =
            new ThreadPoolExecutor(
                    1,
                    1,
                    0,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(16),
                    runnable -> {
                        Thread t = new Thread(runnable, "staffgen-reports");
                        t.setDaemon(true);
                        return t;
                    });
    private volatile boolean running = true;

    TeamExtras(StaffGen plugin, CaseBook book) {
        this.plugin = plugin;
        this.book = book;
        groups = new StaffGroups(plugin);
    }

    void execute(CommandSender sender, String action, String[] args) {
        switch (action) {
            case "group" -> {
                if (!(sender instanceof Player player))
                    throw new IllegalArgumentException("team-player-required");
                groups.execute(player, args);
            }
            case "pin" -> {
                if (args.length == 1) {
                    showPin(sender);
                    return;
                }
                boolean clear = args.length == 2 && args[1].equalsIgnoreCase("clear");
                plugin.save(
                        sender,
                        clear ? "unpin" : "pin",
                        SERVER,
                        clear ? "Cleared" : text(args),
                        0,
                        null,
                        () -> {
                            if (!clear) announce("[Pinned] " + text(args));
                        });
            }
            case "announce" -> {
                String message = text(args);
                plugin.save(sender, "announce", SERVER, message, 0, null, () -> announce(message));
            }
            case "profile", "stats", "top" -> report(sender, action, args);
            default -> throw new IllegalArgumentException("team-unknown-team-operation");
        }
    }

    private void report(CommandSender sender, String action, String[] args) {
        boolean top = action.equals("top");
        OfflinePlayer subject = null;
        if (!top) {
            if (args.length >= 2) subject = plugin.target(sender, args[1]);
            else if (sender instanceof Player player) subject = player;
            else throw new IllegalArgumentException("team-specify-a-player");
        }
        UUID id = subject == null ? null : subject.getUniqueId();
        int pos = top ? 1 : 2;
        int days = args.length <= pos ? 7 : Integer.parseInt(args[pos]);
        if (days < 1 || days > 3650)
            throw new IllegalArgumentException("team-days-must-be-between-1-and-3650");
        if (action.equals("profile"))
            plugin.tell(sender, plugin.tr("team-profile", "name", plugin.role(id)));
        long since = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days);
        try {
            reports.execute(
                    () -> {
                        Map<String, Integer> totals = new HashMap<>();
                        for (CaseBook.Entry e : book.snapshot()) {
                            if (e.created() < since || !ACTIONS.contains(e.kind())) continue;
                            if (top) totals.merge(e.actor(), 1, Integer::sum);
                            else if (id.toString().equals(e.actor()))
                                totals.merge(e.kind(), 1, Integer::sum);
                        }
                        var rows =
                                totals.entrySet().stream()
                                        .sorted(
                                                Map.Entry.<String, Integer>comparingByValue()
                                                        .reversed()
                                                        .thenComparing(Map.Entry.comparingByKey()))
                                        .limit(10)
                                        .toList();
                        if (!running) return;
                        Bukkit.getScheduler()
                                .runTask(
                                        plugin,
                                        () -> {
                                            if (sender instanceof Player p && !p.isOnline()
                                                    || !plugin.allowed(sender, action)) return;
                                            plugin.tell(
                                                    sender,
                                                    "Moderation actions in the last "
                                                            + days
                                                            + " days:");
                                            if (rows.isEmpty())
                                                plugin.tell(sender, plugin.tr("team-empty"));
                                            for (var row : rows)
                                                plugin.tell(
                                                        sender,
                                                        row.getKey() + "  " + row.getValue());
                                        });
                    });
        } catch (RejectedExecutionException busy) {
            throw new IllegalArgumentException("team-report-queue-is-full-try-again");
        }
    }

    private void showPin(CommandSender sender) {
        var records =
                book.recent(
                        e ->
                                e.player().equals(SERVER)
                                        && (e.kind().equals("pin") || e.kind().equals("unpin")));
        if (!records.isEmpty() && records.get(0).kind().equals("pin"))
            plugin.tell(sender, "[Pinned] " + records.get(0).reason());
        else if (!(sender instanceof Player)) plugin.tell(sender, plugin.tr("team-no-pin"));
    }

    private boolean receives(Player player) {
        return plugin.allowed(player, "staffchat")
                || plugin.allowed(player, "announcements.receive");
    }

    private void announce(String text) {
        for (Player player : Bukkit.getOnlinePlayers())
            if (receives(player)) plugin.tell(player, text);
    }

    private static String text(String[] args) {
        if (args.length < 2) throw new IllegalArgumentException("team-enter-a-message");
        String message = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        if (message.length() > 2048) throw new IllegalArgumentException("team-message-is-too-long");
        return message;
    }

    @EventHandler
    public void join(PlayerJoinEvent e) {
        if (receives(e.getPlayer())) showPin(e.getPlayer());
    }

    @EventHandler
    public void quit(PlayerQuitEvent e) {
        groups.leave(e.getPlayer().getUniqueId());
    }

    @Override
    public void close() {
        running = false;
        reports.shutdownNow();
    }
}
