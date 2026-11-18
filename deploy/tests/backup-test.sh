#!/usr/bin/env bash
set -euo pipefail

# Isolated unit & double test suite for deploy/backup/backup.sh.
# Verifies:
# - bash syntax
# - backup.env permission/ownership enforcement & secret separation from compose .env
# - happy path (pg_dump -Fc -> pg_restore --list -> erasure.log -> restic backup -> forget --keep-daily 14 --prune -> snapshots -> success heartbeat)
# - pg_dump failure & corrupt dump (pg_restore --list fail) -> no restic, no success ping, /fail ping
# - erasure.log contract (ALERTS_ENABLED=true missing fail, directory fail, M1 absent ok, empty prepared file included)
# - restic backup failure, restic forget/prune failure, heartbeat failure
# - real flock concurrency exclusion (exit 20)
# - secret redaction (RESTIC_PASSWORD with regex chars, AWS keys, Healthchecks URL & token stripped from stdout/stderr)
# - init subcommand

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
BACKUP_SH="$REPO_ROOT/deploy/backup/backup.sh"

TMP_ROOT="$(mktemp -d)"
trap 'rm -rf "$TMP_ROOT"' EXIT

assert_eq() {
  local expected="$1" actual="$2" msg="$3"
  if [[ "$expected" != "$actual" ]]; then
    printf 'ASSERT_EQ FAILED (%s): expected=[%s] actual=[%s]\n' "$msg" "$expected" "$actual" >&2
    exit 1
  fi
}

assert_file_contains() {
  local path="$1" needle="$2" msg="$3"
  if ! grep -qF "$needle" "$path"; then
    printf 'ASSERT_CONTAINS FAILED (%s): %s missing [%s]\n' "$msg" "$path" "$needle" >&2
    cat "$path" >&2 || true
    exit 1
  fi
}

assert_file_not_contains() {
  local path="$1" needle="$2" msg="$3"
  if grep -qF "$needle" "$path"; then
    printf 'ASSERT_NOT_CONTAINS FAILED (%s): %s unexpectedly contains [%s]\n' "$msg" "$path" "$needle" >&2
    cat "$path" >&2 || true
    exit 1
  fi
}

setup_env() {
  local case_name="$1"
  local case_dir="$TMP_ROOT/$case_name"
  mkdir -p "$case_dir/bin" "$case_dir/state" "$case_dir/erasure" "$case_dir/logs"

  cat >"$case_dir/compose.prod.yml" <<'EOF'
# dummy compose file for mock docker
EOF
  cat >"$case_dir/compose.env" <<'EOF'
DB_USER=gongchae
DB_PASSWORD=COMPOSE_DB_SECRET_DO_NOT_LEAK
ALERTS_ENABLED=false
ALERTS_TOKEN_KEY=COMPOSE_TOKEN_SECRET_DO_NOT_LEAK
APP_IMAGE=ghcr.io/lcjam/gongchae-bell
APP_BLUE_TAG=
APP_GREEN_TAG=
EOF
  chmod 0600 "$case_dir/compose.env"

  printf '00000000-0000-0000-0000-000000000001\t2026-11-18T01:00:00Z\n' >"$case_dir/erasure/erasure.log"
  chmod 0600 "$case_dir/erasure/erasure.log"

  cat >"$case_dir/backup.env" <<EOF
RESTIC_REPOSITORY=s3:https://ns.compat.objectstorage.ap-tokyo-1.oraclecloud.com/gongchae-backups
RESTIC_PASSWORD=p@ss/w&rd\$1!test
AWS_ACCESS_KEY_ID=mock_access_key_placeholder
AWS_SECRET_ACCESS_KEY=mock_secret_key_placeholder
AWS_DEFAULT_REGION=ap-tokyo-1
HEALTHCHECKS_BACKUP_URL=https://hc-ping.com/deadbeef-1111-2222-3333-444455556666
COMPOSE_FILE=$case_dir/compose.prod.yml
COMPOSE_ENV_FILE=$case_dir/compose.env
DEPLOY_STATE_DIR=$case_dir/state
ERASURE_LOG_PATH=$case_dir/erasure/erasure.log
BACKUP_LOCK_FILE=$case_dir/backup.lock
BACKUP_STAGE_ROOT=$case_dir
EOF
  chmod 0600 "$case_dir/backup.env"

  cat >"$case_dir/bin/docker" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
LOG_DIR="${MOCK_LOG_DIR:?}"
echo "docker $*" >>"$LOG_DIR/commands.log"

if [[ "${1:-}" == "compose" ]]; then
  shift
  while [[ $# -gt 0 ]]; do
    case "$1" in
      -f|--env-file|--profile) shift 2 ;;
      *) break ;;
    esac
  done
  subcmd="${1:-}"
  shift || true
  case "$subcmd" in
    ps)
      if [[ "${MOCK_PG_DOWN:-0}" == "1" ]]; then
        exit 0
      fi
      echo "mock-postgres-1"
      exit 0
      ;;
    exec)
      shift # -T
      svc="$1"
      shift
      if [[ "$*" == *"pg_isready"* ]]; then
        [[ "${MOCK_PG_NOT_READY:-0}" == "1" ]] && exit 1
        exit 0
      fi
      if [[ "$*" == *"pg_dump"* ]]; then
        [[ "${MOCK_PG_DUMP_FAIL:-0}" == "1" ]] && exit 2
        if [[ "${MOCK_PG_DUMP_EMPTY:-0}" == "1" ]]; then
          exit 0
        fi
        printf 'PGDMP-MOCK-CUSTOM-FORMAT'
        exit 0
      fi
      if [[ "$*" == *"pg_restore --list"* ]]; then
        cat >/dev/null
        [[ "${MOCK_PG_RESTORE_LIST_FAIL:-0}" == "1" ]] && exit 3
        if [[ "${MOCK_PG_RESTORE_LIST_EMPTY:-0}" == "1" ]]; then
          printf '; Archive created at 2026-11-18\n'
          exit 0
        fi
        printf '; Archive created at 2026-11-18\n1234; 1259 16385 TABLE public posting gongchae\n'
        exit 0
      fi
      ;;
  esac
