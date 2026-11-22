#!/usr/bin/env bash
# Controlled collector alert drill harness (#15):
# - Verifies original expr and for: 10m of GongchaeApiBudgetWarning and GongchaeRegularCollectionFailing
#   without touching production DB, calling external APIs, or masking GongchaeAppMetricsDown.
# - B1: Inspects live Prometheus bind mount (single-file inode vs directory mount) before mutating.
# - B2: Appends an isolated scrape job `gongchae-drill` instead of modifying `gongchae-app`.
# - B3 / R1: Snapshots and compares Postgres state using real Flyway schema columns via container env.
# - B4 / R2-R5:
#   * Deploy-root exclusive lock serializes preflight/run/restore across backup-dirs.
#   * Mandatory Git main blob SHA-256 verification before mutation and on restore.
#   * Allowlisted .env (including `export KEY=val`) AND live app container runtime flag checks.
#   * Supervisor/worker separation with bounded external commands, independent deadline kill + handoff,
#     signal masking during restore, pre-write ownership/hash guard, no-op on already-RESTORED state,
#     owned-resource-only cleanup (never glob-deletes unrelated rules), and non-zero exit on restore errors.
set -euo pipefail

ROOT_DIR="${DRILL_ROOT_DIR:-/opt/gongchae-bell}"
ENV_FILE="${DRILL_ENV_FILE:-}"
BACKUP_DIR="${DRILL_BACKUP_DIR:-${HOME}/gongchae-ops/collector-drill-backup}"
WORK_DIR="${DRILL_WORK_DIR:-/tmp/gongchae-collector-drill}"
PROJECT_NAME="${DRILL_PROJECT_NAME:-gongchae-bell-prod}"
PROM_CONTAINER="${DRILL_PROM_CONTAINER:-}"
PG_CONTAINER="${DRILL_PG_CONTAINER:-}"
APP_CONTAINERS="${DRILL_APP_CONTAINERS:-}"
EXPORTER_NAME="${DRILL_EXPORTER_NAME:-gongchae-drill-exporter}"
PROM_IMAGE="${DRILL_PROM_IMAGE:-prom/prometheus:v3.16.0}"
CADDY_IMAGE="${DRILL_CADDY_IMAGE:-caddy:2-alpine}"
EXPECTED_PROM_SHA256="${DRILL_EXPECTED_PROM_SHA256:-}"
EXPECTED_RULES_SHA256="${DRILL_EXPECTED_RULES_SHA256:-}"
RECREATE_CMD="${DRILL_RECREATE_CMD:-}"
ALLOW_RECREATE_PROMETHEUS="false"
LOCK_WAIT_SEC="${DRILL_LOCK_WAIT_SEC:-30}"
CMD_TIMEOUT_SEC="${DRILL_CMD_TIMEOUT_SEC:-20}"
CMD_KILL_AFTER_SEC="${DRILL_CMD_KILL_AFTER_SEC:-5}"
RECREATE_TIMEOUT_SEC="${DRILL_RECREATE_TIMEOUT_SEC:-90}"

# Real production thresholds and timing guardrails (never shortened to fake rule passage).
readonly BUDGET_INJECT_VALUE=500
readonly FAILING_INJECT_VALUE=2
readonly PENDING_HOLD_CHECK_SEC=300     # >= 5m check while for: 10m is still pending
readonly FAILING_MIN_ELAPSED_SEC=600    # must not fire before full for: 10m
readonly FAILING_WAIT_TIMEOUT_SEC=900   # 15m max wait for 10m firing
readonly RESOLVE_WAIT_TIMEOUT_SEC=180   # 3m max wait for Prometheus alerts to clear
readonly POST_RESOLVE_WAIT_SEC="${DRILL_POST_RESOLVE_WAIT_SEC:-90}"
readonly MAX_DURATION_SEC="${DRILL_MAX_DURATION_SEC:-1500}"
readonly EXPORTER_TTL_SEC="${DRILL_EXPORTER_TTL_SEC:-1560}"

# Test-only internal hooks for deterministic failure-mode regression (never change thresholds or for: 10m).
TEST_WORKER_STALL_SEC="${DRILL_TEST_WORKER_STALL_SEC:-0}"
TEST_RESTORE_HOLD_SEC="${DRILL_TEST_RESTORE_HOLD_SEC:-0}"

LOCK_DIR_HELD=0
LOCK_FILE=""
OWNER_FILE=""
MUTATIONS_STARTED=0
RESTORE_ATTEMPTED=0
RESTORE_DONE=0
SUPERVISOR_SIGNAL=""
SUPERVISOR_PID=""
SUPERVISOR_START_TIME=""
WORKER_PID=""
WORKER_START_TIME=""
WORKER_LOCK_FILE=""
WORKER_REVOKED_FILE=""
ATTEMPT_DIR=""
RUN_ID=""
DRILL_PROM_SHA256=""
DRILL_RULES_SHA256=""

log() {
  printf '%s [drill] %s\n' "$(date -u +'%Y-%m-%dT%H:%M:%SZ')" "$*"
}

fail() {
  printf '%s [drill] ERROR: %s\n' "$(date -u +'%Y-%m-%dT%H:%M:%SZ')" "$*" >&2
  exit 1
}

usage() {
  cat <<'EOF'
Usage:
  collector-alert-drill.sh <preflight|run|restore> [options]

Options:
  --root-dir DIR                  Deploy root (default: /opt/gongchae-bell)
  --env-file FILE                 Path to .env (default: <root-dir>/.env)
  --backup-dir DIR                Backup & baseline state dir (default: ~/gongchae-ops/collector-drill-backup)
  --work-dir DIR                  Temporary exporter work dir (default: /tmp/gongchae-collector-drill)
  --project NAME                  Docker Compose project name (default: gongchae-bell-prod)
  --prom-container NAME_OR_ID     Explicit Prometheus container (overrides compose label lookup)
  --pg-container NAME_OR_ID       Explicit Postgres container (overrides compose label lookup)
  --app-containers LIST           Comma-separated running app containers (overrides compose label lookup)
  --exporter-name NAME            Temporary exporter container name (default: gongchae-drill-exporter)
  --expected-prom-sha256 SHA      REQUIRED for preflight/run: expected Git main blob SHA-256 of prometheus.yml
  --expected-rules-sha256 SHA     REQUIRED for preflight/run: expected Git main blob SHA-256 of rules/gongchae.yml
  --recreate-cmd CMD              Command to recreate Prometheus if single-file bind mount is stale
  --allow-recreate-prometheus     Opt-in to recreate Prometheus on stale single-file bind mount (run only)
  --lock-wait-sec SEC             Seconds restore waits for deploy-root lock (default: 30)
  -h, --help                      Show this help
EOF
}

if [[ $# -lt 1 ]]; then
  usage >&2
  exit 2
fi

ACTION="$1"
shift

while [[ $# -gt 0 ]]; do
  case "$1" in
    --root-dir) ROOT_DIR="$2"; shift 2 ;;
    --env-file) ENV_FILE="$2"; shift 2 ;;
    --backup-dir) BACKUP_DIR="$2"; shift 2 ;;
    --work-dir) WORK_DIR="$2"; shift 2 ;;
    --project) PROJECT_NAME="$2"; shift 2 ;;
    --prom-container) PROM_CONTAINER="$2"; shift 2 ;;
    --pg-container) PG_CONTAINER="$2"; shift 2 ;;
    --app-containers) APP_CONTAINERS="$2"; shift 2 ;;
    --exporter-name) EXPORTER_NAME="$2"; shift 2 ;;
    --expected-prom-sha256) EXPECTED_PROM_SHA256="$2"; shift 2 ;;
    --expected-rules-sha256) EXPECTED_RULES_SHA256="$2"; shift 2 ;;
    --recreate-cmd) RECREATE_CMD="$2"; shift 2 ;;
    --allow-recreate-prometheus) ALLOW_RECREATE_PROMETHEUS="true"; shift ;;
    --lock-wait-sec) LOCK_WAIT_SEC="$2"; shift 2 ;;
    --run-id) RUN_ID="$2"; shift 2 ;;
    --attempt-dir) ATTEMPT_DIR="$2"; shift 2 ;;
    --supervisor-pid) SUPERVISOR_PID="$2"; shift 2 ;;
    --supervisor-start-time) SUPERVISOR_START_TIME="$2"; shift 2 ;;
    --worker-lock-file) WORKER_LOCK_FILE="$2"; shift 2 ;;
    --worker-revoked-file) WORKER_REVOKED_FILE="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) printf 'Unknown argument: %s\n' "$1" >&2; usage >&2; exit 2 ;;
  esac
done

case "$ACTION" in
  preflight|run|restore|_worker) ;;
  -h|--help) usage; exit 0 ;;
  *) printf 'Unknown action: %s\n' "$ACTION" >&2; usage >&2; exit 2 ;;
esac

if [[ -z "$ENV_FILE" ]]; then
  ENV_FILE="${ROOT_DIR}/.env"
fi
PROM_CONFIG="${ROOT_DIR}/deploy/monitoring/prometheus/prometheus.yml"
RULES_DIR="${ROOT_DIR}/deploy/monitoring/prometheus/rules"
RULES_FILE="${RULES_DIR}/gongchae.yml"
STATE_DIR="${ROOT_DIR}/deploy/state"
LOCK_FILE="${STATE_DIR}/drill-runtime.lock"
OWNER_FILE="${STATE_DIR}/drill-runtime.owner"
if [[ -z "$RECREATE_CMD" ]]; then
  RECREATE_CMD="cd \"${ROOT_DIR}\" && deploy/deploy.sh monitoring up -d --force-recreate prometheus"
fi

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || fail "Missing required tool: $1"
}

PYTHON_BIN="python3"
if ! command -v python3 >/dev/null 2>&1 || ! python3 --version >/dev/null 2>&1; then
  PYTHON_BIN="python"
fi

docker_host_path() {
  if command -v cygpath >/dev/null 2>&1; then
    cygpath -m "$1"
  else
    printf '%s' "$1"
  fi
}

run_bounded() {
  local secs="$1"
  shift
  # Reset INT/TERM/HUP to default inside a subshell before exec'ing timeout so
  # restore's `trap '' INT TERM HUP` (SIG_IGN) is never inherited by bounded children.
  # Close deploy-root lock FD 9 so bounded children can never retain the root lock.
  # GNU timeout (without --foreground) puts the child in its own process group, so
  # `-k` escalates to SIGKILL for the entire child process group (child + descendants).
  (
    trap - INT TERM HUP
    exec timeout -k "$CMD_KILL_AFTER_SEC" "$secs" "$@" 9>&-
  )
}

# Returns: 0 if EXPORTER_NAME exists, 1 if docker ps succeeded and it is absent, 2 on error/timeout.
exporter_container_exists() {
  local names
  names="$(run_bounded "$CMD_TIMEOUT_SEC" docker ps -a --format '{{.Names}}')" || return 2
  if printf '%s\n' "$names" | grep -Fxq "$EXPORTER_NAME"; then
    return 0
  fi
  return 1
}

read_proc_start_time() {
  local pid="$1"
  [[ -n "$pid" && "$pid" =~ ^[0-9]+$ && -r "/proc/${pid}/stat" ]] || return 1
  local raw rest
  raw="$(cat "/proc/${pid}/stat" 2>/dev/null)" || return 1
  rest="${raw##*) }"
  [[ "$rest" != "$raw" ]] || return 1
  awk '{ if (NF >= 20) print $20; else exit 1 }' <<< "$rest"
}

