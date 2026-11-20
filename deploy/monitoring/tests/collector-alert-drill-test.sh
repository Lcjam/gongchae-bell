#!/usr/bin/env bash
# Isolated local/CI integration test for deploy/monitoring/collector-alert-drill.sh (#15):
# - Never touches production SSH, DB, env, keys, or external Discord/API.
# - Applies REAL tracked Flyway SQL migrations (V1..V5) to isolated postgres:17 (no fake schema).
# - Verifies R1~R5 & B1~B4:
#   1) Mandatory Git SHA-256, `export KEY=true` parsing, runtime app container true/file false mismatch,
#      stale single-file bind mount (Linux tar sync), and directory bind mount compatibility.
#   2) Concurrent run #2 (different backup-dir) blocked by deploy-root lock; concurrent restore serialized.
#   3) Real firing + pending (unmodified expr & for: 10m), 1st SIGTERM + 2nd SIGTERM DURING restore
#      (signal masking), owned-only cleanup preserving unrelated `rules/drill-keep.yml`.
#   4) Independent supervisor deadline handoff on a worker stalled in foreground sleep (> deadline).
#   5) Post-RESTORED no-op preserves newer valid config; ARMED external change is preserved without
#      overwriting; DB real-schema mutation is caught; restore errors are NEVER masked as success.
set -euo pipefail

export MSYS_NO_PATHCONV=1

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
DRILL_SH="${REPO_ROOT}/deploy/monitoring/collector-alert-drill.sh"
MIGRATIONS_DIR="${REPO_ROOT}/src/main/resources/db/migration"
WORK="$(mktemp -d)"
SUFFIX="$$"
NET_NAME="gongchae-drill-test-backend-${SUFFIX}"
PG_NAME="gongchae-drill-test-pg-${SUFFIX}"
APP_NAME="gongchae-drill-test-app-${SUFFIX}"
APP_BAD_NAME="gongchae-drill-test-app-bad-${SUFFIX}"
PROM_NAME="gongchae-drill-test-prom-${SUFFIX}"
PROM_DIR_NAME="gongchae-drill-test-promdir-${SUFFIX}"
EXPORTER_NAME="gongchae-drill-test-exp-${SUFFIX}"

docker_host_path() {
  if command -v cygpath >/dev/null 2>&1; then
    cygpath -m "$1"
  else
    printf '%s' "$1"
  fi
}

cleanup() {
  docker rm -f "$EXPORTER_NAME" "$PROM_NAME" "$PROM_DIR_NAME" "$APP_NAME" "$APP_BAD_NAME" "$PG_NAME" >/dev/null 2>&1 || true
  docker network rm "$NET_NAME" >/dev/null 2>&1 || true
  rm -rf "$WORK"
}
trap cleanup EXIT

fail() { echo "DRILL_TEST_FAIL: $*" >&2; exit 1; }

mkdir -p "$WORK/deploy/monitoring/prometheus/rules" "$WORK/deploy/state" "$WORK/app" "$WORK/backup" "$WORK/backup2" "$WORK/drill-work" "$WORK/drill-work2"
cp "$REPO_ROOT/deploy/monitoring/prometheus/prometheus.yml" "$WORK/deploy/monitoring/prometheus/prometheus.yml"
cp "$REPO_ROOT/deploy/monitoring/prometheus/rules/gongchae.yml" "$WORK/deploy/monitoring/prometheus/rules/gongchae.yml"

# Pre-existing unrelated operator rule file that restore MUST preserve (never glob-delete drill*.yml!).
cat > "$WORK/deploy/monitoring/prometheus/rules/drill-keep.yml" <<'EOF'
groups:
  - name: drill-keep-operator
    rules:
      - alert: OperatorKeepExample
        expr: vector(0) == 1
        labels:
          severity: warning
          summary: unrelated operator rule
