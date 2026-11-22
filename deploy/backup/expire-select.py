#!/usr/bin/env python3
"""Pick gongchae-bell restic snapshots against the wall-clock retention window (ADR-0017).

Usage: restic snapshots --tag gongchae-bell --json | expire-select.py [--mode expired|kept] NOW_EPOCH WINDOW_SECONDS

A snapshot expires when NOW_EPOCH - snapshot time > WINDOW_SECONDS (strictly older). There is no
"keep the latest" exception: every copy past the window is selected, so zero copies may remain.

stdout: one summary line, then "<full id> <time UTC>" for each snapshot in the chosen mode
("expired" by default, or "kept" to pick non-expired copies for PC pull), oldest first.
Exit 2 (reason on stderr) when the list cannot be trusted: not JSON, not an array, an entry
without a 64-hex id, a time that is missing or has no zone, a snapshot without the gongchae-bell
tag, or a time more than FUTURE_SKEW_SECONDS ahead of NOW_EPOCH. Nothing is selected then.
"""
import json
import re
import sys
from datetime import datetime, timezone

TAG = "gongchae-bell"
# Clock skew tolerated between the writer of a snapshot and this host; beyond it the clock is wrong.
FUTURE_SKEW_SECONDS = 300
SNAPSHOT_ID = re.compile(r"[0-9a-f]{64}")


def fail(reason):
    print(f"RETENTION_INPUT_INVALID {reason}", file=sys.stderr)
    sys.exit(2)


def utc(epoch):
    return datetime.fromtimestamp(epoch, timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def main(argv):
    args = argv[1:]
    mode = "expired"
    if len(args) >= 2 and args[0] == "--mode":
        mode = args[1]
        args = args[2:]
    if mode not in ("expired", "kept") or len(args) != 2 or not args[0].isdigit() or not args[1].isdigit():
        fail("usage: expire-select.py [--mode expired|kept] NOW_EPOCH WINDOW_SECONDS")
    now, window = int(args[0]), int(args[1])

    try:
        snapshots = json.load(sys.stdin)
    except ValueError:
        fail("snapshot list is not JSON")
    if snapshots is None:  # Go encodes an empty (nil) slice as null.
        snapshots = []
    if not isinstance(snapshots, list):
        fail("snapshot list is not a JSON array")

    expired, kept = [], []
    for snap in snapshots:
        if not isinstance(snap, dict):
            fail("snapshot entry is not an object")
        sid = snap.get("id")
        if not isinstance(sid, str) or not SNAPSHOT_ID.fullmatch(sid):
            fail("snapshot id missing or not 64 hex characters")
        tags = snap.get("tags")
        if not isinstance(tags, list) or TAG not in tags:
            fail(f"snapshot {sid[:8]} lacks the {TAG} tag")
        raw = snap.get("time")
        try:
            when = datetime.fromisoformat(raw) if isinstance(raw, str) else None
        except ValueError:
            when = None
        if when is None or when.tzinfo is None:
            fail(f"snapshot {sid[:8]} time missing, malformed or without a zone")
        epoch = when.timestamp()
        if epoch > now + FUTURE_SKEW_SECONDS:
            fail(f"snapshot {sid[:8]} time {utc(epoch)} is after now {utc(now)}")
        (expired if now - epoch > window else kept).append((epoch, sid))

    expired.sort()
    kept.sort()
    print(
        f"total={len(expired) + len(kept)} expire={len(expired)} keep={len(kept)}"
        f" oldest_kept_utc={utc(kept[0][0]) if kept else 'none'}"
        f" newest_kept_utc={utc(kept[-1][0]) if kept else 'none'}"
        f" now_utc={utc(now)} window_seconds={window}"
    )
    selected = expired if mode == "expired" else kept
    for epoch, sid in selected:
        print(sid, utc(epoch))


if __name__ == "__main__":
    main(sys.argv)
