# Activity and staff communication

Use `activity.yml` to choose which events a server records. StaffGen stores activity in daily UTC files under `plugins/StaffGen/activity/`. Case records use the selected database separately. The default retention is 30 days. Changes take effect through `/sg reload`. The single disk worker serializes appends, queries and daily-file cleanup. If the bounded queue fills, a loss counter is written to the server log.

| Command | What staff see |
| --- | --- |
| `/sg logs [YYYY-MM-DD]` | Latest 20 entries for the selected UTC date. Access is audited. |
| `/sg cmdspy` | Live command labels while enabled for the session. Command arguments are discarded. Authentication commands and namespaced equivalents are excluded. |
| `/sg spy` | Live private messages delivered by StaffGen while enabled. Both participants must be visible to the observer. |
| `/sg alts <online-player>` | Other visible online players currently sharing the same connection address. A shared address can represent a household or proxy. Raw addresses are never displayed or written to the activity journal. |
| `/sg cps <online-player>` | Observed swings per second during a five-second sample. |
| `/sg reach <online-player>` | Count of melee hits and maximum observed entity-center distance during the sample. These measurements carry no automatic punishment. |

Each command requires its own `staffgen.<command>` permission. Live observers, shared-address lookup and combat sampling also require their corresponding configuration flags. A fresh installation disables all four. Public chat, command-name and login logging each have independent opt-in settings. Operators with log access can read the selected types of activity.

## Team channels and reports

Use `/sg group create <name>`, then `/sg group invite <player>`. The invited player uses `/sg group join <name>`. Members send messages with `/sg group say <text>`. Owners can kick members or disband the channel. List and leave operate on the current group. Leaving the server ends membership; when the owner leaves the session, the channel closes.

Use `/sg stats [player] [days]` or `/sg top [days]` to count recorded moderation actions within the period. The default is seven days. The profile command also displays the internal role. Reports run off the server thread.

`/sg announce <message>` broadcasts to staff with announcement or staff-chat access. `/sg pin <message>` stores a pinned notice for subsequent joins and `/sg pin clear` removes it. Announcements are saved to the case store before delivery.
