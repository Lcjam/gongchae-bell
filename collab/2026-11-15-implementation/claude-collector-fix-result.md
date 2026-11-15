# #3·#4 수정 결과 (codex 11 결정, 11 재검수) · claude

> 2026-11-15 · 수정: claude (Sol 종료 후 #11 수정 소유권 인계) · 검수: codex · 다음 교차 리뷰: agy
> 지시: 원본 작업 경로의 `collab/2026-11-15-review/11-review-decisions.md`, `11-codex-collector-review.md`, `collab/reviews/pr-9-agy.md`. 2차: `collab/2026-11-15-review/11-second-review.md` 1~6번, `collab/reviews/pr-11-agy.md` §5, `official-fixtures-sol-review.md` → [아래 2차 절](#2차-수정-codex-11-재검수-16번)
> **claude가 #4를 수정했으므로 claude의 [PR #11 리뷰](../reviews/pr-11-claude.md)(원본 작업 경로)는 이 head의 독립 리뷰로 세지 않는다.**
> 3차: PR #9·#12·#10 머지 뒤 main 통합 → [아래 main 통합 절](#main-통합-pr-9--12--10-머지-뒤)
> 4차: 실제 적재에서 드러난 FR-C1 누락 수정(시작일 하한 전체 조회) → [아래 4차 절](#4차-최초-적재를-시작일-하한-전체-조회로-start_scan)
> claude는 머지하지 않았다. PR #9는 codex가 `0bf3d40`으로 머지했고 PR #11은 초안이다. 원본 작업 경로, 사용자 변경, 웹(#5), 배포, V1·V2·V3는 건드리지 않았다.

## 커밋과 CI
| 브랜치 | 커밋 | 내용 | 원격 CI |
|---|---|---|---|
| `feat/#3-ingestion-core` (PR #9) | `aab6d5b2a9b64fe4b767f8deb415c77417e4dd86` | #3 계약 v3: 파싱·반영 분리, null 본문 `NON_JSON`, routes 표 | [43681733201](https://github.com/Lcjam/gongchae-bell/actions/runs/43681733201) test·secrets 성공 |
| `feat/#4-collector-runtime` (PR #11) | `bd6b706` | #3 v3를 **merge**로 반영. Sol의 커밋 `8db36bc`·`b636168`·`309216a`·`7b3eb31`은 SHA 그대로 남는다(rebase·force push 없음) | — |
| 〃 | `e7099226dd93693961ae29250131c8d2025b242b` | 아래 수정 5건, 테스트, 문서 | [43682376057](https://github.com/Lcjam/gongchae-bell/actions/runs/43682376057) test·secrets 성공 |
| 〃 | `1a7e470`, `5f9db02` | #3 결과 문서 커밋을 merge하고 SHA·CI 기록, SHA 오타 정정 | [43682540599](https://github.com/Lcjam/gongchae-bell/actions/runs/43682540599) test·secrets 성공 |
| 〃 | `e3b3867`, `304389c` | **2차**: agy 공식 fixture 커밋 `5b78c55`·`b95fa67`(`docs/#4-official-fixtures`, 로컬 커밋, 원격 미push)을 cherry-pick. 내용 그대로, SHA만 다르다 | — |
| 〃 | `5e2950bd93dc6a8f6b5b1fb1b5aff0dbf6aae9b3` | **2차** 수정 6건, 테스트, 문서, ADR-0016 | [43684660748](https://github.com/Lcjam/gongchae-bell/actions/runs/43684660748) test·secrets 성공 |
| 〃 | `56faf0510c30d2b7cecc00a40e2757c2d509128e` | 2차 SHA·CI 기록 | [43684804047](https://github.com/Lcjam/gongchae-bell/actions/runs/43684804047) test·secrets 성공 |
| 〃 | `dc8d016c5e8dc654c4ffb84783880058a8ca49b9` | **main 통합**: `origin/main` `764819a`를 **merge**(부모 `56faf05`·`764819a`). 충돌 2파일 양쪽 보존 → [아래 main 통합 절](#main-통합-pr-9--12--10-머지-뒤). **커밋 메시지와 달리 상태 문서 2개 갱신은 이 커밋에 없다**(아래 `f75d8f1`) | [43704687576](https://github.com/Lcjam/gongchae-bell/actions/runs/43704687576) test(ARM64)·secrets 성공 |
| 〃 | `d729422b602a11cb540eed35323bdc286c3fa823` | main 통합 SHA·충돌·건수·CI 기록(이 문서) | [43704886330](https://github.com/Lcjam/gongchae-bell/actions/runs/43704886330) test·secrets 성공 |
| 〃 | `f75d8f1` | 머지 커밋에서 빠진 상태 문서 2개(`README.md`, `docs/implementation-status.md`) | 문서만 |
| 〃 | `c76edad132cec9e4986eccaea1e127fdff487de2` | 위 누락 정정 기록 | [43705112949](https://github.com/Lcjam/gongchae-bell/actions/runs/43705112949) test·secrets 성공 |
| 〃 | `cdd778e` | **4차**: 검수된 Sol `fc752ad7`을 **merge**(부모 `c76edad`·`fc752ad`). 통합 테스트 3건, 테스트 기반 `false` 고정, Sol 보고 이력 → [아래 4차 절](#4차-최초-적재를-시작일-하한-전체-조회로-start_scan) | 아래 커밋과 함께 push |
| 〃 | `2d9e263939f4ce9f27506e775a674d741cd17571` | **4차** 최초 적재 START_SCAN, V4, 공식 하한 fixture, 테스트, 문서 | [43710407513](https://github.com/Lcjam/gongchae-bell/actions/runs/43710407513) test(ARM64)·secrets 성공 |
| 〃 | (이 문서) | 4차 SHA·테스트·CI 기록 | 문서만 |

PR #11의 base는 main 통합 뒤 `feat/#3-ingestion-core`에서 `main`으로 바꿨다.

## #3 계약 v3 (먼저 처리)
[계약 v3](claude-ingestion-contract.md) 변경 이력과 §1·§3. 요약:
- `ParsedPage parsePage(String body)`: 파싱·행 검증만 한다. DB에 접근하지 않는다. `totalCount()`, `sns()`(수정 불가 정렬 집합), `beginsWithin(from, to)`.
- `Counts upsertPage(ParsedPage page, Instant observedAt)`: 호출자가 받아들인 페이지만 한 트랜잭션으로 반영한다.
- 파싱은 여전히 한 번이다. `PageResult`와 `upsertPage(String, Instant)`는 지웠다(호환 래퍼 없음).
- `body == null` → `NON_JSON`(agy PR #9 리뷰 3-1). `docs/design/routes.md` 외부 API 표를 다시 이었다(3-2).
- #3 테스트 32건(전 31 + `parsedPageWritesNothingSoTheCallerCanRejectItBeforeAnyChange`). 기존 적재 테스트 6개를 새 API로 옮겼다. 자세한 표는 [#3 결과](claude-ingestion-result.md)의 "계약 v3 수정".

## #4 수정 → 테스트
| codex 지시 | 처리 | 테스트 (실제 PostgreSQL 17 Testcontainers) |
|---|---|---|
| **1a. 완료 월 SN 집합은 완료 당시 값** (11-codex 1번) | 월 행이 완료될 때 그 월에서 검증한 `seen` 집합을 그 행의 `received_sns`에 저장한다(`CollectionRuns.completeMonth`). 완료 월을 건너뛸 때 같은 행의 `total`·`received`·`unique_count`·`received_sns`를 함께 읽는다(`completedMonth`). 페이지 행을 다시 합치던 `completedMonthSns`는 지웠다 | `completedMonthIsSkippedByItsOwnSnSnapshotAfterUpstreamShrankAndPagesRestarted`: 총 2001건 달의 1·2페이지 완료 → 3페이지 실패 → upstream 총수 1로 축소 → `restart-pages=true`로 완료(`received_sns=[100]`) → 다음 명령이 HTTP 없이 그 달을 건너뛰고 기간 고유 수 1. 예전 코드는 2페이지(1000건)를 다시 합쳐 `BACKFILL_PAGE_MISMATCH`가 된다 |
| **1b. 실패 페이지의 의미 검사를 반영 전으로** (11-codex 3번) | `parsePage` → 월 총수 변경·기대 페이지 크기·페이지 간 중복·요청 기간 검사 → 통과하면 `upsertPage`. DB를 다시 읽던 `withinRange`는 지웠다 | `rejectedPagesCommitNoChangeToStoredPostings`: 저장된 공고(sn 100)의 제목 변경과 새 공고를 섞은 페이지 4종(`BACKFILL_RANGE_MISMATCH`, `BACKFILL_COUNT_CHANGED`, `BACKFILL_DUPLICATE_SN`, `BACKFILL_PAGE_MISMATCH`) 모두 sn 100의 제목·해시 그대로, 새 공고 없음, `posting_change` 0, 월 행 `FAILED` |
| **1c. unlock 실패 시 물리 연결 폐기** (11-codex 2번, claude 리뷰 M3) | 잠금 연결을 Hikari에서 빌리지 않는다. `JdbcConnectionDetails`로 만든 `DriverManagerDataSource`에서 잠금마다 전용 물리 연결을 열고, `close()`는 unlock 결과(SQL 오류·false 포함)와 관계없이 그 연결을 닫는다. 연결이 닫히면 백엔드가 끝나 PostgreSQL이 잠금을 푼다. 풀에 돌려줄 연결이 아예 없다 | `leaseClosesItsPhysicalConnectionWhenUnlockFailsOnALiveSessionSoAnotherOwnerAcquires`: 세션은 살아 있고 unlock 문만 SQLState 57014로 실패하는 대역 + 실제 세션. close가 `DATABASE_FAILURE`를 알리고, 소유 pid가 `pg_stat_activity`에서 사라진 뒤 다른 소유자가 재선점한다. `CollectorWiringIntegrationTest.leaseConnectionIsNotBorrowedFromThePool`: 실제 Spring 배선에서 잠금 보유 중 Hikari 활성 연결 수가 그대로다 |
| **2. 상세 무제한 재시도 금지 (H1)** | ① 공고당 실패 3회 후 중단: `collect_run`의 같은 sn DETAIL 회차 중 `FAILED`를 센다. `BUDGET_EXHAUSTED`·`INTERRUPTED`는 세지 않는다. 마이그레이션 없음. ② sweep당 30건·10분. ③ 정기 몫 보존: `ApiBudget.reserve`가 REGULAR가 아니면 `count < stopLimit − 72`일 때만 예약한다. 같은 조건부 `INSERT … ON CONFLICT DO UPDATE … WHERE` 한 문장이라 원자적이다. ④ 상한에 걸린 공고는 `detail_status=FAILED`로 남고 새 지표 `collector_detail_gave_up`에 나온다 | `detailRetriesStopAfterThreeFailedRunsAndTheGapStaysVisible`(예산 소진 1회는 세지 않고 INVALID_DETAIL 3회 뒤 요청 중단, 6개 슬롯에서 호출 4회, 지표 1). `oneSweepStopsAtThirtyPostingsOrTenMinutes`(40건 중 30건, 4분씩 흐르는 시계로 2건에서 중단). `nonRegularKindsStopShortOfTheRegularDailyShare`(827에서 DETAIL 1회만, BACKFILL 거부, REGULAR는 900까지 72회) |
| **3. 큰 Retry-After** | 60초(`MAX_INLINE_WAIT`) 이하면 그만큼 기다린다. 넘거나 읽을 수 없으면 기다리지 않고 `HTTP_RATE_LIMIT`로 회차를 끝낸다. 60초로 잘라 일찍 재요청하지 않는다. 헤더가 없으면 일반 대기 2초/4초 | `rateLimitWaitsOnlyForShortReadableDelaysAndNeverRetriesEarlierThanAsked`: `60`·HTTP 날짜 +30초·지난 날짜(0초)·헤더 없음은 3회 요청과 해당 대기. `61`·`86400`·`99999999999999999999`·HTTP 날짜 +61초·`-5`·`+5`·`soon`·빈 값은 요청 1회, 대기 없음. 기존 `rateLimitUsesServerDelay`(7초)도 통과 |
| **4. sweep이 정기 스케줄을 오래 붙잡지 않게, 종료 대기 30초** | 2번의 30건·10분 상한. `spring.task.scheduling.shutdown.await-termination=true`, `await-termination-period=30s`. 풀 크기는 바꾸지 않았다 | `CollectorWiringIntegrationTest.schedulerWaitsThirtySecondsForARunningCollectionOnShutdown`(실제 `ThreadPoolTaskScheduler`의 설정값). **항상 INVALID_DETAIL인 대역으로 여러 슬롯**: `alwaysInvalidDetailsAcrossManySlotsLeaveTheRegularShare`: 실제 HTTP 대역 + 실제 `RecruitmentClient`·`ApiBudget`(stop 100으로 축소)에서 진행 공고 40건, 24개 슬롯 → 상세 요청 28회에서 멈추고 그 뒤 REGULAR 72회 예약 성공, 73번째만 거부 |
| **5. 운영 순서와 별도 게이트** | `docs/ops/collector.md`에 "운영 순서와 남은 게이트" 절: 최초 적재 완료 → `COLLECTOR_ENABLED=true`. 공식 API 원문 확인, 빈 달 응답, 운영 500 경보 수신은 별도 게이트로 남겼다 | 문서 |

## 초기값 제안 (ADR-0014에 반영)
| 항목 | 값 | 근거 |
|---|---|---|
| 정기 몫 | 하루 72회 | 매시 1회 × 24 × 최대 3회 시도. 예산 900 중 상세·적재는 828까지 |
| 공고당 상세 실패 | 3회 | 형식 불일치(200 응답)면 공고당 3회, HTTP 오류면 공고당 최대 9회 호출. 일시 장애가 3시간 이어지면 그동안 들어온 공고의 상세는 포기된다(지표로 보임) |
| sweep당 | 30건·10분 | "하루 신규 약 34건"(1년치에서 나눈 **추정**, 실측 아님)이면 시간당 30건으로 충분하다. 10분은 **새 요청의 시작 제한**이다. 진행 중인 한 건은 끝까지 가며 HTTP 상한만으로 최대 약 150초가 더 걸린다(10초 × 3 + `Retry-After` 60초 × 2). (2차 정정: 처음에 쓴 "최악 약 36초, 최대 약 11분"은 `Retry-After` 대기를 빠뜨린 값이었다) |
| Retry-After 인라인 대기 | 60초 | 정기 회차 하나가 한 시간 슬롯 안에서 끝나도록 |
| 스케줄러 종료 대기 | 30초 | ADR-0014의 graceful shutdown 30초와 맞춤. 컨테이너 종료 유예 40초 안 |

`docs/adr/0014-initial-settings.md`의 외부 HTTP·API 예산·종료 행을 고치고 "공고 상세 수집" 행을 추가했다. 설정 키는 늘리지 않았다(코드 상수).

## V3
바꾸지 않았다. 월 SN 스냅숏은 V3에 이미 있는 `collect_run.received_sns`(월 행에도 쓸 수 있는 열)를 쓰고, 상세 실패 횟수는 기존 DETAIL 회차 행에서 센다.

## 검증
- 로컬: Windows x64, Temurin 21.0.13, Gradle 9.9.1, Docker Desktop, PostgreSQL 17 Testcontainers, HTTP 대역만. 실제 공식 API 호출 0회.
- #4 head에서 `gradlew check`: JUnit XML 합계 **71 tests / 0 failures / 0 errors / 0 skipped** (#3 32 + #4 39. #4는 Sol 29 + 새 10).
- 새 회귀 테스트를 **예전 코드에 돌려 실패하는 것(red)은 확인하지 않았다.** 예전 코드는 #3 v3 API로는 컴파일되지 않는다. 실패 경로는 위 표의 "예전 코드는 …"처럼 코드로만 추론했다. 다만 `RuntimeDatabaseTest`는 예전에도 비풀 `DriverManagerDataSource`를 썼으므로 unlock 실패 테스트만으로는 차이가 드러나지 않는다. 풀 밖 연결이라는 점은 `CollectorWiringIntegrationTest`가 실제 Hikari 배선으로 확인한다.

## 한계와 남은 일
- **상세 실패 집계**: `collect_run.slot LIKE '%/' || sn`으로 센다. 인덱스를 못 쓰지만 대상은 미완료 상세 공고뿐이고 DETAIL 행은 하루 수십 건 규모(추정)다. DETAIL 행 보존 기간은 정하지 않았다(claude 리뷰 L3, M3 운영에서).
- **종료 30초**: 상세 sweep이 진행 중이면 30초 안에 끝나지 않을 수 있다. 그 회차는 `result IS NULL`로 남는다. 실제 컨테이너 종료(SIGTERM) 시나리오는 시험하지 않았다. 설정값만 확인했다.
- **세션 잠금**: (2차에서 해소) [ADR-0016](../../docs/adr/0016-supplemental-session-lock.md)으로 승격했다.
- **공식 API**: (2차에서 해소) 목록 봉투·상세 형식·빈 달 응답을 agy 공식 표본 1건씩으로 확인했다. 표본 하나라는 한계는 남는다.
- 슬롯을 실행 시각으로 계산해서 한 시간이 통째로 빠질 수 있다는 claude 리뷰 M2의 뒷부분은 이번 상한으로 가능성만 줄였다. 코드는 바꾸지 않았다.
- agy PR #9 리뷰 3-3(`loadCodes` 정렬의 DB collation 의존)은 선택 사항이라 고치지 않았다.

## 2차 수정 (codex 11 재검수 1~6번)
> 대상 head: 1차 `e709922`(CI [43682376057](https://github.com/Lcjam/gongchae-bell/actions/runs/43682376057)) 위. 기존 테스트는 재설계하지 않고 회귀 테스트만 더했다(합본 시험은 Sol 담당). 공식 API 추가 호출 0회. V1~V3, 원본 `.env`, 사용자 변경, 웹 브랜치는 건드리지 않았다.

| # | 지시 | 처리 | 테스트 |
|---|---|---|---|
| 1 | ERD를 V3에 맞춤 | `docs/design/erd.md`: 구현 현황에 V3, `collect_run`의 V3 열 7개(`period_from`·`period_to`·`range_from`·`range_to`·`page_no`·`unique_count`·`received_sns`)와 두 부분 인덱스·CHECK, `api_attempt` 표, `api_budget`의 정기 몫, 슬롯 형식과 명령·월 행의 집계 범위 | 문서. V3 SQL과 대조했다 |
| 2 | 거부 페이지가 월 집계를 오염시키지 않게 | `validate`를 부작용 없는 검사로 바꿨다(`seen.add` → `seen.contains`). 페이지는 검사·반영·체크포인트를 모두 마친 뒤에만 `expected`·`received`·`seen`에 한꺼번에 더한다. 체크포인트로 건너뛴 페이지도 검사 뒤 같은 자리에서 더한다 | `rejectedPagesCommitNoChangeToStoredPostings`에 월 행 `total|received|unique` 검사를 더했다: 범위 오류·페이지 크기 오류(1페이지 거부) → `null|0|0`, 총수 변경 → `1001|1000|1000`, 페이지 간 중복 → `1002|1000|1000`. 중복 사례는 새 SN 50을 중복 SN 100보다 먼저 검사하게 만들어, 예전 코드라면 50이 `seen`에 남는 경우를 겨냥했다 |
| 3 | 실패 명령의 누적 요약 보존 | 명령 행의 `CollectionFailure`·예상 밖 예외 경로가 `runs.fail`(합계 null) 대신 완료한 달의 `total`·`received`·`unique`로 마감한다. 월 행의 예상 밖 예외 경로도 받아들인 페이지의 합계를 남긴다. 집계 범위(명령 = 완료한 달, 월 = 받아들인 페이지, 실패 중인 달의 내역은 월·페이지 행)를 `collector.md`와 `erd.md`에 적었다 | `failedCommandKeepsCompletedMonthTotalsAndTheFailingMonthIsOnItsOwnRows`: 10월 완료 → 11월 1페이지 `HTTP_STATUS` → 명령 `FAILED|HTTP_STATUS|1|1|1`, 11월 월 행 `FAILED|HTTP_STATUS|null|0|0`, 페이지 행 `FAILED|HTTP_STATUS`. 이어서 새 슬롯에서 예상 밖 예외 → 명령 `FAILED|INGESTION_FAILED|1|1|1`(10월은 월 행에서 재사용) |
| 4 | 상세 실패 상태 한 곳에서, 중복 `failed(sn)` 제거 | `DetailCollector.collect`의 catch 하나가 실패를 기록한다: 예외가 `CollectionFailure`가 아니면 `INGESTION_FAILED`. 예산 소진·종료는 sweep을 멈추고 공고 상태도 실패 횟수도 건드리지 않는다(실패 집계 `FAILURES`와 같은 기준). 그 밖의 실패는 공고를 `FAILED`로 바꾼다(`DONE`은 SQL 조건으로 그대로). `save()`의 `failed(sn)` 두 곳은 지웠다. **바뀐 동작**: 전에는 `INTERRUPTED`도 공고를 `FAILED`로 바꿨다 | `unexpectedDetailErrorsMarkThePostingFailedAndStopAtTheCap`: 예상 밖 예외 대역 → 첫 회차에 `FAILED`, 5개 슬롯에서 요청 3회로 중단, DETAIL 행 오류 `INGESTION_FAILED` 3건. 기존 `detailIsIndependentAndMalformedResponseKeepsSavedArrays`(DONE 배열 보존)도 통과 |
| 5 | sweep 시간 설명 정정 | `collector.md` 표, ADR-0014 행, 이 문서 초기값 표, `DetailCollector` 주석: "10분은 새 요청의 시작 제한, 진행 중인 한 건은 끝까지(HTTP 상한만으로 최대 약 150초 추가)". 강제 취소는 넣지 않았다 | 문서·주석 |
| 6 | 사용자 키의 앱 입력 경계 | `CollectorProperties`가 설정을 읽을 때 한 번만 정규화한다: `%`가 있으면 모든 `%XX`가 유효한 ASCII 16진수인지 확인하고 정확히 한 번 percent-decode(UTF-8 엄격, 평문 `+` 보존). `%`가 없으면 그대로. 잘못된 표기는 `INVALID_SERVICE_KEY`(키 미포함). 그 값을 기존 `RecruitmentClient`가 한 번 인코딩한다. 원본 `.env`는 읽지도 고치지도 않았다 | `CollectorPropertiesTest` 3건: 두 표기·소문자 16진수가 같은 원키로, `a+b%2F` → `a+b/`, `a%252B` → `a%2B`(한 번만), UTF-8 바이트, 잘못된 6종(`%2`, `%`, `%G1`, `%%41`, `%FF`, `%2-`) → 메시지가 정확히 `INVALID_SERVICE_KEY`, `toString`에 키 없음. `RecruitmentClientTest.encodingOrDecodingFormOfTheKeyGoesOnTheWireEncodedExactlyOnce`: Decoding·Encoding·소문자 Encoding 세 입력이 모두 같은 `serviceKey=fixture%2B%2F%3D+key`로 나간다(HTTP 대역) |

### 함께 반영한 것
- **ADR-0016**: [보조 세션 잠금](../../docs/adr/0016-supplemental-session-lock.md)으로 초안을 승격했다(범위, 획득, 전용 물리 연결, 해제, 한계, 검증 테스트). ADR-0003의 결정은 그대로이고 결과 목록에 ADR-0016 링크 한 줄만 더했다. ADR 목록과 초안 파일 머리에 상호 링크를 달았다.
- **공식 fixture**: cherry-pick한 `fixtures/alio-official-*.json`으로 두 테스트를 더했다. `officialDetailSampleIsStoredWithoutWrappers`(sn 307032 상세 → `DONE`, steps 4·files 3, 래퍼 없는 필드 그대로), `officialEmptyPeriodResponseCompletesAMonthWithZeroPostings`(2010-01 빈 기간 → 월 `COMPLETE`, `0|0|0|[]`). 497건 목록 fixture는 합본 시험에 맡기고 쓰지 않았다.
- **agy 보고 정정**: cherry-pick한 `collab/2026-11-15-api-verification/agy-result.md` §5의 "커밋·푸시 없이 보존"을 "로컬 커밋 `b95fa67`(2026-11-15 14:44:33 KST), 원격 미push"로 고쳤다. `git ls-remote origin`에 `docs/#4-official-fixtures`가 없음을 확인했다. agy의 원래 브랜치와 작업 경로는 건드리지 않았다.
- **운영 문서**: 공식 표본으로 확인한 봉투·상세·빈 달을 적고, 남은 게이트를 "3년 적재 실측"과 "500 경보 실제 수신(M3)"으로 줄였다. 키 입력은 두 표기 모두 받는다고 고쳤다.

### 검증
- 로컬(같은 환경): `gradlew check` **79 tests / 0 failures / 0 errors / 0 skipped**. #3 32 + #4 47(1차 39 + 2차 8: 백필 2, 상세 2, 키 4).
- **red 확인**: 2차 코드 중 `BackfillCollector`·`DetailCollector`만 직전 커밋 판으로 되돌려 돌리면 새 테스트 3개가 실패한다: `rejectedPagesCommitNoChangeToStoredPostings`(범위 오류 월 행 `2|0|2`), `failedCommandKeepsCompletedMonthTotalsAndTheFailingMonthIsOnItsOwnRows`(명령 행 `null|null|null`), `unexpectedDetailErrorsMarkThePostingFailedAndStopAtTheCap`(공고가 `NONE`). 확인 뒤 새 코드로 되돌리고 전체를 다시 돌려 79건 통과했다. 키 정규화 테스트는 예전 코드에 정규화가 없으니 자명하게 실패하므로 따로 돌리지 않았다. 중복 사례의 월 행 오염은 범위 오류 사례에서 테스트가 먼저 멈춰 따로 관측하지 않았다.
- 원격 CI: `5e2950b` → [43684660748](https://github.com/Lcjam/gongchae-bell/actions/runs/43684660748) test·secrets 성공(ARM64).

### 2차 한계
- 상세 sweep이 DB 장애로 실패 기록(`failed`, `runs.fail`)까지 못 하면 예외가 `RegularCollector.collect` 밖으로 나간다. 그 정기 회차는 이미 `COMPLETE`로 커밋돼 있고 Spring 스케줄러가 오류를 로그로 남긴 뒤 다음 정시에 다시 돈다. 공고 상태가 `FAILED`로 안 바뀐 그 회차는 실패 횟수에도 안 들어갈 수 있다(DETAIL 행이 `result IS NULL`로 남음). 코드로 막지 않았다.
- 같은 sweep 안에서 429(`HTTP_RATE_LIMIT`)가 나도 남은 공고를 계속 시도한다(agy 재검수 5-2-3). 예산 상한과 공고당 3회로 비용은 묶여 있다. 바꾸지 않았다.
- 키 정규화는 `%`의 유무로 표기를 판단한다. 포털 키는 base64 문자(`A-Za-z0-9+/=`)라 Decoding 표기에 `%`가 없다는 전제다. 첫 실패의 원인이 이중 인코딩이었다는 것은 HTTP 상태 번호가 없어 여전히 추정이다.

## main 통합 (PR #9 · #12 · #10 머지 뒤)

지시: codex 조율. PR #9 `0bf3d40`, PR #12 `6fdf2df`, PR #10 `764819a`가 main에 머지된 뒤 #4를 main 위로 올린다. 새 앱 로직·V1~V3·env·개인정보는 바꾸지 않는다. Sol 격리 합본 `fc752ad7`은 참고만 하고, 통합 테스트와 테스트 컨텍스트 `false` 고정은 후속 PR로 남긴다.

### 방법
- `git fetch` 뒤 `56faf05`에서 `git merge --no-ff origin/main`(`764819a`) → 머지 커밋 `dc8d016`. rebase와 force push는 하지 않았다. Sol·agy의 기존 커밋 SHA는 그대로다.
- PR #11 base: `feat/#3-ingestion-core` → `main`. 변경 뒤 GitHub `mergeable=MERGEABLE`, main 대비 38파일.

### 충돌 범위
양쪽이 함께 바꾼 파일은 이 2개뿐이고 둘 다 텍스트 충돌이었다. 양쪽 내용을 모두 남겼다.

| 파일 | #4 쪽 | main(#5) 쪽 | 결과 |
|---|---|---|---|
| `src/main/resources/application.yml` | `spring.task.scheduling.shutdown` 30초 대기(ADR-0014) | `app.base-url: ${APP_BASE_URL:http://localhost:8080}` | 둘 다. `spring.task` 아래 #4, 그 뒤 최상위 `app` |
| `.env.example` | `COLLECTOR_ENABLED`·`COLLECTOR_SERVICE_KEY`·`COLLECTOR_WARNING_LIMIT`·`COLLECTOR_STOP_LIMIT` | `APP_BASE_URL` | 키 이름 다섯 개 모두, 값은 비움 |

- 두 파일은 Sol의 `fc752ad7`과 바이트 단위로 같다(`git diff fc752ad7 -- <두 파일>` 출력 없음).
- 머지 커밋 `dc8d016`에서 두 부모 어느 쪽과도 다른 파일은 위 2개뿐이다. 나머지 파일(Java·V1~V3·테스트·fixture·문서 전부)은 한쪽 부모와 같다. 양쪽 차이 파일마다 `git diff --quiet 56faf05|764819a dc8d016 -- <파일>`로 확인했다. 아래 상태 문서 2개는 그 뒤 `f75d8f1`에서 따로 고쳤다.
- 내 M1 검수 L4(a)에서 우려한 `agy-result.md` 충돌은 일어나지 않았다. main에는 이 파일이 없고 #4의 §5 정정본이 그대로 남았다. `fixtures/`와 `collab/2026-11-15-api-verification/`도 `56faf05`와 같다.
- `fc752ad7`과 비교한 소스 차이는 후속 PR 몫 3파일뿐이다: `OfficialFixtureIntegrationTest`(신규 3건)와 `IntegrationTestSupport`·`CatalogIntegrationTest`의 `collector.enabled/backfill=false` 고정. 이 3파일은 cherry-pick하지 않았다.

### 상태 문서 (머지 상태에 맞춤, `f75d8f1`)
**정정**: 이 두 파일의 수정을 머지 커밋에 넣으려 했으나, 첫 커밋 시도가 로컬 커밋 메시지 hook에 막히면서 같은 명령의 `git add`도 실행되지 않았다. 다시 커밋할 때 `git add`를 빠뜨려 `dc8d016`에는 main 쪽 원본이 그대로 들어갔다. 그 커밋 메시지의 "Status docs updated" 문장은 사실과 다르다. force push 없이 바로 다음 커밋 `f75d8f1`에 두 파일만 넣었다. 로컬 `check bootJar`는 이 수정이 작업 트리에 있는 상태에서 돌렸다. 문서만 다르므로 빌드 결과는 같다.
- `docs/implementation-status.md`: 기준일 2026-11-15. #2 "PR #8 머지 대기"를 "머지"로 바꿨다. #3 "PR 리뷰 대기"는 "PR #9 머지"로 바꾸고, #4가 같은 반영 경로를 부른다는 점을 더했다. FR-C1과 FR-C5~C6에는 #4 구현과 PR #11 리뷰 대기를 적고, 실제 3년 적재 기록이 없다는 점과 두 앱 실제 동시 운영이 미검증이라는 점을 남겼다. FR-C7~C8에는 #4 상세 수집과 공식 `detail` 표본 저장 테스트를 더했다. FR-W1~W4에는 PR #10 머지를 적었다. 공식 `detail` 표본으로 화면까지 잇는 테스트는 별도 PR이라고 적었다. fixture 문단은 공식 OpenAPI 응답 3종이 있다는 사실로 고쳤다.
- `README.md`: 첫 상태 문단에 수집 실행기가 PR #11에서 리뷰 중이고 기본값이 꺼짐이라고 적었다. 검증 절에는 테스트 셸에 `COLLECTOR_ENABLED=true`나 `COLLECTOR_BACKFILL=true`를 두지 말라는 한 줄을 넣었다. 일부 테스트 컨텍스트가 아직 이 값을 고정하지 않아서다(M1 검수 L2, 후속 PR에서 고정).

### 검증
- 로컬: Windows x64, Java 21.0.13, Gradle 9.9.1, Docker 29.9.2. 셸에 `COLLECTOR_ENABLED=false COLLECTOR_BACKFILL=false`를 주고 `gradlew check bootJar`를 1회 실행해 **BUILD SUCCESSFUL**을 얻었다. 결과는 **91 tests / 0 failures / 0 errors / 0 skipped**다(테스트 결과 XML 합계). 내역은 기존 79(#2·#3 32 + #4 47)에 #5 12를 더한 값이고 기대와 같다. M1 검수에서 쓴 94는 Sol의 신규 3건을 포함한 수라 여기에는 해당하지 않는다. `build/libs/gongchae-bell.jar`가 만들어졌다.
- DB: Testcontainers PostgreSQL 17만 썼다. 영속 dev DB 컨테이너(`gongchae-bell-dev-postgres-1`)는 실행 중인 것을 `docker ps`로 보기만 했고 연결·중지·변경하지 않았다. 실제 API 호출과 메일 발송은 0회다.
- Gitleaks 8.30.1(`gitleaks git --redact`): `dc8d016` 기준 전체 이력 44커밋·PR 범위 `origin/main..HEAD` 11커밋, `f75d8f1` 기준 46커밋·13커밋 모두 **no leaks found**.
- 원격 CI: `dc8d016` → [43704687576](https://github.com/Lcjam/gongchae-bell/actions/runs/43704687576) `test`(ubuntu-24.04-arm, Temurin 21.0.13 aarch64)·`secrets` 성공.
- 추가 코드 수정은 없다. 이 머지의 Java·SQL·테스트 내용은 검수된 두 head(`56faf05`, `764819a`)의 합 그대로다.

### 하지 않은 것
- 원격 머지: root 검수 전이라 하지 않았다.
- 후속 PR 몫: Sol 통합 테스트 3건과 테스트 `false` 고정.
- 원본 작업 경로·사용자 변경·agy 작업·Sol 격리 브랜치: 수정하지 않았다.

## 4차: 최초 적재를 시작일 하한 전체 조회로 (START_SCAN)

지시: codex 조율(실제 조회 계약 검증 뒤 #4 필수 수정 배정). 읽은 것은 root `collab/2026-11-15-backfill/`의 `agy-query-contract.md`, `claude-correction-plan.md`, `sol-correction-plan.md`, `codex-plan-review.md`와 CLAUDE.md다. 이 네 파일은 root 작업 경로에만 있다.

### 결함
2026-11-15 월별 실제 적재(37회, 22,733건)는 시작월과 마감월이 다른 공고를 하나도 받지 못했다. agy의 추가 검증 2회(`api_attempt` 42·43)에서 `pbancEndYmd`가 마감일 상한처럼 동작함을 확인했다. 같은 하한에서 `pbancEndYmd=2026-11-15`를 붙이면 totalCount가 36,277이었고, 빼면 36,772였다. 뺀 쪽의 1페이지에는 2026-11-15 이후 마감 381건과 그 뒤 시작 4건이 있었다. 공식 명세는 두 날짜 모두 "시작일 기준"이라고 적는다. 원인은 확인하지 않았다.

### 1) Sol 통합 테스트 merge (`cdd778e`)
- `git merge fc752ad7`(rebase 없음). 충돌 4파일:
  - `README.md`, `docs/implementation-status.md`, `agy-catalog-result.md`는 c76(최신 main) 문구를 유지했다. `agy-catalog-result.md`는 main 쪽이 이미 #5 최종본이다.
  - `CatalogIntegrationTest`는 fc752ad7 쪽을 받았다. 그 파일은 main 파일에 `collector.enabled/backfill=false` 두 줄만 더한 것이다.
- 확인: 머지 결과의 `src/test`는 fc752ad7과, `src/main`과 문서는 c76edad와 같다(`git diff --quiet`). 이 머지에 프로덕션 변경은 없다.

### 2) 수정 (`2d9e263`)
| 부분 | 변경 |
|---|---|
| 요청 | `RecruitmentClient.backfillList(from, page)`: `pbancBgngYmd=from&numOfRows=1000&pageNo=n`만 보낸다. `pbancEndYmd`는 없앴다 |
| 흐름 | 월 반복 대신 scan 하나: 명령 `command/{slot}` → scan `{slot}/scan` → 페이지 `{slot}/scan/{n}`. raw 수신이 원격 `totalCount`에 이를 때까지 읽는다. 첫 페이지에 반영할 행이 0건이어도 계속 읽는다 |
| 검사 순서 | `parsePage` 한 번으로 **모든 raw 행**(반영하지 않을 행 포함)의 식별자·날짜·페이지 안 중복을 검증한다. 이어서 총수 불변, 페이지 크기, raw SN 페이지 간 중복, 하한(`beginsWithin(from, MAX)`)을 본다. 그다음 같은 파싱 결과에서 `startingOnOrBefore(to)`만 `upsertPage`한다. 하나라도 어긋나면 그 페이지는 DB를 바꾸지 않는다 |
| V4 | `collect_run`에 열만 추가: `strategy`(`START_SCAN`, 기존 행 null), `scan_generation`, `applied_count`, `applied_sns`. 기존 `total_count`·`received`·`unique_count`·`received_sns`는 raw 의미로 쓴다. V1~V3는 그대로이고 M2는 V5부터다 |
| 세대 | 새 scan은 자기 id를 세대로 쓴다. 새 슬롯은 같은 기간의 **가장 최근 scan**을 본다. COMPLETE면 **그 행의 두 집합만** 재사용하고 HTTP는 0회다. 아니면 그 세대의 1페이지부터 **연속 완료 페이지만** 재사용하고, 그 뒤로는 끝까지 새로 받는다. `restart-pages`는 완료 여부와 상관없이 새 세대다. 페이지 조회 조건은 `strategy='START_SCAN' AND scan_generation=:g`라서 다른 세대나 월별 행을 섞지 않는다 |
| 집계 | scan 행과 명령 행: raw total/received/unique와 applied. 받아들인 페이지만 세고, 실패해도 그때까지 남긴다. 페이지 행: raw 두 수와 SN, applied SN, 반영 행의 inserted/changed/closed |
| 계약 | `ParsedPage.startingOnOrBefore(LocalDate)` 추가(#3 계약 v4). `BackfillRange.months()` 삭제 |
| 보존 | 기존 공고·`collect_run`·`api_budget`/`api_attempt`를 지우거나 고치는 코드는 없다. V4 이전 월별 COMPLETE는 새 전략의 skip 근거가 되지 않는다 |
| fixture | `fixtures/alio-official-list-lower-bound-sample.json`. agy의 ignored 보관본 `probe2-lower-only-p1.json`을 바꾸지 않고 복사했다. 4,442,918 bytes(agy 보고의 2,329,935는 문자 수), SHA-256 `3698d86882ed0f09584eb513bca1237cd8ab8d3933bd7555d50cd1eebb5d6b83`, `api_attempt.id=43`. 저장소 `.env`의 키 9가지 표기(원문·디코딩·인코딩·대소문자)·DB 비밀번호·사용자명이 0건임을 값 출력 없이 확인했다. `serviceKey`·`password` 문자열도 0건이다. provenance는 `fixtures/README.md`에 적었다 |
| 문서 | `routes.md` 최초 적재 행과 날짜 조건 주, `erd.md` V4, `docs/ops/collector.md` 운영 순서·최초 적재 절, #3 계약 v4, ADR-0016 슬롯 형식, `implementation-status.md` FR-C1 |

### 회귀 테스트 (실제 API 0회, Testcontainers + Mockito 대역)
`BackfillRuntimeTest`는 11건에서 14건이 됐다. 월 경계 이동 1건은 단일 scan에서 의미가 없어 지웠고, 새 사례 4건을 넣었다.

| codex 지정 사례 | 테스트 |
|---|---|
| 월 걸침·목표 뒤 마감·미래 시작·양끝 경계 | `monthCrossingLateDeadlineAndBoundaryStartsAreSelectedByStartDateOnly`: from 시작, 월 걸침, 마감이 to 뒤, to 시작은 반영하고 to+1 시작은 미반영. raw `6|6|6`, applied 5. 정기 수집 공고(`from_backfill=false`, 상세 DONE)는 출처와 상세가 그대로다 |
| raw 1001 중 목표 1 | `rawRowsOutsideTheTargetAreReadToTheEndButNotApplied`: 1페이지는 1000건이 모두 목표 밖(applied 0)이지만 체크포인트하고 2페이지까지 읽는다. `1001|1001|1001|1` |
| 밖의 잘못된 행·중복 | `rejectedPagesCommitNoChangeToStoredPostings` 6사례. 기존 4사례(하한, 총수 변경, 중복, 짧은 페이지)에 두 사례를 더했다: 목표 밖 행의 페이지 간 중복(`DUPLICATE_SN`, 함께 온 새 목표 행 미반영), 목표 밖 행의 날짜 형식 오류(`INVALID_ROW`). 모두 기존 공고 내용과 `posting_change`가 그대로이고, scan·명령 행 합계는 받아들인 페이지까지다 |
| legacy 같은 기간 | `legacyMonthlyRowsForTheSamePeriodAreNeverReused`: V3 방식 COMPLETE 명령·월·페이지 행(월 행 범위 = 새 기간)이 있어도 HTTP를 하고 둘 다 반영한다. legacy 행은 바뀌지 않는다. 완료 뒤 새 슬롯은 HTTP 0회, `restart-pages`는 새 세대로 다시 읽는다 |
| 같은 T 세대 혼합 | `explicitRestartStartsANewGenerationAndPagesWithTheSameTotalAreNeverMixed`: 세대 A는 1·2페이지 완료 후 3페이지 실패, 세대 B(restart)는 1페이지 완료 후 2페이지 실패, T는 둘 다 3000이다. 이어 받기는 B의 1페이지만 쓰고 2·3페이지를 새로 받는다. 결과 SN에 A의 SN이 0건이다 |
| 반영·체크포인트 crash | `resumesAfterProcessExitBeforeOrAfterPageCheckpoint…`: 체크포인트 전 종료 → 다시 받아 멱등 반영. 체크포인트 후 종료 → 재사용. 변경 기록 0 |
| 완료 재실행 0 HTTP | `resumesAFailedPageInTheSameGenerationAndKeepsExactUniqueIds`(`all-skipped`), `completedScanIsReusedOnlyThroughItsOwnSetsAfterUpstreamShrankAndARestart`(옛 세대 2000 SN을 합치지 않고 unique 1), legacy 사례 |
| 실제 모양 | `officialLowerBoundPageKeepsItsRealShapeThroughSelection`: 원본 그대로 파싱하면 T 36,772, 1000행, 하한 통과, 선택 996(미래 시작 4건 제외)이다. 이어서 `totalCount`만 1,000으로 바꾼 단일 페이지 scan → 반영 996건, 그중 월 걸침 374, 2026-11-15 이후 마감 377, 미래 시작 0 |

함께 고친 테스트:
- `RecruitmentClientTest.backfillSendsOnlyTheStartLowerBound…`: 실제 HTTP 대역에서 `pbancEndYmd`·`ongoingYn`가 없음을 확인한다.
- `RuntimeDatabaseTest.completedPageCheckpoint…`: 세대 조회와 두 집합을 확인하고, `strategy` null인 같은 범위 행은 찾지 않음을 확인한다.
- `BackfillCommandIntegrationTest`, `BackfillRangeTest`(월 분할 1건 삭제), `PostingIngestorIntegrationTest`(`startingOnOrBefore` 단언)도 고쳤다.
- Sol 통합 테스트 3번(`OfficialFixtureIntegrationTest`): 대역 조건을 "`pbancEndYmd` 없음"으로 바꾸고, 0건 행 3개를 명령·scan·페이지로 고쳤다.

**변이 확인** (일부러 고장 낸 뒤 대상 테스트만 실행, 끝나고 백업에서 복원하고 `cmp`로 같음을 확인):

| 고장 낸 것 | 결과 |
|---|---|
| 페이지 조회를 세대 대신 기간+페이지로 | 세대 혼합 테스트 실패(1/1) |
| scan 조회에서 `strategy` 조건 제거 | legacy 테스트 실패(1/1) |
| 선택 없이 raw 전체 반영 | 14건 중 4건 실패: 실제 모양 `1000` vs `996`, 월 걸침 사례에 sn 5 포함, 목표 밖 중복 사례에 sn 5000 반영, raw 1001 사례 1페이지 applied 1000 |
| `pbancEndYmd` 다시 전송 | `RecruitmentClientTest` 실패(1/10) |

### 검증
- 로컬: Windows x64, Java 21.0.13, Gradle 9.9.1, Testcontainers PostgreSQL 17. 셸에 `COLLECTOR_ENABLED=false COLLECTOR_BACKFILL=false`를 주고 `gradlew check bootJar`를 1회 실행해 **BUILD SUCCESSFUL**을 얻었다. 결과는 **96 tests / 0 failures / 0 errors / 0 skipped**다.
  - 건수 변화: c76의 91에 Sol 3건을 더하면 94다. 여기서 `BackfillRuntimeTest`는 +3(11→14), `BackfillRangeTest`는 −1이다.
  - 클래스별: BackfillRuntime 14, RuntimeDatabase 18, PostingIngestorIntegration 20, RecruitmentClient 10, ListResponseParser 8, Catalog 6+6, Foundation 4, CollectorProperties 3, OfficialFixtureIntegration 3, CollectorWiring 2, BackfillCommand 1, BackfillRange 1.
- Gitleaks 8.30.1(`2d9e263`): 전체 48커밋, PR 범위 `origin/main..HEAD` 24커밋 **no leaks found**.
- 원격 CI: `2d9e263` → [43710407513](https://github.com/Lcjam/gongchae-bell/actions/runs/43710407513) `test`(ubuntu-24.04-arm)·`secrets` 성공. `cdd778e`는 같은 push라 별도 실행이 없다
- 실제 API 호출 0회. 영속 dev DB와 컨테이너는 건드리지 않았다. agy 보관 파일은 읽고 복사만 했다.

### 하지 않은 것
- 새 방식의 실제 전체 조회(약 37페이지)는 하지 않았다(지시대로 보류).
- 원격 머지는 하지 않았다. PR #11은 초안이며 두 독립 리뷰 전이다.
- root 작업 경로의 `collab/2026-11-15-backfill/` 기록을 이 브랜치로 옮기지 않았다. 문서에서는 경로만 적었다.
- 원본 사용자 변경·agy 운영 파일·Sol 브랜치에는 쓰지 않았다.

### 한계와 바뀐 동작
- **같은 총수 안의 교체**: 원천에 스냅숏 토큰이 없다. 실행 중 `totalCount`를 그대로 둔 채 한 행이 빠지고 다른 행이 들어오면 감지하지 못할 수 있다. 총수 변경·중복·페이지 크기 이상은 감지해 멈춘다.
- **원격 총수 변동**: 하한만 둔 조회에는 새로 등록되는 공고가 들어간다. 그래서 실행 중이나 재개 사이에 `BACKFILL_COUNT_CHANGED`가 날 수 있다. 이때는 새 슬롯과 `restart-pages`로 다시 읽는다. 실패 한 번은 최대 페이지 수만큼 호출을 쓴다.
- **바뀐 동작**: `restart-pages`는 이제 완료된 scan이 있어도 새 세대로 다시 읽는다(전에는 완료 달을 건너뜀). 실패한 명령 행의 합계는 "완료한 달"에서 "받아들인 페이지"로 바뀌었다. scan이 하나뿐이기 때문이다.
- **반영하지 않는 행**: 목표 종료일 뒤에 시작하는 행과, `from` 전에 시작해 아직 진행 중인 공고는 최초 적재 대상이 아니다. 이후 정기 수집이 `from_backfill=false`로 넣고 상세 대상이 된다.
- **fixture 사용 범위**: 빈 응답 fixture는 월별 요청(`pbancEndYmd` 포함)으로 받은 본문이다. 여기서는 "원격 총수 0인 scan"의 본문 모양으로만 쓴다. 하한 fixture 테스트는 `totalCount`만 바꿨고 행은 원본 그대로다.
- 실DB에 V4가 적용되는 것은 다음 앱 실행 때다. 열 추가뿐이며, 기존 22,733건과 76행은 그대로 남는다.
