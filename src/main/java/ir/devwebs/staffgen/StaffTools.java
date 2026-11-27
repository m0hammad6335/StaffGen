package ir.devwebs.staffgen;

import ir.devwebs.staffgen.compat.ServerPresentation;

import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.*;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * World-facing actions are handled on the server thread; event checks only query the in-memory
 * ledger.
 */
final class StaffTools implements Listener, AutoCloseable {
    static final Set<String> COMMANDS =
            Set.of(
                    "duty",
                    "vanish",
                    "tp",
                    "tphere",
                    "rtp",
                    "inspect",
                    "invsee",
                    "endersee",
                    "stafflist");
    private final StaffGen plugin;
    private final CaseBook book;
    private final Set<UUID> hidden = new HashSet<>();

    StaffTools(StaffGen plugin, CaseBook book) {
        this.plugin = plugin;
        this.book = book;
    }

    void execute(CommandInput in) {
        if (in.action().equals("stafflist")) {
            var names =
                    Bukkit.getOnlinePlayers().stream()
                            .filter(
                                    p ->
                                            plugin.allowed(p, "staffchat")
                                                    && (!(in.sender() instanceof Player viewer)
                                                            || viewer.canSee(p)))
                            .map(Player::getName)
                            .sorted()
                            .toList();
            plugin.tell(
                    in.sender(), names.isEmpty() ? plugin.tr("empty") : String.join(", ", names));
            return;
        }
        Player viewer = in.player();
        switch (in.action()) {
            case "duty" -> {
                var prior = book.active(viewer.getUniqueId(), "duty");
                plugin.save(
                        viewer,
                        prior.isPresent() ? "offduty" : "duty",
                        viewer.getUniqueId(),
                        "Duty status",
                        0,
                        prior.map(CaseBook.Entry::id).orElse(null),
                        () ->
                                plugin.tell(
                                        viewer,
                                        plugin.tr(prior.isPresent() ? "duty-off" : "duty-on")));
            }
            case "vanish" -> {
                boolean enabled = hidden.add(viewer.getUniqueId());
                if (!enabled) hidden.remove(viewer.getUniqueId());
                for (Player observer : Bukkit.getOnlinePlayers()) visibility(observer, viewer);
                plugin.audit(viewer, in.action(), viewer.getUniqueId());
                plugin.tell(viewer, plugin.tr(enabled ? "vanish-on" : "vanish-off"));
            }
            case "rtp" -> {
                var candidates =
                        Bukkit.getOnlinePlayers().stream()
                                .filter(
                                        p ->
                                                !p.equals(viewer)
                                                        && viewer.canSee(p)
                                                        && !p.hasPermission("staffgen.protected")
                                                        && plugin.rank(p) <= plugin.rank(viewer))
                                .toList();
                if (candidates.isEmpty()) throw new IllegalArgumentException("empty");
                Player selected =
                        candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
                teleport(viewer, selected);
                plugin.audit(viewer, "rtp", selected.getUniqueId());
            }
            default -> {
                OfflinePlayer offline = plugin.target(viewer, in.word(1));
                if (!offline.isOnline() && Set.of("invsee", "endersee").contains(in.action())) {
                    plugin.inspectOffline(viewer, offline, in.action().equals("endersee"));
                    return;
                }
                plugin.protect(viewer, offline);
                Player target = offline.getPlayer();
                if (target == null) throw new IllegalArgumentException("unknown-online");
                switch (in.action()) {
                    case "tp" -> teleport(viewer, target);
                    case "tphere" -> teleport(target, viewer);
                    case "invsee" ->
                            StaffMenu.inspect(
                                    viewer,
                                    target.getInventory(),
                                    plugin.tr("inventory", "player", target.getName()));
                    case "endersee" ->
                            StaffMenu.inspect(
                                    viewer,
                                    target.getEnderChest(),
                                    plugin.tr("enderchest", "player", target.getName()));
                    case "inspect" ->
                            plugin.tell(
                                    viewer,
                                    plugin.tr(
                                            "inspect",
                                            "player",
                                            target.getName(),
                                            "world",
                                            target.getWorld().getName(),
                                            "health",
                                            String.valueOf((int) target.getHealth()),
                                            "mode",
                                            target.getGameMode().name(),
                                            "food",
                                            String.valueOf(target.getFoodLevel())));
                }
                plugin.audit(viewer, in.action(), target.getUniqueId());
            }
        }
    }

