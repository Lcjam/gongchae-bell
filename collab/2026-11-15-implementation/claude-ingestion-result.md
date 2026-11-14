# #3 구현 결과 · claude

> 2026-11-15 · 구현: claude · 검수: codex · 브랜치 `feat/#3-ingestion-core` (main `9402015` 기반) · 초안 PR [#9](https://github.com/Lcjam/gongchae-bell/pull/9)
> 계약: [claude-ingestion-contract.md](claude-ingestion-contract.md) · 준비안: [claude-ingestion-ready.md](../2026-11-14-resume/claude-ingestion-ready.md)

## 커밋
| SHA | 내용 |
|---|---|
| `497db48` | 계약 고정과 준비안 보존. **구현보다 먼저 푸시했다**(Sol #4가 읽을 수 있게) |
| `4b5c44261536a299cf004ff8cad45e8a3880f3e5` | 구현, 테스트, fixture, 설계 문서. 계약에 행 검증 한 줄("텍스트 필드가 객체나 배열이면 INVALID_ROW")을 보충했다. 동작 변경은 아니다 |
| (이 문서) | 결과 기록 |

머지하지 않았다. PR은 초안이다.

## 바뀐 파일
| 파일 | 내용 |
|---|---|
| `src/main/java/kr/gongchae/bell/ingestion/` | `PostingIngestor`(공개 API 2개), `ListResponseParser`(봉투·행 검증), `Failure`, `IngestionException`, `Counts`, `RunResult` |
| `src/test/java/kr/gongchae/bell/IntegrationTestSupport.java` | JVM당 PostgreSQL 컨테이너 1개를 모든 Spring 테스트 클래스가 공유 |
| `FoundationIntegrationTest.java` | 위 기반 클래스를 상속하도록만 바꿨다(검증 내용 그대로) |
| `src/test/java/kr/gongchae/bell/ingestion/` | `ListFixture`(실제 응답 변형 헬퍼), `ListResponseParserTest`(7), `PostingIngestorIntegrationTest`(16) |
| `fixtures/alio-public-list-quality-sample.json` + README | 깨짐 의심 실제 행 2건 원문 발췌와 출처, 497건 실측 요약 |
| `docs/design/erd.md` | 응답 매핑 표, `pref` 형식, 품질 플래그 정의, 실패 코드, `CLOSED → OPEN` 전이 |
| `docs/design/routes.md`, `docs/implementation-status.md` | 매핑·계약 링크, FR-C2~C4·C7~C8 행 상태 |

앱 웹 코드(`catalog`), 템플릿, 마이그레이션, 빌드 설정은 건드리지 않았다. 새 의존성 없음.

## 검증
| 환경 | 결과 |
|---|---|
| 로컬 Windows x64, Temurin 21.0.13, Docker 29.9.2, PostgreSQL 17 Testcontainers | `gradlew check bootJar` 성공. 27개 테스트(Foundation 4, 파서 7, 반영 16) 통과. `test --rerun`을 두 번 더 돌려 같은 결과(동시성 테스트 포함) |
| GitHub Actions [실행 43653438479](https://github.com/Lcjam/gongchae-bell/actions/runs/43653438479), head `4b5c442` | `test`(ubuntu-24.04-arm) success, 아티팩트 기준 **27 tests, 0 failures, 0 skipped**. `secrets`(gitleaks) success |

### 이슈 #3 완료 기준 → 테스트
| 완료 기준 | 테스트 |
|---|---|
| 동일 입력 재적재 0 변경 | `sameInputTwiceRecordsNoChange` |
| 마감일 변경 1건, `decimalDay` 변경 0건 | `decimalDayAndCodeOrderAreNotChangesButADeadlineChangeIsExactlyOne` (코드 순서 변경 0건 포함, `changed_fields` 내용과 관측 시각까지 비교) |
| 실패·빈·잘린·중복 ID 응답은 공고와 마지막 완전 수집을 바꾸지 않음 | `unusableResponsesChangeNoPostingDataAndKeepTheLastCompleteTime`: 8가지(비 JSON, 절반 잘린 본문, `resultCode` 22, 잘못된 봉투, 건수 불일치, 빈 응답, 중복 sn, 잘못된 행)마다 공고·코드·변경·기관·코드명 **전체 덤프가 반영 전과 같고**, 마지막 완전 수집 시각이 그대로이고, `collect_run.error`가 상수 이름과 정확히 같다. 중복·잘못된 행 사례에는 다른 행의 정상 변경을 함께 넣어 부분 반영이 없음을 확인했다 |
| KST 마감 | `deadlineUsesTheKoreanDate`: KST 11-20 00:30(UTC 11-19)에 마감일 11-19 공고가 CLOSED. UTC 날짜를 쓰면 실패한다 |
| 누락 CHECK | `missingPostingIsCheckedAndOnlyTheDeadlineOrOngoingFlagClosesIt`: 누락 → CHECK → 재등장 OPEN, `ongoingYn=N` → CLOSED, 마감 후 누락 → CLOSED, D-0은 OPEN, 마감 연장 → OPEN |
| 코드 정렬 | 파서 `readsRealRowsWithSortedCodesAndPairedNames`, 반영 `completeSnapshot…`(DB 정렬 확인) |
| 원문 보존 | `sourceUrlIsStoredVerbatim…`(포트 `:442`와 인코딩된 쿼리 그대로), `suspectedBrokenText…`(깨짐 의심 원문을 바꾸지 않음) |
| 품질 배지 | `suspectedBrokenTextIsFlaggedWithoutRewritingIt`, 기본 표본은 0건(`completeSnapshot…`) |

### codex 검수 지시 → 처리
| 지시 | 처리 | 근거 |
|---|---|---|
| 정기 수집 `INVALID_ROW`는 전체 미반영 | 그대로 구현 | 실패 매트릭스의 "invalid row" |
| 최초 적재도 페이지 전체 실패, 건너뛰고 COMPLETE 금지 | `upsertPage`는 `IngestionException`을 던지고 아무것도 반영하지 않는다. 계약 §3에 호출자 의무로 적었다 | `backfillPageWithOneInvalidRowAppliesNothing` |
| `prefCondCn` 변경도 잡도록 두 원문을 같은 `pref`에 안정된 구분자로 보존 + 해시 테스트 | `<prefCondCn>` U+001F `<prefCn>`. 구분자가 원문에 있으면 `INVALID_ROW` | `bothPreferenceOriginalsAreKeptAndEitherChangeIsObserved`(요약만 바꿔도 해시가 바뀌고 `pref` 변경 1건, 상세 변경도 1건) |
| 공유 URL은 같은 기관 내 중복만, 원문 정규화 금지 | 키 = `pblntInstCd` + URL 원문 문자열. 저장값은 손대지 않음 | `sourceUrl…`, `sameUrlAcrossInstitutionsIsNotGeneric…` |
| `broken_text`는 의심 표시 | 계약, erd.md, 코드 주석에 "의심이지 확정 아님, 복구 안 함"을 적었다 | |
| `resultCode` 원래 값을 `error`에 붙이지 않음 | `RESULT_CODE_NOT_OK` 상수만 저장 | 실패 매트릭스가 `error = failure.name()`을 정확히 비교 |
| 새 마이그레이션은 요청 후 | 만들지 않았다. V1·V2만 쓴다 | Foundation 테스트의 Flyway 성공 2건 |
| 계약을 먼저 파일로 고정 | `497db48`로 구현 전에 푸시 | |
| M2 outbox·발송 금지 | 없음. 새 공고는 `first_seen_at`, `from_backfill`만 정확히 채운다 | |
| 공용 테스트 컨테이너, 새 데이터 충돌 통합 테스트 | `IntegrationTestSupport`. `postingInsertedConcurrentlyByAnotherTransactionIsUpdatedAsExisting`: 다른 트랜잭션이 같은 새 `sn`을 넣고 커밋하기 전에 `upsertPage`가 그 삽입에서 **대기하는 것을 `pg_stat_activity`로 확인한 뒤** 커밋시킨다. 결과는 `inserted 9, changed 1`로, 기존 행 경로로 처리되고 예외가 없다. `regularRunAndBackfillPageOverlapWithoutDeadlock`: 정기 반영과 적재 페이지를 동시에 시작해도 둘 다 성공 | |

준비안에서 codex가 따로 언급하지 않은 결정(D-C 마감 연장 시 OPEN 복귀, D-D 마감 당일 누락은 CHECK, D-G 비교 대상 외 값은 기록 없이 덮어쓰기, D-H `item` 래퍼 허용, D-I 텍스트 정규화 없음)은 준비안의 권장대로 구현하고 erd.md와 계약에 적었다. 바꿔야 하면 알려 주면 된다.

## 한계와 미확인
- **공식 API 봉투**: 실제 `apis.data.go.kr` 응답을 아직 본 적이 없다. 파서는 swagger 문서의 `{"item": …}` 래퍼와 공개 경로의 평평한 원소를 모두 받는다. #4가 실응답을 받으면 안 쓰는 쪽을 지워야 한다. 빈 달이 `200 + []`인지 `resultCode 3`인지도 #4가 확인할 일이다(지금은 3이면 `RESULT_CODE_NOT_OK`).
- **게이트웨이 오류 본문**: 실물을 확보하지 못해 테스트에서 합성 XML 문자열을 쓴다(테스트 주석과 fixtures README에 표시).
- **`APPLY_ERROR` 경로**(반영 중 DB 예외 → 롤백 후 FAILED 기록 → 예외 재전파)는 테스트하지 않았다. 일부러 DB 오류를 일으킬 깔끔한 방법을 찾지 못했다. 코드 경로는 짧지만 검증되지 않은 상태다.
- **`broken_text` 오탐**: 정상 문장에서 `?` 바로 뒤에 공백 없이 한글이 오면 걸린다. 497건 스냅숏에서 규칙에 걸린 10건은 모두 `「」` 손실 형태였지만, 그 이상은 확인하지 않았다.
- **`src_url_generic` 비율**: 같은 스냅숏에서 249/497(50%)이 generic이다(Python으로 계산한 값이라 Java `URI`와 경계 사례가 다를 수 있다). 원문 링크는 그대로 보여 주고 잡알리오 링크를 함께 주는 표시라서 오탐 비용은 작다고 판단했다.
- **텍스트 정규화 없음**: 원천이 공백만 바꿔도 `posting_change`가 1건 생긴다. 알림과는 무관하다. 운영에서 빈도를 보고 정한다.
- **`pref`**: `prefCondCn`/`prefCn`의 null과 빈 문자열을 구분하지 않는다. #5는 U+001F로 나눠 표시해야 한다(그대로 `th:text`로 찍으면 제어 문자가 섞인다). 이 점을 #5 담당에게 전달해야 한다.
- **D-0 누락 시각**: 마감 당일 저녁에 진행 공고 목록에서 빠지면 CHECK가 된다. 실제로 그렇게 빠지는지는 평일 운영에서 확인해야 한다.
- **성능**: 행마다 SQL 문을 따로 실행한다(정기 약 500행). 1,000행짜리 적재 페이지의 반영 시간은 재지 않았다. 로컬 반영 테스트 16개 전체가 약 15초였다는 것만 확인했다(측정 목적의 수치는 아니다).
- **로그**: 반영하지 않은 응답은 WARN 로그에 실패 코드, 필드 이름, `sn`, 정수일 때의 `resultCode`를 남긴다. 본문과 URL은 남기지 않는다. `collect_run.error`에는 상수만 들어간다.

## 다음 담당에게
- **#4 (Sol)**: 계약 §2~§3을 따르면 된다. 트랜잭션 밖에서 호출하고, `upsertPage`가 예외를 던지면 그 달을 INCOMPLETE/FAILED로 남긴다. 월 단위 합계 검사는 반환된 `Counts.totalCount`, `received`로 한다. 공식 봉투를 확인하면 `ListResponseParser.parse`의 `item` 분기 한 줄과 테스트 하나를 정리한다.
- **#5 (agy)**: `pref`는 `split("\u001F", -1)`로 [요약, 상세]. `src_url_generic`이 false여도 `src_url`이 무효면 잡알리오 링크를 보여 줘야 한다(스킴 없는 값 19/497건). `broken_text` 배지 문구는 "의심"으로 써야 한다.
- **M2**: "새 공고"는 첫 COMPLETE 정기 회차 이후의 `first_seen_at`이고 `from_backfill = false`인 행이다. 시작일이 3년보다 오래된 진행 공고도 있으므로(스냅숏에서 1건, 2020년 시작) 첫 회차를 기준선으로 봐야 한다.
