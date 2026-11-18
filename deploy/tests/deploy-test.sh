#!/usr/bin/env bash
set -euo pipefail

# Isolated unit & double test suite for deploy/deploy.sh and deploy/g4-probe.sh.
# Verifies:
# - bash syntax
# - bootstrap vs blue/green separation
# - ADR-0005 two-phase switch -> external G4 -> finalize (old color kept alive until G4 passes)
# - external G4 failure -> abort --deploy-id (instant Caddy revert to running old color without cold start)
# - deploy_id correlation & mismatch rejection (finalize/abort/if-pending)
# - interrupted pending deploy fail-closed & manual rollback rescue
# - pre-switch, Caddy validate, Caddy reload, and post-switch local failures
# - state mismatch without pending deploy (fail-closed)
# - post-commit two-phase rollback (switch -> G4 -> finalize) & full rollback
# - real flock concurrency exclusion (exit 20)
# - G4 probe pass/fail and JSON report deploy_id correlation

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
DEPLOY_SH="$REPO_ROOT/deploy/deploy.sh"
G4_SH="$REPO_ROOT/deploy/g4-probe.sh"

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
  mkdir -p "$case_dir/bin" "$case_dir/state/caddy" "$case_dir/erasure" "$case_dir/logs"

  cat >"$case_dir/compose.prod.yml" <<'EOF'
# minimal dummy compose file path for mock docker
EOF
  # Include blank optional keys matching deploy/.env.prod.example key-only convention.
  cat >"$case_dir/.env" <<'EOF'
DB_USER=test
DB_PASSWORD=test
SITE_DOMAIN=
CADDY_RUNTIME_DIR=
APP_IMAGE=ghcr.io/lcjam/gongchae-bell
APP_BLUE_TAG=
APP_GREEN_TAG=
EOF

  cat >"$case_dir/bin/docker" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
LOG_DIR="${MOCK_LOG_DIR:?}"
echo "docker $*" >>"$LOG_DIR/commands.log"
if [[ "$*" == *compose.monitoring.yml* ]]; then
  echo "monitoring-tags blue=${APP_BLUE_TAG:-} green=${APP_GREEN_TAG:-}" >>"$LOG_DIR/commands.log"
fi

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
    up)
      if [[ "$*" == *"caddy"* && "${MOCK_FAIL_CADDY_UP:-0}" == "1" ]]; then
        exit 1
      fi
      if [[ "$*" == *"app-blue"* ]]; then
        [[ "${MOCK_FAIL_START_BLUE:-0}" == "1" ]] && exit 1
        touch "$LOG_DIR/container_app-blue" "$LOG_DIR/running_app-blue"
      fi
      if [[ "$*" == *"app-green"* ]]; then
        [[ "${MOCK_FAIL_START_GREEN:-0}" == "1" ]] && exit 1
        touch "$LOG_DIR/container_app-green" "$LOG_DIR/running_app-green"
      fi
      exit 0
      ;;
    pull)
      exit 0
      ;;
    ps)
      running_only=0
      target_svc=""
      while [[ $# -gt 0 ]]; do
        case "$1" in
          --status)
            [[ "${2:-}" == "running" ]] && running_only=1
            shift 2
            ;;
          -a|-q)
            shift
            ;;
          app-blue|app-green)
            target_svc="$1"
            shift
            ;;
          *)
            shift
            ;;
        esac
      done
      [[ "${MOCK_FAIL_PS:-0}" == "1" ]] && exit 44
      for svc in app-blue app-green; do
        if [[ -z "$target_svc" || "$target_svc" == "$svc" ]]; then
          if [[ "$running_only" -eq 1 ]]; then
            [[ -f "$LOG_DIR/running_$svc" ]] && echo "mock-$svc"
          else
            [[ -f "$LOG_DIR/container_$svc" ]] && echo "mock-$svc"
          fi
        fi
      done
      exit 0
      ;;
    stop)
      [[ "${MOCK_FAIL_STOP:-0}" == "1" ]] && exit 42
      if [[ "${MOCK_FAIL_STOP_SILENT:-0}" == "1" ]]; then
        exit 0
      fi
      for svc in app-blue app-green; do
        if [[ "$*" == *"$svc"* ]]; then
          rm -f "$LOG_DIR/running_$svc"
        fi
      done
      exit 0
      ;;
    rm)
      [[ "${MOCK_FAIL_RM:-0}" == "1" ]] && exit 43
      for svc in app-blue app-green; do
        if [[ "$*" == *"$svc"* ]]; then
          rm -f "$LOG_DIR/container_$svc" "$LOG_DIR/running_$svc"
        fi
      done
      exit 0
      ;;
    exec)
      shift # -T
      svc="$1"
      shift
      if [[ "$svc" == "caddy" ]]; then
        if [[ "$*" == *"caddy validate"* && "${MOCK_FAIL_CADDY_VALIDATE:-0}" == "1" ]]; then
          exit 1
        fi
        if [[ "$*" == *"caddy reload"* ]]; then
          if [[ "${MOCK_FAIL_ROLLBACK_RELOAD:-0}" == "1" ]] && grep -q 'app-blue:8080' "${CADDY_RUNTIME_DIR}/upstream.caddy"; then
            exit 1
          fi
          if [[ "${MOCK_FAIL_CADDY_RELOAD:-0}" == "1" ]]; then
            exit 1
          fi
        fi
        exit 0
      fi
      # exec inside app-blue / app-green fails if container is not running
      if [[ ! -f "$LOG_DIR/running_$svc" ]]; then
        exit 1
      fi
      if [[ "$svc" == "app-blue" && "${MOCK_FAIL_READY_BLUE:-0}" == "1" ]]; then
        exit 1
      fi
      if [[ "$svc" == "app-green" && "${MOCK_FAIL_READY_GREEN:-0}" == "1" ]]; then
        if [[ "$*" == *"/actuator/health/readiness"* ]]; then
          exit 1
        fi
      fi
      if [[ "$svc" == "app-green" && "${MOCK_FAIL_ROUTE_GREEN:-0}" == "1" ]]; then
        if [[ "$*" == *"/jobs"* ]]; then
          printf '500'
          exit 0
        fi
        printf '200'
        exit 0
      fi
      if [[ "$*" == *"/actuator/health/readiness"* ]]; then
        printf '{"status":"UP","groups":["readiness"]}'
        exit 0
      fi
      printf '200'
      exit 0
      ;;
  esac
