#!/usr/bin/env bash
set -euo pipefail
umask 077

# Gongchae-Bell daily encrypted backup script (PRD §6, ADR-0012, ADR-0013, ADR-0014, ADR-0006, ADR-0017).
# - First expires every gongchae-bell snapshot older than 14 days by the run's wall clock (explicit IDs, no
#   "keep latest" exception, zero copies allowed), prunes their data, and checks none is left (ADR-0017).
#   This runs before the dump, so a failing dump or upload never keeps expired copies alive.
# - Runs logical pg_dump -Fc inside the running Compose postgres container (never copies live postgres_data files).
# - Stages ADR-0013 host erasure journal alongside the dump.
# - Encrypts and uploads via restic to OCI Object Storage (S3-compatible).
# - Keeps restic/OCI credentials in BACKUP_ENV_FILE separate from COMPOSE_ENV_FILE (DB_PASSWORD/app secrets).
# - Sends Healthchecks.io ping only after expiry, prune, dump validation and restic backup all succeed; /fail on error.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEFAULT_ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"

BACKUP_ENV_FILE="${BACKUP_ENV_FILE:-/opt/gongchae-bell/backup/backup.env}"
DOCKER_BIN="${DOCKER_BIN:-docker}"
RESTIC_BIN="${RESTIC_BIN:-restic}"
CURL_BIN="${CURL_BIN:-curl}"
PYTHON_BIN="${PYTHON_BIN:-python3}"
EXPIRE_SELECT="$SCRIPT_DIR/expire-select.py"
# User decision 2026-11-19 (ADR-0017): fixed 14 days by wall clock, not a tunable count.
RETENTION_SECONDS=$((14 * 86400))

STAGE_DIR=""
RUN_NOW_EPOCH=""
RETENTION_SUMMARY=""
IN_BACKUP_RUN=0
BACKUP_SUCCEEDED=0
FAIL_REASON=""

log() {
  printf '[%s] %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*"
}

