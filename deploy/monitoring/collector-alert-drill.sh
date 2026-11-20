#!/usr/bin/env bash
# Controlled collector alert drill harness (#15):
# - Verifies original expr and for: 10m of GongchaeApiBudgetWarning and GongchaeRegularCollectionFailing
#   without touching production DB, calling external APIs, or masking GongchaeAppMetricsDown.
# - B1: Inspects live Prometheus bind mount (single-file inode vs directory mount) before mutating.
# - B2: Appends an isolated scrape job `gongchae-drill` instead of modifying `gongchae-app`.
# - B3: Snapshots and compares Postgres state via container env (never sources .env or hardcodes day/37).
# - B4: Supports SSH-detached runs with hard deadline watchdog, idempotent EXIT trap restore,
#       backup + Git blob SHA-256 verification, and non-zero exit if any restore check fails.
set -euo pipefail

ROOT_DIR="${DRILL_ROOT_DIR:-/opt/gongchae-bell}"
ENV_FILE="${DRILL_ENV_FILE:-}"
BACKUP_DIR="${DRILL_BACKUP_DIR:-${HOME}/gongchae-ops/collector-drill-backup}"
WORK_DIR="${DRILL_WORK_DIR:-/tmp/gongchae-collector-drill}"
PROJECT_NAME="${DRILL_PROJECT_NAME:-gongchae-bell-prod}"
PROM_CONTAINER="${DRILL_PROM_CONTAINER:-}"
PG_CONTAINER="${DRILL_PG_CONTAINER:-}"
EXPORTER_NAME="${DRILL_EXPORTER_NAME:-gongchae-drill-exporter}"
PROM_IMAGE="${DRILL_PROM_IMAGE:-prom/prometheus:v3.16.0}"
CADDY_IMAGE="${DRILL_CADDY_IMAGE:-caddy:2-alpine}"
EXPECTED_PROM_SHA256="${DRILL_EXPECTED_PROM_SHA256:-}"
EXPECTED_RULES_SHA256="${DRILL_EXPECTED_RULES_SHA256:-}"
RECREATE_CMD="${DRILL_RECREATE_CMD:-}"
ALLOW_RECREATE_PROMETHEUS="false"

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

MUTATIONS_STARTED=0
RESTORE_DONE=0
WATCHDOG_PID=""
ABORT_REASON=""

log() {
  printf '%s [collector-drill] %s\n' "$(date -u +'%Y-%m-%dT%H:%M:%SZ')" "$*"
}

fail() {
  printf '%s [collector-drill] ERROR: %s\n' "$(date -u +'%Y-%m-%dT%H:%M:%SZ')" "$*" >&2
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
  --exporter-name NAME            Temporary exporter container name (default: gongchae-drill-exporter)
  --expected-prom-sha256 SHA      Expected Git main blob SHA-256 for prometheus.yml
  --expected-rules-sha256 SHA     Expected Git main blob SHA-256 for rules/gongchae.yml
  --recreate-cmd CMD              Command to recreate Prometheus if single-file bind mount is stale
  --allow-recreate-prometheus     Opt-in to recreate Prometheus on stale single-file bind mount (run only)
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
    --exporter-name) EXPORTER_NAME="$2"; shift 2 ;;
    --expected-prom-sha256) EXPECTED_PROM_SHA256="$2"; shift 2 ;;
    --expected-rules-sha256) EXPECTED_RULES_SHA256="$2"; shift 2 ;;
    --recreate-cmd) RECREATE_CMD="$2"; shift 2 ;;
    --allow-recreate-prometheus) ALLOW_RECREATE_PROMETHEUS="true"; shift ;;
    -h|--help) usage; exit 0 ;;
    *) printf 'Unknown argument: %s\n' "$1" >&2; usage >&2; exit 2 ;;
  esac
done

case "$ACTION" in
  preflight|run|restore) ;;
  -h|--help) usage; exit 0 ;;
  *) printf 'Unknown action: %s\n' "$ACTION" >&2; usage >&2; exit 2 ;;
esac