proc_cmdline_has_worker_run_id() {
  local pid="$1"
  local run_id="$2"
  [[ -n "$pid" && "$pid" =~ ^[0-9]+$ && -n "$run_id" && -r "/proc/${pid}/cmdline" ]] || return 1
  cat "/proc/${pid}/cmdline" 2>/dev/null | tr '\0' '\n' | awk -v rid="$run_id" '
    $0 == "_worker" { has_worker = 1 }
    prev == "--run-id" && $0 == rid { has_run_id = 1 }
    { prev = $0 }
    END { exit((has_worker && has_run_id) ? 0 : 1) }
  '
}

proc_cmdline_has_supervisor_run() {
  local pid="$1"
  [[ -n "$pid" && "$pid" =~ ^[0-9]+$ && -r "/proc/${pid}/cmdline" ]] || return 1
  cat "/proc/${pid}/cmdline" 2>/dev/null | tr '\0' '\n' | awk '
    $0 == "run" { found = 1 }
    END { exit(found ? 0 : 1) }
  '
}

is_verified_worker_pid() {
  local pid="$1"
  local run_id="$2"
  local expected_start="${3:-}"
  [[ -n "$pid" && "$pid" =~ ^[0-9]+$ && -n "$run_id" ]] || return 1
  kill -0 "$pid" 2>/dev/null || return 1
  proc_cmdline_has_worker_run_id "$pid" "$run_id" || return 1
  if [[ -n "$expected_start" ]]; then
    local cur_start
    cur_start="$(read_proc_start_time "$pid" 2>/dev/null || true)"
    [[ -n "$cur_start" && "$cur_start" == "$expected_start" ]] || return 1
  fi
  return 0
}

is_verified_supervisor_pid() {
  local pid="$1"
  local expected_start="${2:-}"
  [[ -n "$pid" && "$pid" =~ ^[0-9]+$ ]] || return 1
  kill -0 "$pid" 2>/dev/null || return 1
  proc_cmdline_has_supervisor_run "$pid" || return 1
  if [[ -n "$expected_start" ]]; then
    local cur_start
    cur_start="$(read_proc_start_time "$pid" 2>/dev/null || true)"
    [[ -n "$cur_start" && "$cur_start" == "$expected_start" ]] || return 1
  fi
  return 0
}

list_child_pids() {
  local ppid="$1"
  local p raw rest c_ppid
  [[ -n "$ppid" && "$ppid" =~ ^[0-9]+$ ]] || return 0
  for p in /proc/[0-9]*; do
    p="${p#/proc/}"
    [[ "$p" =~ ^[0-9]+$ ]] || continue
    [[ -r "/proc/${p}/stat" ]] || continue
    raw="$(cat "/proc/${p}/stat" 2>/dev/null || true)"
    rest="${raw##*) }"
    [[ "$rest" != "$raw" ]] || continue
    c_ppid="$(awk '{ print $2 }' <<< "$rest" 2>/dev/null || true)"
    if [[ "$c_ppid" == "$ppid" ]]; then
      printf '%s\n' "$p"
    fi
  done
}

list_descendant_pids() {
  local parent="$1"
  local depth="${2:-0}"
  [[ "$depth" -lt 8 ]] || return 0
  local c
  while IFS= read -r c; do
    [[ -n "$c" && "$c" != "$parent" ]] || continue
    list_descendant_pids "$c" $(( depth + 1 ))
    printf '%s\n' "$c"
  done < <(list_child_pids "$parent")
}

# Returns 0 only if no process holds the worker lease lock (Linux flock) and no /proc _worker matches RUN_ID.
is_worker_lease_free() {
  local lock_path="$1"
  local run_id="$2"
  if [[ -n "$lock_path" ]] && command -v flock >/dev/null 2>&1; then
    if [[ -f "$lock_path" ]]; then
      if ! (
        exec 7>"$lock_path"
        flock -n 7
      ) 2>/dev/null; then
        return 1
      fi
    fi
  fi
  if [[ -n "$run_id" ]]; then
    local p
    for p in /proc/[0-9]*; do
      p="${p#/proc/}"
      [[ "$p" =~ ^[0-9]+$ ]] || continue
      if proc_cmdline_has_worker_run_id "$p" "$run_id"; then
        return 1
      fi
    done
  fi
  return 0
}

host_sha256() {
  sha256sum "$1" | awk '{print $1}'
}

is_valid_sha256() {
  [[ "$1" =~ ^[0-9a-f]{64}$ ]]
}

acquire_root_lock() {
  local mode="$1" # nonblock | wait
  local wait_secs="${2:-$LOCK_WAIT_SEC}"
  mkdir -p "$STATE_DIR"
  if command -v flock >/dev/null 2>&1; then
    exec 9>"$LOCK_FILE"
    if [[ "$mode" == "nonblock" ]]; then
      flock -n 9 || fail "DRILL_LOCK_BUSY: another drill run or restore holds ${LOCK_FILE}"
    else
      flock -w "$wait_secs" 9 || fail "DRILL_LOCK_BUSY: timed out after ${wait_secs}s waiting for ${LOCK_FILE}"
    fi
  else
    local ldir="${LOCK_FILE}.d"
    local deadline=$(( $(date +%s) + wait_secs ))
    while true; do
      if mkdir "$ldir" 2>/dev/null; then
        printf '%s\n' "$$" > "${ldir}/pid"
        LOCK_DIR_HELD=1
        break
      fi
      # Reap stale lockdir only if its recorded PID is confirmed dead (e.g. after supervisor SIGKILL on Windows).
      if [[ -f "${ldir}/pid" ]]; then
        local holder_pid
        holder_pid="$(cat "${ldir}/pid" 2>/dev/null || true)"
        if [[ -n "$holder_pid" && "$holder_pid" =~ ^[0-9]+$ ]] && ! kill -0 "$holder_pid" 2>/dev/null; then
          rm -rf "$ldir" 2>/dev/null || true
          continue
        fi
      fi
      if [[ "$mode" == "nonblock" || $(date +%s) -ge $deadline ]]; then
        fail "DRILL_LOCK_BUSY: another drill run or restore holds ${ldir}"
      fi
      sleep 1
    done
  fi
}

release_root_lock() {
  if [[ "$LOCK_DIR_HELD" -eq 1 ]]; then
    rm -rf "${LOCK_FILE}.d" 2>/dev/null || true
    LOCK_DIR_HELD=0
  fi
  if command -v flock >/dev/null 2>&1; then
    exec 9>&- 2>/dev/null || true
  fi
}

write_meta_files() {
  local state="$1"
  local content
  content="$(cat <<EOF
RUN_ID=${RUN_ID}
ROOT_DIR=${ROOT_DIR}
BACKUP_DIR=${BACKUP_DIR}
WORK_DIR=${WORK_DIR}
ATTEMPT_DIR=${ATTEMPT_DIR}
EXPORTER_NAME=${EXPORTER_NAME}
EXPECTED_PROM_SHA256=${EXPECTED_PROM_SHA256}
EXPECTED_RULES_SHA256=${EXPECTED_RULES_SHA256}
DRILL_PROM_SHA256=${DRILL_PROM_SHA256}
DRILL_RULES_SHA256=${DRILL_RULES_SHA256}
SUPERVISOR_PID=${SUPERVISOR_PID}
SUPERVISOR_START_TIME=${SUPERVISOR_START_TIME}
WORKER_PID=${WORKER_PID}
WORKER_START_TIME=${WORKER_START_TIME}
WORKER_LOCK_FILE=${WORKER_LOCK_FILE}
WORKER_REVOKED_FILE=${WORKER_REVOKED_FILE}
STATE=${state}
EOF
)"
  printf '%s\n' "$content" > "${BACKUP_DIR}/meta.env"
  printf '%s\n' "$state" > "${BACKUP_DIR}/state"
  mkdir -p "$STATE_DIR"
  printf '%s\n' "$content" > "$OWNER_FILE"
}

read_meta_key() {
  local file="$1"
  local key="$2"
  [[ -f "$file" ]] || return 1
  awk -F= -v k="$key" '$1 == k { print substr($0, index($0, "=") + 1); found=1 } END { exit(found ? 0 : 1) }' "$file"
}

resolve_containers() {
  if [[ -z "$PROM_CONTAINER" ]]; then
    PROM_CONTAINER="$(run_bounded "$CMD_TIMEOUT_SEC" docker ps -q \
      --filter "label=com.docker.compose.project=${PROJECT_NAME}" \
      --filter "label=com.docker.compose.service=prometheus" | head -n1)"
  fi
  if [[ -z "$PG_CONTAINER" ]]; then
    PG_CONTAINER="$(run_bounded "$CMD_TIMEOUT_SEC" docker ps -q \
      --filter "label=com.docker.compose.project=${PROJECT_NAME}" \
      --filter "label=com.docker.compose.service=postgres" | head -n1)"
  fi
  [[ -n "$PROM_CONTAINER" ]] || fail "Running Prometheus container not found (project=${PROJECT_NAME})"
  [[ -n "$PG_CONTAINER" ]] || fail "Running Postgres container not found (project=${PROJECT_NAME})"
}

resolve_app_containers() {
  local list=""
  if [[ -n "$APP_CONTAINERS" ]]; then
    list="$(printf '%s\n' "$APP_CONTAINERS" | tr ',' '\n' | sed '/^[[:space:]]*$/d' | sort -u)"
  else
    local blue green
    blue="$(run_bounded "$CMD_TIMEOUT_SEC" docker ps -q \
      --filter "label=com.docker.compose.project=${PROJECT_NAME}" \
      --filter "label=com.docker.compose.service=app-blue")"
    green="$(run_bounded "$CMD_TIMEOUT_SEC" docker ps -q \
      --filter "label=com.docker.compose.project=${PROJECT_NAME}" \
      --filter "label=com.docker.compose.service=app-green")"
    list="$(printf '%s\n%s\n' "$blue" "$green" | sed '/^[[:space:]]*$/d' | sort -u)"
  fi
  [[ -n "$list" ]] || fail "NO_RUNNING_APP_CONTAINERS: cannot verify runtime flags"
  printf '%s\n' "$list"
}

prom_api_get() {
  local path="$1"
  run_bounded "$CMD_TIMEOUT_SEC" docker exec "$PROM_CONTAINER" wget -T 10 -qO- "http://localhost:9090${path}"
}

prom_sighup() {
  run_bounded "$CMD_TIMEOUT_SEC" docker kill -s HUP "$PROM_CONTAINER" >/dev/null
}

