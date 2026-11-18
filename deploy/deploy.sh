#!/usr/bin/env bash
set -euo pipefail

# Gongchae-Bell blue/green deployment and rollback script (ADR-0005, ADR-0014).
# Enforces ADR-0005 order:
#   1. Start inactive color
#   2. Check internal readiness and core HTTP routes (stop new color on failure)
#   3. Validate & reload Caddy upstream to new color
#   4. Keep old color running through external G4 verification (abort/rollback immediately on failure)
#   5. SIGTERM old color with 40s graceful shutdown (finalize)
#   6. Commit active_color / active_sha / active_deploy_id
#
# Requires: bash, flock, docker (with compose plugin), curl.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="${DEPLOY_ROOT_DIR:-$(cd "$SCRIPT_DIR/.." && pwd)}"
COMPOSE_FILE="${COMPOSE_FILE:-$ROOT_DIR/compose.prod.yml}"
ENV_FILE="${ENV_FILE:-$ROOT_DIR/.env}"
CADDY_TEMPLATES_DIR="${CADDY_TEMPLATES_DIR:-$ROOT_DIR/deploy/caddy}"
STATE_DIR="${DEPLOY_STATE_DIR:-$ROOT_DIR/deploy/state}"
CADDY_RUNTIME_DIR="${CADDY_RUNTIME_DIR:-$STATE_DIR/caddy}"
ERASURE_HOST_DIR="${ERASURE_HOST_DIR:-/var/lib/gongchae-bell/erasure}"
LOCK_FILE="${DEPLOY_LOCK_FILE:-$STATE_DIR/deploy.lock}"
LOCK_WAIT_SECONDS="${LOCK_WAIT_SECONDS:-0}"
READINESS_TIMEOUT="${READINESS_TIMEOUT:-60}"
READINESS_INTERVAL="${READINESS_INTERVAL:-2}"
STOP_GRACE_SECONDS="${STOP_GRACE_SECONDS:-40}"
PULL_POLICY="${PULL_POLICY:-missing}" # CI passes always; local test doubles can set never
SITE_DOMAIN="${SITE_DOMAIN:-gongchae.enjoylcjworld.com}"
POST_SWITCH_SCHEME="${POST_SWITCH_SCHEME:-https}"
POST_SWITCH_PORT="${POST_SWITCH_PORT:-443}"
DOCKER_BIN="${DOCKER_BIN:-docker}"
CURL_BIN="${CURL_BIN:-curl}"

log() {
  printf '[%s] %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*"
}

fail() {
  log "ERROR: $*" >&2
  exit 1
}

require_tools() {
  command -v "$DOCKER_BIN" >/dev/null 2>&1 || fail "docker binary not found: $DOCKER_BIN"
  command -v "$CURL_BIN" >/dev/null 2>&1 || fail "curl binary not found: $CURL_BIN"
  command -v flock >/dev/null 2>&1 || fail "flock is required for deployment locking"
}

load_env_defaults() {
  if [[ -f "$ENV_FILE" ]]; then
    set -a
    # shellcheck disable=SC1090
    source "$ENV_FILE"
    set +a
  fi
  SITE_DOMAIN="${SITE_DOMAIN:-gongchae.enjoylcjworld.com}"
  ERASURE_HOST_DIR="${ERASURE_HOST_DIR:-/var/lib/gongchae-bell/erasure}"
  CADDY_RUNTIME_DIR="${CADDY_RUNTIME_DIR:-$STATE_DIR/caddy}"
  export SITE_DOMAIN ERASURE_HOST_DIR CADDY_RUNTIME_DIR
}

compose() {
  local args=(-f "$COMPOSE_FILE")
  if [[ -f "$ENV_FILE" ]]; then
    args+=(--env-file "$ENV_FILE")
  fi
  "$DOCKER_BIN" compose "${args[@]}" "$@"
}

atomic_write() {
  local target="$1"
  local value="$2"
  local tmp="${target}.tmp.$$"
  mkdir -p "$(dirname "$target")"
  printf '%s\n' "$value" >"$tmp"
  mv -f "$tmp" "$target"
}

read_state() {
  local name="$1"
  local path="$STATE_DIR/$name"
  if [[ -f "$path" ]]; then
    tr -d '[:space:]' <"$path"
  fi
}

