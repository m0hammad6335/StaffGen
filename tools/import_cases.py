"""Preview or import legacy Staff punishment, team and support data into a stopped YAML installation."""
import argparse
import os
from pathlib import Path
from tempfile import NamedTemporaryFile
from uuid import UUID, uuid5, NAMESPACE_URL

import yaml


def case(key, player, kind, actor, reason, created=0, expires=0, closes=None):
    record = dict(
        id=str(uuid5(NAMESPACE_URL, "staffgen:legacy:" + key)),
        player=str(UUID(str(player))),
        actor=str(actor or "console"),
        kind=kind,
        reason=str(reason or kind),
        created=int(created or 0),
        expires=max(0, int(expires or 0)),
    )
    if closes is not None:
        record["closes"] = closes
    # Java and SQL store bounded text. Report the exact record before writing anything.
    if len(record["actor"].encode("utf-16-le")) > 256 or len(record["reason"].encode("utf-16-le")) > 4096:
        raise ValueError(f"Text exceeds StaffGen's field limit for source record {key}")
    return record


def records(source, roles):
    for player, profile in (source.get("p") or {}).items():
        for kind in ("ban", "mute", "warn", "kick"):
            for number, old in (profile.get(kind) or {}).items():
                key = f"{player}:{kind}:{number}"
                row = case(key, player, kind, old.get("by-uuid") or old.get("by"),
                           old.get("reason") or "Imported case", old["start"], old["end"])
                yield row
                if old.get("revoked"):
                    yield case(key + ":revoked", player, "revoke", old.get("revoked-by"),
                               old.get("revoked-reason") or "Revoked", old.get("revoked-at", row["created"]),
                               closes=row["id"])

    for section, kind in (("notes", "note"), ("sw", "staffwarn"), ("ss", "star")):
        for player, profile in (source.get(section) or {}).items():
            for number, old in (profile.get("list") or {}).items():
                text = old.get("text") or old.get("reason")
                if kind == "note" and old.get("tag"):
                    text = f"[{old['tag']}] {text}"
                yield case(f"{section}:{player}:{number}", player, kind, old.get("by"), text, old.get("at"))

    for player, profile in (source.get("staff") or {}).items():
        rank = profile.get("rank")
        if rank:
            yield case(f"rank:{player}", player, "rank", "console", roles.get(rank, rank), profile.get("since"))

    for section, kind in (("reports", "report"), ("appeals", "appeal")):
        for number, old in (source.get(section) or {}).items():
            actor = old.get("reporter") or old["player"]
            target = old.get("target") or actor
            key = f"{section}:{number}"
            text = str(old.get("text") or kind)
            category = old.get("type") or old.get("category") or old.get("kind")
            if category:
                text = f"[{category}] {text}"
            row = case(key, target, kind, actor, text, old.get("at"))
            yield row
            state = old.get("status", "open")
            if state not in ("open", "progress"):
                yield case(key + ":resolved", target, "resolve", old.get("handler") or old.get("by"),
                           old.get("note") or state, old.get("at"), closes=row["id"])


def migrate(source, destination, roles, apply):
    pending = []
    found = 0
    for row in records(source, roles):
        path = destination / (row["id"] + ".yml")
        found += 1
        if not path.exists():
            pending.append((path, row))
        elif yaml.safe_load(path.read_text(encoding="utf-8")) != row:
            raise ValueError(f"Conflicting existing record: {path.name}")
    print(f"{found} source records, {found - len(pending)} matching, {len(pending)} new.")
    if apply:
        destination.mkdir(parents=True, exist_ok=True)
        for path, row in pending:
            # Publish a complete file and refuse to overwrite a case created after the preview.
            with NamedTemporaryFile(dir=destination, suffix=".tmp", mode="w", encoding="utf-8",
                                    delete=False) as tmp:
                tmp_path = Path(tmp.name)
                try:
                    yaml.safe_dump(row, tmp, allow_unicode=True)
                    tmp.flush()
                    os.fsync(tmp.fileno())
                except BaseException:
                    tmp.close()
                    tmp_path.unlink(missing_ok=True)
                    raise
            try:
                os.link(tmp_path, path)
            finally:
                tmp_path.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path, help="Legacy data/staff.yml")
    parser.add_argument("destination", type=Path, help="StaffGen cases directory")
    parser.add_argument("--roles-map", type=Path, help="YAML dictionary from legacy rank to StaffGen role ID")
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    source = yaml.safe_load(args.source.read_text(encoding="utf-8-sig")) or {}
    roles = yaml.safe_load(args.roles_map.read_text(encoding="utf-8")) if args.roles_map else {}
    migrate(source, args.destination, roles or {}, args.apply)


if __name__ == "__main__":
    main()

