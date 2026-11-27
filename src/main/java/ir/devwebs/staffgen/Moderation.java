package ir.devwebs.staffgen;

import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.function.Predicate;

/**
 * Case operations share the same target protection and revocation rules across GUI and commands.
 */
final class Moderation {
    private static final Set<String> REQUESTS = Set.of("report", "helpop", "appeal");
    private static final Set<String> SANCTIONS = Set.of("ban", "mute", "warn", "kick", "freeze");
    private static final Set<String> STAFF_RECORDS =
            Set.of("staffwarn", "star", "rank", "duty", "offduty", "unstar", "unstaffwarn");
    private static final Map<String, String> REVERSALS =
            Map.of(
                    "ban",
                    "unban",
                    "mute",
                    "unmute",
                    "freeze",
                    "unfreeze",
                    "warn",
                    "unwarn",
                    "staffwarn",
                    "unstaffwarn",
                    "star",
                    "unstar",
                    "note",
                    "delnote");
    private final StaffGen plugin;
    private final CaseBook book;
    private final Map<UUID, Long> lastRequest = new HashMap<>();

    Moderation(StaffGen plugin, CaseBook book) {
        this.plugin = plugin;
        this.book = book;
    }

    void execute(CommandInput in) {
        switch (in.action()) {
            case "bans", "mutes" -> {
                String kind = in.action().substring(0, in.action().length() - 1);
                list(in, entry -> entry.kind().equals(kind) && book.open(entry), 1);
            }
            case "requests" ->
                    list(in, entry -> REQUESTS.contains(entry.kind()) && book.open(entry), 1);
            case "unwarn", "undo", "delnote", "resolve", "unstar", "unstaffwarn" ->
                    close(in, book.find(in.id(1)), in.text(2));
            case "appeal", "helpop", "report" -> request(in);
            default -> target(in, plugin.target(in.sender(), in.word(1)));
        }
    }

    private void target(CommandInput in, OfflinePlayer target) {
        UUID id = target.getUniqueId();
        String action = in.action();
        if (SANCTIONS.contains(action)) {
            punish(in, target);
            return;
        }
        switch (action) {
            case "unban", "unmute", "unfreeze" ->
                    close(in, book.active(id, action.substring(2)).orElse(null), in.text(2));
            case "record" ->
                    list(in, e -> e.player().equals(id) && STAFF_RECORDS.contains(e.kind()), 2);
            case "history" ->
                    list(
                            in,
                            e ->
                                    e.player().equals(id)
                                            && (SANCTIONS.contains(e.kind())
                                                    || Set.of(
                                                                    "unban",
                                                                    "unmute",
                                                                    "unwarn",
                                                                    "unfreeze")
                                                            .contains(e.kind())),
                            2);
            case "notes" ->
                    list(
                            in,
                            e -> e.player().equals(id) && e.kind().equals("note") && book.open(e),
                            2);
            case "note", "star", "staffwarn" -> {
                plugin.protect(in.sender(), target);
                plugin.save(in.sender(), action, id, in.text(2), 0, null, null);
            }
            case "setrank", "promote", "demote" -> setRank(in, target);
            case "punish" -> {
                plugin.protect(in.sender(), target);
                StaffMenu.punish(in.player(), plugin, target);
            }
            default -> throw new IllegalArgumentException("unknown-command");
        }
    }

    private void request(CommandInput in) {
        Player reporter = in.player();
        boolean report = in.action().equals("report");
        UUID target =
                report
                        ? plugin.target(in.sender(), in.word(1)).getUniqueId()
                        : reporter.getUniqueId();
        String reason = in.text(report ? 2 : 1);
        long now = System.currentTimeMillis();
        long interval = plugin.config().getLong("request-cooldown-seconds") * 1000L;
        if (now - lastRequest.getOrDefault(reporter.getUniqueId(), 0L) < interval)
            throw new IllegalArgumentException("request-cooldown");
        // Reserve a cooldown before scheduling I/O to handle repeated clicks in one tick.
        lastRequest.put(reporter.getUniqueId(), now);
        plugin.save(
                reporter,
                in.action(),
                target,
                reason,
                0,
                null,
                () -> {
                    for (Player staff : Bukkit.getOnlinePlayers())
                        if (plugin.allowed(staff, "requests"))
                            plugin.tell(
                                    staff,
                                    plugin.tr(
                                            "new-request",
                                            "type",
                                            in.action(),
                                            "player",
                                            reporter.getName()));
                });
    }