fi
exit 0
EOF

  cat >"$case_dir/bin/restic" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
LOG_DIR="${MOCK_LOG_DIR:?}"
echo "restic $*" >>"$LOG_DIR/commands.log"
# Verify DB secrets from compose.env were NOT leaked into restic environment
if [[ -n "${DB_PASSWORD:-}" || -n "${ALERTS_TOKEN_KEY:-}" ]]; then
  echo "ERROR: compose secrets leaked into restic environment!" >&2
  exit 99
fi

cmd="${1:-}"
case "$cmd" in
  cat)
    if [[ "${MOCK_RESTIC_UNINIT:-0}" == "1" ]]; then
      echo "Fatal: unable to open config file: does not exist" >&2
      exit 10
    fi
    exit 0
    ;;
  init)
    [[ "${MOCK_RESTIC_INIT_FAIL:-0}" == "1" ]] && exit 11
    echo "created restic repository"
    exit 0
    ;;
  backup)
    # Record staged files seen inside CWD
    ls -1 | sort >"$LOG_DIR/staged_files.txt"
    if [[ "${MOCK_RESTIC_LEAK_SECRET:-0}" == "1" ]]; then
      echo "debug password=${RESTIC_PASSWORD:-} key=${AWS_ACCESS_KEY_ID:-} secret=${AWS_SECRET_ACCESS_KEY:-}" >&2
    fi
    [[ "${MOCK_RESTIC_BACKUP_FAIL:-0}" == "1" ]] && exit 12
    echo "snapshot a1b2c3d4 saved"
    exit 0
    ;;
  forget)
    [[ "${MOCK_RESTIC_PRUNE_FAIL:-0}" == "1" ]] && exit 13
    echo "Applying Policy: keep 14 daily snapshots"
    echo "2 snapshots have been removed, keeping 3"
    exit 0
    ;;
  snapshots)
    cat <<'OUT'
ID        Time                 Host          Tags           Size
----------------------------------------------------------------
1111aaaa  2026-11-16 03:00:01  gongchae      gongchae-bell  10 MiB
2222bbbb  2026-11-17 03:00:02  gongchae      gongchae-bell  10 MiB
a1b2c3d4  2026-11-18 03:00:03  gongchae      gongchae-bell  11 MiB
----------------------------------------------------------------
Timestamps shown in UTC
3 snapshots
OUT
    exit 0
    ;;
