# #17 독립 설계안 (1라운드) · agy

- **작성자 / 일자**: 추가 agy / 2026-11-19
- **대상**: GitHub [#17](https://github.com/Lcjam/gongchae-bell/issues/17) (`ops: OCI 밖 백업 pull과 해지 보존 복원 리허설`)
- **제약 준수**: 실제 source·설정·서버·계정·env·키·provider 수정·접근 없음. 이번 라운드 타 에이전트 독립안 미열람. 채택 전 보유기간·법적 고지 확정 없음.
- **성격 구분**: 각 절에서 **확인된 사실(저장소 코드·문서·공식 문서)** 과 **기술적 제안(작은 실행안)** 을 분리해 표기한다.

---

## 1. 근거 (코드 · 문서 · 공식 문서 확인)

### 1.1 저장소 내 확인 사실
- **OCI 백업 구현(`main` @ `52c172c`)**:
  - `deploy/backup/systemd/gongchae-backup.timer`: 매일 `03:00:00 Asia/Seoul` 실행(`Persistent=true`), `gongchae-backup.service`는 `TimeoutStartSec=30min`, `User=ubuntu`.
  - `deploy/backup/backup.sh`: `flock` 배타 잠금(`/opt/gongchae-bell/backup/backup.lock`) 아래 실행 중인 Compose `postgres` 컨테이너에서 `pg_dump -Fc` 수행 → `pg_restore --list`로 TOC 비어 있지 않음 검증 → `/var/lib/gongchae-bell/erasure/erasure.log` 스테이징 → `backup-meta.txt`(`backup_utc`, `dump_bytes`, `dump_sha256`, `toc_entries`, `erasure_included`, `erasure_bytes`, `erasure_lines`, `keep_daily`) 생성 → `restic backup --tag gongchae-bell --tag daily` → `restic forget --tag gongchae-bell --group-by host,tags --keep-daily "$BACKUP_KEEP_DAILY" --prune` → 성공 시 `HEALTHCHECKS_BACKUP_URL`, 실패 시 `/fail` 전송(로그 마스킹 포함).
  - `BACKUP_KEEP_DAILY` 기본값은 `14`이나, 코드 주석과 `docs/ops/backup-restore.md` §2가 명시하듯 이는 **성공한 서로 다른 날짜 14개** 기준이며 달력 14일 상한을 보장하지 않는다.
- **해지 대장 · 복원 재적용 구현**:
  - `src/main/java/kr/gongchae/bell/alerts/ErasureJournal.java`: 운영자가 미리 생성한 빈 `erasure.log`에 대해서만(`CREATE` 없음) `synchronized (writers)` + `FileChannel.open(path, READ, WRITE)` + `channel.lock()` 아래 줄바꿈 보호, 전체 바이트 쓰기, `channel.force(true)`, POSIX `syncDirectory()`를 수행한다.
  - `readStrict()`: 파일 없음(`ERASURE_LOG_MISSING`), 마지막 줄 개행 누락(`ERASURE_LOG_TORN_LAST_LINE`), 잘못된 UUID/ISO-8601 줄(`ERASURE_LOG_BAD_LINE_<n>`)이면 `CorruptJournal`을 던지고 아무것도 반환하지 않는다(fail-closed).
  - `src/main/java/kr/gongchae/bell/alerts/ErasureReapplyCommand.java`: `--spring.main.web-application-type=none --alerts.erasure-reapply=true`로 실행 시 `journal.readStrict()` 통과 후 `DELETE FROM subscription WHERE id IN (:ids)`(1000건 배치)를 단일 트랜잭션으로 수행하고 `ERASURE_REAPPLY_COMPLETE deleted=<N>`을 기록한다.
  - **현재 없는 기능**: `erasure.log` compaction(정리) 코드, 덤프 생성 시각과 허용 복원 최소시점 비교 게이트, 백업 동봉 대장 vs 최신 대장의 연속 커버리지 검증 로직.
- **암호화 키 분리**:
  - `src/main/java/kr/gongchae/bell/alerts/AlertsCrypto.java`, `AlertsProperties.java`: `ALERTS_TOKEN_KEY`, `ALERTS_HMAC_KEY`, `ALERTS_WEBHOOK_KEY`(AES-256-GCM 32바이트, AAD=구독 UUID), `ALERTS_WEBHOOK_KEY_ID`는 환경변수로만 주입되며 DB·백업에 포함되지 않는다.
- **고지 현황**:
  - `src/main/resources/templates/privacy.html`: 해지 대장 정리 시점은 현재 `미정 (운영 시작 전 설정)`("모든 백업 사본의 삭제가 확인된 뒤 정리"), 백업은 "설계상 14일 보관 후 삭제"로 표기되어 있다.
- **선행 검토(`collab/2026-11-16-m2-privacy/sol-followup-review.md`)**:
  - 단순 숫자 맞춘 15일/14일 자동 정리는 불충분하며, 허용 복원 최소시점·스냅샷 시각 검증·대장 연속 커버리지·교체용 고정 잠금 파일·crash 경계를 충족해야 함을 지적했다.

### 1.2 외부 공식 문서 확인 사실 (확인일: 2026-11-19)
| 항목 | 확인된 동작 | 공식 출처 |
|---|---|---|
| `restic copy` 초기화 및 복사 | 사본 저장소는 최초 `restic init --from-repo <원본> --copy-chunker-params`로 청커 파라미터를 복사해야 중복 제거가 유지된다. `restic copy`는 `--host`, `--path`, `--tag`, 스냅샷 ID 필터만 받으며 `--keep-*` 보관 옵션은 받지 않는다. 중단 후 재실행 시 이어받는다. | [restic Working with repositories](https://restic.readthedocs.io/en/stable/045_working_with_repos.html#copying-snapshots-between-repositories) |
| `restic copy` 잠금·환경변수 | 대상 저장소에는 쓰기 잠금을, 원본 저장소에는 읽기/공유 성격의 접근을 하며 `RESTIC_FROM_REPOSITORY` / `RESTIC_FROM_PASSWORD`로 원본 인증을 분리한다. 단 `prune`은 저장소에 배타 잠금(exclusive lock)을 잡으므로 OCI `03:00 KST` 백업+prune과 겹치지 않게 pull 시각을 분리해야 한다. | [restic Working with repositories](https://restic.readthedocs.io/en/stable/045_working_with_repos.html), [restic FAQ / Locking](https://restic.readthedocs.io/en/stable/075_faq.html) |
| `restic forget` 보관 기준 | `--keep-daily N`은 일일 스냅샷 **개수**다. `--keep-within-daily <기간>` 역시 현재 벽시계(`now`)가 아니라 **가장 최신(latest) 스냅샷 시각 기준** 상대 기간이다. `forget`은 메타데이터만 지우며 실제 데이터 삭제는 `--prune`이 필요하다. | [restic Removing backup snapshots](https://restic.readthedocs.io/en/stable/060_forget.html) |
| `pg_dump -Fc` 스냅샷 일관성 및 TOC 시각 | `pg_dump`는 실행 시작 시점의 단일 트랜잭션 스냅샷으로 내부 일관성 있는 덤프를 비차단으로 생성하며, `pg_restore --list` 헤더에 `; Archive created at ...`을 기록한다. | [PostgreSQL 17 `pg_dump`](https://www.postgresql.org/docs/17/app-pgdump.html), [PostgreSQL 17 `pg_restore`](https://www.postgresql.org/docs/17/app-pgrestore.html) |
| Healthchecks.io 실패·미수신 감지 | 작업 실패 시 `/fail` 또는 종료코드 엔드포인트로 즉시 경보할 수 있고, 장비 전원 꺼짐 등으로 실행 자체가 안 되면 설정된 주기+유예(Grace) 초과로 미수신 경보가 울린다. | [Healthchecks.io Docs](https://healthchecks.io/docs/bash/) |
| Windows ACL · 백그라운드 실행 | `icacls`로 상속 제거(`/inheritance:r`) 및 소유자·SYSTEM 전용 권한 부여가 가능하며, `New-ScheduledTaskSettingsSet -Hidden` 등으로 작업 스케줄러 백그라운드 실행을 구성한다. | [Microsoft Learn `icacls`](https://learn.microsoft.com/en-us/windows-server/administration/windows-commands/icacls), [ScheduledTasks](https://learn.microsoft.com/en-us/powershell/module/scheduledtasks/new-scheduledtasksettingsset) |

---

## 2. 독립 설계안 (작은 실행안)

새 원격 SaaS, 유료 자원, 추가 VM 없이 기존 `restic` + `Healthchecks.io` + `ErasureJournal` 골격을 최소 확장하는 방향을 제안한다.

### 2.1 OCI 밖 NAS·PC pull 최소 경로 (외부 포트 개방 0)
- **네트워크 구조**: 사용자 NAS 또는 PC가 **아웃바운드 HTTPS(443)** 로만 OCI Object Storage S3 호환 버킷(`RESTIC_FROM_REPOSITORY`)에 접속해 `restic copy`를 수행한다. 집 공유기 인바운드 포트 포워딩, SSH 역터널, VPN 서버는 두지 않는다.
- **최소 권한 · 키 분리**:
  - 가능하면 OCI 쪽에 **pull 전용 버킷 읽기 전용(Read-Only) Customer Secret Key**를 별도 발급해 사본 장비 유출 시 OCI 원본 삭제 위험을 막는다(제안).
  - 로컬 사본 저장소는 OCI와 **다른 `RESTIC_PASSWORD`**(또는 최소한 분리 관리)로 초기화하며 두 비밀번호 모두 사용자 비밀번호 관리자에 백업한다.
- **장비 유형별 최소 배치안** (실제 장비는 사용자 입력 후 택1):
  1. **Linux NAS (권장 1순위, 상시 가동 가정)**:
     - 저장 경로: `/var/backups/gongchae-bell/restic-repo` (`0700`, 전용 계정 소유, SMB/NFS 공유 경로 밖).
     - env 경로: `/etc/gongchae-bell/pull.env` (`0600`, OCI·로컬 repo 키 및 별도 `HEALTHCHECKS_PULL_URL`).
     - 스케줄: `systemd` timer `04:00 KST` (`Persistent=true`) + `flock` 잠금. OCI `03:00 KST` 백업/prune(`TimeoutStartSec=30min`)과 1시간 분리.
  2. **Windows 개인 PC (대안)**:
     - 저장 경로: 클라우드 동기화(OneDrive/Dropbox 등)가 걸리지 않은 로컬 경로(예: `%LOCALAPPDATA%\gongchae-bell\backup\restic-repo` 또는 전용 드라이브 경로).
     - 접근 제한: `icacls`로 상속을 끄고 현재 사용자 + `SYSTEM`에만 Full Control 허용(`Users`/`Authenticated Users` 제거). `pull.env`도 동일 ACL 적용.
     - 스케줄: Windows Task Scheduler(`04:00 KST` 또는 로그온/가동 시 실행, `StartWhenAvailable` 활성, Hidden 실행, 스크립트 1회 대기/실행 상한 관리).
- **pull 실행 순서 (작은 스크립트 `deploy/backup/pull.sh` / `.ps1` 추가 제안)**:
  1. env 권한 검사(group/other 접근 차단) + 로컬 락 획득.
  2. `restic copy --tag gongchae-bell --tag daily` (원본 OCI → 로컬 사본).
  3. 사본 저장소 `restic forget --tag gongchae-bell --group-by host,tags --keep-daily <N> --prune` + 확정 시 wall-clock 상한 정리(§2.2).
  4. `restic snapshots --tag gongchae-bell --compact`로 사본 최신 ID·시각 확인 후 **별도 `HEALTHCHECKS_PULL_URL`** 에 성공 ping(실패 시 `/fail`, 비밀값 마스킹은 `backup.sh`와 동일 규칙).
  5. 오프라인 PC는 꺼진 동안 `/fail` 대신 **미수신(Period + Grace) 경보**로 "며칠째 사본 갱신이 끊겼는지"를 관측한다.

### 2.2 `keep-daily 14` · 달력 14일 · prune 실패 · 오프라인 PC · 수동 사본 정합성
- **사실의 정직한 분리**: 현재 구현(`BACKUP_KEEP_DAILY=14`)은 "백업 성공 14일치"이지 "달력 14일 경과 즉시 전 사본 소멸"이 아니다. `restic`의 `--keep-within-daily 14d` 역시 최신 스냅샷 기준이므로 백업 중단 기간만큼 과거 스냅샷이 더 남는다.
- **최소 보완 제안**:
  1. **오프라인 PC 복귀 시 정리 선행 또는 복사 직후 강제 정리**: 장기간 꺼져 있던 PC가 켜지면 `restic copy` 직후 사본 측 `forget --prune`이 반드시 실행되어야 오래된 사본이 남지 않는다(단, 네트워크 실패로 `copy`가 실패하더라도 **로컬 만료 사본 정리**는 분리 실행할지 선택 필요 — 제안: 로컬 만료 정리는 `copy` 성공 여부와 무관하게 독립 단계로 두어 오프라인 복귀 PC에 오래된 사본이 계속 잠자는 일을 막는다).
  2. **달력 기준 만료 상한(`MAX_CALENDAR_AGE_DAYS`) 도입 여부**: 만약 처리방침에서 "백업은 생성 후 달력 `D`일 경과 시 파기"를 약속하려면, `restic snapshots --json`의 `time` 필드를 읽어 `now - D일` 이전 스냅샷 ID를 명시적으로 `restic forget <ids> --prune`해야 한다. 단, 이 경우 **14일 이상 백업이 전면 중단되면 마지막 1개 사본까지 지워져 복원점 0개가 되는 트레이드오프**가 생긴다. 반대로 최신 1개는 남긴다면 "장애 시 14일 초과 잔존 가능"을 고지에 적어야 한다. 이는 사용자 결정 사항이다(§5 쟁점).
  3. **수동 사본 금지 규율**: 리허설·긴급 점검 시 `restic restore`로 푼 평문 덤프와 임시 디렉터리는 `0700` 경로에만 두고 작업 직후 `rm -rf`로 즉시 파기한다. 별도 USB·클라우드 폴더 수동 복사는 금지한다.
  4. **채택 전 고지 유지**: 기간·예외 규칙이 확정되기 전까지 `privacy.html`의 미정 상태 및 임의의 법정 유예 수치 미기재 원칙을 유지한다.

### 2.3 허용 복원 최소시점 · 덤프 시각 검증 · 해지 대장 연속 커버리지 · 보유/정리 정책
복원 게이트는 **모두 fail-closed**로 설계한다(의심스러우면 DB 복원은 연습할 수 있어도 **`ALERTS_ENABLED=true` 발송 재개는 절대 허용하지 않음**).

1. **덤프 실제 스냅샷 시각 검증 (작은 변경)**:
   - `backup.sh`의 `stage_postgres_dump()`가 `pg_dump -Fc`를 호출하기 **직전**의 호스트 UTC 시각(`dump_started_utc`)을 `backup-meta.txt`에 추가 기록하고, `pg_dump` 컨테이너 실행 환경에 `TZ=UTC`를 주어 `pg_restore --list` 헤더의 `; Archive created at ...`도 UTC로 고정한다.
   - 복원 검증 시 파일 mtime이나 restic 업로드 시각이 아니라 **`backup-meta.txt`의 `dump_sha256` 일치 + `dump_started_utc` + `pg_restore --list` 헤더 시각 파싱**으로 덤프 시점 `T_dump`를 확정한다. 하나라도 누락·불일치·파싱 실패 시 즉시 거부.
2. **허용 복원 최소시점 (`T_min_restore`) 검사**:
   - `T_dump < T_min_restore` (예: 오프라인 PC의 너무 오래된 사본)이면 발송 재개를 거부한다. `T_min_restore`는 대장이 보장할 수 있는 커버리지 시작 시각(`coverage_since_utc`) 이상이어야 한다.
3. **해지 대장 연속 커버리지 검증**:
   - **정리 전(append-only) 구간 — 즉시 적용 가능한 최소 검증**: 호스트 `erasure.log`를 절대 잘라내지 않는 한, 덤프에 동봉된 `snapshot/erasure.log`는 복원에 사용할 최신 `erasure.log`의 **바이트 prefix**여야 한다(`cmp -n <snapshot_erasure_bytes> snapshot/erasure.log latest/erasure.log`). 이 1줄 검증만으로도 "누군가 빈 파일로 새로 만들었거나, 다른 환경의 대장을 잘못 가져왔거나, 중간 줄이 유실된" 사고를 잡을 수 있다.
   - **정리 도입 시 — 메타파일 연동 검증**: 정리를 도입하면 prefix가 깨지므로, 대장과 같은 디렉터리에 `erasure.meta`(고정 잠금 아래 함께 원자 교체)를 두어 `coverage_since_utc`를 기록한다. 재개 조건은 `coverage_since_utc <= T_dump` AND (덤프 동봉 대장의 `instant >= coverage_since_utc`인 구간이 최신 대장에 포함됨)이다. 이를 증명 못 하면 발송 재개를 거부한다.
4. **대장 보유 및 정리 정책 (단계적 제안)**:
   - **권장 최소안(1단계, M3 초기/#17)**: 대장 한 줄은 약 60~70바이트로 1만 건 해지에도 1MB 미만이다. 모든 사본(OCI·NAS/PC)의 실제 `prune` 관측과 `T_min_restore`가 운영에서 안정적으로 검증되기 전에는 **자동 정리를 켜지 않고**, 정리는 "운영 확인 후 정지 상태 수동 정리" 또는 "조건 충족 시 별도 이슈로 구현"한다.
   - **정리 구현 시(2단계) 필수 조건**: 어떤 해지 시각 `t`도 `t <= min(OCI 가장 오래된 생존 사본 시각, NAS/PC 가장 오래된 허용 복원점, T_min_restore)`이 **실제 사본 조회(`restic snapshots`)로 확인된 경우**에만 정리 대상에 포함한다.

### 2.4 대장 정리/교체 동시성 및 Crash 경계 (구현 시 필수 규약)
현재 `ErasureJournal.java`는 `erasure.log` 파일 자체에 `FileChannel.lock()`을 건다. 이 구조에서 임시 파일을 `ATOMIC_MOVE`로 `erasure.log` 위에 덮어쓰면 **구 inode를 열어 잠금 대기 중이던 다른 앱 컨테이너가 언링크된 옛 inode에 `append()`를 써서 새 해지가 유실**된다.

이를 막기 위한 작은 고정 규약(정리를 구현할 경우 `ErasureJournal`과 정리 도구가 함께 준수):
1. **고정 잠금 파일 도입**: 절대 교체·삭제하지 않는 `/var/lib/gongchae-bell/erasure/erasure.lock`(운영자가 사전 생성, `CREATE` 없음)에 대해 `append`, `readStrict`, `compact` 모두가 배타 `FileChannel.lock()`을 획득한다.
2. **정리 원자 교체 순서 (`erasure.lock` 보유 중)**:
   - ① `erasure.log` 전체를 `readStrict` 로직으로 파싱 검증. 손상·잘린 줄이 있으면 **즉시 실패 종료(유효 줄만 추려 덮어쓰지 않음)**.
   - ② `instant.isBefore(cutoff)`인 줄만 제외(경계 동일 시각 및 clock 역행 방어를 위해 엄격 미만 `<` 및 안전 마진 적용).
   - ③ `erasure.log.tmp`에 완전 쓰기 루프 → `channel.force(true)` (및 `erasure.meta.tmp`에 새 `coverage_since_utc` 기록 → `force(true)`).
   - ④ `Files.move(tmp, target, ATOMIC_MOVE, REPLACE_EXISTING)` 원자 교체.
   - ⑤ 교체 후 `syncDirectory(parentDir)` 실행 → 그 뒤에야 `erasure.lock` 해제.
3. **Crash 경계**:
   - 쓰기(`③`) 중 중단 → 원본 `erasure.log` 무손상, 고아 `.tmp`만 남음(무시/삭제 가능).
   - 교체(`④`) 직후 디렉터리 fsync(`⑤`) 전 중단 → POSIX 원자 rename으로 인해 디스크에는 완전한 구 파일 또는 `force(true)`된 신 파일 중 하나만 존재한다.
   - 파일 `mtime`/`birthtime`은 대장 커버리지 근거로 사용하지 않는다.

### 2.5 발송 중지 상태 격리 PG 복원 · 키 검증 · 재적용 · 기록 절차
**원칙**: 실행 중인 운영 Compose DB(`postgres_data`)에 직접 복원하지 않는다.

1. **격리 환경 기동**:
   - 별도 Compose 프로젝트명(`-p gongchae-restore-test`)과 일회용 볼륨으로 `postgres:17` 컨테이너를 기동한다.
   - 앱 실행/검증 환경은 `ALERTS_ENABLED=false`, `COLLECTOR_ENABLED=false`, `RESEND_API_KEY=""`로 고정해 외부 API·이메일·웹훅 발송을 원천 차단한다.
2. **외부(NAS/PC) 사본에서 복원물 추출**:
   - OCI 버킷이 아닌 **NAS/PC 로컬 restic 저장소**에서 `restic snapshots` 확인 후 `<SNAPSHOT_ID>`를 `0700` 임시 디렉터리에 `restic restore`한다.
3. **사전 무결성·시각·커버리지 검증**:
   - `sha256sum gongchae-bell.dump` == `backup-meta.txt`의 `dump_sha256` 확인.
   - `pg_restore --list` 헤더 시각 및 `dump_started_utc` 확인 → `T_min_restore` 이상인지 확인.
   - 덤프 동봉 `erasure.log`와 재적용할 최신 `erasure.log`의 연속성(prefix 또는 `coverage_since_utc`) 확인.
4. **격리 DB 복원 & 시간 측정**:
   - `pg_restore --clean --if-exists --no-owner --exit-on-error` 실행 및 소요 시간 기록.
5. **키 검증 (DB 분리 키 포함 복구 시험, PRD §6)**:
   - 비밀번호 관리자에 보관된 `ALERTS_WEBHOOK_KEY` / `ALERTS_WEBHOOK_KEY_ID` / `ALERTS_TOKEN_KEY` / `ALERTS_HMAC_KEY`를 격리 환경에 주입해, 복원된 DB의 `subscription.webhook_enc`가 `AlertsCrypto.decryptWebhook()`으로 실제 복호화되는지(잘못된 키일 때 실패하는지) **외부 웹훅 요청 없이** 검증한다.
6. **최신 해지 대장 재적용 & 데이터 검수**:
   - `ALERTS_ENABLED=false ALERTS_ERASURE_LOG=<최신 대장> java -jar gongchae-bell.jar --spring.main.web-application-type=none --alerts.erasure-reapply=true` 실행 → `ERASURE_REAPPLY_COMPLETE deleted=N` 및 `exit 0` 확인.
   - SQL로 `subscription` 내 재적용 대상 UUID 0건, 공고 건수·수집 최신 시각·`flyway_schema_history` 일치 확인.
7. **임시 평문 삭제 및 실측 기록**:
   - 임시 복원 디렉터리 `rm -rf` 후 `docs/ops/backup-restore.md` §8 표에 일시, 복원 환경, snapshot ID, 덤프 시점, 단계별 소요 시간, `deleted=N`, 키 검증 결과를 적고 **그 실측치를 근거로 RTO/RPO 목표를 설정**한다.

### 2.6 VM 영구 소실 한계와 성립 조건
- **VM 영구 소실 시 유실 한계(ADR-0013)**: OCI VM과 호스트 `/var/lib/gongchae-bell/erasure/erasure.log`가 함께 소실되면, **마지막 성공 일일 백업(03:00 KST) 이후 소실 시점까지(최대 약 24시간 + 직전 백업 실패 시 공백 전체)** 의 해지 기록은 어느 백업에도 없다. 복원 후 해당 구간 해지자에게 알림이 재발송될 수 있으며, 이를 개인정보처리방침과 `docs/ops/backup-restore.md`, `docs/runbook.md`에 숨기지 않고 명시한다.
- **세 지점(해지 대장 · 모든 복원점 · 외부 사본) 간 성립 조건**:
  1. **커버리지**: 모든 복원 재개는 `erasure_coverage_since_utc <= T_dump`를 만족하는 유효 대장 재적용 이후에만 허용된다.
  2. **정리 선행 조건**: 대장 항목 `(uuid, t)`는 `t` 이전 시점의 복원 가능한 모든 사본(OCI + NAS/PC + 수동 사본 0건)이 제거·차단됨이 확인된 뒤에만 삭제할 수 있다.
  3. **비밀값 공생존**: `RESTIC_PASSWORD`(OCI·로컬)와 `ALERTS_WEBHOOK_KEY` 등 앱 암호화 키는 VM 디스크·DB 백업과 분리된 비밀번호 관리자에서 함께 복구 가능해야 한다.

---

## 3. 예상 실패 경계

| 경계 / 시나리오 | 예상 실패 증상 | 설계 대응 (fail-closed) |
|---|---|---|
| OCI `03:00` prune 중 NAS/PC pull 동시 실행 | OCI 저장소 배타 잠금 충돌로 `restic copy` 실패 | pull 스케줄을 `04:00 KST` 이후로 분리, 잠금 충돌 시 재시도 및 실패 시 `/fail` |
| PC 장기 오프라인 (예: 20일 꺼짐) | 로컬 `forget --prune` 미실행 → 만료된 구 사본 잔존 | 복귀 시 로컬 사본 정리 실행 + 복원 게이트가 `T_dump < T_min_restore` 거부 + Healthchecks 미수신 경보 |
| `forget` 성공 후 `prune` 실패/중단 | 스냅샷 목록엔 없으나 실제 데이터 팩이 버킷/디스크에 잔존 | `prune` 실패 시 non-zero 종료 및 `/fail` 전송, 다음 실행 시 `prune` 재수행·용량/스냅샷 요약 확인 |
| 덤프 시각 불명 또는 파일 mtime 변조 | `T_min_restore` 및 대장 커버리지 판정 불가 | `backup-meta.txt` SHA-256 + `dump_started_utc` + `pg_restore --list` UTC 헤더 모두 검증, 1개라도 실패 시 재개 거부 |
| 대장 missing / 빈 파일 덮어쓰기 / 잘린 줄 | 최근 해지 미삭제 상태로 알림 재개 위험 | `ERASURE_LOG_MISSING` / `TORN` / `BAD_LINE` fail-closed + 덤프 동봉 대장 prefix(또는 `coverage_since_utc`) 불일치 시 차단 |
| `erasure.log` compaction 중 앱 `append()` 경합 | 구 inode에 쓴 해지가 원자 교체 후 소멸 | `erasure.lock` 고정 잠금 파일 공유 + `readStrict` → 완전 쓰기/`force` → `ATOMIC_MOVE` → `syncDirectory` |
| `ALERTS_WEBHOOK_KEY` 분실/키 ID 불일치 | DB는 복원돼도 Discord 웹훅 복호화 불가 | 리허설에서 비밀번호 관리자 키로 실제 복호화 검증 필수 |
| VM 영구 소실 | 마지막 성공 백업~소실 사이(최대 약 24시간+) 해지 유실 | 대장 파일 특성상 완전 복구 불가 → 고지 정합성으로 한계 명시 |

---

## 4. 검증 계획 (실제 구현·리허설 단계용, 본 라운드 미실행)

1. **OCI 밖 pull 및 관측 검증**:
   - 실제 NAS/PC 환경에서 `restic init --copy-chunker-params` → `restic copy` → 사본 `forget --prune` → 별도 `HEALTHCHECKS_PULL_URL` 성공·`/fail`·미수신 경보까지 실측 확인.
   - 저장소 디렉터리/env 파일 권한(`0700`/`0600` 또는 Windows `icacls`) 점검으로 타 계정 접근 차단 확인.
2. **Fail-closed 단위/통합 테스트**:
   - `T_dump < T_min_restore`인 오래된 사본(예: 20일 전 NAS 덤프)으로 복원/재개 시도 시 거부됨을 검증.
   - `pg_restore --list` 헤더 누락·SHA-256 불일치·`dump_started_utc` 변조 시 거부 검증.
   - 대장 missing, 마지막 줄 잘림, 잘못된 줄, 덤프 동봉 대장과 최신 대장 prefix/커버리지 불일치 시 `deleted=0` + non-zero exit 검증.
   - (대장 정리 구현 시) `erasure.lock` 경합 하에 `append()` vs `compact()`, `cutoff` 직전/직후, clock 역행, `tmp` 쓰기 중단 및 교체 직전·직후 crash 복구 테스트.
3. **격리 복원 리허설 (실제 스키마·합성/검수 데이터 또는 운영 스냅샷)**:
   - 외부 발송 차단(`ALERTS_ENABLED=false`, `RESEND_API_KEY=""`) 상태에서 NAS/PC 사본 `restic restore` → 격리 PG 복원 → `ALERTS_WEBHOOK_KEY` 복호화 확인 → `erasure-reapply` 실행(`deleted=N`) → DB/공고/구독 상태 검수 → `docs/ops/backup-restore.md` 기록.

---

## 5. 쟁점 및 미확정 운영 입력 (사용자 / 3자 검토 필요)

1. **실제 pull 장비 확정**: Linux NAS인지 Windows PC인지, 상시 가동 여부 및 허용 오프라인 일수(Healthchecks Grace 시간 결정에 필요).
2. **백업 장기 실패 시 "달력상 14일 파기" vs "최신 1개 사본 생존" 우선순위**:
   - `keep-daily 14`는 성공 일수 기준이고, `--keep-within-daily 14d`도 최신 스냅샷 기준이다. 백업이 연속 실패할 때 달력 `N`일 경과분(마지막 스냅샷 포함)을 무조건 지울지, 최소 1개를 남기고 처리방침에 잔존 예외를 적을지 결정 필요.
3. **`erasure.log` 정리 방식 및 시점**:
   - (A안) #17/M3 초기에는 append-only로 유지하고 사본 파기 확인 후 유지보수 정지 상태에서 수동 정리.
   - (B안) `erasure.lock` 기반 온라인 자동 compaction 구현.
   - 어느 안이든 **실제 확정·구현·사본 파기 검증 전까지 `privacy.html`의 보유기간 숫자를 확정하지 않는다**.
4. **허용 복원 최소시점(`T_min_restore`) 값**: 14일 보관 정책과 오프라인 PC 유예·대장 보관 기간이 맞물려 정해져야 한다.
5. **OCI pull 전용 Customer Secret Key 분리 여부**: 사본 장비가 OCI 원본 버킷을 지울 수 없도록 Read-Only 권한 키를 따로 둘지 운영 계정 정책 확인 필요.
6. **#17 첫 격리 리허설 실행 환경**: 로컬 PC 일회용 Docker `postgres:17`에서 먼저 수행할지, OCI VM 내 별도 격리 Compose 프로젝트로 수행할지(메모리 여유 고려) 결정 필요.
