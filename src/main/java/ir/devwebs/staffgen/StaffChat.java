package ir.devwebs.staffgen;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Session chat settings. Only the public chat interval and staff channel flag are read
 * asynchronously.
 */
final class StaffChat implements Listener {
    static final Set<String> COMMANDS =
            Set.of(
                    "staffchat",
                    "msg",
                    "reply",
                    "ignore",
                    "chatlock",
                    "slowchat",
                    "broadcast",
                    "clearchat");
    private final StaffGen plugin;
    private final Map<UUID, UUID> lastContact = new HashMap<>();
    private final Map<UUID, Set<UUID>> ignored = new HashMap<>();
    private final Set<UUID> channel = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> lastChat = new ConcurrentHashMap<>();
    private volatile boolean locked;
    private volatile long interval;

    StaffChat(StaffGen plugin) {
        this.plugin = plugin;
    }

    void execute(CommandInput in) {
        switch (in.action()) {
            case "staffchat" -> {
                if (in.words().length == 1) {
                    UUID id = in.player().getUniqueId();
                    boolean enabled = channel.add(id);
                    if (!enabled) channel.remove(id);
                    plugin.tell(in.sender(), plugin.tr(enabled ? "channel-on" : "channel-off"));
                } else staff(in.sender().getName(), in.text(1));
            }
            case "chatlock" -> {
                locked = !locked;
                plugin.tell(in.sender(), plugin.tr(locked ? "chat-locked" : "chat-unlocked"));
            }
            case "slowchat" -> {
                int seconds;
                try {
                    seconds = Integer.parseInt(in.word(1));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("slow-range");
                }
                if (seconds < 0 || seconds > 3600) throw new IllegalArgumentException("slow-range");
                interval = seconds * 1000L;
                plugin.tell(
                        in.sender(),
                        plugin.tr("chat-interval", "seconds", String.valueOf(seconds)));
            }
            case "broadcast" -> Bukkit.broadcastMessage(plugin.title() + in.text(1));
            case "clearchat" ->
                    Bukkit.broadcastMessage(
                            "\n".repeat(100) + plugin.title() + plugin.tr("cleared"));
            default -> whisper(in);
        }
        if (Set.of("chatlock", "slowchat", "clearchat", "broadcast").contains(in.action()))
            plugin.audit(in.sender(), in.action(), new UUID(0, 0));
    }

    private void staff(String name, String message) {
        String formatted = plugin.tr("staff-chat", "player", name, "message", message);
        for (Player p : Bukkit.getOnlinePlayers())
            if (plugin.allowed(p, "staffchat")) plugin.tell(p, formatted);
    }

    private void whisper(CommandInput in) {
        Player from = in.player();
        UUID previous = lastContact.get(from.getUniqueId());
        Player to =
                in.action().equals("reply")
                        ? (previous == null ? null : Bukkit.getPlayer(previous))
                        : Bukkit.getPlayerExact(in.word(1));
        if (to == null || !from.canSee(to)) throw new IllegalArgumentException("unknown-online");
        if (in.action().equals("ignore")) {
            Set<UUID> list =
                    ignored.computeIfAbsent(from.getUniqueId(), ignoredId -> new HashSet<>());
            boolean added = list.add(to.getUniqueId());
            if (!added) list.remove(to.getUniqueId());
            plugin.tell(from, plugin.tr(added ? "ignored" : "unignored", "player", to.getName()));
            return;
        }
        if (ignored.getOrDefault(to.getUniqueId(), Set.of()).contains(from.getUniqueId()))
            throw new IllegalArgumentException("unknown-online");
        if (plugin.muted(from)) throw new IllegalArgumentException("muted");
        String body = in.text(in.action().equals("reply") ? 1 : 2);
        String line =
                plugin.tr(
                        "private-chat",
                        "from",
                        from.getName(),
                        "to",
                        to.getName(),
                        "message",
                        body);
        plugin.tell(from, line);
        if (!from.equals(to)) plugin.tell(to, line);
        plugin.privateMessage(from, to, body);
        lastContact.put(to.getUniqueId(), from.getUniqueId());
        lastContact.put(from.getUniqueId(), to.getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void chat(AsyncPlayerChatEvent event) {
        Player p = event.getPlayer();
        UUID id = p.getUniqueId();
        if (plugin.muted(p)) {
            deny(event, "muted");
            return;
        }
        if (channel.contains(id)) {
            String message = event.getMessage();
            event.setCancelled(true);
            Bukkit.getScheduler()
                    .runTask(
                            plugin,
                            () -> {
                                if (p.isOnline() && plugin.allowed(p, "staffchat"))
                                    staff(p.getName(), message);
                                else channel.remove(id);
                            });
            return;
        }
        if (p.hasPermission("staffgen.chat.bypass")) return;
        long now = System.currentTimeMillis();
        if (locked || now - lastChat.getOrDefault(id, 0L) < interval)
            deny(event, "chat-restricted");
        else lastChat.put(id, now);
    }

    private void deny(AsyncPlayerChatEvent event, String key) {
        event.setCancelled(true);
        Bukkit.getScheduler().runTask(plugin, () -> plugin.tell(event.getPlayer(), plugin.tr(key)));
    }

    @EventHandler
    public void quit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        channel.remove(id);
        lastChat.remove(id);
        ignored.remove(id);
        lastContact.remove(id);
    }
}