read_env_flag() {
  local key="$1"
  "$PYTHON_BIN" - "$(docker_host_path "$ENV_FILE")" "$key" <<'PY'
import re, sys

path, target = sys.argv[1], sys.argv[2]
val = "false"
with open(path, "r", encoding="utf-8") as fh:
    for lineno, raw in enumerate(fh, 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if re.match(r"^export\s+", line):
            line = re.sub(r"^export\s+", "", line, count=1)
        if "=" not in line:
            continue
        k, v = line.split("=", 1)
        if k.strip() != target:
            continue
        v = v.strip()
        if v.startswith(("'", '"')):
            q = v[0]
            if len(v) < 2 or not v.endswith(q):
                raise SystemExit(f"INVALID_ENV_FLAG_SYNTAX:{target}:line={lineno}")
            v = v[1:-1]
        elif "#" in v:
            v = v.split("#", 1)[0].strip()
        if "${" in v or "$(" in v or "`" in v:
            raise SystemExit(f"INVALID_ENV_FLAG_SYNTAX:{target}:line={lineno}")
        norm = v.lower() if v else "false"
        if norm not in ("true", "false"):
            raise SystemExit(f"INVALID_ENV_FLAG_VALUE:{target}:line={lineno}")
        val = norm
print(val)
PY
}

capture_runtime_flags_snapshot() {
  local app_ids cid env_json parsed
  app_ids="$(resolve_app_containers)" || return 1
  while IFS= read -r cid; do
    [[ -n "$cid" ]] || continue
    env_json="$(run_bounded "$CMD_TIMEOUT_SEC" docker inspect --format '{{json .Config.Env}}' "$cid")" \
      || fail "RUNTIME_FLAG_INSPECT_FAILED:${cid}"
    parsed="$("$PYTHON_BIN" - "$cid" "$env_json" <<'PY'
import json, sys
cid = sys.argv[1]
env_list = json.loads(sys.argv[2]) or []
allow = {}
for item in env_list:
    if not isinstance(item, str) or "=" not in item:
        continue
    k, v = item.split("=", 1)
    if k in ("COLLECTOR_ENABLED", "ALERTS_ENABLED"):
        allow[k] = v.strip().lower()
for req in ("COLLECTOR_ENABLED", "ALERTS_ENABLED"):
    if req not in allow or allow[req] not in ("true", "false"):
        raise SystemExit(f"RUNTIME_FLAG_UNVERIFIED:{cid}:{req}")
print(f"APP[{cid}]:COLLECTOR_ENABLED={allow['COLLECTOR_ENABLED']}:ALERTS_ENABLED={allow['ALERTS_ENABLED']}")
PY
)" || fail "$parsed"
    printf '%s\n' "$parsed"
  done <<< "$app_ids"
}

capture_flags_snapshot() {
  local flag_col flag_al rt_lines
  flag_col="$(read_env_flag "COLLECTOR_ENABLED")" || return 1
  flag_al="$(read_env_flag "ALERTS_ENABLED")" || return 1
  rt_lines="$(capture_runtime_flags_snapshot)" || return 1
  printf 'FILE:COLLECTOR_ENABLED=%s\nFILE:ALERTS_ENABLED=%s\n%s\n' "$flag_col" "$flag_al" "$rt_lines"
}

verify_all_flags_false() {
  local snap
  snap="$(capture_flags_snapshot)" || return 1
  "$PYTHON_BIN" - "$snap" <<'PY' || return 1
import sys
lines = [l.strip() for l in sys.argv[1].splitlines() if l.strip()]
file_flags = {}
app_count = 0
for line in lines:
    if line.startswith("FILE:"):
        k, v = line[5:].split("=", 1)
        file_flags[k] = v
        if v != "false":
            raise SystemExit(f"ENV_FILE_FLAG_NOT_FALSE:{k}={v}")
    elif line.startswith("APP["):
        app_count += 1
        parts = line.split(":")
        c_val = parts[1].split("=", 1)[1]
        a_val = parts[2].split("=", 1)[1]
        if c_val != "false" or a_val != "false":
            raise SystemExit(f"RUNTIME_FLAG_NOT_FALSE:{line}")
if app_count == 0:
    raise SystemExit("NO_RUNNING_APP_CONTAINERS")
if file_flags.get("COLLECTOR_ENABLED") != "false" or file_flags.get("ALERTS_ENABLED") != "false":
    raise SystemExit("ENV_FILE_FLAGS_NOT_VERIFIED")
PY
  printf '%s\n' "$snap"
}

# R1: Uses only real Flyway V1/V3 columns (posting has first_seen_at/last_seen_at/last_changed_at, never updated_at).
capture_db_snapshot() {
  run_bounded "$CMD_TIMEOUT_SEC" docker exec "$PG_CONTAINER" sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1 -qtAc "
    SELECT '\''posting_count='\'' || count(*)::text FROM posting;
    SELECT '\''posting_md5='\'' || coalesce(md5(string_agg(
      sn::text || '\'':'\'' || status || '\'':'\'' || content_hash || '\'':'\'' ||
      first_seen_at::text || '\'':'\'' || last_seen_at::text || '\'':'\'' ||
      coalesce(last_changed_at::text, '\'''\'') || '\'':'\'' || detail_status || '\'':'\'' ||
      coalesce(detail_collected_at::text, '\'''\''), '\'','\'' ORDER BY sn)), '\''empty'\'') FROM posting;
    SELECT '\''collect_run='\'' || count(*)::text || '\'':'\'' || coalesce(max(id), 0)::text FROM collect_run;
    SELECT '\''api_attempt='\'' || count(*)::text || '\'':'\'' || coalesce(max(id), 0)::text FROM api_attempt;
    SELECT '\''api_budget='\'' || coalesce(string_agg(day::text || '\''='\'' || count::text, '\'','\'' ORDER BY day), '\''empty'\'') FROM api_budget;
  "'
}

capture_containers_snapshot() {
  local out
  out="$(run_bounded "$CMD_TIMEOUT_SEC" docker ps --filter "label=com.docker.compose.project=${PROJECT_NAME}" \
    --format '{{.Names}}|{{.Image}}|{{.ID}}' | sort)"
  if [[ -n "$out" ]]; then
    printf '%s\n' "$out"
  else
    run_bounded "$CMD_TIMEOUT_SEC" docker inspect --format '{{.Name}}|{{.Config.Image}}|{{.Id}}' "$PROM_CONTAINER" "$PG_CONTAINER" | sort
  fi
}

inspect_prom_mounts() {
  local mounts_json mount_kind host_prom_sha cont_prom_sha host_rules_sha cont_rules_sha
  mounts_json="$(run_bounded "$CMD_TIMEOUT_SEC" docker inspect --format '{{json .Mounts}}' "$PROM_CONTAINER")"
  mount_kind="$("$PYTHON_BIN" - "$mounts_json" <<'PY'
import json, sys
mounts = json.loads(sys.argv[1])
dests = {m.get("Destination"): m for m in mounts}
if "/etc/prometheus/prometheus.yml" in dests:
    print("single-file")
elif "/etc/prometheus" in dests:
    print("directory")
else:
    print("unknown")
PY
)"
  [[ "$mount_kind" != "unknown" ]] || fail "MOUNT_UNEXPECTED: Prometheus has neither /etc/prometheus/prometheus.yml nor /etc/prometheus mount"
  host_prom_sha="$(host_sha256 "$PROM_CONFIG")"
  cont_prom_sha="$(run_bounded "$CMD_TIMEOUT_SEC" docker exec "$PROM_CONTAINER" sha256sum /etc/prometheus/prometheus.yml | awk '{print $1}')"
  host_rules_sha="$(host_sha256 "$RULES_FILE")"
  cont_rules_sha="$(run_bounded "$CMD_TIMEOUT_SEC" docker exec "$PROM_CONTAINER" sha256sum /etc/prometheus/rules/gongchae.yml | awk '{print $1}')"

  log "Mount inspection: kind=${mount_kind} host_prom_sha=${host_prom_sha} cont_prom_sha=${cont_prom_sha}" >&2
  if [[ "$host_rules_sha" != "$cont_rules_sha" ]]; then
    fail "RULES_MOUNT_MISMATCH: host=${host_rules_sha} container=${cont_rules_sha}"
  fi

  if [[ "$mount_kind" == "single-file" ]]; then
    local stale=0 host_inode="" cont_inode=""
    if [[ "$host_prom_sha" != "$cont_prom_sha" ]]; then
      stale=1
    fi
    if [[ "$(uname -s)" == "Linux" ]]; then
      host_inode="$(stat -c %i "$PROM_CONFIG")"
      cont_inode="$(run_bounded "$CMD_TIMEOUT_SEC" docker exec "$PROM_CONTAINER" stat -c %i /etc/prometheus/prometheus.yml)"
      log "Single-file bind mount inodes: host=${host_inode} container=${cont_inode}" >&2
      if [[ "$host_inode" != "$cont_inode" ]]; then
        stale=1
      fi
    fi
    if [[ "$stale" -eq 1 ]]; then
      echo "MOUNT_STALE_SINGLE_FILE"
      return 10
    fi
  else
    if [[ "$host_prom_sha" != "$cont_prom_sha" ]]; then
      fail "DIR_MOUNT_SHA_MISMATCH: host=${host_prom_sha} container=${cont_prom_sha}"
    fi
  fi
  echo "MOUNT_OK:${mount_kind}"
  return 0
}

wait_prom_ready() {
  local deadline=$(( $(date +%s) + 60 ))
  while [[ $(date +%s) -lt $deadline ]]; do
    if run_bounded 10 docker exec "$PROM_CONTAINER" wget -T 5 -qO- "http://localhost:9090/-/ready" >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  return 1
}

require_expected_hashes_for_op() {
  if ! is_valid_sha256 "$EXPECTED_PROM_SHA256"; then
    fail "EXPECTED_SHA_REQUIRED: valid 64-hex --expected-prom-sha256 is mandatory"
  fi
  if ! is_valid_sha256 "$EXPECTED_RULES_SHA256"; then
    fail "EXPECTED_SHA_REQUIRED: valid 64-hex --expected-rules-sha256 is mandatory"
  fi
}

verify_expected_hashes() {
  local prom_sha rules_sha
  prom_sha="$(host_sha256 "$PROM_CONFIG")"
  rules_sha="$(host_sha256 "$RULES_FILE")"
  if [[ "$prom_sha" != "$EXPECTED_PROM_SHA256" ]]; then
    printf 'PROM_SHA_MISMATCH expected=%s actual=%s\n' "$EXPECTED_PROM_SHA256" "$prom_sha" >&2
    return 1
  fi
  if [[ "$rules_sha" != "$EXPECTED_RULES_SHA256" ]]; then
    printf 'RULES_SHA_MISMATCH expected=%s actual=%s\n' "$EXPECTED_RULES_SHA256" "$rules_sha" >&2
    return 1
  fi
  return 0
}

# Returns: 0 if any gongchae-drill series has value > 0, 1 if clean (valid empty or all <= 0 vector), 2 on query/parse/schema error.
prom_has_nonzero_drill_series() {
  local q1 q2 py_rc=0
  q1="$(prom_api_get "/api/v1/query?query=collector_api_attempts_today%7Bjob%3D%22gongchae-drill%22%7D" 2>/dev/null)" || return 2
  q2="$(prom_api_get "/api/v1/query?query=collector_regular_incomplete_consecutive%7Bjob%3D%22gongchae-drill%22%7D" 2>/dev/null)" || return 2
  "$PYTHON_BIN" - "$q1" "$q2" <<'PY' || py_rc=$?
import json, math, sys

try:
    has_positive = False
    for raw in sys.argv[1:]:
        data = json.loads(raw)
        if not isinstance(data, dict) or data.get("status") != "success":
            raise SystemExit(2)
        payload = data.get("data")
        if not isinstance(payload, dict):
            raise SystemExit(2)
        if payload.get("resultType") != "vector" or "result" not in payload:
            raise SystemExit(2)
        results = payload["result"]
        if not isinstance(results, list):
            raise SystemExit(2)
        for item in results:
            if not isinstance(item, dict) or "value" not in item:
                raise SystemExit(2)
            pair = item["value"]
            if not isinstance(pair, list) or len(pair) != 2:
                raise SystemExit(2)
            ts, raw_val = pair[0], pair[1]
            if isinstance(ts, bool) or not isinstance(ts, (int, float)) or not math.isfinite(float(ts)):
                raise SystemExit(2)
            if isinstance(raw_val, bool) or not isinstance(raw_val, (str, int, float)):
                raise SystemExit(2)
            val = float(raw_val)
            if not math.isfinite(val):
                raise SystemExit(2)
            if val > 0.0:
                has_positive = True
    raise SystemExit(0 if has_positive else 1)
except SystemExit:
    raise
except Exception:
    raise SystemExit(2)
PY
  if [[ "$py_rc" -eq 0 || "$py_rc" -eq 1 ]]; then
    return "$py_rc"
  fi
  return 2
}

