# #3·#4 수정 결과 (codex 11 결정, 11 재검수) · claude

> 2026-11-15 · 수정: claude (Sol 종료 후 #11 수정 소유권 인계) · 검수: codex · 다음 교차 리뷰: agy
> 지시: 원본 작업 경로의 `collab/2026-11-15-review/11-review-decisions.md`, `11-codex-collector-review.md`, `collab/reviews/pr-9-agy.md`. 2차: `collab/2026-11-15-review/11-second-review.md` 1~6번, `collab/reviews/pr-11-agy.md` §5, `official-fixtures-sol-review.md` → [아래 2차 절](#2차-수정-codex-11-재검수-16번)
> **claude가 #4를 수정했으므로 claude의 [PR #11 리뷰](../reviews/pr-11-claude.md)(원본 작업 경로)는 이 head의 독립 리뷰로 세지 않는다.**
> 머지하지 않았다. 두 PR 모두 초안이다. 원본 작업 경로, 사용자 변경, 웹(#5), 배포, V1·V2·V3는 건드리지 않았다.

## 커밋과 CI
| 브랜치 | 커밋 | 내용 | 원격 CI |
|---|---|---|---|
| `feat/#3-ingestion-core` (PR #9) | `aab6d5b2a9b64fe4b767f8deb415c77417e4dd86` | #3 계약 v3: 파싱·반영 분리, null 본문 `NON_JSON`, routes 표 | [43681733201](https://github.com/Lcjam/gongchae-bell/actions/runs/43681733201) test·secrets 성공 |
| `feat/#4-collector-runtime` (PR #11) | `bd6b706` | #3 v3를 **merge**로 반영. Sol의 커밋 `8db36bc`·`b636168`·`309216a`·`7b3eb31`은 SHA 그대로 남는다(rebase·force push 없음) | — |
| 〃 | `e7099226dd93693961ae29250131c8d2025b242b` | 아래 수정 5건, 테스트, 문서 | [43682376057](https://github.com/Lcjam/gongchae-bell/actions/runs/43682376057) test·secrets 성공 |
| 〃 | `1a7e470`, `5f9db02` | #3 결과 문서 커밋을 merge하고 SHA·CI 기록, SHA 오타 정정 | [43682540599](https://github.com/Lcjam/gongchae-bell/actions/runs/43682540599) test·secrets 성공 |
| 〃 | `e3b3867`, `304389c` | **2차**: agy 공식 fixture 커밋 `5b78c55`·`b95fa67`(`docs/#4-official-fixtures`, 로컬 커밋, 원격 미push)을 cherry-pick. 내용 그대로, SHA만 다르다 | — |
| 〃 | `5e2950bd93dc6a8f6b5b1fb1b5aff0dbf6aae9b3` | **2차** 수정 6건, 테스트, 문서, ADR-0016 | [43684660748](https://github.com/Lcjam/gongchae-bell/actions/runs/43684660748) test·secrets 성공 |
| 〃 | (이 문서) | 2차 SHA·CI 기록 | 문서만 |

PR #11의 base는 지시대로 `feat/#3-ingestion-core`로 둔다.

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