    private void teleport(Player player, Player destination) {
        if (!player.teleport(destination)) throw new IllegalArgumentException("teleport-failed");
    }

    private void visibility(Player observer, Player target) {
        if (observer.equals(target)) return;
        if (hidden.contains(target.getUniqueId()) && !plugin.allowed(observer, "vanish.see"))
            ServerPresentation.hide(plugin, observer, target);
        else ServerPresentation.show(plugin, observer, target);
    }

    private boolean frozen(Player player) {
        return book.active(player.getUniqueId(), "freeze").isPresent();
    }

    private boolean restricted(Player player) {
        return frozen(player) || book.active(player.getUniqueId(), "duty").isPresent();
    }

    @EventHandler
    public void join(PlayerJoinEvent e) {
        for (UUID id : hidden) {
            Player target = Bukkit.getPlayer(id);
            if (target != null) visibility(e.getPlayer(), target);
        }
    }

    @EventHandler
    public void quit(PlayerQuitEvent event) {
        Player leaving = event.getPlayer();
        if (hidden.remove(leaving.getUniqueId())) {
            for (Player viewer : Bukkit.getOnlinePlayers())
                ServerPresentation.show(plugin, viewer, leaving);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void move(PlayerMoveEvent e) {
        Location to = e.getTo();
        if (to != null
                && frozen(e.getPlayer())
                && (to.getX() != e.getFrom().getX()
                        || to.getY() != e.getFrom().getY()
                        || to.getZ() != e.getFrom().getZ())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void teleport(PlayerTeleportEvent e) {
        if (frozen(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void command(PlayerCommandPreprocessEvent e) {
        // Check canonical and namespaced labels. Only explicit freeze exceptions are accepted.
        String raw = e.getMessage().substring(1).trim().toLowerCase(Locale.ROOT);
        String[] words = raw.split("\\s+", 3);
        String root = words[0].replaceFirst("^[^:]+:", "");
        boolean sg = root.equals("sg") || root.equals("staffgen");
        String action = sg && words.length > 1 ? words[1] : root;
        if (frozen(e.getPlayer())
                && !(sg
                        && plugin.config()
                                .getStringList("freeze-allowed-actions")
                                .contains(action))) {
            e.setCancelled(true);
            plugin.tell(e.getPlayer(), plugin.tr("frozen"));
        } else if (plugin.muted(e.getPlayer())
                && plugin.config().getStringList("muted-blocked-commands").contains(action)) {
            e.setCancelled(true);
            plugin.tell(e.getPlayer(), plugin.tr("muted"));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void place(BlockPlaceEvent e) {
        if (restricted(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void mine(BlockBreakEvent e) {
        if (restricted(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void interact(PlayerInteractEvent e) {
        if (frozen(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void drop(PlayerDropItemEvent e) {
        if (restricted(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void pickup(EntityPickupItemEvent e) {
        if (e.getEntity() instanceof Player p && restricted(p)) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void open(InventoryOpenEvent e) {
        if (e.getPlayer() instanceof Player p && frozen(p)) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void damage(EntityDamageByEntityEvent e) {
        Entity damager = e.getDamager();
        Player attacker = damager instanceof Player p ? p : null;
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player p)
            attacker = p;
        if (attacker != null && restricted(attacker)
                || e.getEntity() instanceof Player p && frozen(p)) e.setCancelled(true);
    }

    @Override
    public void close() {
        for (UUID id : hidden) {
            Player target = Bukkit.getPlayer(id);
            if (target != null)
                for (Player viewer : Bukkit.getOnlinePlayers())
                    ServerPresentation.show(plugin, viewer, target);
        }
        hidden.clear();
    }
}
