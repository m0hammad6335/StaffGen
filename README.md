# StaffGen

StaffGen is a configurable staff management and moderation plugin for Minecraft servers. Staff can handle player reports, review case histories, apply timed sanctions and inspect inventories through commands or menus. Cases use player UUIDs and preserve the original decision when a moderator revokes an action.

**Authors:** m0hammad6335 and m0hammad1 · **Website:** https://devwebs.ir · **Version:** 1.0-beta.

## First installation

1. Use a Paper or Spigot 1.21.4 test server with Java 21. Install the packaged StaffGen JAR in the plugins directory and restart.
2. Grant staff individual permission nodes or a role defined in config.yml through your permissions manager. Check plugin.yml for exact defaults. Start with an ordinary player to verify the public report and help commands.
3. Use /sg help to see the commands available to you. Use /sg help ban to see a single command's syntax. /staffgen is the long form.
4. Edit the generated YAML files in plugins/StaffGen. Theme colors, presets, roles and menus live in config.yml. Restart to switch storage. Run /sg reload after editing messages or role rules.

### Example moderation session

A player submits /sg report Alex Repeated spam. Staff open /sg requests and inspect /sg history Alex. With the relevant permission, a moderator issues /sg mute Alex 10m Repeated spam. They close the report with /sg resolve CASE-ID Warned and muted for ten minutes. A later /sg unmute Alex Appeal accepted creates a separate record that points to the mute.

### Data and privacy

YAML is the default backend. Place approved JDBC driver JARs in the private drivers directory when configuring an SQL backend. Database credentials and live player files belong outside Git. Back up both settings and data before switching providers. An empty destination appears as an empty server until records have been migrated. The legacy case importer supports preview before applying changes.

Activity and inspection controls have separate configuration files. Logins, chat recording, command observation and offline inventory snapshots are optional. Offline views use a read-only copy taken at logout. Audit and staff communication commands are described in [Activity and staff communication](docs/activity.md).

### Building

Use Maven with mvn package or Gradle with gradle build. The Maven artifact is target/StaffGen-1.0-beta.jar. The Gradle distribution is build/libs/StaffGen-1.0-beta.jar. Install the shaded distribution containing its database clients. Keep the server-provided Bukkit API on the compile classpath only.

See [Developer guide](docs/development.md) for the source layout. When reporting an issue, include the server build, Java version, selected storage backend, command and sanitized error. License terms are in LICENSE.

## Operator reference

- [Commands and permissions](docs/commands.md)
- [Storage setup, supported providers and migration](docs/storage.md)
- [Activity logging and staff communication](docs/activity.md)

When PlaceholderAPI is installed, StaffGen provides %staffgen_version% and %staffgen_on_duty%.

