# PR #11 수집 실행기 독립 리뷰 · agy

- **대상**: PR `#11` (`feat/#4-collector-runtime`, base `feat/#3-ingestion-core`)
- **검토 기준 커밋 (고정, 읽기 전용)**: `7b3eb31aa218029f7f54f07895426c4bb8697ab1` (`fix: bound slow response bodies by the request deadline (#4)`), `#3` 기준점 `925aa18e4f5bd6f426b540ac20deb070328239a5`
  - 작업 트리 가변 파일 대신 `git show 7b3eb31aa218029f7f54f07895426c4bb8697ab1:...`로 고정해 읽었다. 코드·설정·브랜치 수정 및 테스트 재실행은 하지 않았다.
- **선행 검토 원천**: `docs/prd.md` (FR-C1·C5·C6·C7, G3·G6), `docs/adr/0003-schedule-dedup-by-slot.md`, `docs/adr/0010-rate-limit-and-budget.md`, `docs/adr/0014-initial-settings.md`, `docs/design/routes.md`, `docs/design/erd.md`, `V3__collector_runtime_records.sql`, `ingestion/runtime/*` 14개 소스, 테스트 5개, `docs/ops/collector.md`, `sol-collector-lock-proposal.md`, `sol-collector-result.md`
- **사후 교차 대조**: `collab/2026-11-15-review/11-codex-collector-review.md`, `collab/2026-11-15-review/11-review-decisions.md`, `collab/reviews/pr-11-claude.md`

---

## 1. 핵심 판정

제출 커밋 `7b3eb31` 기준 구현은 `REQUIRES_NEW` 기반 일 예산 원자 예약/900 경계, 정기 수집 회차 `collect_run(kind, slot)` UNIQUE 선점, 비밀값·본문 배제 고정 오류 코드, `7b3eb31`의 느린 HTTP 본문 전체 타임아웃 보완(`sendAsync(...).get(timeout)`) 등 핵심 안전 장치를 잘 갖추고 있다.

다만 **독립 검토에서도 아래 High 3건 · Medium 3건**이 확인되었으며, 이 중 핵심 결함들은 사후 대조한 `11-codex-collector-review.md` · `pr-11-claude.md` · `11-review-decisions.md`에서 **이미 수정 지시된 상태**임을 확인했다. 추가 실무 결함(`erd.md` V3 누락, 예외 시 누적 카운트 손실, `DetailCollector` 429/예외 처리 등)을 함께 남긴다.

---

## 2. 독립 검토 발견 (7b3eb31 기준)

### 2-1. High (반드시 수정 필요)

1. **[H1 / 상세 예산·무제한 재시도] `DetailCollector`가 실패 상세를 매시간 재시도하고 `REGULAR` 몫을 보호하지 않아 일 예산(900)을 고갈시킬 수 있음 (FR-C6, FR-C7, ADR-0010)** `[이미 수정 지시됨]`
   - `DetailCollector.collect`는 `WHERE status='OPEN' AND from_backfill=false AND detail_status<>'DONE'`로 조회하므로 `'FAILED'`로 바뀐 공고도 매 정기 회차(1일 24회)마다 재시도된다.
   - 공고별 실패 상한, sweep당 호출 상한, 정기 수집 잔여 몫(예: 남은 시간 수 등) 예약이 모두 없어, 공용 `api_budget`(900)을 `DETAIL`이 끝까지 소진해 후속 `REGULAR` 수집이 `BUDGET_EXHAUSTED`로 막힐 수 있다.
   - 특히 실제 공식 `detail` API 응답은 아직 미검증(`sol-collector-result.md`)이므로, 실응답 구조가 swagger 가정과 어긋나면 신규 공고가 대량으로 `INVALID_DETAIL` → 매시간 재호출 상태에 빠진다.
   - **추가 관찰**: `DetailCollector.collect`는 `HTTP_RATE_LIMIT`(429)이나 `INVALID_DETAIL`을 만나도 `BUDGET_EXHAUSTED`/`INTERRUPTED`가 아니면 루프를 끊지 않고 남은 `pending` 공고를 계속 호출한다. 또한 `save()`와 `collect()` 양쪽에서 `failed(sn)`를 중복 호출하고, 반면 `catch (RuntimeException)`에서는 `failed(sn)`를 부르지 않아 `detail_status`가 `'NONE'`으로 남는다.

2. **[H2 / 재개 정확성] `CollectionRuns.completedMonthSns`가 완료 월 행이 아닌 모든 시도의 최신 완료 페이지를 재합산해 `BACKFILL_PAGE_MISMATCH`를 유발함 (FR-C1)** `[이미 수정 지시됨]`
   - `BackfillCollector.collect`는 월 완료 여부를 `completedMonthSummary`(`page_no IS NULL AND result='COMPLETE'`)로 판단하면서, SN 합집합은 `completedMonthSns`로 조회한다.
   - `completedMonthSns`는 `WHERE kind='BACKFILL' AND range_from=:f AND range_to=:t AND page_no IS NOT NULL AND result='COMPLETE'`에서 `DISTINCT ON (page_no)`로 페이지별 최신 성공 행을 모두 끌어온다.
   - 이전 미완료 시도에서 `page_no=2`까지 완료된 뒤 upstream `totalCount`가 줄어 `--collector.backfill-restart-pages=true`로 `page_no=1`만 받아 월을 `COMPLETE`한 경우, 이후 재개 시 `completedMonthSns`가 이전 시도의 `page_no=2` SN까지 합쳐 `sns.size() != completed.get().unique()` → `BACKFILL_PAGE_MISMATCH`로 영구 실패한다.

