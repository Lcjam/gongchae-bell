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
# At the same time, seed 4 FAILED M2 notifications (< 5 threshold) to verify M2 stays quiet below 5.
sql "INSERT INTO api_budget(day, count) VALUES ((now() AT TIME ZONE 'Asia/Seoul')::date, 950)"
sql "INSERT INTO subscription(id, channel, status, email, dest_hmac, conditions, notify_new, notify_deadline, consent_version, consented_at, age_confirmed, confirmed_at, created_at, updated_at)
     VALUES ('11111111-1111-4111-8111-111111111111', 'EMAIL', 'ACTIVE', 'e2e-only@example.invalid', E'\\\\x01', '{}'::jsonb, true, false, 'e2e', now(), true, now(), now(), now())"
sql "INSERT INTO notification(subscription_id, kind, batch_key, status, created_at, finished_at, idempotency_key)
     SELECT '11111111-1111-4111-8111-111111111111', 'EMAIL_DIGEST', 'e2e-fail-' || i, 'FAILED', now() - interval '1 minute', now(),
            ('00000000-0000-4000-8000-00000000000' || i)::uuid
     FROM generate_series(1, 4) AS i"
wait_until "firing GongchaeApiBudgetStopped reached the webhook stub" 300 stub has firing GongchaeApiBudgetStopped
wait_until "M2 failed_last1h scraped at 4 (< 5 threshold)" 120 stub query 'sum(max by (kind) (alerts_notification_failed_last1h{job="gongchae-app"})) == 4'
if stub has GongchaeAlertsFailedHigh; then echo "failed alert fired below 5" >&2; exit 1; fi
sql "DELETE FROM api_budget"
wait_until "its resolve reached the webhook stub" 420 stub has resolved GongchaeApiBudgetStopped

# Trigger M2 FAILED (>= 5) and UNCERTAIN (> 0) alerts and expose backlog / sending_stuck / delay_max gauges.
sql "INSERT INTO notification(subscription_id, kind, batch_key, status, created_at, finished_at, idempotency_key) VALUES
     ('11111111-1111-4111-8111-111111111111', 'EMAIL_DIGEST', 'e2e-fail-5', 'FAILED', now() - interval '1 minute', now(), '00000000-0000-4000-8000-000000000005'),
     ('11111111-1111-4111-8111-111111111111', 'EMAIL_DIGEST', 'e2e-uncertain-1', 'UNCERTAIN', now() - interval '1 minute', now(), '00000000-0000-4000-8000-000000000006')"
sql "INSERT INTO notification(subscription_id, kind, batch_key, status, next_attempt_at, claimed_at, created_at, idempotency_key) VALUES
     ('11111111-1111-4111-8111-111111111111', 'EMAIL_DIGEST', 'e2e-backlog-1', 'PENDING', now() - interval '10 minutes', NULL, now() - interval '10 minutes', '00000000-0000-4000-8000-000000000007'),
     ('11111111-1111-4111-8111-111111111111', 'EMAIL_DIGEST', 'e2e-stuck-1', 'SENDING', NULL, now() - interval '10 minutes', now() - interval '10 minutes', '00000000-0000-4000-8000-000000000008')"
wait_until "M2 backlog/sending_stuck/delay_max gauges scraped" 120 stub query 'max(alerts_notification_backlog{job="gongchae-app"}) == 1 and max(alerts_notification_sending_stuck{job="gongchae-app"}) == 1 and max(alerts_delivery_delay_max_seconds{job="gongchae-app"}) > 0'
wait_until "firing GongchaeAlertsFailedHigh reached the webhook stub" 300 stub has firing GongchaeAlertsFailedHigh
wait_until "firing GongchaeAlertsUncertain reached the webhook stub" 300 stub has firing GongchaeAlertsUncertain

sql "DELETE FROM subscription WHERE id = '11111111-1111-4111-8111-111111111111'"
wait_until "GongchaeAlertsFailedHigh resolve reached the webhook stub" 420 stub has resolved GongchaeAlertsFailedHigh
wait_until "GongchaeAlertsUncertain resolve reached the webhook stub" 420 stub has resolved GongchaeAlertsUncertain
if stub has e2e-only@example.invalid || stub has 11111111-1111; then echo "PII leaked to webhook stub" >&2; exit 1; fi
stub messages