esac
exit 0
EOF

  cat >"$case_dir/bin/curl" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
LOG_DIR="${MOCK_LOG_DIR:?}"
echo "curl $*" >>"$LOG_DIR/commands.log"
url="${@: -1}"
if [[ "${MOCK_HEARTBEAT_FAIL:-0}" == "1" && "$url" != *"/fail" ]]; then
  echo "curl: (7) Failed to connect to ${url} with token" >&2
  exit 7
fi
if [[ "${MOCK_FAIL_PING_ERROR:-0}" == "1" && "$url" == *"/fail" ]]; then
  echo "curl: (7) Failed to connect to ${url}" >&2
  exit 7
fi
printf '%s\n' "$url" >>"$LOG_DIR/heartbeat_urls.log"
exit 0
EOF

  chmod +x "$case_dir/bin/docker" "$case_dir/bin/restic" "$case_dir/bin/curl"

  export CASE_DIR="$case_dir"
  export MOCK_LOG_DIR="$case_dir/logs"
  export DOCKER_BIN="$case_dir/bin/docker"
  export RESTIC_BIN="$case_dir/bin/restic"
  export CURL_BIN="$case_dir/bin/curl"
  unset MOCK_PG_DOWN MOCK_PG_NOT_READY MOCK_PG_DUMP_FAIL MOCK_PG_DUMP_EMPTY \
        MOCK_PG_RESTORE_LIST_FAIL MOCK_PG_RESTORE_LIST_EMPTY \
        MOCK_RESTIC_UNINIT MOCK_RESTIC_INIT_FAIL MOCK_RESTIC_BACKUP_FAIL \
        MOCK_RESTIC_PRUNE_FAIL MOCK_RESTIC_LEAK_SECRET \
        MOCK_HEARTBEAT_FAIL MOCK_FAIL_PING_ERROR || true
}

echo "=== [1/11] bash -n syntax check ==="
bash -n "$BACKUP_SH"

echo "=== [2/11] env file permission, ownership, and required key checks ==="
setup_env env_checks
chmod 0644 "$CASE_DIR/backup.env"
if "$BACKUP_SH" run --env-file "$CASE_DIR/backup.env" 2>"$CASE_DIR/err.log"; then
  echo "Expected 0644 backup.env to be rejected" >&2; exit 1
fi
assert_file_contains "$CASE_DIR/err.log" "ENV_PERM_FAIL" "0644 rejected"
assert_file_not_contains "$CASE_DIR/err.log" "p@ss/w&rd" "password not leaked on perm error"

chmod 0640 "$CASE_DIR/backup.env"
if "$BACKUP_SH" run --env-file "$CASE_DIR/backup.env" 2>/dev/null; then
  echo "Expected 0640 backup.env to be rejected" >&2; exit 1
fi

ln -s "$CASE_DIR/backup.env" "$CASE_DIR/backup.env.link"
if "$BACKUP_SH" run --env-file "$CASE_DIR/backup.env.link" 2>/dev/null; then
  echo "Expected symlink backup.env to be rejected" >&2; exit 1
fi

chmod 0600 "$CASE_DIR/backup.env"
grep -v '^AWS_SECRET_ACCESS_KEY=' "$CASE_DIR/backup.env" >"$CASE_DIR/backup_no_aws.env"
chmod 0600 "$CASE_DIR/backup_no_aws.env"
if "$BACKUP_SH" run --env-file "$CASE_DIR/backup_no_aws.env" 2>/dev/null; then
  echo "Expected missing AWS_SECRET_ACCESS_KEY for S3 repo to fail" >&2; exit 1
fi

echo "=== [3/11] happy path, secret separation, retention summary, and success heartbeat ==="
setup_env happy
if ! "$BACKUP_SH" run --env-file "$CASE_DIR/backup.env" >"$CASE_DIR/out.log" 2>&1; then
  cat "$CASE_DIR/out.log" >&2 || true
  exit 1