fi
exit 0
EOF
  chmod +x "$case_dir/bin/docker"

  cat >"$case_dir/bin/curl" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
echo "curl $*" >>"${MOCK_LOG_DIR:?}/commands.log"
if [[ "${MOCK_POST_SWITCH_FAIL:-0}" == "1" && "$*" == *"--resolve"* ]]; then
  printf '502'
  exit 0
fi
if [[ -n "${MOCK_G4_STATUS_SEQUENCE:-}" ]]; then
  idx_file="${MOCK_LOG_DIR}/g4_idx"
  idx=0
  [[ -f "$idx_file" ]] && idx="$(cat "$idx_file")"
  IFS=',' read -r -a seq <<<"$MOCK_G4_STATUS_SEQUENCE"
  val="${seq[$((idx % ${#seq[@]}))]}"
  echo $((idx + 1)) >"$idx_file"
  if [[ "$val" == "TIMEOUT" ]]; then
    exit 28
  elif [[ "$val" == "TLS_ERR" ]]; then
    exit 35
  fi
  printf '%s 0.012' "$val"
  exit 0
fi
printf '200'
EOF
  chmod +x "$case_dir/bin/curl"

  export CASE_DIR="$case_dir"
  export MOCK_LOG_DIR="$case_dir/logs"
  export DEPLOY_ROOT_DIR="$REPO_ROOT"
  export COMPOSE_FILE="$case_dir/compose.prod.yml"
  export ENV_FILE="$case_dir/.env"
  export DEPLOY_STATE_DIR="$case_dir/state"
  export CADDY_RUNTIME_DIR="$case_dir/state/caddy"
  export ERASURE_HOST_DIR="$case_dir/erasure"
  export DEPLOY_LOCK_FILE="$case_dir/state/deploy.lock"
  export DOCKER_BIN="$case_dir/bin/docker"
  export CURL_BIN="$case_dir/bin/curl"
  export READINESS_TIMEOUT=1
  export READINESS_INTERVAL=0
  export STOP_GRACE_SECONDS=40
  export PULL_POLICY=never
  unset MOCK_FAIL_CADDY_UP MOCK_FAIL_START_BLUE MOCK_FAIL_START_GREEN \
        MOCK_FAIL_CADDY_VALIDATE MOCK_FAIL_CADDY_RELOAD MOCK_FAIL_ROLLBACK_RELOAD \
        MOCK_FAIL_READY_BLUE MOCK_FAIL_READY_GREEN MOCK_FAIL_ROUTE_GREEN \
        MOCK_FAIL_STOP MOCK_FAIL_STOP_SILENT MOCK_FAIL_RM MOCK_FAIL_PS \
        MOCK_POST_SWITCH_FAIL MOCK_G4_STATUS_SEQUENCE POST_SWITCH_HOOK G4_HOOK MONITORING_COMPOSE_FILE || true
}

echo "=== [1/16] bash -n syntax check ==="
bash -n "$DEPLOY_SH"
bash -n "$G4_SH"

echo "=== [2/16] bootstrap vs bluegreen separation & two-phase ADR-0005 happy path ==="
setup_env happy_twophase
if "$DEPLOY_SH" deploy sha-111 --mode bluegreen --deploy-id dep-bad 2>/dev/null; then
  echo "Expected bluegreen before bootstrap to fail" >&2
  exit 1
fi

"$DEPLOY_SH" deploy sha-111 --mode bootstrap --deploy-id boot-1
assert_eq "blue" "$(cat "$DEPLOY_STATE_DIR/active_color")" "bootstrap active_color"
assert_eq "sha-111" "$(cat "$DEPLOY_STATE_DIR/active_sha")" "bootstrap active_sha"
assert_eq "boot-1" "$(cat "$DEPLOY_STATE_DIR/active_deploy_id")" "bootstrap deploy_id"
assert_file_contains "$CADDY_RUNTIME_DIR/upstream.caddy" "app-blue:8080" "bootstrap upstream"

if "$DEPLOY_SH" bootstrap sha-222 2>/dev/null; then
  echo "Expected second bootstrap to fail" >&2
  exit 1
fi

# Phase 1: switch (must keep app-blue running and leave active_color=blue until finalize)
: >"$MOCK_LOG_DIR/commands.log"
"$DEPLOY_SH" deploy sha-222 --mode bluegreen --phase switch --deploy-id dep-2
assert_file_contains "$CADDY_RUNTIME_DIR/upstream.caddy" "app-green:8080" "green serving after switch"
assert_file_not_contains "$MOCK_LOG_DIR/commands.log" "stop -t 40 app-blue" "old blue NOT stopped before external G4"
[[ -f "$MOCK_LOG_DIR/running_app-blue" ]] || { echo "app-blue must remain running during G4" >&2; exit 1; }
[[ -f "$MOCK_LOG_DIR/running_app-green" ]] || { echo "app-green must be running during G4" >&2; exit 1; }
assert_eq "blue" "$(cat "$DEPLOY_STATE_DIR/active_color")" "active_color still blue during G4"
assert_eq "dep-2" "$(cat "$DEPLOY_STATE_DIR/pending_deploy_id")" "pending_deploy_id recorded"

# Mismatched deploy_id finalize must be rejected
if "$DEPLOY_SH" finalize --deploy-id wrong-id 2>/dev/null; then
  echo "Expected finalize with mismatched deploy_id to fail" >&2
  exit 1
fi
[[ -f "$MOCK_LOG_DIR/running_app-blue" ]] || { echo "mismatched finalize must not stop app-blue" >&2; exit 1; }

# Phase 2: finalize after external G4 passes (now stops app-blue and commits green)
"$DEPLOY_SH" finalize --deploy-id dep-2
assert_eq "green" "$(cat "$DEPLOY_STATE_DIR/active_color")" "commit active_color=green"
assert_eq "sha-222" "$(cat "$DEPLOY_STATE_DIR/active_sha")" "commit active_sha=sha-222"
assert_eq "dep-2" "$(cat "$DEPLOY_STATE_DIR/active_deploy_id")" "commit active_deploy_id=dep-2"
assert_eq "blue" "$(cat "$DEPLOY_STATE_DIR/prev_color")" "prev_color blue"
assert_eq "sha-111" "$(cat "$DEPLOY_STATE_DIR/prev_sha")" "prev_sha sha-111"
assert_eq "boot-1" "$(cat "$DEPLOY_STATE_DIR/prev_deploy_id")" "prev_deploy_id boot-1"
assert_file_contains "$MOCK_LOG_DIR/commands.log" "stop -t 40 app-blue" "old blue stopped after finalize"
[[ ! -f "$MOCK_LOG_DIR/running_app-blue" ]] || { echo "app-blue should be stopped after finalize" >&2; exit 1; }
[[ ! -f "$DEPLOY_STATE_DIR/pending_deploy_id" ]] || { echo "pending_deploy_id should be cleared after finalize" >&2; exit 1; }

