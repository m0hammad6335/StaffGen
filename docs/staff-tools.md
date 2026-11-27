# Moderation and staff tools

Run `/sg help` as the player whose permissions you are testing. The list and tab suggestions use that player's permissions. Use `/sg help <command>` for arguments. The console can administer cases and assign initial local ranks.

## First moderator

1. Have the player join so StaffGen can resolve their UUID.
2. From the console, run `sg setrank PlayerName moderator`. The new role takes effect once storage acknowledges the case.
3. In game, run `/sg menu`. A button needs the same permission as its command, including when clicked. Edit the actions, slots, materials and text in `config.yml`.
4. Use `/sg reload` for theme, messages, rank rules and presets. Restart to change storage providers.

Each action has a `staffgen.<action>` node. Grant individual nodes or assign `staffgen.role.<role>` through a permission manager. Negative node overrides take precedence over role grants. Local ranks are assignments in the case ledger. Removing a local rank leaves externally granted permissions in the permission manager. Define rank ladders in ascending levels. A player changing another player's rank must outrank the target, outrank the new role and already hold every action it grants. Offline rank changes and offline target immunity checks require the console. `staffgen.protected` protects online targets. An administrator may disable specific actions globally using `disabled-actions`.

## Sanctions

| Example | Effect |
| --- | --- |
| `/sg mute PlayerName 15m silent Spamming chat` | Mute for fifteen minutes and notify the target and authorized staff. |
| `/sg ban PlayerName @harassment public` | Use the YAML preset and announce the recorded sanction. |
| `/sg punish PlayerName` | Choose a ban or mute preset and confirm before recording it. |
| `/sg history PlayerName 2` | Show the next page of sanctions and reversals. Notes and staff reviews have separate read permissions. |
| `/sg unban PlayerName Appeal accepted` | Append a reversal linked to the active ban. |
| `/sg undo <case-id> Incorrect target` | Reverse an eligible open case. The sender also needs the corresponding reversal permission. |

Durations support s, m, h, d, w, mo, y and perm. The default visibility is silent. Optional repeat durations match the original reason for the same sanction and player. Revoked cases do not count. Freeze blocks movement, interaction, combat, teleport and configured commands. It continues after reconnect. The `freeze-allowed-actions` list lets a frozen player request staff help.

## Daily workflow

- Ask players to use `/sg report <target> <reason>` or `/sg helpop <message>`. Authorized staff open `/sg requests [page]`, copy the case UUID and run `/sg resolve <case-id> <resolution>`. Online reporters receive the resolution.
- Use `/sg duty` to toggle duty restrictions. Duty protects against accidental world edits, melee attacks, drops and pickups. Use `/sg rtp` to observe a random visible player, or `/sg inspect` to view player status.
- Use `/sg star`, `/sg staffwarn` and `/sg record` for internal reviews. Remove entries with `/sg unstar` or `/sg unstaffwarn` and a reason. Notes have independent create, view and delete permissions.
- Use `/sg staffchat` to toggle staff-only chat, or include a message to send it once. `/sg msg`, `/sg reply` and `/sg ignore` handle session private messages.
- Online inventory and ender chest inspection display read-only copies. Offline snapshots require the separate `staffgen.invsee.offline` node and opt-in snapshot configuration.
- Vanish lasts for the current session. Grant `staffgen.vanish.see` to observers who should retain visibility.

## YAML customization

`messages.yml` contains command feedback. Keep the existing named placeholders when translating a template. Installations upgraded from the first beta can keep their `messages.*` overrides in `config.yml`. Redefine the white and burgundy theme in `theme`. Audit/chat observations and offline inventories have separate YAML files and access policies.

For an optional short server alias, use the server's `commands.yml` and check for collisions in the server command map. For example, the following creates `/staffreport`:

```yaml
aliases:
  staffreport:
    - "staffgen:sg report $1-"
```