fi
assert_file_contains "$CASE_DIR/out.log" "BACKUP_OK" "backup ok"
assert_file_contains "$CASE_DIR/out.log" "SNAPSHOT_RETENTION_SUMMARY count=3 oldest_utc=2026-11-16T03:00:01Z latest_id=a1b2c3d4 latest_utc=2026-11-18T03:00:03Z keep_daily=14" "retention summary logged"
assert_file_contains "$MOCK_LOG_DIR/staged_files.txt" "gongchae-bell.dump" "dump staged"
assert_file_contains "$MOCK_LOG_DIR/staged_files.txt" "erasure.log" "erasure.log staged"
assert_file_contains "$MOCK_LOG_DIR/staged_files.txt" "backup-meta.txt" "meta staged"
assert_file_contains "$MOCK_LOG_DIR/commands.log" "forget --tag gongchae-bell --group-by host,tags --keep-daily 14 --prune" "keep-daily 14 prune ran"
assert_file_contains "$MOCK_LOG_DIR/heartbeat_urls.log" "https://hc-ping.com/deadbeef-1111-2222-3333-444455556666" "success ping sent"
assert_file_not_contains "$MOCK_LOG_DIR/heartbeat_urls.log" "/fail" "no /fail ping on success"
assert_file_not_contains "$CASE_DIR/out.log" "COMPOSE_DB_SECRET_DO_NOT_LEAK" "compose DB secret absent"
assert_file_not_contains "$CASE_DIR/out.log" "deadbeef-1111-2222-3333-444455556666" "healthchecks secret URL absent from logs"

echo "=== [4/11] pg_dump failure & corrupt dump aborts before restic and sends /fail ==="
setup_env dump_fail
export MOCK_PG_DUMP_FAIL=1
if "$BACKUP_SH" run --env-file "$CASE_DIR/backup.env" 2>/dev/null; then
  echo "Expected pg_dump failure" >&2; exit 1
fi
assert_file_not_contains "$MOCK_LOG_DIR/commands.log" "restic backup" "restic not called on dump fail"
assert_file_contains "$MOCK_LOG_DIR/heartbeat_urls.log" "/fail" "/fail ping sent on dump fail"
grep -qv '/fail' "$MOCK_LOG_DIR/heartbeat_urls.log" && { echo "unexpected success ping on dump fail" >&2; exit 1; } || true

setup_env toc_fail
export MOCK_PG_RESTORE_LIST_EMPTY=1
if "$BACKUP_SH" run --env-file "$CASE_DIR/backup.env" 2>/dev/null; then
  echo "Expected empty TOC to fail" >&2; exit 1
fi
assert_file_not_contains "$MOCK_LOG_DIR/commands.log" "restic backup" "restic not called on empty TOC"
assert_file_contains "$MOCK_LOG_DIR/heartbeat_urls.log" "/fail" "/fail ping sent on empty TOC"

echo "=== [5/11] erasure.log contract (ALERTS_ENABLED=true required, directory rejected, M1 absent ok, empty file included) ==="
setup_env erasure_contract
rm -f "$CASE_DIR/erasure/erasure.log"
# M1 (ALERTS_ENABLED=false): succeeds without erasure.log
"$BACKUP_SH" run --env-file "$CASE_DIR/backup.env" >"$CASE_DIR/m1.log" 2>&1
assert_file_contains "$CASE_DIR/m1.log" "ERASURE_LOG_ABSENT_M1_ONLY" "M1 allows absent erasure.log"

# M2 (ALERTS_ENABLED=true, quoted, or uppercase): missing erasure.log fails closed
for alerts_line in \
  'ALERTS_ENABLED=true' \
  'ALERTS_ENABLED="true"' \
  "ALERTS_ENABLED='true'" \
  'ALERTS_ENABLED=TRUE' \
  'ALERTS_ENABLED="True" # inline comment'; do
  setup_env erasure_contract_m2
  rm -f "$CASE_DIR/erasure/erasure.log"
  printf 'COMPOSE_PROJECT_NAME=gongchae\n%s\n' "$alerts_line" >"$CASE_DIR/compose.env"
  : >"$MOCK_LOG_DIR/heartbeat_urls.log"
  if "$BACKUP_SH" run --env-file "$CASE_DIR/backup.env" 2>/dev/null; then
    echo "Expected missing erasure.log with [$alerts_line] to fail" >&2; exit 1
  fi
  assert_file_contains "$MOCK_LOG_DIR/heartbeat_urls.log" "/fail" "/fail sent when required erasure.log missing for [$alerts_line]"