if [[ -z "$ENV_FILE" ]]; then
  ENV_FILE="${ROOT_DIR}/.env"
fi
PROM_CONFIG="${ROOT_DIR}/deploy/monitoring/prometheus/prometheus.yml"
RULES_DIR="${ROOT_DIR}/deploy/monitoring/prometheus/rules"
RULES_FILE="${RULES_DIR}/gongchae.yml"
if [[ -z "$RECREATE_CMD" ]]; then
  RECREATE_CMD="cd \"${ROOT_DIR}\" && deploy/deploy.sh monitoring up -d --force-recreate prometheus"
fi

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || fail "Missing required tool: $1"
}

PYTHON_BIN="python3"
if ! command -v python3 >/dev/null 2>&1 || ! "$PYTHON_BIN" --version >/dev/null 2>&1; then
  PYTHON_BIN="python"
fi

resolve_containers() {
  if [[ -z "$PROM_CONTAINER" ]]; then
    PROM_CONTAINER="$(docker ps -q --filter "label=com.docker.compose.project=${PROJECT_NAME}" \
      --filter "label=com.docker.compose.service=prometheus" | head -n1)"
  fi
  if [[ -z "$PG_CONTAINER" ]]; then
    PG_CONTAINER="$(docker ps -q --filter "label=com.docker.compose.project=${PROJECT_NAME}" \
      --filter "label=com.docker.compose.service=postgres" | head -n1)"
  fi
  [[ -n "$PROM_CONTAINER" ]] || fail "Running Prometheus container not found (project=${PROJECT_NAME})"
  [[ -n "$PG_CONTAINER" ]] || fail "Running Postgres container not found (project=${PROJECT_NAME})"
}

host_sha256() {
  sha256sum "$1" | awk '{print $1}'
}

docker_host_path() {
  if command -v cygpath >/dev/null 2>&1; then
    cygpath -m "$1"
  else
    printf '%s' "$1"
  fi
}

interruptible_sleep() {
  local secs="$1"
  if [[ "$secs" -le 0 ]]; then return 0; fi
  sleep "$secs" &
  local spid=$!
  wait "$spid" || { kill "$spid" 2>/dev/null || true; return 1; }
}

prom_api_get() {
  local path="$1"
  docker exec "$PROM_CONTAINER" wget -qO- "http://localhost:9090${path}"
}

prom_sighup() {
  docker kill -s HUP "$PROM_CONTAINER" >/dev/null
}

read_env_flag() {
  local key="$1"
  "$PYTHON_BIN" - "$(docker_host_path "$ENV_FILE")" "$key" <<'PY'
import sys
path, target = sys.argv[1], sys.argv[2]
val = "false"
with open(path, "r", encoding="utf-8") as fh:
    for raw in fh:
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        if k.strip() == target:
            v = v.strip()
            if len(v) >= 2 and v[0] == v[-1] and v[0] in ("'", '"'):
                v = v[1:-1]
            val = v.lower() if v else "false"
print(val)
PY
}

capture_flags_snapshot() {
  local collector alerts
  collector="$(read_env_flag "COLLECTOR_ENABLED")"
  alerts="$(read_env_flag "ALERTS_ENABLED")"
  printf 'COLLECTOR_ENABLED=%s\nALERTS_ENABLED=%s\n' "$collector" "$alerts"
}

capture_db_snapshot() {
  docker exec "$PG_CONTAINER" sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1 -qtAc "
    SELECT '\''posting_count='\'' || count(*)::text FROM posting;
    SELECT '\''posting_md5='\'' || coalesce(md5(string_agg(sn::text || '\'':'\'' || status || '\'':'\'' || coalesce(updated_at::text, '\'''\''), '\'','\'' ORDER BY sn)), '\''empty'\'') FROM posting;
    SELECT '\''collect_run_count='\'' || count(*)::text FROM collect_run;
    SELECT '\''api_attempt='\'' || count(*)::text || '\'':'\'' || coalesce(max(id), 0)::text FROM api_attempt;
    SELECT '\''api_budget='\'' || coalesce(string_agg(day::text || '\''='\'' || count::text, '\'','\'' ORDER BY day), '\''empty'\'') FROM api_budget;
  "'
}

