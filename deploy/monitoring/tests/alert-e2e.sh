#!/usr/bin/env bash
# Alert forwarding end to end on an isolated stack (docs/ops/monitoring.md, "대역 시험").
# The real app jar on a fresh Postgres, Prometheus and Grafana with this repo's config and provisioning, and the ops
# Discord webhook replaced by a local stub: nothing leaves the Docker networks, no real account is touched.
# Needs docker compose, curl and build/libs/gongchae-bell.jar (./gradlew bootJar). Uses host port 127.0.0.1:3000.
set -euo pipefail
cd "$(dirname "$0")/../../.."

export GRAFANA_ADMIN_PASSWORD="e2e-$RANDOM$RANDOM"
unset OPS_DISCORD_WEBHOOK_URL
dc() { docker compose --project-directory . -f deploy/monitoring/tests/compose.e2e.yml -f compose.monitoring.yml "$@"; }
sql() { dc exec -T postgres psql -U e2e -d gongchae -v ON_ERROR_STOP=1 -qtAc "$1"; }
stub() { dc exec -T discord-stub python /stub/stub.py "$@"; }
grafana() { curl -fsS -u "admin:$GRAFANA_ADMIN_PASSWORD" "http://127.0.0.1:3000$1"; }
wait_until() { # description, timeout in seconds, command...
  local what=$1 limit=$2 from=$SECONDS
  shift 2
  until "$@" >/dev/null 2>&1; do
    if ((SECONDS - from >= limit)); then echo "TIMEOUT after ${limit}s: $what" >&2; stub messages || true; exit 1; fi
    sleep 5
  done
  echo "ok after $((SECONDS - from))s: $what"
}
trap 'dc down -v --remove-orphans >/dev/null 2>&1 || true' EXIT

# Default: no webhook configured. Grafana must still start and provision everything, pointed at the off address.
dc up -d
wait_until "Grafana starts with alerts off (no webhook set)" 120 grafana /api/v1/provisioning/alert-rules/gongchae-prometheus-forward
grafana '/api/v1/provisioning/contact-points/export?decrypt=true&format=json' | grep -q '127.0.0.1:9/alerts-off'

# Now the stub as the ops Discord webhook. Changing the env recreates only Grafana.
export OPS_DISCORD_WEBHOOK_URL=http://discord-stub:8080/webhook
dc up -d grafana
wait_until "Prometheus scrapes app-blue:8081 over the backend network" 300 stub query 'up{instance="app-blue:8081"} == 1'
wait_until "Grafana provisioned the forwarding rule" 120 grafana /api/v1/provisioning/alert-rules/gongchae-prometheus-forward
grafana '/api/v1/provisioning/contact-points/export?decrypt=true&format=json' | grep -q 'discord-stub:8080'
grafana /api/v1/provisioning/contact-points | grep -q '"name":"ops-discord"'
grafana /api/dashboards/uid/gongchae-ops >/dev/null
stub queries
if stub has GongchaeApiBudget; then echo "budget alert before the trigger" >&2; exit 1; fi

# Over the stop limit for today (KST), exactly what ApiBudget reads: warning and stop fire with no `for`.
sql "INSERT INTO api_budget(day, count) VALUES ((now() AT TIME ZONE 'Asia/Seoul')::date, 950)"
wait_until "firing GongchaeApiBudgetStopped reached the webhook stub" 300 stub has firing GongchaeApiBudgetStopped
sql "DELETE FROM api_budget"
wait_until "its resolve reached the webhook stub" 420 stub has resolved GongchaeApiBudgetStopped
stub messages
