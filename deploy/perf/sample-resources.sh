#!/usr/bin/env bash
set -euo pipefail

# Host-side read-only resource sampler for gongchae-bell (#18).
# Execution contract:
#   - Runs INSIDE the Linux OCI VM (e.g. ubuntu user) while external perf-probe.py runs outside.
#   - Strictly read-only: NEVER restarts containers, NEVER writes to DB or .env, NEVER modifies deploy state.
#   - Uses a monotonic 5-second schedule (default) recording both scheduled_utc and actual_utc, skipping
#     overrun ticks instead of bursting catch-up samples.
#   - Captures /proc/stat raw ticks, /proc/meminfo (MemAvailable), /proc/loadavg, df -B1, and raw
#     docker stats --no-stream (distinguishing Docker cgroup MemUsage from RSS, and per-core CPUPerc
#     from 2-vCPU host-normalized percentage). Runs docker system df and optional volume du ONLY before/after.
#   - Emits warnings (never auto-restarts) on MemAvailable < 1536 MiB, disk > 80%, or RestartCount increase.

OUT_DIR=""
INTERVAL_SEC="${SAMPLE_INTERVAL_SEC:-5}"
DURATION_SEC="${SAMPLE_DURATION_SEC:-0}"
MAX_SAMPLES="${SAMPLE_MAX_SAMPLES:-0}"
STOP_FILE="${SAMPLE_STOP_FILE:-}"
DISK_PATH="${SAMPLE_DISK_PATH:-/}"
HOST_VCPU="${SAMPLE_HOST_VCPU:-2}"
WARN_MEM_MIB="${SAMPLE_WARN_MEM_MIB:-1536}"
WARN_DISK_PCT="${SAMPLE_WARN_DISK_PCT:-80}"
PROC_ROOT="${PROC_ROOT:-/proc}"
DOCKER_BIN="${DOCKER_BIN:-docker}"
DF_BIN="${DF_BIN:-df}"
DU_BIN="${DU_BIN:-du}"
MONOTONIC_CMD="${MONOTONIC_CMD:-}"
declare -a VOLUME_PATHS=()

usage() {
  cat <<'EOF'
Usage:
  sample-resources.sh --out-dir DIR [--interval SEC] [--duration SEC] [--max-samples N] [--stop-file PATH]
                      [--disk-path PATH] [--host-vcpu N] [--warn-mem-mib N] [--warn-disk-pct N]
                      [--volume-path PATH]...
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --out-dir) OUT_DIR="$2"; shift 2 ;;
    --interval) INTERVAL_SEC="$2"; shift 2 ;;
    --duration) DURATION_SEC="$2"; shift 2 ;;
    --max-samples) MAX_SAMPLES="$2"; shift 2 ;;
    --stop-file) STOP_FILE="$2"; shift 2 ;;
    --disk-path) DISK_PATH="$2"; shift 2 ;;
    --host-vcpu) HOST_VCPU="$2"; shift 2 ;;
    --warn-mem-mib) WARN_MEM_MIB="$2"; shift 2 ;;
    --warn-disk-pct) WARN_DISK_PCT="$2"; shift 2 ;;
    --volume-path) VOLUME_PATHS+=("$2"); shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) printf 'ERROR: unknown argument: %s\n' "$1" >&2; exit 2 ;;
  esac
done

[[ -n "$OUT_DIR" ]] || { printf 'ERROR: --out-dir is required\n' >&2; exit 2; }
[[ "$INTERVAL_SEC" =~ ^[0-9]+([.][0-9]+)?$ ]] || { printf 'ERROR: invalid --interval: %s\n' "$INTERVAL_SEC" >&2; exit 2; }
[[ "$HOST_VCPU" =~ ^[1-9][0-9]*$ ]] || { printf 'ERROR: invalid --host-vcpu: %s\n' "$HOST_VCPU" >&2; exit 2; }

if [[ -e "$OUT_DIR/resources.csv" || -e "$OUT_DIR/containers.csv" ]]; then
  printf 'ERROR: OUTPUT_EXISTS refusing to overwrite existing sampler files in %s\n' "$OUT_DIR" >&2
  exit 2
fi

