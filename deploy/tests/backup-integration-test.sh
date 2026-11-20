#!/usr/bin/env bash
set -euo pipefail

# Isolated end-to-end backup & restore integration test:
# - Disposable postgres:17 source container + compose.test.yml
# - Synthetic erasure.log + sample DB rows
# - Real restic encrypted repository (local repo; uses native restic or restic/restic container)
# - backup.sh init + backup.sh run (expire >14d by wall clock -> prune --max-unused 0 -> pg_dump -Fc ->
#   pg_restore --list -> restic backup), ADR-0017
# - Seeded snapshots dated with restic --time: 20d and 14d+1h gongchae-bell copies are forgotten and the data unique
#   to them is physically gone (restic cat blob fails); a 14d-1h copy and a 30d copy with another tag stay
# - A second repository whose copies are all past 14 days and whose dump fails ends with zero gongchae-bell copies
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

NOW="$(date -u +%s)"
DAY=86400
# restic against $TMP_DIR/$1 (repo or repo2).
rr() { local repo="$1"; shift; RESTIC_REPOSITORY="$TMP_DIR/$repo" RESTIC_PASSWORD="test-only-restic-passphrase" TZ=UTC restic "$@"; }
# Seed one snapshot dated $3 seconds ago with tag $2 holding content $4. Content under 512 KiB is one data blob
# whose restic ID is the SHA-256 of that content, so `cat blob` shows whether its data is still stored.
seed() {
  local repo="$1" tag="$2" age="$3" content="$4" dir="$TMP_DIR/seed-$RANDOM$RANDOM"
  mkdir -p "$dir"
  printf '%s' "$content" >"$dir/gongchae-bell.dump"
  (cd "$dir" && rr "$repo" backup --host backup-integration-test --tag "$tag" --tag daily \
    --time "$(date -u -d "@$((NOW - age))" '+%Y-%m-%d %H:%M:%S')" gongchae-bell.dump >/dev/null)
}
blob_id() { printf '%s' "$1" | sha256sum | awk '{print $1}'; }
count_snaps() { rr "$1" snapshots --tag "$2" --json | grep -o '"short_id"' | wc -l | tr -d '[:space:]'; }
expect_eq() { [[ "$1" == "$2" ]] || { echo "ERROR: $3: expected [$1] got [$2]" >&2; exit 1; }; }

EXPIRED_20D="expired 20d unique $NOW-$RANDOM"
EXPIRED_14D1H="expired 14d+1h unique $NOW-$RANDOM"
KEPT_14D="kept 14d-1h unique $NOW-$RANDOM"
OTHER_TAG="other app 30d unique $NOW-$RANDOM"
seed repo gongchae-bell $((20 * DAY)) "$EXPIRED_20D"
seed repo gongchae-bell $((14 * DAY + 3600)) "$EXPIRED_14D1H"
seed repo gongchae-bell $((14 * DAY - 3600)) "$KEPT_14D"
seed repo other-app $((30 * DAY)) "$OTHER_TAG"
rr repo cat blob "$(blob_id "$EXPIRED_20D")" >/dev/null || { echo "ERROR: seeded blob not addressable by SHA-256" >&2; exit 1; }

if ! "$BACKUP_SH" run --env-file "$TMP_DIR/backup.env" >"$TMP_DIR/backup.log" 2>&1; then
  cat "$TMP_DIR/backup.log" >&2 || true
  exit 1
fi
cat "$TMP_DIR/backup.log"
grep -q "BACKUP_OK" "$TMP_DIR/backup.log"
grep -q "SNAPSHOT_RETENTION_SUMMARY policy=wall-clock-14d forgotten=2 total=1 expire=0 keep=1" "$TMP_DIR/backup.log"
expect_eq 2 "$(count_snaps repo gongchae-bell)" "gongchae-bell copies after run (14d-1h copy + new backup)"
expect_eq 1 "$(count_snaps repo other-app)" "copy with another tag must stay"
for gone in "$EXPIRED_20D" "$EXPIRED_14D1H"; do
  if rr repo cat blob "$(blob_id "$gone")" >/dev/null 2>&1; then
    echo "ERROR: data of a forgotten snapshot is still stored after prune" >&2; exit 1
  fi
done
rr repo cat blob "$(blob_id "$KEPT_14D")" >/dev/null
rr repo cat blob "$(blob_id "$OTHER_TAG")" >/dev/null
if grep -qF "test-only-restic-passphrase" "$TMP_DIR/backup.log"; then
  echo "ERROR: RESTIC_PASSWORD leaked into backup log" >&2
  exit 1
fi

# Backup outage + last copies: every copy is past 14 days and the dump fails -> zero copies remain (user decision).
sed -e "s#^RESTIC_REPOSITORY=.*#RESTIC_REPOSITORY=$TMP_DIR/repo2#" -e 's#^POSTGRES_SERVICE=.*#POSTGRES_SERVICE=postgres-absent#' \
  "$TMP_DIR/backup.env" >"$TMP_DIR/backup2.env"
chmod 0600 "$TMP_DIR/backup2.env"
"$BACKUP_SH" init --env-file "$TMP_DIR/backup2.env" >/dev/null
seed repo2 gongchae-bell $((20 * DAY)) "outage 20d $NOW-$RANDOM"
LAST_COPY="outage 15d last copy $NOW-$RANDOM"
seed repo2 gongchae-bell $((15 * DAY)) "$LAST_COPY"
if "$BACKUP_SH" run --env-file "$TMP_DIR/backup2.env" >"$TMP_DIR/backup2.log" 2>&1; then
  echo "ERROR: run with an absent PostgreSQL service must fail" >&2; exit 1
fi
cat "$TMP_DIR/backup2.log"
grep -q "forgotten=2 total=0 expire=0 keep=0" "$TMP_DIR/backup2.log"
expect_eq 0 "$(count_snaps repo2 gongchae-bell)" "copies left after a failed dump with every copy past 14 days"
if rr repo2 cat blob "$(blob_id "$LAST_COPY")" >/dev/null 2>&1; then
  echo "ERROR: data of the last expired copy is still stored" >&2; exit 1
fi

rr repo restore latest --tag gongchae-bell --target "$TMP_DIR/restored" >/dev/null

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
