#!/usr/bin/env bash
# Isolated local/CI integration test for deploy/monitoring/collector-alert-drill.sh (#15):
# - Never touches production SSH, DB, env, keys, or external Discord/API.
# - Uses real postgres:17, caddy:2-alpine (stand-in app-green), and prom/prometheus:v3.16.0
#   with the real unmodified deploy/monitoring/prometheus/{prometheus.yml,rules/gongchae.yml}.
# - Verifies:
#   1) Preflight fail-closed (COLLECTOR_ENABLED=true, ALERTS_ENABLED=true, git SHA mismatch,
#      stale single-file bind mount inode after tar sync).
#   2) Opt-in Prometheus recreate fixes stale single-file mount; directory bind mount also passes.
#   3) Real drill run: job_name: gongchae-drill added without touching gongchae-app, [훈련] added
#      only to the 2 target summaries with expr & for: 10m preserved, GongchaeApiBudgetWarning fires,
#      GongchaeRegularCollectionFailing enters pending, no Stopped/Stale/Down alerts.
#   4) Mid-flight SIGTERM triggers automatic EXIT trap restore -> exact host+container SHA-256,
#      exporter removed, gongchae-drill removed from config/targets, alerts cleared, DB unchanged.
#   5) Restore failure is NEVER masked as success (exits non-zero, writes RESTORE_FAILED, blocks next preflight).
set -euo pipefail

export MSYS_NO_PATHCONV=1

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
DRILL_SH="${REPO_ROOT}/deploy/monitoring/collector-alert-drill.sh"
WORK="$(mktemp -d)"
SUFFIX="$$"
NET_NAME="gongchae-drill-test-backend-${SUFFIX}"
PG_NAME="gongchae-drill-test-pg-${SUFFIX}"
APP_NAME="gongchae-drill-test-app-${SUFFIX}"
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
  docker rm -f "$EXPORTER_NAME" "$PROM_NAME" "$PROM_DIR_NAME" "$APP_NAME" "$PG_NAME" >/dev/null 2>&1 || true
  docker network rm "$NET_NAME" >/dev/null 2>&1 || true
  rm -rf "$WORK"
}
trap cleanup EXIT

fail() { echo "DRILL_TEST_FAIL: $*" >&2; exit 1; }

mkdir -p "$WORK/deploy/monitoring/prometheus/rules" "$WORK/app" "$WORK/backup" "$WORK/drill-work"
cp "$REPO_ROOT/deploy/monitoring/prometheus/prometheus.yml" "$WORK/deploy/monitoring/prometheus/prometheus.yml"
cp "$REPO_ROOT/deploy/monitoring/prometheus/rules/gongchae.yml" "$WORK/deploy/monitoring/prometheus/rules/gongchae.yml"

# Use 5s scrape/eval ONLY in test stack's copy? NO — keep exact unmodified prometheus.yml & gongchae.yml!
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

echo "=== 0. Starting isolated backend network, Postgres 17, stand-in app-green, and Prometheus ==="
docker network create --internal "$NET_NAME" >/dev/null

docker run -d --name "$PG_NAME" --network "$NET_NAME" \
  -e POSTGRES_DB=gongchae -e POSTGRES_USER=gongchae -e POSTGRES_PASSWORD=testpw \
  postgres:17 >/dev/null
until docker exec "$PG_NAME" pg_isready -U gongchae -d gongchae >/dev/null 2>&1; do sleep 1; done

docker exec -i "$PG_NAME" psql -U gongchae -d gongchae -v ON_ERROR_STOP=1 <<'SQL' >/dev/null
CREATE TABLE posting (sn bigint PRIMARY KEY, status text NOT NULL, updated_at timestamptz);
CREATE TABLE collect_run (id bigint PRIMARY KEY);
CREATE TABLE api_attempt (id bigint PRIMARY KEY);
CREATE TABLE api_budget (day date PRIMARY KEY, count integer NOT NULL);
INSERT INTO posting VALUES (307032, 'OPEN', '2026-11-19T00:00:00Z'), (307031, 'CLOSED', '2026-11-18T00:00:00Z');
INSERT INTO collect_run VALUES (1);
INSERT INTO api_attempt VALUES (1), (2), (37);
INSERT INTO api_budget VALUES ('2026-11-19', 37);
SQL