wait_for_drill_zero_baseline() {
  local timeout_sec="${1:-60}"
  local deadline=$(( $(date +%s) + timeout_sec ))
  while [[ $(date +%s) -lt $deadline ]]; do
    local q1 q2
    q1="$(prom_api_get "/api/v1/query?query=collector_api_attempts_today%7Bjob%3D%22gongchae-drill%22%7D" 2>/dev/null || true)"
    q2="$(prom_api_get "/api/v1/query?query=collector_regular_incomplete_consecutive%7Bjob%3D%22gongchae-drill%22%7D" 2>/dev/null || true)"
    if [[ -n "$q1" && -n "$q2" ]] && "$PYTHON_BIN" - "$q1" "$q2" <<'PY'
import json, sys
for raw in sys.argv[1:]:
    data = json.loads(raw)
    if data.get("status") != "success":
        raise SystemExit(1)
    res = data.get("data", {}).get("result", [])
    if len(res) != 1 or float(res[0]["value"][1]) != 0.0:
        raise SystemExit(1)
raise SystemExit(0)
PY
    then
      return 0
    fi
    sleep 2
  done
  return 1
}

prom_rule_group_last_eval() {
  local rules_json
  rules_json="$(prom_api_get "/api/v1/rules?type=alert" 2>/dev/null)" || return 1
  "$PYTHON_BIN" - "$rules_json" <<'PY'
import json, sys
data = json.loads(sys.argv[1])
if data.get("status") != "success":
    raise SystemExit(1)
for g in data.get("data", {}).get("groups", []):
    if g.get("name") == "gongchae":
        print(g.get("lastEvaluation", ""))
        raise SystemExit(0)
raise SystemExit(1)
PY
}

wait_for_prom_rule_eval_after() {
  local prev_eval="$1"
  local timeout_sec="${2:-60}"
  local deadline=$(( $(date +%s) + timeout_sec ))
  while [[ $(date +%s) -lt $deadline ]]; do
    local cur_eval
    cur_eval="$(prom_rule_group_last_eval 2>/dev/null || true)"
    if [[ -n "$cur_eval" && "$cur_eval" != 0001-01-01* && "$cur_eval" != "$prev_eval" ]]; then
      return 0
    fi
    sleep 2
  done
  return 1
}

check_prom_clean_baseline() {
  local prom_alerts_json targets_json config_json
  prom_alerts_json="$(prom_api_get "/api/v1/alerts")" || return 1
  targets_json="$(prom_api_get "/api/v1/targets?state=active")" || return 1
  config_json="$(prom_api_get "/api/v1/status/config")" || return 1

  "$PYTHON_BIN" - "$prom_alerts_json" "$targets_json" "$config_json" <<'PY' || return 1
import json, sys

try:
    alerts_doc = json.loads(sys.argv[1])
    targets_doc = json.loads(sys.argv[2])
    cfg_doc = json.loads(sys.argv[3])
    if (
        not isinstance(alerts_doc, dict) or alerts_doc.get("status") != "success"
        or not isinstance(targets_doc, dict) or targets_doc.get("status") != "success"
        or not isinstance(cfg_doc, dict) or cfg_doc.get("status") != "success"
    ):
        raise SystemExit("PROM_BASELINE_API_STATUS_NOT_SUCCESS")
    items = alerts_doc["data"]["alerts"]
    targets = targets_doc["data"]["activeTargets"]
    cfg = cfg_doc["data"]["yaml"]
    if not isinstance(items, list) or not isinstance(targets, list) or not isinstance(cfg, str):
        raise SystemExit("PROM_BASELINE_API_MALFORMED_SCHEMA")
except SystemExit:
    raise
except Exception as exc:
    raise SystemExit(f"PROM_BASELINE_API_PARSE_ERROR: {exc}")

if items:
    names = [(a.get("labels", {}).get("alertname"), a.get("state")) for a in items]
    raise SystemExit(f"EXISTING_ALERTS_NONEMPTY: {names}")

if "gongchae-drill" in cfg:
    raise SystemExit("DRILL_JOB_ALREADY_IN_PROM_CONFIG")

drill_targets = [
    t for t in targets
    if t.get("labels", {}).get("job") == "gongchae-drill" or "drill-exporter" in t.get("scrapeUrl", "")
]
if drill_targets:
    raise SystemExit("DRILL_TARGET_ALREADY_ACTIVE")

app_up = [t for t in targets if t.get("labels", {}).get("job") == "gongchae-app" and t.get("health") == "up"]
if not app_up:
    raise SystemExit("NO_ACTIVE_HEALTHY_GONGCHAE_APP_TARGET")

print("PROM_BASELINE_OK active_app=" + ",".join(t.get("labels", {}).get("instance", "?") for t in app_up))
PY
  local nz_rc=0
  prom_has_nonzero_drill_series || nz_rc=$?
  if [[ "$nz_rc" -eq 0 ]]; then
    printf 'LINGERING_DRILL_SERIES_NONZERO: job="gongchae-drill" still has >0 samples in Prometheus TSDB\n' >&2
    return 1
  elif [[ "$nz_rc" -ne 1 ]]; then
    printf 'PROM_DRILL_SERIES_CHECK_FAILED: unable to query gongchae-drill TSDB baseline (rc=%s)\n' "$nz_rc" >&2
    return 1
  fi
  return 0
}

preflight_checks() {
  require_cmd docker
  require_cmd "$PYTHON_BIN"
  require_cmd sha256sum
  require_cmd stat
  require_cmd timeout

  require_expected_hashes_for_op

  [[ -f "$ENV_FILE" ]] || fail "Env file not found: $ENV_FILE"
  [[ -f "$PROM_CONFIG" ]] || fail "Prometheus config not found: $PROM_CONFIG"
  [[ -f "$RULES_FILE" ]] || fail "Rules file not found: $RULES_FILE"

  # Check both deploy-root owner file and backup-dir state so different backup-dirs cannot bypass an unrestored run.
  if [[ -f "$OWNER_FILE" ]]; then
    local root_state
    root_state="$(read_meta_key "$OWNER_FILE" "STATE" || echo "UNKNOWN")"
    if [[ "$root_state" != "RESTORED" ]]; then
      fail "PREVIOUS_DRILL_NOT_RESTORED: ${OWNER_FILE} STATE=${root_state}; run restore first"
    fi
  fi
  if [[ -f "${BACKUP_DIR}/state" ]] && [[ "$(cat "${BACKUP_DIR}/state")" != "RESTORED" ]]; then
    fail "PREVIOUS_DRILL_NOT_RESTORED: ${BACKUP_DIR}/state is $(cat "${BACKUP_DIR}/state"); run restore first"
  fi

  local exp_rc=0
  exporter_container_exists || exp_rc=$?
  if [[ "$exp_rc" -eq 0 ]]; then
    fail "STALE_EXPORTER_EXISTS: container ${EXPORTER_NAME} already exists"
  elif [[ "$exp_rc" -ne 1 ]]; then
    fail "DOCKER_PS_FAILED: cannot inspect containers for ${EXPORTER_NAME} (rc=${exp_rc})"
  fi

  if grep -q 'job_name: gongchae-drill' "$PROM_CONFIG"; then
    fail "DRILL_JOB_ALREADY_IN_FILE: ${PROM_CONFIG}"
  fi
  if grep -q '\[훈련\]' "$RULES_FILE"; then
    fail "DRILL_MARKER_ALREADY_IN_RULES: ${RULES_FILE}"
  fi

  verify_expected_hashes || fail "EXPECTED_GIT_SHA_MISMATCH"

  resolve_containers

  local flags_snap
  flags_snap="$(verify_all_flags_false 2>&1)" || fail "$flags_snap"
  log "Flags verified false (file + runtime): $(printf '%s' "$flags_snap" | tr '\n' ' ')"

  local mount_status mount_rc=0
  mount_status="$(inspect_prom_mounts)" || mount_rc=$?
  if [[ "$mount_rc" -eq 10 ]]; then
    if [[ "$ACTION" == "run" && "$ALLOW_RECREATE_PROMETHEUS" == "true" ]]; then
      log "Stale single-file bind mount detected; recreating Prometheus via opt-in bounded command (timeout=${RECREATE_TIMEOUT_SEC}s, kill_after=${CMD_KILL_AFTER_SEC}s)..."
      run_bounded "$RECREATE_TIMEOUT_SEC" bash -c "$RECREATE_CMD" \
        || fail "RECREATE_PROMETHEUS_FAILED: opt-in recreate command failed or timed out"
      PROM_CONTAINER=""
      resolve_containers
      wait_prom_ready || fail "Prometheus failed to become ready after recreate"
      mount_status="$(inspect_prom_mounts)" || fail "Prometheus mount still stale after recreate"
    else
      fail "MOUNT_STALE_SINGLE_FILE: host and container prometheus.yml differ/detached (recreate required: ${RECREATE_CMD})"
    fi
  elif [[ "$mount_rc" -ne 0 ]]; then
    fail "Mount inspection failed (rc=${mount_rc})"
  fi
  log "Mount status: ${mount_status}"

  local prom_check
  prom_check="$(check_prom_clean_baseline 2>&1)" || fail "$prom_check"
  log "$prom_check"

  local db_snap
  db_snap="$(capture_db_snapshot)"
  log "DB baseline captured ($(printf '%s' "$db_snap" | tr '\n' ' '))"
}

verify_worker_attempt_active() {
  if [[ "$ACTION" != "_worker" ]]; then
    return 0
  fi
  if [[ -z "$RUN_ID" || -z "$ATTEMPT_DIR" ]]; then
    fail "WORKER_IDENTITY_MISSING: refusing to mutate metrics without RUN_ID/ATTEMPT_DIR"
  fi
  if [[ -n "$WORKER_REVOKED_FILE" && -f "$WORKER_REVOKED_FILE" ]]; then
    fail "WORKER_REVOKED: revocation marker present (${WORKER_REVOKED_FILE})"
  fi
  if [[ -f "${ATTEMPT_DIR}/revoked" ]]; then
    fail "WORKER_REVOKED: attempt revocation marker present (${ATTEMPT_DIR}/revoked)"
  fi
  if [[ ! -d "$ATTEMPT_DIR" || ! -f "${ATTEMPT_DIR}/run_id" ]]; then
    fail "WORKER_ATTEMPT_GONE: attempt directory missing (${ATTEMPT_DIR})"
  fi
  if [[ "$(cat "${ATTEMPT_DIR}/run_id" 2>/dev/null || true)" != "$RUN_ID" ]]; then
    fail "WORKER_RUN_ID_MISMATCH: attempt run_id mismatch"
  fi
  if [[ -f "$OWNER_FILE" ]]; then
    local owner_run owner_state
    owner_run="$(read_meta_key "$OWNER_FILE" "RUN_ID" || true)"
    owner_state="$(read_meta_key "$OWNER_FILE" "STATE" || true)"
    if [[ "$owner_run" != "$RUN_ID" || "$owner_state" != "ARMED" ]]; then
      fail "WORKER_OWNERSHIP_LOST: owner run_id=${owner_run} state=${owner_state} expected=${RUN_ID}/ARMED"
    fi
  else
    fail "WORKER_OWNERSHIP_LOST: missing ${OWNER_FILE}"
  fi
  if [[ -n "$SUPERVISOR_PID" ]]; then
    if ! is_verified_supervisor_pid "$SUPERVISOR_PID" "$SUPERVISOR_START_TIME"; then
      fail "WORKER_SUPERVISOR_GONE: supervisor pid=${SUPERVISOR_PID} no longer active"
    fi
  fi
}