EOF
KEEP_RULE_SHA="$(sha256sum "$WORK/deploy/monitoring/prometheus/rules/drill-keep.yml" | awk '{print $1}')"

ORIG_PROM_SHA="$(sha256sum "$WORK/deploy/monitoring/prometheus/prometheus.yml" | awk '{print $1}')"
ORIG_RULES_SHA="$(sha256sum "$WORK/deploy/monitoring/prometheus/rules/gongchae.yml" | awk '{print $1}')"

cat > "$WORK/.env" <<'EOF'
COLLECTOR_ENABLED=false
ALERTS_ENABLED=false
EOF

cat > "$WORK/app/Caddyfile" <<'EOF'
:8081 {
	handle /actuator/prometheus {
		header Content-Type "text/plain; version=0.0.4; charset=utf-8"
		root * /srv
		rewrite * /metrics.prom
		file_server
	}
}
EOF
cat > "$WORK/app/metrics.prom" <<'EOF'
# TYPE collector_api_warning_limit gauge
collector_api_warning_limit 500
# TYPE collector_api_stop_limit gauge
collector_api_stop_limit 900
# TYPE collector_last_complete_epoch gauge
collector_last_complete_epoch 0
# TYPE collector_api_attempts_today gauge
collector_api_attempts_today 0
# TYPE collector_regular_incomplete_consecutive gauge
collector_regular_incomplete_consecutive 0
EOF

echo "=== 0. Starting isolated backend network, Postgres 17 (real Flyway V1..V5), stand-in app, and Prometheus ==="
docker network create --internal "$NET_NAME" >/dev/null

docker run -d --name "$PG_NAME" --network "$NET_NAME" \
  -e POSTGRES_DB=gongchae -e POSTGRES_USER=gongchae -e POSTGRES_PASSWORD=testpw \
  postgres:17 >/dev/null
until docker exec "$PG_NAME" pg_isready -U gongchae -d gongchae >/dev/null 2>&1; do sleep 1; done

# R1: Apply actual tracked Flyway migration files in exact order (no fake CREATE TABLE).
for mig in "$MIGRATIONS_DIR"/V*.sql; do
  docker exec -i "$PG_NAME" psql -U gongchae -d gongchae -v ON_ERROR_STOP=1 < "$mig" >/dev/null
done

docker exec -i "$PG_NAME" psql -U gongchae -d gongchae -v ON_ERROR_STOP=1 <<'SQL' >/dev/null
INSERT INTO institution (code, name) VALUES ('C001', '테스트공공기관');
INSERT INTO posting (
  sn, inst_code, title, bgng_date, end_date, status, ongoing_yn, from_backfill,
  content_hash, first_seen_at, last_seen_at, last_changed_at, detail_status
) VALUES
  (307032, 'C001', '2026 신입 채용', '2026-11-18', '2026-11-25', 'OPEN', 'Y', false,
   'hash-307032', '2026-11-18T00:00:00Z', '2026-11-19T00:00:00Z', NULL, 'NONE'),
  (307031, 'C001', '2026 마감 공고', '2026-11-01', '2026-11-15', 'CLOSED', 'N', true,
   'hash-307031', '2026-11-01T00:00:00Z', '2026-11-15T00:00:00Z', '2026-11-10T00:00:00Z', 'NONE');
INSERT INTO collect_run (kind, slot, started_at, finished_at, result)
VALUES ('BACKFILL', 'initial-001', '2026-11-19T00:00:00Z', '2026-11-19T00:05:00Z', 'COMPLETE');
INSERT INTO api_budget (day, count) VALUES ('2026-11-19', 37);
INSERT INTO api_attempt (day, kind, attempt_no, started_at, finished_at, outcome)
VALUES ('2026-11-19', 'BACKFILL', 1, '2026-11-19T00:00:00Z', '2026-11-19T00:00:02Z', 'SUCCESS');
SQL

