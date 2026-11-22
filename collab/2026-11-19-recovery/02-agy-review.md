# #17 2라운드 교차 검토 · agy

- **작성자 / 일자**: 추가 agy / 2026-11-19
- **검토 대상**: root Codex 독립안 [`01-codex.md`](01-codex.md) 및 자기 독립안 [`01-agy.md`](01-agy.md) (`01-agy.md` 원문은 1라운드 기록으로 보존하고 철회·보완은 본 문서에만 기록한다)
- **전제**: 추가 Claude 독립안 및 3자 종합 전 단계이므로 어떤 기간·구현·운영 결정도 확정하지 않는다. 실제 source·설정·서버·계정·env·키·provider 변경 없음.

---

## 1. 공식 출처 교차 확인 및 기술적 반례 (자기 제안 철회·보완 포함)

### 1.1 OCI Customer Secret Key 권한 모델과 `restic copy` source lock
- **공식 출처 확인 (확인일: 2026-11-19)**:
  1. **OCI Customer Secret Key**: 공식 문서([Managing User Credentials](https://docs.oracle.com/en-us/iaas/Content/Identity/Tasks/managingcredentials.htm), [Policy Reference for Object Storage](https://docs.oracle.com/en-us/iaas/Content/Identity/Reference/objectstoragepolicyreference.htm)) 기준, Customer Secret Key 자체에는 키별 읽기/쓰기 권한 설정이 없다. 키는 IAM 사용자(User)에 귀속(사용자당 최대 2개)되며, 실제 권한은 그 사용자가 속한 **IAM Group과 IAM Policy**(`target.bucket.name`, `target.object.name`, `request.permission`)가 결정한다.
  2. **`restic copy` source lock 동작**: `restic` 공식 소스([`cmd/restic/cmd_copy.go`](https://github.com/restic/restic/blob/master/cmd/restic/cmd_copy.go), [`cmd/restic/lock.go`](https://github.com/restic/restic/blob/master/cmd/restic/lock.go), [`internal/global/secondary_repo.go`](https://github.com/restic/restic/blob/master/internal/global/secondary_repo.go) — `v0.18.1` / `v0.19.1` 확인)를 대조한 결과:
     - `restic copy --from-repo ...`는 `openWithReadLock(ctx, gopts, gopts.NoLock, printer)`로 **원본 저장소(`srcRepo`)에 비배타 lock 객체(`locks/<id>`)를 생성하고 종료 시 삭제**한다(`OBJECT_CREATE` + `OBJECT_DELETE` 필요).
     - 전역 `--no-lock`을 주면 `SecondaryRepoOptions.FillGlobalOpts`가 `gopts.NoLock`을 원본 설정으로 넘겨 `srcRepo`는 `SetDryRun()`으로 잠금을 생략하는 반면, 대상 저장소(`dstRepo`)는 `openWithAppendLock(ctx, secondaryGopts, false, printer)`가 `false`를 하드코딩하므로 **대상(PC/NAS) 잠금은 계속 유지**된다.
- **자기 철회 (`01-agy.md` §2.1, §5-5)**:
  - "pull 전용 Customer Secret Key에 읽기 전용 권한만 발급한다"는 제안은 (1) 키 자체가 아니라 별도 IAM 사용자/그룹/정책을 둬야 한다는 점, (2) 순수 `OBJECT_READ`만 주면 기본 `restic copy`가 `srcRepo`의 `locks/` 쓰기·삭제 실패로 중단된다는 두 지점에서 틀렸으므로 **철회**한다.
- **작은 대안 (추론이 아닌 확인된 API/소스 동작 기반)**:
  - **대안 A (원본 잠금 유지, 권장)**: pull 전용 IAM 사용자 + 그룹을 분리해 버킷 `OBJECT_READ` + **`target.object.name = 'locks/*'`에 한정한 `OBJECT_CREATE`·`OBJECT_DELETE`** 정책을 부여한다. `data/*`, `snapshots/*`, `index/*`, `keys/*`, `config` 삭제/변조는 막으면서 `restic copy`가 OCI `prune` 배타 잠금을 정상 감지(exit `11` 또는 `--retry-lock`)한다. (단, `locks/` 내 기존 lock 객체 삭제 가능성이라는 잔여 범위는 감수해야 한다.)
  - **대안 B (순수 Read-Only IAM + `--no-lock`)**: 정책상 `locks/*` 쓰기조차 빼려면 `restic copy --no-lock`을 쓴다. `dstRepo`는 여전히 잠기지만 `srcRepo` 잠금을 건너뛰므로, OCI `prune`과 우연히 겹치면 팩 파일 삭제 경합으로 copy가 실패할 수 있다(원본 훼손은 없으나 실패율·재시도 관리 필요).

### 1.2 2파일(`erasure.log` + `erasure.meta`) 동시 rename의 비원자성과 초기/빈 대장 함정
- **기술적 반례 (`01-agy.md` §2.3, §2.4 자기 철회·보완)**:
  - POSIX `rename()`은 단일 파일에만 원자적이며, `erasure.log.tmp → erasure.log`와 `erasure.meta.tmp → erasure.meta` 두 번의 `ATOMIC_MOVE`는 **결코 하나의 원자 교체가 아니다**. 사이 crash 시 두 조합이 발생한다:
    1. **조합 1 (`erasure.log` 먼저 교체 → crash → `erasure.meta` 구 파일 잔존)**: `erasure.log`는 이미 cutoff 이전 줄이 지워졌는데 `erasure.meta`는 더 과거 `coverage_since_utc`를 가리킨다. 복원 게이트가 이를 정상 커버리지로 **거짓 통과(false positive)** 시켜 해지된 구독이 되살아날 수 있는 치명적 결함이다.
    2. **조합 2 (`erasure.meta` 먼저 교체 → crash → `erasure.log` 구 파일 잔존)**: `coverage_since_utc`만 앞으로 당겨져 아직 대장에 남아 있는 구간까지 복원이 거절된다(안전 방향이지만 거짓 거부 발생).
  - **초기/빈 대장의 검증 공백**: `01-agy.md` §2.3에서 제안한 `cmp -n <snapshot_erasure_bytes> snapshot/erasure.log latest/erasure.log`는 초기 빈 대장 또는 `erasure_bytes == 0` 스냅샷에서 `cmp -n 0`이 되므로 **실제로 아무 바이트도 검증하지 않고 통과**한다. 또한 M1→M2 전환 직후 `erasure_included=false` 스냅샷과 호스트 빈 대장이 만나는 경계에서도, 대장이 중간에 빈 파일로 새로 만들어졌는지 파일 내용만으로는 판별할 수 없다.
- **이종 언어 잠금 비호환 추가 확인 (`01-codex.md` line 21 뒷받침)**:
  - 공식 Linux [`flock(2)` 매뉴얼](https://man7.org/linux/man-pages/man2/flock.2.html)(확인일: 2026-11-19)에 명시되어 있듯 Linux 2.0 이후 `flock(2)`(shell `flock(1)`)과 `fcntl(2)`(OpenJDK Linux `FileChannel.lock()`이 사용하는 POSIX byte-range lock)은 **서로 상호작용하지 않는다**. 따라서 셸 스크립트(`flock`)와 Java(`FileChannel.lock`)가 같은 파일을 잠근다고 가정하면 동시성이 깨진다.
- **구현량·기존 앱 호환성을 포함한 보완안**:
  1. **2파일 동시 교체 제안 철회**: `erasure.meta` 별도 파일과 2회 rename 안을 거둔다.
  2. **compaction 미도입 구간 (최소 구현·100% 호환)**: `erasure.log` 1파일만 유지한다. 복원 검증은 `cmp -n` 단독 대신 (a) 백업 메타의 `erasure_included=true` 확인(M2 이후 스냅샷), (b) 최신 대장 `readStrict()` 통과, (c) `snapshot_erasure_bytes > 0`이면 바이트 prefix 일치 확인, (d) `snapshot_erasure_bytes == 0`인 초기 구간은 "빈 스냅샷 시점 이후 대장 교체 여부는 파일 내용만으로 검증 불가"라는 한계를 인지하고 `backup-meta.txt`의 `erasure_lines=0` 및 호스트 파일/마운트 연속성(운영 기록)을 함께 본다.
  3. **향후 compaction 도입 시(단일 원자 교체 + 구버전 호환)**: 2파일 대신 `erasure.log` 1파일 안에 상태(예: v2 첫 줄 `# coverage-since=<ISO-8601>`)를 담거나 단일 교체 파일만 원자 이동한다. 단, 현재 `ErasureJournal.readStrict()`는 모든 줄을 UUID로 파싱해 `#` 줄을 만나면 `ERASURE_LOG_BAD_LINE_1`로 실패하므로, **blue·green 배포 중 구버전 컨테이너가 떠 있는 동안에는 새 포맷/새 `erasure.lock` 규약을 혼합 실행할 수 없다**. 전환 시에는 (a) 새 앱이 구·신 규약을 함께 읽고 쓸 수 있는 과도기 버전을 먼저 배포한 뒤 활성화하거나 (b) 앱 정지 유지보수 상태에서 전환한다. 잠금 공유 역시 shell `flock`이 아니라 Java helper를 호출해야 한다.

### 1.3 수동 사본 전면 금지 철회 → 통제 가능한 관리 사본 모델
- **자기 철회 (`01-agy.md` §2.2-3, §2.6)**:
  - "별도 USB·클라우드 폴더 수동 복사는 금지", "수동 사본 0건"은 사용자가 승인한 규칙이 아니라 agy가 과도하게 추가한 금지 규칙이므로 **철회**한다. 장애 점검·장비 교체·리허설 비교 중 수동 추출/사본이 생길 수 있다.
- **보완**:
  - 출발점은 사용자가 정한 **PC/NAS `restic copy` pull**이다.
  - 수동 사본/임시 복원물은 금지 대신 **(1) 미등록 수동 사본은 운영 복원 소스로 사용 불가(복원 게이트 차단)**, **(2) 임시 복원물은 전용 접근 제한 경로(`0700`/사용자 전용 ACL)에 두고 작업 완료 후 파기 기록**, **(3) 만약 계속 보유할 관리 사본을 만든다면 저장소 목록에 등록해 동일한 `forget --prune` 정리 및 대장 정리 선행 조건 대상에 포함**하는 방식으로 검증 가능성을 확보한다.

### 1.4 `keep-within` 기준 · 오프라인/prune 실패 잔류 · 시각 분리 가정 · `dump_started_utc` 시차
- **`keep-within` · 오프라인 PC · `prune` 실패 실제 잔류 (`01-agy.md` §2.2 보완)**:
  - 공식 restic 문서 확인대로 `--keep-within-daily`는 현재 시각이 아니라 **`latest` 스냅샷 기준**이다. 따라서 오프라인 PC가 20일 만에 켜졌는데 네트워크 오류로 `copy`가 실패하고 `--keep-daily 14`만 돌면 **20~34일 전 스냅샷 14개가 단 1개도 지워지지 않는다**. PC/NAS 정리 단계가 `now` 기준 만료(구현 시 `restic snapshots --json`의 `time` 비교 후 ID 지정 `forget`)를 쓰지 않는 한, 오프라인 PC 복귀 시 사본 잔류는 현실적으로 발생하며 이를 임의의 삭제 보장으로 가릴 수 없다.
  - `backup.sh`의 `restic forget ... --prune`에서 `forget`만 끝나고 `prune`이 중단되면 스냅샷 목록(`snapshots`)에서는 사라지나 암호화 팩은 스토리지에 그대로 남는다. 따라서 정기 점검·복구 절차에 **`restic prune` 단독 실행 및 실제 버킷/디스크 사용량 확인**을 추가한다.
- **"일정 시각 분리(03:00 vs 04:00)만으로 lock 충돌 방지" 가정 철회 (`01-agy.md` §2.1, §3)**:
  - `deploy/backup/systemd/gongchae-backup.timer`는 `Persistent=true`이므로 OCI VM이 03:00에 내려가 있다가 04:05에 기동되면 백업이 04:05에 실행돼 pull과 충돌한다. Windows `StartWhenAvailable`도 절전 복귀 시 임의 시각에 실행된다. 따라서 시각 분리는 **충돌 확률 완화**일 뿐이며, pull 스크립트에 `--retry-lock` 또는 exit `11`(잠금 충돌) 구분 처리가 필요하다.
- **`dump_started_utc` vs 실제 DB 트랜잭션 스냅샷 시각 시차 (`01-agy.md` §2.3-1 보완)**:
  - `backup.sh`가 호스트에서 찍는 `dump_started_utc` 이후 `docker compose exec` 기동, PostgreSQL 접속 인증, `pg_dump` 내부 `REPEATABLE READ` 스냅샷 획득(타 DDL 락 대기 포함)까지 지연이 존재한다. `pg_restore --list`의 `; Archive created at ...`도 아카이브 생성 시점의 시스템 시각(`TZ` 영향)이지 DB 내부 트랜잭션 커밋 시각과 정확히 일치하지 않는다.
  - 따라서 두 시각이 같다고 가정하지 않고, 복원 검증에서는 **보수적 안전 방향(더 이른 시각인 `dump_started_utc`를 덤프 시점 하한 `T_dump_low`로, 완료 시점 `backup_utc`를 상한 `T_dump_high`로 사용)** 으로만 판정한다: `T_min_restore` 검사는 `T_dump_low` 기준, 대장 커버리지 하한 검증은 `T_dump_low` 이하를 요구한다(예상 시차의 구체 수치는 실측 전 임의로 정하지 않음).

### 1.5 자동 정리 연기와 PRD FR-A6 공개 보유기간의 정합성
- **자기 보완 (`01-agy.md` §2.3-4, §5-3)**:
  - `01-agy.md`처럼 자동 정리를 나중으로 미루면서 `privacy.html`을 `미정`으로 두는 상태는 **M2/M3 비활성 개발 중(`ALERTS_ENABLED=false`)에만 허용**되며, PRD FR-A6 공개 출시 상태에서는 성립하지 않는다. 또한 자동 정리가 없다고 해서 무기한 보존을 기본 정책으로 채택할 수도 없다.
- **구체적 운영 조건 제안 (법률 해석 없이 기술·운영 실행 가능성만 기준)**:
  - 공개 출시(`ALERTS_ENABLED=true`) 전 게이트로서 다음 두 선택지 중 하나를 채택해야 한다:
    - **조건부 수동 정리 경로(최소 코드 변경)**: 자동 compaction 코드가 없더라도, (1) 모든 등록 저장소(OCI + PC/NAS + 등록 관리 사본)에서 기준 시점 이전 스냅샷의 `forget --prune` 완료를 확인하고, (2) 앱 발송/해지 정지 상태에서 runbook 체크리스트로 대장을 정리하며, (3) 미정리 최장 경과일 초과 시 운영 경보를 울리는 **주기적 수동 정리 절차**가 실측·문서화되어야만 고지에 그 조건과 최장 잔존 구조를 적을 수 있다.
    - **온라인 자동 compaction 경로**: `erasure.lock` + 단일 파일 원자 교체 + 전체 저장소 `prune` 확인 연동이 구현·혼합 잠금 테스트·crash 테스트까지 통과한 뒤에만 "자동 정리 주기"를 고지에 적는다.
  - 두 경로 모두 채택·검증 전에는 구체 일수를 확정/약속하지 않는다.

---

## 2. root Codex 독립안(`01-codex.md`)에 대한 교차 검토

| `01-codex.md` 항목 | 평가 | 구체 보완 의견 |
|---|---|---|
| 첫 pull 대상을 현재 사용 중인 PC로 두고 ignored 경로에 배치 (line 7) | 방향 합의, **경로 위치 반례 있음** | 프로젝트 워크트리 내부의 `.gitignore` 경로에 실제 개인정보가 든 `restic` 저장소를 두면 `git clean -fdx`, 워크트리 삭제, 에이전트/도구 인덱싱·압축에 노출·유실될 위험이 크다. **코드 저장소 바깥의 OS 사용자 전용 경로**(예: Windows `%LOCALAPPDATA%\gongchae-bell\backup\restic-repo` + `icacls` 격리, 비동기화 폴더)로 빼는 편이 안전하다. |
| `restore` 명령이 최소 복원점·커버리지 검사 (line 15) | 찬성, 구현 접점 보완 | 현재 `ErasureReapplyCommand`는 `erasure.log` 파일만 읽고 덤프 메타를 입력받지 않는다. 따라서 1단계는 복원 래퍼/runbook 검증 스크립트에서 선검증하고, 코드 강제가 필요하면 `--alerts.erasure-dump-meta` 같은 선택 인자를 추가한다. |
| 구버전 앱과 새 잠금 규약 전환 구간·이종 언어 잠금 호환성 검증 (line 21) | **전적으로 동의** | Linux에서 Java `FileChannel.lock()`(`fcntl`)과 `flock(1)`은 상호 배제되지 않으므로(§1.2), 혼합 프로세스 실측 시험과 blue·green 전환 과도기 호환 설계가 필수다. |
| 물리 삭제 확인과 복원 허용 여부 분리 증거, 수동 사본 제어 (line 15) | **찬성** | `restic prune` 실패 시 스냅샷 목록엔 없어도 팩이 남으므로 물리 삭제 확인과 복원 게이트를 분리한 점이 정확하다. |

---

## 3. 종합 전 남은 선택 (미확정 · 사용자/조율자 결정 필요)

1. **OCI pull 권한 방식**: (A) pull 전용 IAM 사용자/그룹 + `locks/*`만 쓰기/삭제 허용 정책 vs (B) 순수 Read-Only IAM 정책 + `restic copy --no-lock`(OCI `prune` 경합 시 실패·재시도 감수).
2. **PC/NAS 로컬 저장 경로**: 프로젝트 외부 사용자 전용 비동기화 경로(권장) vs 프로젝트 외부 별도 드라이브/NAS 경로.
3. **달력 기준 만료 vs 최소 1개 사본 유지**: 백업 장기 실패 시 달력 만료 상한을 넘긴 마지막 사본까지 지울지, 최소 1개 최신 사본을 남기고 고지에 잔존 예외를 반영할지.
4. **대장 정리 구현 시점 (공개 출시 게이트 기준)**: (A) 정지 상태 주기적 수동 정리 runbook + 미정리 경보로 먼저 출시할지, (B) `erasure.lock` + 단일 파일 원자 교체 기반 온라인 compaction을 #17/M2 실사용 전에 구현할지.
5. **허용 복원 최소시점(`T_min_restore`)과 오프라인 PC 허용 유예**: 실제 사용 PC의 가동 패턴을 확인한 뒤 사용자 결정으로 수치 확정.
