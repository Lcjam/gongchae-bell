#!/usr/bin/env bash
set -euo pipefail

# Synthetic local unit tests for deploy/perf/sample-resources.sh (#18).
# Uses fake /proc files, mock docker/df/du binaries, and a fast synthetic monotonic clock.
# Does NOT touch the live VM, real Docker daemon, or real disk/DB.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
SAMPLER="$REPO_ROOT/deploy/perf/sample-resources.sh"

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

MOCK_BIN="$TMP_DIR/bin"
FAKE_PROC="$TMP_DIR/proc"
STATE_DIR="$TMP_DIR/state"
mkdir -p "$MOCK_BIN" "$FAKE_PROC" "$STATE_DIR"

echo "=== [1/4] bash -n syntax check ==="
bash -n "$SAMPLER"

# Synthetic /proc files
cat >"$FAKE_PROC/stat" <<'EOF'
cpu  1000 10 500 90000 20 0 5 1 0 0
cpu0 500 5 250 45000 10 0 2 0 0 0
EOF

cat >"$FAKE_PROC/meminfo" <<'EOF'
MemTotal:       12211200 kB
MemFree:         2000000 kB
MemAvailable:    10485760 kB
SwapTotal:             0 kB
SwapFree:              0 kB
EOF

cat >"$FAKE_PROC/loadavg" <<'EOF'
0.25 0.30 0.28 1/240 12345
EOF

# Synthetic monotonic clock controlled by state file (in ms)
printf '10000\n' >"$STATE_DIR/clock_ms"
cat >"$MOCK_BIN/mono-clock" <<EOF
#!/usr/bin/env bash
cat "$STATE_DIR/clock_ms"
EOF
chmod +x "$MOCK_BIN/mono-clock"

# Mock docker: advances synthetic clock on stats (simulating overrun on sample 2) and toggles RestartCount on sample 3
printf '0\n' >"$STATE_DIR/stats_calls"
printf '0\n' >"$STATE_DIR/restart_count"
cat >"$MOCK_BIN/docker" <<EOF
#!/usr/bin/env bash
cmd="\${1:-}"
case "\$cmd" in
  system)
    printf 'TYPE\tTOTAL\tACTIVE\tSIZE\nImages\t5\t5\t1.2GB\n'
    ;;
  ps)
    printf 'cid1\ncid2\n'
    ;;
  inspect)
    rc="\$(cat "$STATE_DIR/restart_count")"
    printf '/gongchae-bell-prod-app-blue-1|%s\n/gongchae-bell-prod-postgres-1|0\n' "\$rc"
    ;;
  stats)
    c="\$(cat "$STATE_DIR/stats_calls")"
    c=\$((c + 1))
    printf '%d\n' "\$c" >"$STATE_DIR/stats_calls"
    cur="\$(cat "$STATE_DIR/clock_ms")"
    if [[ "\$c" -eq 1 ]]; then
      # Overrun by 2.4x interval (240ms > 100ms) -> must skip missed ticks on next loop instead of bursting!
      printf '%d\n' \$((cur + 240)) >"$STATE_DIR/clock_ms"
      # Trigger low memory & container restart for next sample
      sed -i 's/^MemAvailable:.*/MemAvailable:    1048576 kB/' "$FAKE_PROC/meminfo"
      printf '1\n' >"$STATE_DIR/restart_count"
    else
      printf '%d\n' \$((cur + 100)) >"$STATE_DIR/clock_ms"
    fi
    printf 'gongchae-bell-prod-app-blue-1|120.00%%|412MiB / 11.6GiB|3.47%%|10MB / 5MB|0B / 0B|42\n'
    printf 'gongchae-bell-prod-postgres-1|10.00%%|180MiB / 11.6GiB|1.51%%|4MB / 4MB|0B / 0B|12\n'
    ;;
esac
EOF
chmod +x "$MOCK_BIN/docker"

# Mock df: reports 85% used to trigger disk warning
cat >"$MOCK_BIN/df" <<'EOF'
#!/usr/bin/env bash
printf 'Filesystem 1B-blocks Used Available Capacity Mounted on\n'
printf '/dev/sda1 50000000000 42500000000 7500000000 85%% /\n'
EOF
chmod +x "$MOCK_BIN/df"