3. **[H3 / 실패 페이지 반영] `BackfillCollector.month`가 DB 커밋 이후에 페이지 검증·기간 검증을 수행해 실패 페이지 행이 공고 테이블에 남음 (FR-C1)** `[이미 수정 지시됨]`
   - `BackfillCollector.month`는 `ingestor.upsertPage(...)`로 `posting`·`institution`·`posting_code`·`posting_change`를 별도 트랜잭션에 먼저 커밋한 뒤에 `validate(...)`(`BACKFILL_COUNT_CHANGED`, `BACKFILL_PAGE_MISMATCH`, `BACKFILL_DUPLICATE_SN`)와 `runs.withinRange(...)`(`BACKFILL_RANGE_MISMATCH`)를 실행한다.
   - 그 결과 `collect_run` 페이지는 `FAILED`로 기록되고 재개 시 건너뛰지 않지만, 기간 밖 행(`pbancBgngYmd` 불일치)이나 잘못된 페이지의 변경사항은 이미 DB에 커밋돼 남는다. `BackfillRuntimeTest.outOfRangeRowsFailMonthAndNoHistoricalDetailIsRequested`도 예외만 확인하고 `posting` 테이블 무변경을 확인하지 않는다.

### 2-2. Medium (머지 전 보완 권고)

4. **[M1 / 잠금 필요성·세션 폐기] `AdvisoryLease.Lease.close()`에서 unlock 실패 시 잠금을 쥔 물리 커넥션이 Hikari 풀로 반납될 수 있음** `[이미 수정 지시됨]`
   - **잠금 필요성 판단**:
     - `REGULAR`는 ADR-0003(`collect_run(kind, slot)` UNIQUE INSERT)만으로 동시 실행이 방지되며 `AdvisoryLease`를 쓰지 않는다(ADR-0003 부합).
     - 반면 수동 `BACKFILL`은 실행마다 새 `slot`을 사용하고, `DETAIL`은 슬롯이 `{regularSlot}/{sn}`이라 sweep이 1시간을 넘기면 서로 다른 슬롯 간 중복 호출이 가능하다. 따라서 회차 소유권을 대체하지 않는 범위에서 보조 세션 잠금을 두는 문제의식 자체는 타당하다(`sol-collector-lock-proposal.md`).
   - **세션 폐기 결함**:
     - `AdvisoryLease.Lease.close()`는 `try (connection; var statement = connection.prepareStatement("SELECT pg_advisory_unlock(?)"))`를 사용한다. TCP 단절(`pg_terminate_backend`)이면 Hikari가 폐기하지만, 연결은 살아 있는데 `pg_advisory_unlock`이 SQL 오류/타임아웃/취소로 실패하거나 `false`를 반환하면 `connection.close()`로 풀에 돌아가고 PostgreSQL 세션에는 advisory lock이 남는다.
     - 추가로 `AdvisoryLease`는 잠금당 Hikari 커넥션 1개를 작업 전체 기간 동안 점유하고 PgBouncer transaction pooling에서 사용할 수 없으며, `BackfillCollector` 작업이 성공해 `COMPLETE` 커밋까지 끝난 직후 DB 단절로 `close()`만 실패하면 `COMPLETE` 기록과 달리 호출자는 `DATABASE_FAILURE`를 받게 된다.
     - `CLAUDE.md` 규칙상 채택 시 ADR-0003을 대체하지 말고 별도 ADR(또는 결정 문서)로 연결 생명주기·반환·단절 한계를 확정해야 한다.

5. **[M2 / `Retry-After`] `RecruitmentClient.retryAfter`에 대기 상한이 없어 스레드·잠금이 장시간 정지됨 (FR-C6)** `[이미 수정 지시됨]`
   - `RecruitmentClient.retryAfter`는 429 응답의 초 단위 또는 RFC-1123 날짜 차이(`Duration.between`)를 상한 없이 그대로 `sleeper.sleep(delay)`에 넘긴다.
   - `Retry-After: 86400`이나 먼 미래 시각이 오면 `@Scheduled` 단일 스레드나 `AdvisoryLease` DB 커넥션·잠금을 장시간 붙잡는다. 상한을 초과하는 값을 잘라 조기 재시도하면 서버 지시를 어기므로, 인라인 허용 상한 초과 시 대기 없이 `HTTP_RATE_LIMIT` 실패로 종료해 다음 회차에 넘겨야 한다.

