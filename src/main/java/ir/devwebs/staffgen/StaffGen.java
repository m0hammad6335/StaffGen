package ir.devwebs.staffgen;

import net.md_5.bungee.api.ChatColor;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;
import java.io.File;
import java.util.*;
import java.util.regex.*;
import java.util.stream.Stream;

public final class StaffGen extends JavaPlugin implements Listener {
    private static final List<String> COMMANDS = List.of("msg", "reply", "ignore", "chatlock", "slowchat", "broadcast", "clearchat", "menu", "endersee", "staffwarn", "star", "record", "duty", "ban", "mute", "warn", "kick", "unban", "unmute", "unwarn", "history", "note", "report", "appeal", "helpop", "requests", "resolve", "staffchat", "freeze", "unfreeze", "vanish", "tp", "tphere", "invsee", "stafflist", "reload");
    private static final Set<String> REQUESTS = Set.of("report","appeal","helpop");
    private static final Pattern TIME = Pattern.compile("(\\d+)([smhdw])");
    private final Set<UUID> hidden = new HashSet<>();
    private final Map<UUID,Long> lastRequest = new HashMap<>();
    private CaseBook book;
    private StaffChat chat;
    private final Map<String,UUID> knownPlayers = new HashMap<>();
    private YamlConfiguration settings;
    private String prefix;