echo "=== [2/4] monotonic schedule, overrun tick skip, normalization, and warnings ==="
OUT1="$TMP_DIR/out1"
PROC_ROOT="$FAKE_PROC" \
DOCKER_BIN="$MOCK_BIN/docker" \
DF_BIN="$MOCK_BIN/df" \
MONOTONIC_CMD="$MOCK_BIN/mono-clock" \
  bash "$SAMPLER" --out-dir "$OUT1" --interval 0.1 --max-samples 3 --host-vcpu 2 --warn-mem-mib 1536 --warn-disk-pct 80 >/dev/null 2>&1

# Check 3 host rows collected + skipped tick recorded on sample 2 (no burst)
row_count="$(tail -n +2 "$OUT1/resources.csv" | wc -l | tr -d '[:space:]')"
[[ "$row_count" == "3" ]] || { echo "FAIL: expected 3 resource rows, got $row_count" >&2; exit 1; }
skipped_s2="$(awk -F, 'NR==3 {print $4}' "$OUT1/resources.csv")"
[[ "$skipped_s2" -ge 1 ]] || { echo "FAIL: expected skipped_since_prev >= 1 on overrun sample 2, got $skipped_s2" >&2; exit 1; }
grep -q 'total_skipped_ticks=[1-9]' "$OUT1/sampler-meta.txt" || { echo "FAIL: total_skipped_ticks missing" >&2; exit 1; }

# Check Docker CPU 2-vCPU normalization (120.00% -> 60.00) and cgroup MemUsage metadata distinction
grep -q 'gongchae-bell-prod-app-blue-1,120.00%,60.00,"412MiB / 11.6GiB"' "$OUT1/containers.csv" \
  || { echo "FAIL: container CPU normalization or raw MemUsage missing" >&2; cat "$OUT1/containers.csv" >&2; exit 1; }
grep -q 'NOT process RSS' "$OUT1/sampler-meta.txt" || { echo "FAIL: RSS distinction missing in meta" >&2; exit 1; }

# Check before/after disk snapshots exist
[[ -s "$OUT1/disk-before.txt" && -s "$OUT1/disk-after.txt" ]] || { echo "FAIL: before/after disk snapshots missing" >&2; exit 1; }
grep -q 'docker system df' "$OUT1/disk-before.txt" || { echo "FAIL: docker system df missing" >&2; exit 1; }

# Check all 3 safety warnings fired
grep -q 'WARN_MEM_AVAILABLE_LOW' "$OUT1/warnings.log" || { echo "FAIL: WARN_MEM_AVAILABLE_LOW missing" >&2; exit 1; }
grep -q 'WARN_DISK_USAGE_HIGH' "$OUT1/warnings.log" || { echo "FAIL: WARN_DISK_USAGE_HIGH missing" >&2; exit 1; }
grep -q 'WARN_CONTAINER_RESTART' "$OUT1/warnings.log" || { echo "FAIL: WARN_CONTAINER_RESTART missing" >&2; exit 1; }

echo "=== [3/4] refuses to overwrite existing output directory ==="
if PROC_ROOT="$FAKE_PROC" DOCKER_BIN="$MOCK_BIN/docker" DF_BIN="$MOCK_BIN/df" MONOTONIC_CMD="$MOCK_BIN/mono-clock" \
    bash "$SAMPLER" --out-dir "$OUT1" --interval 0.1 --max-samples 1 >/dev/null 2>&1; then
  echo "FAIL: sampler should refuse to overwrite existing resources.csv" >&2
  exit 1
fi

echo "=== [4/4] stops cleanly on --stop-file ==="
OUT2="$TMP_DIR/out2"
STOP_FILE="$TMP_DIR/sampler.stop"
touch "$STOP_FILE"
PROC_ROOT="$FAKE_PROC" DOCKER_BIN="$MOCK_BIN/docker" DF_BIN="$MOCK_BIN/df" MONOTONIC_CMD="$MOCK_BIN/mono-clock" \
  bash "$SAMPLER" --out-dir "$OUT2" --interval 0.1 --max-samples 10 --stop-file "$STOP_FILE" >/dev/null 2>&1
row_count2="$(tail -n +2 "$OUT2/resources.csv" | wc -l | tr -d '[:space:]')"
[[ "$row_count2" == "0" ]] || { echo "FAIL: expected 0 rows when stop-file already exists, got $row_count2" >&2; exit 1; }

echo "ALL_SAMPLE_RESOURCES_TESTS_PASSED"