# Stand-in app container explicitly carries runtime COLLECTOR_ENABLED=false / ALERTS_ENABLED=false.
docker run -d --name "$APP_NAME" --network "$NET_NAME" --network-alias app-green \
  -e COLLECTOR_ENABLED=false -e ALERTS_ENABLED=false \
  -v "$(docker_host_path "$WORK/app/Caddyfile"):/etc/caddy/Caddyfile:ro" \
  -v "$(docker_host_path "$WORK/app"):/srv:ro" \
  caddy:2-alpine caddy run --config /etc/caddy/Caddyfile >/dev/null

# Second stand-in container simulating runtime flag drift (.env false while running app is true).
docker run -d --name "$APP_BAD_NAME" --network "$NET_NAME" \
  -e COLLECTOR_ENABLED=true -e ALERTS_ENABLED=false \
  caddy:2-alpine sleep 600 >/dev/null

start_prom_single_file() {
  docker rm -f "$PROM_NAME" >/dev/null 2>&1 || true
  docker run -d --name "$PROM_NAME" --network "$NET_NAME" \
    -v "$(docker_host_path "$WORK/deploy/monitoring/prometheus/prometheus.yml"):/etc/prometheus/prometheus.yml:ro" \
    -v "$(docker_host_path "$WORK/deploy/monitoring/prometheus/rules"):/etc/prometheus/rules:ro" \
    prom/prometheus:v3.16.0 \
    --config.file=/etc/prometheus/prometheus.yml \
    --storage.tsdb.path=/prometheus >/dev/null
  for _ in $(seq 1 30); do
    docker exec "$PROM_NAME" wget -qO- http://localhost:9090/-/ready >/dev/null 2>&1 && break
    sleep 1
  done
}

wait_prom_clean_targets_and_alerts() {
  local cname="$1"
  for _ in $(seq 1 45); do
    local t a
    t="$(docker exec "$cname" wget -qO- 'http://localhost:9090/api/v1/targets?state=active' 2>/dev/null || true)"
    a="$(docker exec "$cname" wget -qO- 'http://localhost:9090/api/v1/alerts' 2>/dev/null || true)"
    if grep -q '"instance":"app-green:8081".*"health":"up"' <<<"$t" && grep -q '"alerts":\[\]' <<<"$a"; then
      return 0
    fi
    sleep 2
  done
  fail "Prometheus ${cname} did not reach healthy app-green + empty alerts"
}

start_prom_single_file
wait_prom_clean_targets_and_alerts "$PROM_NAME"

COMMON_ARGS=(
  --root-dir "$WORK"
  --env-file "$WORK/.env"
  --backup-dir "$WORK/backup"
  --work-dir "$WORK/drill-work"
  --prom-container "$PROM_NAME"
  --pg-container "$PG_NAME"
  --app-containers "$APP_NAME"
  --exporter-name "$EXPORTER_NAME"
  --expected-prom-sha256 "$ORIG_PROM_SHA"
  --expected-rules-sha256 "$ORIG_RULES_SHA"
)

echo "=== 1. Preflight fail-closed checks (R4 export/runtime, R5 required hashes, B1 stale mount) ==="
# R5: missing expected hashes rejected
if bash "$DRILL_SH" preflight --root-dir "$WORK" --env-file "$WORK/.env" --backup-dir "$WORK/backup" \
    --prom-container "$PROM_NAME" --pg-container "$PG_NAME" --app-containers "$APP_NAME" >"$WORK/p_nohash.log" 2>&1; then
  fail "preflight should fail when expected Git SHA-256 args are omitted"
fi
grep -q "EXPECTED_SHA_REQUIRED" "$WORK/p_nohash.log" || fail "expected EXPECTED_SHA_REQUIRED: $(cat "$WORK/p_nohash.log")"

