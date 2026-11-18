#!/usr/bin/env bash
set -euo pipefail
umask 077

# Gongchae-Bell daily encrypted backup script (PRD §6, ADR-0012, ADR-0013, ADR-0014, ADR-0006).
# - Runs logical pg_dump -Fc inside the running Compose postgres container (never copies live postgres_data files).
# - Stages ADR-0013 host erasure journal alongside the dump.
# - Encrypts and uploads via restic to OCI Object Storage (S3-compatible), then applies keep-daily 14 + prune.
# - Keeps restic/OCI credentials in BACKUP_ENV_FILE separate from COMPOSE_ENV_FILE (DB_PASSWORD/app secrets).
# - Sends Healthchecks.io ping only after dump validation, restic backup, and prune all succeed; sends /fail on error.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEFAULT_ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"

BACKUP_ENV_FILE="${BACKUP_ENV_FILE:-/opt/gongchae-bell/backup/backup.env}"
DOCKER_BIN="${DOCKER_BIN:-docker}"
RESTIC_BIN="${RESTIC_BIN:-restic}"
CURL_BIN="${CURL_BIN:-curl}"

STAGE_DIR=""
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

  awk \
    -v p1="${RESTIC_PASSWORD:-}" \
    -v p2="${AWS_ACCESS_KEY_ID:-}" \
    -v p3="${AWS_SECRET_ACCESS_KEY:-}" \
    -v p4="${HEALTHCHECKS_BACKUP_URL:-}" \
    -v p5="$hc_token" '
  function mask(line, s,   idx) {
    if (s == "") return line
    while ((idx = index(line, s)) > 0) {
      line = substr(line, 1, idx - 1) "[REDACTED]" substr(line, idx + length(s))
    }
    return line
  }
  {
    line = $0
    line = mask(line, p1)
    line = mask(line, p2)
    line = mask(line, p3)
    line = mask(line, p4)
    line = mask(line, p5)
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
  for bin in "$DOCKER_BIN" "$RESTIC_BIN" "$CURL_BIN" flock awk stat mktemp sha256sum; do
    command -v "$bin" >/dev/null 2>&1 || fail "Required tool not found: $bin"
  done
}

check_env_file_permissions() {
  local path="$1"
  local meta mode owner cur_uid

  [[ -e "$path" ]] || fail "Backup env file does not exist: $path"
  [[ ! -L "$path" ]] || fail "Backup env file must not be a symlink: $path"
  [[ -f "$path" ]] || fail "Backup env file must be a regular file: $path"

  if meta="$(stat -c '%a %u' "$path" 2>/dev/null)"; then
    :
  elif meta="$(stat -f '%Lp %u' "$path" 2>/dev/null)"; then
    :
  else
    fail "Unable to inspect permissions of backup env file: $path"
  fi

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
  BACKUP_KEEP_DAILY="${BACKUP_KEEP_DAILY:-14}"
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
  [[ "$BACKUP_KEEP_DAILY" =~ ^[1-9][0-9]*$ ]] || fail "BACKUP_KEEP_DAILY must be a positive integer: $BACKUP_KEEP_DAILY"

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
  grep -qE '^[[:space:]]*ALERTS_ENABLED=true([[:space:]]|#|$)' "$COMPOSE_ENV_FILE"
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

log_snapshot_retention_summary() {
  local snap_out summary_line
  snap_out="$(mktemp "$STAGE_DIR/snapshots.XXXXXX")"
  if ! "$RESTIC_BIN" snapshots --tag gongchae-bell --compact >"$snap_out" 2>&1; then
    redact_stream <"$snap_out" >&2
    rm -f "$snap_out"
    fail "RESTIC_SNAPSHOTS_FAIL unable to list snapshots after prune"
  fi
  redact_stream <"$snap_out"
  summary_line="$(awk -v keep="$BACKUP_KEEP_DAILY" '
    /^[0-9a-f]{8}[[:space:]]+[0-9]{4}-[0-9]{2}-[0-9]{2}[[:space:]]+[0-9]{2}:[0-9]{2}:[0-9]{2}/ {
      count++
      ts = $2 "T" $3 "Z"
      if (count == 1) oldest = ts
      latest_id = $1
      latest_ts = ts
    }
    END {
      if (count > 0) {
        printf "SNAPSHOT_RETENTION_SUMMARY count=%d oldest_utc=%s latest_id=%s latest_utc=%s keep_daily=%s",
          count, oldest, latest_id, latest_ts, keep
      } else {
        printf "SNAPSHOT_RETENTION_SUMMARY count=0 oldest_utc=none latest_id=none latest_utc=none keep_daily=%s", keep
      }
    }' "$snap_out")"
  rm -f "$snap_out"
  log "$summary_line"
  printf '%s\n' "$summary_line" >"$STAGE_DIR/retention.summary"
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

cmd_run() {
  IN_BACKUP_RUN=1
  STAGE_DIR="$(mktemp -d "${BACKUP_STAGE_ROOT%/}/gongchae-backup.XXXXXX")"
  chmod 0700 "$STAGE_DIR"

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
keep_daily=$BACKUP_KEEP_DAILY
EOF

  ensure_restic_repo

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

  log "Applying restic retention policy (--keep-daily $BACKUP_KEEP_DAILY --prune)"
  if ! run_redacted "$RESTIC_BIN" forget --tag gongchae-bell --keep-daily "$BACKUP_KEEP_DAILY" --prune; then
    fail "RESTIC_PRUNE_FAIL"
  fi

  log_snapshot_retention_summary
  local summary
  summary="$(cat "$STAGE_DIR/retention.summary")"

  if ! send_heartbeat "" "BACKUP_OK dump_bytes=$dump_bytes dump_sha256=$dump_sha erasure_included=$erasure_included $summary"; then
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