capture_containers_snapshot() {
  local out
  out="$(docker ps --filter "label=com.docker.compose.project=${PROJECT_NAME}" \
    --format '{{.Names}}|{{.Image}}|{{.ID}}' | sort)"
  if [[ -n "$out" ]]; then
    printf '%s\n' "$out"
  else
    docker inspect --format '{{.Name}}|{{.Config.Image}}|{{.Id}}' "$PROM_CONTAINER" "$PG_CONTAINER" | sort
  fi
}

inspect_prom_mounts() {
  local mounts_json mount_kind host_prom_sha cont_prom_sha host_rules_sha cont_rules_sha
  mounts_json="$(docker inspect --format '{{json .Mounts}}' "$PROM_CONTAINER")"
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
  cont_prom_sha="$(docker exec "$PROM_CONTAINER" sha256sum /etc/prometheus/prometheus.yml | awk '{print $1}')"
  host_rules_sha="$(host_sha256 "$RULES_FILE")"
  cont_rules_sha="$(docker exec "$PROM_CONTAINER" sha256sum /etc/prometheus/rules/gongchae.yml | awk '{print $1}')"

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
      cont_inode="$(docker exec "$PROM_CONTAINER" stat -c %i /etc/prometheus/prometheus.yml)"
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
    if docker exec "$PROM_CONTAINER" wget -qO- "http://localhost:9090/-/ready" >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  return 1
}

verify_expected_hashes() {
  local prom_sha rules_sha
  prom_sha="$(host_sha256 "$PROM_CONFIG")"
  rules_sha="$(host_sha256 "$RULES_FILE")"
  if [[ -n "$EXPECTED_PROM_SHA256" && "$prom_sha" != "$EXPECTED_PROM_SHA256" ]]; then
    printf 'PROM_SHA_MISMATCH expected=%s actual=%s\n' "$EXPECTED_PROM_SHA256" "$prom_sha" >&2
    return 1
  fi
  if [[ -n "$EXPECTED_RULES_SHA256" && "$rules_sha" != "$EXPECTED_RULES_SHA256" ]]; then
    printf 'RULES_SHA_MISMATCH expected=%s actual=%s\n' "$EXPECTED_RULES_SHA256" "$rules_sha" >&2
    return 1
  fi
  return 0
}