write_metrics() {
  local budget="$1"
  local failing="$2"
  verify_worker_attempt_active
  [[ -n "$ATTEMPT_DIR" && -d "$ATTEMPT_DIR" ]] || fail "WORKER_ATTEMPT_GONE: missing ${ATTEMPT_DIR}"
  printf '# TYPE collector_api_attempts_today gauge\ncollector_api_attempts_today %s\n# TYPE collector_regular_incomplete_consecutive gauge\ncollector_regular_incomplete_consecutive %s\n' \
    "$budget" "$failing" > "${ATTEMPT_DIR}/metrics.prom"
}

prepare_drill_files() {
  mkdir -p "${ATTEMPT_DIR}/rules_stage"
  chmod 0700 "$WORK_DIR" "${WORK_DIR}/runs" "$ATTEMPT_DIR"
  rm -f "${ATTEMPT_DIR}/revoked"
  printf '%s\n' "$RUN_ID" > "${ATTEMPT_DIR}/run_id"

  cat > "${ATTEMPT_DIR}/Caddyfile" <<'EOF'
:8081 {
	handle /actuator/prometheus {
		header Content-Type "text/plain; version=0.0.4; charset=utf-8"
		root * /srv
		rewrite * /metrics.prom
		file_server
	}
}
EOF

  write_metrics 0 0

  "$PYTHON_BIN" - "$(docker_host_path "${BACKUP_DIR}/prometheus.yml")" "$(docker_host_path "${ATTEMPT_DIR}/prometheus.drill.yml")" <<'PY'
import sys
src, dst = sys.argv[1], sys.argv[2]
text = open(src, "r", encoding="utf-8").read()
if "job_name: gongchae-drill" in text:
    raise SystemExit("gongchae-drill already present")
if "job_name: gongchae-app" not in text:
    raise SystemExit("gongchae-app job missing in prometheus.yml")
addition = (
    "\n"
    "  - job_name: gongchae-drill\n"
    "    metrics_path: /actuator/prometheus\n"
    "    static_configs:\n"
    "      - targets: [\"drill-exporter:8081\"]\n"
)
out = text.rstrip("\n") + "\n" + addition
open(dst, "w", encoding="utf-8", newline="\n").write(out)
assert "job_name: gongchae-drill" in out
assert text.rstrip("\n") in out
PY

  "$PYTHON_BIN" - "$(docker_host_path "${BACKUP_DIR}/gongchae.yml")" "$(docker_host_path "${ATTEMPT_DIR}/rules_stage/gongchae.yml")" <<'PY'
import sys
src, dst = sys.argv[1], sys.argv[2]
orig_lines = open(src, "r", encoding="utf-8").read().splitlines(keepends=True)
targets = {"GongchaeApiBudgetWarning", "GongchaeRegularCollectionFailing"}
current_alert = None
out_lines = []
changed = 0

for line in orig_lines:
    stripped = line.strip()
    if stripped.startswith("- alert:"):
        current_alert = stripped.split(":", 1)[1].strip()
    if current_alert in targets and stripped.startswith("summary:"):
        indent = line[:len(line) - len(line.lstrip())]
        val = stripped.split(":", 1)[1].strip().strip('"').strip("'")
        newline = "\n" if line.endswith("\n") else ""
        out_lines.append(f'{indent}summary: "[훈련] {val}"{newline}')
        changed += 1
    else:
        out_lines.append(line)

if changed != 2:
    raise SystemExit(f"Expected exactly 2 summary changes, got {changed}")

orig_expr_for = [l for l in orig_lines if l.strip().startswith(("expr:", "for:"))]
new_expr_for = [l for l in out_lines if l.strip().startswith(("expr:", "for:"))]
if orig_expr_for != new_expr_for:
    raise SystemExit("RULE_EXPR_OR_FOR_CHANGED")

joined = "".join(out_lines)
if "for: 10m" not in joined:
    raise SystemExit("FOR_10M_MISSING")

open(dst, "w", encoding="utf-8", newline="\n").write(joined)
PY

  DRILL_PROM_SHA256="$(host_sha256 "${ATTEMPT_DIR}/prometheus.drill.yml")"
  DRILL_RULES_SHA256="$(host_sha256 "${ATTEMPT_DIR}/rules_stage/gongchae.yml")"

  # Validate modified config and rules with promtool before touching live files.
  run_bounded 30 docker run --rm \
    -v "$(docker_host_path "${ATTEMPT_DIR}/prometheus.drill.yml"):/etc/prometheus/prometheus.yml:ro" \
    -v "$(docker_host_path "${ATTEMPT_DIR}/rules_stage"):/etc/prometheus/rules:ro" \
    --entrypoint promtool "$PROM_IMAGE" \
    check config /etc/prometheus/prometheus.yml >/dev/null
  log "promtool check config/rules passed for staged drill files"
}

wait_for_drill_config_and_target() {
  local mode="$1" # present | absent
  local timeout_sec="${2:-90}"
  local deadline=$(( $(date +%s) + timeout_sec ))
  while [[ $(date +%s) -lt $deadline ]]; do
    local cfg targets
    cfg="$(prom_api_get "/api/v1/status/config" 2>/dev/null || true)"
    targets="$(prom_api_get "/api/v1/targets?state=active" 2>/dev/null || true)"
    if [[ -n "$cfg" && -n "$targets" ]] && "$PYTHON_BIN" - "$mode" "$cfg" "$targets" <<'PY'
import json, sys
mode = sys.argv[1]
cfg = json.loads(sys.argv[2])["data"]["yaml"]
targets = json.loads(sys.argv[3])["data"]["activeTargets"]
drill_targets = [t for t in targets if t.get("labels", {}).get("job") == "gongchae-drill"]
app_up = [t for t in targets if t.get("labels", {}).get("job") == "gongchae-app" and t.get("health") == "up"]

if mode == "present":
    ok = ("job_name: gongchae-drill" in cfg) and len(drill_targets) == 1 and drill_targets[0].get("health") == "up" and not drill_targets[0].get("lastError") and len(app_up) >= 1
else:
    ok = ("job_name: gongchae-drill" not in cfg) and len(drill_targets) == 0
raise SystemExit(0 if ok else 1)
PY
    then
      return 0
    fi
    sleep 2
  done
  return 1
}

wait_for_alert_state() {
  local alert_name="$1"
  local expected_state="$2" # firing | pending | absent
  local timeout_sec="$3"
  local expect_drill_prefix="${4:-true}"
  local deadline=$(( $(date +%s) + timeout_sec ))
  while [[ $(date +%s) -lt $deadline ]]; do
    local prom_alerts_json
    prom_alerts_json="$(prom_api_get "/api/v1/alerts" 2>/dev/null || true)"
    if [[ -n "$prom_alerts_json" ]] && "$PYTHON_BIN" - "$prom_alerts_json" "$alert_name" "$expected_state" "$expect_drill_prefix" <<'PY'
import json, sys
items = json.loads(sys.argv[1])["data"]["alerts"]
name, want, prefix = sys.argv[2], sys.argv[3], sys.argv[4] == "true"
matched = [a for a in items if a["labels"].get("alertname") == name]
if want == "absent":
    raise SystemExit(0 if not matched else 1)
if len(matched) != 1:
    raise SystemExit(1)
a = matched[0]
if a.get("state") != want:
    raise SystemExit(1)
if prefix and not a["labels"].get("summary", "").startswith("[훈련] "):
    raise SystemExit(1)
raise SystemExit(0)
PY
    then
      return 0
    fi
    sleep 5
  done
  return 1
}

assert_no_unexpected_alerts() {
  local allowed_csv="$1"
  local prom_alerts_json
  prom_alerts_json="$(prom_api_get "/api/v1/alerts")" || return 1
  "$PYTHON_BIN" - "$prom_alerts_json" "$allowed_csv" <<'PY'
import json, sys
items = json.loads(sys.argv[1])["data"]["alerts"]
allowed = {x for x in sys.argv[2].split(",") if x}
unexpected = [
    (a["labels"].get("alertname"), a.get("state"))
    for a in items
    if a["labels"].get("alertname") not in allowed
]
if unexpected:
    raise SystemExit(f"UNEXPECTED_ALERTS: {unexpected}")
PY
}

load_meta_for_restore() {
  local meta_file="${BACKUP_DIR}/meta.env"
  if [[ ! -f "$meta_file" ]]; then
    printf '%s [drill] DRILL_RESTORE_FAILED missing %s\n' "$(date -u +'%Y-%m-%dT%H:%M:%SZ')" "$meta_file" >&2
    return 1
  fi
  local m_root m_prom m_rules m_dprom m_drules m_exp m_work m_run
  local m_attempt m_spid m_sstart m_wpid m_wstart m_wlock m_wrev
  m_run="$(read_meta_key "$meta_file" "RUN_ID")" || return 1
  m_root="$(read_meta_key "$meta_file" "ROOT_DIR")" || return 1
  m_prom="$(read_meta_key "$meta_file" "EXPECTED_PROM_SHA256")" || return 1
  m_rules="$(read_meta_key "$meta_file" "EXPECTED_RULES_SHA256")" || return 1
  m_dprom="$(read_meta_key "$meta_file" "DRILL_PROM_SHA256")" || return 1
  m_drules="$(read_meta_key "$meta_file" "DRILL_RULES_SHA256")" || return 1
  m_exp="$(read_meta_key "$meta_file" "EXPORTER_NAME")" || return 1
  m_work="$(read_meta_key "$meta_file" "WORK_DIR")" || return 1
  m_attempt="$(read_meta_key "$meta_file" "ATTEMPT_DIR" 2>/dev/null || true)"
  m_spid="$(read_meta_key "$meta_file" "SUPERVISOR_PID" 2>/dev/null || true)"
  m_sstart="$(read_meta_key "$meta_file" "SUPERVISOR_START_TIME" 2>/dev/null || true)"
  m_wpid="$(read_meta_key "$meta_file" "WORKER_PID" 2>/dev/null || true)"
  m_wstart="$(read_meta_key "$meta_file" "WORKER_START_TIME" 2>/dev/null || true)"
  m_wlock="$(read_meta_key "$meta_file" "WORKER_LOCK_FILE" 2>/dev/null || true)"
  m_wrev="$(read_meta_key "$meta_file" "WORKER_REVOKED_FILE" 2>/dev/null || true)"

  if [[ "$m_root" != "$ROOT_DIR" ]]; then
    printf 'DRILL_RESTORE_FAILED backup ROOT_DIR=%s does not match current ROOT_DIR=%s\n' "$m_root" "$ROOT_DIR" >&2
    return 1
  fi
  if [[ -n "$EXPECTED_PROM_SHA256" && "$EXPECTED_PROM_SHA256" != "$m_prom" ]]; then
    printf 'DRILL_RESTORE_FAILED CLI expected prom SHA (%s) != backup meta SHA (%s)\n' "$EXPECTED_PROM_SHA256" "$m_prom" >&2
    return 1
  fi
  if [[ -n "$EXPECTED_RULES_SHA256" && "$EXPECTED_RULES_SHA256" != "$m_rules" ]]; then
    printf 'DRILL_RESTORE_FAILED CLI expected rules SHA (%s) != backup meta SHA (%s)\n' "$EXPECTED_RULES_SHA256" "$m_rules" >&2
    return 1
  fi

  RUN_ID="$m_run"
  EXPECTED_PROM_SHA256="$m_prom"
  EXPECTED_RULES_SHA256="$m_rules"
  DRILL_PROM_SHA256="$m_dprom"
  DRILL_RULES_SHA256="$m_drules"
  EXPORTER_NAME="$m_exp"
  WORK_DIR="$m_work"
  ATTEMPT_DIR="${m_attempt:-${m_work}/runs/${m_run}}"
  SUPERVISOR_PID="$m_spid"
  SUPERVISOR_START_TIME="$m_sstart"
  WORKER_PID="$m_wpid"
  WORKER_START_TIME="$m_wstart"
  WORKER_LOCK_FILE="${m_wlock:-${STATE_DIR}/worker-${m_run}.lock}"
  WORKER_REVOKED_FILE="${m_wrev:-${STATE_DIR}/worker-${m_run}.revoked}"
  return 0
}