    public void onEnable() {
        saveDefaultConfig();
        try {
            loadSettings();
            for (OfflinePlayer p : Bukkit.getOfflinePlayers()) if (p.getName()!=null)
                knownPlayers.put(p.getName().toLowerCase(Locale.ROOT),p.getUniqueId());
            chat = new StaffChat(this);
            getServer().getPluginManager().registerEvents(chat,this);
            SqlCases sql = settings.getString("storage").equals("jdbc")
                ? new SqlCases(getDataFolder().toPath(),settings.getConfigurationSection("jdbc")) : null;
            book = new CaseBook(getDataFolder().toPath().resolve("cases"),sql);
            getServer().getPluginManager().registerEvents(this, this);
            getServer().getPluginManager().registerEvents(new StaffMenu.Events(), this);
            getServer().getPluginManager().registerEvents(new DutyMode(id -> book.active(id,"duty").isPresent()),this);
        } catch (Exception ex) {
            getLogger().severe("Startup failed (" + ex.getClass().getSimpleName() + "). Check storage and configuration.");
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    private void loadSettings() throws Exception {
        YamlConfiguration next = new YamlConfiguration();
        next.load(new File(getDataFolder(), "config.yml"));
        if (!Set.of("yaml","jdbc").contains(next.getString("storage","")) || next.getInt("request-cooldown-seconds", -1) < 0)
            throw new IllegalArgumentException("Check storage and request-cooldown-seconds");
        if (settings != null && (!Objects.equals(settings.getString("storage"), next.getString("storage")) ||
            !Objects.equals(settings.getConfigurationSection("jdbc").getValues(true).toString(), next.getConfigurationSection("jdbc").getValues(true).toString())))
            throw new IllegalArgumentException("Restart to change storage.");
        if (!next.isConfigurationSection("menu") || !next.isConfigurationSection("roles")) throw new IllegalArgumentException("Missing menu");
        String nextPrefix = ChatColor.of(next.getString("theme.staff")) + "Staff"
            + ChatColor.of(next.getString("theme.gen")) + "Gen "
            + ChatColor.of(next.getString("theme.accent")) + "» " + ChatColor.RESET;
        settings = next;
        prefix = nextPrefix;
    }

    public void onDisable() {
        if (book != null) book.close();
        for (Player viewer : Bukkit.getOnlinePlayers())
            for (UUID id : hidden) {
                Player target = Bukkit.getPlayer(id);
                if (target != null) viewer.showPlayer(this, target);
            }
        hidden.clear();
    }

    private void tell(CommandSender sender, String message) { sender.sendMessage(prefix + message); }
    private String msg(String key) { return settings.getString("messages." + key, key); }
    boolean allowed(CommandSender sender, String action) {
        String node = "staffgen."+action;
        if (sender instanceof ConsoleCommandSender) return true;
        if (sender.isPermissionSet(node)) return sender.hasPermission(node);
        for (String role : settings.getConfigurationSection("roles").getKeys(false))
            if ((sender.isPermissionSet("staffgen.role."+role) && sender.hasPermission("staffgen.role."+role)) && settings.getStringList("roles."+role+".actions").contains(action)) return true;
        return sender.hasPermission(node);
    }

    private int rank(CommandSender sender) {
        int highest = 0;
        for (String role : settings.getConfigurationSection("roles").getKeys(false))
            if ((sender.isPermissionSet("staffgen.role."+role) && sender.hasPermission("staffgen.role."+role)))
                highest = Math.max(highest,settings.getInt("roles."+role+".level"));
        return highest;
    }

    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String action = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        if (action.equals("help") || !COMMANDS.contains(action)) {
            tell(sender, "/sg " + String.join(" | ", COMMANDS.stream().filter(c -> allowed(sender,c)).toList()));
            return true;
        }
        if (!allowed(sender,action)) { tell(sender,msg("denied")); return true; }
        try { execute(sender, action, args); }
        catch (IllegalArgumentException ex) { tell(sender, ex.getMessage()); }
        return true;
    }

    boolean muted(Player p) { return book.active(p.getUniqueId(),"mute").isPresent(); }
    String title() { return prefix; }
    org.bukkit.configuration.ConfigurationSection menuItems() { return settings.getConfigurationSection("menu"); }

    private void execute(CommandSender s, String action, String[] args) {
        if (StaffChat.COMMANDS.contains(action)) { chat.execute(s,action,args); return; }
        switch (action) {
            case "menu" -> StaffMenu.open(player(s),this);
            case "duty" -> {
                UUID id = player(s).getUniqueId();
                var previous = book.active(id,"duty");
                save(s,previous.isPresent() ? "offduty" : "duty",id,"Duty status",0,previous.map(CaseBook.Entry::id).orElse(null));
            }
            case "reload" -> {
                try { loadSettings(); tell(s,"Configuration loaded."); }
                catch (Exception ex) { tell(s,"Invalid configuration: " + ex.getMessage()); }
            }
            case "stafflist" -> tell(s,String.join(", ", Bukkit.getOnlinePlayers().stream()
                .filter(p -> allowed(p,"staffchat") && (!(s instanceof Player v) || v.canSee(p))).map(Player::getName).toList()));
            case "staffchat" -> {
                String message = "[Staff] " + s.getName() + ": " + rest(args,1);
                for (Player recipient : Bukkit.getOnlinePlayers()) if (allowed(recipient,"staffchat")) tell(recipient,message);
            }
            case "requests" -> book.recent(e -> REQUESTS.contains(e.kind()) && book.open(e)).forEach(e -> display(s,e));
            case "resolve","unwarn" -> {
                CaseBook.Entry entry = book.find(UUID.fromString(arg(args,1,"<case-id>")));
                if (entry == null || !book.open(entry) || !(action.equals("resolve")
                    ? REQUESTS.contains(entry.kind()) : entry.kind().equals("warn")))
                    throw new IllegalArgumentException("No matching open case.");
                save(s,action,entry.player(),rest(args,2),0,entry.id());
            }
            case "appeal","helpop" -> {
                Player p = player(s);
                String reason = rest(args,1);
                throttle(p);
                save(s,action,p.getUniqueId(),reason,0,null);
            }
            case "vanish" -> {
                Player p = player(s);
                boolean enable = hidden.add(p.getUniqueId());
                if (!enable) hidden.remove(p.getUniqueId());
                for (Player viewer : Bukkit.getOnlinePlayers()) {
                    if (enable && !viewer.equals(p)) viewer.hidePlayer(this,p);
                    else viewer.showPlayer(this,p);
                }
                tell(s,"Vanish " + (enable ? "enabled." : "disabled."));
            }
            default -> targetAction(s,action,args);
        }
    }

    private void targetAction(CommandSender s, String action, String[] args) {
        String name = arg(args,1,"<player>");
        OfflinePlayer target = Bukkit.getPlayerExact(name);
        if (target == null) {
            UUID id = knownPlayers.get(name.toLowerCase(Locale.ROOT));
            if (id!=null) target = Bukkit.getOfflinePlayer(id);
        }
        if (target == null) throw new IllegalArgumentException("Unknown player.");
        UUID id = target.getUniqueId();
        Player online = target.getPlayer();
        if (!action.equals("report") && !action.equals("history") && online != null
            && (online.hasPermission("staffgen.protected") || rank(online)>rank(s)) && !(s instanceof ConsoleCommandSender))
            throw new IllegalArgumentException(msg("protected"));
        // Offline permission attachments cannot reliably resolve target immunity.
        if (!target.isOnline() && !(s instanceof ConsoleCommandSender) &&
            Set.of("ban","mute","warn","freeze").contains(action))
            throw new IllegalArgumentException("Use the console for offline sanctions.");
        switch (action) {
            case "record" -> book.recent(e -> e.player().equals(id) && Set.of("star","staffwarn","duty","offduty").contains(e.kind())).forEach(e -> display(s,e));
            case "history" -> book.recent(e -> e.player().equals(id) && !Set.of("star","staffwarn","duty","offduty").contains(e.kind())).forEach(e -> display(s,e));
            case "tp","tphere","invsee","endersee","kick" -> {
                if (online == null) throw new IllegalArgumentException("Player is offline.");
                switch (action) {
                    case "tp" -> player(s).teleport(online);
                    case "tphere" -> online.teleport(player(s));
                    case "invsee" -> StaffMenu.inspect(player(s),online.getInventory(),"Inventory: "+name);
                    case "endersee" -> StaffMenu.inspect(player(s),online.getEnderChest(),"Ender chest: "+name);
                    case "kick" -> save(s,action,id,rest(args,2),0,null);
                }
            }
            case "unban","unmute","unfreeze" -> {
                var parent = book.active(id, action.substring(2)).orElseThrow(() -> new IllegalArgumentException("No active case."));
                save(s,action,id,rest(args,2),0,parent.id());
            }
            case "ban","mute" -> {
                String choice = arg(args,2,"<duration|perm|@preset>");
                if (choice.startsWith("@")) {
                    var preset = settings.getConfigurationSection("presets."+action+"."+choice.substring(1));
                    if (preset==null) throw new IllegalArgumentException("Unknown preset.");
                    save(s,action,id,preset.getString("reason","Moderation"),expiry(preset.getString("duration","perm")),null);
                } else save(s,action,id,rest(args,3),expiry(choice),null);
            }
            case "staffwarn","star","note","warn","freeze","report" -> {
                String reason = rest(args,2);
                if (action.equals("report")) throttle(player(s));
                save(s,action,id,reason,0,null);
            }
        }
    }

    private void save(CommandSender s,String kind,UUID target,String reason,long expiry,UUID parent) {
        var entry = new CaseBook.Entry(UUID.randomUUID(),target,
            s instanceof Player p ? p.getUniqueId().toString() : s.getName(),kind,reason,System.currentTimeMillis(),expiry,parent);
        book.append(entry).whenComplete((result,error) -> {
            if (!isEnabled()) return;
            Bukkit.getScheduler().runTask(this,() -> {
                if (error != null) { tell(s,msg("error")); getLogger().warning("Case write failed: "+entry.id()); return; }
                tell(s,msg("saved").replace("{id}",entry.id().toString()));
                Player p = Bukkit.getPlayer(target);
                if (p != null && (kind.equals("kick") || kind.equals("ban") && book.open(entry))) p.kickPlayer(reason);
                if (REQUESTS.contains(kind)) for (Player recipient : Bukkit.getOnlinePlayers())
                    if (allowed(recipient,"requests")) tell(recipient,"New "+kind+" "+entry.id());
            });
        });
    }

    private void display(CommandSender s,CaseBook.Entry e) {
        tell(s,e.id()+" "+e.kind()+" "+e.player()+" — "+e.reason()+(book.open(e) ? "" : " [closed]"));
    }

    private long expiry(String value) {
        if (value.equalsIgnoreCase("perm")) return 0;
        Matcher m = TIME.matcher(value);
        if (!m.matches()) throw new IllegalArgumentException("Use 30s, 10m, 2h, 7d, 1w or perm.");
        long unit = switch (m.group(2)) { case "s" -> 1000; case "m" -> 60000; case "h" -> 3600000; case "d" -> 86400000; default -> 604800000; };
        try {
            long n = Long.parseLong(m.group(1));
            if (n < 1) throw new ArithmeticException();
            return Math.addExact(System.currentTimeMillis(), Math.multiplyExact(n,unit));
        } catch (ArithmeticException | NumberFormatException ex) { throw new IllegalArgumentException("Invalid duration."); }
    }

    private void throttle(Player p) {
        long now = System.currentTimeMillis();
        if (now-lastRequest.getOrDefault(p.getUniqueId(),0L) < settings.getInt("request-cooldown-seconds")*1000L)
            throw new IllegalArgumentException("Please wait before opening another request.");
        lastRequest.put(p.getUniqueId(),now);
    }

    private static Player player(CommandSender s) {
        if (s instanceof Player p) return p;
        throw new IllegalArgumentException("This action requires a player.");
    }
    private static String arg(String[] args,int pos,String hint) {
        if (pos >= args.length) throw new IllegalArgumentException("Expected "+hint);
        return args[pos];
    }
    private static String rest(String[] args,int pos) {
        arg(args,pos,"a reason or message");
        String text = String.join(" ",Arrays.copyOfRange(args,pos,args.length));
        if (text.length() > 2048) throw new IllegalArgumentException("Text exceeds 2048 characters.");
        return text;
    }

    @EventHandler public void login(AsyncPlayerPreLoginEvent e) {
        book.active(e.getUniqueId(),"ban").ifPresent(c -> e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED,c.reason()));
    }
    @EventHandler(ignoreCancelled=true) public void chat(AsyncPlayerChatEvent e) {
        if (book.active(e.getPlayer().getUniqueId(),"mute").isPresent()) {
            e.setCancelled(true);
            Bukkit.getScheduler().runTask(this,() -> tell(e.getPlayer(),msg("muted")));
        }
    }
    @EventHandler(ignoreCancelled=true) public void move(PlayerMoveEvent e) {
        var to = e.getTo();
        if (to != null && (to.getX()!=e.getFrom().getX() || to.getY()!=e.getFrom().getY() || to.getZ()!=e.getFrom().getZ())
            && book.active(e.getPlayer().getUniqueId(),"freeze").isPresent()) e.setCancelled(true);
    }
    @EventHandler public void join(PlayerJoinEvent e) {
        knownPlayers.put(e.getPlayer().getName().toLowerCase(Locale.ROOT),e.getPlayer().getUniqueId());
        for (UUID id : hidden) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) e.getPlayer().hidePlayer(this,p);
        }
    }
    @EventHandler public void quit(PlayerQuitEvent e) { hidden.remove(e.getPlayer().getUniqueId()); }
    public List<String> onTabComplete(CommandSender s,Command cmd,String label,String[] args) {
        Stream<String> options = Stream.empty();
        if (args.length==1) options = COMMANDS.stream().filter(c -> allowed(s,c));
        else if (args.length==2 && allowed(s,args[0])) options = Bukkit.getOnlinePlayers().stream()
            .filter(p -> !(s instanceof Player viewer) || viewer.canSee(p)).map(Player::getName);
        String start = args[args.length-1].toLowerCase(Locale.ROOT);
        return options.filter(x -> x.toLowerCase(Locale.ROOT).startsWith(start)).toList();
    }
}





