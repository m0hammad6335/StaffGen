# Developing StaffGen

## Following a moderation request

Start in StaffGen for the Bukkit command entry point and permission gate. CommandInput handles durations, case IDs and page numbers. Moderation owns sanction and revocation rules. CaseBook persists the decision and updates its indexes. A successful write triggers the in-game side effect. Keep writes ahead of notifications so players never see confirmation for an uncommitted case.

CaseStore defines the persistence contract. YamlCases, SqlCases, MongoCases and RedisCases implement it. StorageSettings interprets backend configuration. CaseDocument maps stored fields. Preserve existing record IDs and UUIDs when changing data formats. Closing a case keeps the original reason and actor.

## Server events and threads

StaffTools and DutyMode handle gameplay restrictions. StaffMenu identifies read-only views by inventory holder. StaffChat and StaffGroups manage communication. TeamExtras builds staff reports. The activity package owns optional monitoring and UTC log retention. OfflineInventories handles logout snapshots. The compat package isolates version-sensitive presentation calls.

Keep entity, inventory and permission operations on the server thread. Submit independent data to bounded workers for persistence or reporting. Recheck current permission and session state before displaying results returned asynchronously. Close workers during plugin shutdown.

## Extending commands

Add command syntax to the help table and a permission with an explicit default in plugin.yml. Implement the behavior in its feature class, reuse CommandInput for validation and register configurable text in the messages resource. Check role hierarchy when changing another player's state. If an action is available from a menu, enforce the same permission when the button is clicked.

For a storage change, check writing a record, reopening the provider, closing that record, and reopening it again. For a server integration change, run the throwaway Paper smoke test in tools against an extracted installation. Build success and live server compatibility are separate results. Include the server build and Java version with each compatibility claim.