fence_and_terminate_worker() {
  # 1) Persist revocation markers before signaling so any awake worker immediately refuses writes.
  if [[ -n "$WORKER_REVOKED_FILE" ]]; then
    mkdir -p "$(dirname "$WORKER_REVOKED_FILE")" 2>/dev/null || true
    printf '%s\n' "${RUN_ID:-revoked}" > "$WORKER_REVOKED_FILE" 2>/dev/null || true
  fi
  if [[ -n "$ATTEMPT_DIR" && -d "$ATTEMPT_DIR" ]]; then
    printf '%s\n' "${RUN_ID:-revoked}" > "${ATTEMPT_DIR}/revoked" 2>/dev/null || true
  fi

  # 2) Only signal WORKER_PID if its /proc cmdline (_worker --run-id <RUN_ID>) and starttime match.
  #    Never signal an unrelated process that reused WORKER_PID!
  if [[ -n "$WORKER_PID" ]]; then
    if is_verified_worker_pid "$WORKER_PID" "$RUN_ID" "$WORKER_START_TIME"; then
      log "Fencing verified worker pid=${WORKER_PID} run_id=${RUN_ID}..."
      local cpid i
      while IFS= read -r cpid; do
        [[ -n "$cpid" ]] || continue
        kill -TERM "$cpid" 2>/dev/null || true
      done < <(list_descendant_pids "$WORKER_PID")
      if is_verified_worker_pid "$WORKER_PID" "$RUN_ID" "$WORKER_START_TIME"; then
        kill -TERM "$WORKER_PID" 2>/dev/null || true
      fi
      for i in 1 2 3 4 5; do
        is_verified_worker_pid "$WORKER_PID" "$RUN_ID" "$WORKER_START_TIME" || break
        sleep 1
      done
      if is_verified_worker_pid "$WORKER_PID" "$RUN_ID" "$WORKER_START_TIME"; then
        log "Verified worker pid=${WORKER_PID} did not exit after SIGTERM; sending SIGKILL"
        while IFS= read -r cpid; do
          [[ -n "$cpid" ]] || continue
          kill -KILL "$cpid" 2>/dev/null || true
        done < <(list_descendant_pids "$WORKER_PID")
        if is_verified_worker_pid "$WORKER_PID" "$RUN_ID" "$WORKER_START_TIME"; then
          kill -KILL "$WORKER_PID" 2>/dev/null || true
        fi
        for i in 1 2 3 4 5; do
          is_verified_worker_pid "$WORKER_PID" "$RUN_ID" "$WORKER_START_TIME" || break
          sleep 1
        done
      fi
      if [[ "$ACTION" == "run" ]]; then
        wait "$WORKER_PID" 2>/dev/null || true
      fi
    else
      log "Recorded worker pid=${WORKER_PID} is no longer active for run_id=${RUN_ID} (already exited or PID reused; not signaling)"
    fi
  fi

  # 3) Confirm neither verified worker PID nor the kernel-enforced worker lease remains active.
  if [[ -n "$WORKER_PID" ]] && is_verified_worker_pid "$WORKER_PID" "$RUN_ID" "$WORKER_START_TIME"; then
    printf 'DRILL_RESTORE_FAILED UNVERIFIED_WORKER_ALIVE verified worker pid=%s still alive for run_id=%s\n' "$WORKER_PID" "$RUN_ID" >&2
    return 1
  fi
  if ! is_worker_lease_free "$WORKER_LOCK_FILE" "$RUN_ID"; then
    printf 'DRILL_RESTORE_FAILED UNVERIFIED_WORKER_ALIVE worker lease %s is still held for run_id=%s (refusing to mark RESTORED)\n' \
      "$WORKER_LOCK_FILE" "$RUN_ID" >&2
    return 1
  fi
  WORKER_PID=""
  WORKER_START_TIME=""
  return 0
}

