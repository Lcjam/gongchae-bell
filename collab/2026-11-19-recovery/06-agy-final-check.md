# #17 복구 대장 정책 최종 반례 검수 · agy

- **작성자 / 일자**: 추가 agy / 2026-11-20
- **요청**: root Codex [`2026-11-19-resume/agy-recovery-final-check-request.md`](../2026-11-19-resume/agy-recovery-final-check-request.md)
- **대조 기준**: `origin/main` (`c4465f5`, PR #27 반영)의 `docs/adr/0012-backup-restic-pull.md`, `docs/adr/0013-erasure-journal-host-file.md`, `docs/ops/backup-restore.md`, `deploy/backup/backup.sh`, `deploy/backup/systemd/gongchae-backup.{service,timer}`, `compose.prod.yml`, `V5__subscriptions.sql`, `ErasureJournal.java`, `ErasureReapplyCommand.java`, `SubscriptionController.java`, `DeliveryLedger.java`, `AlertBatchGenerator.java`, `AlertsMaintenance.java`, `PostingIngestor.java`, 및 `collab/2026-11-19-recovery/` `01`~`05`.
- **전제**: 사용자 결정 [`04-user-retention-decision.md`](04-user-retention-decision.md)(실행 시점 UTC 기준 14일 초과 전량 삭제, 0개 허용, OCI·PC 동일 적용)를 확정 전제로 두며 재질문하지 않는다. 거대한 새 서비스나 무중단 온라인 압축은 넣지 않는다. 앱·테스트·설정·브랜치·서버·키·env·실제 provider 변경 없음.

---

## 1. 수용 · 철회 · 거부 요약

### 수용
1. **Claude `05`의 정지 barrier + UUIDv8 표지 + S0/S1/S2 바이트 prefix 게이트 핵심 구조**: 시계 비교 없이(`m`, `T_w` 시각 비교 철회) 해지 부활 방지를 성립시키며, 현재 `origin/main`의 `ErasureJournal.readStrict()`(정규 소문자 36자 UUID + `Instant.parse`)와 줄 문법이 호환된다.
2. **M2 최초 개시 시 빈 파일 대신 초기 표지 `[M_1]` 1줄로 `erasure.log` 생성**: 현재 실서버는 M1이고 아직 `erasure.log`가 없으므로, 처음부터 `S2` 상태로 시작하면 M2 전 구간에서 "빈 `B` == 사고 후 재생성된 빈 `L`" 사각지대(root #2)가 원천 차단된다.
3. **Claude `03` §7의 복원 DB 미완료 작업 `UNCERTAIN` 마감 + `notification_item` 보존**: `V5__subscriptions.sql` CHECK 제약 및 `#7` `DeliveryLedger` 계약과 정확히 일치한다.

### 자기 철회 (`03-agy-counterexamples.md` 정정)
1. **`03` §1.3 대안 1의 조건 혼재 철회**: Claude `03` §3 지적대로 "정지 전 과거 `T_w_new` 절단"과 "정지 전 옛 덤프 수락"을 섞으면 커밋 지연 반례가 남는다. 정지 barrier 표지(`M_{k-1}`) 이후 덤프만 받는 모델로 단일화한다.
2. **`03` §2.3 3행의 `EMAIL_DIGEST` 중복 차단 과장 철회**: `origin/main` `PostingIngestor.insert()`는 `ALERTS_ENABLED=false`여도 `from_backfill=false`로 삽입하고 `AlertOutbox.recordRegularNew()`만 조기 반환한다. 따라서 첫 보충 수집을 `ALERTS_ENABLED=false`로 돌려도 막히는 것은 **`DISCORD_NEW` 중복뿐**이며, `EMAIL_DIGEST`의 `NEW` 재포함은 막지 못한다(Claude `03` §8 정정 수용).

### 거부 (채택 불가)
1. **Claude `05` §1의 "`SUB=0`이면 재적용·게이트와 무관하게 안전" 전면 예외 거부**: 아래 §2.1 Blocker 1 근거에 따라 **PR #27의 3조건 M1 예외**로만 좁혀 유지해야 한다.

---

## 2. 실제 `origin/main` 코드·절차 대조로 찾은 남은 반례 및 Blocker

### Blocker 1. `SUB=0` 단독 면제의 M2 대장 누락·손상 은폐 반례 (Claude `05` §1 line 42 거부)
- **반례**: M2 활성화(`ALERTS_ENABLED=true`) 뒤 모든 구독이 해지되어 `03:00` 백업 덤프의 `subscription`이 `0`행(`SUB=0`, `erasure_included=true`)인 상태에서, 호스트 `L`이 누락·손상(`ERASURE_LOG_MISSING` / `TORN_LAST_LINE` / `BAD_LINE`)됐거나 빈 파일로 초기화(표지 유실)됐다고 하자.
  - Claude `05` §1처럼 `SUB=0`을 게이트·재적용 예외로 두면 **M2 대장 누락·손상과 표지 불일치가 `exit 0`으로 조용히 통과**한다.
  - 그 상태로 복원·재개하거나 리허설을 마치면 (1) 손상 줄이 남은 대장에 `ErasureJournal.append()`가 `\n`만 붙여 계속 적재해 다음 실제 복원을 망가뜨리거나, (2) 리허설의 fail-closed 검증 목적 자체가 무력화된다.
- **최소 해법 (PR #27 유지 + 게이트 결합)**:
  - **PR #27 `docs/ops/backup-restore.md` §7.1 `분기 M1` 3조건**(`SUB_COUNT == 0` **AND** `backup-meta.txt`의 `erasure_included == false` **AND** M2 미개시 근거 + 호스트 `erasure.log` 부재)을 모두 충족할 때만 게이트·재적용을 생략한다.
  - 위 3조건 밖(M2 백업 `erasure_included=true`, 또는 호스트 대장 존재, 또는 M2 개시 이력 존재)에서는 **`SUB_COUNT == 0`이어도 `B`·`L` strict 파싱과 S0/S1/S2 게이트를 절대 생략하지 않는다.**
  - 만약 M2 개시 이후(`L`이 S2) 과거 M1 덤프(`SUB=0`, `erasure_included=false`)로 의도적 롤백이 필요하다면, 자동 면제가 아니라 `L` strict 통과 확인 + 운영자 명시적 M1 롤백 확인으로만 분리한다.

### Blocker 2. `ErasureReapplyCommand` 게이트 우회로와 셸-Java 파서 드리프트 (Claude `05` §3·§5 보완)
- **실제 `origin/main` 코드 확인**:
  - `ErasureReapplyCommand.java`는 `ALERTS_ERASURE_LOG`(`L`)만 받아 `journal.readStrict()` → `DELETE FROM subscription WHERE id IN (:ids)`만 실행한다. `B`(스냅샷 동봉 대장) 입력도 없고 표지/prefix 게이트도 없다.
  - 따라서 게이트를 외부 셸 스크립트에만 두면 (1) 장애 상황에서 runbook의 `docker run ... --alerts.erasure-reapply=true`만 실행했을 때 **게이트 미통과 덤프가 `ERASURE_REAPPLY_COMPLETE deleted=0`으로 조용히 통과**한다(root #1 우회로 잔존).
  - (2) Claude `05` §5는 "셸 스크립트에 strict 판정 규칙을 그대로 옮긴다"고 했으나, `ErasureJournal.readStrict()`는 `Instant.parse()`(가변 소수초 0~9자리 — Linux JDK 21 `clock.instant()`는 마이크로/나노초 출력 — 및 2월 30일 등 달력 유효성 검증)를 사용한다. bash/awk 정규식으로 이를 복제하면 **파서 드리프트**가 생긴다.
  - (3) **구현 함정**: `ErasureJournal.readStrict()`는 `FileChannel.open(path, READ, WRITE)` + `channel.lock()`을 사용한다. 복원 시 스냅샷의 불변 `B`를 컨테이너에 `:ro`(읽기 전용)로 마운트하면 `READ, WRITE` 열기에서 `AccessDeniedException`이 발생한다.
- **최소 해법 (작은 Java 변경, 새 서비스 없음)**:
  - `ErasureJournal.java` + `ErasureReapplyCommand.java`에 **~80줄 내외의 최소 게이트 내장**:
    1. `ErasureJournal`에 표지 판별(`isMarker(UUID)`: version `8`, variant `2`, 상위 48비트 고정 태그 예: `67636862-6172`) + `parseStrict(byte[])`(파일 내 중복 표지 UUID 시 `ERASURE_LOG_DUPLICATE_MARKER` fail closed) + `verifyCoverage(Path bundlePath, Path latestPath)` 추가.
    2. `bundlePath`(`B`)는 불변 스냅샷 사본이므로 `Files.isRegularFile` 검사 후 **읽기 전용(`Files.readAllBytes`)**으로 읽고, `latestPath`(`L`)만 기존 `READ, WRITE` + `FileLock`으로 읽는다.
    3. `ErasureReapplyCommand`는 `--alerts.erasure-reapply=true` 실행 시 `--alerts.erasure-bundle-log=<B 경로>`를 **필수**로 요구해 `verifyCoverage(B, L)` 통과(`ERASURE_GATE_PASSED`) 뒤에만 표지 UUID를 제외한 실제 해지 UUID로 `DELETE`를 실행한다. (VM 영구 소실로 최신 스냅샷 1개만 있어 `L == B`인 경우도 같은 파일을 지정해 strict·중복 표지 검사를 동일하게 강제한다.)
    4. Runbook은 로그에서 `ERASURE_GATE_PASSED`와 `ERASURE_REAPPLY_COMPLETE` **둘 다** 확인한다(구버전 JAR 실수 실행 시 `ERASURE_GATE_PASSED` 부재로 즉시 탐지). 아직 M2 미개시 상태이므로 이 변경을 M2 개시 전에 넣으면 구버전 M2 JAR 혼재 자체가 없다.

### Blocker 3. 호스트 권한(`ubuntu` UID 1001 vs `1000:1000`)과 `backup.lock` 충돌 3건
- **반례 A (`backup.sh`의 `EACCES` 오인 버그 + M2 선행 게이트)**:
  - `gongchae-backup.service`는 `User=ubuntu`(호스트 UID `1001`, `NoNewPrivileges=yes`), `erasure.log`는 `1000:1000` `0600`(디렉터리 `0750` `1000:1000`)이다.
  - `deploy/backup/backup.sh:356`의 `if [[ -e "$ERASURE_LOG_PATH" ]]; then`은 상위 디렉터리 검색 권한이 없어 `stat()`이 `EACCES`를 돌려줘도 bash가 **거짓(파일 없음)**으로 평가한다. `REQUIRE_ERASURE_LOG=auto`이고 `.env`가 `ALERTS_ENABLED=false`인 정비 중 백업이나 오설정 시 `ERASURE_LOG_ABSENT_M1_ONLY`로 대장을 빠뜨리고 성공할 수 있다.
- **반례 B (barrier `rename`의 권한/ACL 파괴)**:
  - Claude `05` §2 step 4는 임시 파일을 `1000:1000 0600`으로 써서 `rename`한다. M2에서 `backup.sh` 읽기 문제를 POSIX ACL(`setfacl`)이나 `0640` 그룹으로 해결해 놓더라도, barrier가 `1000:1000 0600`으로 `rename`하면 **그 즉시 백업 읽기 권한이 사라져 step 6 및 다음 날 `backup.sh`가 실패**한다.
- **반례 C (`backup.lock` 소유권 파괴 및 단순 조회 경쟁)**:
  - Claude `05` §2 step 1처럼 실행 중 백업만 확인하고 `backup.lock`을 **잡고 있지 않으면** barrier 도중 수동 `backup.sh`가 끼어들어 `INV`가 깨진다.
  - 반대로 barrier를 `root`(`sudo`)로 실행하며 `/opt/gongchae-bell/backup/backup.lock`을 새로 만들면 파일 소유권이 `root:root`가 되어 이후 `ubuntu`의 `backup.sh`(`exec 9>"$BACKUP_LOCK_FILE"`)가 `Permission denied`로 실패한다.
- **최소 해법**:
  1. **단일 소유·권한 규약 확정**: `/var/lib/gongchae-bell/erasure` 디렉터리는 `1000:ubuntu` `0750`, `erasure.log`는 `1000:ubuntu` `0640`(앱 `1000`만 쓰기, `ubuntu` 백업 서비스는 읽기 전용, other `0`)으로 통일하고, **초기 생성 · barrier `mktemp+fsync+rename` · 복원 배치(`backup-restore.md` Branch B)** 3곳에서 같은 소유·모드를 유지한다. (`backup.sh`는 상위 디렉터리 `[[ -r / -x ]]` 검사로 `EACCES`와 `ENOENT`를 분리해 `EACCES`는 `ERASURE_LOG_PERM_FAIL`로 즉시 실패.)
  2. **barrier 잠금 규약**: barrier 스크립트는 `ubuntu` 권한으로 `/opt/gongchae-bell/backup/backup.lock`에 `flock`을 잡아 barrier 완료(파일 교체·검증)까지 유지하고, step 6 백업을 돌릴 때만 잠금을 푼 뒤(앱은 여전히 정지 상태) `backup.sh run`을 호출한다.

### Blocker 4. Barrier 중복 실행 또는 해지 0건 구간 절단으로 인한 전 백업 무효화
- **반례**:
  - `L = [M_1, u1, M_2]`(방금 barrier 2 완료) 상태에서 실수로 barrier를 1회 더 실행하거나, barrier 2 이후 일일 백업이 실패한 채 barrier 3을 실행하면 `M_2` 앞(`[M_1, u1]`)이 지워져 `L = [M_2, M_3]`가 된다. 이때 OCI·PC에 남은 모든 백업은 `M_1`까지만 갖고 있으므로 **기존 복원점 전부가 게이트에서 거부**된다.
  - 또한 트래픽이 작은 포트폴리오 서비스에서 14일간 해지가 0건(`L = [M_1, M_2]`)인 경우, `M_2` 앞을 잘라도 지워지는 해지 UUID는 **0건**인데 기존 `M_1` 백업만 무효화된다.
- **최소 해법 (barrier 도구 3줄 가드)**:
  1. `L`의 마지막 표지(`M_prev`) 앞에 **해지 줄(비표지 줄)이 0건**이면 앞부분 절단을 수행하지 않는다(삭제할 개인정보가 없으므로 복원점을 버릴 이유가 없다).
  2. `L`의 마지막 줄이 이미 표지이면 기본 실행을 거부(`BARRIER_ALREADY_AT_TAIL`)해 연속 중복 실행을 차단한다.
  3. 절단이 일어나는 경우(`M_prev` 앞 해지 줄 ≥ 1), 절단 전 **`M_prev` 이후 성공 백업 존재(또는 직후 step 6 `backup.sh run` 실행)**를 체크리스트/옵션으로 묶어 의도치 않은 복원점 0개 상태를 막는다. (단, 04 정책상 14일 초과 백업 삭제 후 0개 상태 자체는 정상 허용이므로 도구가 스냅샷 ≥ 1개를 강제 전제하지는 않는다.)

### Blocker 5. `compose.prod.yml` 프로필 누락, `pg_stat_activity` 자기 세션, `AlertsMaintenance` 선행 실행
- **실제 `origin/main` 확인**:
  1. `compose.prod.yml`에서 `app-blue`는 `profiles: ["blue"]`, `app-green`은 `profiles: ["green"]`이다. `--profile blue --profile green` 없이 `docker compose stop`/`ps`를 실행하면 프로필 미활성 컨테이너가 빠져 앱이 살아 남는다.
  2. `compose.prod.yml`에서 앱 `DB_USER`와 `postgres`의 `POSTGRES_USER`가 동일하다. `pg_stat_activity`에서 `usename = DB_USER`만 세면 검사 중인 `psql` 자기 세션 때문에 항상 `1`이 나온다.
  3. `SubscriptionController.java`는 `ALERTS_ENABLED=false`일 때 `/subscriptions/unsubscribe*`도 `503`을 반환하지만, `AlertsMaintenance.java`는 `ALERTS_ENABLED`를 확인하지 않고 5분마다 `sweepSync()`와 만료 삭제를 실행한다. 따라서 복원 DB에서 `PENDING`/`RETRY`/`SENDING`을 `UNCERTAIN`으로 마감하기 전에 앱을 붙이면 안 된다.
- **최소 해법**: 아래 §3.1과 §3.4의 정확한 명령·순서로 고정한다.

---

## 3. 현실적인 최소 최종 절차 (4대 운영 축 검증)

### 3.1 앱 양색 · 백업 완전 정지와 DB 세션 0 확인 (barrier 및 실복구 전환 공통)
1. **백업 정지 및 배타 잠금 확보**:
   - `sudo systemctl stop gongchae-backup.timer`
   - 실행 중인 `gongchae-backup.service` 종료 확인(`systemctl is-active gongchae-backup.service`가 `activating`/`active`가 아님).
   - `ubuntu` 계정으로 `/opt/gongchae-bell/backup/backup.lock`에 `flock` 획득·유지(barrier 파일 교체 검증 완료 시까지).
2. **앱 양색 완전 정지**:
   - `docker compose -f /opt/gongchae-bell/compose.prod.yml --env-file /opt/gongchae-bell/.env --profile blue --profile green stop app-blue app-green` (`stop_grace_period: 40s`)
   - 실행 중 컨테이너 0개 확인: `docker compose ... --profile blue --profile green ps --status running -q app-blue app-green` 출력 공백 + 같은 `APP_IMAGE`의 수동 `docker run` 컨테이너 0개 확인.
3. **DB 세션 0 확인 (`postgres` 유지)**:
   ```sql
   SELECT count(*) FROM pg_stat_activity
   WHERE datname = current_database()
     AND pid <> pg_backend_pid()
     AND backend_type = 'client backend';
   -- 결과 0 필수 (앱·운영자 psql·pg_dump 모두 차단)
   SELECT count(*) FROM pg_prepared_xacts;
   -- 결과 0 필수
   ```
   - `postgres`는 유지하므로 `INV`(직전 표지 앞 모든 해지 트랜잭션 완료)를 시계 가정 없이 보장한다.

### 3.2 Dump와 대장 일관성 (백업 · barrier · 04 보유기간 연계)
1. **M2 최초 준비 (아직 M1인 현재 서버 적용)**:
   - `ALERTS_ENABLED=true` 전환 전 `/var/lib/gongchae-bell/erasure` (`1000:ubuntu 0750`)에 초기 표지 1줄 `[M_1]`이 담긴 `erasure.log`(`1000:ubuntu 0640`)를 생성하고 `fsync`한다.
   - `/opt/gongchae-bell/backup/backup.env`에 `REQUIRE_ERASURE_LOG=true` 설정.
2. **일일 백업 (`backup.sh`)**:
   - 순서 유지: `stage_postgres_dump`(`pg_dump -Fc` + `pg_restore --list`) → `stage_erasure_log`(권한/트레일링 `\n` + 가능하면 `EACCES` 분리 실패) → `backup-meta.txt` → `restic backup`.
   - **04 보유기간 적용**: OCI와 PC 모두 `--keep-daily 14` 대신 실행 시점 UTC 기준 `snapshot.time < now_utc - 14d`인 스냅샷 ID를 `restic snapshots --json`으로 선정해 `restic forget <ids>` + `restic prune` 수행(0개 허용, PC는 pull 실패 시에도 로컬 만료 정리를 별도 실행하며 14일 초과 OCI 스냅샷은 copy하지 않음).
3. **Barrier 정리 (앱·백업 정지 + 세션 0 확인 후)**:
   - `L` strict 검증 + 중복 표지 검사.
   - Blocker 4 가드 통과 시(마지막 줄이 표지가 아니고, 마지막 표지 `M_prev` 앞 해지 줄 ≥ 1이면 `M_prev` 앞 절단; 해지 줄 0이면 절단 없이 보존) 끝에 새 UUIDv8 표지 `M_k`를 붙인 내용을 같은 디렉터리 임시 파일(`1000:ubuntu 0640`)에 기록 → `fsync` → 원자 `rename` → 디렉터리 `fsync`.
   - 재검증(strict + 보존 구간 바이트 일치 + 마지막 줄 `M_k`) → `backup.lock` 해제 → 필요 시(첫 S2 전환 직후 또는 절단 직후) 앱 정지 상태에서 `backup.sh run` 1회 실행 → 앱·백업 타이머 재개.
   - **Barrier 주기 권장 관계**: 주기 `C >= 14일`(구체 주기는 조율자/사용자 운영 일정으로 확정)이면 14일 보관 창 안의 백업이 barrier 때문에 조기 거부되지 않는다.

### 3.3 최신 대장 재적용 (PR #27 격리/전환 절차 + 게이트 내장)
1. **분기 M1 (유일한 생략 예외)**: `SUB_COUNT == 0` **AND** `erasure_included == false` **AND** M2 미개시 근거(호스트 대장 없음) 3조건 동시 충족 시에만 생략.
2. **M2 리허설 · 전환 전 1차 격리 검증**:
   - `B` = 복원한 스냅샷의 `"$RESTORE_WORK/erasure.log"`(`erasure_included=true` 필수, 누락 시 즉시 실패).
   - `L` = 호스트 생존(분기 A)이면 살아 있는 `/var/lib/gongchae-bell/erasure/erasure.log` 사본, VM 영구 소실(분기 B)이면 가장 최신 정상 스냅샷의 `erasure.log`.
   - 격리 컨테이너(`--internal`, `ALERTS_ENABLED=false`, `COLLECTOR_ENABLED=false`)에 `B`를 `:ro`로, `L`을 `:rw`(`1000:1000`)로 마운트해 `--alerts.erasure-reapply=true --alerts.erasure-bundle-log=/bundle/erasure.log` 실행.
   - 로그에서 `ERASURE_GATE_PASSED` + `ERASURE_REAPPLY_COMPLETE deleted=N` + `exit 0` 확인 후 PR #27 §7.1 6.1의 `REMAINING_UNSUBS=0` 대조 통과.
3. **실제 장애 복구 전환 직전 최종 재적용 (PR #27 6.2 강화)**:
   - §3.1 절차로 **앱 양색·백업 정지 + DB 세션 0 확인** 완료 후, 살아 있는 호스트 `/var/lib/gongchae-bell/erasure`와 `B`를 마운트해 동일 게이트+재적용 1회 추가 실행 및 `REMAINING_UNSUBS=0` 재확인. (호스트 `L`은 절대 백업 `B`로 덮어쓰지 않는다.)

### 3.4 Sender / Item 보존 및 발송 재개 절차 (격리 DB 전환 전 실행)
1. **복원 DB 미완료 작업 일괄 `UNCERTAIN` 마감 + `notification_item` 보존** (앱 기동 전, `AlertsMaintenance` 실행 전, 격리 복원 DB에서 단일 트랜잭션 SQL로 실행):
   ```sql
   BEGIN;
   -- 1) 결과 미확정 시도 기록 마감
   UPDATE notification_attempt
      SET outcome = 'RESTORE_UNCERTAIN',
          finished_at = COALESCE(finished_at, now())
    WHERE outcome IS NULL;

   -- 2) 백업 시점 잔류 PENDING/RETRY/SENDING (비동기·동기 전체)을 UNCERTAIN으로 마감
   --    V5 CHECK: status='UNCERTAIN' => finished_at IS NOT NULL, last_error varchar(30)
   --    notification_item은 삭제하지 않고 그대로 보존 -> 재매칭 차단
   UPDATE notification
      SET status = 'UNCERTAIN',
          finished_at = now(),
          claimed_at = NULL,
          last_error = 'RESTORE_UNCERTAIN'
    WHERE status IN ('PENDING', 'RETRY', 'SENDING');
   COMMIT;
   ```
   - `FAILED`/`CANCELLED`를 쓰지 않으므로 `#7` `DeliveryLedger`의 item 반환 규칙에 걸리지 않으며, 30일 뒤 `AlertsMaintenance`가 완료 `notification`을 지워도 `notification_item.notification_id`는 `ON DELETE SET NULL`이라 dedup 항목이 유지된다.
2. **발송 재개 전 키·슬롯·첫 수집 처리 (실제 코드 한계 명시)**:
   - 키 호환(`ALERTS_WEBHOOK_KEY_ID`·`ALERTS_WEBHOOK_KEY`·`ALERTS_HMAC_KEY`·`ALERTS_TOKEN_KEY`) 및 호스트 `erasure.log`(`1000:ubuntu 0640`) 배치 확인.
   - **당일 `collect_run` 슬롯(`DIGEST`, `DEADLINE`)**: 백업(03:00 KST) 이후 같은 날 08:00/09:00 KST를 지나 복원한 경우, 오늘 `DISCORD_DEADLINE`(D-3) 중복을 막으려면 발송 재개 전 `('DEADLINE', kstToday)` 슬롯 사전 마킹이 유효하다(`DEADLINE`은 다음 날 D-2로 자연 소멸). 반면 `('DIGEST', kstToday)` 마킹은 당일 재발송만 막으며, 덤프에 `notification_item(kind='NEW')`이 없는 `OPEN` 공고는 다음 날 `EMAIL_DIGEST`에서 다시 포함된다.
   - **첫 보충 `REGULAR` 수집**: `ALERTS_ENABLED=false`로 먼저 돌리면 백업~장애 사이 기발송 공고의 **`DISCORD_NEW` 중복만** 차단한다(장애 중 신규 공고의 `DISCORD_NEW` 누락과 트레이드오프; `EMAIL_DIGEST` `NEW` 중복은 차단하지 못함).
   - **관측 기록**: 위 덤프 밖 공백 구간의 중복은 `UNKNOWN` 시도를 거치지 않아 `alerts_delivery_duplicate_risk*`에 잡히지 않으므로, 복원 포스트모템에 `backup_utc ~ 장애 시각` 공백을 별도 기록한다.

---

## 4. 조율자 결정용 최소 변경 범위

| 대상 | 최소 변경 내용 | 필요성 |
|---|---|---|
| `src/.../alerts/ErasureJournal.java`<br>`src/.../alerts/ErasureReapplyCommand.java` + 단위 테스트 | UUIDv8 표지 판별, 중복 표지 거부, `verifyCoverage(B, L)`(S0/S1/S2 바이트 prefix, `B`는 읽기 전용), `--alerts.erasure-bundle-log` 필수 게이트 및 `ERASURE_GATE_PASSED` 로그, `DELETE` 대상에서 표지 제외 | **필수** (Blocker 2 우회로·파서 드리프트 차단, 약 80~120줄) |
| `deploy/backup/backup.sh` + 기존 백업 테스트 | (1) `04` 정책 반영: 실행 시각 UTC 14일 초과 스냅샷 ID 명시 `forget` + `prune`(0개 허용)<br>(2) `stage_erasure_log` 디렉터리 `EACCES`와 `ENOENT` 분리(`ERASURE_LOG_PERM_FAIL`) | **필수** (04 정책 + Blocker 3A) |
| `deploy/backup/erasure-barrier.sh` (또는 소형 운영 스크립트 1개) | `ubuntu` `backup.lock` 유지 + 양색 프로필 정지/세션 0 검증 확인 + UUIDv8 표지 생성 + Blocker 4 가드 + `1000:ubuntu 0640` 원자 `mktemp/fsync/rename/dir-fsync` | **필수** (Blocker 3B·3C·4·5, 새 데몬/서비스 없음) |
| `deploy/backup/` PC pull 스크립트 | `04` 정책(로컬 14일 초과 선삭제·pull 실패 독립 실행·초과 OCI 스냅샷 미복사) + `prune` 검증 | **필수** (04 정책) |
| `docs/ops/backup-restore.md`, `docs/adr/0012`·`0013`, `docs/ops/alerts.md` | M2 초기 `[M_1]` 생성 + `1000:ubuntu 0640` 규약, PR #27 M1 3조건 예외 유지(Blocker 1), 게이트 내장 재적용 명령, §3.4 `UNCERTAIN` 마감 SQL 및 중복 한계 반영 | **필수** (문서-코드 일치) |

---

## 5. 확인 범위 (직접 확인 vs 미실행)
- **직접 확인**: `origin/main` (`c4465f5`)의 위 Java/SQL/셸/Compose/문서 원문을 `git show`로 직접 대조.
- **미실행**: 위 최소 변경 구현, JDK 21에서의 `ErasureJournal`/`ErasureReapplyCommand` 표지·게이트 통합 테스트, `ubuntu`↔`1000` 그룹 권한 실서버 적용, barrier crash 재현, PC pull 실실행. (현재 설계/검수 단계 범위 밖)