redact_stream() {
  local hc_token=""
  if [[ -n "${HEALTHCHECKS_BACKUP_URL:-}" ]]; then
    local trimmed="${HEALTHCHECKS_BACKUP_URL%/}"
    local candidate="${trimmed##*/}"
    if [[ "$candidate" != "$trimmed" && ${#candidate} -ge 6 ]]; then
      hc_token="$candidate"
    fi
  fi

  # Pass literals via environment (ENVIRON) so awk never interprets backslashes (\n, \t, \\),
  # and scan single-pass longest-match-first so secrets inside "[REDACTED]" never loop.
  RESTIC_PASSWORD="${RESTIC_PASSWORD:-}" \
  AWS_ACCESS_KEY_ID="${AWS_ACCESS_KEY_ID:-}" \
  AWS_SECRET_ACCESS_KEY="${AWS_SECRET_ACCESS_KEY:-}" \
  HEALTHCHECKS_BACKUP_URL="${HEALTHCHECKS_BACKUP_URL:-}" \
  HC_REDACT_TOKEN="$hc_token" \
  awk '
  BEGIN {
    n_raw = 0
    cand[1] = ENVIRON["RESTIC_PASSWORD"]
    cand[2] = ENVIRON["AWS_ACCESS_KEY_ID"]
    cand[3] = ENVIRON["AWS_SECRET_ACCESS_KEY"]
    cand[4] = ENVIRON["HEALTHCHECKS_BACKUP_URL"]
    cand[5] = ENVIRON["HC_REDACT_TOKEN"]
    for (i = 1; i <= 5; i++) {
      if (cand[i] != "") raw[++n_raw] = cand[i]
    }
    for (i = 2; i <= n_raw; i++) {
      tmp = raw[i]
      j = i - 1
      while (j >= 1 && length(raw[j]) < length(tmp)) {
        raw[j + 1] = raw[j]
        j--
      }
      raw[j + 1] = tmp
    }
  }
  function mask_all(line,   out, rest, i, idx, best_idx, best_len) {
    if (n_raw == 0) return line
    out = ""
    rest = line
    while (rest != "") {
      best_idx = 0
      best_len = 0
      for (i = 1; i <= n_raw; i++) {
        idx = index(rest, raw[i])
        if (idx > 0 && (best_idx == 0 || idx < best_idx || (idx == best_idx && length(raw[i]) > best_len))) {
          best_idx = idx
          best_len = length(raw[i])
        }
      }
      if (best_idx == 0) {
        out = out rest
        break
      }
      out = out substr(rest, 1, best_idx - 1) "[REDACTED]"
      rest = substr(rest, best_idx + best_len)
    }
    return out
  }
  {
    line = mask_all($0)
    gsub(/(https?:\/\/)[^[:space:]@\/:]+:[^[:space:]@\/]+@/, "\\1[REDACTED]@", line)
    gsub(/https?:\/\/[^[:space:]\/]*hc-ping[^[:space:]]*/, "[REDACTED_HC_URL]", line)
    print line
  }'
}

fail() {
  local msg
  msg="$(printf '%s' "$*" | redact_stream)"
  FAIL_REASON="$msg"
  log "ERROR: $msg" >&2
  exit 1
}

run_redacted() {
  local out_file rc=0
  if [[ -n "$STAGE_DIR" && -d "$STAGE_DIR" ]]; then
    out_file="$STAGE_DIR/cmd.out"
  else
    out_file="$(mktemp)"
  fi
  if "$@" >"$out_file" 2>&1; then
    rc=0
  else
    rc=$?
  fi
  if [[ -s "$out_file" ]]; then
    redact_stream <"$out_file"
  fi
  if [[ -z "$STAGE_DIR" || ! -d "$STAGE_DIR" ]]; then
    rm -f "$out_file"
  else
    rm -f "$out_file"
  fi
  return "$rc"
}

send_heartbeat() {
  local suffix="$1"
  local body="$2"
  local url ping_err rc=0

  if [[ -z "${HEALTHCHECKS_BACKUP_URL:-}" ]]; then
    log "HEARTBEAT_SKIPPED no HEALTHCHECKS_BACKUP_URL configured (kind=${suffix:-ok})"
    return 0
  fi

  url="${HEALTHCHECKS_BACKUP_URL%/}${suffix}"
  ping_err="$(mktemp)"
  if "$CURL_BIN" -fsS -o /dev/null --connect-timeout 5 --max-time 10 -X POST --data-raw "$body" "$url" 2>"$ping_err"; then
    rc=0
  else
    rc=$?
  fi
  if [[ -s "$ping_err" ]]; then
    redact_stream <"$ping_err" >&2
  fi
  rm -f "$ping_err"
  return "$rc"
}

on_exit() {
  local exit_rc=$?
  if [[ "$IN_BACKUP_RUN" -eq 1 && "$BACKUP_SUCCEEDED" -ne 1 ]]; then
    local reason="${FAIL_REASON:-backup failed with exit=$exit_rc}"
    send_heartbeat "/fail" "$reason" || log "WARN: failed to deliver Healthchecks /fail ping" >&2
  fi
  if [[ -n "$STAGE_DIR" && -d "$STAGE_DIR" ]]; then
    rm -rf "$STAGE_DIR"
  fi
}

require_tools() {
  local bin
  for bin in "$DOCKER_BIN" "$RESTIC_BIN" "$CURL_BIN" "$PYTHON_BIN" flock awk stat mktemp sha256sum; do
    command -v "$bin" >/dev/null 2>&1 || fail "Required tool not found: $bin"
  done
}

stat_mode_uid() {
  local path="$1"
  local meta
  if meta="$(stat -c '%a %u' "$path" 2>/dev/null)"; then
    printf '%s' "$meta"
  elif meta="$(stat -f '%Lp %u' "$path" 2>/dev/null)"; then
    printf '%s' "$meta"
  else
    fail "Unable to inspect permissions of path: $path"
  fi
}

check_env_file_permissions() {
  local path="$1"
  local meta mode owner cur_uid

  [[ -e "$path" ]] || fail "Backup env file does not exist: $path"
  [[ ! -L "$path" ]] || fail "Backup env file must not be a symlink: $path"
  [[ -f "$path" ]] || fail "Backup env file must be a regular file: $path"

  meta="$(stat_mode_uid "$path")"
  mode="${meta%% *}"
  owner="${meta##* }"
  cur_uid="$(id -u)"

  if [[ "$owner" != "$cur_uid" && "$owner" != "0" ]]; then
    fail "ENV_PERM_FAIL $path owner uid=$owner is not current uid=$cur_uid or root"
  fi
  if (( (8#$mode & 077) != 0 )); then
    fail "ENV_PERM_FAIL $path permissions mode=$mode must restrict group/other access (expected 0600 or 0400)"
  fi
}

load_backup_env() {
  check_env_file_permissions "$BACKUP_ENV_FILE"

  local line key val
  while IFS= read -r line || [[ -n "$line" ]]; do
    line="${line%$'\r'}"
    [[ "$line" =~ ^[[:space:]]*(#|$) ]] && continue
    [[ "$line" =~ ^[[:space:]]*([A-Za-z_][A-Za-z0-9_]*)=(.*)$ ]] || fail "Invalid line in $BACKUP_ENV_FILE"
    key="${BASH_REMATCH[1]}"
    val="${BASH_REMATCH[2]}"
    if [[ "$val" =~ ^\'(.*)\'$ ]] || [[ "$val" =~ ^\"(.*)\"$ ]]; then
      val="${BASH_REMATCH[1]}"
    fi
    printf -v "$key" '%s' "$val"
    export "${key?}"
  done <"$BACKUP_ENV_FILE"

  COMPOSE_FILE="${COMPOSE_FILE:-/opt/gongchae-bell/compose.prod.yml}"
  COMPOSE_ENV_FILE="${COMPOSE_ENV_FILE:-/opt/gongchae-bell/.env}"
  DEPLOY_STATE_DIR="${DEPLOY_STATE_DIR:-/opt/gongchae-bell/deploy/state}"
  POSTGRES_SERVICE="${POSTGRES_SERVICE:-postgres}"
  ERASURE_LOG_PATH="${ERASURE_LOG_PATH:-/var/lib/gongchae-bell/erasure/erasure.log}"
  REQUIRE_ERASURE_LOG="${REQUIRE_ERASURE_LOG:-auto}"
  BACKUP_LOCK_FILE="${BACKUP_LOCK_FILE:-/opt/gongchae-bell/backup/backup.lock}"
  BACKUP_LOCK_WAIT_SECONDS="${BACKUP_LOCK_WAIT_SECONDS:-0}"
  BACKUP_STAGE_ROOT="${BACKUP_STAGE_ROOT:-/tmp}"
  BACKUP_HOST_TAG="${BACKUP_HOST_TAG:-}"
  RESTIC_INIT_IF_MISSING="${RESTIC_INIT_IF_MISSING:-false}"
  HEALTHCHECKS_BACKUP_URL="${HEALTHCHECKS_BACKUP_URL:-}"
  HEALTHCHECKS_BACKUP_URL="${HEALTHCHECKS_BACKUP_URL%/}"

  [[ -n "${RESTIC_REPOSITORY:-}" ]] || fail "Missing RESTIC_REPOSITORY in $BACKUP_ENV_FILE"
  [[ -n "${RESTIC_PASSWORD:-}" ]] || fail "Missing RESTIC_PASSWORD in $BACKUP_ENV_FILE"
  if [[ "$RESTIC_REPOSITORY" == s3:* ]]; then
    [[ -n "${AWS_ACCESS_KEY_ID:-}" ]] || fail "Missing AWS_ACCESS_KEY_ID for S3 repository"
    [[ -n "${AWS_SECRET_ACCESS_KEY:-}" ]] || fail "Missing AWS_SECRET_ACCESS_KEY for S3 repository"
  fi
  # A leftover count setting would suggest a policy that no longer runs.
  [[ -z "${BACKUP_KEEP_DAILY:-}" ]] || fail "BACKUP_KEEP_DAILY is no longer supported: retention is a fixed 14 days by wall clock (ADR-0017); remove it from $BACKUP_ENV_FILE"

  export RESTIC_REPOSITORY RESTIC_PASSWORD
  export AWS_ACCESS_KEY_ID="${AWS_ACCESS_KEY_ID:-}"
  export AWS_SECRET_ACCESS_KEY="${AWS_SECRET_ACCESS_KEY:-}"
  export AWS_DEFAULT_REGION="${AWS_DEFAULT_REGION:-}"
}

acquire_lock() {
  mkdir -p "$(dirname "$BACKUP_LOCK_FILE")"
  exec 9>"$BACKUP_LOCK_FILE"
  if [[ "$BACKUP_LOCK_WAIT_SECONDS" -gt 0 ]]; then
    if ! flock -w "$BACKUP_LOCK_WAIT_SECONDS" 9; then
      log "ERROR: LOCK_HELD lock_file=$BACKUP_LOCK_FILE wait=${BACKUP_LOCK_WAIT_SECONDS}s" >&2
      exit 20
    fi
  else
    if ! flock -n 9; then
      log "ERROR: LOCK_HELD lock_file=$BACKUP_LOCK_FILE" >&2
      exit 20
    fi
  fi
}

read_state_file() {
  local path="$1"
  if [[ -f "$path" ]]; then
    tr -d '[:space:]' <"$path"
  fi
}

compose_exec_with_tags() {
  local blue_tag green_tag
  blue_tag="$(read_state_file "$DEPLOY_STATE_DIR/blue_tag")"
  green_tag="$(read_state_file "$DEPLOY_STATE_DIR/green_tag")"
  local args=(-f "$COMPOSE_FILE")
  if [[ -f "$COMPOSE_ENV_FILE" ]]; then
    args+=(--env-file "$COMPOSE_ENV_FILE")
  fi
  APP_BLUE_TAG="${APP_BLUE_TAG:-${blue_tag:-unset}}" \
  APP_GREEN_TAG="${APP_GREEN_TAG:-${green_tag:-unset}}" \
    "$DOCKER_BIN" compose "${args[@]}" "$@"
}

alerts_enabled_in_compose_env() {
  [[ -f "$COMPOSE_ENV_FILE" ]] || return 1
  local val="" line
  while IFS= read -r line || [[ -n "$line" ]]; do
    line="${line%$'\r'}"
    [[ "$line" =~ ^[[:space:]]*(#|$) ]] && continue
    if [[ "$line" =~ ^[[:space:]]*(export[[:space:]]+)?ALERTS_ENABLED=(.*)$ ]]; then
      val="${BASH_REMATCH[2]}"
      if [[ "$val" =~ ^[[:space:]]*\'([^\']*)\'([[:space:]]*(#.*)?)?$ ]] || [[ "$val" =~ ^[[:space:]]*\"([^\"]*)\"([[:space:]]*(#.*)?)?$ ]]; then
        val="${BASH_REMATCH[1]}"
      else
        val="${val%%#*}"
        val="${val#"${val%%[![:space:]]*}"}"
        val="${val%"${val##*[![:space:]]}"}"
      fi
    fi
  done <"$COMPOSE_ENV_FILE"
  [[ "${val,,}" == "true" ]]
}

stage_postgres_dump() {
  local dump_file="$STAGE_DIR/gongchae-bell.dump"
  local toc_file="$STAGE_DIR/dump.toc"
  local running_ids

  [[ -f "$COMPOSE_FILE" ]] || fail "Missing compose file: $COMPOSE_FILE"
  if ! running_ids="$(compose_exec_with_tags ps --status running -q "$POSTGRES_SERVICE")"; then
    fail "Failed to query running status for service=$POSTGRES_SERVICE"
  fi
  [[ -n "$running_ids" ]] || fail "PostgreSQL service '$POSTGRES_SERVICE' is not running"

  if ! compose_exec_with_tags exec -T "$POSTGRES_SERVICE" sh -lc 'pg_isready -U "$POSTGRES_USER" -d "$POSTGRES_DB" >/dev/null'; then
    fail "PostgreSQL service '$POSTGRES_SERVICE' failed pg_isready"
  fi

  log "Dumping PostgreSQL service=$POSTGRES_SERVICE via pg_dump -Fc"
  if ! compose_exec_with_tags exec -T "$POSTGRES_SERVICE" \
      sh -lc 'PGPASSWORD="$POSTGRES_PASSWORD" pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc' >"$dump_file"; then
    fail "PG_DUMP_FAIL pg_dump exited non-zero"
  fi

  [[ -s "$dump_file" ]] || fail "PG_DUMP_FAIL produced empty dump file"

  if ! compose_exec_with_tags exec -T "$POSTGRES_SERVICE" pg_restore --list <"$dump_file" >"$toc_file"; then
    fail "PG_DUMP_VERIFY_FAIL pg_restore --list failed"
  fi
  if ! grep -vqE '^[[:space:]]*(;|$)' "$toc_file"; then
    fail "PG_DUMP_VERIFY_FAIL pg_restore --list returned zero TOC entries"
  fi
}

stage_erasure_log() {
  local required="false"
  case "$REQUIRE_ERASURE_LOG" in
    true) required="true" ;;
    false) required="false" ;;
    auto)
      if alerts_enabled_in_compose_env; then
        required="true"
      fi
      ;;
    *) fail "Invalid REQUIRE_ERASURE_LOG='$REQUIRE_ERASURE_LOG' (expected auto|true|false)" ;;
  esac

  if [[ -e "$ERASURE_LOG_PATH" ]]; then
    [[ ! -L "$ERASURE_LOG_PATH" ]] || fail "ERASURE_LOG_INVALID must not be a symlink: $ERASURE_LOG_PATH"
    [[ -f "$ERASURE_LOG_PATH" ]] || fail "ERASURE_LOG_NOT_REGULAR_FILE expected file at $ERASURE_LOG_PATH"
    cp "$ERASURE_LOG_PATH" "$STAGE_DIR/erasure.log"
    # Guard against catching a line mid-write by checking trailing newline and retrying once.
    if [[ -s "$STAGE_DIR/erasure.log" ]] && [[ "$(tail -c 1 "$STAGE_DIR/erasure.log" | wc -l)" -ne 1 ]]; then
      sleep 0.1
      cp "$ERASURE_LOG_PATH" "$STAGE_DIR/erasure.log"
      if [[ -s "$STAGE_DIR/erasure.log" ]] && [[ "$(tail -c 1 "$STAGE_DIR/erasure.log" | wc -l)" -ne 1 ]]; then
        fail "ERASURE_LOG_TORN trailing newline missing in $ERASURE_LOG_PATH"
      fi
    fi
    return 0
  fi

  if [[ "$required" == "true" ]]; then
    fail "ERASURE_LOG_MISSING required file missing at $ERASURE_LOG_PATH"
  fi

  log "ERASURE_LOG_ABSENT_M1_ONLY skipping absent $ERASURE_LOG_PATH (REQUIRE_ERASURE_LOG=$REQUIRE_ERASURE_LOG)"
}

ensure_restic_repo() {
  if run_redacted "$RESTIC_BIN" cat config >/dev/null; then
    return 0
  fi
  if [[ "$RESTIC_INIT_IF_MISSING" == "true" ]]; then
    log "Initializing restic repository (RESTIC_INIT_IF_MISSING=true)"
    run_redacted "$RESTIC_BIN" init || fail "RESTIC_INIT_FAIL unable to initialize repository"
    return 0
  fi
  fail "RESTIC_REPO_UNAVAILABLE repository check failed (run 'backup.sh init' or verify credentials/network)"
}

# Lists gongchae-bell snapshots and picks those older than 14 days at RUN_NOW_EPOCH (expire-select.py).
# Leaves the summary line in retention.summary and the expired full IDs in retention.ids.
select_expired() {
  local snaps="$STAGE_DIR/snapshots.json" picked="$STAGE_DIR/retention.picked" err="$STAGE_DIR/select.err"
  if ! "$RESTIC_BIN" snapshots --tag gongchae-bell --json >"$snaps" 2>"$err"; then
    redact_stream <"$err" >&2
    fail "RESTIC_SNAPSHOTS_FAIL unable to list snapshots for retention; nothing was deleted"
  fi
  if ! "$PYTHON_BIN" -I "$EXPIRE_SELECT" "$RUN_NOW_EPOCH" "$RETENTION_SECONDS" <"$snaps" >"$picked" 2>"$err"; then
    redact_stream <"$err" >&2
    fail "RETENTION_SELECT_FAIL snapshot list rejected; nothing was deleted"
  fi
  head -n 1 "$picked" >"$STAGE_DIR/retention.summary"
  tail -n +2 "$picked" | awk '{print $1}' >"$STAGE_DIR/retention.ids"
}

# ADR-0017: delete every snapshot past 14 days (no latest-copy exception), drop its data, then prove none is left.
apply_retention() {
  local -a ids=()
  select_expired
  mapfile -t ids <"$STAGE_DIR/retention.ids"
  log "RETENTION_SELECT $(cat "$STAGE_DIR/retention.summary")"
  if (( ${#ids[@]} > 0 )); then
    log "Forgetting ${#ids[@]} snapshot(s) older than 14 days: ${ids[*]}"
    run_redacted "$RESTIC_BIN" forget "${ids[@]}" \
      || fail "RESTIC_FORGET_FAIL expired=${#ids[@]} snapshots are still listed; retention not met"
  fi
  # Every run, so data left by an earlier failed prune is removed too. --max-unused 0 repacks every pack that
  # holds forgotten data instead of tolerating restic's default 5% of unused space.
  run_redacted "$RESTIC_BIN" prune --max-unused 0 \
    || fail "RESTIC_PRUNE_FAIL forgotten=${#ids[@]} data of forgotten snapshots may still be stored; retention not met"
  select_expired
  [[ ! -s "$STAGE_DIR/retention.ids" ]] \
    || fail "RETENTION_VERIFY_FAIL snapshots older than 14 days are still listed after forget"
  RETENTION_SUMMARY="SNAPSHOT_RETENTION_SUMMARY policy=wall-clock-14d forgotten=${#ids[@]} $(cat "$STAGE_DIR/retention.summary")"
  log "$RETENTION_SUMMARY"
}

cmd_init() {
  ensure_restic_repo_force_init() {
    if run_redacted "$RESTIC_BIN" cat config >/dev/null; then
      log "RESTIC_REPO_ALREADY_INITIALIZED"
      return 0
    fi
    run_redacted "$RESTIC_BIN" init || fail "RESTIC_INIT_FAIL unable to initialize repository"
    log "RESTIC_REPO_INITIALIZED"
  }
  ensure_restic_repo_force_init
}

cmd_snapshots() {
  run_redacted "$RESTIC_BIN" snapshots --tag gongchae-bell --compact || fail "RESTIC_SNAPSHOTS_FAIL"
}

prepare_stage_dir() {
  # Fixed path under flock keeps restic snapshot paths stable across runs without relaxing staging permissions.
  local dir="${BACKUP_STAGE_ROOT%/}/gongchae-backup-stage"
  [[ ! -L "$dir" ]] || fail "STAGE_DIR_SYMLINK refusing symlink at $dir"
  if [[ -e "$dir" ]]; then
    local meta owner
    meta="$(stat_mode_uid "$dir")"
    owner="${meta##* }"
    [[ "$owner" == "$(id -u)" ]] || fail "STAGE_DIR_OWNER unexpected owner uid=$owner for $dir"
    rm -rf "$dir"
  fi
  mkdir -m 0700 "$dir"
  local meta mode owner
  meta="$(stat_mode_uid "$dir")"
  mode="${meta%% *}"
  owner="${meta##* }"
  [[ "$owner" == "$(id -u)" ]] || fail "STAGE_DIR_OWNER mismatch on $dir"
  (( (8#$mode & 077) == 0 )) || fail "STAGE_DIR_PERM unexpected mode=$mode on $dir"
  STAGE_DIR="$dir"
}

cmd_run() {
  IN_BACKUP_RUN=1
  # One clock reading per run: expiry and its check use the same cutoff.
  RUN_NOW_EPOCH="$(date -u +%s)"
  prepare_stage_dir

  # Expiry first (ADR-0017): a failed dump or upload below must not keep expired copies alive.
  ensure_restic_repo
  apply_retention

  stage_postgres_dump
  stage_erasure_log

  local dump_bytes dump_sha toc_entries erasure_included="false" erasure_bytes=0 erasure_lines=0
  dump_bytes="$(wc -c <"$STAGE_DIR/gongchae-bell.dump" | tr -d '[:space:]')"
  dump_sha="$(sha256sum "$STAGE_DIR/gongchae-bell.dump" | awk '{print $1}')"
  toc_entries="$(grep -cvE '^[[:space:]]*(;|$)' "$STAGE_DIR/dump.toc" || echo 0)"
  if [[ -f "$STAGE_DIR/erasure.log" ]]; then
    erasure_included="true"
    erasure_bytes="$(wc -c <"$STAGE_DIR/erasure.log" | tr -d '[:space:]')"
    erasure_lines="$(wc -l <"$STAGE_DIR/erasure.log" | tr -d '[:space:]')"
  fi

  cat >"$STAGE_DIR/backup-meta.txt" <<EOF
backup_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)
dump_bytes=$dump_bytes
dump_sha256=$dump_sha
toc_entries=$toc_entries
erasure_included=$erasure_included
erasure_bytes=$erasure_bytes
erasure_lines=$erasure_lines
retention=wall-clock-14d
EOF

  local -a backup_items=("gongchae-bell.dump" "backup-meta.txt")
  if [[ "$erasure_included" == "true" ]]; then
    backup_items+=("erasure.log")
  fi

  local -a host_args=()
  if [[ -n "$BACKUP_HOST_TAG" ]]; then
    host_args=(--host "$BACKUP_HOST_TAG")
  fi

  log "Uploading encrypted backup snapshot via restic (dump_bytes=$dump_bytes erasure_included=$erasure_included)"
  if ! (cd "$STAGE_DIR" && run_redacted "$RESTIC_BIN" backup --tag gongchae-bell --tag daily "${host_args[@]}" "${backup_items[@]}"); then
    fail "RESTIC_BACKUP_FAIL"
  fi

  if ! send_heartbeat "" "BACKUP_OK dump_bytes=$dump_bytes dump_sha256=$dump_sha erasure_included=$erasure_included $RETENTION_SUMMARY"; then
    fail "HEARTBEAT_PING_FAIL success heartbeat failed"
  fi

  BACKUP_SUCCEEDED=1
  log "BACKUP_OK dump_bytes=$dump_bytes dump_sha256=$dump_sha toc_entries=$toc_entries erasure_included=$erasure_included erasure_lines=$erasure_lines"
}

usage() {
  cat <<'EOF'
Usage:
  backup.sh [run|init|snapshots] [--env-file PATH]
EOF
}

main() {
  local subcmd="run"
  if [[ $# -gt 0 && "$1" != --* && "$1" != -* ]]; then
    subcmd="$1"
    shift
  fi

  while [[ $# -gt 0 ]]; do
    case "$1" in
      --env-file) BACKUP_ENV_FILE="${2:-}"; shift 2 || { usage >&2; exit 2; } ;;
      -h|--help) usage; return 0 ;;
      *) fail "Unknown argument: $1" ;;
    esac
  done

  trap on_exit EXIT
  require_tools
  load_backup_env
  acquire_lock

  case "$subcmd" in
    run) cmd_run ;;
    init) cmd_init ;;
    snapshots) cmd_snapshots ;;
    *) fail "Unknown subcommand: $subcmd" ;;
  esac
}

main "$@"
