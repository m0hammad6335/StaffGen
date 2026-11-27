package ir.devwebs.staffgen;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses command words without exposing parser exceptions to players. */
record CommandInput(CommandSender sender, String action, String[] words) {
    private static final Pattern DURATION = Pattern.compile("([1-9][0-9]*)(s|m|h|d|w|mo|y)");

    String word(int index) {
        if (index >= words.length || words[index].isBlank())
            throw new IllegalArgumentException("usage");
        return words[index];
    }

    int page(int index) {
        if (index >= words.length) return 1;
        try {
            int page = Integer.parseInt(words[index]);
            if (page >= 1 && page <= 1000000) return page;
        } catch (NumberFormatException ignored) {
            // Report the same translated error for every invalid page.
        }
        throw new IllegalArgumentException("invalid-page");
    }

    String text(int start) {
        word(start);
        String result = String.join(" ", Arrays.copyOfRange(words, start, words.length)).trim();
        if (result.isEmpty()
                || result.length() > 2048
                || result.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("invalid-text");
        return result;
    }

    UUID id(int index) {
        try {
            return UUID.fromString(word(index));
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("invalid-id");
        }
    }

    Player player() {
        if (sender instanceof Player p) return p;
        throw new IllegalArgumentException("player-only");
    }

    static long duration(String value) {
        if ("perm".equalsIgnoreCase(value)) return 0;
        Matcher matcher = DURATION.matcher(value);
        if (matcher.matches()) {
            long unit =
                    switch (matcher.group(2)) {
                        case "s" -> 1000L;
                        case "m" -> 60000L;
                        case "h" -> 3600000L;
                        case "d" -> 86400000L;
                        case "w" -> 604800000L;
                        case "mo" -> 2592000000L;
                        default -> 31536000000L;
                    };
            try {
                return Math.multiplyExact(Long.parseLong(matcher.group(1)), unit);
            } catch (NumberFormatException | ArithmeticException ignored) {
                // Overflow is invalid, including values that would wrap around to zero.
            }
        }
        throw new IllegalArgumentException("invalid-duration");
    }
}