# R4: `export COLLECTOR_ENABLED=true` must NOT be misparsed as false
printf 'export COLLECTOR_ENABLED=true\nexport ALERTS_ENABLED=false\n' > "$WORK/.env"
if bash "$DRILL_SH" preflight "${COMMON_ARGS[@]}" >"$WORK/p_export.log" 2>&1; then
  fail "preflight should fail when .env has export COLLECTOR_ENABLED=true"
fi
grep -q "ENV_FILE_FLAG_NOT_FALSE" "$WORK/p_export.log" || fail "expected ENV_FILE_FLAG_NOT_FALSE: $(cat "$WORK/p_export.log")"

# R4: `.env` false while running app container runtime env is true -> rejected before mutation
printf 'COLLECTOR_ENABLED=false\nALERTS_ENABLED=false\n' > "$WORK/.env"
if bash "$DRILL_SH" preflight "${COMMON_ARGS[@]}" --app-containers "${APP_NAME},${APP_BAD_NAME}" >"$WORK/p_rt.log" 2>&1; then
  fail "preflight should fail when a running app container has COLLECTOR_ENABLED=true"
fi
grep -q "RUNTIME_FLAG_NOT_FALSE" "$WORK/p_rt.log" || fail "expected RUNTIME_FLAG_NOT_FALSE: $(cat "$WORK/p_rt.log")"
docker rm -f "$APP_BAD_NAME" >/dev/null

# R5: hash mismatch rejected
if bash "$DRILL_SH" preflight "${COMMON_ARGS[@]}" --expected-prom-sha256 "0000000000000000000000000000000000000000000000000000000000000000" >"$WORK/p_badhash.log" 2>&1; then
  fail "preflight should fail on git SHA mismatch"
fi
grep -q "EXPECTED_GIT_SHA_MISMATCH" "$WORK/p_badhash.log" || fail "unexpected error: $(cat "$WORK/p_badhash.log")"

# B1: Linux stale single-file bind mount caught by both preflight and run
if [[ "$(uname -s)" == "Linux" ]]; then
  (cd "$WORK" && tar -cf - deploy/monitoring/prometheus/prometheus.yml | tar -xf - -C "$WORK")
  if bash "$DRILL_SH" preflight "${COMMON_ARGS[@]}" >"$WORK/p_stale.log" 2>&1; then
    fail "preflight should fail on stale single-file bind mount after tar sync"
  fi
  grep -q "MOUNT_STALE_SINGLE_FILE" "$WORK/p_stale.log" || fail "expected MOUNT_STALE_SINGLE_FILE: $(cat "$WORK/p_stale.log")"
  if bash "$DRILL_SH" run "${COMMON_ARGS[@]}" >"$WORK/p_stale_run.log" 2>&1; then
    fail "run without --allow-recreate-prometheus should fail on stale mount"
  fi
  grep -q "MOUNT_STALE_SINGLE_FILE" "$WORK/p_stale_run.log" || fail "expected MOUNT_STALE_SINGLE_FILE in run"
  echo "Stale single-file bind mount caught by preflight and run (inode mismatch)"
  start_prom_single_file
  wait_prom_clean_targets_and_alerts "$PROM_NAME"
fi

bash "$DRILL_SH" preflight "${COMMON_ARGS[@]}" >"$WORK/p_ok.log" 2>&1 || fail "healthy preflight failed: $(cat "$WORK/p_ok.log")"
grep -q "MOUNT_OK:single-file" "$WORK/p_ok.log" || fail "expected MOUNT_OK:single-file"
echo "Healthy preflight passed on real Flyway V1..V5 schema"

echo "=== 2. Directory bind mount compatibility check ==="
docker run -d --name "$PROM_DIR_NAME" --network "$NET_NAME" \
  -v "$(docker_host_path "$WORK/deploy/monitoring/prometheus"):/etc/prometheus:ro" \
  prom/prometheus:v3.16.0 \
  --config.file=/etc/prometheus/prometheus.yml \
  --storage.tsdb.path=/prometheus >/dev/null