clear_pending_state() {
  rm -f \
    "$STATE_DIR/pending_deploy_id" \
    "$STATE_DIR/pending_action" \
    "$STATE_DIR/pending_from_color" \
    "$STATE_DIR/pending_from_sha" \
    "$STATE_DIR/pending_color" \
    "$STATE_DIR/pending_sha" \
    "$STATE_DIR/pending_started_at" \
    "$CADDY_RUNTIME_DIR/upstream.caddy.prev"
}

write_pending_state() {
  local deploy_id="$1"
  local action="$2"
  local from_color="$3"
  local from_sha="$4"
  local target_color="$5"
  local target_sha="$6"
  atomic_write "$STATE_DIR/pending_deploy_id" "$deploy_id"
  atomic_write "$STATE_DIR/pending_action" "$action"
  atomic_write "$STATE_DIR/pending_from_color" "$from_color"
  atomic_write "$STATE_DIR/pending_from_sha" "$from_sha"
  atomic_write "$STATE_DIR/pending_color" "$target_color"
  atomic_write "$STATE_DIR/pending_sha" "$target_sha"
  atomic_write "$STATE_DIR/pending_started_at" "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
}

read_upstream_color() {
  local upstream_file="$CADDY_RUNTIME_DIR/upstream.caddy"
  if [[ ! -f "$upstream_file" ]]; then
    return 0
  fi
  if grep -qE 'reverse_proxy[[:space:]]+app-blue:8080' "$upstream_file"; then
    printf 'blue'
  elif grep -qE 'reverse_proxy[[:space:]]+app-green:8080' "$upstream_file"; then
    printf 'green'
  else
    printf 'unknown'
  fi
}

opposite_color() {
  case "$1" in
    blue) printf 'green' ;;
    green) printf 'blue' ;;
    *) fail "Invalid color: $1" ;;
  esac
}

validate_identifier() {
  local label="$1"
  local val="$2"
  [[ -n "$val" ]] || fail "$label must not be empty"
  [[ "$val" =~ ^[A-Za-z0-9._-]+$ ]] || fail "Invalid $label characters: $val"
}

default_deploy_id() {
  local prefix="$1"
  printf '%s-%s-%d' "$prefix" "$(date -u +%Y%m%dT%H%M%SZ)" "$$"
}

ensure_dirs_and_cleanup_trap() {
  mkdir -p "$STATE_DIR" "$CADDY_RUNTIME_DIR"
  trap 'rm -f "$CADDY_RUNTIME_DIR/upstream.caddy.next" "$STATE_DIR"/*.tmp.$$' EXIT
}

acquire_lock() {
  mkdir -p "$(dirname "$LOCK_FILE")"
  exec 9>"$LOCK_FILE"
  if [[ "$LOCK_WAIT_SECONDS" -gt 0 ]]; then
    if ! flock -w "$LOCK_WAIT_SECONDS" 9; then
      log "ERROR: LOCK_HELD lock_file=$LOCK_FILE wait=${LOCK_WAIT_SECONDS}s" >&2
      exit 20
    fi
  else
    if ! flock -n 9; then
      log "ERROR: LOCK_HELD lock_file=$LOCK_FILE" >&2
      exit 20
    fi
  fi
}

export_color_tags() {
  local blue_tag green_tag
  blue_tag="$(read_state blue_tag)"
  green_tag="$(read_state green_tag)"
  export APP_BLUE_TAG="${APP_BLUE_TAG:-${blue_tag:-unset}}"
  export APP_GREEN_TAG="${APP_GREEN_TAG:-${green_tag:-unset}}"
}

start_app_color() {
  local color="$1"
  local tag="$2"
  export_color_tags
  if [[ "$color" == "blue" ]]; then
    export APP_BLUE_TAG="$tag"
  else
    export APP_GREEN_TAG="$tag"
  fi
  if [[ "$PULL_POLICY" == "always" ]]; then
    compose --profile "$color" pull "app-$color"
  fi
  compose --profile "$color" up -d --no-deps "app-$color"
}

stop_and_remove_color() {
  local color="$1"
  export_color_tags
  compose --profile "$color" stop -t "$STOP_GRACE_SECONDS" "app-$color" || true
  compose --profile "$color" rm -f "app-$color" || true
}

