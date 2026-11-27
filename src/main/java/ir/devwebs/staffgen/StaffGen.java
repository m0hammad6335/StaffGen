package ir.devwebs.staffgen;

import ir.devwebs.staffgen.activity.ActivityMonitor;
import ir.devwebs.staffgen.compat.ServerPresentation;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Stream;

/**
 * Plugin lifecycle and command entry point. Feature classes own moderation, chat and world events.
 */
public final class StaffGen extends JavaPlugin implements Listener {
    private static final String[] HELP = {
        "help [command]",
        "menu",
        "version",
        "reload",
        "doctor",
        "ban <player> <duration|perm|@preset> [public|silent] [reason]",
        "mute <player> <duration|perm|@preset> [public|silent] [reason]",
        "warn <player> [public|silent] <reason>",
        "kick <player> [public|silent] <reason>",
        "unban <player> <reason>",
        "unmute <player> <reason>",
        "unwarn <case-id> <reason>",
        "undo <case-id> <reason>",
        "history <player> [page]",
        "bans [page]",
        "mutes [page]",
        "punish <player>",
        "note <player> <text>",
        "notes <player> [page]",
        "delnote <case-id> <reason>",
        "report <player> <reason>",
        "appeal <message>",
        "helpop <message>",
        "requests [page]",
        "resolve <case-id> <resolution>",
        "star <staff> <reason>",
        "unstar <case-id> <reason>",
        "staffwarn <staff> <reason>",
        "unstaffwarn <case-id> <reason>",
        "record <player> [page]",
        "setrank <player> <role|none>",
        "promote <player>",
        "demote <player>",
        "profile [player]",
        "stafflist",
        "stats [player]",
        "top",
        "announce <message>",
        "pin [message|clear]",
        "duty",
        "vanish",
        "tp <player>",
        "tphere <player>",
        "rtp",
        "freeze <player> <reason>",
        "unfreeze <player> <reason>",
        "invsee <player>",
        "endersee <player>",
        "inspect <player>",
        "staffchat [message]",
        "msg <player> <message>",
        "reply <message>",
        "ignore <player>",
        "group <operation> [arguments]",
        "chatlock",
        "slowchat <seconds>",
        "clearchat",
        "broadcast <message>",
        "logs [YYYY-MM-DD]",
        "spy",
        "cmdspy",
        "alts <player>",
        "cps <player>",
        "reach <player>"
    };
    private final Map<String, String> usage = new LinkedHashMap<>();
    private static final java.util.regex.Pattern PLACEHOLDERS =
            java.util.regex.Pattern.compile("\\{([a-zA-Z0-9_-]+)\\}");
    private final Map<UUID, String> lastNames = new HashMap<>();
    private final Map<String, UUID> players = new HashMap<>();
    private volatile Settings settings;
    private CaseBook book;
    private StaffChat chat;
    private Moderation moderation;
    private StaffTools tools;
    private TeamExtras team;
    private ActivityMonitor activity;
    private OfflineInventories offline;
    private Runnable removeExpansion = () -> {};

    @Override
    public void onEnable() {
        try {
            saveDefaultConfig();
            install("messages.yml");
            install("messages-team.yml");
            settings = readSettings();
            book = CaseBook.open(getDataFolder().toPath(), settings.config());
            for (String command : HELP) usage.put(command.split(" ")[0], "/sg " + command);
            for (OfflinePlayer player : Bukkit.getOfflinePlayers()) {
                if (player.getName() != null) remember(player);
            }
            activity = new ActivityMonitor(this, this::allowed);
            offline = new OfflineInventories(this);
            if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI"))
                removeExpansion = StaffPlaceholders.install(this, book);
            chat = new StaffChat(this);
            moderation = new Moderation(this, book);
            tools = new StaffTools(this, book);
            team = new TeamExtras(this, book);
            for (Listener listener :
                    List.of(this, activity, offline, chat, tools, team, new StaffMenu.Events()))
                Bukkit.getPluginManager().registerEvents(listener, this);
        } catch (Exception failure) {
            getLogger()
                    .severe(
                            "StaffGen could not start ("
                                    + failure.getClass().getSimpleName()
                                    + "). Check config.yml, messages.yml and the selected storage"
                                    + " provider.");
            Bukkit.getPluginManager().disablePlugin(this);
        }
    }