for _ in $(seq 1 30); do
  docker exec "$PROM_DIR_NAME" wget -qO- http://localhost:9090/-/ready >/dev/null 2>&1 && break
  sleep 1
done
wait_prom_clean_targets_and_alerts "$PROM_DIR_NAME"
bash "$DRILL_SH" preflight \
  --root-dir "$WORK" --env-file "$WORK/.env" --backup-dir "$WORK/backup" --work-dir "$WORK/drill-work" \
  --prom-container "$PROM_DIR_NAME" --pg-container "$PG_NAME" --app-containers "$APP_NAME" \
  --exporter-name "$EXPORTER_NAME" \
  --expected-prom-sha256 "$ORIG_PROM_SHA" --expected-rules-sha256 "$ORIG_RULES_SHA" \
  >"$WORK/p_dir.log" 2>&1 || fail "directory mount preflight failed: $(cat "$WORK/p_dir.log")"
grep -q "MOUNT_OK:directory" "$WORK/p_dir.log" || fail "expected MOUNT_OK:directory"
docker rm -f "$PROM_DIR_NAME" >/dev/null
echo "Directory bind mount preflight passed"

echo "=== 3. Real firing + concurrency lock + serialized restore + 2nd SIGTERM during restore + drill-keep.yml preservation ==="
DRILL_TEST_RESTORE_HOLD_SEC=5 bash "$DRILL_SH" run "${COMMON_ARGS[@]}" >"$WORK/drill.log" 2>&1 &
DRILL_PID=$!

for _ in $(seq 1 90); do
  if ! kill -0 "$DRILL_PID" 2>/dev/null; then
    fail "drill exited prematurely before firing: $(cat "$WORK/drill.log")"
  fi
  if grep -q "DRILL_COLLECTION_FAILING_PENDING" "$WORK/drill.log"; then
    break
  fi
  sleep 2
done
grep -q "DRILL_BUDGET_WARNING_FIRING" "$WORK/drill.log" || fail "budget warning did not fire"
grep -q "DRILL_COLLECTION_FAILING_PENDING" "$WORK/drill.log" || fail "collection failing did not enter pending"

# R3: Concurrent run #2 with a DIFFERENT --backup-dir must be rejected immediately by deploy-root lock
if bash "$DRILL_SH" run "${COMMON_ARGS[@]}" --backup-dir "$WORK/backup2" --work-dir "$WORK/drill-work2" >"$WORK/run2.log" 2>&1; then
  fail "concurrent run #2 with different backup-dir should have been blocked"
fi
grep -q "DRILL_LOCK_BUSY" "$WORK/run2.log" || fail "expected DRILL_LOCK_BUSY on run #2: $(cat "$WORK/run2.log")"

# R3: Concurrent restore with short wait must serialize & fail busy without racing
if bash "$DRILL_SH" restore "${COMMON_ARGS[@]}" --lock-wait-sec 2 >"$WORK/rest_busy.log" 2>&1; then
  fail "concurrent restore with 2s wait should time out on busy lock"
fi
grep -q "DRILL_LOCK_BUSY" "$WORK/rest_busy.log" || fail "expected DRILL_LOCK_BUSY on concurrent restore"

# Start a second restore that waits for run #1 to finish (tests clean serialization -> already-RESTORED no-op)
bash "$DRILL_SH" restore "${COMMON_ARGS[@]}" --lock-wait-sec 60 >"$WORK/rest_ser.log" 2>&1 &
REST_SER_PID=$!

# R2: Send 1st SIGTERM to supervisor, wait until restore begins, then send 2nd SIGTERM DURING restore!
kill -TERM "$DRILL_PID"
for _ in $(seq 1 30); do
  grep -q "DRILL_RESTORE_HOLD_STARTED" "$WORK/drill.log" && break
  sleep 1
done
grep -q "DRILL_RESTORE_HOLD_STARTED" "$WORK/drill.log" || fail "restore did not enter hold window: $(cat "$WORK/drill.log")"
kill -TERM "$DRILL_PID" 2>/dev/null || true