done

# Empty prepared file (0 bytes) is valid and included
: >"$CASE_DIR/erasure/erasure.log"
chmod 0600 "$CASE_DIR/erasure/erasure.log"
"$BACKUP_SH" run --env-file "$CASE_DIR/backup.env" >/dev/null
assert_file_contains "$MOCK_LOG_DIR/staged_files.txt" "erasure.log" "empty prepared erasure.log included"

# Directory at ERASURE_LOG_PATH is rejected
rm -f "$CASE_DIR/erasure/erasure.log"
mkdir -p "$CASE_DIR/erasure/erasure.log"
if "$BACKUP_SH" run --env-file "$CASE_DIR/backup.env" 2>/dev/null; then
  echo "Expected directory at ERASURE_LOG_PATH to fail" >&2; exit 1
fi

echo "=== [6/11] restic backup failure aborts before prune & sends /fail ==="
setup_env restic_fail
export MOCK_RESTIC_BACKUP_FAIL=1
if "$BACKUP_SH" run --env-file "$CASE_DIR/backup.env" 2>/dev/null; then
  echo "Expected restic backup failure" >&2; exit 1
fi
assert_file_not_contains "$MOCK_LOG_DIR/commands.log" "restic forget" "forget not called on backup fail"
assert_file_contains "$MOCK_LOG_DIR/heartbeat_urls.log" "/fail" "/fail sent on backup fail"
grep -qv '/fail' "$MOCK_LOG_DIR/heartbeat_urls.log" && { echo "unexpected success ping on backup fail" >&2; exit 1; } || true

echo "=== [7/11] restic forget/prune failure sends /fail and never sends success ping ==="
setup_env prune_fail
export MOCK_RESTIC_PRUNE_FAIL=1
if "$BACKUP_SH" run --env-file "$CASE_DIR/backup.env" 2>/dev/null; then
  echo "Expected prune failure" >&2; exit 1
fi
assert_file_contains "$MOCK_LOG_DIR/heartbeat_urls.log" "/fail" "/fail sent on prune fail"
grep -qv '/fail' "$MOCK_LOG_DIR/heartbeat_urls.log" && { echo "unexpected success ping on prune fail" >&2; exit 1; } || true

echo "=== [8/11] heartbeat success ping failure exits non-zero & redacts URL ==="
setup_env hb_fail
export MOCK_HEARTBEAT_FAIL=1
if "$BACKUP_SH" run --env-file "$CASE_DIR/backup.env" >"$CASE_DIR/hb.log" 2>&1; then
  echo "Expected heartbeat failure to fail backup run" >&2; exit 1
fi
assert_file_contains "$CASE_DIR/hb.log" "HEARTBEAT_PING_FAIL" "heartbeat failure logged"
assert_file_not_contains "$CASE_DIR/hb.log" "deadbeef-1111-2222-3333-444455556666" "heartbeat token redacted on curl error"

echo "=== [9/11] secret redaction in stdout/stderr even when restic/curl print secrets ==="
setup_env secret_redaction
export MOCK_RESTIC_LEAK_SECRET=1
export MOCK_RESTIC_BACKUP_FAIL=1
export MOCK_FAIL_PING_ERROR=1
if command -v timeout >/dev/null 2>&1; then
  timeout 5 "$BACKUP_SH" run --env-file "$CASE_DIR/backup.env" >"$CASE_DIR/redacted.log" 2>&1 || rc=$?
  if [[ "${rc:-0}" -eq 124 ]]; then
    echo "ERROR: backup.sh timed out during secret redaction" >&2; exit 1
  fi
else
  "$BACKUP_SH" run --env-file "$CASE_DIR/backup.env" >"$CASE_DIR/redacted.log" 2>&1 || true
fi
for secret in 'p@ss/w&rd$1!' 'mock_access_key_placeholder' 'mock_secret_key_placeholder' 'deadbeef-1111-2222-3333-444455556666' 'COMPOSE_DB_SECRET_DO_NOT_LEAK'; do
  assert_file_not_contains "$CASE_DIR/redacted.log" "$secret" "secret [$secret] must be redacted"