restore_and_verify() {
  # Mask INT/TERM/HUP throughout restore so repeated signals cannot interrupt in-flight recovery.
  # run_bounded resets these dispositions inside each bounded child subshell.
  trap '' INT TERM HUP

  if [[ "$RESTORE_DONE" -eq 1 ]]; then
    return 0
  fi

  if ! load_meta_for_restore; then
    return 1
  fi

  local cur_state
  cur_state="$(read_meta_key "${BACKUP_DIR}/meta.env" "STATE" || echo "UNKNOWN")"

  # R3: Already-RESTORED backup must NEVER overwrite newer valid production config!
  if [[ "$cur_state" == "RESTORED" ]]; then
    log "Backup state is already RESTORED; running read-only verification without overwriting config files..."
    local nrc=0 exp_rc=0 nz_rc=0
    if ! is_worker_lease_free "$WORKER_LOCK_FILE" "$RUN_ID"; then
      printf 'DRILL_RESTORE_FAILED UNVERIFIED_WORKER_ALIVE worker lease still held on RESTORED state: %s\n' "$WORKER_LOCK_FILE" >&2
      nrc=1
    fi
    exporter_container_exists || exp_rc=$?
    if [[ "$exp_rc" -eq 0 ]]; then
      printf 'DRILL_RESTORE_FAILED owned exporter still exists on RESTORED state: %s\n' "$EXPORTER_NAME" >&2
      nrc=1
    elif [[ "$exp_rc" -ne 1 ]]; then
      printf 'DRILL_RESTORE_FAILED docker ps failed/timed out on RESTORED state check\n' >&2
      nrc=1
    fi
    if grep -q 'job_name: gongchae-drill' "$PROM_CONFIG" 2>/dev/null; then
      printf 'DRILL_RESTORE_FAILED gongchae-drill still in %s on RESTORED state\n' "$PROM_CONFIG" >&2
      nrc=1
    fi
    if grep -q '\[훈련\]' "$RULES_FILE" 2>/dev/null; then
      printf 'DRILL_RESTORE_FAILED [훈련] marker still in %s on RESTORED state\n' "$RULES_FILE" >&2
      nrc=1
    fi
    if resolve_containers 2>/dev/null; then
      wait_for_drill_config_and_target absent 15 || nrc=1
      prom_has_nonzero_drill_series || nz_rc=$?
      if [[ "$nz_rc" -ne 1 ]]; then
        printf 'DRILL_RESTORE_FAILED lingering non-zero gongchae-drill series or query failure on RESTORED state (rc=%s)\n' "$nz_rc" >&2
        nrc=1
      fi
    fi
    if [[ "$nrc" -eq 0 ]]; then
      RESTORE_DONE=1
      log "DRILL_RESTORE_ALREADY_RESTORED_NOOP"
    else
      printf 'DRILL_RESTORE_FAILED read-only verification failed on RESTORED state\n' >&2
    fi
    return "$nrc"
  fi

  local rrc=0 worker_fenced_ok=0
  log "Starting idempotent restore and post-condition verification (run_id=${RUN_ID})..."

  if [[ "$TEST_RESTORE_HOLD_SEC" -gt 0 ]]; then
    log "DRILL_RESTORE_HOLD_STARTED (${TEST_RESTORE_HOLD_SEC}s)"
    sleep "$TEST_RESTORE_HOLD_SEC"
  fi

  if [[ ! -f "${BACKUP_DIR}/prometheus.yml" || ! -f "${BACKUP_DIR}/gongchae.yml" || ! -f "${BACKUP_DIR}/orig.sha256" ]]; then
    printf 'DRILL_RESTORE_FAILED missing backup files in %s\n' "$BACKUP_DIR" >&2
    return 1
  fi

  # 0) Fence and terminate any surviving worker (including after supervisor SIGKILL) before touching resources.
  if fence_and_terminate_worker; then
    worker_fenced_ok=1
  else
    rrc=1
  fi

  # 0b) Unconditionally overwrite metrics.prom to 0/0 FIRST (before querying Prometheus) so no in-flight or
  #     upcoming scrape can ingest stale 500/2 right before exporter removal. Then wait for any already-ingested
  #     non-zero gongchae-drill samples in TSDB to be overwritten by a 0/0 scrape before SIGHUP removes the job.
  if [[ "$worker_fenced_ok" -eq 1 && -n "$ATTEMPT_DIR" && -d "$ATTEMPT_DIR" ]]; then
    printf '# TYPE collector_api_attempts_today gauge\ncollector_api_attempts_today 0\n# TYPE collector_regular_incomplete_consecutive gauge\ncollector_regular_incomplete_consecutive 0\n' \
      > "${ATTEMPT_DIR}/metrics.prom"
    if resolve_containers 2>/dev/null; then
      local flush_exp_rc=0 flush_nz_rc=0
      exporter_container_exists || flush_exp_rc=$?
      if [[ "$flush_exp_rc" -eq 0 ]]; then
        # Brief pause so any scrape that started just before the 0/0 write finishes ingesting before we query TSDB.
        sleep 2
        prom_has_nonzero_drill_series || flush_nz_rc=$?
        if [[ "$flush_nz_rc" -eq 0 ]]; then
          log "Flushing 0/0 to live drill exporter before removing gongchae-drill job..."
          local flush_deadline=$(( $(date +%s) + 75 ))
          while [[ $(date +%s) -lt $flush_deadline ]]; do
            flush_nz_rc=0
            prom_has_nonzero_drill_series || flush_nz_rc=$?
            if [[ "$flush_nz_rc" -eq 1 ]]; then
              log "DRILL_TSDB_FLUSHED_ZERO"
              break
            fi
            sleep 3
          done
        fi
      fi
    fi
  fi

  # 1) Pre-write ownership guard: only overwrite prometheus.yml / gongchae.yml if current hash is THIS run's
  #    original or drill hash. Never clobber an unexpected external deploy/operator update.
  local cur_prom_sha cur_rules_sha prom_touched=0 rules_touched=0
  cur_prom_sha="$(host_sha256 "$PROM_CONFIG")"
  if [[ "$cur_prom_sha" == "$DRILL_PROM_SHA256" ]]; then
    if cat "${BACKUP_DIR}/prometheus.yml" > "$PROM_CONFIG"; then
      prom_touched=1
    else
      printf 'DRILL_RESTORE_FAILED cannot write %s\n' "$PROM_CONFIG" >&2
      rrc=1
    fi
  elif [[ "$cur_prom_sha" == "$EXPECTED_PROM_SHA256" ]]; then
    log "prometheus.yml already matches original SHA-256 (no write needed)"
  else
    printf 'DRILL_RESTORE_FAILED UNEXPECTED_EXTERNAL_CHANGE target=prometheus.yml cur=%s expected=%s drill=%s (preserved without overwriting)\n' \
      "$cur_prom_sha" "$EXPECTED_PROM_SHA256" "$DRILL_PROM_SHA256" >&2
    rrc=1
  fi

  cur_rules_sha="$(host_sha256 "$RULES_FILE")"
  if [[ "$cur_rules_sha" == "$DRILL_RULES_SHA256" ]]; then
    if cat "${BACKUP_DIR}/gongchae.yml" > "$RULES_FILE"; then
      rules_touched=1
    else
      printf 'DRILL_RESTORE_FAILED cannot write %s\n' "$RULES_FILE" >&2
      rrc=1
    fi
  elif [[ "$cur_rules_sha" == "$EXPECTED_RULES_SHA256" ]]; then
    log "rules/gongchae.yml already matches original SHA-256 (no write needed)"
  else
    printf 'DRILL_RESTORE_FAILED UNEXPECTED_EXTERNAL_CHANGE target=gongchae.yml cur=%s expected=%s drill=%s (preserved without overwriting)\n' \
      "$cur_rules_sha" "$EXPECTED_RULES_SHA256" "$DRILL_RULES_SHA256" >&2
    rrc=1
  fi

  # 2) Clean up ONLY resources owned by this run (never glob-delete files in RULES_DIR!).
  local exp_rc=0
  exporter_container_exists || exp_rc=$?
  if [[ "$exp_rc" -eq 0 ]]; then
    run_bounded "$CMD_TIMEOUT_SEC" docker rm -f "$EXPORTER_NAME" >/dev/null || rrc=1
  elif [[ "$exp_rc" -ne 1 ]]; then
    printf 'DRILL_RESTORE_FAILED docker ps failed/timed out before exporter cleanup\n' >&2
    rrc=1
  fi
  exp_rc=0
  exporter_container_exists || exp_rc=$?
  if [[ "$exp_rc" -eq 0 ]]; then
    printf 'DRILL_RESTORE_FAILED exporter container still exists: %s\n' "$EXPORTER_NAME" >&2
    rrc=1
  elif [[ "$exp_rc" -ne 1 ]]; then
    printf 'DRILL_RESTORE_FAILED docker ps failed/timed out after exporter cleanup\n' >&2
    rrc=1
  fi
  if [[ "$worker_fenced_ok" -eq 1 && -n "$ATTEMPT_DIR" && "$ATTEMPT_DIR" != "/" && -d "$ATTEMPT_DIR" ]]; then
    rm -rf "$ATTEMPT_DIR" || rrc=1
    rmdir "${WORK_DIR}/runs" "$WORK_DIR" 2>/dev/null || true
  fi

  # 3) Reload Prometheus, wait for a post-SIGHUP rule evaluation, verify no lingering non-zero drill series,
  #    and verify alerts cleared and active app target healthy.
  if resolve_containers 2>/dev/null; then
    local prev_eval=""
    prev_eval="$(prom_rule_group_last_eval 2>/dev/null || true)"
    prom_sighup || rrc=1
    if ! wait_for_drill_config_and_target absent 60; then
      printf 'DRILL_RESTORE_FAILED gongchae-drill still present in Prometheus config/targets after SIGHUP\n' >&2
      rrc=1
    fi
    if ! wait_for_prom_rule_eval_after "$prev_eval" 60; then
      printf 'DRILL_RESTORE_FAILED Prometheus gongchae rule group did not complete post-SIGHUP evaluation\n' >&2
      rrc=1
    fi
    local post_nz_rc=0
    prom_has_nonzero_drill_series || post_nz_rc=$?
    if [[ "$post_nz_rc" -eq 0 ]]; then
      printf 'DRILL_RESTORE_FAILED LINGERING_DRILL_SERIES_NONZERO job="gongchae-drill" still has >0 samples in Prometheus TSDB\n' >&2
      rrc=1
    elif [[ "$post_nz_rc" -ne 1 ]]; then
      printf 'DRILL_RESTORE_FAILED failed to query Prometheus TSDB for lingering gongchae-drill series\n' >&2
      rrc=1
    fi
    if ! wait_for_alert_state "GongchaeApiBudgetWarning" "absent" "$RESOLVE_WAIT_TIMEOUT_SEC" "false"; then
      printf 'DRILL_RESTORE_FAILED GongchaeApiBudgetWarning still active after restore\n' >&2
      rrc=1
    fi
    if ! wait_for_alert_state "GongchaeRegularCollectionFailing" "absent" "$RESOLVE_WAIT_TIMEOUT_SEC" "false"; then
      printf 'DRILL_RESTORE_FAILED GongchaeRegularCollectionFailing still active after restore\n' >&2
      rrc=1
    fi
    if ! assert_no_unexpected_alerts ""; then
      printf 'DRILL_RESTORE_FAILED firing/pending alerts remain after restore\n' >&2
      rrc=1
    fi
    local post_targets
    post_targets="$(prom_api_get "/api/v1/targets?state=active" 2>/dev/null || true)"
    if [[ -z "$post_targets" ]] || ! "$PYTHON_BIN" - "$post_targets" <<'PY'
import json, sys
targets = json.loads(sys.argv[1])["data"]["activeTargets"]
app_up = [t for t in targets if t.get("labels", {}).get("job") == "gongchae-app" and t.get("health") == "up"]
raise SystemExit(0 if len(app_up) >= 1 else 1)
PY
    then
      printf 'DRILL_RESTORE_FAILED no healthy gongchae-app target after restore\n' >&2
      rrc=1
    fi
  else
    printf 'DRILL_RESTORE_FAILED could not resolve Prometheus/Postgres containers during restore\n' >&2
    rrc=1
  fi

  # 4) Verify host and container SHA-256 against backup and expected Git blob SHA-256.
  if ! sha256sum -c "${BACKUP_DIR}/orig.sha256" >/dev/null 2>&1; then
    printf 'DRILL_RESTORE_FAILED sha256sum -c %s failed\n' "${BACKUP_DIR}/orig.sha256" >&2
    rrc=1
  fi
  if ! verify_expected_hashes; then
    printf 'DRILL_RESTORE_FAILED restored files do not match expected Git main blob SHA-256\n' >&2
    rrc=1
  fi
  if [[ -n "$PROM_CONTAINER" ]]; then
    local h_prom c_prom h_rules c_rules
    h_prom="$(host_sha256 "$PROM_CONFIG")"
    c_prom="$(run_bounded "$CMD_TIMEOUT_SEC" docker exec "$PROM_CONTAINER" sha256sum /etc/prometheus/prometheus.yml 2>/dev/null | awk '{print $1}')"
    h_rules="$(host_sha256 "$RULES_FILE")"
    c_rules="$(run_bounded "$CMD_TIMEOUT_SEC" docker exec "$PROM_CONTAINER" sha256sum /etc/prometheus/rules/gongchae.yml 2>/dev/null | awk '{print $1}')"
    if [[ "$h_prom" != "$c_prom" || "$h_rules" != "$c_rules" ]]; then
      printf 'DRILL_RESTORE_FAILED container hash mismatch prom(%s!=%s) rules(%s!=%s)\n' "$h_prom" "$c_prom" "$h_rules" "$c_rules" >&2
      rrc=1
    fi
  fi

  # 5) Verify DB (real Flyway schema), file+runtime flags, and service containers match baseline.
  if [[ -f "${BACKUP_DIR}/db_before.txt" && -n "$PG_CONTAINER" ]]; then
    capture_db_snapshot > "${BACKUP_DIR}/db_after.txt" || rrc=1
    if ! diff -u "${BACKUP_DIR}/db_before.txt" "${BACKUP_DIR}/db_after.txt" >&2; then
      printf 'DRILL_RESTORE_FAILED DB baseline changed during drill\n' >&2
      rrc=1
    fi
  fi
  if [[ -f "${BACKUP_DIR}/flags_before.txt" ]]; then
    if capture_flags_snapshot > "${BACKUP_DIR}/flags_after.txt"; then
      if ! diff -u "${BACKUP_DIR}/flags_before.txt" "${BACKUP_DIR}/flags_after.txt" >&2; then
        printf 'DRILL_RESTORE_FAILED file or runtime flags changed during drill\n' >&2
        rrc=1
      fi
    else
      printf 'DRILL_RESTORE_FAILED could not read post-drill flags\n' >&2
      rrc=1
    fi
  fi
  if [[ -f "${BACKUP_DIR}/containers_before.txt" ]]; then
    capture_containers_snapshot > "${BACKUP_DIR}/containers_after.txt" || rrc=1
    if ! diff -u "${BACKUP_DIR}/containers_before.txt" "${BACKUP_DIR}/containers_after.txt" >&2; then
      printf 'DRILL_RESTORE_FAILED service containers changed during drill\n' >&2
      rrc=1
    fi
  fi

  if [[ "$rrc" -eq 0 ]]; then
    rm -f "$WORKER_LOCK_FILE" "$WORKER_REVOKED_FILE" 2>/dev/null || true
    write_meta_files "RESTORED"
    RESTORE_DONE=1
    log "DRILL_RESTORE_VERIFIED_OK (prom_written=${prom_touched} rules_written=${rules_touched})"
  else
    write_meta_files "RESTORE_FAILED"
    printf '%s [drill] DRILL_RESTORE_FAILED (non-zero exit)\n' "$(date -u +'%Y-%m-%dT%H:%M:%SZ')" >&2
  fi
  return "$rrc"
}

