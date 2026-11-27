package ir.devwebs.staffgen;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;

import java.nio.file.Path;
import java.util.*;

/** Selects a driver without exposing its credentials to diagnostics or chat. */
final class StorageSettings {
    private record JdbcDefaults(String driver, String url) {}

    private static final Map<String, JdbcDefaults> DRIVERS =
            Map.of(
                    "h2", new JdbcDefaults("org.h2.Driver", "jdbc:h2:file:{folder}/database"),
                    "sqlite",
                            new JdbcDefaults(
                                    "org.sqlite.JDBC", "jdbc:sqlite:{folder}/database.sqlite"),
                    "mysql",
                            new JdbcDefaults(
                                    "com.mysql.cj.jdbc.Driver",
                                    "jdbc:mysql://localhost:3306/staffgen"),
                    "mariadb",
                            new JdbcDefaults(
                                    "org.mariadb.jdbc.Driver",
                                    "jdbc:mariadb://localhost:3306/staffgen"),
                    "postgresql",
                            new JdbcDefaults(
                                    "org.postgresql.Driver",
                                    "jdbc:postgresql://localhost:5432/staffgen"),
                    "cockroachdb",
                            new JdbcDefaults(
                                    "org.postgresql.Driver",
                                    "jdbc:postgresql://localhost:26257/staffgen?sslmode=verify-full"),
                    "sqlserver",
                            new JdbcDefaults(
                                    "com.microsoft.sqlserver.jdbc.SQLServerDriver",
                                    "jdbc:sqlserver://localhost:1433;databaseName=staffgen;encrypt=true"),
                    "tidb",
                            new JdbcDefaults(
                                    "com.mysql.cj.jdbc.Driver",
                                    "jdbc:mysql://localhost:4000/staffgen?sslMode=VERIFY_IDENTITY"));

    private StorageSettings() {}

    static void validate(ConfigurationSection settings) {
        String name = name(settings);
        if (!DRIVERS.containsKey(name)
                && !Set.of("yaml", "jdbc", "mongodb", "redis").contains(name))
            throw new IllegalArgumentException("Unknown storage provider.");
        if (name.equals("jdbc")
                && (!settings.isString("jdbc.url") || !settings.isString("jdbc.driver")))
            throw new IllegalArgumentException("JDBC requires jdbc.url and jdbc.driver.");
    }

    private static String name(ConfigurationSection settings) {
        return settings.getString("storage", "yaml").toLowerCase(Locale.ROOT);
    }

    static boolean same(ConfigurationSection current, ConfigurationSection next) {
        return name(current).equals(name(next)) && config(current).equals(config(next));
    }

    private static Map<String, Object> config(ConfigurationSection root) {
        String name = name(root);
        ConfigurationSection section =
                root.getConfigurationSection(name.equals("jdbc") ? "jdbc" : "databases." + name);
        if (section == null) return Map.of();
        Map<String, Object> result = new HashMap<>();
        section.getValues(true)
                .forEach(
                        (key, val) -> {
                            if (!(val instanceof ConfigurationSection)) result.put(key, val);
                        });
        return result;
    }

    static CaseStore connect(Path root, ConfigurationSection settings) throws Exception {
        validate(settings);
        String name = name(settings);
        MemoryConfiguration config = new MemoryConfiguration();
        config(settings).forEach(config::set);
        return switch (name) {
            case "yaml" -> new YamlCases(root.resolve("cases"));
            case "mongodb" -> new MongoCases(config);
            case "redis" -> new RedisCases(config);
            default -> {
                JdbcDefaults defaults = DRIVERS.get(name);
                if (defaults != null) {
                    if (!config.isSet("driver")) config.set("driver", defaults.driver());
                    if (!config.isSet("url")) config.set("url", defaults.url());
                }
                yield new SqlCases(root, config);
            }
        };
    }
}
