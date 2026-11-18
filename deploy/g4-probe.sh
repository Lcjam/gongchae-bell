#!/usr/bin/env bash
set -euo pipefail

# PRD G4 fixed external continuous HTTP/TLS probe (runs OUTSIDE the target VM).
# Contract synchronized with docs/runbook.md:
# - Targets: / and /jobs on https://gongchae.enjoylcjworld.com
# - Interval: 1s
# - Timeouts: --connect-timeout 3 --max-time 5
# - Failures: non-zero curl exit (DNS/TCP/TLS/timeout), HTTP 5xx, or HTTP != 200
# - Pass condition: failures == 0 and total_requests >= min_requests

BASE_URL="${G4_BASE_URL:-https://gongchae.enjoylcjworld.com}"
INTERVAL_SEC="${G4_INTERVAL_SEC:-1}"
CONNECT_TIMEOUT="${G4_CONNECT_TIMEOUT:-3}"
MAX_TIME="${G4_MAX_TIME:-5}"
DURATION_SEC="${G4_DURATION_SEC:-0}"
STOP_FILE="${G4_STOP_FILE:-}"
POST_WINDOW_SEC="${G4_POST_WINDOW_SEC:-15}"
MIN_REQUESTS="${G4_MIN_REQUESTS:-5}"
REPORT_FILE="${G4_REPORT_FILE:-}"
EXECUTION_CONTEXT="${G4_EXECUTION_CONTEXT:-external}"
DEPLOY_ID="${G4_DEPLOY_ID:-untracked}"
CURL_BIN="${CURL_BIN:-curl}"

usage() {
  cat <<'EOF'
Usage:
  g4-probe.sh [--base-url URL] [--duration SEC | --stop-file PATH] [--post-window SEC] [--interval SEC] [--min-requests N] [--report FILE] [--context CTX] [--deploy-id ID]
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --base-url) BASE_URL="$2"; shift 2 ;;
    --duration) DURATION_SEC="$2"; shift 2 ;;
    --stop-file) STOP_FILE="$2"; shift 2 ;;
    --post-window) POST_WINDOW_SEC="$2"; shift 2 ;;
    --interval) INTERVAL_SEC="$2"; shift 2 ;;
    --min-requests) MIN_REQUESTS="$2"; shift 2 ;;
    --report) REPORT_FILE="$2"; shift 2 ;;
    --context) EXECUTION_CONTEXT="$2"; shift 2 ;;
    --deploy-id) DEPLOY_ID="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) printf 'Unknown argument: %s\n' "$1" >&2; exit 2 ;;
  esac
done

if [[ "$DURATION_SEC" -le 0 && -z "$STOP_FILE" ]]; then
  DURATION_SEC=30
fi

routes=("/" "/jobs")
total=0
ok=0
http_5xx=0
connect_tls_errors=0
timeouts=0
other_non_200=0
max_ms=0
declare -a latencies_ms=()

runner_host="$(hostname 2>/dev/null || echo unknown)"
start_iso="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
start_epoch="$(date +%s)"
post_deadline=0
stopping=0

probe_once() {
  local route="$1"
  local url="${BASE_URL%/}${route}"
  local out rc code time_total time_ms

  rc=0
  out="$("$CURL_BIN" -sS -o /dev/null -w '%{http_code} %{time_total}' \
    --connect-timeout "$CONNECT_TIMEOUT" --max-time "$MAX_TIME" \
    "$url" 2>/dev/null)" || rc=$?

  total=$((total + 1))

  if [[ "$rc" -ne 0 ]]; then
    if [[ "$rc" -eq 28 ]]; then
      timeouts=$((timeouts + 1))
      printf '%s FAIL deploy_id=%s route=%s kind=timeout curl_rc=%d\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$DEPLOY_ID" "$route" "$rc"
    else
      connect_tls_errors=$((connect_tls_errors + 1))
      printf '%s FAIL deploy_id=%s route=%s kind=connect_tls curl_rc=%d\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$DEPLOY_ID" "$route" "$rc"
    fi
    return
  fi

  if [[ "$out" == *" "* ]]; then
    code="${out%% *}"
    time_total="${out##* }"
  else
    code="$out"
    time_total="0"
  fi
  time_ms="$(awk -v t="$time_total" 'BEGIN { printf "%d", (t * 1000) + 0.5 }')"
  latencies_ms+=("$time_ms")
  if [[ "$time_ms" -gt "$max_ms" ]]; then
    max_ms="$time_ms"
  fi

  if [[ "$code" == "200" ]]; then
    ok=$((ok + 1))
  elif [[ "$code" =~ ^5[0-9]{2}$ ]]; then
    http_5xx=$((http_5xx + 1))
    printf '%s FAIL deploy_id=%s route=%s kind=http_5xx status=%s latency_ms=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$DEPLOY_ID" "$route" "$code" "$time_ms"
  else
    other_non_200=$((other_non_200 + 1))
    printf '%s FAIL deploy_id=%s route=%s kind=non_200 status=%s latency_ms=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$DEPLOY_ID" "$route" "$code" "$time_ms"
  fi
}

