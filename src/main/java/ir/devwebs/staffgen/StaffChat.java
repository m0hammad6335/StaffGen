package ir.devwebs.staffgen;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Session chat controls. Message bodies are never written to the case ledger. */
final class StaffChat implements Listener {
    static final List<String> COMMANDS = List.of("msg","reply","ignore","chatlock","slowchat","broadcast","clearchat");
    private final Map<UUID,UUID> lastContact = new HashMap<>();
    private final Map<UUID,Set<UUID>> ignored = new HashMap<>();
    private final Map<UUID,Long> lastChat = new ConcurrentHashMap<>();
    private volatile boolean locked;
    private volatile long interval;
    private final StaffGen plugin;
    StaffChat(StaffGen plugin) { this.plugin = plugin; }

    void execute(CommandSender s,String action,String[] args) {
        switch(action) {
            case "chatlock" -> { locked = !locked; s.sendMessage("Chat lock: "+locked); }
            case "slowchat" -> {
                int seconds = Integer.parseInt(argument(args,1,"<seconds>"));
                if (seconds<0 || seconds>3600) throw new IllegalArgumentException("Use 0 to 3600 seconds.");
                interval = seconds*1000L;
                s.sendMessage("Chat interval: "+seconds+"s");
            }
            case "broadcast" -> Bukkit.broadcastMessage(plugin.title()+body(args,1));
            case "clearchat" -> Bukkit.broadcastMessage("\n".repeat(100)+plugin.title()+"Chat cleared.");
            default -> {
                if (!(s instanceof Player p)) throw new IllegalArgumentException("Player required.");
                UUID contact = lastContact.get(p.getUniqueId());
                Player target = action.equals("reply") ? (contact==null ? null : Bukkit.getPlayer(contact))
                    : Bukkit.getPlayerExact(argument(args,1,"<player>"));
                if (target==null || !p.canSee(target)) throw new IllegalArgumentException("Player unavailable.");
                if (action.equals("ignore")) {
                    Set<UUID> list = ignored.computeIfAbsent(p.getUniqueId(), id -> new HashSet<>());
                    if (list.add(target.getUniqueId())) s.sendMessage("Player ignored.");
                    else { list.remove(target.getUniqueId()); s.sendMessage("Player unignored."); }
                    return;
                }
                if (ignored.getOrDefault(target.getUniqueId(),Set.of()).contains(p.getUniqueId()))
                    throw new IllegalArgumentException("Player unavailable.");
                if (plugin.muted(p)) throw new IllegalArgumentException("You are muted.");
                String message = "[Private] "+p.getName()+" → "+target.getName()+": "+body(args,action.equals("reply")?1:2);
                p.sendMessage(message);
                target.sendMessage(message);
                lastContact.put(target.getUniqueId(),p.getUniqueId());
                lastContact.put(p.getUniqueId(),target.getUniqueId());
            }
        }
    }

    @EventHandler(ignoreCancelled=true) public void chat(AsyncPlayerChatEvent e) {
        if (e.getPlayer().hasPermission("staffgen.chat.bypass")) return;
        long now = System.currentTimeMillis();
        UUID id = e.getPlayer().getUniqueId();
        if (locked || now-lastChat.getOrDefault(id,0L)<interval) {
            e.setCancelled(true);
            Bukkit.getScheduler().runTask(plugin,() -> e.getPlayer().sendMessage("Chat is currently restricted."));
        } else lastChat.put(id,now);
    }

    private static String argument(String[] args,int pos,String expected) {
        if (args.length<=pos) throw new IllegalArgumentException("Expected "+expected);
        return args[pos];
    }
    private static String body(String[] args,int pos) {
        argument(args,pos,"<message>");
        return String.join(" ", Arrays.copyOfRange(args,pos,args.length));
    }
}