echo "=== [3/16] external G4 failure triggers instant abort to still-running old color ==="
setup_env g4_fail_abort
"$DEPLOY_SH" bootstrap sha-111 --deploy-id boot-1 >/dev/null
"$DEPLOY_SH" deploy sha-222 --mode bluegreen --phase switch --deploy-id dep-fail >/dev/null
: >"$MOCK_LOG_DIR/commands.log"

# Wrong deploy_id abort must fail
if "$DEPLOY_SH" abort --deploy-id wrong-id 2>/dev/null; then
  echo "Expected abort with wrong deploy_id to fail" >&2
  exit 1
fi

# Correct deploy_id abort switches Caddy back immediately WITHOUT restarting app-blue
"$DEPLOY_SH" abort --deploy-id dep-fail
assert_eq "blue" "$(cat "$DEPLOY_STATE_DIR/active_color")" "active remains blue after abort"
assert_eq "sha-111" "$(cat "$DEPLOY_STATE_DIR/active_sha")" "sha remains sha-111 after abort"
assert_file_contains "$CADDY_RUNTIME_DIR/upstream.caddy" "app-blue:8080" "upstream reverted to blue"
assert_file_not_contains "$MOCK_LOG_DIR/commands.log" "up -d --no-deps app-blue" "running old blue was NOT cold-restarted"
assert_file_contains "$MOCK_LOG_DIR/commands.log" "stop -t 40 app-green" "failed green stopped"
[[ ! -f "$DEPLOY_STATE_DIR/pending_deploy_id" ]] || { echo "pending_deploy_id should be cleared after abort" >&2; exit 1; }

# abort --if-pending with no pending deploy is a safe no-op
: >"$MOCK_LOG_DIR/commands.log"
"$DEPLOY_SH" abort --deploy-id dep-fail --if-pending
assert_file_not_contains "$MOCK_LOG_DIR/commands.log" "caddy reload" "abort --if-pending no-op when clean"

echo "=== [4/16] interrupted pending deploy blocks new deploys & allows manual rollback rescue ==="
setup_env interrupted_pending
"$DEPLOY_SH" bootstrap sha-111 --deploy-id boot-1 >/dev/null
"$DEPLOY_SH" deploy sha-222 --mode bluegreen --phase switch --deploy-id dep-int >/dev/null
: >"$MOCK_LOG_DIR/commands.log"

# New deploy with a different ID must fail closed without touching containers
if "$DEPLOY_SH" deploy sha-333 --mode bluegreen --phase switch --deploy-id dep-next 2>/dev/null; then
  echo "Expected new deploy to fail closed while pending_deploy_id exists" >&2
  exit 1
fi
assert_file_not_contains "$MOCK_LOG_DIR/commands.log" "up -d" "new deploy blocked before touching containers"
assert_file_not_contains "$MOCK_LOG_DIR/commands.log" "caddy reload" "new deploy blocked before touching Caddy"

# abort --if-pending with a DIFFERENT ID must also reject
if "$DEPLOY_SH" abort --deploy-id dep-next --if-pending 2>/dev/null; then
  echo "Expected abort --if-pending with different ID to reject" >&2
  exit 1
fi

# status exposes pending_deploy_id
"$DEPLOY_SH" status >"$CASE_DIR/status.txt"
assert_file_contains "$CASE_DIR/status.txt" "pending_deploy_id=dep-int" "status reports pending_deploy_id"
assert_file_contains "$CASE_DIR/status.txt" "upstream_color=green" "status reports upstream_color=green"

# Manual `deploy.sh rollback` rescues the interrupted pending deploy cleanly
"$DEPLOY_SH" rollback
assert_eq "blue" "$(cat "$DEPLOY_STATE_DIR/active_color")" "manual rollback restores active blue"
assert_file_contains "$CADDY_RUNTIME_DIR/upstream.caddy" "app-blue:8080" "manual rollback restores blue upstream"
[[ ! -f "$DEPLOY_STATE_DIR/pending_deploy_id" ]] || { echo "pending cleared after manual rollback" >&2; exit 1; }

echo "=== [5/16] pre-switch failure preserves active color & never reloads Caddy ==="
setup_env pre_fail
"$DEPLOY_SH" bootstrap sha-111 >/dev/null
: >"$MOCK_LOG_DIR/commands.log"
export MOCK_FAIL_ROUTE_GREEN=1
if "$DEPLOY_SH" deploy sha-bad --mode bluegreen --phase switch --deploy-id dep-pre 2>/dev/null; then
  echo "Expected route failure to fail deploy" >&2
  exit 1
fi
assert_eq "blue" "$(cat "$DEPLOY_STATE_DIR/active_color")" "active remains blue"
assert_eq "sha-111" "$(cat "$DEPLOY_STATE_DIR/active_sha")" "sha remains sha-111"
assert_file_contains "$CADDY_RUNTIME_DIR/upstream.caddy" "app-blue:8080" "upstream remains blue"
assert_file_contains "$MOCK_LOG_DIR/commands.log" "stop -t 40 app-green" "failed green stopped"
assert_file_not_contains "$MOCK_LOG_DIR/commands.log" "caddy reload" "Caddy reload must NOT run on pre-switch failure"

echo "=== [6/16] Caddy validate failure reverts staged file & preserves active ==="
setup_env validate_fail
"$DEPLOY_SH" bootstrap sha-111 >/dev/null
export MOCK_FAIL_CADDY_VALIDATE=1
if "$DEPLOY_SH" deploy sha-222 --mode bluegreen --phase switch --deploy-id dep-val 2>/dev/null; then
  echo "Expected validate failure" >&2
  exit 1