docker run -d --name "$APP_NAME" --network "$NET_NAME" --network-alias app-green \
  -v "$(docker_host_path "$WORK/app/Caddyfile"):/etc/caddy/Caddyfile:ro" \
  -v "$(docker_host_path "$WORK/app"):/srv:ro" \
  caddy:2-alpine caddy run --config /etc/caddy/Caddyfile >/dev/null

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
  --exporter-name "$EXPORTER_NAME"
  --expected-prom-sha256 "$ORIG_PROM_SHA"
  --expected-rules-sha256 "$ORIG_RULES_SHA"
)

echo "=== 1. Preflight fail-closed checks ==="
printf 'COLLECTOR_ENABLED=true\nALERTS_ENABLED=false\n' > "$WORK/.env"
if bash "$DRILL_SH" preflight "${COMMON_ARGS[@]}" >"$WORK/p1.log" 2>&1; then
  fail "preflight should fail when COLLECTOR_ENABLED=true"
fi
grep -q "COLLECTOR_ENABLED must be false" "$WORK/p1.log" || fail "unexpected error: $(cat "$WORK/p1.log")"

printf 'COLLECTOR_ENABLED=false\nALERTS_ENABLED=false\n' > "$WORK/.env"
if bash "$DRILL_SH" preflight "${COMMON_ARGS[@]}" --expected-prom-sha256 "0000000000000000000000000000000000000000000000000000000000000000" >"$WORK/p2.log" 2>&1; then
  fail "preflight should fail on git SHA mismatch"
fi
grep -q "EXPECTED_GIT_SHA_MISMATCH" "$WORK/p2.log" || fail "unexpected error: $(cat "$WORK/p2.log")"

# Stale single-file bind mount check (on Linux, GNU tar replacement changes host inode while content stays identical!)
if [[ "$(uname -s)" == "Linux" ]]; then
  (cd "$WORK" && tar -cf - deploy/monitoring/prometheus/prometheus.yml | tar -xf - -C "$WORK")
  if bash "$DRILL_SH" preflight "${COMMON_ARGS[@]}" >"$WORK/p3.log" 2>&1; then
    fail "preflight should fail on stale single-file bind mount after tar sync"
  fi
  grep -q "MOUNT_STALE_SINGLE_FILE" "$WORK/p3.log" || fail "expected MOUNT_STALE_SINGLE_FILE, got: $(cat "$WORK/p3.log")"
  if bash "$DRILL_SH" run "${COMMON_ARGS[@]}" >"$WORK/p3_run.log" 2>&1; then
    fail "run without --allow-recreate-prometheus should fail on stale single-file bind mount"
  fi
  grep -q "MOUNT_STALE_SINGLE_FILE" "$WORK/p3_run.log" || fail "expected MOUNT_STALE_SINGLE_FILE in run: $(cat "$WORK/p3_run.log")"
  echo "Stale single-file bind mount caught by preflight and run (inode mismatch)"
  start_prom_single_file
  wait_prom_clean_targets_and_alerts "$PROM_NAME"
fi

bash "$DRILL_SH" preflight "${COMMON_ARGS[@]}" >"$WORK/p_ok.log" 2>&1 || fail "healthy preflight failed: $(cat "$WORK/p_ok.log")"
grep -q "MOUNT_OK:single-file" "$WORK/p_ok.log" || fail "expected MOUNT_OK:single-file: $(cat "$WORK/p_ok.log")"
echo "Healthy single-file mount preflight passed"

echo "=== 2. Directory bind mount compatibility check (Claude fix/#14-monitoring-bind pattern) ==="
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
  --prom-container "$PROM_DIR_NAME" --pg-container "$PG_NAME" --exporter-name "$EXPORTER_NAME" \
  --expected-prom-sha256 "$ORIG_PROM_SHA" --expected-rules-sha256 "$ORIG_RULES_SHA" \
  >"$WORK/p_dir.log" 2>&1 || fail "directory mount preflight failed: $(cat "$WORK/p_dir.log")"
grep -q "MOUNT_OK:directory" "$WORK/p_dir.log" || fail "expected MOUNT_OK:directory: $(cat "$WORK/p_dir.log")"
docker rm -f "$PROM_DIR_NAME" >/dev/null
echo "Directory bind mount preflight passed"

echo "=== 3. Real drill firing + mid-flight SIGTERM auto-restore (unmodified rules & for: 10m) ==="
bash "$DRILL_SH" run "${COMMON_ARGS[@]}" >"$WORK/drill.log" 2>&1 &
DRILL_PID=$!