mkdir -p "$OUT_DIR"

STOP_REQUESTED=0
on_signal() {
  STOP_REQUESTED=1
}
trap on_signal INT TERM

now_mono_ms() {
  if [[ -n "$MONOTONIC_CMD" ]]; then
    eval "$MONOTONIC_CMD"
    return
  fi
  if [[ -r "$PROC_ROOT/uptime" ]]; then
    awk '{ printf "%.0f\n", $1 * 1000 }' "$PROC_ROOT/uptime"
    return
  fi
  python3 -c 'import time; print(int(time.monotonic() * 1000))'
}

epoch_ms_to_utc() {
  local ms="$1"
  local sec=$((ms / 1000))
  local rem=$((ms % 1000))
  printf '%s.%03dZ' "$(date -u -d "@$sec" +%Y-%m-%dT%H:%M:%S 2>/dev/null || python3 -c "import datetime; print(datetime.datetime.fromtimestamp($sec, datetime.timezone.utc).strftime('%Y-%m-%dT%H:%M:%S'))")" "$rem"
}

now_utc_iso() {
  local now_s
  now_s="$(date -u +%Y-%m-%dT%H:%M:%S)"
  printf '%sZ' "$now_s"
}

warn_msg() {
  local msg="$1"
  local line="[$(now_utc_iso)] $msg"
  printf '%s\n' "$line" >>"$OUT_DIR/warnings.log"
  printf '%s\n' "$line" >&2
}

