package ir.devwebs.staffgen;

import org.bukkit.*;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import java.util.*;

/** Read-only inventory views. The holder carries the action map for this session. */
final class StaffMenu implements InventoryHolder {
    private final Inventory view;
    private final Map<Integer,String> actions = new HashMap<>();
    private StaffMenu(int size,String title) { view = Bukkit.createInventory(this,size,title); }
    public Inventory getInventory() { return view; }

    static void inspect(Player viewer, Inventory source,String title) {
        StaffMenu menu = new StaffMenu(54,title);
        ItemStack[] contents = source.getContents();
        for (int i=0; i<Math.min(contents.length,54); i++)
            if (contents[i]!=null) menu.view.setItem(i,contents[i].clone());
        viewer.openInventory(menu.view);
    }

    static void open(Player viewer,StaffGen plugin) {
        StaffMenu menu = new StaffMenu(27,plugin.title());
        for (var item : plugin.menuItems().getKeys(false)) {
            var config = plugin.menuItems().getConfigurationSection(item);
            if (config == null || !plugin.allowed(viewer,item)) continue;
            int slot = config.getInt("slot",-1);
            Material material = Material.matchMaterial(config.getString("material","PAPER"));
            if (slot < 0 || slot >= 27 || material == null || !material.isItem()) continue;
            ItemStack button = new ItemStack(material);
            var meta = button.getItemMeta();
            if (meta==null) continue;
            meta.setDisplayName(config.getString("label",item));
            button.setItemMeta(meta);
            menu.view.setItem(slot,button);
            menu.actions.put(slot,item);
        }
        viewer.openInventory(menu.view);
    }

    static final class Events implements Listener {
        @EventHandler public void click(InventoryClickEvent e) {
            if (!(e.getView().getTopInventory().getHolder() instanceof StaffMenu menu)) return;
            e.setCancelled(true);
            String action = menu.actions.get(e.getRawSlot());
            if (action != null && e.getWhoClicked() instanceof Player p && StaffGen.getPlugin(StaffGen.class).allowed(p,action))
                Bukkit.getScheduler().runTask(StaffGen.getPlugin(StaffGen.class), () -> {
                    if (p.getOpenInventory().getTopInventory().getHolder() == menu && StaffGen.getPlugin(StaffGen.class).allowed(p,action)) {
                        p.closeInventory();
                        p.performCommand("staffgen:sg "+action);
                    }
                });
        }
        @EventHandler public void drag(InventoryDragEvent e) {
            if (e.getView().getTopInventory().getHolder() instanceof StaffMenu) e.setCancelled(true);
        }
    }
}