set +e
wait "$DRILL_PID"
DRILL_RC=$?
wait "$REST_SER_PID"
REST_SER_RC=$?
set -e

[[ "$DRILL_RC" -ne 0 ]] || fail "SIGTERM-interrupted drill should exit non-zero"
grep -q "DRILL_RESTORE_VERIFIED_OK" "$WORK/drill.log" || fail "restore was interrupted by 2nd SIGTERM: $(cat "$WORK/drill.log")"
[[ "$REST_SER_RC" -eq 0 ]] || fail "serialized restore after run should exit 0: $(cat "$WORK/rest_ser.log")"
grep -q "DRILL_RESTORE_ALREADY_RESTORED_NOOP" "$WORK/rest_ser.log" || fail "expected ALREADY_RESTORED_NOOP: $(cat "$WORK/rest_ser.log")"
[[ "$(sha256sum "$WORK/deploy/monitoring/prometheus/prometheus.yml" | awk '{print $1}')" == "$ORIG_PROM_SHA" ]] || fail "prometheus.yml hash mismatch"
[[ "$(sha256sum "$WORK/deploy/monitoring/prometheus/rules/gongchae.yml" | awk '{print $1}')" == "$ORIG_RULES_SHA" ]] || fail "gongchae.yml hash mismatch"
# R5: Unrelated operator rule file drill-keep.yml MUST still exist unchanged!
[[ -f "$WORK/deploy/monitoring/prometheus/rules/drill-keep.yml" ]] || fail "drill-keep.yml was deleted!"
[[ "$(sha256sum "$WORK/deploy/monitoring/prometheus/rules/drill-keep.yml" | awk '{print $1}')" == "$KEEP_RULE_SHA" ]] || fail "drill-keep.yml modified!"
echo "Concurrency, serialized restore, repeated SIGTERM immunity, and drill-keep.yml preservation verified"

echo "=== 4. R2: Independent supervisor deadline handoff when worker stalls in foreground command ==="
t_stall_start="$(date +%s)"
set +e
DRILL_MAX_DURATION_SEC=40 DRILL_TEST_WORKER_STALL_SEC=120 bash "$DRILL_SH" run "${COMMON_ARGS[@]}" >"$WORK/stall.log" 2>&1
STALL_RC=$?
set -e
t_stall_elapsed=$(( $(date +%s) - t_stall_start ))
[[ "$STALL_RC" -eq 124 ]] || fail "expected exit 124 on supervisor deadline, got ${STALL_RC}: $(cat "$WORK/stall.log")"
grep -q "DRILL_SUPERVISOR_DEADLINE_REACHED" "$WORK/stall.log" || fail "missing DRILL_SUPERVISOR_DEADLINE_REACHED: $(cat "$WORK/stall.log")"
grep -q "DRILL_RESTORE_VERIFIED_OK" "$WORK/stall.log" || fail "supervisor failed to restore after stalled worker: $(cat "$WORK/stall.log")"
[[ "$t_stall_elapsed" -lt 110 ]] || fail "supervisor waited for stalled 120s worker instead of cutting off independently (${t_stall_elapsed}s)"
echo "Independent supervisor deadline recovery verified in ${t_stall_elapsed}s (< 120s worker stall)"