    private void install(String name) throws IOException {
        try (InputStream bundled = getResource(name)) {
            if (bundled != null && !new File(getDataFolder(), name).exists())
                saveResource(name, false);
        }
    }

    @Override
    public void onDisable() {
        try {
            removeExpansion.run();
            if (offline != null) offline.close();
            if (tools != null) tools.close();
            if (team != null) team.close();
        } finally {
            try {
                if (activity != null) activity.close();
            } finally {
                if (book != null) book.close();
            }
        }
    }

    private record Settings(YamlConfiguration config, Map<String, String> messages, String title) {}

    private Settings readSettings() throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.load(new File(getDataFolder(), "config.yml"));
        try (var in =
                new InputStreamReader(
                        Objects.requireNonNull(getResource("config.yml")),
                        StandardCharsets.UTF_8)) {
            config.setDefaults(YamlConfiguration.loadConfiguration(in));
        }
        StorageSettings.validate(config);
        if (settings != null && !StorageSettings.same(settings.config(), config))
            throw new IllegalArgumentException("restart-storage");
        ConfigurationSection roles = config.getConfigurationSection("roles");
        if (roles == null
                || !config.isConfigurationSection("menu")
                || !config.isInt("page-size")
                || config.getInt("page-size") < 1
                || config.getInt("page-size") > 100
                || !config.isInt("request-cooldown-seconds")
                || config.getInt("request-cooldown-seconds") < 0
                || !Set.of("silent", "public")
                        .contains(config.getString("punishments.default-mode", "silent")))
            throw new IllegalArgumentException("invalid-config");
        Set<Integer> levels = new HashSet<>();
        for (String role : roles.getKeys(false)) {
            int level = roles.getInt(role + ".level", 0);
            if (role.equals("none")
                    || !role.matches("[a-z0-9_-]+")
                    || level < 1
                    || !levels.add(level)
                    || !roles.isList(role + ".actions"))
                throw new IllegalArgumentException("invalid-role");
        }
        for (String kind : List.of("ban", "mute")) {
            var presets = config.getConfigurationSection("presets." + kind);
            if (presets != null)
                for (String name : presets.getKeys(false)) {
                    CommandInput.duration(presets.getString(name + ".duration", "perm"));
                    for (String step : presets.getStringList(name + ".repeat-durations"))
                        CommandInput.duration(step);
                    String reason = presets.getString(name + ".reason", "");
                    if (!name.matches("[a-zA-Z0-9_-]+")
                            || reason.isBlank()
                            || reason.length() > 2048)
                        throw new IllegalArgumentException("invalid-preset");
                }
        }
        Map<String, String> messages = new HashMap<>();
        for (String name : List.of("messages.yml", "messages-team.yml")) {
            File file = new File(getDataFolder(), name);
            if (!file.isFile()) continue;
            YamlConfiguration translations = new YamlConfiguration();
            translations.load(file);
            for (String key : translations.getKeys(true))
                if (translations.isString(key)) messages.put(key, translations.getString(key));
        }
        // Older deployments customized these basic strings in config.yml.
        ConfigurationSection overrides = config.getConfigurationSection("messages");
        if (overrides != null)
            for (String key : overrides.getKeys(false))
                if (overrides.isString(key)) messages.put(key, overrides.getString(key));
        String title =
                ServerPresentation.color(config.getString("theme.staff"))
                        + "Staff"
                        + ServerPresentation.color(config.getString("theme.gen"))
                        + "Gen "
                        + ServerPresentation.color(config.getString("theme.accent"))
                        + "» §r";
        return new Settings(config, Map.copyOf(messages), title);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String action = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        CommandInput input = new CommandInput(sender, action, args);
        try {
            if (!usage.containsKey(action)) throw new IllegalArgumentException("unknown-command");
            if (!allowed(sender, action)) throw new IllegalArgumentException("denied");
            if (ActivityMonitor.COMMANDS.contains(action)) activity.execute(sender, action, args);
            else if (StaffChat.COMMANDS.contains(action)) chat.execute(input);
            else if (StaffTools.COMMANDS.contains(action)) tools.execute(input);
            else if (Set.of("group", "profile", "stats", "top", "announce", "pin").contains(action))
                team.execute(sender, action, args);
            else
                switch (action) {
                    case "help" -> {
                        if (args.length <= 1)
                            tell(
                                    sender,
                                    "/sg "
                                            + String.join(
                                                    " | ",
                                                    usage.keySet().stream()
                                                            .filter(key -> allowed(sender, key))
                                                            .toList()));
                        else {
                            String sub = input.word(1).toLowerCase(Locale.ROOT);
                            if (!usage.containsKey(sub) || !allowed(sender, sub))
                                throw new IllegalArgumentException("unknown-command");
                            tell(sender, usage.get(sub));
                        }
                    }
                    case "menu" -> StaffMenu.open(input.player(), this);
                    case "version", "doctor" ->
                            tell(
                                    sender,
                                    tr(
                                            "status",
                                            "version",
                                            getDescription().getVersion(),
                                            "storage",
                                            config().getString("storage"),
                                            "platform",
                                            Bukkit.getBukkitVersion()));
                    case "reload" -> {
                        try {
                            Settings replacement = readSettings();
                            offline.reload();
                            activity.reload();
                            settings = replacement;
                            tell(sender, tr("reloaded"));
                        } catch (Exception ex) {
                            tell(sender, tr("invalid-config"));
                        }
                    }
                    default -> moderation.execute(input);
                }
        } catch (IllegalArgumentException ex) {
            if ("usage".equals(ex.getMessage())) tell(sender, usage.get(action));
            else tell(sender, tr(Objects.requireNonNullElse(ex.getMessage(), "invalid-input")));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(
            CommandSender s, Command command, String label, String[] args) {
        if (args.length == 0) return List.of();
        String action = args[0].toLowerCase(Locale.ROOT);
        Stream<String> values = Stream.empty();
        if (args.length == 1 || args.length == 2 && action.equals("help"))
            values = usage.keySet().stream().filter(key -> allowed(s, key));
        else if (allowed(s, action)) {
            if (args.length == 2
                    && usage.getOrDefault(action, "")
                            .matches("/sg [^ ]+ (?:<|\\[)(?:staff|player)(?:>|\\]).*"))
                values =
                        Bukkit.getOnlinePlayers().stream()
                                .filter(p -> !(s instanceof Player viewer) || viewer.canSee(p))
                                .map(Player::getName);
            else if (args.length == 3 && action.equals("setrank"))
                values = Stream.concat(Stream.of("none"), roles().getKeys(false).stream());
            else if (args.length == 3 && Set.of("ban", "mute").contains(action)) {
                var presets = config().getConfigurationSection("presets." + action);
                values =
                        Stream.concat(
                                Stream.of("1h", "1d", "perm"),
                                presets == null
                                        ? Stream.empty()
                                        : presets.getKeys(false).stream().map(id -> "@" + id));
            }
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        return values.filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix))
                .sorted()
                .limit(100)
                .toList();
    }

