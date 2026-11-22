# #17 OCI 밖 사본·해지 보존 복원 · claude 독립안

2026-11-19 · 1라운드 독립안 · 요청: root codex, [`2026-11-19-resume/extra-claude-recovery-design.md`](../2026-11-19-resume/extra-claude-recovery-design.md)

- **읽은 것**: CLAUDE.md, PRD(G5, FR-A6, 비기능 백업·보안, §7), GitHub #17, main `517eb6b`(PR22 `52c172c` 포함)의 ADR-0012·0013, `deploy/backup/backup.sh`, `docs/ops/backup-restore.md`, `ErasureJournal`·`ErasureReapplyCommand`, root `2026-11-16-m2-privacy/sol-source-review.md`·`sol-followup-review.md`.
- **읽지 않은 것**: 이번 라운드의 `01-codex.md`, `01-agy.md`. 이 문서를 다 쓴 뒤에 읽는다.
- **하지 않은 것**: source·테스트·설정·ADR 수정, 실제 OCI·PC 저장소·계정·키·env 접근, 실행 진단. 외부 사실은 §1의 공식 문서로만 확인했다.
- 아래 기간 숫자는 모두 **기술 제안**이다. 법정 유예나 실측이 아니다.

## 0. 요약

1. **지금 runbook에 실제 결함이 둘 있다(§2 R1·R2).** 복원 절차가 운영 Compose의 postgres에 `--clean`으로 덮어쓴다. 또 백업 안의 오래된 대장으로 재적용한 뒤 그 대장으로 호스트 대장을 교체한다. 호스트가 살아 있는 흔한 사고에서도 백업 뒤의 해지가 되살아나고, 대장에서도 영구히 사라진다. source를 바꾸지 않고 문서만 고쳐도 닫힌다.
2. **복원점 시각은 하한값으로 판정한다.** `backup_utc`와 restic snapshot 시각은 덤프가 끝난 뒤에 찍히는 상한값이라 판정 방향이 틀리다. pg_dump 아카이브 생성 시각은 연결과 스냅샷 시작보다 먼저 찍힌다(§1 F7). 또는 덤프 전에 잰 시각을 메타에 추가한다.
3. **"14일"은 restic 옵션 하나로 보장되지 않는다.** `keep-daily`는 백업이 있는 날만 센다. `keep-within`은 최신 snapshot을 기준으로 잰다. prune은 기본적으로 미사용 데이터를 최대 5%까지 남긴다(F1~F3). 정상 운영의 상한, 실패할 때의 한계, 남는 조각을 나눠서 고지해야 한다.
4. **대장 정리는 "모든 사본이 지워졌는지 증명"하는 대신 "워터마크보다 오래된 덤프의 복원을 거부"하는 방식으로 안전하게 만든다.** 워터마크는 별도 메타파일이 아니라 대장 첫 줄에 nil UUID 줄로 넣는다. 그러면 교체 한 번으로 원자적으로 바뀐다. 기존 앱의 append와 재적용 명령과도 호환된다(§3.5).
5. **잠금 규약은 언어마다 다르다.** JDK `FileChannel.lock()`은 Linux에서 fcntl POSIX 레코드 잠금이고, `flock(1)`은 flock(2)이다. 둘은 서로를 막지 않는다(F4·F5). 셸 정리 스크립트는 앱의 append를 막지 못한다. 정리는 Java로 하거나 두 앱을 멈추고 한다. 첫 단계는 앱을 멈추고 하는 정리를 제안한다.
6. **PC pull의 읽기 전용 자격증명은 아직 성립이 확인되지 않았다.** restic은 무엇을 하든 먼저 저장소에 잠금을 만든다. OCI에서 새 객체를 만들려면 `manage`가 필요하다(F6·F9). `copy`가 원본 쪽에서 `--no-lock`을 따르는지는 공식 문서에 없다. 리허설에서 확인한다.

## 1. 확인한 사실 (공식 문서, 2026-11-19 열람)