check_internal_app() {
  local color="$1"
  local deadline=$(( $(date +%s) + READINESS_TIMEOUT ))
  local readiness_body=""
  local ready=0

  log "Checking internal readiness for app-$color (timeout=${READINESS_TIMEOUT}s)"
  while [[ $(date +%s) -le $deadline ]]; do
    if readiness_body="$(compose --profile "$color" exec -T "app-$color" \
        "$CURL_BIN" -fsS --max-time 3 "http://127.0.0.1:8081/actuator/health/readiness" 2>/dev/null)"; then
      if [[ "$readiness_body" == *'"status":"UP"'* ]]; then
        ready=1
        break
      fi
    fi
    sleep "$READINESS_INTERVAL"
  done

  if [[ "$ready" -ne 1 ]]; then
    log "PRE_SWITCH_FAIL readiness failed for app-$color" >&2
    return 1
  fi

  local route code
  for route in "/" "/jobs" "/robots.txt"; do
    code="$(compose --profile "$color" exec -T "app-$color" \
      "$CURL_BIN" -sS -o /dev/null -w '%{http_code}' --max-time 5 "http://127.0.0.1:8080${route}" || echo "000")"
    if [[ "$code" != "200" ]]; then
      log "PRE_SWITCH_FAIL route=${route} status=${code} on app-$color" >&2
      return 1
    fi
  done

  log "Internal readiness and key routes passed for app-$color"
  return 0
}

is_app_color_healthy_now() {
  local color="$1"
  local readiness_body=""
  if ! readiness_body="$(compose --profile "$color" exec -T "app-$color" \
      "$CURL_BIN" -fsS --max-time 3 "http://127.0.0.1:8081/actuator/health/readiness" 2>/dev/null)"; then
    return 1
  fi
  [[ "$readiness_body" == *'"status":"UP"'* ]] || return 1

  local route code
  for route in "/" "/jobs" "/robots.txt"; do
    code="$(compose --profile "$color" exec -T "app-$color" \
      "$CURL_BIN" -sS -o /dev/null -w '%{http_code}' --max-time 5 "http://127.0.0.1:8080${route}" || echo "000")"
    [[ "$code" == "200" ]] || return 1
  done
  return 0
}

stage_and_validate_upstream() {
  local target_color="$1"
  local template="$CADDY_TEMPLATES_DIR/upstream.${target_color}.caddy"
  [[ -f "$template" ]] || fail "Missing upstream template: $template"
  cp -f "$template" "$CADDY_RUNTIME_DIR/upstream.caddy.next"

  if ! compose exec -T caddy sh -c '
    sed "s|/etc/caddy/runtime/upstream.caddy|/etc/caddy/runtime/upstream.caddy.next|g" /etc/caddy/Caddyfile > /tmp/Caddyfile.validate
    caddy validate --config /tmp/Caddyfile.validate --adapter caddyfile
    rc=$?
    rm -f /tmp/Caddyfile.validate
    exit $rc
  '; then
    rm -f "$CADDY_RUNTIME_DIR/upstream.caddy.next"
    log "CADDY_VALIDATE_FAIL for target=$target_color" >&2
    return 1
  fi
  return 0
}

atomic_reload_upstream() {
  local target_color="$1"
  if [[ -f "$CADDY_RUNTIME_DIR/upstream.caddy" ]]; then
    cp -f "$CADDY_RUNTIME_DIR/upstream.caddy" "$CADDY_RUNTIME_DIR/upstream.caddy.prev"
  fi
  mv -f "$CADDY_RUNTIME_DIR/upstream.caddy.next" "$CADDY_RUNTIME_DIR/upstream.caddy"
  if ! compose exec -T caddy caddy reload --config /etc/caddy/Caddyfile --adapter caddyfile; then
    if [[ -f "$CADDY_RUNTIME_DIR/upstream.caddy.prev" ]]; then
      mv -f "$CADDY_RUNTIME_DIR/upstream.caddy.prev" "$CADDY_RUNTIME_DIR/upstream.caddy"
    fi
    log "CADDY_RELOAD_FAIL reverted upstream.caddy" >&2
    return 1
  fi
  return 0
}

rollback_reload_from_prev() {
  if [[ ! -f "$CADDY_RUNTIME_DIR/upstream.caddy.prev" ]]; then
    log "ROLLBACK_RELOAD_FAILED missing upstream.caddy.prev" >&2
    return 1
  fi
  mv -f "$CADDY_RUNTIME_DIR/upstream.caddy.prev" "$CADDY_RUNTIME_DIR/upstream.caddy"
  if ! compose exec -T caddy caddy reload --config /etc/caddy/Caddyfile --adapter caddyfile; then
    log "ROLLBACK_RELOAD_FAILED caddy reload failed during rollback; keeping both containers running" >&2
    return 1
  fi
  return 0
}