6. **[M3 / 스케줄 점유·예외 경계] `RegularCollector`가 단일 `@Scheduled` 스레드에서 `DetailCollector`를 동기 호출하며 종료 대기 설정이 없음 (G3, ADR-0014)** `[이미 수정 지시됨]`
   - `CollectorConfiguration`에 `@EnableScheduling`만 있고 `application.yml`에 `spring.task.scheduling.shutdown.await-termination` / `await-termination-period`가 없어 기본 단일 스레드에서 정기 수집 직후 `details.collect(slot)`가 완료될 때까지 대기한다. 다건 타임아웃/재시도가 길어지면 다음 `:07` 정기 수집이 지연/누락될 수 있다.
   - **추가 관찰**: `RegularCollector.collect`에서 `details.collect(slot)`가 `try/catch` 블록 바깥에 있어, 정기 수집 완료 후 `AdvisoryLease`가 `DATABASE_FAILURE`를 던지면 `RegularCollector` 밖으로 미포착 예외가 전파된다.

### 2-3. Low · 문서 · 관측 보완 (신규 관찰 포함)

7. **[L1 / 예외 시 누적 카운트 손실]** `BackfillCollector.collect`의 `catch (CollectionFailure)`와 `BackfillCollector.month`의 `catch (RuntimeException)`가 `runs.fail(run, code)`로 `null, null, null`을 전달해 앞선 월·페이지에서 이미 누적한 `total`/`received`/`unique` 기록이 사라진다(`IngestionException` 분기는 누적치를 보존함).
8. **[L2 / 운영 순서 미명시]** `docs/ops/collector.md`에 "최초 적재 완료 후 `COLLECTOR_ENABLED=true` 활성화" 순서가 적혀 있지 않아, 최초 적재 전에 정기 수집을 먼저 켜면 진행 공고 전체가 `from_backfill=false`로 등록되어 첫 상세 sweep에 대량 API 요청이 발생한다. `[이미 수정 지시됨]`
9. **[L3 / `erd.md` V3 미반영]** `V3__collector_runtime_records.sql`이 `collect_run`에 7개 컬럼(`period_from`, `period_to`, `range_from`, `range_to`, `page_no`, `unique_count`, `received_sns`)을 추가하고 `api_attempt` 테이블을 신설했으나 `docs/design/erd.md`에 반영되지 않았다(`CLAUDE.md` 문서 일치 규칙).
10. **[L4 / `collector_runs_unfinished` 지표 해석]** `CollectorMetrics`의 `collector.runs.unfinished`(`SELECT count(*) FROM collect_run WHERE result IS NULL`)는 중단된 과거 수동 백필 행을 `result IS NULL`로 영구 보존하는 정책과 맞물려, 과거 1회라도 중단된 백필이 있으면 이후 정상 운영 중에도 항상 `>= 1`이 된다(대시보드/경보 기준 시 주의 필요).
11. **[L5 / `7b3eb31` 느린 본문 타임아웃 및 실응답 미검증 경계]**
    - `7b3eb31`의 `RecruitmentClient.send`는 `sendAsync(..., BodyHandlers.ofString)`의 `CompletableFuture.get(timeout)`과 `cancel(true)`로 헤더 후 지연 본문까지 전체 요청 타임아웃에 묶었으며 `requestTimeoutAlsoBoundsBodyAfterHeadersArrive`로 검증되었다. 다만 응답 본문 바이트 크기 상한은 없다.
    - 공식 `apis.data.go.kr`의 실제 봉투·빈 달 응답·`detail` 실응답 및 운영 500 경보 수신은 아직 대역/swagger 계약 수준이며 실환경 확인 게이트로 남아 있다.

---

## 3. 교차 검토 대조 (`11-codex-collector-review.md`, `11-review-decisions.md`, `pr-11-claude.md`)