    String tr(String key, String... pairs) {
        String template = settings.messages().getOrDefault(key, key);
        // Expand the template once so user-provided reasons cannot introduce placeholders.

        Map<String, String> values = new HashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) values.put(pairs[i], pairs[i + 1]);
        return PLACEHOLDERS
                .matcher(template)
                .replaceAll(
                        match ->
                                java.util.regex.Matcher.quoteReplacement(
                                        values.getOrDefault(match.group(1), match.group())));
    }

    void tell(CommandSender s, String text) {
        s.sendMessage(title() + text);
    }

    String title() {
        return settings.title();
    }

    YamlConfiguration config() {
        return settings.config();
    }

    ConfigurationSection roles() {
        return config().getConfigurationSection("roles");
    }

    ConfigurationSection menuItems() {
        return config().getConfigurationSection("menu");
    }

    void inspectOffline(Player viewer, OfflinePlayer target, boolean ender) {
        offline.inspect(viewer, target, ender);
    }

    boolean muted(Player p) {
        return book.active(p.getUniqueId(), "mute").isPresent();
    }

    void audit(CommandSender s, String action, UUID target) {
        activity.audit(s, action, target);
    }

    void privateMessage(Player from, Player to, String text) {
        activity.privateMessage(from, to, text);
    }

    String role(UUID id) {
        return book.latest(id, "rank").map(CaseBook.Entry::reason).orElse("none");
    }

    private boolean memberOf(CommandSender s, String role, String assigned) {
        String node = "staffgen.role." + role;
        return role.equals(assigned) || s.isPermissionSet(node) && s.hasPermission(node);
    }

    boolean allowed(CommandSender s, String action) {
        if (config().getStringList("disabled-actions").contains(action)) return false;
        if (s instanceof ConsoleCommandSender) return true;
        String node = "staffgen." + action;
        if (s.isPermissionSet(node)) return s.hasPermission(node);
        String assigned = s instanceof Player p ? role(p.getUniqueId()) : "none";
        for (String role : roles().getKeys(false)) {
            if (memberOf(s, role, assigned)
                    && roles().getStringList(role + ".actions").contains(action)) return true;
        }
        // Unknown permissions default to OP on some servers; only declared defaults are relevant.
        return Bukkit.getPluginManager().getPermission(node) != null && s.hasPermission(node);
    }

    int rank(CommandSender s) {
        if (s instanceof ConsoleCommandSender) return Integer.MAX_VALUE;
        String assigned = s instanceof Player p ? role(p.getUniqueId()) : "none";
        return roles().getKeys(false).stream()
                .filter(r -> memberOf(s, r, assigned))
                .mapToInt(r -> roles().getInt(r + ".level"))
                .max()
                .orElse(0);
    }

    OfflinePlayer target(CommandSender s, String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null && (!(s instanceof Player viewer) || viewer.canSee(online)))
            return online;
        UUID id = players.get(name.toLowerCase(Locale.ROOT));
        if (id == null || online != null) throw new IllegalArgumentException("unknown-player");
        return Bukkit.getOfflinePlayer(id);
    }

    /**
     * Offline integrations cannot guarantee a current external rank. Console can review offline
     * sanctions.
     */
    void protect(CommandSender sender, OfflinePlayer target) {
        if (sender instanceof ConsoleCommandSender) return;
        Player player = target.getPlayer();
        if (player == null) throw new IllegalArgumentException("offline-protected");
        if (player.hasPermission("staffgen.protected") || rank(player) > rank(sender))
            throw new IllegalArgumentException("protected");
    }

    void save(
            CommandSender s,
            String kind,
            UUID target,
            String reason,
            long expires,
            UUID parent,
            Runnable success) {
        var e =
                new CaseBook.Entry(
                        UUID.randomUUID(),
                        target,
                        s instanceof Player p ? p.getUniqueId().toString() : s.getName(),
                        kind,
                        reason,
                        System.currentTimeMillis(),
                        expires,
                        parent);
        book.append(e)
                .whenComplete(
                        (unused, failure) -> {
                            if (!isEnabled()) return;
                            Bukkit.getScheduler()
                                    .runTask(
                                            this,
                                            () -> {
                                                if (failure != null) {
                                                    tell(s, tr("error"));
                                                    getLogger()
                                                            .warning(
                                                                    "Could not record "
                                                                            + kind
                                                                            + " case "
                                                                            + e.id());
                                                    return;
                                                }
                                                tell(s, tr("saved", "id", e.id().toString()));
                                                if (success != null) success.run();
                                            });
                        });
    }

    /** Remove an obsolete name without scanning the entire player directory on every login. */
    private void remember(OfflinePlayer player) {
        String name = player.getName();
        if (name == null) return;
        String normalized = name.toLowerCase(Locale.ROOT);
        UUID id = player.getUniqueId();
        String previous = lastNames.put(id, normalized);
        if (previous != null) players.remove(previous, id);
        players.put(normalized, id);
    }

    @EventHandler
    public void join(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        remember(p);
    }

    @EventHandler
    public void login(AsyncPlayerPreLoginEvent event) {
        book.active(event.getUniqueId(), "ban")
                .ifPresent(
                        entry ->
                                event.disallow(
                                        AsyncPlayerPreLoginEvent.Result.KICK_BANNED,
                                        entry.reason()));
    }
}

