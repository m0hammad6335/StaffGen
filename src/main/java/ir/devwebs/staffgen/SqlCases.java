package ir.devwebs.staffgen;

import org.bukkit.configuration.ConfigurationSection;

import java.net.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Stream;

/** One connection per serialized ledger writer. A failed connection is replaced on next use. */
final class SqlCases implements CaseStore {
    private static final String TABLE = "staffgen_cases";
    private static final String COLUMNS =
            "id, player_id, actor, kind, reason, created_at, expires_at, closes_id";
    private final URLClassLoader libraries;
    private final String url;
    private final Properties properties = new Properties();
    private final Driver driver;
    private final int statementTimeout;
    private Connection connection;

    SqlCases(Path root, ConfigurationSection config) throws Exception {
        Path dir = Files.createDirectories(root.resolve("drivers"));
        try (Stream<Path> files = Files.list(dir)) {
            List<URL> urls = new ArrayList<>();
            for (Path file : files.filter(p -> p.toString().endsWith(".jar")).sorted().toList())
                urls.add(file.toUri().toURL());
            libraries = new URLClassLoader(urls.toArray(URL[]::new), getClass().getClassLoader());
        }
        try {
            driver =
                    (Driver)
                            Class.forName(config.getString("driver"), true, libraries)
                                    .getConstructor()
                                    .newInstance();
            url =
                    config.getString("url")
                            .replace(
                                    "{folder}",
                                    root.toAbsolutePath().toString().replace('\\', '/'));
            ConfigurationSection props = config.getConfigurationSection("properties");
            if (props != null)
                props.getValues(false)
                        .forEach((k, v) -> properties.setProperty(k, String.valueOf(v)));
            statementTimeout = config.getInt("statement-timeout", 30);
            if (statementTimeout <= 0)
                throw new IllegalArgumentException("Invalid JDBC statement timeout.");
        } catch (Exception failure) {
            libraries.close();
            throw failure;
        }
    }

    private Connection connect() throws SQLException {
        if (connection == null || connection.isClosed()) {
            connection =
                    Objects.requireNonNull(
                            driver.connect(url, properties), "Driver rejected JDBC URL.");
        }
        return connection;
    }

    @Override
    public void load(Consumer<CaseBook.Entry> accept) throws SQLException {
        Connection db = connect();
        boolean sqlServer =
                db.getMetaData()
                        .getDatabaseProductName()
                        .toLowerCase(Locale.ROOT)
                        .contains("microsoft");
        String text = sqlServer ? "NVARCHAR" : "VARCHAR";
        // V1 uses the same schema as beta; SQL Server lacks CREATE TABLE IF NOT EXISTS.
        String create =
                sqlServer
                        ? "IF OBJECT_ID('staffgen_cases', 'U') IS NULL CREATE TABLE "
                        : "CREATE TABLE IF NOT EXISTS ";
        try (Statement s = db.createStatement()) {
            s.setQueryTimeout(statementTimeout);
            s.executeUpdate(
                    create
                            + TABLE
                            + " (id VARCHAR(36) PRIMARY KEY, player_id VARCHAR(36) NOT NULL, "
                            + "actor "
                            + text
                            + "(128) NOT NULL, kind VARCHAR(20) NOT NULL, reason "
                            + text
                            + "(2048) NOT NULL, created_at BIGINT NOT NULL, expires_at BIGINT NOT"
                            + " NULL, closes_id VARCHAR(36))");
            try (ResultSet rs = s.executeQuery("SELECT " + COLUMNS + " FROM " + TABLE)) {
                while (rs.next()) accept.accept(read(rs));
            }
        }
    }

    @Override
    public CaseBook.Entry find(UUID id) throws SQLException {
        try (PreparedStatement query =
                connect()
                        .prepareStatement("SELECT " + COLUMNS + " FROM " + TABLE + " WHERE id=?")) {
            query.setQueryTimeout(statementTimeout);
            query.setString(1, id.toString());
            try (ResultSet rs = query.executeQuery()) {
                return rs.next() ? read(rs) : null;
            }
        }
    }

    private CaseBook.Entry read(ResultSet row) throws SQLException {
        String parent = row.getString("closes_id");
        return new CaseBook.Entry(
                UUID.fromString(row.getString("id")),
                UUID.fromString(row.getString("player_id")),
                row.getString("actor"),
                row.getString("kind"),
                row.getString("reason"),
                row.getLong("created_at"),
                row.getLong("expires_at"),
                parent == null ? null : UUID.fromString(parent));
    }

    @Override
    public void insert(CaseBook.Entry e) throws SQLException {
        try (PreparedStatement p =
                connect()
                        .prepareStatement(
                                "INSERT INTO "
                                        + TABLE
                                        + " ("
                                        + COLUMNS
                                        + ") VALUES (?,?,?,?,?,?,?,?)")) {
            p.setQueryTimeout(statementTimeout);
            p.setString(1, e.id().toString());
            p.setString(2, e.player().toString());
            p.setString(3, e.actor());
            p.setString(4, e.kind());
            p.setString(5, e.reason());
            p.setLong(6, e.created());
            p.setLong(7, e.expires());
            p.setString(8, e.closes() == null ? null : e.closes().toString());
            p.executeUpdate();
        } catch (SQLException failure) {
            // Clear even on indeterminate network failures. A fresh connection can reconcile the
            // UUID.
            if (connection != null) {
                try {
                    connection.close();
                } catch (SQLException close) {
                    failure.addSuppressed(close);
                }
                connection = null;
            }
            throw failure;
        }
    }

    @Override
    public void close() throws Exception {
        try {
            if (connection != null) connection.close();
        } finally {
            libraries.close();
        }
    }
}