| 검토 주제 | `11-codex-collector-review.md` (309216a) | `pr-11-claude.md` (309216a) | `11-review-decisions.md` (인계 지시) | `agy` 독립 검토 (`7b3eb31`) 대조 결과 |
|---|---|---|---|---|
| **잠금 필요성 · 세션 폐기 (`AdvisoryLease`)** | 수정 #2: unlock 실패 시 물리 연결 폐기 보장 + 재선점 테스트. ADR은 교차 검토 후 확정 | 적정성·범위 타당, M3: unlock 실패 시 풀 반납 위험, `evictConnection`/`abort` 및 `pg_locks` 확인 제안, 별도 ADR 권고 | **수정 지시 #1**: unlock 실패 시 물리 연결 폐기 | **동의 + `[수정 지시됨]`**. 추가로 `pg_advisory_unlock`의 `false` 반환 확인 및 `COMPLETE` 직후 `close()` 예외 전파 경계 확인 권고 |
| **재개 · 완료 월 SN 집합** | 수정 #1: 완료 월 행에 검증한 SN 집합 함께 저장, 축소 재시작 후 스킵 PG 테스트 | 문제없음으로 판단(축소 재시작 시 옛 페이지 혼입 미발견) | **수정 지시 #1**: 완료 월 SN 집합 스냅숏 저장 | **codex 지적과 일치 + `[수정 지시됨]`**. `completedMonthSns`의 옛 시드 `page_no` 잔존으로 `BACKFILL_PAGE_MISMATCH` 발생 확인 |
| **실패 페이지 DB 반영** | 수정 #3: `upsertPage` 후 검증 문제, `#3` 코어 계약 확장으로 반영 전/트랜잭션 내 검증 | L1(Low): `#3` 단일 파싱 계약상 불가피, 멱등하므로 `collector.md`에 안내만 추가 | **수정 지시 #1**: `#3`에서 사전/트랜잭션 내 검증 계약 확장 후 `#4`에서 반영 | **codex/결정과 일치 + `[수정 지시됨]`**. `outOfRangeRowsFailMonth` 테스트에 `posting` 무변경 검증 추가 필요 확인 |
| **상세 수집 · 상세 예산** | 수용 범위에 포함(세부 제한 미지적) | **H1**: `FAILED` 무제한 시간별 재시도 + `REGULAR` 몫 고갈 위험, 실측 497건 기준 위험 정량화 | **수정 지시 #2**: 상세 실패 상한, sweep 상한, 정기 수집 몫 원자적 보존, ADR-0014 정합 | **claude/결정과 일치 + `[수정 지시됨]`**. `429` 시 잔여 공고 루프 지속, `save()`/`collect()` 이중 `failed(sn)`, `RuntimeException` 시 `'FAILED'` 누락 추가 관찰 |
| **`Retry-After`** | 미지적 | **M1**: 상한(예: 60초) 초과 시 `HTTP_RATE_LIMIT` 실패 | **수정 지시 #3**: 허용 대기(예: 60초) 초과 시 자르지 말고 즉시 `HTTP_RATE_LIMIT` 종료 | **claude/결정과 일치 + `[수정 지시됨]`**. 큰 초/미래 HTTP 날짜 경계 테스트 필수 |
| **스케줄 스레드 점유 · 종료 대기** | 미지적 | **M2**: 동기 실행으로 다음 `:07` 지연 가능, 종료 대기 30초 설정 누락 | **수정 지시 #4**: sweep 상한으로 장시간 점유 방지 + 스케줄러 종료 대기 30초 설정 | **claude/결정과 일치 + `[수정 지시됨]`**. `details.collect(slot)` 예외 미포착(`RegularCollector`) 추가 확인 |
| **느린 본문 timeout (`7b3eb31`)** | `309216a` 시점 검증 보고 대기 | `309216a` 시점 미적용 | `7b3eb31` 제출 후 인계 | **해결 확인**: `sendAsync(...).get(timeout)` + `requestTimeoutAlsoBoundsBodyAfterHeadersArrive` 테스트로 보완됨 |
| **운영 순서 · 미검증 게이트 · 문서** | 500 경보/공식 API/3년 적재 후속 게이트 | H1(4) 최초 적재 → 정기 활성화 순서 명시, L2 빈 달 응답 확인, L3 `collect_run` 누적 | **수정 지시 #5**: 운영 문서 활성화 순서 명시, 미검증 게이트 유지 | **일치 + `[수정 지시됨]`**. 추가로 `erd.md`에 `V3` 반영 누락, `collect`/`month` `fail()` 누적치 손실, `runs_unfinished` 지표 해석 주의 추가 |

---

## 4. 후속 리뷰 시 확인 기준 (Claude 수정 완료 후)

`11-review-decisions.md`에 따라 Claude가 `#3` 사전/트랜잭션 내 검증 계약 확장 후 `#4`를 재구성하면 다음 항목을 새 head 기준으로 재검증한다.
1. 완료 월 행의 SN 스냅숏 저장 및 "다중 페이지 완료 → 실패 → upstream 축소 → `restart-pages=true` 완료 → 후속 실행 월 건너뛰기" 실제 PostgreSQL 검증
2. `AdvisoryLease` unlock 실패 시 물리 커넥션 폐기 및 타 커넥션 재선점 검증, ADR(또는 채택 근거) 정리 여부
3. 실패 페이지(`out-of-range` / `changed-total` / `duplicate`) 시 기존 정상 공고 변경 포함 `posting` 테이블 무변경 검증
4. `DetailCollector` 실패 상한/정기 몫 예약/sweep 상한 및 `항상 INVALID_DETAIL` 대역 다회차 후 정기 수집 예산 확보 테스트
5. `Retry-After` 상한 초과 시 조기 재시도 없이 `HTTP_RATE_LIMIT` 즉시 실패 테스트 및 `spring.task.scheduling.shutdown.await-termination` 30초 설정
6. `docs/design/erd.md` V3 동기화 및 `docs/ops/collector.md` 최초 적재 → 정기 활성화 순서 명시 여부

---

## 5. 재검수 (고정 head `e7099226dd93693961ae29250131c8d2025b242b` 기준)

- **검토 방식**: `C:/Users/lcj/orca/workspaces/my-project/issue-4-collector-runtime`의 작업 트리를 읽지 않고 `git show e7099226dd93693961ae29250131c8d2025b242b:...` 및 `git diff 7b3eb31..e709922`로만 읽기 전용 검수했다(브랜치·코드·테스트·설정 미수정, API 추가 호출 없음, push/머지 없음).
- **포함 커밋**: `#3` 계약 v3 `aab6d5b` → merge `bd6b706` → `#4` 수정 `e709922` (`claude-collector-fix-result.md` 보고 기준 로컬 71 tests 통과; 원격 `43681733201` `#3` CI 성공, `#4` `e709922` CI는 진행/별도 추적).

### 5-1. `11-review-decisions.md` 필수 수정 7항목 판정

