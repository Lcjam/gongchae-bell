#!/usr/bin/env bash
set -euo pipefail

# Isolated end-to-end backup & restore integration test:
# - Disposable postgres:17 source container + compose.test.yml
# - Synthetic erasure.log + sample DB rows
# - Real restic encrypted repository (local repo; uses native restic or restic/restic container)
# - backup.sh init + backup.sh run (pg_dump -Fc -> pg_restore --list -> restic backup -> keep-daily 14 prune)
# - restic restore -> separate disposable postgres:17 target container pg_restore -> row count & erasure.log check

export MSYS_NO_PATHCONV=1

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
BACKUP_SH="$REPO_ROOT/deploy/backup/backup.sh"

if command -v cygpath >/dev/null 2>&1; then
  mkdir -p "$REPO_ROOT/build"
  TMP_DIR="$(mktemp -d "$REPO_ROOT/build/backup-it.XXXXXX")"
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

PROJECT_NAME="gcbackupit$$"

COMPOSE_TEST_HOST="$(host_path "$TMP_DIR/compose.test.yml")"
COMPOSE_ENV_HOST="$(host_path "$TMP_DIR/compose.env")"

cleanup() {
  docker compose -p "$PROJECT_NAME" -f "$COMPOSE_TEST_HOST" down -v --remove-orphans >/dev/null 2>&1 || true
  rm -rf "$TMP_DIR"
}
trap cleanup EXIT

mkdir -p "$TMP_DIR/bin" "$TMP_DIR/repo" "$TMP_DIR/stage" "$TMP_DIR/restored" "$TMP_DIR/erasure" "$TMP_DIR/state"

if ! command -v restic >/dev/null 2>&1; then
  cat >"$TMP_DIR/bin/restic" <<EOF
#!/usr/bin/env bash
set -euo pipefail
exec docker run --rm -i \
  -v "$(host_path "$TMP_DIR"):$TMP_DIR" \
  -w "\$(pwd)" \
  -e RESTIC_REPOSITORY \
  -e RESTIC_PASSWORD \
  restic/restic:latest "\$@"
EOF
  chmod +x "$TMP_DIR/bin/restic"
fi

if ! command -v flock >/dev/null 2>&1; then
  cat >"$TMP_DIR/bin/flock" <<'EOF'
#!/usr/bin/env bash
exit 0
EOF
  chmod +x "$TMP_DIR/bin/flock"
fi

export PATH="$TMP_DIR/bin:$PATH"

cat >"$TMP_DIR/compose.test.yml" <<'EOF'
services:
  postgres:
    image: postgres:17
    environment:
      POSTGRES_DB: gongchae_src
      POSTGRES_USER: gongchae_test
      POSTGRES_PASSWORD: test_only_pg_password
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U $$POSTGRES_USER -d $$POSTGRES_DB"]
      interval: 2s
      timeout: 3s
      retries: 20

  postgres-restore:
    image: postgres:17
    environment:
      POSTGRES_DB: gongchae_restored
      POSTGRES_USER: gongchae_test
      POSTGRES_PASSWORD: test_only_pg_password
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U $$POSTGRES_USER -d $$POSTGRES_DB"]
      interval: 2s
      timeout: 3s
      retries: 20
EOF

cat >"$TMP_DIR/compose.env" <<'EOF'
ALERTS_ENABLED=true
APP_BLUE_TAG=
APP_GREEN_TAG=
EOF
chmod 0600 "$TMP_DIR/compose.env"

cat >"$TMP_DIR/erasure/erasure.log" <<'EOF'
00000000-0000-0000-0000-000000000001	2026-11-18T01:00:00Z
00000000-0000-0000-0000-000000000002	2026-11-18T02:00:00Z
EOF
chmod 0600 "$TMP_DIR/erasure/erasure.log"

cat >"$TMP_DIR/backup.env" <<EOF
RESTIC_REPOSITORY=$TMP_DIR/repo
RESTIC_PASSWORD=test-only-restic-passphrase
COMPOSE_FILE=$COMPOSE_TEST_HOST
COMPOSE_ENV_FILE=$COMPOSE_ENV_HOST
DEPLOY_STATE_DIR=$TMP_DIR/state
POSTGRES_SERVICE=postgres
ERASURE_LOG_PATH=$TMP_DIR/erasure/erasure.log
REQUIRE_ERASURE_LOG=auto
BACKUP_LOCK_FILE=$TMP_DIR/backup.lock
BACKUP_STAGE_ROOT=$TMP_DIR/stage
BACKUP_KEEP_DAILY=14
BACKUP_HOST_TAG=backup-integration-test
EOF
chmod 0600 "$TMP_DIR/backup.env"

docker compose -p "$PROJECT_NAME" -f "$COMPOSE_TEST_HOST" up -d --wait

docker compose -p "$PROJECT_NAME" -f "$COMPOSE_TEST_HOST" exec -T postgres \
  psql -U gongchae_test -d gongchae_src -v ON_ERROR_STOP=1 <<'SQL'
CREATE TABLE backup_verify_items (
  id INTEGER PRIMARY KEY,
  label TEXT NOT NULL
);
INSERT INTO backup_verify_items (id, label) VALUES
  (1, 'alpha'),
  (2, 'bravo'),
  (3, 'charlie'),
  (4, 'delta'),
  (5, 'echo');
SQL