| # | 사실 | 출처 | 설계에 주는 영향 |
|---|---|---|---|
| F1 | `--keep-daily n`은 "snapshot이 있는 마지막 n일"을 남긴다. 달력 옵션은 forget을 실행한 시각 기준이 아니다 | [restic forget](https://restic.readthedocs.io/en/stable/060_forget.html) | 백업이 빠진 날만큼 더 오래된 사본이 남는다 |
| F2 | `--keep-within`·`--keep-within-daily`는 "최신 snapshot 기준" 기간이다. 미래 시각 snapshot은 지우지 않는다 | 같은 곳 | 지금 시각 기준 상한이 아니다. 최신 snapshot이 신선할 때만 대략 달력 상한이 된다 |
| F3 | `prune --max-unused` 기본값은 5%다. `0`은 "공간을 최소화"한다고만 적혀 있다 | 같은 곳 | forget한 snapshot의 데이터 일부가 암호화된 채 저장소에 남을 수 있다 |
| F4 | Linux에서 flock(2)과 fcntl 레코드 잠금은 서로 상호작용하지 않는다. 단 NFS·CIFS에서는 에뮬레이션 때문에 상호작용한다. `flock(1)`은 기본으로 flock(2)를 쓴다 | [flock(2)](https://man7.org/linux/man-pages/man2/flock.2.html), [fcntl_locking(2)](https://man7.org/linux/man-pages/man2/fcntl_locking.2.html), [flock(1)](https://man7.org/linux/man-pages/man1/flock.1.html) | 셸 `flock`으로는 Java writer를 막지 못한다 |
| F5 | OpenJDK의 Unix `lock0`은 `fcntl(fd, F_SETLK/F_SETLKW)`를 쓴다(OFD 잠금이 아니다). POSIX 레코드 잠금은 프로세스에 묶이고, 프로세스가 그 파일의 **아무 fd나** 닫으면 그 파일에 걸린 잠금이 모두 풀린다 | [UnixFileDispatcherImpl.c](https://github.com/openjdk/jdk/blob/master/src/java.base/unix/native/libnio/ch/UnixFileDispatcherImpl.c), fcntl_locking(2) | 같은 JVM 안에서 잠금을 쥔 동안 같은 파일을 다른 채널로 열고 닫으면 잠금이 조용히 풀린다 |
| F6 | restic은 무엇을 하든 먼저 저장소에 잠금을 만든다. 데이터를 지우는 쪽은 배타 잠금이 필요하다. 30분보다 오래된 잠금은 stale이다(같은 호스트면 프로세스 생존도 검사한다). 전역 `--no-lock`은 "일부 작업을 읽기 전용 저장소에서 가능하게" 한다고만 적혀 있고, 어느 명령이 지원하는지는 없다 | [restic references · Locks](https://restic.readthedocs.io/en/stable/100_references.html), [manual](https://restic.readthedocs.io/en/stable/manual_rest.html) | PC pull과 서버 prune이 겹치면 한쪽이 실패한다. 읽기 전용 pull은 아직 확인되지 않았다 |
| F7 | pg_dump `main()`은 `CreateArchive` → `ConnectDatabase` → `setup_connection` 순서다. 아카이브 생성 시각(`AH->createDate = time(NULL)`)은 `_allocAH`에서 찍힌다. `PrintTOCSummary`가 "; Archive created at …"을 출력한다 | [pg_dump.c](https://github.com/postgres/postgres/blob/REL_17_STABLE/src/bin/pg_dump/pg_dump.c), [pg_backup_archiver.c](https://github.com/postgres/postgres/blob/REL_17_STABLE/src/bin/pg_dump/pg_backup_archiver.c), [pg_restore](https://www.postgresql.org/docs/17/app-pgrestore.html) | 아카이브 생성 시각은 스냅샷 시각의 **하한**이다. 시간대 표기 형식(`PGDUMP_STRFTIME_FMT`)과 `--list` 연결은 원문으로 다 보지 못했다 → 리허설에서 확인 |
| F8 | `restic copy`는 `--from-repo`로 원본을 받는다. 중단되면 다음 실행에서 이어 간다. 대상 저장소는 미리 있어야 한다. `init --from-repo … --copy-chunker-params`로 만들어야 중복 제거가 된다 | [restic working with repos](https://restic.readthedocs.io/en/stable/045_working_with_repos.html) | copy가 원본 snapshot의 시각·호스트를 보존하는지는 **문서에 없다** → 리허설 확인 항목 |
| F9 | OCI `objects`: `read`=GET·LIST, `use`=기존 객체 덮어쓰기만, 새 객체 생성과 삭제는 `manage`. `target.object.name` 조건(문자열·패턴)이 있다 | [OCI Object Storage 정책 참조](https://docs.oracle.com/en-us/iaas/Content/Identity/Reference/objectstoragepolicyreference.htm) | restic 잠금 파일은 새 객체라 `manage` 범위다. 패턴 문법, 그리고 Customer Secret Key가 소유 사용자의 IAM 권한을 그대로 따르는지는 읽은 페이지에 명시가 없다 |

추론 표시: F5의 "다른 fd를 닫으면 풀린다"는 JDK가 fcntl을 쓴다는 사실과 man 페이지를 합친 결론이다. 잠금이 inode에 묶여서 rename 뒤 새 파일을 열면 옛 잠금에 막히지 않는다는 점은 이번에 읽은 페이지에 적혀 있지 않다. sol 후속 검토 C4의 주장과 같은 방향의 통상 의미론으로만 쓴다.

## 2. 지금 저장소에서 찾은 반례

| # | 위치 (main 517eb6b) | 반례 | 결과 |
|---|---|---|---|
| R1 | `docs/ops/backup-restore.md` §7 4단계 | "격리 PostgreSQL 복원"이라고 하면서 `docker compose -f compose.prod.yml … exec -T postgres pg_restore … --clean --if-exists`를 실행한다. 이는 운영 DB 자체를 지우고 덮어쓴다 | #17 완료 기준("기존 운영 DB에 직접 덮어쓰지 않는다")과 충돌한다. 리허설이 곧 운영 DB 교체가 된다 |
| R2 | 같은 문서 §7 5단계 | 백업에서 꺼낸 `/tmp/gongchae-restore/erasure.log`로 재적용한다. 그 뒤 그 파일을 호스트 `/var/lib/gongchae-bell/erasure/erasure.log`로 배치한다. 예: 03:00 백업, 15:00 해지 A(호스트 대장에 기록), 18:00 DB 손상 → 03:00 덤프 복원 → 03:00 시점 대장으로 재적용하면 A가 살아난다. 이어서 호스트 대장이 03:00 판으로 교체되어 A 줄이 영구히 사라진다 | ADR-0013의 "가장 흔한 사고에서는 해지가 완전히 유지된다"와 정반대다. 호스트가 살아 있으면 **살아 있는 호스트 대장**을 써야 한다 |
| R3 | `backup.sh` `backup-meta.txt`의 `backup_utc`, restic snapshot 시각 | 둘 다 덤프가 끝난 뒤에 찍힌다. 덤프 스냅샷 시각보다 늦다(상한). 이 값으로 "허용 최소시점 이상"을 판정하면 실제보다 새 덤프로 본다. 예: 워터마크 03:05, 실제 스냅샷 03:00:05, 03:02 해지(스냅샷 뒤 커밋)의 줄이 정리됐을 때 `backup_utc=03:07 ≥ 03:05`로 통과하면 그 해지가 살아난다 | 판정에는 하한값이 필요하다(F7, §3.3) |
| R4 | `backup.sh` `stage_erasure_log`는 잠금 없이 `cp`한다 | 지금은 대장이 append 전용이고 잘린 줄 재시도가 있어 안전하다. 하지만 이후 정리 스크립트가 같은 방식으로 셸 `flock`을 쓰면 Java writer를 막지 못한다(F4·F5) | 정리를 구현할 때 같은 잠금 종류를 쓰거나, 앱을 멈춰야 한다 |
| R5 | `backup.sh`의 순서: `stage_postgres_dump` → `stage_erasure_log` | **유지해야 할 올바른 점.** 대장을 덤프 뒤에 복사하므로 묶음 안의 대장이 스냅샷 뒤의 해지까지 덮는다. 순서를 뒤집으면 VM 소실 때 잃는 구간이 넓어진다 | 불변식으로 문서화한다 |
| R6 | ADR-0012·`backup-restore.md` §2의 `--keep-daily 14` | F1·F2. PRD의 "보관 14일"과 정의가 다르다. 문서 §2도 이미 이 점을 인정하고 #17로 넘겼다 | §3.2 |

## 3. 제안

### 3.1 PC pull 실행안 (현재 Windows PC, 새 서비스·유료 자원 없음)

| 항목 | 제안 | 근거·확인 필요 |
|---|---|---|
| 저장소 | PC에 별도 restic 저장소를 둔다. `restic init --from-repo <OCI> --copy-chunker-params`로 만들고, 비밀번호는 OCI 저장소와 다르게 한다 | F8. 비밀번호는 사용자의 비밀번호 관리자에 두고, 분실하면 PC 사본도 복구할 수 없다 |
| 경로 | OneDrive·파일 히스토리 같은 동기화나 백업 대상이 **아닌** 로컬 NTFS 경로(예: `%LOCALAPPDATA%\gongchae-backup\repo`). NTFS ACL은 그 사용자만 갖게 한다 | 동기화 폴더에 두면 통제하지 못하는 사본이 하나 더 생긴다. 이것은 금지 규칙이 아니라 사본 목록과 보관 고지를 맞추기 위한 위치 선택이다 |
| 비밀값 | OCI 키와 두 저장소 비밀번호를 사용자 계정 DPAPI로 보호해 저장한다(PowerShell `ConvertFrom-SecureString`). 작업이 실행될 때만 환경변수로 푼다 | 평문 파일을 두지 않는다. 저장소 밖에서 사용자가 관리한다 |
| OCI 자격 | PC 전용 IAM 사용자와 Customer Secret Key를 쓴다. 우선순위: (a) `read objects` + `copy --no-lock`. (b) a가 안 되면 `read objects` + `target.object.name`을 잠금 경로(`locks/…`)로 제한한 `manage objects`. (c) 그것도 안 되면 버킷 한정 `manage objects`(PC가 침해되면 OCI 사본 삭제도 가능하다는 위험을 기록한다) | F6·F9. a는 copy가 원본에서 `--no-lock`을 따르는지, b는 패턴 문법을 리허설에서 확인한다. 금지된 PUT이 403으로 거부되는지도 키로 직접 확인한다. IAM 사용자 추가 비용은 확인이 필요하다 |
| 순서 | ① **로컬 forget·prune 먼저**(네트워크가 없어도 로컬 보관 상한을 지킨다) → ② `restic copy --from-repo <OCI> --tag gongchae-bell` → ③ 다시 로컬 forget(서버 prune이 실패해 넘어온 오래된 snapshot을 정리) → ④ `restic check`(주 1회 `--read-data-subset`) → ⑤ 로컬 최신 snapshot의 메타 `backup_utc`가 now−36h 이후인지 확인 → ⑥ 성공일 때만 Healthchecks 별도 check에 핑, 실패하면 `/fail` | ADR-0012의 별도 heartbeat. ⑤가 실패하면 "pull은 됐지만 서버 백업이 멈춤"이다 |
| 스케줄 | 작업 스케줄러, 매일 낮 1회(예: 12:30 KST). "놓친 실행은 가능할 때 바로 실행"을 켜고 로그온 시에도 실행한다. 02:50~03:40 KST에 시작하게 되면 기다린다 | 서버 03:00 backup·prune과 잠금이 겹치지 않게 한다(F6). 겹치면 서버가 `RESTIC_PRUNE_FAIL`로 경보한다. 작업 스케줄러 설정 이름은 공식 문서로 아직 확인하지 않았다 |
| 관측 | Healthchecks check `gongchae-pull`, 주기 1일. 유예는 제안값 2일 | PC가 꺼져 있으면 경보가 오는 것이 정상 동작이다. 유예값은 사용자가 정한다 |
| 기록 | 실행마다 남은 snapshot 수, 가장 오래된·최신 `backup_utc`, copy 건수를 로컬 로그 한 줄로 남긴다. UUID·이메일은 남기지 않는다 | 보관 상한 준수를 나중에 보여 줄 근거 |

NAS는 같은 설계를 restic이 도는 장비에 그대로 쓸 수 있다. 사양을 모르므로 이번에는 PC만 첫 사본으로 제안한다.

### 3.2 "보관 14일"의 정의

- **제안 정의:** "정상 운영에서 각 저장소에는 마지막 성공 백업 기준 14일 안의 일일 사본만 남긴다."
  - 서버: `--keep-daily 14`를 `--keep-within-daily 14d`로 바꾼다. 백업 직후 forget하므로 최신 snapshot이 곧 지금이다(F2).
  - PC: 같은 옵션을 쓴다. ⑤에서 최신이 36시간 안이어야 성공이므로 상한은 대략 14일+1.5일이다.
- **고지해야 할 한계(숨기지 않는다):**
  1. 서버 백업이 연속으로 실패하면 forget도 돌지 않는다. 그 일수만큼 더 남는다(경보로 감지된다).
  2. PC가 꺼져 있는 동안은 PC 사본이 정리되지 않는다. 켜지면 ①에서 정리된다.
  3. prune은 기본적으로 미사용 데이터를 최대 5%까지 남긴다(F3). 남은 조각은 암호화된 상태다. PC는 `--max-unused 0`을 제안한다. OCI는 요청 수(월 5만 회)를 리허설에서 잰 뒤 정한다.
  4. 수동 사본(복원 작업 디렉터리, 내보낸 파일)은 자동 정리 대상이 아니다. 리허설 기록에 만든 시각과 삭제 시각을 남긴다.
- 지금 시각 기준으로 정확히 자르려면 `snapshots --json`으로 ID를 골라 forget하는 스크립트가 필요하다. 그 경우 "최신 1개는 남긴다"와 "백업이 2주 실패하면 사본 0개" 중 하나를 골라야 한다. 이 선택은 사용자 결정이다(§6). 나는 원래 옵션과 위 한계 고지를 권한다.

### 3.3 덤프 시각과 허용 최소시점

- **덤프 시각 하한 `D_lo`**: `backup.sh`가 pg_dump 직전에 `date -u`로 잰 `dump_started_utc`를 메타에 더한다(작은 source 변경). 아카이브의 "Archive created at"(F7)과 교차 확인한다. 이미 만든 snapshot에는 새 키가 없다. 그때는 아카이브 생성 시각의 시간대를 확실히 해석할 수 있을 때만 쓰고, 아니면 "시각 미확인"으로 거부한다.
- **무결성**: 기존 `dump_sha256` 대조를 유지한다. 메타의 진위는 restic 저장소 키(쓰기 권한자만 snapshot을 만든다)에 기댄다. 서버 시계가 틀리면 시각도 틀리다. 그래서 `D_lo ≤ backup_utc ≤ D_lo + 2h`, `backup_utc ≤ 지금`을 함께 검사해 시계 이상을 잡는다(2h는 제안값).
- **허용 최소시점**: 발송 재개 조건은 `D_lo ≥ max(지금 − P, T_w + m)`이다.
  - `P`: 정책상 복원을 허용하는 최대 나이. 제안값은 14일 + 7일 여유.
  - `T_w`: 대장 워터마크(§3.5).
  - `m`: 해지 줄 시각(앱 시계, DB 커밋 전)과 덤프 시각 사이의 오차 여유. 제안값 1시간.
  - 어긋나면 데이터 확인용 복원은 할 수 있지만 `ALERTS_ENABLED=true`로 재개하지 않는다.

### 3.4 대장의 연속 커버리지

- **J1 출처 선택**: 호스트가 살아 있으면 **살아 있는 호스트 대장**을 쓴다(R2를 고친다). 호스트를 잃었을 때만 사용 가능한 가장 새로운 snapshot의 대장을 쓴다. 덤프와 같은 오래된 snapshot의 대장은 더 새로운 것이 있으면 쓰지 않는다.
- **J2 연속성 검사**: 복원할 덤프와 같은 snapshot에 들어 있는 대장 사본 `B`가, 재적용에 쓸 대장 `L`의 본문 앞부분과 바이트 단위로 같아야 한다. 정리된 적이 있으면 `B`에서 `T_w`보다 앞선 앞부분 줄을 뺀 나머지로 비교한다. 이것으로 잡는 것: 경로 착오, 빈 파일로 바뀐 대장, 잘린 대장, 더 오래된 대장 선택. 메타의 `erasure_bytes`·`erasure_lines`도 `B`와 맞춘다.
- **J3 기존 fail closed 유지**: 파일 없음, 잘린 줄, 형식이 틀린 줄은 지금처럼 0건 삭제 후 실패한다. 빈 파일은 `B`도 비어 있을 때만 받는다(J2).
- **J4 사후 확인**: 재적용 뒤 `SELECT count(*) FROM subscription WHERE id IN (대장 UUID)`가 0이어야 한다.
- 한계: 정상 형식의 줄이 통째로 빠진 경우는 `B`보다 뒤쪽이면 잡지 못한다. sol C2의 지적과 같다.

### 3.5 대장 보유와 정리

**워터마크 방식(제안).** 정리할 때 대장 첫 줄에 `00000000-0000-0000-0000-000000000000\t<T_w>`를 쓴다. 이 줄은 "`T_w` 이후의 해지는 모두 들어 있다"는 뜻이다.

- **원자성**: 워터마크가 대장 안에 있으므로 rename 한 번으로 본문과 함께 바뀐다. 백업 `cp`도 한 시점의 쌍을 얻는다. 별도 메타파일이면 반례가 생긴다. 대장을 교체한 뒤 메타 갱신 전에 crash하면 "정리 안 됨"으로 보여 오래된 덤프를 통과시킨다. 또 백업이 둘을 다른 순간에 복사하면 새 대장과 옛 메타가 한 snapshot에 함께 담긴다.
- **이전 버전 호환(코드로 확인)**: 기존 `readStrict`는 nil UUID를 정상 줄로 받는다(36자, 소문자 정규형, `Instant.parse` 통과). 재적용은 `DELETE … WHERE id IN (nil)`이라 0행이다. 기존 append는 끝에만 쓰므로 영향이 없다. `gen_random_uuid()`는 nil을 만들지 않는다. 새 판독기만 이 줄을 워터마크로 해석한다.
- **정리 대상**: 파일의 **앞부분**만 지운다. 첫 줄부터 시각이 `T_w_new`보다 작은 동안 지우고, 처음으로 그 이상인 줄을 만나면 멈춘다. 시계가 역행해 뒤쪽에 더 이른 시각이 있어도 그 줄은 남는다(sol C5).
  - `T_w_new`는 기존 `T_w` 이상이어야 한다(단조).
  - `T_w_new ≤ 지금 − P − m`. §3.3 판정이 그보다 오래된 덤프를 거부하므로, 모든 사본의 삭제를 증명하지 않아도 정리가 안전하다. 꺼진 PC에 오래된 사본이 남아 있어도 복원이 거부될 뿐이다.
  - 제안값으로 줄 보유는 해지 뒤 최소 22일(P 21일 + m), 정리 주기만큼 더 남는다.
- **정리 절차(첫 구현은 두 앱을 멈추고)**: ADR-0013이 이미 "앱을 멈춘 상태에서만 안전하다"고 했다. 이 방식은 잠금 규약도 이전 버전 문제도 없다.
  1. 두 색 앱과 백업 타이머를 멈춘다.
  2. 대장 전체를 엄격히 검사한다. 이상이 있으면 중단하고, 유효한 줄만 건져 쓰지 않는다.
  3. 같은 디렉터리의 임시 파일(O_EXCL)에 워터마크와 남길 줄을 쓴다. 전부 쓰고 fsync한다.
  4. rename으로 교체한다. 디렉터리를 fsync한다. 소유자 `1000:1000`, 권한 `0600`을 확인한다.
  5. 다시 엄격히 검사한 뒤 앱을 시작한다.
  - crash가 rename 전이면 원본이 그대로 남는다(남은 임시 파일은 다음 실행 때 지운다). rename 뒤면 완전한 새 파일이 남는다.
  - 멈추는 시간은 몇십 초로 예상한다(미측정). 주기는 월 1회를 제안한다.
- **무중단 정리(나중 선택지)**: 교체되지 않는 고정 잠금 파일(`erasure.lock`, 운영자가 만든다)을 쓴다. 새 앱은 `synchronized` 안에서 잠금 파일에 fcntl 잠금을 건 뒤 대장을 **경로로 다시 열어** append한다. 정리기도 Java로 같은 잠금을 쓴다(F4). 이 방식의 조건:
  - 같은 JVM 안에서 잠금 파일을 다른 채널로 열고 닫지 않는다(F5).
  - **옛 버전 앱이 하나라도 돌고 있으면 쓸 수 없다.** 옛 앱은 대장 inode만 잠근다. 교체 뒤 풀려나면 이미 unlink된 옛 inode에 줄을 쓰고 DB 삭제를 커밋한다. 이 줄은 사라지고 복원 때 해지가 살아난다. 양쪽 잠금을 다 쥐어도 이 문제는 막지 못한다.
  - 그래서 두 색이 모두 새 버전인지 배포 상태(`blue_tag`·`green_tag`)로 확인하는 운영 게이트가 필요하다.
- **정리하지 않는 동안의 고지**: 지금처럼 "정리 시점 미정"이다. 무기한 보존을 기본 정책으로 정하자는 뜻이 아니다. 정리 방식을 고르기 전까지의 상태다.

### 3.6 격리 복원과 발송 재개 게이트

1. 운영 앱은 그대로 둔다. 복원 대상은 별도 일회용 컨테이너다(`postgres:17`, `--network none`, 임시 볼륨). R1을 고친다.
2. snapshot 선택 → `restic restore`로 0700 작업 디렉터리에 푼다 → `dump_sha256` 대조 → `pg_restore --list`의 생성 시각과 메타 시각 검사(§3.3).
3. 격리 PG에 `pg_restore --exit-on-error`(운영 compose의 postgres가 아니다).
4. 대장 출처 선택(J1)과 J2 검사 → 재적용 명령(대상 DB를 격리 PG로 지정, `ALERTS_ENABLED=false`) → `ERASURE_REAPPLY_COMPLETE`와 exit 0 → J4.
5. 키 검증:
   - `ALERTS_WEBHOOK_KEY_ID`가 행의 `webhook_key_id`와 같은지 본다. Discord 구독 전체를 복호화해 실패가 0건이어야 한다.
   - 이메일 행의 `dest_hmac`을 현재 HMAC 키로 다시 계산해 같아야 한다.
   - 토큰 키는 DB에 근거가 없다. 리허설 운영자의 시험 구독 관리 링크로 확인한다.
   - 이 단계는 앱 클래스를 재사용하는 작은 검증 명령이 있으면 좋다(새 source).
6. 기록: 일시, snapshot ID, `D_lo`, 단계별 소요 시간, `deleted=N`, 키 검증 결과, 작업 디렉터리 삭제 시각.
7. 실제 장애에서 운영으로 전환하는 일은 이 게이트를 통과한 DB로만 한다. 전환 방식(볼륨 교체 등)은 runbook의 별도 단계로 둔다.

**함께 볼 미해결 경계**: 복원된 DB에는 백업 시점의 `PENDING`·`RETRY` 작업과 `collect_run` 슬롯이 있다. 백업 뒤에 이미 발송된 작업이 23시간 수명 안이면 다시 나갈 수 있다. 이메일 NEW도 다시 선택될 수 있다. 해지 보존과는 다른 중복 발송 문제다. 이번 범위에서는 리허설에서 건수를 세고 처리 방식을 쟁점으로 남긴다.

### 3.7 소실 한계

| 상황 | 쓸 수 있는 것 | 해지 유실 구간 |
|---|---|---|
| DB 손상·실수 삭제(호스트 생존) | 살아 있는 호스트 대장 | 없음(R2를 고치면) |
| VM 영구 소실, OCI 생존 | OCI 최신 snapshot의 덤프와 대장 | 마지막 백업(03:00) 뒤부터 소실 시점까지, 최대 약 24시간 + 실패한 백업 일수(ADR-0013) |
| VM과 OCI 계정 모두 상실 | PC 최신 사본 | 마지막 **성공 pull**에 들어 있는 백업 뒤 전부. PC가 꺼져 있던 기간만큼 늘어난다 |

모든 경우에 되살아난 구독자도 메일마다 해지 링크를 받는다. 이것은 완화일 뿐 유실을 없애지 않는다. 처리방침과 runbook에 표의 둘째·셋째 줄을 적는다.

## 4. 최소 구현 경로

| 단계 | 내용 | source 변경 |
|---|---|---|
| A (지금 가능) | runbook R1·R2를 고친다(격리 컨테이너, 살아 있는 대장 우선, 호스트 대장 교체 금지 대신 J1 규칙). R5 불변식, §3.2 정의와 한계, §3.7 표를 문서화한다. PC 저장소 생성, 작업 스케줄러, Healthchecks pull check를 수동 설정한다. 첫 격리 복원 리허설을 하고 실측을 기록한다 | 없음(문서와 PC 설정) |
| B (작게) | `backup.sh`: `dump_started_utc` 추가, `--keep-within-daily`. 복원 검증 스크립트(J2, 시각 검사, J4)를 만든다 | 셸 스크립트와 테스트 |
| C (작게) | Java: 워터마크 줄을 아는 엄격 판독, 키 검증 명령. 정리는 앱을 멈추고 실행하는 명령으로 만든다 | Java와 테스트 |
| D (선택) | 고정 잠금 파일과 무중단 정리, 배포 버전 게이트 | Java, 배포 스크립트 |

A만으로 #17의 "실제 OCI 밖 사본, 리허설 기록, 운영 DB 무덮어쓰기"를 채울 수 있다. 공개 보유기간을 실제 절차와 맞추는 것은 B·C 뒤에 한다.

## 5. 검증 계획 (대역·격리 PG·로컬 restic, 실제 계정 없이)

- **보관**: 로컬 restic으로 날짜를 조작한 snapshot을 만든다. keep-daily와 keep-within-daily를 비교한다. 백업이 빠진 날, 미래 시각 snapshot, `--max-unused 0`과 기본값의 남는 크기를 확인한다.
- **copy**: copy가 시각과 호스트를 보존하는지, 원본에 `--no-lock`을 줄 때 동작과 잠금 객체 생성 여부, 서버 forget이 겹칠 때의 실패 형태를 확인한다. 로컬 저장소 두 개로 하고, 읽기 전용은 파일 권한으로 흉내 낸다.
- **시각**: `pg_restore --list` 헤더의 생성 시각 형식과 시간대를 확인한다. `dump_started_utc ≤ createDate`. 시각이 없거나 해석할 수 없는 메타를 거부하는지 확인한다.
- **대장**:
  - nil 워터마크 줄이 있는 대장을 **현재 main의 재적용 명령**으로 읽어 성공하고 0행 삭제인지 확인한다(이전 버전 호환).
  - J2가 다음을 잡는지 확인한다: 빈 대장, 잘린 대장, 더 오래된 대장, 다른 경로.
  - 시계 역행 줄이 앞부분 정리에서 남는지 확인한다.
  - 정리 중 rename 직전·직후에 kill했을 때 결과를 확인한다.
- **R2 회귀**: 백업 → 해지 → 복원 시나리오에서 J1 규칙으로 해지가 유지되는지 확인한다. 옛 runbook 절차로는 되살아나는 것을 대조한다.
- **무중단 정리(D를 할 때만)**: 다른 JVM 두 개가 append하는 중에 정리했을 때 유실 0, 옛 버전 writer가 섞이면 유실이 재현되는지 확인한다(게이트가 필요하다는 근거).

## 6. 사용자 결정이 필요한 것

1. 보관 정의: restic 옵션과 한계 고지(권장) / 지금 시각 기준 스크립트에서 최신 1개를 남김 / 0개 허용.
2. `P`(복원 허용 최대 나이), `m`, 줄 보유 하한, 정리 주기의 값.
3. 대장 정리 방식: 앱 정지 정리(권장 첫 단계) / 무중단(D) / 당분간 정리하지 않음(고지는 "미정").
4. PC 자격 범위: §3.1 a→b→c 중 리허설 결과로 허용할 최저선.
5. OCI `--max-unused 0` 여부(요청 수 실측 뒤).
6. PC pull 유예와 경보 채널, 처리방침에 넣을 §3.7 문구.
7. 복원 때의 중복 발송(§3.6 끝)을 #17에서 다룰지, 별도 이슈로 둘지.

## 7. 하지 않은 것

- 실제 OCI 버킷·IAM·PC 저장소·작업 스케줄러를 만들거나 조회하지 않았다. 어떤 키·env·DB에도 접근하지 않았다.
- restic copy 동작, 작업 스케줄러 설정 이름, pg_dump 생성 시각의 시간대 형식, OCI 패턴 문법, Customer Secret Key와 IAM의 관계는 문서로 확인하지 못했다. 위에 확인 필요로 표시했다.
- 법적 보유기간, 법정 고지의 적합성은 판단하지 않았다.
