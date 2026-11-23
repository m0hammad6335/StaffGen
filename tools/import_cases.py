"""Preview or import legacy staff punishments into a stopped YAML installation."""
import argparse
from pathlib import Path
from uuid import UUID, uuid5, NAMESPACE_URL
import yaml

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("source", type=Path, help="Legacy data/staff.yml")
parser.add_argument("destination", type=Path, help="StaffGen cases directory")
parser.add_argument("--apply", action="store_true")
args = parser.parse_args()
source = yaml.safe_load(args.source.read_text(encoding="utf-8-sig")) or {}
records = []
for player, profile in source.get("p", {}).items():
    for kind in ("ban", "mute", "warn", "kick"):
        for number, old in (profile.get(kind) or {}).items():
            uid = str(UUID(str(player)))
            key = f"staffgen:legacy:{uid}:{kind}:{number}"
            case = dict(id=str(uuid5(NAMESPACE_URL, key)), player=uid, kind=kind,
                        actor=str(old.get("by-uuid") or old.get("by") or "console"),
                        reason=str(old.get("reason") or "Imported case"),
                        created=int(old["start"]), expires=max(0, int(old["end"])))
            records.append(case)
            if old.get("revoked"):
                records.append(dict(case, id=str(uuid5(NAMESPACE_URL, key + ":revoked")),
                                    closes=case["id"], kind="revoke", expires=0,
                                    created=int(old.get("revoked-at", case["created"])),
                                    reason=str(old.get("revoked-reason") or "Revoked"),
                                    actor=str(old.get("revoked-by") or "console")))
pending = []
for row in records:
    path = args.destination / (row["id"] + ".yml")
    if path.exists():
        if yaml.safe_load(path.read_text(encoding="utf-8")) != row:
            raise SystemExit(f"Conflicting existing record: {path.name}")
    else:
        pending.append((path, row))
print(f"{len(records)} records examined; {len(pending)} new records.")
if args.apply:
    args.destination.mkdir(parents=True, exist_ok=True)
    for path, row in pending:
        with path.open("x", encoding="utf-8") as output:
            yaml.safe_dump(row, output, allow_unicode=True)

