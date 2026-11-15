# #3 구현 결과 · claude

> 2026-11-15 · 구현: claude · 검수: codex · 브랜치 `feat/#3-ingestion-core` (main `9402015` 기반) · 초안 PR [#9](https://github.com/Lcjam/gongchae-bell/pull/9)
> 계약: [claude-ingestion-contract.md](claude-ingestion-contract.md) (**v3**) · 검수 03 대응: [claude-ingestion-review-response.md](claude-ingestion-review-response.md) · 준비안: [claude-ingestion-ready.md](../2026-11-14-resume/claude-ingestion-ready.md)

## 커밋
| SHA | 내용 |
|---|---|
| `497db48` | 계약 v1 고정과 준비안 보존. 구현보다 먼저 푸시 |
| `4b5c442` | 1차 구현, 테스트, fixture, 설계 문서 |
| `4cca391` | 1차 결과 기록 |
| `67ed7dc` | **계약 v2와 검수 대응 파일**. 검수 03을 받고 구현보다 먼저 푸시(Sol #4 전달용) |
| `ef7dd4cb074a48406c7883e093f5ec9abf767629` | 검수 03 수정 5건의 구현과 테스트, erd.md |
| `925aa18` | 검수 03 결과와 CI 기록 |
| (이 커밋) | **계약 v3**: 최초 적재 파싱·반영 분리, null 본문 `NON_JSON`, routes 표 정리 |

머지하지 않았다. PR은 초안이다.

## 바뀐 파일 (main 대비)
| 파일 | 내용 |
|---|---|
| `src/main/java/kr/gongchae/bell/ingestion/` | `PostingIngestor`(공개 API 2개), `ListResponseParser`(봉투·행 검증), `Failure`, `IngestionException`, `Counts`, `RunResult`, `PageResult`(v2) |
| `src/test/java/kr/gongchae/bell/IntegrationTestSupport.java` | JVM당 PostgreSQL 컨테이너 1개를 모든 Spring 테스트 클래스가 공유 |
| `FoundationIntegrationTest.java` | 기반 클래스 상속. Flyway 검사를 "V1·V2 성공 기록 있음 + 실패 기록 0건"으로 바꿈(V3 이후가 들어와도 깨지지 않음) |
| `src/test/java/kr/gongchae/bell/ingestion/` | `ListFixture`(실제 응답 변형 헬퍼), `ListResponseParserTest`(8), `PostingIngestorIntegrationTest`(19) |
| `fixtures/alio-public-list-quality-sample.json` + README | 깨짐 의심 실제 행 2건 원문 발췌와 출처, 497건 실측 요약 |
| `docs/design/erd.md` | 응답 매핑 표, `pref` 형식(v2), 품질 플래그 정의, 실패 코드, `CLOSED → OPEN` 전이 |
| `docs/design/routes.md`, `docs/implementation-status.md` | 매핑·계약 링크, FR-C2~C4·C7~C8 행 상태 |

웹 코드(`catalog`), 템플릿, HTTP·배포 설정, 마이그레이션(V1·V2 포함), 빌드 설정은 건드리지 않았다. 새 의존성 없음.

## 검증
| 환경 | 결과 |
|---|---|
| 로컬 Windows x64, Temurin 21.0.13, Docker 29.9.2, PostgreSQL 17 Testcontainers, head `ef7dd4c` | `gradlew check bootJar` 성공. **31개 테스트**(Foundation 4, 파서 8, 반영 19) 통과. `test --rerun` 두 번 더 같은 결과(동시성·예외 경로 포함) |
| GitHub Actions [실행 43654236239](https://github.com/Lcjam/gongchae-bell/actions/runs/43654236239), head `ef7dd4c` | `test`(ubuntu-24.04-arm) success, 아티팩트 기준 **31 tests, 0 failures, 0 skipped**. `secrets`(gitleaks) success |
| (이전) [실행 43653438479](https://github.com/Lcjam/gongchae-bell/actions/runs/43653438479), head `4b5c442` | 27 tests 통과(1차 구현) |

### 검수 03 수정 → 테스트
| # | 지적 | 수정 | 테스트 |
|---|---|---|---|
| 1 | 페이지 SN 집합 | `upsertPage`가 `PageResult(Counts counts, SortedSet<Long> sns)`를 반환한다. `sns`는 그 페이지에서 검증·반영한 모든 번호(이미 저장된 공고 포함), 수정 불가. 파싱 한 번, 반영 한 번 | `pageSnSetCountsStoredPostingsAndStaysTheSameWhenThePageRepeats`: 저장된 공고 2건과 새 공고 1건이 섞인 페이지 → `sns = {307021, 307032, 999003}`, `received = sns.size()`, `inserted = 1`. 같은 페이지를 다시 넣으면 `inserted = 0`이어도 `sns` 동일. `sns.add`는 `UnsupportedOperationException`. 동시 삽입 테스트와 적재 테스트도 `sns`를 확인 |
| 2 | 우대 제어문자 형식 | `pref` = `[우대조건]\n<prefCondCn>` + `\n\n` + `[우대내용]\n<prefCn>`(없는 쪽 단락은 뺌). U+001F 거부 규칙 삭제. 화면은 나누지 않고 줄바꿈만 살려 출력 | `bothPreferenceOriginalsAreKeptAndEitherChangeIsObserved`: 요약만 변경 → 해시 변경, `pref` 변경 1건 / 상세 변경 → 1건 / 요약이 사라짐 → 1건. `controlCharacterInPreferenceDoesNotBlockTheCollection`: U+001F가 든 행이 있어도 정기 수집 COMPLETE, 10건 반영, 원문 그대로 저장. 파서 `preferenceKeepsOnlyThePresentOriginalsUnderTheirLabels`, `controlCharactersInTextAreKeptNotRejected` |
| 3 | `APPLY_ERROR` 미검증 | 코드 변경 없음(경로는 이미 있었음). 테스트 추가 | `writeErrorMidwayRollsBackEveryTableAndRecordsApplyError`: 임시 `CHECK (sn <> 307027) NOT VALID` 제약을 걸고, 그보다 앞서 쓰이는 307021(기관명·마감일 변경 → 기관 갱신, 변경 기록), 307023(코드 교체)과 뒤의 307032 변경을 함께 보낸다. 307027 UPDATE에서 실패하면 `DataIntegrityViolationException`이 다시 던져지고, 공고·코드·변경·기관·코드명 **전체 덤프가 반영 전과 같고**, 마지막 COMPLETE 시각이 그대로이고, 회차는 `FAILED`/`APPLY_ERROR`(`total_count`·`received` 10, `inserted` 0)로 마감된다. 제약은 `finally`에서 지운다 |
| 4 | Flyway 건수 고정 | V1·V2 성공 기록과 실패 기록 0건만 확인 | `FoundationIntegrationTest.migrationAndEmptyPages…` |
| 5 | 음수 `totalCount` | 파서에서 `INVALID_ENVELOPE` | 파서 `rejectsUnreadableEnvelopes`(`-1`), 통합 `backfillPageWithOneInvalidRowOrNegativeTotalAppliesNothing`(예외, 공고·기관 0건) |

### 이슈 #3 완료 기준 → 테스트 (1차와 같음)
| 완료 기준 | 테스트 |
|---|---|
| 동일 입력 재적재 0 변경 | `sameInputTwiceRecordsNoChange` |
| 마감일 변경 1건, `decimalDay` 변경 0건 | `decimalDayAndCodeOrderAreNotChangesButADeadlineChangeIsExactlyOne` (코드 순서 변경 0건 포함, `changed_fields` 내용과 관측 시각까지 비교) |
| 실패·빈·잘린·중복 ID 응답은 공고와 마지막 완전 수집을 바꾸지 않음 | `unusableResponsesChangeNoPostingDataAndKeepTheLastCompleteTime`: 8가지(비 JSON, 절반 잘린 본문, `resultCode` 22, 잘못된 봉투, 건수 불일치, 빈 응답, 중복 sn, 잘못된 행)마다 전체 덤프와 마지막 완전 수집 시각이 그대로이고 `collect_run.error`가 상수 이름과 같다. 반영 도중 오류는 위 3번 테스트 |
| KST 마감 | `deadlineUsesTheKoreanDate`: KST 11-20 00:30(UTC 11-19)에 마감일 11-19 공고가 CLOSED |
| 누락 CHECK | `missingPostingIsCheckedAndOnlyTheDeadlineOrOngoingFlagClosesIt`: 누락 → CHECK → 재등장 OPEN, `ongoingYn=N` → CLOSED, 마감 후 누락 → CLOSED, D-0은 OPEN, 마감 연장 → OPEN |
| 코드 정렬 | 파서 `readsRealRowsWithSortedCodesAndPairedNames`, 반영 `completeSnapshot…` |
| 원문 보존 | `sourceUrlIsStoredVerbatim…`(포트 `:442`와 인코딩된 쿼리 그대로), `suspectedBrokenText…`, `controlCharacterInPreference…` |
| 품질 배지 | `suspectedBrokenTextIsFlaggedWithoutRewritingIt`, 기본 표본은 0건 |

### 1차 검수 지시(구현 착수 시) → 처리
| 지시 | 처리 |
|---|---|
| 정기 수집 `INVALID_ROW`는 전체 미반영 | 실패 매트릭스의 "invalid row"(다른 행의 정상 변경도 함께 넣어 부분 반영 없음 확인) |
| 최초 적재도 페이지 전체 실패, COMPLETE 금지 | `upsertPage`는 예외를 던지고 아무것도 반영하지 않는다. 계약 §3에 호출자 의무로 적었다 |
| 두 우대 원문 보존과 해시 테스트 | v2에서 라벨 단락 형식으로 바꿨다(위 2번) |
| 공유 URL은 같은 기관 내 중복만, 원문 정규화 금지 | 키 = `pblntInstCd` + URL 원문 문자열. `sourceUrl…`, `sameUrlAcrossInstitutionsIsNotGeneric…` |
| `broken_text`는 의심 표시 | 계약, erd.md, 코드 주석에 "의심이지 확정 아님, 복구 안 함" |
| `resultCode` 원래 값을 `error`에 붙이지 않음 | `RESULT_CODE_NOT_OK` 상수만 저장. 실패 매트릭스가 정확히 비교 |
| 새 마이그레이션은 요청 후 | 만들지 않았다 |
| 계약을 먼저 파일로 고정 | v1 `497db48`, v2 `67ed7dc` 모두 구현보다 먼저 푸시 |
| M2 outbox·발송 금지 | 없음 |
| 공용 테스트 컨테이너, 새 데이터 충돌 통합 테스트 | `IntegrationTestSupport`. `postingInsertedConcurrentlyByAnotherTransactionIsUpdatedAsExisting`(다른 트랜잭션의 같은 새 `sn` 삽입에서 대기하는 것을 `pg_stat_activity`로 확인한 뒤 커밋시킴 → `inserted 9, changed 1`), `regularRunAndBackfillPageOverlapWithoutDeadlock` |

준비안의 D-C(마감 연장 시 OPEN 복귀), D-D(마감 당일 누락은 CHECK), D-G(비교 대상 외 값은 기록 없이 덮어쓰기), D-H(`item` 래퍼 허용), D-I(텍스트 정규화 없음)는 권장대로 구현하고 erd.md와 계약에 적었다.

## 계약 v3 수정 (codex 11 결정 1번, agy PR #9 리뷰 3-1·3-2)
| 지시 | 처리 | 테스트 |
|---|---|---|
| 최초 적재의 의미 검사를 반영 전에. 단일 파싱 유지, 호환 래퍼 없음 | `PostingIngestor.parsePage(body) → ParsedPage`(DB 접근 없음, `totalCount()`·`sns()`·`beginsWithin(from,to)`)와 `upsertPage(ParsedPage, observedAt) → Counts`로 나눴다. `PageResult`와 `upsertPage(String, Instant)`는 지웠다 | `parsedPageWritesNothingSoTheCallerCanRejectItBeforeAnyChange`: 저장된 공고의 제목 변경과 기간 밖 새 공고가 섞인 페이지를 파싱만 하면 5개 공고 테이블 덤프가 그대로이고, `sns`·`totalCount`·기간 경계(시작일 당일 포함, 하루 전 제외)를 반영 전에 알 수 있다. 기존 적재 테스트 6개를 새 API로 옮겼다 |
| null 본문 → `NON_JSON` | `ListResponseParser.parse` 맨 앞에서 거부 | `ListResponseParserTest.rejectsUnreadableEnvelopes`(null), `unusableResponsesChangeNoPostingDataAndKeepTheLastCompleteTime`의 "no body": 정기 회차가 `FAILED`/`NON_JSON`으로 마감되고 공고 테이블과 마지막 완전 수집 시각이 그대로 |
| `routes.md` 외부 API 표 끊김 | 표 중간 문단을 표 아래로 옮겼다 | 문서 |

agy 리뷰 3-3(`loadCodes` 정렬의 DB collation 의존, 비교 외 필드 단독 테스트)은 선택 사항이라 이번에 고치지 않았다. 지금 코드 값(`R600001` 같은 대문자·숫자)에서는 두 정렬이 같다.

로컬: Windows x64, Temurin 21.0.13, Docker Desktop, PostgreSQL 17 Testcontainers에서 `gradlew check` **32 tests / 0 failures / 0 skipped**(전 31 + 새 1). 커밋과 원격 CI는 아래 "커밋" 표에 추가한다.

## 한계와 미확인
- **공식 API 봉투**: 실제 `apis.data.go.kr` 응답을 아직 본 적이 없다. 파서는 swagger 문서의 `{"item": …}` 래퍼와 평평한 원소를 모두 받는다. #4가 실응답을 받으면 안 쓰는 쪽을 지워야 한다. 빈 달이 `200 + []`인지 `resultCode 3`인지도 #4가 확인할 일이다(지금은 3이면 `RESULT_CODE_NOT_OK`).
- **게이트웨이 오류 본문**: 실물을 확보하지 못해 테스트에서 합성 XML 문자열을 쓴다(테스트 주석과 fixtures README에 표시).
- **`APPLY_ERROR` 테스트의 범위**: 임시 CHECK 제약으로 공고 UPDATE 하나를 실패시킨 경우만 확인했다. 연결 끊김이나 교착 같은 다른 DB 오류는 같은 `catch` 경로를 타지만 따로 재현하지 않았다. 실패 기록용 두 번째 트랜잭션까지 실패하면(DB 자체가 죽은 경우) 회차는 `result IS NULL`로 남고 원래 예외에 그 실패가 `suppressed`로 붙는다.
- **`pref` 형식**: 표시용이다. 원문 안에 `\n\n[우대내용]\n` 같은 글이 그대로 들어 있으면 두 원문으로 되돌릴 수 있다고 보장하지 않는다. 변경 관측은 라벨 덕분에 생김·사라짐·내용 변경을 모두 잡는다.
- **`broken_text` 오탐**: 정상 문장에서 `?` 바로 뒤에 공백 없이 한글이 오면 걸린다. 497건 스냅숏에서 규칙에 걸린 10건은 모두 `「」` 손실 형태였지만, 그 이상은 확인하지 않았다.
- **`src_url_generic` 비율**: 같은 스냅숏에서 249/497(50%)이 generic이다(Python으로 계산한 값이라 Java `URI`와 경계 사례가 다를 수 있다). 원문 링크는 그대로 두고 잡알리오 링크를 함께 주는 표시라서 오탐 비용은 작다고 판단했다.
- **텍스트 정규화 없음**: 원천이 공백만 바꿔도 `posting_change`가 1건 생긴다. 알림과는 무관하다.
- **D-0 누락 시각**: 마감 당일 저녁에 진행 공고 목록에서 빠지면 CHECK가 된다. 평일 운영에서 확인해야 한다.
- **성능**: 행마다 SQL 문을 따로 실행한다(정기 약 500행). 1,000행짜리 적재 페이지의 반영 시간은 재지 않았다.
- **로그**: 반영하지 않은 응답은 WARN 로그에 실패 코드, 필드 이름, `sn`, 정수일 때의 `resultCode`를 남긴다. 본문과 URL은 남기지 않는다. `collect_run.error`에는 상수만 들어간다.

## 다음 담당에게
- **#4**: 계약 v3 §2~§3. `parsePage` → 반영 전 검사 → `upsertPage` 순서로 부르고, `upsertPage`는 트랜잭션 밖에서 호출한다. `parsePage`가 예외를 던지거나 검사에서 거부하면 그 달을 INCOMPLETE/FAILED로 남긴다. 월 고유 수와 페이지 간 중복은 `ParsedPage.sns()`로 계산하고 `counts.inserted`는 쓰지 않는다. 본문을 다시 파싱하지 않는다. 공식 봉투를 확인하면 `ListResponseParser.parse`의 `item` 분기와 테스트 하나를 정리한다. V3를 추가해도 `FoundationIntegrationTest`는 그대로 통과해야 한다.
- **#5 (agy)**: `pref`는 그대로 출력하되 줄바꿈을 살린다(`th:text` + `white-space: pre-line` 등). 나눌 필요 없다. `src_url_generic`이 false여도 `src_url`이 무효면 잡알리오 링크를 보여 줘야 한다(스킴 없는 값 19/497건). `broken_text` 배지 문구는 "의심"으로 쓴다.
- **M2**: "새 공고"는 첫 COMPLETE 정기 회차 이후의 `first_seen_at`이고 `from_backfill = false`인 행이다. 시작일이 3년보다 오래된 진행 공고도 있으므로(스냅숏에서 1건, 2020년 시작) 첫 회차를 기준선으로 봐야 한다.