| 항목 | 판정 | 코드·설정·테스트 확인 근거 (`e709922`) |
|---|---|---|
| **1. 월 스냅숏 (`CollectionRuns.completedMonth`)** | **해결됨** | `completeMonth`가 월 요약 행(`page_no IS NULL`)에 검증 완료 `seen` 집합을 `received_sns`로 저장하고, `BackfillCollector.collect`는 `completedMonth`로 그 동일 행의 `total`·`received`·`unique_count`·`received_sns`만 읽는다(`completedMonthSns` 제거). `BackfillRuntimeTest.completedMonthIsSkippedByItsOwnSnSnapshotAfterUpstreamShrankAndPagesRestarted`로 2001건→실패→축소(1건) `restart-pages=true` 완료→후속 스킵 실 PostgreSQL 검증 확인. |
| **2. 실패 페이지 선검증 (`ParsedPage` + `BackfillCollector.month`)** | **해결됨** | `#3` `PostingIngestor.parsePage(body)`가 DB 접근 없이 `ParsedPage`(`totalCount`, `sns`, `beginsWithin`)를 반환하고, `BackfillCollector.month`가 `validate`·`beginsWithin` 통과 후에만 `upsertPage`를 호출한다(`withinRange` 제거). `BackfillRuntimeTest.rejectedPagesCommitNoChangeToStoredPostings`에서 4종 실패(`BACKFILL_RANGE_MISMATCH`, `BACKFILL_COUNT_CHANGED`, `BACKFILL_DUPLICATE_SN`, `BACKFILL_PAGE_MISMATCH`) 시 기존 `sn=100` 무변경 및 `posting_change=0` 검증 확인. |
| **3. 전용 물리 연결 폐기 (`AdvisoryLease`)** | **해결됨** | `AdvisoryLease(JdbcConnectionDetails)`가 Hikari 밖 `DriverManagerDataSource`로 매 호출 새 물리 연결을 열고, `Lease.close()`는 `try (connection; statement)`로 `pg_advisory_unlock` 예외 또는 `false` 반환 시에도 물리 연결을 닫아 백엔드 종료로 잠금을 해제한다. `RuntimeDatabaseTest.leaseClosesItsPhysicalConnectionWhenUnlockFailsOnALiveSessionSoAnotherOwnerAcquires`(SQLState `57014` + `pg_stat_activity` PID 소멸 후 재선점) 및 `CollectorWiringIntegrationTest.leaseConnectionIsNotBorrowedFromThePool` 확인. |
| **4. 원자적 정기 예산 72 (`ApiBudget`)** | **해결됨** | `REGULAR_SHARE = 72` (ADR-0014: 24회 × 3시도). `"REGULAR"`는 `stopLimit`(900), `"DETAIL"`/`"BACKFILL"`은 `stopLimit - 72`(828)를 단일 `INSERT ... ON CONFLICT DO UPDATE ... WHERE count < :limit`로 원자 검사한다(`limit < 1` 방어 포함). `RuntimeDatabaseTest.nonRegularKindsStopShortOfTheRegularDailyShare` 및 `alwaysInvalidDetailsAcrossManySlotsLeaveTheRegularShare` 검증 확인. |
| **5. 상세 3실패 · 30건 · 10분 (`DetailCollector`, `CollectorMetrics`)** | **해결됨** | `MAX_FAILURES = 3`, `MAX_PER_SWEEP = 30`, `MAX_SWEEP = 10분`. `FAILURES` 서브쿼리가 `BUDGET_EXHAUSTED`/`INTERRUPTED`를 제외한 `FAILED` 횟수를 세어 3회 도달 공고를 중단하고, `posting.detail_status='FAILED'` 유지 및 `collector.detail.gave.up` 게이지 노출 확인. `RuntimeDatabaseTest.detailRetriesStopAfterThreeFailedRunsAndTheGapStaysVisible`, `oneSweepStopsAtThirtyPostingsOrTenMinutes` 확인. |
| **6. `Retry-After` 60초 경계 (`RecruitmentClient`)** | **해결됨** | `MAX_INLINE_WAIT = 60s`. `retryAfter`가 `<= 60s`만 정확히 서버 지시 시간 대기(과거 날짜는 0초), `> 60s`(초·HTTP 날짜) 또는 비정상 문자열(`-5`, `+5`, overflow, `soon`, `""`)은 조기 재시도 없이 `Optional.empty()`로 1회 시도 만에 `HTTP_RATE_LIMIT` 즉시 종료. `RecruitmentClientTest.rateLimitWaitsOnlyForShortReadableDelaysAndNeverRetriesEarlierThanAsked` 확인. |
| **7. 스케줄러 종료 30초 + 운영 문서 순서** | **해결됨** | `application.yml`에 `spring.task.scheduling.shutdown.await-termination: true`, `await-termination-period: 30s` 설정 및 `CollectorWiringIntegrationTest.schedulerWaitsThirtySecondsForARunningCollectionOnShutdown` 검증, `docs/adr/0014-initial-settings.md` 동기화, `docs/ops/collector.md`에 "최초 적재 완료 후 `COLLECTOR_ENABLED=true`" 순서 명시 확인. |

### 5-2. 이전 `agy` 추가 발견 항목의 `e709922` 해결 여부 확인

