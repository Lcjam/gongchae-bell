# #17 Claude 독립안·교차 검토 추가 검토 · agy

- **작성자 / 일자**: 추가 agy / 2026-11-19
- **검토 대상**: [`01-claude.md`](01-claude.md), [`02-claude-review.md`](02-claude-review.md) (기존 [`01-agy.md`](01-agy.md)·[`02-agy-review.md`](02-agy-review.md) 원문은 그대로 보존)
- **제약**: 3자 종합 전 제안 단계. 새로운 서비스·유료 자원·보유기간·금지 규칙 미확정. 실제 source·시험·구현 설정·서버·계정·env·키·provider 변경/조회 없음.

---

## 1. 5대 핵심 쟁점별 반례 및 미해결 경계

### 1.1 nil UUID 워터마크와 기존 `readStrict`/`reapply` 호환성 · 초기 빈 대장 연속성
- **저장소 코드 확인 사실 (`ErasureJournal.java`, `ErasureReapplyCommand.java`, `V5__subscriptions.sql`)**:
  - `ErasureJournal.readStrict()`(lines 88-100)는 모든 줄을 `<36자 소문자 UUID>\t<Instant>`로 파싱하므로 `00000000-0000-0000-0000-000000000000\t<T_w>`를 문법상 정상 줄로 읽는다.
  - `ErasureReapplyCommand.reapply()`(lines 48-56)는 `DELETE FROM subscription WHERE id IN (:ids)`에 nil UUID를 포함해 실행하며, `SubscriptionService`가 `UUID.randomUUID()`로 생성한 실제 구독 행과 충돌할 가능성은 실질적으로 0이다(`deleted` 카운트에서 0행 매치).
- **반례와 주의 경계**:
  1. **구버전 재적용 명령과의 조용한 통과(silent pass) 위험**: 기존 `main`의 `ErasureReapplyCommand`는 nil UUID를 "해지 대상 1건"으로만 취급하고 **워터마크 시각(`T_w`)을 검사해 오래된 덤프를 거부하지 못한다**. 따라서 대장이 한 번이라도 워터마크로 잘린(`T_w` 기록) 뒤 **실수로 구버전 JAR로 재적용하면 0건 삭제 후 `exit 0`으로 통과**해 오래된 덤프의 해지 부활을 막지 못한다. 워터마크 도입 시점부터는 구버전 JAR의 `--alerts.erasure-reapply=true` 실행을 절차·게이트에서 원천 차단해야 한다.
  2. **관측·테스트·파싱 혼동**: `readStrict()` 반환 리스트 길이와 `backup-meta.txt`의 `erasure_lines`에 워터마크 1줄이 포함되므로, J4(`SELECT count(*) ...`)나 통계에서 "대장 줄 수 = 해지 건수"로 오해할 수 있다. 또한 테스트 fixture에서 `00000000-0000-0000-0000-000000000000`를 더미 구독 UUID로 쓰는 경우가 있다면 워터마크 파서와 충돌한다.
  3. **파일 중간의 nil UUID 혼입 경계**: 새 판독기는 **첫 줄만** 워터마크로 허용하고, 2번째 줄 이후(본문)에서 nil UUID가 나오거나 워터마크 줄이 2개 이상이면(예: 수동 병합·오동작) 즉시 `CorruptJournal`로 fail-closed 해야 한다.
  4. **해지 0건인 초기·빈 대장 연속성(`01-claude.md` §3.4 J2 반례)**:
     - M2 개시 후 해지가 0건이라 스냅샷 동봉 대장 `B`가 `0 bytes`인 동안에는, `L`이 정상적으로 유지된 빈 파일이든 누군가 사고로 호스트 대장을 삭제한 뒤 빈 파일로 새로 만들었든 **`B`가 `L`의 0바이트 prefix인지는 항상 참**이다(또한 M1 `erasure_included=false` 스냅샷에도 `B`가 없다).
     - 마찬가지로 정리(`T_w`)로 본문이 0줄이 되어 `L`이 `[워터마크 T_w]` 1줄만 남았고 `B`의 본문도 전부 `T_w` 미만이라 0줄만 남는 경우에도 바이트 prefix 비교만으로는 중간 재작성 여부를 판별할 수 없다.
     - 따라서 초기 `erasure_bytes == 0` 구간은 파일 바이트만으로 연속성을 입증할 수 없음을 명시적 한계로 인정하거나(J2만으로 만능 방어 불가), v2 대장을 도입할 때 초기 생성 시점부터 첫 줄에 초기 워터마크(`T_w0`)를 두는 방안을 선택지로 비교할 수 있다.

