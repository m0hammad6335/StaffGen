package ir.devwebs.staffgen;

import ir.devwebs.staffgen.compat.ServerPresentation;

import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;

import java.util.*;

/** Only inventories with this holder are managed by StaffGen. Titles are freely customizable. */
final class StaffMenu implements InventoryHolder {
    private record Button(String permission, Runnable click) {}

    private final Inventory inventory;
    private final Map<Integer, Button> buttons = new HashMap<>();

    private StaffMenu(int size, String title) {
        inventory = Bukkit.createInventory(this, size, title);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    static void inspect(Player viewer, Inventory source, String title) {
        StaffMenu menu = new StaffMenu(54, title);
        ItemStack[] contents = source.getContents();
        for (int slot = 0; slot < Math.min(contents.length, 54); slot++) {
            if (contents[slot] != null) menu.inventory.setItem(slot, contents[slot].clone());
        }
        viewer.openInventory(menu.inventory);
    }

    static void open(Player player, StaffGen plugin) {
        StaffMenu menu = new StaffMenu(27, plugin.title());
        for (String name : plugin.menuItems().getKeys(false)) {
            var option = plugin.menuItems().getConfigurationSection(name);
            if (option == null || !plugin.allowed(player, name)) continue;
            int slot = option.getInt("slot", -1);
            if (slot < 0 || slot >= 27) continue;
            menu.button(
                    slot,
                    option.getString("material", "PAPER"),
                    option.getString("label", name),
                    name,
                    () -> player.performCommand("staffgen:sg " + name));
        }
        player.openInventory(menu.inventory);
    }

    static void punish(Player viewer, StaffGen plugin, OfflinePlayer target) {
        StaffMenu menu =
                new StaffMenu(
                        54, plugin.tr("punish-menu", "player", String.valueOf(target.getName())));
        int slot = 0;
        for (String kind : List.of("ban", "mute")) {
            var presets = plugin.config().getConfigurationSection("presets." + kind);
            if (presets == null || !plugin.allowed(viewer, kind)) continue;
            for (String preset : presets.getKeys(false)) {
                if (slot >= 54) break;
                String description =
                        kind
                                + " · "
                                + presets.getString(preset + ".duration")
                                + " · "
                                + presets.getString(preset + ".reason");
                String command = kind + " " + target.getName() + " @" + preset;
                menu.button(
                        slot++,
                        presets.getString(preset + ".icon", "PAPER"),
                        description,
                        kind,
                        () ->
                                confirm(
                                        viewer,
                                        plugin,
                                        kind,
                                        command,
                                        description,
                                        target.getUniqueId(),
                                        target.getName()));
            }
        }
        viewer.openInventory(menu.inventory);
    }

    private static void confirm(
            Player viewer,
            StaffGen plugin,
            String permission,
            String command,
            String description,
            UUID id,
            String targetName) {
        StaffMenu menu = new StaffMenu(9, plugin.tr("confirm"));
        menu.button(
                2,
                "PAPER",
                description,
                permission,
                () -> {
                    // Re-resolve the target, rank and preset at execution; the player may have
                    // reconnected.
                    if (!plugin.target(viewer, targetName).getUniqueId().equals(id)) return;
                    viewer.performCommand("staffgen:sg " + command);
                });
        menu.button(6, "BARRIER", plugin.tr("cancel"), permission, () -> {});
        viewer.openInventory(menu.inventory);
    }

    private void button(
            int slot, String material, String label, String permission, Runnable click) {
        Material type = ServerPresentation.icon(material);
        if (type == null || type == Material.AIR) type = Material.PAPER;
        ItemStack button = new ItemStack(type);
        var meta = button.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(label);
            button.setItemMeta(meta);
        }
        inventory.setItem(slot, button);
        buttons.put(slot, new Button(permission, click));
    }

    static final class Events implements Listener {
        @EventHandler
        public void click(InventoryClickEvent event) {
            if (!(event.getView().getTopInventory().getHolder() instanceof StaffMenu menu)) return;
            event.setCancelled(true);
            Button button = menu.buttons.get(event.getRawSlot());
            if (button == null || !(event.getWhoClicked() instanceof Player viewer)) return;
            StaffGen plugin = StaffGen.getPlugin(StaffGen.class);
            // Opening or closing an inventory inside InventoryClickEvent can corrupt the client's
            // view.
            Bukkit.getScheduler()
                    .runTask(
                            plugin,
                            () -> {
                                if (!viewer.isOnline()
                                        || viewer.getOpenInventory().getTopInventory().getHolder()
                                                != menu
                                        || !plugin.allowed(viewer, button.permission())) return;
                                viewer.closeInventory();
                                try {
                                    button.click().run();
                                } catch (IllegalArgumentException rejected) {
                                    plugin.tell(viewer, plugin.tr(rejected.getMessage()));
                                }
                            });
        }

        @EventHandler
        public void drag(InventoryDragEvent event) {
            if (event.getView().getTopInventory().getHolder() instanceof StaffMenu)
                event.setCancelled(true);
        }
    }
}
