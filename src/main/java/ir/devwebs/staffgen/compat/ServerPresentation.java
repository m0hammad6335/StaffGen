package ir.devwebs.staffgen.compat;

import net.md_5.bungee.api.ChatColor;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Bukkit presentation operations used by the modern distribution. Keeping version-sensitive display
 * and visibility calls together makes it possible to substitute a legacy implementation without
 * changing moderation rules.
 */
public final class ServerPresentation {
    private ServerPresentation() {}

    public static String color(String hex) {
        if (hex == null || !hex.matches("#[0-9a-fA-F]{6}")) {
            throw new IllegalArgumentException("Expected a color in #RRGGBB format.");
        }
        return ChatColor.of(hex).toString();
    }

    /** Invalid or non-item material names are omitted by the menu builder. */
    public static Material icon(String materialName) {
        if (materialName == null || materialName.isBlank()) {
            return null;
        }
        Material material = Material.matchMaterial(materialName);
        return material != null && material.isItem() && !material.isAir() ? material : null;
    }

    public static void hide(Plugin owner, Player viewer, Player target) {
        viewer.hidePlayer(owner, target);
    }

    public static void show(Plugin owner, Player viewer, Player target) {
        // Remove only this plugin's visibility restriction.
        viewer.showPlayer(owner, target);
    }
}