### 1.2 정지 정리 · 혼합 구버전 배포 · 워터마크 이행의 최소 구현
- **`01-claude.md` §3.5 · §4 단계화 평가**:
  - **정지 정리(앱 두 색 + 백업 타이머 정지 후 정리)**는 `ErasureJournal.append()`와 `backup.sh`가 동시에 돌지 않으므로 `fcntl` vs `flock` 비호환 및 구버전 inode 잠금 경합을 완전히 피할 수 있는 가장 작은 안전 경로다.
  - **보완할 운영 경계**:
    1. 정지 정리 중 `gongchae-backup.timer`가 `Persistent=true`로 재기동 직후 깨어나거나 수동 `backup.sh`가 실행되지 않도록 타이머 `stop` + `/opt/gongchae-bell/backup/backup.lock` 또는 서비스 마스킹/대기 확인을 절차에 넣어야 한다.
    2. **혼합 구버전 배포에서의 워터마크 이행**: 구버전 앱은 `append()` 시 파일 끝에만 추가하므로 대장 첫 줄의 nil UUID 워터마크가 있어도 쓰기는 깨지지 않는다. **그러나** 앞선 §1.1처럼 구버전 `ErasureReapplyCommand`는 워터마크를 해석하지 못하므로, **대장 정리는 반드시 새 버전 앱/JAR 배포가 완료된 뒤에만 최초 실행**해야 한다.
    3. **무중단 정리(단계 D)의 호환성 검증**: `02-claude-review.md` X7이 지적했듯 `blue_tag`·`green_tag` 상태 파일만 보지 말고, 실제 실행 중인 Docker 컨테이너 이미지/라벨(`docker compose ps`)에서 구버전 앱 프로세스가 0개인지 확인해야 하며, 동일 JVM 내 다른 채널로 `erasure.lock`을 닫으면 POSIX `fcntl` 잠금이 풀리는 함정([`fcntl_locking(2)`](https://man7.org/linux/man-pages/man2/fcntl_locking.2.html))도 차단해야 한다.

### 1.3 `dump` 하한 시각 · 대장 coverage · 복원 허용 판정의 시간 역행(Clock Rollback) 안전성
- **`01-claude.md` §3.3 · §3.5 점검**:
  - `01-claude.md`는 `pg_dump` 소스 순서(`CreateArchive` → `ConnectDatabase` → `setup_connection`)를 근거로 `; Archive created at ...` 및 `dump_started_utc`(`D_lo`)가 스냅샷 시각의 **하한**임을 확인했고, 앞부분만 지우는 정리 규칙으로 대장 뒤쪽의 역행 시각 줄을 보존한다. 이는 아주 견고하다.
- **시간 역행 관련 반례와 미해결 경계**:
  1. **반례 1 — "미래로 튀었다가 정상 복귀한 시계"에서의 백업 (`D_lo` 과대평가)**:
     - 백업 시점에 서버 시계가 실수로 미래(+2시간, 예: `05:00Z`인데 실제 물리 시각 `03:00Z`)로 가 있어 `D_lo = 05:00Z`, `backup_utc = 05:02Z`가 찍히고, 이후 NTP로 시계가 정상(`03:10Z`)으로 되돌아왔다고 하자.
     - `01-claude.md` §3.3의 `backup_utc <= 지금` 검사는 `03:10Z` 시점엔 잡지만, **실제 2시간이 지나 `지금 = 06:00Z`가 된 뒤 복원하면 통과**한다! 그동안 물리 시각 `03:05Z`에 일어난 해지(`erasure.log`에 `03:05Z` 기록)가 워터마크 `T_w = 03:30Z`로 정리돼 사라졌다면, 실제로는 `03:00Z` 스냅샷인 백업이 `D_lo = 05:00Z >= T_w + m`으로 통과해 **해지가 되살아난다**.
     - **완화 제안**: `backup.sh`가 직전 성공 백업의 `backup_utc`보다 `D_lo`가 크게 역행하거나(`D_lo < prev_backup_utc`), 복원 시 `pg_restore` 데이터 내 최신 `posting`/`collect_run`/`subscription` 시각(`max(updated_at)` 등)이 `D_lo`보다 미래이면 시계 이상으로 경보·차단하는 교차 검증을 둘 수 있다(단, 외부 API/시계 의존 없는 최소 보조 검증).
  2. **반례 2 — 대장 정리 J2(`B`에서 앞부분 제거)의 구현 함정**:
     - `01-claude.md` §3.4 J2("정리된 적이 있으면 `B`에서 `T_w`보다 앞선 앞부분 줄을 뺀 나머지")는 구현 시 반드시 §3.5의 앞부분 정리와 **동일하게 "첫 줄부터 `instant < T_w`인 동안만 건너뛰고, 처음 `>= T_w`를 만나면 멈춤"** 으로 구현해야 한다. 만약 `B` 전체를 대상으로 `instant >= T_w` 필터링을 하면 대장 뒤쪽에 남은 시계 역행 줄이 `B`와 `L`에서 어긋난다.
  3. **반례 3 — `m` 및 `2h` 고정 상한**:
     - `m = 1시간`, `backup_utc <= D_lo + 2h`는 시계 오차가 그보다 작다는 가정에 의존한다. 리허설 및 호스트 시간 동기화 상태(`timedatectl` / NTP) 확인 전까지는 제안값(미확정)으로만 둔다.

### 1.4 달력 14일 삭제 vs 최후 복원점 보존의 실제 양립 · 한계
- **확인된 공식 동작 (2026-11-19 확인, [`restic forget`](https://restic.readthedocs.io/en/stable/060_forget.html))**:
  - `--keep-daily 14`는 성공한 날짜 개수 기준이고, `--keep-within-daily 14d`는 **가장 최신 스냅샷 시각 기준** 14일이다. `prune --max-unused` 기본값은 `5%`다.
- **양립 불가능성 정리**:
  - **"달력 14일이 지난 모든 개인정보 백업을 예외 없이 물리 삭제"** 와 **"백업이 15일 이상 연속 실패하거나 PC가 장기 오프라인이어도 복원 가능한 최후 사본 1개는 보존"** 은 **동시에 성립할 수 없다**.
  - 특히 `01-claude.md` §3.2의 `--keep-within-daily 14d`는 백업 실패 기간이 길어질수록 `14일 + 서버 백업 실패 일수`(+ PC의 경우 `pull 실패/오프라인 일수`)만큼 과거 사본이 자동 잔존하므로, 실제로는 "최후 14일치 사본 보존(장애 시 14일 초과 잔존)" 쪽에 가깝다.
  - 반대로 벽시계 `now - 14d` 기준 스냅샷 ID를 골라 전부 지우면, 14일 이상 장애 시 복원점이 `0개`가 된다.
  - 또한 `02-claude-review.md` §3-2가 짚었듯, 서버 백업이 며칠 멈춘 상태에서 PC가 ① 로컬 `forget`을 돌리면 서버 실패 일수 + PC 실패 일수만큼 잔존 기간이 늘어난다. `prune`의 `--max-unused 0` 역시 OCI 월 요청 한도(5만 회)와의 트레이드오프가 있어 리허설 실측 후 결정해야 한다.
- **결론**: 코드 변경 전 사용자 결정으로 **(A) 정상 시 14일 + 장애/오프라인/prune 조각 잔존 한계를 처리방침에 정직하게 고지**할지, **(B) 벽시계 강제 만료(복원점 0개 허용)**로 갈지 택해야 한다.

### 1.5 복원된 과거 `PENDING`/`SENDING` 작업과 슬롯의 중복 발송 방지 절차 (발송 재개 전 필수 게이트)
- **현재 `main` 코드 확인 사실 (`V5__subscriptions.sql`, `DeliveryLedger.java`, `V1__catalog_and_ingestion.sql`, `HttpTransports.java`)**:
  - `V5__subscriptions.sql`: `notification` 상태는 `PENDING`, `SENDING`, `RETRY`, `SENT`, `FAILED`, `CANCELLED`, `UNCERTAIN`이고, `UNIQUE (subscription_id, kind, batch_key)` 및 `notification_item(subscription_id, sn, kind)`가 있다.
  - `DeliveryLedger.java`:
    - `SYNC_KINDS`(`EMAIL_CONFIRM`, `EMAIL_RECOVER`): `createSync()`가 `SENDING`으로 만들고, `AlertsMaintenance`가 5분마다 `sweepSync()`를 호출해 5분 넘은 `SENDING`을 `UNCERTAIN`/`FAILED`(`INTERRUPTED`)로 **영구 종결(재발송 안 함)**한다.
    - `ASYNC_KINDS`(`EMAIL_DIGEST`, `DISCORD_NEW`, `DISCORD_DEADLINE`, #7 foundation): `claimDue()`는 `status IN ('PENDING', 'RETRY') AND next_attempt_at <= :now`를 집어 `SENDING`으로 만들고, `recoverAsync()`는 `SENDING`이 5분 넘으면 `attempts < 3`이고 `created_at + 23h > now`일 때 `RETRY`로 되돌린다.
    - Resend `Idempotency-Key`(`HttpTransports.java` line 96): `DeliveryLedger.reserve()`가 매 시도(attempt)마다 전달받은 `key`로 갱신하므로, **백업 당시 `PENDING` 상태였던 행(시도 0회)은 백업 이후 첫 발송 때 새로 생성된 `Idempotency-Key`를 사용했기 때문에 복원된 덤프의 `idempotency_key`와 일치하지 않는다**(즉, Resend 측 24시간 멱등 키로도 백업 이후 이미 발송된 `PENDING`의 재발송을 막을 수 없다).
  - `V1__catalog_and_ingestion.sql`: `collect_run(kind, slot)`은 `BACKFILL`, `REGULAR`, `DETAIL`, `DIGEST`, `DEADLINE` 슬롯을 기록한다.
- **발송 재개(`ALERTS_ENABLED=true`) 전 필수 절차 (제안)**:
  1. **잔류 미완료 `notification` 큐 검수 및 재발송 차단**:
     - `ALERTS_ENABLED=false` 상태에서 복원 DB의 `SELECT kind, status, count(*) FROM notification WHERE status IN ('PENDING','RETRY','SENDING') GROUP BY 1, 2;`를 조회·기록한다.
     - 이미 백업 이후 외부 발송됐을 가능성이 있는 과거 미완료 행을 재발송하지 않으려면(중복 방지 우선 시), 발송 재개 전 `PENDING`·`RETRY`·`SENDING` 행(및 `notification_attempt` 중 `outcome IS NULL`인 행)을 `CANCELLED` 또는 `FAILED`(`last_error = 'RESTORE_ABORTED'`, `finished_at = now()`, `claimed_at = NULL`)로 일괄 마감한다. (반대로 "장애 공백 미발송 최소화를 위해 만료 전 건은 재발송 감수"를 택할지 여부는 운영 정책 선택이다. 단, **무검수 자동 발송 재개는 금지**한다.)
  2. **`SYNC_KINDS` `SENDING` 잔류 확인**:
     - `EMAIL_CONFIRM`/`EMAIL_RECOVER` 중 `SENDING`이 5분 미만으로 찍혀 남아 있으면 `sweepSync()` 대기 대신 위 일괄 마감에 포함해 1회성 토큰 재전송을 막는다.
  3. **과거 `collect_run` / `DIGEST` / `DEADLINE` 슬롯 및 `DISCORD_NEW` 재매칭 차단**:
     - 백업 시점과 복원 시점 사이에 이미 지나간 날짜의 `DIGEST`/`DEADLINE` 슬롯이 복원 DB의 `collect_run`에 빠져 있으면, `ALERTS_ENABLED=true` 전환 직후 스케줄러가 "오늘(또는 어제) 미실행 슬롯"으로 오인해 과거 공고를 다시 묶어 보낼 수 있다.
     - 또한 복원 후 `REGULAR` 수집이 다시 돌 때 백업~장애 사이 이미 수집·알림 완료됐던 공고들이 DB에 없으면 "신규 공고"로 삽입되면서 `DISCORD_NEW` 및 다음 `EMAIL_DIGEST` 대상으로 다시 잡힌다(이 부분은 `#7` 발송 스케줄러 구현이 `main`에 머지될 때 **"복원 직후 첫 수집/만회 발송의 기준 시각 또는 스킵 슬롯 마킹 절차"**로 반드시 함께 다뤄야 한다).

---

## 2. `01-claude.md` · `02-claude-review.md` 대비 추가 확인 및 합의/차이 요약

1. **Claude 미확인 항목(`02-claude-review.md` X1 · `01-claude.md` §3.1) 공식 소스 보충 해소**:
   - `02-claude-review.md` X1은 "`--no-lock`이 `--from-repo` 옵션 교환 뒤 `srcRepo`에 전달되는지 미확인"이라 적었다.
   - `restic` `v0.18.1` / `v0.19.1`의 [`internal/global/secondary_repo.go`](https://github.com/restic/restic/blob/v0.19.1/internal/global/secondary_repo.go)에서 `FillGlobalOpts`가 `dstGopts := gopts`로 `NoLock`을 복사한 뒤 `cmd_copy.go`에서 `gopts, secondaryGopts = secondaryGopts, gopts`로 교환함이 확인된다. 따라서 **`restic copy --no-lock`은 `srcRepo`에만 lock 생략을 적용하고 `dstRepo`에는 여전히 append lock을 건다.**
   - 또한 OCI IAM 기본 사용자/그룹/정책 생성은 OCI 기본 무료 Identity Domain 범위 내 추가 비용이 없다([OCI IAM Pricing/Docs](https://docs.oracle.com/en-us/iaas/Content/Identity/home.htm), 2026-11-19 확인).
2. **`02-claude-review.md` X3(정리 안전 조건 분리) 수용**:
   - 꺼진 PC·수동 사본의 0건을 서버에서 실시간 증명할 수 없으므로, **해지 부활 방지는 복원 게이트(`D_lo >= T_w + m` 및 살아 있는 호스트 대장 우선 J1)**가 맡고, **사본의 물리 삭제 확인은 고지 준수 증거**로 분리하는 방향에 동의한다.
3. **`02-claude-review.md` X4(기존 runbook R1·R2 결함) 전폭 동의**:
   - `docs/ops/backup-restore.md` §7의 운영 `postgres` 직접 `--clean` 복원(R1)과 백업 내 오래된 `erasure.log`로 호스트 대장을 덮어쓰는 절차(R2)는 즉시 고칠 문서 결함(단계 A)이다.
4. **`02-claude-review.md` X2(워터마크 위치) 합의 방향 및 조건**:
   - 2파일(`erasure.log` + `erasure.meta`) 동시 rename의 비원자성 반례를 수용하고, 대장 첫 줄 nil UUID 워터마크 방식이 단일 파일 원자 교체에 부합함에 동의한다. 단, **§1.1의 4가지 경계(구버전 재적용 JAR 차단, `erasure_lines` vs 해지 건수 구분, 본문 내 중복 nil UUID fail-closed, 초기 0바이트 대장 연속성 한계)** 를 구현·검증 항목으로 추가해야 한다.
5. **`02-claude-review.md` X8(반대 방향 잠금 충돌) 수용**:
   - PC pull이 원본 읽기 잠금을 잡은 상태에서 비정상 종료 시 30분 stale 전까지 서버 `forget --prune`이 실패(`RESTIC_PRUNE_FAIL`)할 수 있다는 지적을 수용한다. PC 측 `02:50~03:40 KST` 회피 가드 + 서버 runbook의 lock 확인/`restic unlock` 절차가 함께 필요하다.

---

## 3. 3자 종합을 위한 미확정 쟁점 정리

1. **보관·정리 모델**: `--keep-within-daily 14d` + 장애/오프라인/prune 잔존 한계 고지 vs `now` 기준 강제 만료(복원점 0개 허용).
2. **PC pull 자격**: `read objects` + `copy --no-lock`(서버 prune과 경합 시 copy 실패 감수) vs `locks/*` 한정 `manage objects` + 기본 잠금.
3. **대장 워터마크·초기 빈 대장 처리**: 첫 줄 nil UUID 워터마크 채택 시, 초기 빈 대장(`0 bytes`) 구간 연속성 한계 수용 여부 또는 초기 생성 시 `T_w0` 기록 여부.
4. **복원 시 과거 `PENDING`/`RETRY`/`SENDING` 및 공백 슬롯 처리**: #17에서 runbook 절차/게이트 원칙으로 확정할지, `#7` 병합 시 함께 코드로 강제할지(중복 발송 방지 vs 공백 만회 선택).
5. **대장 정리 구현 단계와 공개 보유기간**: 단계 A(runbook R1·R2 수정 + PC pull + 첫 격리 리허설) 후, 공개 출시 전 `erasure.log` 정리 방식(앱 정지 수동/명령 vs 온라인) 및 `P`·`m`·유예 기간·정리 주기 확정.