post_switch_check() {
  if [[ -n "${POST_SWITCH_HOOK:-}" ]]; then
    "$POST_SWITCH_HOOK"
    return $?
  fi

  local route code url
  for route in "/" "/jobs" "/robots.txt"; do
    url="${POST_SWITCH_SCHEME}://${SITE_DOMAIN}:${POST_SWITCH_PORT}${route}"
    code="$("$CURL_BIN" -sS -o /dev/null -w '%{http_code}' \
      --connect-timeout 3 --max-time 5 \
      --resolve "${SITE_DOMAIN}:${POST_SWITCH_PORT}:127.0.0.1" \
      "$url" || echo "000")"
    if [[ "$code" != "200" ]]; then
      log "POST_SWITCH_FAIL route=${route} status=${code} url=${url}" >&2
      return 1
    fi
  done
  return 0
}

assert_no_pending_and_consistent_state() {
  local pending_id active upstream
  pending_id="$(read_state pending_deploy_id)"
  if [[ -n "$pending_id" ]]; then
    fail "PENDING_DEPLOY_EXISTS pending_deploy_id=$pending_id pending_color=$(read_state pending_color) pending_sha=$(read_state pending_sha); finalize or abort/rollback before starting another deploy"
  fi

  active="$(read_state active_color)"
  upstream="$(read_upstream_color)"

  if [[ -z "$active" ]]; then
    return 0
  fi
  if [[ "$active" != "blue" && "$active" != "green" ]]; then
    fail "STATE_MISMATCH invalid active_color='$active'"
  fi
  if [[ "$upstream" != "$active" ]]; then
    fail "STATE_MISMATCH active_color='$active' disagrees with upstream.caddy='$upstream'; resolve interrupted state manually before retry"
  fi
}

cmd_status() {
  local active sha active_id prev_color prev_sha prev_id upstream
  local pending_id pending_action pending_color pending_sha pending_started_at
  local last_id last_result
  active="$(read_state active_color)"
  sha="$(read_state active_sha)"
  active_id="$(read_state active_deploy_id)"
  prev_color="$(read_state prev_color)"
  prev_sha="$(read_state prev_sha)"
  prev_id="$(read_state prev_deploy_id)"
  upstream="$(read_upstream_color)"
  pending_id="$(read_state pending_deploy_id)"
  pending_action="$(read_state pending_action)"
  pending_color="$(read_state pending_color)"
  pending_sha="$(read_state pending_sha)"
  pending_started_at="$(read_state pending_started_at)"
  last_id="$(read_state last_deploy_id)"
  last_result="$(read_state last_deploy_result)"

  printf 'active_color=%s\nactive_sha=%s\nactive_deploy_id=%s\nprev_color=%s\nprev_sha=%s\nprev_deploy_id=%s\nupstream_color=%s\npending_deploy_id=%s\npending_action=%s\npending_color=%s\npending_sha=%s\npending_started_at=%s\nlast_deploy_id=%s\nlast_deploy_result=%s\n' \
    "${active:-none}" "${sha:-none}" "${active_id:-none}" \
    "${prev_color:-none}" "${prev_sha:-none}" "${prev_id:-none}" \
    "${upstream:-none}" \
    "${pending_id:-none}" "${pending_action:-none}" "${pending_color:-none}" "${pending_sha:-none}" "${pending_started_at:-none}" \
    "${last_id:-none}" "${last_result:-none}"
}