    private void punish(CommandInput in, OfflinePlayer target) {
        plugin.protect(in.sender(), target);
        String action = in.action();
        if (action.equals("kick") && !target.isOnline())
            throw new IllegalArgumentException("unknown-online");
        boolean timed = action.equals("mute") || action.equals("ban");
        int offset = timed ? 3 : 2;
        String mode = plugin.config().getString("punishments.default-mode", "silent");
        if (in.words().length > offset && Set.of("silent", "public").contains(in.words()[offset]))
            mode = in.words()[offset++];
        long duration = 0;
        String reason;
        if (timed && in.word(2).startsWith("@")) {
            String key = in.word(2).substring(1);
            if (!key.matches("[a-zA-Z0-9_-]+"))
                throw new IllegalArgumentException("unknown-preset");
            var preset = plugin.config().getConfigurationSection("presets." + action + "." + key);
            if (preset == null) throw new IllegalArgumentException("unknown-preset");
            if (in.words().length > offset) throw new IllegalArgumentException("usage");
            duration = CommandInput.duration(preset.getString("duration", "perm"));
            reason = preset.getString("reason", "Moderation");
            List<String> steps = preset.getStringList("repeat-durations");
            if (!steps.isEmpty()) {
                long past =
                        book.count(
                                e ->
                                        e.kind().equals(action)
                                                && e.player().equals(target.getUniqueId())
                                                && e.reason().equals(reason)
                                                && book.closure(e.id()) == null);
                if (past > 0)
                    duration =
                            CommandInput.duration(
                                    steps.get((int) Math.min(past - 1, steps.size() - 1)));
            }
        } else {
            if (timed) duration = CommandInput.duration(in.word(2));
            reason = in.text(offset);
        }
        long expires;
        try {
            expires = duration == 0 ? 0 : Math.addExact(System.currentTimeMillis(), duration);
        } catch (ArithmeticException invalid) {
            throw new IllegalArgumentException("invalid-duration");
        }
        boolean announce = "public".equals(mode);
        plugin.save(
                in.sender(),
                action,
                target.getUniqueId(),
                reason,
                expires,
                null,
                () -> {
                    String message =
                            plugin.tr(
                                    "sanction",
                                    "type",
                                    action,
                                    "player",
                                    Objects.toString(
                                            target.getName(), target.getUniqueId().toString()),
                                    "reason",
                                    reason,
                                    "actor",
                                    in.sender().getName());
                    for (Player p : Bukkit.getOnlinePlayers())
                        if (announce
                                || plugin.allowed(p, "history")
                                || p.getUniqueId().equals(target.getUniqueId()))
                            plugin.tell(p, message);
                    Player online = target.getPlayer();
                    if (online != null
                            && (action.equals("kick")
                                    || action.equals("ban")
                                            && book.active(target.getUniqueId(), "ban")
                                                    .isPresent())) online.kickPlayer(reason);
                });
    }

    private void close(CommandInput in, CaseBook.Entry original, String reason) {
        if (original == null || !book.open(original)) throw new IllegalArgumentException("no-case");
        String closing =
                REQUESTS.contains(original.kind()) ? "resolve" : REVERSALS.get(original.kind());
        if (closing == null
                || !(in.action().equals(closing)
                        || in.action().equals("undo") && !closing.equals("resolve")))
            throw new IllegalArgumentException("no-case");
        if (!plugin.allowed(in.sender(), closing)) throw new IllegalArgumentException("denied");
        if (!REQUESTS.contains(original.kind()))
            plugin.protect(in.sender(), Bukkit.getOfflinePlayer(original.player()));
        plugin.save(
                in.sender(),
                closing,
                original.player(),
                reason,
                0,
                original.id(),
                () -> {
                    if (closing.equals("resolve")) {
                        try {
                            Player reporter = Bukkit.getPlayer(UUID.fromString(original.actor()));
                            if (reporter != null)
                                plugin.tell(
                                        reporter,
                                        plugin.tr(
                                                "resolved",
                                                "id",
                                                original.id().toString(),
                                                "reason",
                                                reason));
                        } catch (IllegalArgumentException legacyName) {
                            // Imported cases may predate UUID attribution.
                        }
                    }
                });
    }

    private void list(CommandInput in, Predicate<CaseBook.Entry> filter, int index) {
        List<CaseBook.Entry> rows =
                book.page(filter, in.page(index), plugin.config().getInt("page-size", 10));
        if (rows.isEmpty()) plugin.tell(in.sender(), plugin.tr("empty"));
        for (var e : rows)
            plugin.tell(
                    in.sender(),
                    plugin.tr(
                            "case",
                            "id",
                            e.id().toString(),
                            "type",
                            e.kind(),
                            "player",
                            name(e.player()),
                            "actor",
                            actor(e.actor()),
                            "reason",
                            e.reason(),
                            "status",
                            book.open(e) ? plugin.tr("open") : plugin.tr("closed")));
    }

    private String actor(String actor) {
        try {
            return name(UUID.fromString(actor));
        } catch (IllegalArgumentException consoleOrOldName) {
            return actor;
        }
    }

    private String name(UUID id) {
        return Objects.toString(Bukkit.getOfflinePlayer(id).getName(), id.toString());
    }

    private void setRank(CommandInput in, OfflinePlayer target) {
        plugin.protect(in.sender(), target);
        List<String> ladder = new ArrayList<>(plugin.roles().getKeys(false));
        ladder.sort(Comparator.comparingInt(role -> plugin.roles().getInt(role + ".level")));
        String old = plugin.role(target.getUniqueId());
        String next;
        if (in.action().equals("setrank")) next = in.word(2).toLowerCase(Locale.ROOT);
        else {
            int index = ladder.indexOf(old) + (in.action().equals("promote") ? 1 : -1);
            if (index < -1 || index >= ladder.size())
                throw new IllegalArgumentException("rank-limit");
            next = index == -1 ? "none" : ladder.get(index);
        }
        if (!next.equals("none") && !ladder.contains(next))
            throw new IllegalArgumentException("invalid-role");
        if (!(in.sender() instanceof ConsoleCommandSender)) {
            // Internal promotions cannot give the actor's own level or grant capabilities the actor
            // lacks.
            if (target.getUniqueId().equals(in.player().getUniqueId())
                    || target.getPlayer() == null
                    || plugin.rank(target.getPlayer()) >= plugin.rank(in.sender())
                    || plugin.roles().getInt(next + ".level") >= plugin.rank(in.sender())
                    || plugin.roles().getStringList(next + ".actions").stream()
                            .anyMatch(node -> !plugin.allowed(in.sender(), node)))
                throw new IllegalArgumentException("protected");
        }
        plugin.save(in.sender(), "rank", target.getUniqueId(), next, 0, null, null);
    }
}