fi
assert_eq "blue" "$(cat "$DEPLOY_STATE_DIR/active_color")" "active remains blue"
assert_file_contains "$CADDY_RUNTIME_DIR/upstream.caddy" "app-blue:8080" "upstream remains blue"
[[ ! -f "$CADDY_RUNTIME_DIR/upstream.caddy.next" ]] || { echo "upstream.caddy.next leaked" >&2; exit 1; }
[[ ! -f "$DEPLOY_STATE_DIR/pending_deploy_id" ]] || { echo "pending_deploy_id leaked on validate fail" >&2; exit 1; }

echo "=== [7/16] Caddy reload failure restores upstream.caddy & clears pending ==="
setup_env reload_fail
"$DEPLOY_SH" bootstrap sha-111 >/dev/null
export MOCK_FAIL_CADDY_RELOAD=1
if "$DEPLOY_SH" deploy sha-222 --mode bluegreen --phase switch --deploy-id dep-rel 2>/dev/null; then
  echo "Expected reload failure" >&2
  exit 1
fi
assert_eq "blue" "$(cat "$DEPLOY_STATE_DIR/active_color")" "active remains blue"
assert_file_contains "$CADDY_RUNTIME_DIR/upstream.caddy" "app-blue:8080" "upstream restored to blue"
[[ ! -f "$DEPLOY_STATE_DIR/pending_deploy_id" ]] || { echo "pending_deploy_id leaked on reload fail" >&2; exit 1; }

echo "=== [8/16] post-switch local failure rolls back Caddy immediately & keeps active ==="
setup_env post_fail
"$DEPLOY_SH" bootstrap sha-111 >/dev/null
export MOCK_POST_SWITCH_FAIL=1
if "$DEPLOY_SH" deploy sha-222 --mode bluegreen --phase switch --deploy-id dep-post 2>/dev/null; then
  echo "Expected post-switch failure" >&2
  exit 1
fi
assert_eq "blue" "$(cat "$DEPLOY_STATE_DIR/active_color")" "active remains blue"
assert_eq "sha-111" "$(cat "$DEPLOY_STATE_DIR/active_sha")" "sha remains sha-111"
assert_file_contains "$CADDY_RUNTIME_DIR/upstream.caddy" "app-blue:8080" "upstream rolled back to blue"
assert_file_contains "$MOCK_LOG_DIR/commands.log" "stop -t 40 app-green" "failed green cleaned up"
[[ ! -f "$DEPLOY_STATE_DIR/pending_deploy_id" ]] || { echo "pending_deploy_id leaked on post-switch fail" >&2; exit 1; }

echo "=== [9/16] state mismatch without pending_deploy_id fails closed ==="
setup_env mismatch
"$DEPLOY_SH" bootstrap sha-111 >/dev/null
cp -f "$REPO_ROOT/deploy/caddy/upstream.green.caddy" "$CADDY_RUNTIME_DIR/upstream.caddy"
: >"$MOCK_LOG_DIR/commands.log"
if "$DEPLOY_SH" deploy sha-222 --mode bluegreen 2>/dev/null; then
  echo "Expected state mismatch to fail closed" >&2
  exit 1
fi
if grep -qE "up -d|stop|caddy reload" "$MOCK_LOG_DIR/commands.log"; then
  echo "State mismatch must not touch running containers or Caddy" >&2
  exit 1
fi

echo "=== [10/16] post-commit two-phase rollback restores prev_color after G4 finalize ==="
setup_env post_rollback
"$DEPLOY_SH" bootstrap sha-111 --deploy-id boot-1 >/dev/null
"$DEPLOY_SH" deploy sha-222 --mode bluegreen --phase switch --deploy-id dep-2 >/dev/null
"$DEPLOY_SH" finalize --deploy-id dep-2 >/dev/null
: >"$MOCK_LOG_DIR/commands.log"
"$DEPLOY_SH" rollback --phase switch --deploy-id rb-3
assert_file_contains "$CADDY_RUNTIME_DIR/upstream.caddy" "app-blue:8080" "rollback switch serves blue"
assert_file_not_contains "$MOCK_LOG_DIR/commands.log" "stop -t 40 app-green" "green kept running during rollback G4"
"$DEPLOY_SH" finalize --deploy-id rb-3
assert_eq "blue" "$(cat "$DEPLOY_STATE_DIR/active_color")" "rolled back to blue"
assert_eq "sha-111" "$(cat "$DEPLOY_STATE_DIR/active_sha")" "rolled back to sha-111"
assert_eq "rb-3" "$(cat "$DEPLOY_STATE_DIR/active_deploy_id")" "rollback deploy_id recorded"
assert_file_contains "$MOCK_LOG_DIR/commands.log" "stop -t 40 app-green" "green stopped after rollback finalize"

echo "=== [11/16] standalone G4_HOOK failure aborts before stopping old color ==="
setup_env g4_hook_fail
"$DEPLOY_SH" bootstrap sha-111 --deploy-id boot-1 >/dev/null
cat >"$CASE_DIR/fail-g4.sh" <<'EOF'
#!/usr/bin/env bash
exit 1
EOF
chmod +x "$CASE_DIR/fail-g4.sh"
export G4_HOOK="$CASE_DIR/fail-g4.sh"
if "$DEPLOY_SH" deploy sha-222 --mode bluegreen --phase full --deploy-id dep-hook 2>/dev/null; then
  echo "Expected G4_HOOK failure to fail deploy" >&2
  exit 1
fi
assert_eq "blue" "$(cat "$DEPLOY_STATE_DIR/active_color")" "active remains blue after G4_HOOK failure"
assert_file_contains "$CADDY_RUNTIME_DIR/upstream.caddy" "app-blue:8080" "upstream reverted to blue"
assert_file_not_contains "$MOCK_LOG_DIR/commands.log" "stop -t 40 app-blue" "old blue never stopped"

echo "=== [12/16] flock concurrency exclusion & G4 external probe checks ==="
setup_env flock_and_g4
"$DEPLOY_SH" bootstrap sha-111 >/dev/null
export LOCK_WAIT_SECONDS=0
(
  exec 9>"$DEPLOY_LOCK_FILE"
  flock -x 9
  sleep 2
) &
holder_pid=$!
sleep 0.3
set +e
"$DEPLOY_SH" deploy sha-222 --mode bluegreen --phase switch --deploy-id dep-lock 2>/dev/null
lock_rc=$?
set -e
wait "$holder_pid"
assert_eq "20" "$lock_rc" "concurrent deploy rejected with exit 20 (LOCK_HELD)"