cmd_bootstrap() {
  local sha="$1"
  local deploy_id="${2:-}"
  validate_identifier "image tag/SHA" "$sha"
  if [[ -z "$deploy_id" ]]; then
    deploy_id="$(default_deploy_id "bootstrap")"
  else
    validate_identifier "deploy_id" "$deploy_id"
  fi

  local pending_id active
  pending_id="$(read_state pending_deploy_id)"
  [[ -z "$pending_id" ]] || fail "BOOTSTRAP_REJECTED pending_deploy_id='$pending_id' exists"
  active="$(read_state active_color)"
  if [[ -n "$active" ]]; then
    fail "BOOTSTRAP_REJECTED active_color='$active' already exists; use bluegreen deploy"
  fi
  [[ -d "$ERASURE_HOST_DIR" ]] || fail "Missing host erasure directory: $ERASURE_HOST_DIR (runbook step required)"

  log "Starting initial bootstrap deploy_id=$deploy_id color=blue sha=$sha"
  cp -f "$CADDY_TEMPLATES_DIR/upstream.blue.caddy" "$CADDY_RUNTIME_DIR/upstream.caddy"

  export_color_tags
  compose up -d --wait postgres

  if ! start_app_color "blue" "$sha" || ! check_internal_app "blue"; then
    stop_and_remove_color "blue"
    fail "BOOTSTRAP_APP_FAIL deploy_id=$deploy_id app-blue failed readiness/route checks"
  fi

  if ! compose up -d --no-deps caddy || ! compose exec -T caddy caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile; then
    stop_and_remove_color "blue"
    fail "BOOTSTRAP_CADDY_FAIL deploy_id=$deploy_id caddy startup/validation failed"
  fi

  atomic_write "$STATE_DIR/blue_tag" "$sha"
  atomic_write "$STATE_DIR/active_sha" "$sha"
  atomic_write "$STATE_DIR/active_color" "blue"
  atomic_write "$STATE_DIR/active_deploy_id" "$deploy_id"
  atomic_write "$STATE_DIR/last_deploy_id" "$deploy_id"
  atomic_write "$STATE_DIR/last_deploy_result" "BOOTSTRAP_OK"

  log "BOOTSTRAP_OK deploy_id=$deploy_id color=blue sha=$sha (initial bootstrap separated from steady-state G4)"
}

switch_to_target() {
  local action="$1"
  local old_color="$2"
  local old_sha="$3"
  local target_color="$4"
  local target_sha="$5"
  local deploy_id="$6"

  log "Starting ${action} switch deploy_id=$deploy_id active=${old_color}(${old_sha}) -> target=${target_color}(${target_sha})"

  if ! start_app_color "$target_color" "$target_sha" || ! check_internal_app "$target_color"; then
    stop_and_remove_color "$target_color"
    fail "PRE_SWITCH_FAIL deploy_id=$deploy_id target=app-$target_color sha=$target_sha; active=$old_color untouched"
  fi

  if ! stage_and_validate_upstream "$target_color"; then
    stop_and_remove_color "$target_color"
    fail "CADDY_VALIDATE_FAIL deploy_id=$deploy_id target=$target_color; active=$old_color untouched"
  fi

  # Record pending deployment identity right before Caddy upstream switch so interrupted runs remain traceable.
  write_pending_state "$deploy_id" "$action" "$old_color" "$old_sha" "$target_color" "$target_sha"

  if ! atomic_reload_upstream "$target_color"; then
    clear_pending_state
    stop_and_remove_color "$target_color"
    fail "CADDY_RELOAD_FAIL deploy_id=$deploy_id target=$target_color; active=$old_color kept"
  fi

  if ! post_switch_check; then
    log "Post-switch check failed on $target_color (deploy_id=$deploy_id); reverting Caddy upstream to running $old_color" >&2
    if ! rollback_reload_from_prev; then
      fail "ROLLBACK_RELOAD_FAILED deploy_id=$deploy_id manual intervention required; leaving app-$old_color and app-$target_color up"
    fi
    stop_and_remove_color "$target_color"
    clear_pending_state
    atomic_write "$STATE_DIR/last_deploy_id" "$deploy_id"
    atomic_write "$STATE_DIR/last_deploy_result" "POST_SWITCH_ROLLED_BACK"
    fail "POST_SWITCH_FAIL_ROLLED_BACK deploy_id=$deploy_id active remains $old_color ($old_sha)"
  fi

  log "SWITCH_OK deploy_id=$deploy_id action=$action active_kept=$old_color($old_sha) serving=$target_color($target_sha)"
}

