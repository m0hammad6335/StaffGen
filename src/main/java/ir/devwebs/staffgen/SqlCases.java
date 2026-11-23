package ir.devwebs.staffgen;

import org.bukkit.configuration.ConfigurationSection;
import java.io.IOException;
import java.net.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Stream;

/** A single-server ledger. Only the case writer uses the connection after startup. */
final class SqlCases implements AutoCloseable {
    private final URLClassLoader libraries;
    private final Connection db;
    private static final String COLUMNS = "id, player_id, actor, kind, reason, created_at, expires_at, closes_id";

    SqlCases(Path root, ConfigurationSection config) throws Exception {
        Path drivers = Files.createDirectories(root.resolve("drivers"));
        try (Stream<Path> files = Files.list(drivers)) {
            List<URL> urls = new ArrayList<>();
            for (Path p : files.filter(p -> p.toString().endsWith(".jar")).toList()) urls.add(p.toUri().toURL());
            libraries = new URLClassLoader(urls.toArray(URL[]::new), getClass().getClassLoader());
        }
        try {
            Driver driver = (Driver) Class.forName(config.getString("driver"),true,libraries).getDeclaredConstructor().newInstance();
            Properties props = new Properties();
            config.getConfigurationSection("properties").getValues(false).forEach((k,v) -> props.setProperty(k,v.toString()));
            String url = config.getString("url").replace("{folder}", root.toAbsolutePath().toString().replace('\\','/'));
            db = Objects.requireNonNull(driver.connect(url,props),"Driver rejected JDBC URL");
        } catch (Exception e) {
            libraries.close();
            throw e;
        }
    }

    void load(Consumer<CaseBook.Entry> consumer) throws SQLException {
        try (Statement s = db.createStatement()) {
            boolean exists;
            try (ResultSet tables = db.getMetaData().getTables(db.getCatalog(),null,"%",new String[]{"TABLE","BASE TABLE"})) {
                exists = false;
                while (tables.next()) if ("staffgen_cases".equalsIgnoreCase(tables.getString("TABLE_NAME"))) exists = true;
            }
            if (!exists) s.executeUpdate("CREATE TABLE staffgen_cases (id VARCHAR(36) PRIMARY KEY, player_id VARCHAR(36) NOT NULL, actor VARCHAR(128) NOT NULL, kind VARCHAR(20) NOT NULL, reason VARCHAR(2048) NOT NULL, created_at BIGINT NOT NULL, expires_at BIGINT NOT NULL, closes_id VARCHAR(36))");
            try (ResultSet rs = s.executeQuery("SELECT " + COLUMNS + " FROM staffgen_cases")) {
                while (rs.next()) {
                    String closes = rs.getString(8);
                    consumer.accept(new CaseBook.Entry(UUID.fromString(rs.getString(1)),UUID.fromString(rs.getString(2)),
                        rs.getString(3),rs.getString(4),rs.getString(5),rs.getLong(6),rs.getLong(7),
                        closes == null ? null : UUID.fromString(closes)));
                }
            }
        }
    }

    void insert(CaseBook.Entry e) throws SQLException {
        try (PreparedStatement p = db.prepareStatement("INSERT INTO staffgen_cases ("+COLUMNS+") VALUES (?,?,?,?,?,?,?,?)")) {
            p.setString(1,e.id().toString());
            p.setString(2,e.player().toString());
            p.setString(3,e.actor());
            p.setString(4,e.kind());
            p.setString(5,e.reason());
            p.setLong(6,e.created());
            p.setLong(7,e.expires());
            p.setString(8,e.closes()==null ? null : e.closes().toString());
            p.executeUpdate();
        }
    }

    public void close() throws SQLException, IOException {
        try { db.close(); } finally { libraries.close(); }
    }
}