export MOCK_G4_STATUS_SEQUENCE="200,200,200"
"$G4_SH" --base-url "https://gongchae.enjoylcjworld.com" --duration 2 --interval 0 --min-requests 3 \
  --report "$CASE_DIR/g4-pass.json" --context "test-double" --deploy-id "dep-g4-pass" >/dev/null
assert_file_contains "$CASE_DIR/g4-pass.json" '"failures": 0' "g4 pass zero failures"
assert_file_contains "$CASE_DIR/g4-pass.json" '"deploy_id": "dep-g4-pass"' "g4 report includes deploy_id"

for bad_seq in "200,502,200" "200,TIMEOUT,200" "200,TLS_ERR,200" "200,404,200"; do
  rm -f "$MOCK_LOG_DIR/g4_idx"
  export MOCK_G4_STATUS_SEQUENCE="$bad_seq"
  if "$G4_SH" --base-url "https://gongchae.enjoylcjworld.com" --duration 2 --interval 0 --min-requests 3 \
      --report "$CASE_DIR/g4-fail.json" --context "test-double" --deploy-id "dep-g4-fail" >/dev/null 2>&1; then
    echo "Expected G4 probe to fail on sequence $bad_seq" >&2
    exit 1
  fi
done

echo "=== [13/16] interrupted finalize metadata recovery (abort, finalize retry, duplicate calls, next deploy) ==="
REAL_MV="$(command -v mv)"
REAL_RM="$(command -v rm)"

make_interrupt_bins() {
  mkdir -p "$CASE_DIR/failbin"
  cat >"$CASE_DIR/failbin/mv" <<EOF
#!/usr/bin/env bash
set -euo pipefail
"$REAL_MV" "\$@"
target="\${@: -1}"
if [[ -n "\${INJECT_MV_FAIL_ON:-}" && "\$target" == *"/\${INJECT_MV_FAIL_ON}" ]]; then
  exit 99
fi
EOF
  cat >"$CASE_DIR/failbin/rm" <<EOF
#!/usr/bin/env bash
set -euo pipefail
if [[ -n "\${INJECT_RM_FAIL_BEFORE:-}" && "\$*" == *"\${INJECT_RM_FAIL_BEFORE}"* ]]; then
  exit 98
fi
if [[ -n "\${INJECT_RM_FAIL_AFTER_ID:-}" && "\$*" == *"pending_color"* ]]; then
  exit 97
fi
"$REAL_RM" "\$@"
EOF
  chmod +x "$CASE_DIR/failbin/mv" "$CASE_DIR/failbin/rm"
}

# 13a: interrupt after active_color update, then abort -> must restore active_*, prev_*, upstream, old container, and allow next deploy
setup_env interrupt_abort_active
make_interrupt_bins
"$DEPLOY_SH" bootstrap sha-111 --deploy-id boot-1 >/dev/null
"$DEPLOY_SH" deploy sha-222 --mode bluegreen --phase switch --deploy-id dep-2 >/dev/null
set +e
PATH="$CASE_DIR/failbin:$PATH" INJECT_MV_FAIL_ON="active_color" "$DEPLOY_SH" finalize --deploy-id dep-2 2>/dev/null
rc=$?
set -e
assert_eq "99" "$rc" "finalize interrupted after active_color"
assert_eq "green" "$(cat "$DEPLOY_STATE_DIR/active_color")" "active_color temporarily green before abort"
assert_eq "dep-2" "$(cat "$DEPLOY_STATE_DIR/pending_deploy_id")" "pending_deploy_id preserved"

"$DEPLOY_SH" abort --deploy-id dep-2
assert_eq "blue" "$(cat "$DEPLOY_STATE_DIR/active_color")" "abort restores active_color=blue"
assert_eq "sha-111" "$(cat "$DEPLOY_STATE_DIR/active_sha")" "abort restores active_sha=sha-111"
assert_eq "boot-1" "$(cat "$DEPLOY_STATE_DIR/active_deploy_id")" "abort restores active_deploy_id=boot-1"
assert_eq "sha-111" "$(cat "$DEPLOY_STATE_DIR/blue_tag")" "abort restores blue_tag"
[[ ! -f "$DEPLOY_STATE_DIR/prev_color" ]] || { echo "prev_color should be absent after aborting first deploy" >&2; exit 1; }
[[ ! -f "$DEPLOY_STATE_DIR/green_tag" ]] || { echo "green_tag should be absent after aborting first deploy" >&2; exit 1; }
assert_file_contains "$CADDY_RUNTIME_DIR/upstream.caddy" "app-blue:8080" "abort restores upstream to blue"
[[ -f "$MOCK_LOG_DIR/running_app-blue" ]] || { echo "app-blue running after abort" >&2; exit 1; }
[[ ! -f "$MOCK_LOG_DIR/running_app-green" ]] || { echo "app-green stopped after abort" >&2; exit 1; }
[[ ! -f "$DEPLOY_STATE_DIR/pending_deploy_id" ]] || { echo "pending cleared after abort" >&2; exit 1; }
if "$DEPLOY_SH" abort --deploy-id dep-2 2>/dev/null; then
  echo "Expected duplicate abort to fail" >&2
  exit 1
fi
"$DEPLOY_SH" abort --deploy-id dep-2 --if-pending
"$DEPLOY_SH" deploy sha-333 --mode bluegreen --phase switch --deploy-id dep-3 >/dev/null
"$DEPLOY_SH" finalize --deploy-id dep-3 >/dev/null
assert_eq "green" "$(cat "$DEPLOY_STATE_DIR/active_color")" "next deploy after abort succeeds"

# 13b: interrupt after active_deploy_id overwrite on deploy #3, verify BOTH finalize retry and abort restore prev_* cleanly
setup_env interrupt_retry_and_abort_prev
make_interrupt_bins
"$DEPLOY_SH" bootstrap sha-111 --deploy-id boot-1 >/dev/null
"$DEPLOY_SH" deploy sha-222 --mode bluegreen --phase switch --deploy-id dep-2 >/dev/null
"$DEPLOY_SH" finalize --deploy-id dep-2 >/dev/null
"$DEPLOY_SH" deploy sha-333 --mode bluegreen --phase switch --deploy-id dep-3 >/dev/null

