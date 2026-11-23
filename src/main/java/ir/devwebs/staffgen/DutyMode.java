package ir.devwebs.staffgen;

import java.util.UUID;
import java.util.function.Predicate;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.*;

final class DutyMode implements Listener {
    private final Predicate<UUID> onDuty;
    DutyMode(Predicate<UUID> onDuty) { this.onDuty = onDuty; }
    @EventHandler(ignoreCancelled=true) public void place(BlockPlaceEvent e) {
        if (onDuty.test(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }
    @EventHandler(ignoreCancelled=true) public void mine(BlockBreakEvent e) {
        if (onDuty.test(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }
    @EventHandler(ignoreCancelled=true) public void drop(PlayerDropItemEvent e) {
        if (onDuty.test(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }
    @EventHandler(ignoreCancelled=true) public void pickup(EntityPickupItemEvent e) {
        if (e.getEntity() instanceof Player p && onDuty.test(p.getUniqueId())) e.setCancelled(true);
    }
    @EventHandler(ignoreCancelled=true) public void attack(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Player p && onDuty.test(p.getUniqueId())) e.setCancelled(true);
    }
}
