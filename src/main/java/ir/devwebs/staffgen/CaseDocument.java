package ir.devwebs.staffgen;

import org.bukkit.configuration.file.YamlConfiguration;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Shared field names keep database transfers and the original YAML layout interchangeable. */
final class CaseDocument {
    private CaseDocument() {}

    static Map<String, Object> fields(CaseBook.Entry e) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("id", e.id().toString());
        fields.put("player", e.player().toString());
        fields.put("actor", e.actor());
        fields.put("kind", e.kind());
        fields.put("reason", e.reason());
        fields.put("created", e.created());
        fields.put("expires", e.expires());
        if (e.closes() != null) fields.put("closes", e.closes().toString());
        return fields;
    }

    static CaseBook.Entry read(Map<String, ?> row) {
        String parent = (String) row.get("closes");
        return new CaseBook.Entry(
                UUID.fromString((String) row.get("id")),
                UUID.fromString((String) row.get("player")),
                (String) row.get("actor"),
                (String) row.get("kind"),
                (String) row.get("reason"),
                number(row, "created"),
                number(row, "expires"),
                parent == null ? null : UUID.fromString(parent));
    }

    private static long number(Map<String, ?> row, String field) {
        Object value = Objects.requireNonNull(row.get(field), field);
        if (!(value instanceof Number)) throw new IllegalArgumentException("Invalid " + field);
        return ((Number) value).longValue();
    }

    static String yaml(CaseBook.Entry entry) {
        YamlConfiguration doc = new YamlConfiguration();
        fields(entry).forEach(doc::set);
        return doc.saveToString();
    }

    static CaseBook.Entry parse(String text) throws Exception {
        YamlConfiguration doc = new YamlConfiguration();
        doc.loadFromString(text);
        return read(doc.getValues(false));
    }
}