| 이전 `agy` 발견 (`7b3eb31`) | `e709922` 상태 | 세부 확인 |
|---|---|---|
| **1. `docs/design/erd.md`에 V3 스키마 미반영 (L3)** | **미해결 (문서 누락 유지)** | `e709922`에서도 `docs/design/erd.md`에 `V3__collector_runtime_records.sql`의 `collect_run` 신규 7개 컬럼과 `api_attempt` 테이블이 아직 추가되지 않았다(`CLAUDE.md` 문서 일치 규칙 기준 머지/후속 시 보완 권고). |
| **2. `BackfillCollector` `collect`/`month` `fail()` 누적치 손실 (L1)** | **미해결 (Low 유지)** | `BackfillCollector.collect`의 `catch (CollectionFailure)` 및 `month`의 `catch (RuntimeException)`가 여전히 `runs.fail(..., code)`(`null, null, null`)를 호출해 앞선 월·페이지에서 누적한 `total`/`received`/`unique` 기록을 저장하지 않는다(`IngestionException` 분기만 누적치 전달). |
| **3. `DetailCollector` 이중 `failed(sn)`, `RuntimeException` 처리, `429` 루프 지속** | **완화됨 (핵심 위험 해소, 구조 경미 유지)** | `MAX_FAILURES=3`·`MAX_PER_SWEEP=30`·`MAX_SWEEP=10분`·`REGULAR_SHARE=72`로 예산 고갈 위험은 완전히 차단됨. 다만 `save()`/`collect()`의 이중 `failed(sn)` 호출, `catch (RuntimeException)` 시 `detail_status='FAILED'` 미갱신(`collect_run` `FAILED`는 정상 기록되어 3회 상한에는 포함됨), `429` 발생 시 같은 sweep 잔여 건 계속 시도, `RegularCollector.collect` 내 `details.collect(slot)` 예외(`DATABASE_FAILURE`) 미포착 경계는 그대로 남아 있다(정기 `collect_run`은 이미 `COMPLETE` 커밋된 상태). |
| **4. `collector_runs_unfinished` 지표 해석 (L4)** | **설명 보강으로 완화** | `docs/ops/collector.md`에 30초 종료 유예를 넘겨 중단된 회차도 `result IS NULL`로 남아 `collector_runs_unfinished`에 잡힌다는 점이 추가되었다. 과거 중단 수동 백필 행이 남으면 0으로 돌아가지 않으므로 M3 경보 설계 시 조건(`REGULAR` 최근 회차 등) 구분이 필요하다. |

### 5-3. Session Lock 정책 초안(`sol-collector-lock-proposal.md`) 대조 및 권고

1. **필요성과 범위 (기존 의견 유지 · 타당)**: `REGULAR`는 ADR-0003(`collect_run(kind, slot)` UNIQUE INSERT)이 회차 소유권을 완전히 보장하므로 잠금을 쓰지 않고, 수동 `BACKFILL`(매 실행 다른 `slot`)과 장시간 `DETAIL` sweep(`{regularSlot}/{sn}`)의 슬롯 간 중첩 방지에만 보조 잠금을 두는 경계는 타당하다.
2. **연결 생명주기 해소 확인**: `7b3eb31`의 Hikari 풀 반환 결함은 `e709922`에서 풀 밖 전용 물리 연결(`DriverManagerDataSource`) + 해제 결과 무관 연결 종료로 구조적으로 해소되었고, `pg_stat_activity` PID 소멸 및 Hikari 활성 연결 불변 테스트까지 확보됐다.
3. **남은 한계**: 앱당 최대 2개(blue/green 교차 시 DB 최대 4개)의 풀 밖 연결 여유분이 PostgreSQL `max_connections`에 필요하며, PgBouncer transaction pooling 미지원, 이종 호스트 DB 구성 시 `tcp_keepalives_idle` 필요, `COMPLETE` 커밋 직후 `close()` 단절 시 호출자 `DATABASE_FAILURE` 전파 가능성은 한계로 남는다(초안 문서에 명시됨).
4. **권고**: 구현과 회귀 검증이 충족되었으므로 ADR-0003은 수정하지 않고, `sol-collector-lock-proposal.md`의 범위·물리 연결 생명주기·한계를 **채택 ADR(예: `0016-supplemental-session-lock.md`)**로 승격해 확정하는 것을 권고한다. (이 문서 작업과 `erd.md` V3 보완 외에는 `#11`의 기능·안정성 수정 지시 항목이 코드·테스트 기준으로 모두 반영됨을 확인했다.)

### 5-4. 병행 공식 API 검증 결과 추가 출처 (`docs/#4-official-fixtures`, `b95fa67`, 추가 호출 없음)

