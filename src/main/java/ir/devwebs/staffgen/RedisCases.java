package ir.devwebs.staffgen;

import org.bukkit.configuration.ConfigurationSection;

import redis.clients.jedis.*;
import redis.clients.jedis.params.ScanParams;

import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import javax.net.ssl.SSLParameters;

/** Uses a persistent dedicated Redis hash. Startup rejects an ephemeral or evictable database. */
final class RedisCases implements CaseStore {
    private final JedisPool pool;
    private final String key;

    RedisCases(ConfigurationSection cfg) {
        key = cfg.getString("key", "staffgen:cases:v1");
        var client =
                DefaultJedisClientConfig.builder()
                        .connectionTimeoutMillis(cfg.getInt("connect-timeout-ms", 5000))
                        .socketTimeoutMillis(cfg.getInt("read-timeout-ms", 10000))
                        .database(cfg.getInt("database", 0));
        if (cfg.isString("username")) client.user(cfg.getString("username"));
        if (cfg.isString("password")) client.password(cfg.getString("password"));
        if (cfg.getBoolean("tls", false)) {
            SSLParameters params = new SSLParameters();
            params.setEndpointIdentificationAlgorithm("HTTPS");
            client.ssl(true).sslParameters(params);
        }
        pool =
                new JedisPool(
                        new HostAndPort(
                                cfg.getString("host", "localhost"), cfg.getInt("port", 6379)),
                        client.build());
        try (Jedis admin = pool.getResource()) {
            requireValue(admin, "appendonly", "yes");
            requireValue(admin, "appendfsync", "always");
            requireValue(admin, "maxmemory-policy", "noeviction");
            if (admin.ttl(key) >= 0)
                throw new IllegalArgumentException("Case storage key must not expire.");
        } catch (RuntimeException failure) {
            pool.close();
            throw failure;
        }
    }

    private void requireValue(Jedis admin, String setting, String expected) {
        if (!expected.equals(admin.configGet(setting).get(setting))) {
            throw new IllegalArgumentException("Redis requires " + setting + " " + expected);
        }
    }

    @Override
    public void load(Consumer<CaseBook.Entry> accept) throws Exception {
        String cursor = "0";
        ScanParams options = new ScanParams().count(256);
        try (Jedis db = pool.getResource()) {
            do {
                var page = db.hscan(key, cursor, options);
                for (Map.Entry<String, String> row : page.getResult()) {
                    CaseBook.Entry entry = CaseDocument.parse(row.getValue());
                    if (!entry.id().toString().equals(row.getKey()))
                        throw new IllegalStateException("Mismatched case UUID.");
                    accept.accept(entry);
                }
                cursor = page.getCursor();
            } while (!cursor.equals("0"));
        }
    }

    @Override
    public void insert(CaseBook.Entry entry) {
        try (Jedis db = pool.getResource()) {
            if (db.hsetnx(key, entry.id().toString(), CaseDocument.yaml(entry)) != 1L)
                throw new IllegalStateException("Duplicate case UUID.");
        }
    }

    @Override
    public CaseBook.Entry find(UUID id) throws Exception {
        try (Jedis db = pool.getResource()) {
            String row = db.hget(key, id.toString());
            return row == null ? null : CaseDocument.parse(row);
        }
    }

    @Override
    public void close() {
        pool.close();
    }
}