check_prom_clean_baseline() {
  local prom_alerts_json targets_json config_json
  prom_alerts_json="$(prom_api_get "/api/v1/alerts")"
  targets_json="$(prom_api_get "/api/v1/targets?state=active")"
  config_json="$(prom_api_get "/api/v1/status/config")"

  "$PYTHON_BIN" - "$prom_alerts_json" "$targets_json" "$config_json" <<'PY'
import json, sys
alerts = json.loads(sys.argv[1])["data"]["alerts"]
targets = json.loads(sys.argv[2])["data"]["activeTargets"]
cfg = json.loads(sys.argv[3])["data"]["yaml"]

if alerts:
    names = [(a["labels"].get("alertname"), a.get("state")) for a in alerts]
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

  [[ -f "$ENV_FILE" ]] || fail "Env file not found: $ENV_FILE"
  [[ -f "$PROM_CONFIG" ]] || fail "Prometheus config not found: $PROM_CONFIG"
  [[ -f "$RULES_FILE" ]] || fail "Rules file not found: $RULES_FILE"

  if [[ -f "${BACKUP_DIR}/state" ]] && [[ "$(cat "${BACKUP_DIR}/state")" != "RESTORED" ]]; then
    fail "PREVIOUS_DRILL_NOT_RESTORED: ${BACKUP_DIR}/state is $(cat "${BACKUP_DIR}/state"); run restore first"
  fi

  if docker ps -a --format '{{.Names}}' | grep -Fxq "$EXPORTER_NAME"; then
    fail "STALE_EXPORTER_EXISTS: container ${EXPORTER_NAME} already exists"
  fi

  if grep -q 'job_name: gongchae-drill' "$PROM_CONFIG"; then
    fail "DRILL_JOB_ALREADY_IN_FILE: ${PROM_CONFIG}"
  fi
  if grep -q '\[훈련\]' "$RULES_FILE"; then
    fail "DRILL_MARKER_ALREADY_IN_RULES: ${RULES_FILE}"
  fi
  if compgen -G "${RULES_DIR}/drill*.yml" >/dev/null; then
    fail "STALE_DRILL_RULE_FILE_EXISTS in ${RULES_DIR}"
  fi

  verify_expected_hashes || fail "EXPECTED_GIT_SHA_MISMATCH"

  local flag_collector flag_alerts
  flag_collector="$(read_env_flag "COLLECTOR_ENABLED")"
  flag_alerts="$(read_env_flag "ALERTS_ENABLED")"
  log "Env flags: COLLECTOR_ENABLED=${flag_collector} ALERTS_ENABLED=${flag_alerts}"
  [[ "$flag_collector" == "false" ]] || fail "COLLECTOR_ENABLED must be false (actual=${flag_collector})"
  [[ "$flag_alerts" == "false" ]] || fail "ALERTS_ENABLED must be false (actual=${flag_alerts})"

  resolve_containers

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

prepare_drill_files() {
  mkdir -p "$WORK_DIR/rules_stage"
  chmod 0700 "$WORK_DIR"

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

# Strictly verify every expr: and for: line is byte-for-byte preserved.
orig_expr_for = [l for l in orig_lines if l.strip().startswith(("expr:", "for:"))]
new_expr_for = [l for l in out_lines if l.strip().startswith(("expr:", "for:"))]
if orig_expr_for != new_expr_for:
    raise SystemExit("RULE_EXPR_OR_FOR_CHANGED")

joined = "".join(out_lines)
if "for: 10m" not in joined:
    raise SystemExit("FOR_10M_MISSING")

open(dst, "w", encoding="utf-8", newline="\n").write(joined)
PY

  # Validate modified config and rules with promtool before touching live files.
  docker run --rm \
    -v "$(docker_host_path "${WORK_DIR}/prometheus.drill.yml"):/etc/prometheus/prometheus.yml:ro" \
    -v "$(docker_host_path "${WORK_DIR}/rules_stage"):/etc/prometheus/rules:ro" \
    --entrypoint promtool "$PROM_IMAGE" \
    check config /etc/prometheus/prometheus.yml >/dev/null
  log "promtool check config/rules passed for staged drill files"
}

write_metrics() {
  local budget="$1"
  local failing="$2"
  printf '# TYPE collector_api_attempts_today gauge\ncollector_api_attempts_today %s\n# TYPE collector_regular_incomplete_consecutive gauge\ncollector_regular_incomplete_consecutive %s\n' \
    "$budget" "$failing" > "${WORK_DIR}/metrics.prom"
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
alerts = json.loads(sys.argv[1])["data"]["alerts"]
name, want, prefix = sys.argv[2], sys.argv[3], sys.argv[4] == "true"
matched = [a for a in alerts if a["labels"].get("alertname") == name]
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
alerts = json.loads(sys.argv[1])["data"]["alerts"]
allowed = {x for x in sys.argv[2].split(",") if x}
unexpected = [
    (a["labels"].get("alertname"), a.get("state"))
    for a in alerts
    if a["labels"].get("alertname") not in allowed
]
if unexpected:
    raise SystemExit(f"UNEXPECTED_ALERTS: {unexpected}")
PY
}

restore_and_verify() {
  if [[ "$RESTORE_DONE" -eq 1 ]]; then
    return 0
  fi
  RESTORE_DONE=1

  local rrc=0
  log "Starting idempotent restore and post-condition verification..."

  if [[ ! -f "${BACKUP_DIR}/prometheus.yml" || ! -f "${BACKUP_DIR}/gongchae.yml" || ! -f "${BACKUP_DIR}/orig.sha256" ]]; then
    printf '%s [collector-drill] DRILL_RESTORE_FAILED missing backup files in %s\n' "$(date -u +'%Y-%m-%dT%H:%M:%SZ')" "$BACKUP_DIR" >&2
    return 1
  fi

  # 1) Restore files in-place (preserves single-file bind mount inode) + remove temporary rule files
  if ! cat "${BACKUP_DIR}/prometheus.yml" > "$PROM_CONFIG"; then
    printf 'DRILL_RESTORE_FAILED cannot write %s\n' "$PROM_CONFIG" >&2
    rrc=1
  fi
  if ! cat "${BACKUP_DIR}/gongchae.yml" > "$RULES_FILE"; then
    printf 'DRILL_RESTORE_FAILED cannot write %s\n' "$RULES_FILE" >&2
    rrc=1
  fi
  rm -f "${RULES_DIR}"/drill*.yml || rrc=1

  # 2) Remove temporary exporter container and work directory
  if docker ps -a --format '{{.Names}}' | grep -Fxq "$EXPORTER_NAME"; then
    docker rm -f "$EXPORTER_NAME" >/dev/null || rrc=1
  fi
  if docker ps -a --format '{{.Names}}' | grep -Fxq "$EXPORTER_NAME"; then
    printf 'DRILL_RESTORE_FAILED exporter container still exists: %s\n' "$EXPORTER_NAME" >&2
    rrc=1
  fi
  rm -rf "$WORK_DIR" || rrc=1

  # 3) Reload Prometheus and verify target/config/alerts cleanup
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

  # 4) Verify host and container SHA-256 against backup and optional Git blob SHA-256
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
    c_prom="$(docker exec "$PROM_CONTAINER" sha256sum /etc/prometheus/prometheus.yml 2>/dev/null | awk '{print $1}')"
    h_rules="$(host_sha256 "$RULES_FILE")"
    c_rules="$(docker exec "$PROM_CONTAINER" sha256sum /etc/prometheus/rules/gongchae.yml 2>/dev/null | awk '{print $1}')"
    if [[ "$h_prom" != "$c_prom" || "$h_rules" != "$c_rules" ]]; then
      printf 'DRILL_RESTORE_FAILED container hash mismatch prom(%s!=%s) rules(%s!=%s)\n' "$h_prom" "$c_prom" "$h_rules" "$c_rules" >&2
      rrc=1
    fi
  fi

  # 5) Verify DB, flags, and service containers match pre-drill baseline
  if [[ -f "${BACKUP_DIR}/db_before.txt" && -n "$PG_CONTAINER" ]]; then
    capture_db_snapshot > "${BACKUP_DIR}/db_after.txt" || rrc=1
    if ! diff -u "${BACKUP_DIR}/db_before.txt" "${BACKUP_DIR}/db_after.txt" >&2; then
      printf 'DRILL_RESTORE_FAILED DB baseline changed during drill\n' >&2
      rrc=1
    fi
  fi
  if [[ -f "${BACKUP_DIR}/flags_before.txt" ]]; then
    capture_flags_snapshot > "${BACKUP_DIR}/flags_after.txt" || rrc=1
    if ! diff -u "${BACKUP_DIR}/flags_before.txt" "${BACKUP_DIR}/flags_after.txt" >&2; then
      printf 'DRILL_RESTORE_FAILED env flags changed during drill\n' >&2
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
    echo "RESTORED" > "${BACKUP_DIR}/state"
    log "DRILL_RESTORE_VERIFIED_OK"
  else
    echo "RESTORE_FAILED" > "${BACKUP_DIR}/state"
    printf '%s [collector-drill] DRILL_RESTORE_FAILED (non-zero exit)\n' "$(date -u +'%Y-%m-%dT%H:%M:%SZ')" >&2
  fi
  return "$rrc"
}

on_signal() {
  ABORT_REASON="SIGNAL_INTERRUPTED"
  log "Received termination signal; initiating safe restore..."
  exit 143
}

on_exit() {
  local main_rc=$?
  set +e
  if [[ -n "$WATCHDOG_PID" ]]; then
    kill "$WATCHDOG_PID" 2>/dev/null || true
    wait "$WATCHDOG_PID" 2>/dev/null || true
  fi
  if [[ "$MUTATIONS_STARTED" -eq 1 && "$RESTORE_DONE" -eq 0 ]]; then
    restore_and_verify
    local restore_rc=$?
    if [[ "$restore_rc" -ne 0 ]]; then
      exit "$restore_rc"
    fi
    if [[ "$main_rc" -ne 0 ]]; then
      log "DRILL_ABORTED_BUT_RESTORED reason=${ABORT_REASON:-error} exit=${main_rc}"
    fi
  fi
  exit "$main_rc"
}

if [[ "$ACTION" == "preflight" ]]; then
  preflight_checks
  log "DRILL_PREFLIGHT_OK"
  exit 0
fi

if [[ "$ACTION" == "restore" ]]; then
  require_cmd docker
  require_cmd "$PYTHON_BIN"
  require_cmd sha256sum
  restore_and_verify
  exit $?
fi

# ACTION == run
preflight_checks

mkdir -p "$BACKUP_DIR"
chmod 0700 "$BACKUP_DIR"
cp -a "$PROM_CONFIG" "${BACKUP_DIR}/prometheus.yml"
cp -a "$RULES_FILE" "${BACKUP_DIR}/gongchae.yml"
sha256sum "$PROM_CONFIG" "$RULES_FILE" > "${BACKUP_DIR}/orig.sha256"
capture_db_snapshot > "${BACKUP_DIR}/db_before.txt"
capture_flags_snapshot > "${BACKUP_DIR}/flags_before.txt"
capture_containers_snapshot > "${BACKUP_DIR}/containers_before.txt"

trap on_signal INT TERM HUP
trap on_exit EXIT

# Hard deadline watchdog so SSH drop or stall always restores config & summary automatically.
(
  sleep "$MAX_DURATION_SEC" &
  wsleep=$!
  trap 'kill "$wsleep" 2>/dev/null || true; exit 0' TERM INT
  wait "$wsleep" 2>/dev/null || exit 0
  printf '%s [collector-drill] DRILL_WATCHDOG_TIMEOUT (%ss) sending SIGTERM\n' "$(date -u +'%Y-%m-%dT%H:%M:%SZ')" "$MAX_DURATION_SEC" >&2
  kill -TERM "$$" 2>/dev/null || true
) &
WATCHDOG_PID=$!

BACKEND_NET="$(docker inspect -f '{{range $k,$v := .NetworkSettings.Networks}}{{println $k}}{{end}}' "$PROM_CONTAINER" | grep backend | head -n1)"
[[ -n "$BACKEND_NET" ]] || fail "Could not determine Prometheus backend network"
log "Using backend network: ${BACKEND_NET}"

prepare_drill_files

echo "ARMED" > "${BACKUP_DIR}/state"
MUTATIONS_STARTED=1

log "Starting temporary exporter (${EXPORTER_NAME}) on ${BACKEND_NET} (no host port, ttl=${EXPORTER_TTL_SEC}s)"
docker run -d --rm --name "$EXPORTER_NAME" \
  --network "$BACKEND_NET" --network-alias drill-exporter \
  -v "$(docker_host_path "${WORK_DIR}/Caddyfile"):/etc/caddy/Caddyfile:ro" \
  -v "$(docker_host_path "${WORK_DIR}"):/srv:ro" \
  "$CADDY_IMAGE" sh -c "timeout ${EXPORTER_TTL_SEC} caddy run --config /etc/caddy/Caddyfile" >/dev/null

log "Applying in-place drill config (job_name: gongchae-drill) and [훈련] label summaries + SIGHUP"
cat "${WORK_DIR}/prometheus.drill.yml" > "$PROM_CONFIG"
cat "${WORK_DIR}/rules_stage/gongchae.yml" > "$RULES_FILE"
prom_sighup

wait_for_drill_config_and_target present 90 || fail "gongchae-drill target did not become up in Prometheus"
assert_no_unexpected_alerts "" || fail "False alerts fired at 0/0 drill baseline"
log "DRILL_BASELINE_UP (gongchae-drill up, 0 alerts)"

inject_epoch="$(date +%s)"
write_metrics "$BUDGET_INJECT_VALUE" "$FAILING_INJECT_VALUE"
log "DRILL_INJECTED budget=${BUDGET_INJECT_VALUE} failing=${FAILING_INJECT_VALUE}"

wait_for_alert_state "GongchaeApiBudgetWarning" "firing" 120 "true" || fail "GongchaeApiBudgetWarning did not fire"
assert_no_unexpected_alerts "GongchaeApiBudgetWarning,GongchaeRegularCollectionFailing" || fail "Unexpected alert during budget firing"
log "DRILL_BUDGET_WARNING_FIRING"

wait_for_alert_state "GongchaeRegularCollectionFailing" "pending" 120 "true" || fail "GongchaeRegularCollectionFailing did not enter pending"
log "DRILL_COLLECTION_FAILING_PENDING"

# Wait until >= PENDING_HOLD_CHECK_SEC (5m) and verify for: 10m is still holding pending (not prematurely firing).
pending_check_target=$(( inject_epoch + PENDING_HOLD_CHECK_SEC ))
while [[ $(date +%s) -lt $pending_check_target ]]; do
  interruptible_sleep 5
done
wait_for_alert_state "GongchaeRegularCollectionFailing" "pending" 15 "true" || fail "GongchaeRegularCollectionFailing did not stay pending at >=${PENDING_HOLD_CHECK_SEC}s"
assert_no_unexpected_alerts "GongchaeApiBudgetWarning,GongchaeRegularCollectionFailing" || fail "Unexpected alert during 10m pending hold"
log "DRILL_COLLECTION_FAILING_STILL_PENDING_AT_5M"

remaining_wait=$(( FAILING_WAIT_TIMEOUT_SEC - ($(date +%s) - inject_epoch) ))
if [[ "$remaining_wait" -lt 60 ]]; then remaining_wait=60; fi
wait_for_alert_state "GongchaeRegularCollectionFailing" "firing" "$remaining_wait" "true" || fail "GongchaeRegularCollectionFailing did not fire after for: 10m"
elapsed_to_fire=$(( $(date +%s) - inject_epoch ))
if [[ "$elapsed_to_fire" -lt "$FAILING_MIN_ELAPSED_SEC" ]]; then
  fail "FOR_10M_VIOLATION: GongchaeRegularCollectionFailing fired in ${elapsed_to_fire}s (< ${FAILING_MIN_ELAPSED_SEC}s)"
fi
assert_no_unexpected_alerts "GongchaeApiBudgetWarning,GongchaeRegularCollectionFailing" || fail "Unexpected alert during collection failing firing"
log "DRILL_COLLECTION_FAILING_FIRING elapsed_sec=${elapsed_to_fire}"

# Allow Grafana evaluation/group_wait window before clearing (90s)
interruptible_sleep 90

write_metrics 0 0
log "DRILL_RECOVERY_INJECTED budget=0 failing=0"
wait_for_alert_state "GongchaeApiBudgetWarning" "absent" "$RESOLVE_WAIT_TIMEOUT_SEC" "false" || fail "GongchaeApiBudgetWarning did not resolve"
wait_for_alert_state "GongchaeRegularCollectionFailing" "absent" "$RESOLVE_WAIT_TIMEOUT_SEC" "false" || fail "GongchaeRegularCollectionFailing did not resolve"
log "DRILL_ALERTS_RESOLVED_IN_PROMETHEUS"

if [[ "$POST_RESOLVE_WAIT_SEC" -gt 0 ]]; then
  log "Waiting ${POST_RESOLVE_WAIT_SEC}s for Grafana [RESOLVED] delivery..."
  interruptible_sleep "$POST_RESOLVE_WAIT_SEC"
fi

restore_and_verify
log "DRILL_RUN_COMPLETE"