echo "=== 5. R3/R1: Already-RESTORED no-op, ARMED external config preservation, DB drift detection, restore error non-masking ==="
# 5a) Already RESTORED must NOT overwrite a newer legitimate config update
cp "$WORK/deploy/monitoring/prometheus/prometheus.yml" "$WORK/prom.orig.tmp"
printf '\n# newer legitimate deploy config\n' >> "$WORK/deploy/monitoring/prometheus/prometheus.yml"
NEW_PROM_SHA="$(sha256sum "$WORK/deploy/monitoring/prometheus/prometheus.yml" | awk '{print $1}')"
bash "$DRILL_SH" restore "${COMMON_ARGS[@]}" >"$WORK/rest_noop.log" 2>&1 || fail "RESTORED no-op failed: $(cat "$WORK/rest_noop.log")"
grep -q "DRILL_RESTORE_ALREADY_RESTORED_NOOP" "$WORK/rest_noop.log" || fail "expected ALREADY_RESTORED_NOOP"
[[ "$(sha256sum "$WORK/deploy/monitoring/prometheus/prometheus.yml" | awk '{print $1}')" == "$NEW_PROM_SHA" ]] || fail "old restore overwrote newer valid config!"

# 5b) External config change while ARMED must NOT be overwritten; restore fails closed
sed -i.bak 's/^STATE=.*/STATE=ARMED/' "$WORK/backup/meta.env" && rm -f "$WORK/backup/meta.env.bak"
echo "ARMED" > "$WORK/backup/state"
if bash "$DRILL_SH" restore "${COMMON_ARGS[@]}" >"$WORK/rest_ext.log" 2>&1; then
  fail "restore should fail when ARMED target was externally modified"
fi
grep -q "UNEXPECTED_EXTERNAL_CHANGE" "$WORK/rest_ext.log" || fail "expected UNEXPECTED_EXTERNAL_CHANGE: $(cat "$WORK/rest_ext.log")"
[[ "$(sha256sum "$WORK/deploy/monitoring/prometheus/prometheus.yml" | awk '{print $1}')" == "$NEW_PROM_SHA" ]] || fail "restore clobbered externally changed prometheus.yml!"
cat "$WORK/prom.orig.tmp" > "$WORK/deploy/monitoring/prometheus/prometheus.yml"

# 5c) Real Flyway DB mutation caught by restore_and_verify (and never masked as success)
docker exec -i "$PG_NAME" psql -U gongchae -d gongchae -v ON_ERROR_STOP=1 \
  -c "UPDATE posting SET last_seen_at = '2026-11-20T00:00:00Z' WHERE sn = 307032;" >/dev/null
if bash "$DRILL_SH" restore "${COMMON_ARGS[@]}" >"$WORK/rest_db.log" 2>&1; then
  fail "restore should fail when DB posting.last_seen_at changed"
fi
grep -q "DB baseline changed during drill" "$WORK/rest_db.log" || fail "expected DB baseline mismatch: $(cat "$WORK/rest_db.log")"
grep -q "DRILL_RESTORE_FAILED" "$WORK/rest_db.log" || fail "expected DRILL_RESTORE_FAILED"
[[ "$(cat "$WORK/backup/state")" == "RESTORE_FAILED" ]] || fail "expected RESTORE_FAILED state"
if bash "$DRILL_SH" preflight "${COMMON_ARGS[@]}" >"$WORK/p_blocked.log" 2>&1; then
  fail "preflight must stay blocked while state is RESTORE_FAILED"
fi
grep -q "PREVIOUS_DRILL_NOT_RESTORED" "$WORK/p_blocked.log" || fail "expected PREVIOUS_DRILL_NOT_RESTORED"

# Revert DB mutation and confirm idempotent restore succeeds cleanly
docker exec -i "$PG_NAME" psql -U gongchae -d gongchae -v ON_ERROR_STOP=1 \
  -c "UPDATE posting SET last_seen_at = '2026-11-19T00:00:00Z' WHERE sn = 307032;" >/dev/null
bash "$DRILL_SH" restore "${COMMON_ARGS[@]}" >"$WORK/rest_final.log" 2>&1 || fail "idempotent restore retry failed: $(cat "$WORK/rest_final.log")"
grep -q "DRILL_RESTORE_VERIFIED_OK" "$WORK/rest_final.log" || fail "expected DRILL_RESTORE_VERIFIED_OK"

echo "COLLECTOR_ALERT_DRILL_TEST_PASSED"