run_worker_phase() {
  verify_worker_attempt_active
  resolve_containers
  wait_for_drill_config_and_target present 90 || fail "gongchae-drill target did not become up in Prometheus"
  wait_for_drill_zero_baseline 60 || fail "gongchae-drill 0/0 baseline metrics not scraped in Prometheus"
  assert_no_unexpected_alerts "" || fail "False alerts fired at 0/0 drill baseline"
  log "DRILL_BASELINE_UP (gongchae-drill up, 0/0 scraped, 0 alerts)"

  local inject_epoch
  inject_epoch="$(date +%s)"
  write_metrics "$BUDGET_INJECT_VALUE" "$FAILING_INJECT_VALUE"
  log "DRILL_INJECTED budget=${BUDGET_INJECT_VALUE} failing=${FAILING_INJECT_VALUE}"

  if [[ "$TEST_WORKER_STALL_SEC" -gt 0 ]]; then
    log "DRILL_WORKER_SIMULATED_STALL (${TEST_WORKER_STALL_SEC}s)"
    sleep "$TEST_WORKER_STALL_SEC"
  fi

  wait_for_alert_state "GongchaeApiBudgetWarning" "firing" 120 "true" || fail "GongchaeApiBudgetWarning did not fire"
  assert_no_unexpected_alerts "GongchaeApiBudgetWarning,GongchaeRegularCollectionFailing" || fail "Unexpected alert during budget firing"
  log "DRILL_BUDGET_WARNING_FIRING"

  wait_for_alert_state "GongchaeRegularCollectionFailing" "pending" 120 "true" || fail "GongchaeRegularCollectionFailing did not enter pending"
  log "DRILL_COLLECTION_FAILING_PENDING"

  # Wait until >= PENDING_HOLD_CHECK_SEC (5m) and verify for: 10m is still holding pending (not prematurely firing).
  local pending_check_target=$(( inject_epoch + PENDING_HOLD_CHECK_SEC ))
  while [[ $(date +%s) -lt $pending_check_target ]]; do
    verify_worker_attempt_active
    sleep 5
  done
  wait_for_alert_state "GongchaeRegularCollectionFailing" "pending" 15 "true" || fail "GongchaeRegularCollectionFailing did not stay pending at >=${PENDING_HOLD_CHECK_SEC}s"
  assert_no_unexpected_alerts "GongchaeApiBudgetWarning,GongchaeRegularCollectionFailing" || fail "Unexpected alert during 10m pending hold"
  log "DRILL_COLLECTION_FAILING_STILL_PENDING_AT_5M"

  local remaining_wait=$(( FAILING_WAIT_TIMEOUT_SEC - ($(date +%s) - inject_epoch) ))
  if [[ "$remaining_wait" -lt 60 ]]; then remaining_wait=60; fi
  wait_for_alert_state "GongchaeRegularCollectionFailing" "firing" "$remaining_wait" "true" || fail "GongchaeRegularCollectionFailing did not fire after for: 10m"
  local elapsed_to_fire=$(( $(date +%s) - inject_epoch ))
  if [[ "$elapsed_to_fire" -lt "$FAILING_MIN_ELAPSED_SEC" ]]; then
    fail "FOR_10M_VIOLATION: GongchaeRegularCollectionFailing fired in ${elapsed_to_fire}s (< ${FAILING_MIN_ELAPSED_SEC}s)"
  fi
  assert_no_unexpected_alerts "GongchaeApiBudgetWarning,GongchaeRegularCollectionFailing" || fail "Unexpected alert during collection failing firing"
  log "DRILL_COLLECTION_FAILING_FIRING elapsed_sec=${elapsed_to_fire}"

  # Allow Grafana evaluation/group_wait window before clearing (90s), checking attempt liveness periodically.
  local hold_until=$(( $(date +%s) + 90 ))
  while [[ $(date +%s) -lt $hold_until ]]; do
    verify_worker_attempt_active
    sleep 5
  done

  write_metrics 0 0
  log "DRILL_RECOVERY_INJECTED budget=0 failing=0"
  wait_for_alert_state "GongchaeApiBudgetWarning" "absent" "$RESOLVE_WAIT_TIMEOUT_SEC" "false" || fail "GongchaeApiBudgetWarning did not resolve"
  wait_for_alert_state "GongchaeRegularCollectionFailing" "absent" "$RESOLVE_WAIT_TIMEOUT_SEC" "false" || fail "GongchaeRegularCollectionFailing did not resolve"
  log "DRILL_ALERTS_RESOLVED_IN_PROMETHEUS"

  if [[ "$POST_RESOLVE_WAIT_SEC" -gt 0 ]]; then
    log "Waiting ${POST_RESOLVE_WAIT_SEC}s for Grafana [RESOLVED] delivery..."
    local resolve_until=$(( $(date +%s) + POST_RESOLVE_WAIT_SEC ))
    while [[ $(date +%s) -lt $resolve_until ]]; do
      verify_worker_attempt_active
      sleep 5
    done
  fi
  log "DRILL_WORKER_COMPLETE"
}

supervisor_on_signal() {
  SUPERVISOR_SIGNAL="SIGNAL_INTERRUPTED"
  log "Supervisor received termination signal; handing off to restore..."
}

supervisor_on_exit() {
  local exit_rc=$?
  set +e
  trap '' INT TERM HUP
  if [[ "$MUTATIONS_STARTED" -eq 1 && "$RESTORE_DONE" -eq 0 && "$RESTORE_ATTEMPTED" -eq 0 ]]; then
    RESTORE_ATTEMPTED=1
    fence_and_terminate_worker || true
    restore_and_verify
    local rrc=$?
    release_root_lock
    if [[ "$rrc" -ne 0 ]]; then
      exit "$rrc"
    fi
    exit "$exit_rc"
  fi
  release_root_lock
  exit "$exit_rc"
}

if [[ "$ACTION" == "_worker" ]]; then
  run_worker_phase
  exit 0
fi

if [[ "$ACTION" == "preflight" ]]; then
  acquire_root_lock nonblock
  trap release_root_lock EXIT
  preflight_checks
  log "DRILL_PREFLIGHT_OK"
  exit 0
fi

if [[ "$ACTION" == "restore" ]]; then
  require_cmd docker
  require_cmd "$PYTHON_BIN"
  require_cmd sha256sum
  require_cmd timeout
  acquire_root_lock wait "$LOCK_WAIT_SEC"
  trap release_root_lock EXIT
  restore_and_verify
  exit $?
fi

# ACTION == run (Supervisor)
acquire_root_lock nonblock
trap supervisor_on_exit EXIT

preflight_checks

RUN_ID="$(date -u +'%Y%m%dT%H%M%SZ')-$$-${RANDOM}"
SUPERVISOR_PID="$$"
SUPERVISOR_START_TIME="$(read_proc_start_time "$$" || true)"
ATTEMPT_DIR="${WORK_DIR}/runs/${RUN_ID}"
WORKER_LOCK_FILE="${STATE_DIR}/worker-${RUN_ID}.lock"
WORKER_REVOKED_FILE="${STATE_DIR}/worker-${RUN_ID}.revoked"
mkdir -p "$BACKUP_DIR"
chmod 0700 "$BACKUP_DIR"
cp -a "$PROM_CONFIG" "${BACKUP_DIR}/prometheus.yml"
cp -a "$RULES_FILE" "${BACKUP_DIR}/gongchae.yml"
sha256sum "$PROM_CONFIG" "$RULES_FILE" > "${BACKUP_DIR}/orig.sha256"
capture_db_snapshot > "${BACKUP_DIR}/db_before.txt"
capture_flags_snapshot > "${BACKUP_DIR}/flags_before.txt"
capture_containers_snapshot > "${BACKUP_DIR}/containers_before.txt"

BACKEND_NET="$(run_bounded "$CMD_TIMEOUT_SEC" docker inspect -f '{{range $k,$v := .NetworkSettings.Networks}}{{println $k}}{{end}}' "$PROM_CONTAINER" | grep backend | head -n1)"
[[ -n "$BACKEND_NET" ]] || fail "Could not determine Prometheus backend network"
log "Using backend network: ${BACKEND_NET}"

prepare_drill_files
write_meta_files "ARMED"
MUTATIONS_STARTED=1

trap supervisor_on_signal INT TERM HUP

log "Starting temporary exporter (${EXPORTER_NAME}) on ${BACKEND_NET} (no host port, ttl=${EXPORTER_TTL_SEC}s)"
run_bounded "$CMD_TIMEOUT_SEC" docker run -d --rm --name "$EXPORTER_NAME" \
  --network "$BACKEND_NET" --network-alias drill-exporter \
  -v "$(docker_host_path "${ATTEMPT_DIR}/Caddyfile"):/etc/caddy/Caddyfile:ro" \
  -v "$(docker_host_path "${ATTEMPT_DIR}"):/srv:ro" \
  "$CADDY_IMAGE" sh -c "timeout ${EXPORTER_TTL_SEC} caddy run --config /etc/caddy/Caddyfile" >/dev/null

log "Applying in-place drill config (job_name: gongchae-drill) and [훈련] label summaries + SIGHUP"
cat "${ATTEMPT_DIR}/prometheus.drill.yml" > "$PROM_CONFIG"
cat "${ATTEMPT_DIR}/rules_stage/gongchae.yml" > "$RULES_FILE"
prom_sighup

# Spawn worker with deploy-root lock FD 9 closed and dedicated worker lease FD 8 handed off
# (zero race window on Linux flock; supervisor immediately closes its own FD 8 copy).
worker_args=(
  _worker
  --root-dir "$ROOT_DIR"
  --env-file "$ENV_FILE"
  --backup-dir "$BACKUP_DIR"
  --work-dir "$WORK_DIR"
  --attempt-dir "$ATTEMPT_DIR"
  --run-id "$RUN_ID"
  --supervisor-pid "$SUPERVISOR_PID"
  --supervisor-start-time "$SUPERVISOR_START_TIME"
  --worker-lock-file "$WORKER_LOCK_FILE"
  --worker-revoked-file "$WORKER_REVOKED_FILE"
  --project "$PROJECT_NAME"
  --prom-container "$PROM_CONTAINER"
  --pg-container "$PG_CONTAINER"
  --exporter-name "$EXPORTER_NAME"
)
if [[ -n "$APP_CONTAINERS" ]]; then
  worker_args+=(--app-containers "$APP_CONTAINERS")
fi

if command -v flock >/dev/null 2>&1; then
  exec 8>"$WORKER_LOCK_FILE"
  flock -n 8 || fail "Could not acquire worker lease lock: ${WORKER_LOCK_FILE}"
  bash "$0" "${worker_args[@]}" 9>&- &
  WORKER_PID=$!
  exec 8>&-
else
  bash "$0" "${worker_args[@]}" 9>&- &
  WORKER_PID=$!
fi
WORKER_START_TIME="$(read_proc_start_time "$WORKER_PID" || true)"
write_meta_files "ARMED"

deadline_epoch=$(( $(date +%s) + MAX_DURATION_SEC ))
worker_rc=0
timed_out=0

while kill -0 "$WORKER_PID" 2>/dev/null; do
  if [[ -n "$SUPERVISOR_SIGNAL" ]]; then
    break
  fi
  if [[ $(date +%s) -ge $deadline_epoch ]]; then
    timed_out=1
    log "DRILL_SUPERVISOR_DEADLINE_REACHED (${MAX_DURATION_SEC}s); revoking and stopping worker ${WORKER_PID}"
    break
  fi
  sleep 1
done

# Immediately mask further signals before killing worker and running restore.
trap '' INT TERM HUP

if [[ -n "$SUPERVISOR_SIGNAL" ]]; then
  fence_and_terminate_worker || true
  worker_rc=143
elif [[ "$timed_out" -eq 1 ]]; then
  fence_and_terminate_worker || true
  worker_rc=124
else
  set +e
  wait "$WORKER_PID"
  worker_rc=$?
  set -e
  WORKER_PID=""
  WORKER_START_TIME=""
fi

RESTORE_ATTEMPTED=1
set +e
restore_and_verify
restore_rc=$?
set -e

if [[ "$restore_rc" -ne 0 ]]; then
  exit "$restore_rc"
fi
if [[ "$worker_rc" -ne 0 ]]; then
  log "DRILL_ABORTED_BUT_RESTORED worker_rc=${worker_rc} signal=${SUPERVISOR_SIGNAL:-none} timed_out=${timed_out}"
  exit "$worker_rc"
fi

log "DRILL_RUN_COMPLETE"
exit 0
