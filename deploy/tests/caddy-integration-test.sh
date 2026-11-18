#!/usr/bin/env bash
set -euo pipefail

# Real Caddy (caddy:2-alpine) integration test on an isolated Docker bridge network:
# - Security headers (HSTS, nosniff, DENY, CSP, Referrer-Policy)
# - Token-route headers (/subscriptions* -> no-referrer, no-store, noindex)
# - Access log redaction (t query param, Referer, Authorization, Cookie stripped)
# - Proxy IP boundary (client spoofed X-Forwarded-For discarded; upstream receives Caddy remote_host)
# - Atomic upstream switch (blue -> green) + caddy validate + caddy reload

export MSYS_NO_PATHCONV=1

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

NET_NAME="gc-caddy-test-$$"
CADDY_NAME="gc-caddy-$$"
BLUE_NAME="gc-blue-$$"
GREEN_NAME="gc-green-$$"

if command -v cygpath >/dev/null 2>&1; then
  mkdir -p "$REPO_ROOT/deploy/state"
  TMP_DIR="$(mktemp -d "$REPO_ROOT/deploy/state/caddy-test.XXXXXX")"
else
  TMP_DIR="$(mktemp -d)"
fi

host_path() {
  if command -v cygpath >/dev/null 2>&1; then
    cygpath -m "$1"
  else
    printf '%s' "$1"
  fi
}

cleanup() {
  docker rm -f "$CADDY_NAME" "$BLUE_NAME" "$GREEN_NAME" >/dev/null 2>&1 || true
  docker network rm "$NET_NAME" >/dev/null 2>&1 || true
  rm -rf "$TMP_DIR"
}
trap cleanup EXIT

mkdir -p "$TMP_DIR/runtime" "$TMP_DIR/blue" "$TMP_DIR/green"
cp -f "$REPO_ROOT/deploy/caddy/upstream.blue.caddy" "$TMP_DIR/runtime/upstream.caddy"

cat >"$TMP_DIR/blue/Caddyfile" <<'EOF'
:8080 {
	respond "color=blue xff={http.request.header.X-Forwarded-For}" 200
}
EOF
cat >"$TMP_DIR/green/Caddyfile" <<'EOF'
:8080 {
	respond "color=green xff={http.request.header.X-Forwarded-For}" 200
}
EOF

docker network create "$NET_NAME" >/dev/null
docker run -d --name "$BLUE_NAME" --network "$NET_NAME" --network-alias app-blue \
  -v "$(host_path "$TMP_DIR/blue/Caddyfile"):/etc/caddy/Caddyfile:ro" caddy:2-alpine >/dev/null
docker run -d --name "$GREEN_NAME" --network "$NET_NAME" --network-alias app-green \
  -v "$(host_path "$TMP_DIR/green/Caddyfile"):/etc/caddy/Caddyfile:ro" caddy:2-alpine >/dev/null

docker run -d --name "$CADDY_NAME" --network "$NET_NAME" --network-alias edge-caddy \
  -e "SITE_DOMAIN=http://localhost" \
  -v "$(host_path "$REPO_ROOT/deploy/caddy/Caddyfile"):/etc/caddy/Caddyfile:ro" \
  -v "$(host_path "$TMP_DIR/runtime"):/etc/caddy/runtime" \
  caddy:2-alpine >/dev/null

sleep 2

# 1. Request / via internal test runner container (proving real proxy routing & headers)
resp_root="$(docker run --rm --network "$NET_NAME" caddy:2-alpine \
  curl -fsSi -H "Host: localhost" "http://edge-caddy/")"
printf '%s\n' "$resp_root" | tr -d '\r' >"$TMP_DIR/root.txt"
grep -q "color=blue" "$TMP_DIR/root.txt"
grep -qi "^Strict-Transport-Security: max-age=31536000" "$TMP_DIR/root.txt"
grep -qi "^X-Content-Type-Options: nosniff" "$TMP_DIR/root.txt"
grep -qi "^X-Frame-Options: DENY" "$TMP_DIR/root.txt"
grep -qi "^Content-Security-Policy: default-src 'self'" "$TMP_DIR/root.txt"
grep -qi "^Referrer-Policy: strict-origin-when-cross-origin" "$TMP_DIR/root.txt"

# 2. Request /subscriptions/confirm?t=SUPER_SECRET_TOKEN&keep=1 with spoofed XFF & Referer
auth_header="Authorization: Bearer test-redaction-auth"
cookie_header="Cookie: session=test-redaction-cookie"
referer_header="Referer: https://evil.example/leak?t=test-redaction-ref"
resp_sub="$(docker run --rm --network "$NET_NAME" caddy:2-alpine \
  curl -fsSi -H "Host: localhost" \
  -H "X-Forwarded-For: 9.9.9.9" \
  -H "$referer_header" \
  -H "$auth_header" \
  -H "$cookie_header" \
  "http://edge-caddy/subscriptions/confirm?t=SUPER_SECRET_TOKEN&keep=1")"
printf '%s\n' "$resp_sub" | tr -d '\r' >"$TMP_DIR/sub.txt"
grep -qi "^Referrer-Policy: no-referrer" "$TMP_DIR/sub.txt"
grep -qi "^Cache-Control: no-store" "$TMP_DIR/sub.txt"
grep -qi "^X-Robots-Tag: noindex" "$TMP_DIR/sub.txt"
if grep -q "9.9.9.9" "$TMP_DIR/sub.txt"; then
  echo "ERROR: spoofed X-Forwarded-For 9.9.9.9 leaked to upstream!" >&2
  exit 1
fi

# 3. Check Caddy JSON access log stripped t=, Referer, Authorization, Cookie
sleep 1
docker logs "$CADDY_NAME" >"$TMP_DIR/caddy.log" 2>&1
for secret in "SUPER_SECRET_TOKEN" "test-redaction-ref" "test-redaction-auth" "test-redaction-cookie" "evil.example"; do
  if grep -qF "$secret" "$TMP_DIR/caddy.log"; then
    echo "ERROR: sensitive value '$secret' leaked in Caddy access log!" >&2
    cat "$TMP_DIR/caddy.log" >&2
    exit 1
  fi
done
grep -q "keep=1" "$TMP_DIR/caddy.log"

# 4. Atomic switch blue -> green + caddy validate + caddy reload
cp -f "$REPO_ROOT/deploy/caddy/upstream.green.caddy" "$TMP_DIR/runtime/upstream.caddy.next"
docker exec "$CADDY_NAME" sh -c '
  sed "s|/etc/caddy/runtime/upstream.caddy|/etc/caddy/runtime/upstream.caddy.next|g" /etc/caddy/Caddyfile > /tmp/Caddyfile.validate
  caddy validate --config /tmp/Caddyfile.validate --adapter caddyfile
  rc=$?
  rm -f /tmp/Caddyfile.validate
  exit $rc
'
mv -f "$TMP_DIR/runtime/upstream.caddy.next" "$TMP_DIR/runtime/upstream.caddy"
docker exec "$CADDY_NAME" caddy reload --config /etc/caddy/Caddyfile --adapter caddyfile >/dev/null

resp_green="$(docker run --rm --network "$NET_NAME" caddy:2-alpine \
  curl -fsS -H "Host: localhost" "http://edge-caddy/")"
if [[ "$resp_green" != *"color=green"* ]]; then
  echo "ERROR: expected color=green after reload, got: $resp_green" >&2
  exit 1
fi

echo "CADDY_INTEGRATION_TEST_PASSED"