# Make backup.sh invoke compose with the same isolated project name
REAL_DOCKER_BIN="$(command -v docker)"
cat >"$TMP_DIR/bin/docker" <<EOF
#!/usr/bin/env bash
set -euo pipefail
if [[ "\${1:-}" == "compose" ]]; then
  shift
  exec "$REAL_DOCKER_BIN" compose -p "$PROJECT_NAME" "\$@"
fi
exec "$REAL_DOCKER_BIN" "\$@"
EOF
chmod +x "$TMP_DIR/bin/docker"

"$BACKUP_SH" init --env-file "$TMP_DIR/backup.env"

# Seed two legacy random-path snapshots on distinct days to reproduce pre-fix mktemp paths (B1)
mkdir -p "$TMP_DIR/legacy-mktemp-1" "$TMP_DIR/legacy-mktemp-2"
printf 'legacy-1\n' >"$TMP_DIR/legacy-mktemp-1/gongchae-bell.dump"
printf 'legacy-2\n' >"$TMP_DIR/legacy-mktemp-2/gongchae-bell.dump"
(
  cd "$TMP_DIR/legacy-mktemp-1"
  RESTIC_REPOSITORY="$TMP_DIR/repo" RESTIC_PASSWORD="test-only-restic-passphrase" \
    restic backup --host backup-integration-test --tag gongchae-bell --tag daily --time "2026-11-15 03:00:00" gongchae-bell.dump >/dev/null
)
(
  cd "$TMP_DIR/legacy-mktemp-2"
  RESTIC_REPOSITORY="$TMP_DIR/repo" RESTIC_PASSWORD="test-only-restic-passphrase" \
    restic backup --host backup-integration-test --tag gongchae-bell --tag daily --time "2026-11-16 03:00:00" gongchae-bell.dump >/dev/null
)

# Multi-backup retention reduction: 3 daily snapshots across 3 different paths -> keep-daily=2 prunes down to 2
sed -i 's/^BACKUP_KEEP_DAILY=.*/BACKUP_KEEP_DAILY=2/' "$TMP_DIR/backup.env"
if ! "$BACKUP_SH" run --env-file "$TMP_DIR/backup.env" >"$TMP_DIR/backup-keep2.log" 2>&1; then
  cat "$TMP_DIR/backup-keep2.log" >&2 || true
  exit 1
fi
grep -q "BACKUP_OK" "$TMP_DIR/backup-keep2.log"
grep -q "SNAPSHOT_RETENTION_SUMMARY count=2" "$TMP_DIR/backup-keep2.log"
grep -q "keep_daily=2" "$TMP_DIR/backup-keep2.log"

# Reduce retention further: keep-daily=1 prunes remaining legacy snapshot down to 1
sed -i 's/^BACKUP_KEEP_DAILY=.*/BACKUP_KEEP_DAILY=1/' "$TMP_DIR/backup.env"
if ! "$BACKUP_SH" run --env-file "$TMP_DIR/backup.env" >"$TMP_DIR/backup.log" 2>&1; then
  cat "$TMP_DIR/backup.log" >&2 || true
  exit 1
fi

grep -q "BACKUP_OK" "$TMP_DIR/backup.log"
grep -q "SNAPSHOT_RETENTION_SUMMARY count=1" "$TMP_DIR/backup.log"
snapshot_compact_count="$(RESTIC_REPOSITORY="$TMP_DIR/repo" RESTIC_PASSWORD="test-only-restic-passphrase" \
  restic snapshots --tag gongchae-bell --json | grep -c '"short_id"' || true)"
if [[ "$snapshot_compact_count" != "1" ]]; then
  echo "ERROR: expected 1 remaining snapshot after keep-daily=1, got [$snapshot_compact_count]" >&2
  exit 1
fi
if grep -qF "test-only-restic-passphrase" "$TMP_DIR/backup.log"; then
  echo "ERROR: RESTIC_PASSWORD leaked into backup log" >&2
  exit 1
fi

RESTIC_REPOSITORY="$TMP_DIR/repo" RESTIC_PASSWORD="test-only-restic-passphrase" \
  restic restore latest --target "$TMP_DIR/restored" >/dev/null

[[ -f "$TMP_DIR/restored/gongchae-bell.dump" ]] || { echo "Missing restored dump" >&2; exit 1; }
[[ -f "$TMP_DIR/restored/erasure.log" ]] || { echo "Missing restored erasure.log" >&2; exit 1; }
[[ -f "$TMP_DIR/restored/backup-meta.txt" ]] || { echo "Missing restored backup-meta.txt" >&2; exit 1; }

cmp -s "$TMP_DIR/erasure/erasure.log" "$TMP_DIR/restored/erasure.log"

docker compose -p "$PROJECT_NAME" -f "$COMPOSE_TEST_HOST" exec -T postgres-restore \
  pg_restore -U gongchae_test -d gongchae_restored --no-owner --exit-on-error <"$TMP_DIR/restored/gongchae-bell.dump"

restored_count="$(docker compose -p "$PROJECT_NAME" -f "$COMPOSE_TEST_HOST" exec -T postgres-restore \
  psql -U gongchae_test -d gongchae_restored -tAc 'SELECT count(*) FROM backup_verify_items;')"
restored_count="$(printf '%s' "$restored_count" | tr -d '[:space:]')"

if [[ "$restored_count" != "5" ]]; then
  echo "ERROR: expected restored row count 5, got [$restored_count]" >&2
  exit 1
fi

echo "BACKUP_INTEGRATION_TEST_PASSED"