done
assert_file_contains "$CASE_DIR/redacted.log" "[REDACTED]" "redaction marker present"

# Run edge-case checks directly against redact_stream in backup.sh (extracted safely into subshell, bounded by timeout 5s)
run_redact_probe() {
  local label="$1"
  local restic_pw="$2"
  local aws_key="$3"
  local aws_secret="$4"
  local hc_url="$5"
  local input_line="$6"
  local expected_contains="$7"
  local fn_file="$TMP_ROOT/redact_probe_${label}.sh"
  sed -n '/^redact_stream()/,/^}/p' "$BACKUP_SH" >"$fn_file"
  local out
  local status=0
  if command -v timeout >/dev/null 2>&1; then
    out="$(printf '%s\n' "$input_line" | \
      RESTIC_PASSWORD="$restic_pw" \
      AWS_ACCESS_KEY_ID="$aws_key" \
      AWS_SECRET_ACCESS_KEY="$aws_secret" \
      HEALTHCHECKS_BACKUP_URL="$hc_url" \
      timeout 5 bash -c 'source "$1"; redact_stream' _ "$fn_file")" || status=$?
  else
    out="$(printf '%s\n' "$input_line" | \
      RESTIC_PASSWORD="$restic_pw" \
      AWS_ACCESS_KEY_ID="$aws_key" \
      AWS_SECRET_ACCESS_KEY="$aws_secret" \
      HEALTHCHECKS_BACKUP_URL="$hc_url" \
      bash -c 'source "$1"; redact_stream' _ "$fn_file")" || status=$?
  fi
  if [[ "$status" -ne 0 ]]; then
    echo "ERROR: redact_stream probe [$label] failed or timed out (status=$status)" >&2; exit 1
  fi
  if [[ "$out" != "$expected_contains" ]]; then
    echo "ERROR: redact_stream probe [$label] mismatch: got [$out], expected [$expected_contains]" >&2; exit 1
  fi
}

run_redact_probe "redacted_char_R" "R" "E" "D" "https://hc-ping.com/token-abc" \
  "R and E and D and https://hc-ping.com/token-abc and REST" \
  "[REDACTED] and [REDACTED] and [REDACTED] and [REDACTED] and [REDACTED][REDACTED]ST"
run_redact_probe "embedded_marker" 'pre[REDACTED]post' 'ak1' 'sk1' 'https://hc-ping.com/tok1' \
  'leak pre[REDACTED]post ok' \
  'leak [REDACTED] ok'
run_redact_probe "backslash_and_regex" 'p@ss\nw&rd$1/\[x]*+' 'key\\slash' 'amp&1\0' 'https://hc-ping.com/u+v' \
  'a p@ss\nw&rd$1/\[x]*+ b key\\slash c amp&1\0 d' \
  'a [REDACTED] b [REDACTED] c [REDACTED] d'
run_redact_probe "longest_match_prefix" 'KEY1' 'KEY1_LONGER' 'OTHER' 'https://hc-ping.com/1111' \
  'vals KEY1_LONGER and KEY1 end' \
  'vals [REDACTED] and [REDACTED] end'

echo "=== [10/11] flock concurrency exclusion (exit 20) ==="
setup_env flock_lock
(
  exec 9>"$CASE_DIR/backup.lock"
  flock -x 9
  sleep 2
) &
holder_pid=$!
sleep 0.3
set +e
"$BACKUP_SH" run --env-file "$CASE_DIR/backup.env" 2>/dev/null
lock_rc=$?
set -e
wait "$holder_pid"
assert_eq "20" "$lock_rc" "concurrent backup rejected with exit 20"

echo "=== [11/11] backup.sh init subcommand initializes uninitialized repo ==="
setup_env init_cmd
export MOCK_RESTIC_UNINIT=1
"$BACKUP_SH" init --env-file "$CASE_DIR/backup.env" >"$CASE_DIR/init.log" 2>&1
assert_file_contains "$CASE_DIR/init.log" "RESTIC_REPO_INITIALIZED" "init subcommand succeeded"
assert_file_contains "$MOCK_LOG_DIR/commands.log" "restic init" "restic init executed"

echo "ALL_BACKUP_UNIT_TESTS_PASSED"
