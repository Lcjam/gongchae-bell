#!/usr/bin/env bash
# The erasure-journal file tool on the real boot JAR and real Linux: it runs before Spring, with no DB, env, key or
# network, prints only codes and counts, and keeps owner, group and mode per role. Fixtures live in a throwaway volume.
set -euo pipefail
fail() { echo "FAIL: $*" >&2; exit 1; }

JAR=${1:?usage: journal-tool-smoke.sh <boot jar>}
IMAGE=${JOURNAL_TOOL_IMAGE:-eclipse-temurin:21-jre-noble}  # the runtime base of Dockerfile
VOL=journal-tool-smoke-$$
OUT=$(mktemp -d)
UUID_RE='[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}'
SUB=0b4c2a8e-1f3d-4c5b-9a7e-6d8f0e1a2b3c  # a made-up subscription id (v4)
cleanup() { docker volume rm -f "$VOL" "$VOL-app" >/dev/null 2>&1 || true; rm -rf "$OUT"; }
trap cleanup EXIT
[[ -f $JAR ]] || fail "no boot jar"
docker volume create "$VOL" >/dev/null
docker volume create "$VOL-app" >/dev/null
holder=$(docker create -v "$VOL-app:/app" "$IMAGE" true)
docker cp -q "$JAR" "$holder:/app/gongchae-bell.jar"
docker rm "$holder" >/dev/null

root() { docker run --rm --network none -v "$VOL:/j" "$IMAGE" sh -c "$1"; }
java_as() {  # java_as <uid:gid> <java args...>, no env given, no network
  local user=$1; shift
  docker run --rm --network none --user "$user" -v "$VOL-app:/app:ro" -v "$VOL:/j" "$IMAGE" java "$@"
}

# expect <exit> <stdout glob> <uid:gid> <tool args...>
expect() {
  local want_rc=$1 want=$2 user=$3 out rc=0; shift 3
  out=$(java_as "$user" -jar /app/gongchae-bell.jar erasure-journal "$@" 2>"$OUT/stderr") || rc=$?
  [[ $rc == "$want_rc" && $out == $want ]] || { sed 's/^/stderr: /' "$OUT/stderr" >&2; fail "erasure-journal $* as $user: rc=$rc out=$out"; }
  [[ ! -s $OUT/stderr ]] || fail "erasure-journal $* wrote to stderr"
  ! grep -Eq "$UUID_RE|/" <<<"$out" || fail "erasure-journal $* printed an id or a path"
  echo "ok  $user erasure-journal $* -> $rc $out"
}
state() { root "stat -c '%u:%g %a %h %i' $1 && sha256sum < $1 && ls -A \$(dirname $1) | tr '\n' ' '"; }

# Spring, the DB pool, Flyway and logging are never loaded; the tool is.
java_as 1000:1000 -verbose:class -jar /app/gongchae-bell.jar erasure-journal version >"$OUT/classes"
grep -q '^ERASURE_TOOL v1$' "$OUT/classes" || fail "version token"
grep -q 'kr.gongchae.bell.alerts.ErasureJournalTool ' "$OUT/classes" || fail "the tool class was not loaded"
if grep -E 'org\.springframework\.boot\.SpringApplication |org\.springframework\.context\.|com\.zaxxer\.hikari\.|org\.flywaydb\.|org\.postgresql\.|ch\.qos\.logback\.|org\.apache\.catalina\.' "$OUT/classes"; then
  fail "Spring or DB classes loaded"
fi
echo "ok  no Spring context, Hikari, Flyway, PostgreSQL, logback or Tomcat class loaded ($(grep -c 'class,load' "$OUT/classes") classes)"

expect 0 'ERASURE_TOOL v1' 1000:1000 version
expect 2 'ERASURE_TOOL_USAGE' 1000:1000
expect 2 'ERASURE_TOOL_USAGE' 1000:1000 compact /j/d/erasure.log

root 'mkdir /j/d /j/e /j/c && chown 1000:1000 /j/d /j/e /j/c && chmod 0750 /j/d /j/e /j/c'
L=/j/d/erasure.log