- 본 재검수(`e709922`)와 별도로, `docs/#4-official-fixtures` 브랜치(`b95fa67`, 영속 로컬 DB `api_attempt.id=2,3,4`)에서 **공식 `apis.data.go.kr/1051000/recruitment` 실응답 3종이 이미 실물 확보**되었다(출처: [`official-fixtures/collab/2026-11-15-api-verification/agy-result.md`](../../workspaces/my-project/official-fixtures/collab/2026-11-15-api-verification/agy-result.md), `fixtures/alio-official-*.json`).
  - **공식 `list` (`497`건)**: `"data"`·`"pathParam"` 래퍼 없이 최상위 `{"resultCode":200,"resultMsg":"성공했습니다.","totalCount":497,"result":[...]}`이며 원소 `"item"` 래퍼도 없음. `#3 ListResponseParser.parse()` 파싱 일치(`totalCount=497`, `rowsSize=497`).
  - **빈 기간 `list` (`2010-01-01~2010-01-31`)**: `{"resultCode":200,"resultMsg":"성공했습니다.","totalCount":0,"result":[]}`. `e709922` `docs/ops/collector.md`에 남은 미검증 게이트("빈 달 응답 `resultCode` 3 여부")는 `resultCode=200` + `result=[]`임이 실측 확인됨.
  - **공식 `detail` (`sn=307032`)**: `result` 단일 객체이며 `steps`(4건)·`files`(3건)에 `"step"`/`"file"` 래퍼가 없어 `DetailCollector.save()` 계약과 일치함. 다만 실측 `steps`는 `sortNo`가 0부터 시작해 중복 행(`minStepSn`~`maxStepSn`)이 오고 `recrutPbancTtl`에 단계 라벨(`"진료교수"`, `"진료의사"`)이 들어가며 `recrutNope`/`aplyNope`이 `null`일 수 있음.
  - **참고**: `e709922` 본문의 `docs/ops/collector.md`는 이 실응답 확보 전 문구이므로, 후속/합본 시 공식 응답·빈 달 게이트 해제와 `.env` percent-encoded 키 입력 경계 단서(유효 `%XX` 단일 디코딩 필요 가능성)를 함께 반영하면 된다.

---

## 6. 최종 재검수 (고정 head `56faf0510c30d2b7cecc00a40e2757c2d509128e` / 코드 `5e2950bd93dc6a8f6b5b1fb1b5aff0dbf6aae9b3` 기준)

