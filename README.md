# StaffGen

StaffGen provides UUID-based moderation cases, player support requests and staff tools for Minecraft servers. The 1.0-beta build targets the Spigot 1.21.4 API and uses standard Bukkit permission nodes. Authors: m0hammad6335 and m0hammad1. Website: https://devwebs.ir.

## Installation and build

Use Java 21 for a 1.21.4 server. Build with `mvn package` or `gradle build` from the repository root. Install the resulting StaffGen JAR from `target/` or `build/libs/` in the server's plugins directory. Restart the server and grant staff permissions through your server permission manager. A fresh installation uses YAML. The plugin does not require Skript.

## Commands

Use `/sg` or `/staffgen` to see the actions available to you. Permissions follow `staffgen.<action>`. Report, appeal and helpop are available to players by default. Staff permissions default to false. Bukkit permission grants from LuckPerms work directly.

| Command after /sg | Behavior |
| --- | --- |
| ban or mute PLAYER DURATION REASON | Add an expiring or permanent case. Durations include 15m, 2h, 7d and perm. Use @preset instead of duration and reason for a configured preset. |
| warn, note or kick PLAYER REASON | Record a warning, private note or removal. Kick requires an online target. |
| unban, unmute or unfreeze PLAYER REASON | Revoke the active case and preserve its history. |
| unwarn CASE-ID REASON | Revoke an open warning. |
| history PLAYER | Show the latest 20 player records. |
| report PLAYER TEXT, appeal TEXT, helpop TEXT | Open a request. Player requests share a configurable cooldown. In-game appeals require server access. |
| requests, resolve CASE-ID REASON | View the latest 20 open requests or record a resolution. |
| duty, vanish, menu | Toggle duty, toggle visibility for this session, or open the staff tools menu. Duty persists across restarts and restricts mining, placement, melee attacks, drops and pickups. |
| freeze PLAYER REASON | Restrict movement until the case is closed. Disconnecting carries no automatic sanction. |
| tp, tphere, invsee or endersee PLAYER | Teleport or inspect an online player's inventory through a read-only snapshot. |
| staffwarn or star PLAYER REASON, record PLAYER | Record internal feedback or display staff records. |
| staffchat TEXT, stafflist | Contact staff or list visible online staff with chat access. |
| msg PLAYER TEXT, reply TEXT, ignore PLAYER | Private messages and session ignore settings. |
| chatlock, slowchat SECONDS, clearchat, broadcast TEXT | Manage public chat. Zero disables the interval. |
| reload | Validate and apply configuration. Restart after changing storage. |

Configure role actions and levels in config.yml and grant `staffgen.role.helper` or `staffgen.role.moderator`. The `staffgen.protected` node protects online targets. Console handles offline sanctions. Private communications and login credentials are not collected by the plugin. Inventory inspection is online-only.

## Storage

YAML writes one file per case under `plugins/StaffGen/cases/`. The writer saves a temporary file, atomically renames it, and acknowledges the case only after persistence. Revocation creates a separate record. Back up the entire cases directory with the server stopped.

For H2, place the official H2 driver JAR in `plugins/StaffGen/drivers/`, set `storage: jdbc` and use the supplied H2 URL. Only install trusted drivers. Use one writer instance per database. YAML and H2 2.3.232 have passed create, reopen, revoke and reopen checks. Changing the storage selector opens the selected store and does not copy existing records. Keep operator credentials outside the public repository.

The JDBC adapter also accepts a driver class, URL and driver-specific properties. SQLite, PostgreSQL, MySQL, MariaDB, CockroachDB, SQL Server and TiDB still require integration and recovery tests for their dialects and drivers. MongoDB requires a document provider. Redis requires a persistence and recovery design. These engines are pending validation or implementation.

## Migration

Stop the server and back up the source data and destination. With Python and PyYAML installed, run `python tools/import_cases.py PATH/TO/data/staff.yml PATH/TO/plugins/StaffGen/cases` for a preview. Add `--apply` to write new YAML cases. The importer maps bans, mutes, warns, kicks and their revocations by UUID, retaining dates and reasons. Stable IDs support repeated imports. Staff roles, notes, requests and stars require separate mapping before switching production systems.

## Compatibility and beta scope

The build compiles against Spigot API 1.21.4. Legacy and 26.x server compatibility requires separate runtime verification. Offline inventory editing, network-wide synchronization, full session auditing, translated command errors, group chat, escalation rules and automated rank management are outside this beta's verified feature set.

Open an issue with the server build, Java version, storage type and steps to reproduce. Remove player data and connection secrets from logs. Build from a clean checkout before submitting a change. See LICENSE for distribution terms.