# init: only a missing file, one new marker, 0640, the directory's owner and group, no temporary file left.
expect 3 'ERASURE_LOG_MISSING' 1000:1000 mark "$L"
root "test ! -e $L" || fail "mark created the journal"
expect 0 'ERASURE_INIT_DONE' 1000:1000 init "$L"
expect 0 'ERASURE_CHECK_OK lines=1 markers=1 head=marker tail=marker' 1000:1000 check "$L"
root "grep -c '^67636862-6172-8' $L" | grep -qx 1 || fail "init content"
root "stat -c '%u:%g %a %h' $L" | grep -qx '1000:1000 640 1' || fail "init owner, mode or link count"
[[ $(root 'ls -A /j/d') == erasure.log ]] || fail "temporary file left"
before=$(state "$L")
expect 0 'ERASURE_INIT_ALREADY' 1000:1000 init "$L"
expect 0 'ERASURE_MARK_NOOP lines=1 markers=1 head=marker tail=marker' 1000:1000 mark "$L"
[[ $(state "$L") == "$before" ]] || fail "ALREADY or NOOP changed the file"

# mark after an unsubscribe: every original byte kept, one marker appended, owner/group/mode kept, replaced by rename.
root "printf '%s\t2026-11-22T00:00:00Z\n' $SUB >> $L && cp $L /j/prefix"
inode=$(root "stat -c %i $L")
expect 0 'ERASURE_MARK_DONE lines=3 markers=2 head=marker tail=marker' 1000:1000 mark "$L"
root "cmp -n \$(stat -c %s /j/prefix) /j/prefix $L" || fail "prefix changed"
root "stat -c '%u:%g %a %h' $L" | grep -qx '1000:1000 640 1' || fail "mark owner, mode or link count"
[[ $(root "stat -c %i $L") != "$inode" ]] || fail "not replaced by rename"
[[ $(root 'ls -A /j/d') == erasure.log ]] || fail "temporary file left"
expect 0 'ERASURE_MARK_NOOP lines=3 markers=2 head=marker tail=marker' 1000:1000 mark "$L"
expect 0 'ERASURE_CHECK_OK lines=3 markers=2 head=marker tail=marker' 1000:1000 check "$L"
expect 0 'ERASURE_VERIFY_OK lines=3 markers=2 head=marker tail=marker temps=0' 1000:1000 verify "$L"

# Roles. root would leave a root-owned file: refused before publishing. A group reader (the backup role) can only
# check; other users cannot even look. Nothing changes.
root "printf '%s\t2026-11-22T00:00:01Z\n' $SUB >> $L"
before=$(state "$L")
expect 3 'ERASURE_TOOL_OWNER_MISMATCH' 0:0 mark "$L"
expect 0 'ERASURE_CHECK_OK lines=4 markers=2 head=marker tail=line' 1001:1000 check "$L"
expect 4 'ERASURE_TOOL_ACCESS_DENIED' 1001:1000 mark "$L"
expect 4 'ERASURE_TOOL_ACCESS_DENIED' 1001:1000 verify "$L"
expect 4 'ERASURE_TOOL_ACCESS_DENIED' 1002:1002 check "$L"
expect 4 'ERASURE_TOOL_ACCESS_DENIED' 1002:1002 init /j/d/other.log
[[ $(state "$L") == "$before" ]] || fail "a refused role changed the file"

# An empty or corrupt existing file is refused by init and mark and stays as it is.
root 'install -o 1000 -g 1000 -m 0640 /dev/null /j/e/erasure.log && printf "%s\t2026-11-22" '"$SUB"' > /j/c/erasure.log && chown 1000:1000 /j/c/erasure.log && chmod 0640 /j/c/erasure.log'
before=$(state /j/e/erasure.log)
expect 3 'ERASURE_INIT_EXISTING_EMPTY' 1000:1000 init /j/e/erasure.log
expect 3 'ERASURE_MARK_EMPTY' 1000:1000 mark /j/e/erasure.log
[[ $(state /j/e/erasure.log) == "$before" ]] || fail "empty file changed"
before=$(state /j/c/erasure.log)
expect 3 'ERASURE_LOG_TORN_LAST_LINE' 1000:1000 init /j/c/erasure.log
expect 3 'ERASURE_LOG_TORN_LAST_LINE' 1000:1000 mark /j/c/erasure.log
expect 3 'ERASURE_BUNDLE_TORN_LAST_LINE' 1000:1000 check /j/c/erasure.log
[[ $(state /j/c/erasure.log) == "$before" ]] || fail "corrupt file changed"

echo "ALL_JOURNAL_TOOL_SMOKE_PASSED"