- **검토 방식**: `C:/Users/lcj/orca/workspaces/my-project/issue-4-collector-runtime`의 작업 트리를 읽지 않고 `git show 56faf0510c30d2b7cecc00a40e2757c2d509128e:...` 및 `git diff e7099226dd93693961ae29250131c8d2025b242b..56faf0510c30d2b7cecc00a40e2757c2d509128e`로만 읽기 전용 검수했다(공식 API 추가 호출·테스트/코드/설정/.env 수정·push/머지 없음).
- **원격 CI 확인 (`gh run view`)**:
  - 코드 커밋 `5e2950bd93dc6a8f6b5b1fb1b5aff0dbf6aae9b3`: GitHub Actions run [`43684660748`](https://github.com/Lcjam/gongchae-bell/actions/runs/43684660748) **`completed / success`** (ARM64).
  - 최종 문서·보고 포함 head `56faf0510c30d2b7cecc00a40e2757c2d509128e`: GitHub Actions run [`43684804047`](https://github.com/Lcjam/gongchae-bell/actions/runs/43684804047) **`completed / success`**.
  - Claude 로컬 보고(`claude-collector-fix-result.md` @ `56faf05`): `gradlew check` 79 tests 통과(회귀 red 확인 포함).

### 6-1. `11-second-review.md` 1~6번 및 `ADR-0016` 대조 · 회귀 테스트 판정

| 항목 | 판정 | 코드·문서 확인 (`5e2950b` / `56faf05`) | 회귀 테스트 검수 |
|---|---|---|---|
| **1. 설계 문서 V3 동기화 (`docs/design/erd.md`)** | **수용** | `erd.md`에 `V3__collector_runtime_records.sql`의 `collect_run` 7개 열(`period_from`, `period_to`, `range_from`, `range_to`, `page_no`, `unique_count`, `received_sns`), 부분 인덱스(`collect_run_backfill_progress`), `api_budget` 72회 정기 몫, `api_attempt (V3)` 스키마·제약 및 명령/월/페이지 집계 범위 설명이 모두 추가되어 실제 스키마와 일치한다. | N/A (문서·스키마 정합성 확인) |
| **2. 거부 페이지 월 집계 오염 (부작용 없는 `seen` 검사)** | **수용** | `BackfillCollector.validate(...)`가 `seen`/`expected`를 변경하지 않는 순수 검증 메서드로 분리되었고, `month(...)`는 `validate` → `beginsWithin` → `upsertPage` → `completePage`가 모두 끝난 뒤에만 `expected`, `received`, `seen.addAll(accepted.sns())`를 갱신한다. | `BackfillRuntimeTest.rejectedPagesCommitNoChangeToStoredPostings`: `BACKFILL_RANGE_MISMATCH`(`null\|0\|0`), `BACKFILL_COUNT_CHANGED`(`1001\|1000\|1000`), `BACKFILL_DUPLICATE_SN`(`1002\|1000\|1000`, 중복 `sn=100` 앞의 신규 `sn=50` 미반영 검증), `BACKFILL_PAGE_MISMATCH`(`null\|0\|0`)에서 월 행 오염 차단과 `posting`/`posting_change` 무변경을 정확히 잡는다. |
| **3. 실패 명령의 누적 요약 (`BackfillCollector.collect`)** | **수용** | `BackfillCollector.collect`의 `catch (CollectionFailure)`와 `catch (RuntimeException)`가 `runs.fail` 대신 `runs.finish(run.get(), "FAILED", ..., total, received, periodSns.size())`를 호출해 앞서 완료한 달의 합계를 보존한다. `erd.md`·`docs/ops/collector.md`에 집계 범위 분리도 명시되었다. | `BackfillRuntimeTest.failedCommandKeepsCompletedMonthTotalsAndTheFailingMonthIsOnItsOwnRows`: 10월(1건) 완료 후 11월 `HTTP_STATUS` 및 `IllegalStateException` 실패 시 `command/http`=`FAILED\|HTTP_STATUS\|1\|1\|1`, `command/crash`=`FAILED\|INGESTION_FAILED\|1\|1\|1` 보존 및 실패 월·페이지 행 분리를 검증한다. |
| **4. 상세 `FAILED` 일원화 (`DetailCollector`)** | **수용** | `save()` 내부 중복 `failed(sn)` 호출이 제거되고 `collect()`의 단일 `catch (RuntimeException)`에서만 `BUDGET_EXHAUSTED`/`INTERRUPTED`가 아닐 때 `failed(sn)`(`WHERE detail_status <> 'DONE'`로 기존 `DONE` 보존)과 `runs.fail(run.get(), code)`를 함께 기록한다. | `RuntimeDatabaseTest.unexpectedDetailErrorsMarkThePostingFailedAndStopAtTheCap`: `IllegalStateException("unexpected")` 대역에서 첫 회차에 `detail_status='FAILED'` 반영 및 5개 슬롯 중 `MAX_FAILURES=3`회(`INGESTION_FAILED` 3건) 중단 검증. 기존 `detailIsIndependentAndMalformedResponseKeepsSavedArrays`로 `DONE` 배열 보존 유지 확인. |
| **5. sweep 시간 상한 설명** | **수용** | `DetailCollector.MAX_SWEEP` 주석, `docs/adr/0014-initial-settings.md`, `docs/ops/collector.md`, `claude-collector-fix-result.md` 모두 "10분은 새 요청 시작 상한이며 진행 중인 1건 완료까지(Retry-After 60초 × 2 + 타임아웃 10초 × 3 등) 최대 약 150초가 추가될 수 있음"으로 일치되었다. | N/A (문서·코드 주석 일치 확인) |
| **6. 사용자 키 정규화 및 오류 비노출 (`CollectorProperties`)** | **수용** | `CollectorProperties.normalizeKey`가 `%` 미포함 원문(Decoding)은 유지하고, `%` 포함(Encoding)은 모든 `%XX`의 ASCII 16진수 여부와 UTF-8 유효성을 검사해 **정확히 1회만** percent-decode하며 평문 `+`를 공백으로 바꾸지 않는다. 오류 메시지는 `INVALID_SERVICE_KEY`로 고정되고 `toString()`에도 키가 노출되지 않는다. 이후 기존 `RecruitmentClient`가 정확히 1회 인코딩해 전송한다. | `CollectorPropertiesTest` 3건(`bothPortalFormsOfTheKeyBecomeTheSameRawKey`, `decodesOnceAndKeepsAPlainPlus`, `invalidPercentEscapeStopsStartupWithoutShowingTheKey` — 불완전/비16진수/이중인코딩/`%FF`/평문 `+`/`toString` 비노출) + `RecruitmentClientTest.encodingOrDecodingFormOfTheKeyGoesOnTheWireEncodedExactlyOnce`(Decoding·Encoding·소문자 hex 모두 동일 유선 쿼리 `serviceKey=fixture%2B%2F%3D+key&` 검증). |
| **7. `ADR-0016` 승격 및 공식 fixture/보고 반영** | **수용** | `docs/adr/0016-supplemental-session-lock.md`가 작성되고 `ADR-0003`·`adr/README.md`·`collector.md`·초안 파일과 상호 연결되었다(ADR-0003 불변, 물리 연결 수명·`max_connections` 여유분·PgBouncer 미지원·한계 명시). `agy-result.md` §5의 로컬 커밋/미push 정정도 사실과 일치한다. | `RuntimeDatabaseTest.officialDetailSampleIsStoredWithoutWrappers`(`sn=307032` `DONE\|4\|3\|true\|true`) 및 `BackfillRuntimeTest.officialEmptyPeriodResponseCompletesAMonthWithZeroPostings`(`0\|0\|0\|[]`)로 공식 실응답 구조 검증. |

### 6-2. 최종 결론 (PR `#11`)

- **필수 수정 사항**: **없음 (수용 / `#3·#4·#5` 합본 검증 단계 진행 가능)**.
- **유지되는 기명시 한계(비차단)**: 상세 sweep 중 DB 자체 장애로 실패 기록까지 못 남기면 정기 회차는 `COMPLETE`로 남고 해당 `DETAIL` 행은 `result IS NULL`이 될 수 있는 한계, 동일 sweep 내 `429` 발생 후 잔여 공고를 계속 시도하는 정책, `497`건 공식 목록 fixture 포함 합본 검증 및 실제 3년 최초 적재는 후속 합본·운영 게이트에서 확인한다.
