package ir.devwebs.staffgen;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;

import org.bukkit.OfflinePlayer;

import java.util.Locale;

/**
 * Loaded only when PlaceholderAPI is present. Reads the same in-memory case index as login checks.
 */
final class StaffPlaceholders extends PlaceholderExpansion {
    private final StaffGen plugin;
    private final CaseBook book;

    private StaffPlaceholders(StaffGen plugin, CaseBook book) {
        this.plugin = plugin;
        this.book = book;
    }

    static Runnable install(StaffGen plugin, CaseBook book) {
        StaffPlaceholders expansion = new StaffPlaceholders(plugin, book);
        if (!expansion.register()) {
            plugin.getLogger().warning("Another expansion already owns the staffgen identifier.");
            return () -> {};
        }
        return expansion::unregister;
    }

    @Override
    public String getIdentifier() {
        return "staffgen";
    }

    @Override
    public String getAuthor() {
        return String.join(", ", plugin.getDescription().getAuthors());
    }

    @Override
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String parameter) {
        return switch (parameter.toLowerCase(Locale.ROOT)) {
            case "version" -> getVersion();
            case "on_duty" ->
                    Boolean.toString(
                            player != null
                                    && book.active(player.getUniqueId(), "duty").isPresent());
            default -> null;
        };
    }
}