finalize_pending_internal() {
  local expected_id="$1"
  local pending_id pending_action old_color old_sha old_deploy_id new_color new_sha upstream

  pending_id="$(read_state pending_deploy_id)"
  [[ -n "$pending_id" ]] || fail "FINALIZE_REJECTED no pending_deploy_id recorded"
  if [[ -n "$expected_id" && "$pending_id" != "$expected_id" ]]; then
    fail "DEPLOY_ID_MISMATCH expected=$expected_id actual_pending=$pending_id"
  fi

  pending_action="$(read_state pending_action)"
  old_color="$(read_state pending_from_color)"
  old_sha="$(read_state pending_from_sha)"
  new_color="$(read_state pending_color)"
  new_sha="$(read_state pending_sha)"
  old_deploy_id="$(read_state active_deploy_id)"
  upstream="$(read_upstream_color)"

  [[ "$upstream" == "$new_color" ]] || fail "STATE_MISMATCH upstream_color='$upstream' does not match pending_color='$new_color' for deploy_id=$pending_id"

  log "Finalizing deploy_id=$pending_id action=${pending_action:-bluegreen}: draining old app-$old_color (grace=${STOP_GRACE_SECONDS}s)"
  stop_and_remove_color "$old_color"

  atomic_write "$STATE_DIR/${new_color}_tag" "$new_sha"
  atomic_write "$STATE_DIR/prev_color" "$old_color"
  atomic_write "$STATE_DIR/prev_sha" "$old_sha"
  if [[ -n "$old_deploy_id" ]]; then
    atomic_write "$STATE_DIR/prev_deploy_id" "$old_deploy_id"
  fi
  atomic_write "$STATE_DIR/active_sha" "$new_sha"
  atomic_write "$STATE_DIR/active_color" "$new_color"
  atomic_write "$STATE_DIR/active_deploy_id" "$pending_id"
  atomic_write "$STATE_DIR/last_deploy_id" "$pending_id"
  if [[ "$pending_action" == "rollback" ]]; then
    atomic_write "$STATE_DIR/last_deploy_result" "ROLLBACK_OK"
  else
    atomic_write "$STATE_DIR/last_deploy_result" "DEPLOY_OK"
  fi
  clear_pending_state

  if [[ "$pending_action" == "rollback" ]]; then
    log "ROLLBACK_OK deploy_id=$pending_id color=$new_color sha=$new_sha stopped_color=$old_color"
  else
    log "DEPLOY_OK deploy_id=$pending_id color=$new_color sha=$new_sha prev_color=$old_color prev_sha=$old_sha"
  fi
}

abort_pending_internal() {
  local expected_id="$1"
  local if_pending="$2"
  local pending_id old_color old_sha new_color new_sha

  pending_id="$(read_state pending_deploy_id)"
  if [[ -z "$pending_id" ]]; then
    if [[ "$if_pending" == "1" ]]; then
      log "ABORT_NOOP no pending deployment (expected_id=${expected_id:-none})"
      return 0
    fi
    fail "ABORT_REJECTED no pending_deploy_id recorded"
  fi

  if [[ -n "$expected_id" && "$pending_id" != "$expected_id" ]]; then
    fail "DEPLOY_ID_MISMATCH expected=$expected_id actual_pending=$pending_id"
  fi

  old_color="$(read_state pending_from_color)"
  old_sha="$(read_state pending_from_sha)"
  new_color="$(read_state pending_color)"
  new_sha="$(read_state pending_sha)"

  [[ -n "$old_color" && -n "$new_color" ]] || fail "ABORT_CORRUPT missing pending color state for deploy_id=$pending_id"

  log "Aborting pending deploy_id=$pending_id: returning Caddy upstream from $new_color($new_sha) to $old_color($old_sha)"

  # Prefer already-running old_color (ADR-0005 keeps old color alive until external G4 passes); start only if absent.
  if ! is_app_color_healthy_now "$old_color"; then
    log "Previous app-$old_color is not healthy/running; starting $old_color($old_sha) before upstream revert"
    if ! start_app_color "$old_color" "$old_sha" || ! check_internal_app "$old_color"; then
      fail "ABORT_RESTORE_FAIL unable to verify/start previous app-$old_color($old_sha) for deploy_id=$pending_id"
    fi
  fi

  if ! stage_and_validate_upstream "$old_color"; then
    fail "ABORT_VALIDATE_FAIL could not validate upstream for $old_color (deploy_id=$pending_id)"
  fi

  if ! atomic_reload_upstream "$old_color"; then
    fail "ABORT_RELOAD_FAIL could not reload Caddy back to $old_color (deploy_id=$pending_id)"
  fi

  if ! post_switch_check; then
    fail "ABORT_POST_CHECK_FAIL upstream reverted to $old_color but post_switch_check failed (deploy_id=$pending_id)"
  fi

  stop_and_remove_color "$new_color"
  clear_pending_state
  atomic_write "$STATE_DIR/last_deploy_id" "$pending_id"
  atomic_write "$STATE_DIR/last_deploy_result" "ABORTED_TO_${old_color^^}"

  log "ABORT_OK deploy_id=$pending_id restored_active=$old_color($old_sha) stopped_pending=$new_color($new_sha)"
}

