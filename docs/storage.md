# Storing staff cases

Set `storage` in `plugins/StaffGen/config.yml`, then restart. Use one running server per case store. The plugin loads durable cases at startup. A bounded worker serializes new cases and acknowledgements follow a successful write. In-memory indexes serve moderation events. Distinct servers do not synchronize their indexes during play.

## Providers

| storage | Driver or service | Default connection |
| --- | --- | --- |
| yaml | Built in | One UTF-8 file per case in `cases/` |
| sqlite | org.xerial:sqlite-jdbc | `jdbc:sqlite:{folder}/database.sqlite` |
| h2 | com.h2database:h2 | `jdbc:h2:file:{folder}/database` |
| mysql | com.mysql:mysql-connector-j | `jdbc:mysql://localhost:3306/staffgen` |
| mariadb | org.mariadb.jdbc:mariadb-java-client | `jdbc:mariadb://localhost:3306/staffgen` |
| postgresql | org.postgresql:postgresql | `jdbc:postgresql://localhost:5432/staffgen` |
| cockroachdb | PostgreSQL JDBC driver | `jdbc:postgresql://localhost:26257/staffgen?sslmode=verify-full` |
| sqlserver | com.microsoft.sqlserver:mssql-jdbc | `jdbc:sqlserver://localhost:1433;databaseName=staffgen;encrypt=true` |
| tidb | MySQL Connector/J | `jdbc:mysql://localhost:4000/staffgen?sslMode=VERIFY_IDENTITY` |
| mongodb | Bundled MongoDB sync driver | `mongodb://localhost:27017`, database `staffgen` |
| redis | Bundled Jedis client | Standalone Redis at `localhost:6379`, database 0 |

### YAML

The plugin writes each case to a temporary file on the same filesystem, forces its contents to disk, then atomically renames it to the case UUID. Use a filesystem supporting atomic moves. The directory, including revocations and rank assignments, forms one backup unit.

### JDBC

Install a Java-compatible driver and any driver dependencies under `plugins/StaffGen/drivers/`. Download the driver from its publisher. Third-party driver binaries and their individual licenses are distributed by their respective projects. Create the database and a dedicated account allowed to create and read/write the `staffgen_cases` table. The plugin creates the V1 table on first use. A beta installation reuses the same columns. There is one case writer and one JDBC connection.

```yaml
storage: postgresql
databases:
  postgresql:
    url: jdbc:postgresql://db.internal:5432/staffgen?sslmode=verify-full
    statement-timeout: 30
    properties:
      user: staffgen
      password: "your-local-secret"
      connectTimeout: "5"
      socketTimeout: "30"
```

Replace `postgresql` with the identifier from the table to select a different engine. Put each engine's connect and network timeouts, certificates and driver options in its URL or properties. These properties are driver-specific. The `driver` class is optional for named providers. The beta format `storage: jdbc` and a top-level `jdbc` block with explicit `driver`, `url` and `properties` also works. Keep connection secrets in the server's local configuration.

### MongoDB

```yaml
storage: mongodb
databases:
  mongodb:
    uri: "mongodb://staffgen:your-local-secret@mongo.internal:27017/?authSource=admin&tls=true&maxPoolSize=4"
    database: staffgen
```

Use a journaled storage engine. The adapter requests majority, journaled writes with a 15-second write-concern timeout. The collection is `staffgen_cases` with UUID strings as unique document IDs. Supply driver connection options in the URI. [MongoDB write concern](https://www.mongodb.com/docs/manual/reference/write-concern/)

### Redis

```yaml
storage: redis
databases:
  redis:
    host: redis.internal
    port: 6379
    database: 0
    tls: true
    username: staffgen
    password: "your-local-secret"
    key: "staffgen:cases:v1"
    connect-timeout-ms: 5000
    read-timeout-ms: 10000
```

Use a dedicated Redis database with AOF enabled, `appendfsync always` and `maxmemory-policy noeviction`. Startup checks these settings and requires a key without a TTL. Grant the account access to the selected key, the CONFIG GET subcommand for those three checks, and the hash reads, scans and insert-if-absent operations. Set memory limits high enough to retain all cases. An acknowledgement relies on a successful persistent write. Back up the Redis persistence files using the server's documented procedures. [Redis persistence](https://redis.io/docs/latest/operate/oss_and_stack/management/persistence/)

## Offline backend transfers

Stop all writers and take a backup of both stores. Create two StaffGen configuration directories pointing at the intended source and destination. For relative database file paths, each directory is its own `{folder}`. The destination directory and config.yml must exist. The preview opens and initializes the schema and counts identical, conflicting and missing events without copying events. Keep both directories private.

Run the utility with the StaffGen JAR, Spigot API and its runtime dependencies on the classpath. A matching shaded Spigot API JAR supplies the runtime dependencies on a workstation. Use a semicolon as the classpath separator on Windows and a colon on Linux. Each configuration directory can have its own JDBC `drivers/` directory.

```sh
java -cp "StaffGen.jar:spigot-api-shaded.jar" ir.devwebs.staffgen.CaseTransfer old-data new-data
java -cp "StaffGen.jar:spigot-api-shaded.jar" ir.devwebs.staffgen.CaseTransfer old-data new-data --apply
```

After a copy, the utility rereads the destination and compares all source records, including closure links and UUIDs. A rerun skips identical entries. A conflicting UUID stops the copy before any new record is inserted. A transfer copies the case ledger. Move YAML configuration, offline inventory or separate activity files according to their own backup procedures. To roll back, restore the old settings and the backup captured at cutover.

## Importing the original staff.yml

Install PyYAML into a Python environment. Point `tools/import_cases.py` at the original **data** file and a stopped StaffGen YAML case directory. It recognizes punishments, revocations, current ranks, notes, staff warnings, stars, reports and appeals. Legacy report states `open` and `progress` enter the open queue. Completed requests acquire a separate resolution record. For report categories without a target, the reporter is used as the case subject. Existing staff permissions come from your destination configuration. A rank map is a YAML dictionary such as `{jr-helper: helper, mod: moderator}`. Review unmatched custom role IDs in your configuration.

```sh
python tools/import_cases.py old/data/staff.yml new/cases --roles-map ranks.yml
python tools/import_cases.py old/data/staff.yml new/cases --roles-map ranks.yml --apply
```

Re-running with the same inputs skips identical records. Use a filesystem with hard-link support for the destination. The importer preserves records in the original source. SkyMine currency, IPs, plaintext chat logs and unrelated gameplay data are outside the case migration. Back up those records separately if they are needed.