capture_disk_snapshot() {
  local label="$1"
  local out_file="$OUT_DIR/disk-${label}.txt"
  {
    printf 'captured_utc=%s\n' "$(now_utc_iso)"
    printf '=== df -B1 %s ===\n' "$DISK_PATH"
    "$DF_BIN" -B1 "$DISK_PATH" 2>&1 || true
    printf '=== docker system df ===\n'
    "$DOCKER_BIN" system df 2>&1 || true
    if (( ${#VOLUME_PATHS[@]} > 0 )); then
      printf '=== volume du -sb ===\n'
      local vp
      for vp in "${VOLUME_PATHS[@]}"; do
        if [[ -r "$vp" ]]; then
          "$DU_BIN" -sb "$vp" 2>&1 || true
        else
          printf 'UNREADABLE %s\n' "$vp"
        fi
      done
    fi
  } >"$out_file"
}

declare -A BASELINE_RESTARTS=()
declare -A CURRENT_RESTARTS=()

read_container_restarts() {
  local is_baseline="$1"
  local ids line name count
  CURRENT_RESTARTS=()
  ids="$("$DOCKER_BIN" ps -aq 2>/dev/null || true)"
  [[ -n "$ids" ]] || return 0
  # shellcheck disable=SC2086
  while IFS='|' read -r name count; do
    [[ -n "$name" ]] || continue
    name="${name#/}"
    count="${count:-0}"
    CURRENT_RESTARTS["$name"]="$count"
    if [[ "$is_baseline" == "1" ]]; then
      BASELINE_RESTARTS["$name"]="$count"
    else
      local base="${BASELINE_RESTARTS[$name]:-$count}"
      if [[ "$count" =~ ^[0-9]+$ && "$base" =~ ^[0-9]+$ && "$count" -gt "$base" ]]; then
        warn_msg "WARN_CONTAINER_RESTART container=$name baseline=$base current=$count"
        BASELINE_RESTARTS["$name"]="$count"
      fi
    fi
  done < <("$DOCKER_BIN" inspect --format '{{.Name}}|{{.RestartCount}}' $ids 2>/dev/null || true)
}

cat >"$OUT_DIR/resources.csv" <<'EOF'
sample_seq,scheduled_utc,actual_utc,skipped_since_prev,cpu_user,cpu_nice,cpu_system,cpu_idle,cpu_iowait,cpu_irq,cpu_softirq,cpu_steal,mem_total_kb,mem_available_kb,mem_available_mib,swap_total_kb,swap_free_kb,load1,load5,load15,disk_total_bytes,disk_used_bytes,disk_avail_bytes,disk_used_pct
EOF

cat >"$OUT_DIR/containers.csv" <<'EOF'
sample_seq,scheduled_utc,actual_utc,container,cpu_perc_raw,cpu_host_pct_normalized,mem_usage_cgroup_raw,mem_perc_raw,net_io_raw,block_io_raw,pids,restart_count
EOF

: >"$OUT_DIR/warnings.log"

cat >"$OUT_DIR/sampler-meta.txt" <<EOF
started_utc=$(now_utc_iso)
interval_sec=$INTERVAL_SEC
duration_sec=$DURATION_SEC
max_samples=$MAX_SAMPLES
host_vcpu=$HOST_VCPU
disk_path=$DISK_PATH
warn_mem_mib=$WARN_MEM_MIB
warn_disk_pct=$WARN_DISK_PCT
cpu_note=docker CPUPerc is per-core 100% scale; cpu_host_pct_normalized divides by host_vcpu
mem_note=mem_usage_cgroup_raw is Docker cgroup display usage (NOT process RSS); use host mem_available_mib for headroom
EOF

capture_disk_snapshot "before"
read_container_restarts "1"

INTERVAL_MS="$(awk -v s="$INTERVAL_SEC" 'BEGIN { printf "%.0f\n", s * 1000 }')"
DURATION_MS="$(awk -v s="$DURATION_SEC" 'BEGIN { printf "%.0f\n", s * 1000 }')"

T0_MONO_MS="$(now_mono_ms)"
T0_WALL_MS="$(python3 -c 'import time; print(int(time.time() * 1000))' 2>/dev/null || printf '%s000' "$(date -u +%s)")"

sample_seq=0
tick_index=0
total_skipped=0

while true; do
  if [[ "$STOP_REQUESTED" -eq 1 ]]; then
    break
  fi
  if [[ -n "$STOP_FILE" && -f "$STOP_FILE" ]]; then
    break
  fi
  if [[ "$MAX_SAMPLES" -gt 0 && "$sample_seq" -ge "$MAX_SAMPLES" ]]; then
    break
  fi

  now_ms="$(now_mono_ms)"
  if [[ "$DURATION_MS" -gt 0 && $((now_ms - T0_MONO_MS)) -ge "$DURATION_MS" ]]; then
    break
  fi

  sched_mono_ms=$((T0_MONO_MS + tick_index * INTERVAL_MS))
  skipped_here=0

  if (( now_ms > sched_mono_ms + INTERVAL_MS )); then
    # Overrun: skip missed slots instead of bursting catch-up samples.
    advance=$(( (now_ms - sched_mono_ms) / INTERVAL_MS ))
    skipped_here="$advance"
    total_skipped=$((total_skipped + advance))
    tick_index=$((tick_index + advance))
    sched_mono_ms=$((T0_MONO_MS + tick_index * INTERVAL_MS))
  elif (( now_ms < sched_mono_ms )); then
    wait_ms=$((sched_mono_ms - now_ms))
    if [[ "$DURATION_MS" -gt 0 && $((sched_mono_ms - T0_MONO_MS)) -ge "$DURATION_MS" ]]; then
      break
    fi
    sleep_sec="$(awk -v ms="$wait_ms" 'BEGIN { printf "%.3f", ms / 1000.0 }')"
    sleep "$sleep_sec" || true
    if [[ "$STOP_REQUESTED" -eq 1 ]] || [[ -n "$STOP_FILE" && -f "$STOP_FILE" ]]; then
      break
    fi
    now_ms="$(now_mono_ms)"
  fi

  sample_seq=$((sample_seq + 1))
  sched_wall_ms=$((T0_WALL_MS + (sched_mono_ms - T0_MONO_MS)))
  actual_wall_ms=$((T0_WALL_MS + (now_ms - T0_MONO_MS)))
  scheduled_utc="$(epoch_ms_to_utc "$sched_wall_ms")"
  actual_utc="$(epoch_ms_to_utc "$actual_wall_ms")"

  # Host /proc/stat raw ticks
  read -r _ cpu_user cpu_nice cpu_sys cpu_idle cpu_iowait cpu_irq cpu_softirq cpu_steal _ < <(awk '$1=="cpu"{print; exit}' "$PROC_ROOT/stat")

  # Host /proc/meminfo
  read -r mem_total_kb mem_avail_kb swap_total_kb swap_free_kb < <(
    awk '
      $1=="MemTotal:" { mt=$2 }
      $1=="MemAvailable:" { ma=$2 }
      $1=="SwapTotal:" { st=$2 }
      $1=="SwapFree:" { sf=$2 }
      END { printf "%d %d %d %d\n", mt+0, ma+0, st+0, sf+0 }
    ' "$PROC_ROOT/meminfo"
  )
  mem_avail_mib=$((mem_avail_kb / 1024))

  # Host /proc/loadavg
  read -r load1 load5 load15 _ <"$PROC_ROOT/loadavg"

  # Disk df -B1
  read -r disk_total disk_used disk_avail disk_pct < <(
    "$DF_BIN" -B1 -P "$DISK_PATH" 2>/dev/null | awk 'NR==2 { pct=$5; gsub(/%/, "", pct); printf "%s %s %s %s\n", $2, $3, $4, pct }'
  )

  printf '%d,%s,%s,%d,%s,%s,%s,%s,%s,%s,%s,%s,%d,%d,%d,%d,%d,%s,%s,%s,%s,%s,%s,%s\n' \
    "$sample_seq" "$scheduled_utc" "$actual_utc" "$skipped_here" \
    "${cpu_user:-0}" "${cpu_nice:-0}" "${cpu_sys:-0}" "${cpu_idle:-0}" "${cpu_iowait:-0}" "${cpu_irq:-0}" "${cpu_softirq:-0}" "${cpu_steal:-0}" \
    "$mem_total_kb" "$mem_avail_kb" "$mem_avail_mib" "$swap_total_kb" "$swap_free_kb" \
    "${load1:-0}" "${load5:-0}" "${load15:-0}" \
    "${disk_total:-0}" "${disk_used:-0}" "${disk_avail:-0}" "${disk_pct:-0}" \
    >>"$OUT_DIR/resources.csv"

  if (( mem_avail_mib < WARN_MEM_MIB )); then
    warn_msg "WARN_MEM_AVAILABLE_LOW mem_available_mib=$mem_avail_mib threshold_mib=$WARN_MEM_MIB"
  fi
  if [[ "${disk_pct:-0}" =~ ^[0-9]+$ ]] && (( disk_pct > WARN_DISK_PCT )); then
    warn_msg "WARN_DISK_USAGE_HIGH disk_used_pct=$disk_pct threshold_pct=$WARN_DISK_PCT path=$DISK_PATH"
  fi

  # Container restarts & docker stats --no-stream
  read_container_restarts "0"
  while IFS='|' read -r cname ccpu cmem cmemp cnet cblk cpids; do
    [[ -n "$cname" ]] || continue
    cpu_norm="$(awk -v raw="$ccpu" -v vcpu="$HOST_VCPU" 'BEGIN { gsub(/%/, "", raw); printf "%.2f", (raw + 0) / vcpu }')"
    rcount="${CURRENT_RESTARTS[$cname]:-0}"
    printf '%d,%s,%s,%s,%s,%s,"%s",%s,"%s","%s",%s,%s\n' \
      "$sample_seq" "$scheduled_utc" "$actual_utc" "$cname" "$ccpu" "$cpu_norm" "$cmem" "$cmemp" "$cnet" "$cblk" "$cpids" "$rcount" \
      >>"$OUT_DIR/containers.csv"
  done < <("$DOCKER_BIN" stats --no-stream --format '{{.Name}}|{{.CPUPerc}}|{{.MemUsage}}|{{.MemPerc}}|{{.NetIO}}|{{.BlockIO}}|{{.PIDs}}' 2>/dev/null || true)

  tick_index=$((tick_index + 1))
done

capture_disk_snapshot "after"
{
  printf 'finished_utc=%s\n' "$(now_utc_iso)"
  printf 'samples_collected=%d\n' "$sample_seq"
  printf 'total_skipped_ticks=%d\n' "$total_skipped"
} >>"$OUT_DIR/sampler-meta.txt"

printf 'SAMPLE_RESOURCES_DONE samples=%d skipped=%d out=%s\n' "$sample_seq" "$total_skipped" "$OUT_DIR"