# Fail right after active_deploy_id is overwritten to dep-3, then retry finalize
set +e
PATH="$CASE_DIR/failbin:$PATH" INJECT_MV_FAIL_ON="active_deploy_id" "$DEPLOY_SH" finalize --deploy-id dep-3 2>/dev/null
rc=$?
set -e
assert_eq "99" "$rc" "finalize interrupted after active_deploy_id"
"$DEPLOY_SH" finalize --deploy-id dep-3
assert_eq "blue" "$(cat "$DEPLOY_STATE_DIR/active_color")" "finalize retry sets active_color=blue"
assert_eq "sha-333" "$(cat "$DEPLOY_STATE_DIR/active_sha")" "finalize retry sets active_sha=sha-333"
assert_eq "dep-3" "$(cat "$DEPLOY_STATE_DIR/active_deploy_id")" "finalize retry sets active_deploy_id=dep-3"
assert_eq "green" "$(cat "$DEPLOY_STATE_DIR/prev_color")" "finalize retry preserves prev_color=green"
assert_eq "sha-222" "$(cat "$DEPLOY_STATE_DIR/prev_sha")" "finalize retry preserves prev_sha=sha-222"
assert_eq "dep-2" "$(cat "$DEPLOY_STATE_DIR/prev_deploy_id")" "finalize retry preserves prev_deploy_id=dep-2 (not dep-3)"
if "$DEPLOY_SH" finalize --deploy-id dep-3 2>/dev/null; then
  echo "Expected duplicate finalize to fail" >&2
  exit 1
fi

# Now deploy dep-4, interrupt after active_deploy_id, and abort -> must restore active=blue/dep-3 AND prev=green/dep-2
"$DEPLOY_SH" deploy sha-444 --mode bluegreen --phase switch --deploy-id dep-4 >/dev/null
set +e
PATH="$CASE_DIR/failbin:$PATH" INJECT_MV_FAIL_ON="active_deploy_id" "$DEPLOY_SH" finalize --deploy-id dep-4 2>/dev/null
rc=$?
set -e
assert_eq "99" "$rc" "finalize dep-4 interrupted after active_deploy_id"
"$DEPLOY_SH" abort --deploy-id dep-4
assert_eq "blue" "$(cat "$DEPLOY_STATE_DIR/active_color")" "abort restores active_color=blue"
assert_eq "sha-333" "$(cat "$DEPLOY_STATE_DIR/active_sha")" "abort restores active_sha=sha-333"
assert_eq "dep-3" "$(cat "$DEPLOY_STATE_DIR/active_deploy_id")" "abort restores active_deploy_id=dep-3"
assert_eq "green" "$(cat "$DEPLOY_STATE_DIR/prev_color")" "abort restores prev_color=green"
assert_eq "sha-222" "$(cat "$DEPLOY_STATE_DIR/prev_sha")" "abort restores prev_sha=sha-222"
assert_eq "dep-2" "$(cat "$DEPLOY_STATE_DIR/prev_deploy_id")" "abort restores prev_deploy_id=dep-2"
assert_eq "sha-222" "$(cat "$DEPLOY_STATE_DIR/green_tag")" "abort restores green_tag=sha-222"
assert_file_contains "$CADDY_RUNTIME_DIR/upstream.caddy" "app-blue:8080" "abort restores blue upstream"
[[ -f "$MOCK_LOG_DIR/running_app-blue" ]] || { echo "blue running after abort" >&2; exit 1; }
[[ ! -f "$MOCK_LOG_DIR/running_app-green" ]] || { echo "green stopped after abort" >&2; exit 1; }

# 13c: interrupt clear_pending_state before and after pending_deploy_id removal
setup_env interrupt_clear_pending
make_interrupt_bins
"$DEPLOY_SH" bootstrap sha-111 --deploy-id boot-1 >/dev/null
"$DEPLOY_SH" deploy sha-222 --mode bluegreen --phase switch --deploy-id dep-2 >/dev/null
set +e
PATH="$CASE_DIR/failbin:$PATH" INJECT_RM_FAIL_BEFORE="pending_deploy_id" "$DEPLOY_SH" finalize --deploy-id dep-2 2>/dev/null
rc=$?
set -e
assert_eq "98" "$rc" "rm interrupted before pending_deploy_id"
assert_eq "dep-2" "$(cat "$DEPLOY_STATE_DIR/pending_deploy_id")" "pending_deploy_id still present"
"$DEPLOY_SH" finalize --deploy-id dep-2
assert_eq "green" "$(cat "$DEPLOY_STATE_DIR/active_color")" "retry after rm failure succeeds"

"$DEPLOY_SH" deploy sha-333 --mode bluegreen --phase switch --deploy-id dep-3 >/dev/null
set +e
PATH="$CASE_DIR/failbin:$PATH" INJECT_RM_FAIL_AFTER_ID="1" "$DEPLOY_SH" finalize --deploy-id dep-3 2>/dev/null
rc=$?
set -e
assert_eq "97" "$rc" "rm interrupted after pending_deploy_id removal"
[[ ! -f "$DEPLOY_STATE_DIR/pending_deploy_id" ]] || { echo "pending_deploy_id removed" >&2; exit 1; }
[[ -f "$DEPLOY_STATE_DIR/pending_color" ]] || { echo "orphaned pending_color remains" >&2; exit 1; }
"$DEPLOY_SH" status >"$CASE_DIR/orphan-status.txt"
assert_file_contains "$CASE_DIR/orphan-status.txt" "pending_deploy_id=none" "status ignores orphaned pending files"
assert_file_contains "$CASE_DIR/orphan-status.txt" "pending_color=none" "status reports pending_color=none"
"$DEPLOY_SH" deploy sha-444 --mode bluegreen --phase switch --deploy-id dep-4 >/dev/null
"$DEPLOY_SH" finalize --deploy-id dep-4 >/dev/null
assert_eq "green" "$(cat "$DEPLOY_STATE_DIR/active_color")" "next deploy cleans orphans and succeeds"

echo "=== [14/16] CLI full deploy & post-commit rollback enforce G4_HOOK (bootstrap & pending abort preserved) ==="
setup_env cli_g4_required
# Shorthand on fresh server uses bootstrap without G4_HOOK
"$DEPLOY_SH" sha-111 >/dev/null
assert_eq "blue" "$(cat "$DEPLOY_STATE_DIR/active_color")" "shorthand bootstrap succeeds without G4_HOOK"

