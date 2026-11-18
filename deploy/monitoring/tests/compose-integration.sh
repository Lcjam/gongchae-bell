#!/usr/bin/env bash
# #15 overlay on the real #14 files and deploy.sh, on real Docker Compose (no mocks, synthetic values only).
#  1. Raw compose with the server .env convention (APP_*_TAG blank, tags live in deploy/state) cannot interpolate:
#     the reason monitoring commands go through `deploy.sh monitoring`.
#  2. `deploy.sh monitoring config` merges compose.prod.yml + compose.monitoring.yml: the heartbeat URL reaches both
#     app colors, Grafana publishes on the host loopback only, Prometheus publishes nothing.
#  3. Prometheus and Grafana keep running, same containers, while deploy.sh runs its own app compose calls on the same
#     project (bootstrap: up app-blue, readiness fails on a stand-in image, stop and rm app-blue). The app container
#     gets the heartbeat URL from the real files, and a Grafana restart through deploy.sh works.
# Needs docker with the compose plugin, flock, python3. Leaves nothing behind (own project name, down -v).
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
WORK="${COMPOSE_IT_DIR:-$(mktemp -d)}"
export COMPOSE_PROJECT_NAME="gongchae-monitoring-it"
export DEPLOY_ROOT_DIR="$WORK" PULL_POLICY=never READINESS_TIMEOUT=4 READINESS_INTERVAL=1
HEARTBEAT="http://heartbeat.invalid/ping/synthetic-check"
deploy_sh() { bash "$WORK/deploy/deploy.sh" "$@"; }  # its shebang; no exec bit needed on the copy

fail() { echo "COMPOSE_IT_FAIL $*" >&2; exit 1; }
cleanup() {
  deploy_sh monitoring --profile blue --profile green down -v --remove-orphans >/dev/null 2>&1 || true
  docker image rm gongchae-monitoring-it-standin:sha-it gongchae-monitoring-it-standin:unset >/dev/null 2>&1 || true
  [[ -n "${COMPOSE_IT_DIR:-}" ]] || rm -rf "$WORK"
}
trap cleanup EXIT

mkdir -p "$WORK/erasure"
cp "$REPO_ROOT/compose.prod.yml" "$REPO_ROOT/compose.monitoring.yml" "$WORK/"
cp -r "$REPO_ROOT/deploy" "$WORK/"
rm -rf "$WORK/deploy/state"
cat >"$WORK/.env" <<EOF
APP_IMAGE=gongchae-monitoring-it-standin
APP_BLUE_TAG=
APP_GREEN_TAG=
DB_USER=synthetic
DB_PASSWORD=synthetic
GRAFANA_ADMIN_PASSWORD=synthetic
HEARTBEAT_COLLECTOR_URL=$HEARTBEAT
ERASURE_HOST_DIR=$WORK/erasure
EOF
docker pull -q alpine:3.20 >/dev/null
docker tag alpine:3.20 gongchae-monitoring-it-standin:sha-it
docker tag alpine:3.20 gongchae-monitoring-it-standin:unset  # deploy.sh's placeholder tag before any deploy

echo "=== 1. raw compose with blank tags fails to interpolate"
if (cd "$WORK" && env -u APP_BLUE_TAG -u APP_GREEN_TAG docker compose -f compose.prod.yml -f compose.monitoring.yml \
    --env-file .env config -q) 2>"$WORK/raw.err"; then
  fail "raw compose unexpectedly interpolated blank APP_*_TAG"
fi
# Either required tag may be reported first (Compose does not fix the order).
grep -qE "APP_(BLUE|GREEN)_TAG" "$WORK/raw.err" || fail "raw compose failed for another reason: $(cat "$WORK/raw.err")"

echo "=== 2. deploy.sh monitoring config merges both files"
deploy_sh monitoring --profile blue --profile green config --format json >"$WORK/merged.json"
HEARTBEAT="$HEARTBEAT" python3 - "$WORK/merged.json" <<'PY'
import json, os, sys
s = json.load(open(sys.argv[1]))["services"]
hb = os.environ["HEARTBEAT"]
for color in ("app-blue", "app-green"):
    assert s[color]["environment"].get("HEARTBEAT_COLLECTOR_URL") == hb, color
    assert "ports" not in s[color], color
assert "ports" not in s["prometheus"], "prometheus publishes nothing"
assert set(s["prometheus"]["networks"]) == {"backend", "monitoring"}, s["prometheus"]["networks"]
assert set(s["grafana"]["networks"]) == {"monitoring"}, s["grafana"]["networks"]
ports = s["grafana"]["ports"]
assert ports and all(p.get("host_ip") == "127.0.0.1" for p in ports), ports
published = sorted(name for name, svc in s.items() if svc.get("ports") and name != "grafana")
assert published == ["caddy"], published
print("merged config ok")
PY

running_ids() { deploy_sh monitoring ps -q --status running prometheus grafana | sort; }

echo "=== 3. monitoring keeps running through deploy.sh's own app compose calls"
deploy_sh monitoring up -d prometheus grafana
for _ in $(seq 1 30); do [[ "$(running_ids | wc -l)" -eq 2 ]] && break; sleep 2; done
before="$(running_ids)"
[[ "$(wc -l <<<"$before")" -eq 2 ]] || fail "prometheus and grafana did not both start"

env_out="$(deploy_sh monitoring --profile blue run --rm --no-deps app-blue env)"
grep -qx "HEARTBEAT_COLLECTOR_URL=$HEARTBEAT" <<<"$env_out" || fail "app container did not get HEARTBEAT_COLLECTOR_URL"

if deploy_sh bootstrap sha-it >"$WORK/bootstrap.log" 2>&1; then
  fail "bootstrap with a stand-in image should fail its readiness check"
fi
grep -q "Checking internal readiness for app-blue" "$WORK/bootstrap.log" || fail "deploy.sh did not start app-blue: $(cat "$WORK/bootstrap.log")"
grep -q "BOOTSTRAP_APP_FAIL" "$WORK/bootstrap.log" || fail "unexpected bootstrap failure: $(cat "$WORK/bootstrap.log")"
[[ -z "$(docker ps -aq --filter "label=com.docker.compose.project=$COMPOSE_PROJECT_NAME" --filter "label=com.docker.compose.service=app-blue")" ]] \
  || fail "deploy.sh left app-blue behind"
[[ "$(running_ids)" == "$before" ]] || fail "monitoring containers were stopped or recreated by deploy.sh"

deploy_sh monitoring restart grafana
for _ in $(seq 1 30); do [[ "$(running_ids | wc -l)" -eq 2 ]] && break; sleep 2; done
[[ "$(running_ids)" == "$before" ]] || fail "grafana restart through deploy.sh changed the containers"

echo "COMPOSE_INTEGRATION_TEST_PASSED"