# Wait until GongchaeApiBudgetWarning fires and GongchaeRegularCollectionFailing enters pending
for _ in $(seq 1 90); do
  if ! kill -0 "$DRILL_PID" 2>/dev/null; then
    fail "drill exited prematurely before firing: $(cat "$WORK/drill.log")"
  fi
  if grep -q "DRILL_COLLECTION_FAILING_PENDING" "$WORK/drill.log"; then
    break
  fi
  sleep 2
done
grep -q "DRILL_BUDGET_WARNING_FIRING" "$WORK/drill.log" || fail "budget warning did not fire: $(cat "$WORK/drill.log")"
grep -q "DRILL_COLLECTION_FAILING_PENDING" "$WORK/drill.log" || fail "collection failing did not enter pending: $(cat "$WORK/drill.log")"

# Verify live drill state: gongchae-drill separated, gongchae-app untouched, for: 10m untouched, [훈련] on 2 summaries only
grep -q 'job_name: gongchae-drill' "$WORK/deploy/monitoring/prometheus/prometheus.yml" || fail "gongchae-drill missing"
grep -q 'targets: \["app-blue:8081", "app-green:8081"\]' "$WORK/deploy/monitoring/prometheus/prometheus.yml" || fail "gongchae-app targets modified"
grep -q 'for: 10m' "$WORK/deploy/monitoring/prometheus/rules/gongchae.yml" || fail "for: 10m missing"
[[ "$(grep -c '\[훈련\]' "$WORK/deploy/monitoring/prometheus/rules/gongchae.yml")" -eq 2 ]] || fail "expected exactly 2 [훈련] markers"

# Simulate SSH/process termination mid-drill via SIGTERM
kill -TERM "$DRILL_PID"
set +e
wait "$DRILL_PID"
DRILL_RC=$?
set -e
[[ "$DRILL_RC" -ne 0 ]] || fail "SIGTERM-interrupted drill should exit non-zero"
grep -q "DRILL_RESTORE_VERIFIED_OK" "$WORK/drill.log" || fail "auto-restore failed on SIGTERM: $(cat "$WORK/drill.log")"
[[ "$(sha256sum "$WORK/deploy/monitoring/prometheus/prometheus.yml" | awk '{print $1}')" == "$ORIG_PROM_SHA" ]] || fail "prometheus.yml hash mismatch after SIGTERM restore"
[[ "$(sha256sum "$WORK/deploy/monitoring/prometheus/rules/gongchae.yml" | awk '{print $1}')" == "$ORIG_RULES_SHA" ]] || fail "gongchae.yml hash mismatch after SIGTERM restore"
[[ "$(cat "$WORK/backup/state")" == "RESTORED" ]] || fail "backup state not RESTORED"
if docker ps -a --format '{{.Names}}' | grep -Fxq "$EXPORTER_NAME"; then
  fail "exporter container leaked after SIGTERM restore"
fi
echo "Mid-flight SIGTERM auto-restore verified completely"

echo "=== 4. Restore error must NEVER be masked as success ==="
echo "ARMED" > "$WORK/backup/state"
if bash "$DRILL_SH" restore "${COMMON_ARGS[@]}" --expected-rules-sha256 "1111111111111111111111111111111111111111111111111111111111111111" >"$WORK/restore_fail.log" 2>&1; then
  fail "restore should exit non-zero on SHA mismatch"
fi
grep -q "DRILL_RESTORE_FAILED" "$WORK/restore_fail.log" || fail "expected DRILL_RESTORE_FAILED: $(cat "$WORK/restore_fail.log")"
[[ "$(cat "$WORK/backup/state")" == "RESTORE_FAILED" ]] || fail "expected RESTORE_FAILED state"
if bash "$DRILL_SH" preflight "${COMMON_ARGS[@]}" >"$WORK/p_blocked.log" 2>&1; then
  fail "preflight should stay blocked while state is RESTORE_FAILED"
fi
grep -q "PREVIOUS_DRILL_NOT_RESTORED" "$WORK/p_blocked.log" || fail "expected PREVIOUS_DRILL_NOT_RESTORED"

# Idempotent retry of restore with correct SHA recovers cleanly
bash "$DRILL_SH" restore "${COMMON_ARGS[@]}" >"$WORK/restore_ok.log" 2>&1 || fail "idempotent restore retry failed: $(cat "$WORK/restore_ok.log")"
grep -q "DRILL_RESTORE_VERIFIED_OK" "$WORK/restore_ok.log" || fail "expected DRILL_RESTORE_VERIFIED_OK"

echo "COLLECTOR_ALERT_DRILL_TEST_PASSED"