cmd_bluegreen() {
  local sha="$1"
  local phase="$2"
  local deploy_id="$3"

  validate_identifier "image tag/SHA" "$sha"
  if [[ -z "$deploy_id" ]]; then
    deploy_id="$(default_deploy_id "deploy")"
  else
    validate_identifier "deploy_id" "$deploy_id"
  fi

  assert_no_pending_and_consistent_state

  local old_color old_sha new_color
  old_color="$(read_state active_color)"
  [[ -n "$old_color" ]] || fail "BLUEGREEN_REJECTED server is not bootstrapped yet (no active_color)"
  old_sha="$(read_state active_sha)"
  [[ -n "$old_sha" ]] || old_sha="$(read_state "${old_color}_tag")"
  new_color="$(opposite_color "$old_color")"

  switch_to_target "bluegreen" "$old_color" "$old_sha" "$new_color" "$sha" "$deploy_id"

  if [[ "$phase" == "switch" ]]; then
    return 0
  fi

  if [[ -n "${G4_HOOK:-}" ]]; then
    if ! "$G4_HOOK" "$deploy_id"; then
      log "G4_HOOK failed for deploy_id=$deploy_id; aborting back to running $old_color" >&2
      abort_pending_internal "$deploy_id" "0"
      fail "G4_HOOK_FAIL_ROLLED_BACK deploy_id=$deploy_id active=$old_color"
    fi
  fi

  finalize_pending_internal "$deploy_id"
}

cmd_rollback() {
  local phase="$1"
  local deploy_id="$2"

  local pending_id
  pending_id="$(read_state pending_deploy_id)"
  if [[ -n "$pending_id" ]]; then
    # Interrupted or G4-failed pending switch: roll back directly to the still-active old color.
    if [[ -n "$deploy_id" ]]; then
      validate_identifier "deploy_id" "$deploy_id"
    fi
    abort_pending_internal "$deploy_id" "0"
    return 0
  fi

  assert_no_pending_and_consistent_state

  local cur_color cur_sha target_color target_sha
  cur_color="$(read_state active_color)"
  cur_sha="$(read_state active_sha)"
  target_color="$(read_state prev_color)"
  target_sha="$(read_state prev_sha)"

  [[ -n "$cur_color" ]] || fail "ROLLBACK_REJECTED no active_color recorded"
  [[ -n "$target_color" && -n "$target_sha" ]] || fail "ROLLBACK_REJECTED no previous deployment recorded"
  [[ "$target_color" != "$cur_color" ]] || fail "ROLLBACK_REJECTED prev_color equals active_color ($cur_color)"
  validate_identifier "target image tag/SHA" "$target_sha"

  if [[ -z "$deploy_id" ]]; then
    deploy_id="$(default_deploy_id "rollback")"
  else
    validate_identifier "deploy_id" "$deploy_id"
  fi

  switch_to_target "rollback" "$cur_color" "$cur_sha" "$target_color" "$target_sha" "$deploy_id"

  if [[ "$phase" == "switch" ]]; then
    return 0
  fi

  if [[ -n "${G4_HOOK:-}" ]]; then
    if ! "$G4_HOOK" "$deploy_id"; then
      log "G4_HOOK failed during rollback deploy_id=$deploy_id; reverting to $cur_color" >&2
      abort_pending_internal "$deploy_id" "0"
      fail "ROLLBACK_G4_HOOK_FAIL deploy_id=$deploy_id active=$cur_color"
    fi
  fi

  finalize_pending_internal "$deploy_id"
}

usage() {
  cat <<'EOF'
Usage:
  deploy.sh deploy <image_tag> [--mode auto|bootstrap|bluegreen] [--phase full|switch] [--deploy-id ID]
  deploy.sh bootstrap <image_tag> [--deploy-id ID]
  deploy.sh finalize --deploy-id ID
  deploy.sh abort --deploy-id ID [--if-pending]
  deploy.sh rollback [--phase full|switch] [--deploy-id ID]
  deploy.sh status
  deploy.sh <image_tag>    (shorthand for: deploy <image_tag> --mode auto --phase full)
EOF
}