# Shorthand, --mode auto, and --mode bluegreen --phase full all reject missing G4_HOOK without touching containers
: >"$MOCK_LOG_DIR/commands.log"
for cmd_args in "sha-222" "deploy sha-222 --mode auto --phase full" "deploy sha-222 --mode bluegreen --phase full"; do
  set +e
  # shellcheck disable=SC2086
  "$DEPLOY_SH" $cmd_args 2>/dev/null
  rc=$?
  set -e
  [[ "$rc" -ne 0 ]] || { echo "Expected '$cmd_args' without G4_HOOK to fail" >&2; exit 1; }
done
assert_eq "blue" "$(cat "$DEPLOY_STATE_DIR/active_color")" "active remains blue when G4_HOOK missing"
assert_file_contains "$CADDY_RUNTIME_DIR/upstream.caddy" "app-blue:8080" "upstream untouched when G4_HOOK missing"
[[ -f "$MOCK_LOG_DIR/running_app-blue" ]] || { echo "app-blue still running when G4_HOOK missing" >&2; exit 1; }
[[ ! -f "$DEPLOY_STATE_DIR/pending_deploy_id" ]] || { echo "no pending_deploy_id left when G4_HOOK missing" >&2; exit 1; }
assert_file_not_contains "$MOCK_LOG_DIR/commands.log" "up -d" "missing G4_HOOK fails before starting new container"

# Passing G4_HOOK sees both colors running, then finalize stops old blue
cat >"$CASE_DIR/pass-g4.sh" <<EOF
#!/usr/bin/env bash
set -euo pipefail
[[ -f "$MOCK_LOG_DIR/running_app-blue" && -f "$MOCK_LOG_DIR/running_app-green" ]] || exit 1
EOF
chmod +x "$CASE_DIR/pass-g4.sh"
export G4_HOOK="$CASE_DIR/pass-g4.sh"
"$DEPLOY_SH" deploy sha-222 --mode bluegreen --phase full --deploy-id dep-full-ok >/dev/null
assert_eq "green" "$(cat "$DEPLOY_STATE_DIR/active_color")" "full deploy with G4_HOOK commits green"
[[ ! -f "$MOCK_LOG_DIR/running_app-blue" ]] || { echo "old blue stopped after G4_HOOK pass" >&2; exit 1; }

# Post-commit rollback without G4_HOOK fails closed and keeps green running
unset G4_HOOK
if "$DEPLOY_SH" rollback 2>/dev/null; then
  echo "Expected post-commit rollback without G4_HOOK to fail" >&2
  exit 1
fi
assert_eq "green" "$(cat "$DEPLOY_STATE_DIR/active_color")" "active remains green when rollback lacks G4_HOOK"
[[ -f "$MOCK_LOG_DIR/running_app-green" ]] || { echo "green stays running when rollback lacks G4_HOOK" >&2; exit 1; }
[[ ! -f "$DEPLOY_STATE_DIR/pending_deploy_id" ]] || { echo "no pending_deploy_id when rollback lacks G4_HOOK" >&2; exit 1; }

# Post-commit rollback with failing G4_HOOK aborts back to green; passing G4_HOOK commits blue
cat >"$CASE_DIR/fail-g4.sh" <<'EOF'
#!/usr/bin/env bash
exit 1
EOF
chmod +x "$CASE_DIR/fail-g4.sh"
export G4_HOOK="$CASE_DIR/fail-g4.sh"
if "$DEPLOY_SH" rollback --deploy-id rb-fail 2>/dev/null; then
  echo "Expected rollback with failing G4_HOOK to fail" >&2
  exit 1
fi
assert_eq "green" "$(cat "$DEPLOY_STATE_DIR/active_color")" "active remains green after rollback G4_HOOK failure"
[[ -f "$MOCK_LOG_DIR/running_app-green" ]] || { echo "green kept running after rollback G4_HOOK failure" >&2; exit 1; }
[[ ! -f "$MOCK_LOG_DIR/running_app-blue" ]] || { echo "blue stopped after rollback G4_HOOK failure" >&2; exit 1; }

export G4_HOOK="$CASE_DIR/pass-g4.sh"
"$DEPLOY_SH" rollback --deploy-id rb-pass >/dev/null
assert_eq "blue" "$(cat "$DEPLOY_STATE_DIR/active_color")" "rollback with passing G4_HOOK commits blue"
[[ ! -f "$MOCK_LOG_DIR/running_app-green" ]] || { echo "green stopped after rollback G4_HOOK pass" >&2; exit 1; }

# Pending deploy rollback still works WITHOUT G4_HOOK
unset G4_HOOK
"$DEPLOY_SH" deploy sha-333 --mode bluegreen --phase switch --deploy-id dep-pend >/dev/null
"$DEPLOY_SH" rollback >/dev/null
assert_eq "blue" "$(cat "$DEPLOY_STATE_DIR/active_color")" "pending rollback works without G4_HOOK"
[[ ! -f "$DEPLOY_STATE_DIR/pending_deploy_id" ]] || { echo "pending cleared after pending rollback" >&2; exit 1; }

echo "=== [15/16] stop/rm error propagation, idempotency, and finalize retry ==="
# 15a: stop failure (and silent no-op stop leaving container running) -> non-zero, pending kept, active unchanged, retry succeeds
setup_env stop_rm_failures
"$DEPLOY_SH" bootstrap sha-111 --deploy-id boot-1 >/dev/null
"$DEPLOY_SH" deploy sha-222 --mode bluegreen --phase switch --deploy-id dep-2 >/dev/null
export MOCK_FAIL_STOP_SILENT=1
if "$DEPLOY_SH" finalize --deploy-id dep-2 2>/dev/null; then
  echo "Expected finalize to fail when container stays running after stop" >&2
  exit 1
fi
assert_eq "blue" "$(cat "$DEPLOY_STATE_DIR/active_color")" "active remains blue when still running"
assert_eq "dep-2" "$(cat "$DEPLOY_STATE_DIR/pending_deploy_id")" "pending kept when still running"
unset MOCK_FAIL_STOP_SILENT

export MOCK_FAIL_STOP=1
if "$DEPLOY_SH" finalize --deploy-id dep-2 2>/dev/null; then
  echo "Expected finalize to fail when stop fails" >&2
  exit 1
