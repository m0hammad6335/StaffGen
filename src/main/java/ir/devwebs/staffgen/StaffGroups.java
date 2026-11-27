package ir.devwebs.staffgen;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.*;

/** Invite-only session channels. All mutations run on the Bukkit server thread. */
final class StaffGroups {
    private static final class Channel {
        final String name;
        final UUID owner;
        final Set<UUID> members = new HashSet<>();
        final Set<UUID> invited = new HashSet<>();

        Channel(String name, UUID owner) {
            this.name = name;
            this.owner = owner;
            members.add(owner);
        }
    }

    private final StaffGen plugin;
    private final Map<String, Channel> channels = new HashMap<>();
    private final Map<UUID, Channel> memberships = new HashMap<>();

    StaffGroups(StaffGen plugin) {
        this.plugin = plugin;
    }

    void execute(Player player, String[] args) {
        UUID id = player.getUniqueId();
        Channel group = memberships.get(id);
        String action = args.length < 2 ? "list" : args[1].toLowerCase(Locale.ROOT);
        switch (action) {
            case "create" -> {
                if (group != null)
                    throw new IllegalArgumentException("team-leave-your-current-group-first");
                String name = argument(args).toLowerCase(Locale.ROOT);
                if (!name.matches("[a-z0-9_]{2,16}"))
                    throw new IllegalArgumentException(
                            "team-use-2-16-letters-digits-or-underscores");
                Channel created = new Channel(name, id);
                if (channels.putIfAbsent(name, created) != null)
                    throw new IllegalArgumentException("team-that-group-already-exists");
                memberships.put(id, created);
                plugin.tell(player, plugin.tr("team-created", "name", name));
            }
            case "join" -> {
                Channel invited = channels.get(argument(args).toLowerCase(Locale.ROOT));
                if (group != null || invited == null || !invited.invited.remove(id))
                    throw new IllegalArgumentException(
                            "team-join-requires-an-invitation-and-an-available-membership");
                invited.members.add(id);
                memberships.put(id, invited);
                broadcast(invited, player.getName() + " joined.");
            }
            case "invite", "kick" -> {
                requireOwner(group, id);
                Player other = Bukkit.getPlayerExact(argument(args));
                if (other == null || !player.canSee(other) || other.equals(player))
                    throw new IllegalArgumentException(
                            "team-specify-another-visible-online-player");
                UUID target = other.getUniqueId();
                if (action.equals("kick")) {
                    if (!group.members.remove(target))
                        throw new IllegalArgumentException("team-player-is-outside-this-group");
                    memberships.remove(target);
                    plugin.tell(other, plugin.tr("team-removed", "name", group.name));
                } else {
                    if (!plugin.allowed(other, "group") || memberships.containsKey(target))
                        throw new IllegalArgumentException("team-player-is-unavailable");
                    group.invited.add(target);
                    plugin.tell(other, plugin.tr("team-invite", "name", group.name));
                }
                plugin.tell(player, plugin.tr("team-updated"));
            }
            case "disband" -> {
                requireOwner(group, id);
                disband(group);
            }
            case "leave" -> {
                requireMember(group);
                leave(id);
                plugin.tell(player, plugin.tr("team-left"));
            }
            case "list" -> {
                requireMember(group);
                List<String> visible = new ArrayList<>();
                for (UUID member : group.members) {
                    Player other = Bukkit.getPlayer(member);
                    if (other != null && player.canSee(other)) visible.add(other.getName());
                }
                plugin.tell(player, group.name + ": " + String.join(", ", visible));
            }
            case "say" -> {
                requireMember(group);
                if (plugin.muted(player)) throw new IllegalArgumentException("team-you-are-muted");
                argument(args);
                broadcast(
                        group,
                        player.getName()
                                + ": "
                                + String.join(" ", Arrays.copyOfRange(args, 2, args.length)));
            }
            default ->
                    throw new IllegalArgumentException(
                            "team-sg-group-create-invite-join-kick-leave-disband-list-say");
        }
    }

    void leave(UUID id) {
        for (Channel group : channels.values()) group.invited.remove(id);
        Channel group = memberships.remove(id);
        if (group == null) return;
        if (group.owner.equals(id)) disband(group);
        else group.members.remove(id);
    }

    private void disband(Channel group) {
        broadcast(group, "Group closed.");
        group.members.forEach(memberships::remove);
        channels.remove(group.name);
    }

    private void broadcast(Channel group, String text) {
        for (UUID id : group.members) {
            Player member = Bukkit.getPlayer(id);
            if (member != null && plugin.allowed(member, "group"))
                plugin.tell(member, "[" + group.name + "] " + text);
        }
    }

    private static void requireMember(Channel group) {
        if (group == null)
            throw new IllegalArgumentException(
                    "team-use-sg-group-create-name-or-sg-group-join-name");
    }

    private static void requireOwner(Channel group, UUID id) {
        requireMember(group);
        if (!group.owner.equals(id))
            throw new IllegalArgumentException("team-only-the-group-owner-can-do-this");
    }

    private static String argument(String[] args) {
        if (args.length < 3)
            throw new IllegalArgumentException("team-missing-group-player-or-message");
        return args[2];
    }
}