idx=0
while true; do
  now="$(date +%s)"
  if [[ -n "$STOP_FILE" ]]; then
    if [[ "$stopping" -eq 0 && -f "$STOP_FILE" ]]; then
      stopping=1
      post_deadline=$((now + POST_WINDOW_SEC))
    fi
    if [[ "$stopping" -eq 1 && "$now" -ge "$post_deadline" ]]; then
      break
    fi
  else
    if [[ $((now - start_epoch)) -ge "$DURATION_SEC" ]]; then
      break
    fi
  fi

  route="${routes[$((idx % ${#routes[@]}))]}"
  idx=$((idx + 1))
  probe_once "$route"
  sleep "$INTERVAL_SEC"
done

end_iso="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
failures=$((http_5xx + connect_tls_errors + timeouts + other_non_200))

p95_ms=0
if [[ "${#latencies_ms[@]}" -gt 0 ]]; then
  p95_ms="$(printf '%s\n' "${latencies_ms[@]}" | sort -n | awk '{ a[NR]=$1 } END { idx = int((NR * 95 + 99) / 100); if (idx < 1) idx = 1; print a[idx] }')"
fi

summary_json="$(cat <<EOF
{
  "deploy_id": "$DEPLOY_ID",
  "execution_context": "$EXECUTION_CONTEXT",
  "runner_host": "$runner_host",
  "base_url": "$BASE_URL",
  "started_at": "$start_iso",
  "finished_at": "$end_iso",
  "interval_sec": $INTERVAL_SEC,
  "connect_timeout_sec": $CONNECT_TIMEOUT,
  "max_time_sec": $MAX_TIME,
  "total_requests": $total,
  "ok_requests": $ok,
  "failures": $failures,
  "http_5xx": $http_5xx,
  "connect_tls_errors": $connect_tls_errors,
  "timeouts": $timeouts,
  "other_non_200": $other_non_200,
  "p95_latency_ms": $p95_ms,
  "max_latency_ms": $max_ms
}
EOF
)"

printf '%s\n' "$summary_json"
if [[ -n "$REPORT_FILE" ]]; then
  mkdir -p "$(dirname "$REPORT_FILE")"
  printf '%s\n' "$summary_json" >"$REPORT_FILE"
fi

if [[ "$total" -lt "$MIN_REQUESTS" ]]; then
  printf 'G4_FAIL deploy_id=%s insufficient_samples total=%d min_required=%d\n' "$DEPLOY_ID" "$total" "$MIN_REQUESTS" >&2
  exit 1
fi

if [[ "$failures" -ne 0 ]]; then
  printf 'G4_FAIL deploy_id=%s failures=%d (5xx=%d connect_tls=%d timeouts=%d other=%d)\n' \
    "$DEPLOY_ID" "$failures" "$http_5xx" "$connect_tls_errors" "$timeouts" "$other_non_200" >&2
  exit 1
fi

printf 'G4_PASS deploy_id=%s total=%d ok=%d failures=0 p95_ms=%d max_ms=%d\n' "$DEPLOY_ID" "$total" "$ok" "$p95_ms" "$max_ms"