fi
assert_eq "blue" "$(cat "$DEPLOY_STATE_DIR/active_color")" "active remains blue on stop fail"
assert_eq "dep-2" "$(cat "$DEPLOY_STATE_DIR/pending_deploy_id")" "pending kept on stop fail"
assert_eq "FINALIZE_STOP_FAILED" "$(cat "$DEPLOY_STATE_DIR/last_deploy_result")" "last_deploy_result records stop fail"
[[ -f "$MOCK_LOG_DIR/running_app-blue" ]] || { echo "app-blue still running" >&2; exit 1; }
unset MOCK_FAIL_STOP

# 15b: rm failure (stop succeeds) -> non-zero, pending kept, retry succeeds idempotently
export MOCK_FAIL_RM=1
if "$DEPLOY_SH" finalize --deploy-id dep-2 2>/dev/null; then
  echo "Expected finalize to fail when rm fails" >&2
  exit 1
fi
assert_eq "blue" "$(cat "$DEPLOY_STATE_DIR/active_color")" "active remains blue on rm fail"
assert_eq "dep-2" "$(cat "$DEPLOY_STATE_DIR/pending_deploy_id")" "pending kept on rm fail"
assert_eq "FINALIZE_STOP_FAILED" "$(cat "$DEPLOY_STATE_DIR/last_deploy_result")" "last_deploy_result records rm fail"
[[ ! -f "$MOCK_LOG_DIR/running_app-blue" ]] || { echo "app-blue stopped even though rm failed" >&2; exit 1; }
[[ -f "$MOCK_LOG_DIR/container_app-blue" ]] || { echo "app-blue container still present after rm fail" >&2; exit 1; }
unset MOCK_FAIL_RM
"$DEPLOY_SH" finalize --deploy-id dep-2 >/dev/null
assert_eq "green" "$(cat "$DEPLOY_STATE_DIR/active_color")" "finalize retry succeeds after rm recovery"
[[ ! -f "$DEPLOY_STATE_DIR/pending_deploy_id" ]] || { echo "pending cleared after retry" >&2; exit 1; }

# 15c: both stop and rm fail, then recover & retry; plus already-removed container idempotency
"$DEPLOY_SH" deploy sha-333 --mode bluegreen --phase switch --deploy-id dep-3 >/dev/null
export MOCK_FAIL_STOP=1 MOCK_FAIL_RM=1
if "$DEPLOY_SH" finalize --deploy-id dep-3 2>/dev/null; then
  echo "Expected finalize to fail when both stop and rm fail" >&2
  exit 1
fi
assert_eq "green" "$(cat "$DEPLOY_STATE_DIR/active_color")" "active remains green on stop+rm fail"
assert_eq "dep-3" "$(cat "$DEPLOY_STATE_DIR/pending_deploy_id")" "pending kept on stop+rm fail"
unset MOCK_FAIL_STOP MOCK_FAIL_RM
# Remove container manually beforehand to test idempotent finalize when container is already gone
rm -f "$MOCK_LOG_DIR/running_app-green" "$MOCK_LOG_DIR/container_app-green"
"$DEPLOY_SH" finalize --deploy-id dep-3 >/dev/null
assert_eq "blue" "$(cat "$DEPLOY_STATE_DIR/active_color")" "finalize succeeds idempotently when container already removed"
[[ ! -f "$DEPLOY_STATE_DIR/pending_deploy_id" ]] || { echo "pending cleared after idempotent finalize" >&2; exit 1; }

echo "=== [16/16] monitoring overlay: deploy/state tags, same project, no deploy lock or state change ==="
setup_env monitoring_overlay
export MONITORING_COMPOSE_FILE="$CASE_DIR/compose.monitoring.yml"
printf '# dummy overlay for mock docker
' >"$MONITORING_COMPOSE_FILE"
# Before any deploy there is no tag state: the placeholder keeps compose.prod.yml's required tags satisfied.
"$DEPLOY_SH" monitoring config -q
assert_file_contains "$MOCK_LOG_DIR/commands.log" "docker compose -f $COMPOSE_FILE --env-file $ENV_FILE -f $MONITORING_COMPOSE_FILE config -q" "overlay on the same project and env file"
assert_file_contains "$MOCK_LOG_DIR/commands.log" "monitoring-tags blue=unset green=unset" "placeholder tags before bootstrap"
"$DEPLOY_SH" bootstrap sha-111 --deploy-id boot-1 >/dev/null
state_before="$(cd "$DEPLOY_STATE_DIR" && for f in active_color active_sha blue_tag; do printf '%s=%s;' "$f" "$(cat "$f")"; done)"
: >"$MOCK_LOG_DIR/commands.log"
# Holding the deploy lock does not block it: monitoring never takes the lock (it touches no app, Caddy or state).
( exec 8>"$DEPLOY_LOCK_FILE"; flock 8; "$DEPLOY_SH" monitoring up -d prometheus grafana )
assert_file_contains "$MOCK_LOG_DIR/commands.log" "$ENV_FILE -f $MONITORING_COMPOSE_FILE up -d prometheus grafana" "monitoring up passes compose args"
assert_file_contains "$MOCK_LOG_DIR/commands.log" "monitoring-tags blue=sha-111 green=unset" "tags come from deploy/state, not .env"
assert_file_not_contains "$MOCK_LOG_DIR/commands.log" "app-" "monitoring never starts or stops an app"
state_after="$(cd "$DEPLOY_STATE_DIR" && for f in active_color active_sha blue_tag; do printf '%s=%s;' "$f" "$(cat "$f")"; done)"
assert_eq "$state_before" "$state_after" "deploy state unchanged by monitoring"
[[ ! -f "$DEPLOY_STATE_DIR/pending_deploy_id" ]] || { echo "monitoring must not create a pending deploy" >&2; exit 1; }
# Fail closed before any docker call: no arguments, or no overlay file.
: >"$MOCK_LOG_DIR/commands.log"
if "$DEPLOY_SH" monitoring 2>/dev/null; then echo "Expected monitoring without arguments to fail" >&2; exit 1; fi
rm -f "$MONITORING_COMPOSE_FILE"
if "$DEPLOY_SH" monitoring ps 2>/dev/null; then echo "Expected monitoring without the overlay file to fail" >&2; exit 1; fi
[[ ! -s "$MOCK_LOG_DIR/commands.log" ]] || { echo "no docker call expected on a refused monitoring command" >&2; cat "$MOCK_LOG_DIR/commands.log" >&2; exit 1; }
unset MONITORING_COMPOSE_FILE

echo "ALL_DEPLOY_TESTS_PASSED"
