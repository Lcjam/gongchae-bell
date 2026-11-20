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
RESTORE_DONE=0
SUPERVISOR_SIGNAL=""
WORKER_PID=""
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
  timeout "$secs" "$@"
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
EXPORTER_NAME=${EXPORTER_NAME}
EXPECTED_PROM_SHA256=${EXPECTED_PROM_SHA256}
EXPECTED_RULES_SHA256=${EXPECTED_RULES_SHA256}
DRILL_PROM_SHA256=${DRILL_PROM_SHA256}
DRILL_RULES_SHA256=${DRILL_RULES_SHA256}
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
  app_ids="$(resolve_app_containers)"
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

check_prom_clean_baseline() {
  local prom_alerts_json targets_json config_json
  prom_alerts_json="$(prom_api_get "/api/v1/alerts")" || return 1
  targets_json="$(prom_api_get "/api/v1/targets?state=active")" || return 1
  config_json="$(prom_api_get "/api/v1/status/config")" || return 1

  "$PYTHON_BIN" - "$prom_alerts_json" "$targets_json" "$config_json" <<'PY'
import json, sys
items = json.loads(sys.argv[1])["data"]["alerts"]
targets = json.loads(sys.argv[2])["data"]["activeTargets"]
cfg = json.loads(sys.argv[3])["data"]["yaml"]

if items:
    names = [(a["labels"].get("alertname"), a.get("state")) for a in items]
    raise SystemExit(f"EXISTING_ALERTS_NONEMPTY: {names}")

if "gongchae-drill" in cfg:
    raise SystemExit("DRILL_JOB_ALREADY_IN_PROM_CONFIG")

drill_targets = [t for t in targets if t.get("labels", {}).get("job") == "gongchae-drill" or "drill-exporter" in t.get("scrapeUrl", "")]
if drill_targets:
    raise SystemExit("DRILL_TARGET_ALREADY_ACTIVE")

app_up = [t for t in targets if t.get("labels", {}).get("job") == "gongchae-app" and t.get("health") == "up"]
if not app_up:
    raise SystemExit("NO_ACTIVE_HEALTHY_GONGCHAE_APP_TARGET")

print("PROM_BASELINE_OK active_app=" + ",".join(t["labels"].get("instance", "?") for t in app_up))
PY
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

  if run_bounded "$CMD_TIMEOUT_SEC" docker ps -a --format '{{.Names}}' | grep -Fxq "$EXPORTER_NAME"; then
    fail "STALE_EXPORTER_EXISTS: container ${EXPORTER_NAME} already exists"
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
      log "Stale single-file bind mount detected; recreating Prometheus via opt-in command..."
      bash -c "$RECREATE_CMD"
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

write_metrics() {
  local budget="$1"
  local failing="$2"
  if [[ -f "${WORK_DIR}/revoked" ]]; then
    fail "WORKER_REVOKED: refusing to mutate metrics after handoff"
  fi
  printf '# TYPE collector_api_attempts_today gauge\ncollector_api_attempts_today %s\n# TYPE collector_regular_incomplete_consecutive gauge\ncollector_regular_incomplete_consecutive %s\n' \
    "$budget" "$failing" > "${WORK_DIR}/metrics.prom"
}

prepare_drill_files() {
  mkdir -p "${WORK_DIR}/rules_stage"
  chmod 0700 "$WORK_DIR"
  rm -f "${WORK_DIR}/revoked"

  cat > "${WORK_DIR}/Caddyfile" <<'EOF'
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

  "$PYTHON_BIN" - "$(docker_host_path "${BACKUP_DIR}/prometheus.yml")" "$(docker_host_path "${WORK_DIR}/prometheus.drill.yml")" <<'PY'
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

  "$PYTHON_BIN" - "$(docker_host_path "${BACKUP_DIR}/gongchae.yml")" "$(docker_host_path "${WORK_DIR}/rules_stage/gongchae.yml")" <<'PY'
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

  DRILL_PROM_SHA256="$(host_sha256 "${WORK_DIR}/prometheus.drill.yml")"
  DRILL_RULES_SHA256="$(host_sha256 "${WORK_DIR}/rules_stage/gongchae.yml")"

  # Validate modified config and rules with promtool before touching live files.
  run_bounded 30 docker run --rm \
    -v "$(docker_host_path "${WORK_DIR}/prometheus.drill.yml"):/etc/prometheus/prometheus.yml:ro" \
    -v "$(docker_host_path "${WORK_DIR}/rules_stage"):/etc/prometheus/rules:ro" \
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
  prom_alerts_json="$(prom_api_get "/api/v1/alerts")"
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
  m_run="$(read_meta_key "$meta_file" "RUN_ID")" || return 1
  m_root="$(read_meta_key "$meta_file" "ROOT_DIR")" || return 1
  m_prom="$(read_meta_key "$meta_file" "EXPECTED_PROM_SHA256")" || return 1
  m_rules="$(read_meta_key "$meta_file" "EXPECTED_RULES_SHA256")" || return 1
  m_dprom="$(read_meta_key "$meta_file" "DRILL_PROM_SHA256")" || return 1
  m_drules="$(read_meta_key "$meta_file" "DRILL_RULES_SHA256")" || return 1
  m_exp="$(read_meta_key "$meta_file" "EXPORTER_NAME")" || return 1
  m_work="$(read_meta_key "$meta_file" "WORK_DIR")" || return 1

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
  return 0
}

restore_and_verify() {
  # Mask INT/TERM/HUP throughout restore so repeated signals cannot interrupt in-flight recovery.
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
    local nrc=0
    if run_bounded "$CMD_TIMEOUT_SEC" docker ps -a --format '{{.Names}}' | grep -Fxq "$EXPORTER_NAME"; then
      printf 'DRILL_RESTORE_FAILED owned exporter still exists on RESTORED state: %s\n' "$EXPORTER_NAME" >&2
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
    fi
    if [[ "$nrc" -eq 0 ]]; then
      RESTORE_DONE=1
      log "DRILL_RESTORE_ALREADY_RESTORED_NOOP"
    else
      printf 'DRILL_RESTORE_FAILED read-only verification failed on RESTORED state\n' >&2
    fi
    return "$nrc"
  fi

  local rrc=0
  log "Starting idempotent restore and post-condition verification (run_id=${RUN_ID})..."

  if [[ "$TEST_RESTORE_HOLD_SEC" -gt 0 ]]; then
    log "DRILL_RESTORE_HOLD_STARTED (${TEST_RESTORE_HOLD_SEC}s)"
    sleep "$TEST_RESTORE_HOLD_SEC"
  fi

  if [[ ! -f "${BACKUP_DIR}/prometheus.yml" || ! -f "${BACKUP_DIR}/gongchae.yml" || ! -f "${BACKUP_DIR}/orig.sha256" ]]; then
    printf 'DRILL_RESTORE_FAILED missing backup files in %s\n' "$BACKUP_DIR" >&2
    return 1
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
  if run_bounded "$CMD_TIMEOUT_SEC" docker ps -a --format '{{.Names}}' | grep -Fxq "$EXPORTER_NAME"; then
    run_bounded "$CMD_TIMEOUT_SEC" docker rm -f "$EXPORTER_NAME" >/dev/null || rrc=1
  fi
  if run_bounded "$CMD_TIMEOUT_SEC" docker ps -a --format '{{.Names}}' | grep -Fxq "$EXPORTER_NAME"; then
    printf 'DRILL_RESTORE_FAILED exporter container still exists: %s\n' "$EXPORTER_NAME" >&2
    rrc=1
  fi
  if [[ -n "$WORK_DIR" && "$WORK_DIR" != "/" && -d "$WORK_DIR" ]]; then
    rm -rf "$WORK_DIR" || rrc=1
  fi

  # 3) Reload Prometheus and verify drill config/target/alerts cleared and active app target healthy.
  if resolve_containers 2>/dev/null; then
    prom_sighup || rrc=1
    if ! wait_for_drill_config_and_target absent 60; then
      printf 'DRILL_RESTORE_FAILED gongchae-drill still present in Prometheus config/targets after SIGHUP\n' >&2
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
  resolve_containers
  wait_for_drill_config_and_target present 90 || fail "gongchae-drill target did not become up in Prometheus"
  assert_no_unexpected_alerts "" || fail "False alerts fired at 0/0 drill baseline"
  log "DRILL_BASELINE_UP (gongchae-drill up, 0 alerts)"

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

  # Allow Grafana evaluation/group_wait window before clearing (90s)
  sleep 90

  write_metrics 0 0
  log "DRILL_RECOVERY_INJECTED budget=0 failing=0"
  wait_for_alert_state "GongchaeApiBudgetWarning" "absent" "$RESOLVE_WAIT_TIMEOUT_SEC" "false" || fail "GongchaeApiBudgetWarning did not resolve"
  wait_for_alert_state "GongchaeRegularCollectionFailing" "absent" "$RESOLVE_WAIT_TIMEOUT_SEC" "false" || fail "GongchaeRegularCollectionFailing did not resolve"
  log "DRILL_ALERTS_RESOLVED_IN_PROMETHEUS"

  if [[ "$POST_RESOLVE_WAIT_SEC" -gt 0 ]]; then
    log "Waiting ${POST_RESOLVE_WAIT_SEC}s for Grafana [RESOLVED] delivery..."
    sleep "$POST_RESOLVE_WAIT_SEC"
  fi
  log "DRILL_WORKER_COMPLETE"
}

terminate_worker_and_wait() {
  if [[ -z "$WORKER_PID" ]]; then
    return 0
  fi
  if [[ -d "$WORK_DIR" ]]; then
    printf '1\n' > "${WORK_DIR}/revoked" 2>/dev/null || true
  fi
  if kill -0 "$WORKER_PID" 2>/dev/null; then
    pkill -TERM -P "$WORKER_PID" 2>/dev/null || true
    kill -TERM "$WORKER_PID" 2>/dev/null || true
    local i
    for i in 1 2 3 4 5; do
      kill -0 "$WORKER_PID" 2>/dev/null || break
      sleep 1
    done
    if kill -0 "$WORKER_PID" 2>/dev/null; then
      log "Worker ${WORKER_PID} did not exit after SIGTERM; sending SIGKILL"
      pkill -KILL -P "$WORKER_PID" 2>/dev/null || true
      kill -KILL "$WORKER_PID" 2>/dev/null || true
    fi
  fi
  wait "$WORKER_PID" 2>/dev/null || true
  WORKER_PID=""
}

supervisor_on_signal() {
  SUPERVISOR_SIGNAL="SIGNAL_INTERRUPTED"
  log "Supervisor received termination signal; handing off to restore..."
}

supervisor_on_exit() {
  local exit_rc=$?
  set +e
  trap '' INT TERM HUP
  if [[ "$MUTATIONS_STARTED" -eq 1 && "$RESTORE_DONE" -eq 0 ]]; then
    terminate_worker_and_wait
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
  -v "$(docker_host_path "${WORK_DIR}/Caddyfile"):/etc/caddy/Caddyfile:ro" \
  -v "$(docker_host_path "${WORK_DIR}"):/srv:ro" \
  "$CADDY_IMAGE" sh -c "timeout ${EXPORTER_TTL_SEC} caddy run --config /etc/caddy/Caddyfile" >/dev/null

log "Applying in-place drill config (job_name: gongchae-drill) and [훈련] label summaries + SIGHUP"
cat "${WORK_DIR}/prometheus.drill.yml" > "$PROM_CONFIG"
cat "${WORK_DIR}/rules_stage/gongchae.yml" > "$RULES_FILE"
prom_sighup

# Spawn worker with lock FD 9 closed so only the supervisor owns the deploy-root lock and config mutations.
worker_args=(
  _worker
  --root-dir "$ROOT_DIR"
  --env-file "$ENV_FILE"
  --backup-dir "$BACKUP_DIR"
  --work-dir "$WORK_DIR"
  --project "$PROJECT_NAME"
  --prom-container "$PROM_CONTAINER"
  --pg-container "$PG_CONTAINER"
  --exporter-name "$EXPORTER_NAME"
)
if [[ -n "$APP_CONTAINERS" ]]; then
  worker_args+=(--app-containers "$APP_CONTAINERS")
fi

bash "$0" "${worker_args[@]}" 9>&- &
WORKER_PID=$!

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
  terminate_worker_and_wait
  worker_rc=143
elif [[ "$timed_out" -eq 1 ]]; then
  terminate_worker_and_wait
  worker_rc=124
else
  set +e
  wait "$WORKER_PID"
  worker_rc=$?
  set -e
  WORKER_PID=""
fi

restore_and_verify
restore_rc=$?

if [[ "$restore_rc" -ne 0 ]]; then
  exit "$restore_rc"
fi
if [[ "$worker_rc" -ne 0 ]]; then
  log "DRILL_ABORTED_BUT_RESTORED worker_rc=${worker_rc} signal=${SUPERVISOR_SIGNAL:-none} timed_out=${timed_out}"
  exit "$worker_rc"
fi

log "DRILL_RUN_COMPLETE"
exit 0