main() {
  local subcmd="${1:-}"
  [[ -n "$subcmd" ]] || { usage >&2; exit 2; }

  require_tools
  load_env_defaults
  ensure_dirs_and_cleanup_trap

  case "$subcmd" in
    status)
      cmd_status
      return 0
      ;;
    finalize)
      shift
      local deploy_id=""
      while [[ $# -gt 0 ]]; do
        case "$1" in
          --deploy-id) deploy_id="${2:-}"; shift 2 || { usage >&2; exit 2; } ;;
          *) fail "Unknown argument for finalize: $1" ;;
        esac
      done
      [[ -n "$deploy_id" ]] || fail "finalize requires --deploy-id"
      validate_identifier "deploy_id" "$deploy_id"
      acquire_lock
      finalize_pending_internal "$deploy_id"
      return 0
      ;;
    abort)
      shift
      local deploy_id="" if_pending="0"
      while [[ $# -gt 0 ]]; do
        case "$1" in
          --deploy-id) deploy_id="${2:-}"; shift 2 || { usage >&2; exit 2; } ;;
          --if-pending) if_pending="1"; shift ;;
          *) fail "Unknown argument for abort: $1" ;;
        esac
      done
      [[ -n "$deploy_id" ]] || fail "abort requires --deploy-id"
      validate_identifier "deploy_id" "$deploy_id"
      acquire_lock
      abort_pending_internal "$deploy_id" "$if_pending"
      return 0
      ;;
    rollback)
      shift
      local phase="full" deploy_id=""
      while [[ $# -gt 0 ]]; do
        case "$1" in
          --phase) phase="${2:-}"; shift 2 || { usage >&2; exit 2; } ;;
          --deploy-id) deploy_id="${2:-}"; shift 2 || { usage >&2; exit 2; } ;;
          *) fail "Unknown argument for rollback: $1" ;;
        esac
      done
      [[ "$phase" == "full" || "$phase" == "switch" ]] || fail "Invalid --phase: $phase"
      acquire_lock
      cmd_rollback "$phase" "$deploy_id"
      return 0
      ;;
    bootstrap)
      local sha="${2:-}"
      shift 2 || { usage >&2; exit 2; }
      local deploy_id=""
      while [[ $# -gt 0 ]]; do
        case "$1" in
          --deploy-id) deploy_id="${2:-}"; shift 2 || { usage >&2; exit 2; } ;;
          *) fail "Unknown argument for bootstrap: $1" ;;
        esac
      done
      acquire_lock
      cmd_bootstrap "$sha" "$deploy_id"
      return 0
      ;;
    deploy)
      local sha="${2:-}"
      shift 2 || { usage >&2; exit 2; }
      local mode="auto" phase="full" deploy_id=""
      while [[ $# -gt 0 ]]; do
        case "$1" in
          --mode) mode="${2:-}"; shift 2 || { usage >&2; exit 2; } ;;
          --phase) phase="${2:-}"; shift 2 || { usage >&2; exit 2; } ;;
          --deploy-id) deploy_id="${2:-}"; shift 2 || { usage >&2; exit 2; } ;;
          *) fail "Unknown argument: $1" ;;
        esac
      done
      [[ "$phase" == "full" || "$phase" == "switch" ]] || fail "Invalid --phase: $phase"
      acquire_lock
      case "$mode" in
        bootstrap) cmd_bootstrap "$sha" "$deploy_id" ;;
        bluegreen) cmd_bluegreen "$sha" "$phase" "$deploy_id" ;;
        auto)
          if [[ -z "$(read_state active_color)" ]]; then
            cmd_bootstrap "$sha" "$deploy_id"
          else
            cmd_bluegreen "$sha" "$phase" "$deploy_id"
          fi
          ;;
        *) fail "Invalid --mode: $mode" ;;
      esac
      return 0
      ;;
    -h|--help)
      usage
      return 0
      ;;
    *)
      acquire_lock
      if [[ -z "$(read_state active_color)" ]]; then
        cmd_bootstrap "$subcmd" ""
      else
        cmd_bluegreen "$subcmd" "full" ""
      fi
      ;;
  esac
}

main "$@"
