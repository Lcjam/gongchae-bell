# M1 공식 fixture 합본 검증 결과 · sol

2026-11-15. 작업 경로: `C:/Users/lcj/orca/workspaces/my-project/m1-integration`, 로컬 브랜치: `test/#4-m1-integration`. Orca로 생성했고 writer는 Sol 한 명이다. 원본 사용자 변경, 다른 구현자의 워킹트리, 원본 `.env`는 가져오지 않았다. 새 워크트리에도 `.env`가 없음을 확인했다.

**최종 결과: 최신 #4 `56faf051`을 포함한 합본 94 tests, failures/errors/skipped 모두 0. `check bootJar` 통과.** 공식 fixture 연결 테스트 3건도 수정 없이 통과했다. 아래 1차 기록의 86건은 e709922 기반 결과이며, 최신 부모·검증 head와 결과는 마지막 2차 기록에 명시한다. 원격 CI를 대신하는 결과는 아니다.

## 1차 고정 입력과 로컬 통합 이력

- #4 기반: `e7099226dd93693961ae29250131c8d2025b242b` (공개 계약 v3의 #3 `aab6d5b` 포함).
- #5 대상: `4334af809e3d8ce97205e29dcf9a22d28fcddaa6`까지의 5개 커밋을 순서대로 로컬 cherry-pick했다. 로컬 대응 커밋은 `6998ea3`, `98d3382`, `ff75a41`, `832eb94`, `11c09e2`다.
- 공식 fixture: `b95fa67dd7e17fd1a14d5d9aa0e93173865fae93`을 로컬 `7635427ff4e1952398ffbce639e80ee3b052b35f`로 cherry-pick했다. 이전 #4 구현 전체를 이 fixture 브랜치에서 다시 가져오지 않았다.
- 충돌 해결은 격리 브랜치에서 기존 내용의 합집합만 유지했다. `application.yml`은 scheduler 종료 설정과 app.base-url 모두 보존했고, `.env.example`은 양쪽의 빈 키 이름만 보존했다. 구현현황 표는 #3 설명과 최신 #5 설명을 유지했고 fixture README/보고서의 추가 내용을 보존했다. 새 프로덕션 로직은 쓰지 않았다.
- 자체 추가 파일은 `src/test/java/kr/gongchae/bell/ingestion/runtime/OfficialFixtureIntegrationTest.java`와 이 결과 문서 두 개뿐이다. 기존 공용 테스트 기반과 기존 테스트를 수정하지 않았다.

## 실제 통과한 경로

| 새 테스트 | 실제 경로와 확인 내용 | 입력 변형 및 경계 |
|---|---|---|
| `official497ListPassesTheRealParserAndAtomicRegularApplyWithoutDetailFanout` | 공식 497건 전체 본문 → 실제 `PostingIngestor.parsePage` → DB 무변경 확인 → 실제 `applyRegular`의 파서·원자 반영 → DB 공고 497건, 회차 COMPLETE와 total/received/inserted=497. 상세 상태는 전부 NONE이고 HTTP/호출 시도 0건. | 목록 본문을 바꾸지 않았다. 파싱 API 확인과 정기 반영을 각각 호출한 테스트이며 런타임 최초 적재가 같은 본문을 두 번 파싱하도록 만든 것이 아니다. 전체 목록을 RegularCollector에 넣어 상세를 30건 호출하는 불필요한 흐름은 실행하지 않았다. |
| `officialDetailFlowsThroughCollectorBeansDatabaseAndHtmlAndSurvivesListReplay` | 실제 목록 반영 빈 → 실제 `DetailCollector.collect` → 실제 `AdvisoryLease`/`CollectionRuns`/`RecruitmentClient`/`ApiBudget` → loopback HTTP의 공식 상세 본문 → DB DONE, steps 4건/files 3건을 원본 JSON과 비교 → 실제 웹 controller/repository/Thymeleaf MockMvc HTML. 우대 라벨·원문 버튼·전형 정보 0/1과 경쟁률 0.0·첨부 이름/HTTPS 링크 3개·마지막 COMPLETE 시각을 확인한다. 같은 목록 재반영은 inserted/changed=0이고 steps/files/상세 시각/DONE이 그대로이며 다음 상세 sweep도 추가 HTTP 0건이다. | 497건 중 SN 307032의 원본 행 하나만 골라 totalCount=1로 만든다. 행 값과 상세 본문은 바꾸지 않는다. 단일 대상 상세 수집을 위해 나머지 공고에 가짜 DONE이나 과거 적재 상태를 쓰지 않는다. 단일행 입력에는 다른 공고의 URL 재사용 근거가 없어 generic 링크를 기대하지 않는다. |
| `officialEmptyListIsIncompleteForRegularButCompleteZeroForBackfill` | 먼저 같은 실제 목록 한 행으로 정상 기준선을 만든다. 공식 빈 본문을 실제 `applyRegular`에 주면 INCOMPLETE/EMPTY_SNAPSHOT이고 마지막 COMPLETE와 공고 전체 행이 유지된다. 실제 `BackfillCollector.collect`/잠금/회차/호출 예산/loopback HTTP/파서/의미검증/upsertPage 경로는 같은 빈 본문을 0건 COMPLETE로 기록한다. command/month/page 3개 영수증의 total/received/unique=0, 기존 공고와 기준 시각 불변을 확인한다. | 빈 본문은 무변형이다. 적재 기간은 실제 취득 기록과 같은 2010-01-01~2010-01-31이다. loopback 서버가 기간과 pageNo=1을 확인한다. 3년 전체 적재를 실행한 것은 아니다. |

새 테스트의 HTTP는 `127.0.0.1`의 임시 포트만 사용한다. Spring `@Primary`로 이 주소를 향하는 실제 RecruitmentClient를 주입하고 scheduler/backfill 자동 실행은 명시적으로 끈다. 키는 공개 테스트 문자열 `fixture-only`이며 실제 키를 읽거나 복사하지 않았다. 새 테스트 전체에서 성공 HTTP는 상세 1회와 빈 기간 1회뿐이고 모두 로컬 대역이다. 497건 본문과 HTML 렌더링은 HTTP를 호출하지 않는다는 카운터도 확인한다.

Spring 빈 경로를 검증했지만 정상 전체 497건에 대해 `RegularCollector → DetailCollector`까지 실행한 E2E라고 주장하지 않는다. 실제 연결은 전체 목록의 core 반영, 단일 실제 공고의 detail 빈 경로, 빈 달의 backfill 빈 경로로 나누어 검증했다. HTTP status 200은 테스트 대역이 반환한 상태이며 공식 API에 재요청해 확인한 상태가 아니다.

## 기존 검증을 반복 추가하지 않은 범위

기존 CatalogUnitTest/CatalogIntegrationTest의 악성 링크·XSS·원문 이동·미수집/알 수 없는 상세 형식·404·필터·기관 통계·오류 페이지·지연/회복 검증을 전체 suite로 함께 실행했다. 새 테스트에서 그 사례들을 별도로 복제하지 않았다. 상세 실패/재시도 상한·성공 재호출 방지는 기존 RuntimeDatabaseTest가 함께 실행한다. `NONE`과 `FAILED`를 서로 다른 웹 문구로 보여 주는 기능을 새로 구현하거나 검증했다고 주장하지 않는다.

관측된 현행 표시: 실제 상세의 4행은 DB에 모두 보존된다. 웹은 경쟁률 0.0이 있는 두 행만 전형 정보 0/1로 표시하고, 표시 가능한 숫자 필드가 모두 null인 두 행에 대해서는 일부 정보 형식 확인 안내를 낸다. 본 테스트는 새로운 단계 이름/그룹핑/라벨 정책을 만들지 않았다. 이 표시가 제품에 충분한지와 `recrutPbancTtl`의 일반적 의미는 원천 계약 검토 대상이다.

## 1차 실행 조건과 결과

- 로컬 Windows x64, Docker Desktop의 Testcontainers 임시 `postgres:17` DB. 영속 개발 DB를 사용하지 않았다.
- Gradle Wrapper 9.9.1. 셸의 Java는 17.0.12이며 빌드/테스트 toolchain은 Temurin 21.0.13+9다.
- 새 통합 테스트 고정 시각은 `2026-11-15T05:41:46Z`, zone은 Asia/Seoul이다. 수집 완료 시각은 실제 collect_run에 저장된 SQL 시각을 읽어 화면과 비교한다.
- 첫 전체 실행은 새 테스트 기대 문자열의 PowerShell 인코딩 손실로 1건 실패했다. 다음 실행은 단일행 발췌 뒤 URL 재사용 판정을 기대한 테스트 가정으로 1건 실패했다. 두 문제 모두 테스트에서만 수정했고 프로덕션 코드는 수정하지 않았다.
- 수정 후 `./gradlew.bat --no-daemon test --tests '*OfficialFixtureIntegrationTest'`: **3 tests 통과**, BUILD SUCCESSFUL in 22s.
- 최종 `./gradlew.bat --no-daemon check bootJar`: **BUILD SUCCESSFUL in 1m 7s**. 최종 테스트는 실행됐고 bootJar는 같은 프로덕션 입력으로 이미 만들어져 UP-TO-DATE였다. 최초 실행에서 bootJar 작업 생성 자체도 성공했다.
- 최종 `build/test-results/test/TEST-*.xml`의 합계: **86 tests / 0 failures / 0 errors / 0 skipped**.

| 테스트 클래스 | 건수 |
|---|---:|
| FoundationIntegrationTest | 4 |
| ListResponseParserTest | 8 |
| PostingIngestorIntegrationTest | 20 |
| BackfillCommandIntegrationTest | 1 |
| BackfillRangeTest | 2 |
| BackfillRuntimeTest | 9 |
| CollectorWiringIntegrationTest | 2 |
| RecruitmentClientTest | 9 |
| RuntimeDatabaseTest | 16 |
| CatalogUnitTest | 6 |
| CatalogIntegrationTest | 6 |
| OfficialFixtureIntegrationTest (신규) | 3 |

`git diff --cached --check` 통과. `gitleaks dir --redact .`는 약 3.95MB 검사에서 no leaks found였다. 새 워크트리에 원본 `.env`가 없으며 `.tools/CaptureOfficialFixtures.java`나 실제 키를 가져오지 않았다.

## 1차 제출 기록

테스트와 이 문서를 이 격리 로컬 브랜치에 커밋해 제출한다. push, PR 생성, 원격 머지, 배포, 실제 API 추가 호출은 하지 않았다. 원본 사용자 변경과 Claude/agy 담당 브랜치를 수정하지 않았다.

1차 제출 시점에는 e709922 이후 최신 #4를 반영하지 않았으며, 당시 결과는 그 기반에만 유효했다. 이후 조율자가 지정한 최신 #4의 통합·재검증은 아래 2차 기록으로 갱신한다. 브라우저 재관측, 실제 API 재검증, 3년 전체 적재, 상세 전체 공고 수집 및 새로운 단계 의미 해석은 수행하지 않았다.
## 2차: 검수된 최신 #4 통합 및 최종 재검증

조율자가 지정한 불변 `56faf0510c30d2b7cecc00a40e2757c2d509128e`만 기존 격리 브랜치에 로컬 merge했다. #4의 코드 커밋은 `5e2950bd93dc6a8f6b5b1fb1b5aff0dbf6aae9b3`이고 56faf051은 결과 문서 후속이다. 다른 구현자의 이후 워킹트리를 가져오지 않았다.

- **검증한 merge head**: `f5474a8c8132a6d5ff1c83f6a9f47a0833a8167a`.
- **첫 부모(기존 합본)**: `03e2c4ccaf48185f2f2fadc32bef3b4f18eb8b35`.
- **둘째 부모(최신 #4)**: `56faf0510c30d2b7cecc00a40e2757c2d509128e`.
- 이 결과 문서 갱신은 위 merge head 위의 문서 전용 커밋으로 제출한다. 최종 제출 head는 완료 응답에 남기며, 코드·테스트·fixture는 검증한 merge head와 같다.

유일한 충돌은 `collab/2026-11-15-api-verification/agy-result.md`의 add/add였다. 지시대로 최신 #4의 검수된 내용을 선택해 공식 fixture의 로컬 커밋 기록 정정을 보존했다. 공식 fixture JSON 3개는 기존 합본과 동일하다. `application.yml`의 scheduler 종료 설정+app.base-url 및 `.env.example`의 빈 키 이름 목록도 기존 합본 대비 diff 0으로 보존됐다.

`DetailCollector.save`의 실패 상태 기록 책임이 collect로 옮겨진 최신 구현을 그대로 사용했다. 신규 OfficialFixtureIntegrationTest는 실제 collect 빈 경로를 사용하므로 테스트 기대나 공용 기반을 바꿀 필요가 없었다. 새 프로덕션 로직, 테스트 조정, 환경파일 변경은 하지 않았다.

### 실행 결과

동일한 로컬 Windows x64 / Temurin 21.0.13+9 toolchain / Gradle 9.9.1 / Testcontainers postgres:17 환경에서 **`./gradlew.bat --no-daemon check bootJar`를 한 번** 실행했다.

- **BUILD SUCCESSFUL in 1m 20s**. test와 bootJar 작업 모두 실행돼 성공했다.
- 최종 JUnit XML 합계: **94 tests / 0 failures / 0 errors / 0 skipped**.
- 공식 fixture 합본 3건은 모두 통과했다. 1차 이후 #4에서 추가된 8건도 함께 실행됐다: BackfillRuntimeTest 9→11, RecruitmentClientTest 9→10, RuntimeDatabaseTest 16→18, CollectorPropertiesTest 신규 3건. 다른 클래스 건수는 1차 표와 같다.
- `git diff --cached --check` 통과. 최신 통합 트리에서 `gitleaks dir --redact .`는 약 4.01MB 검사에 no leaks found였다.
- 앱 결함으로 인한 실패는 없었다. 이번 후속 검증에서는 앱·테스트 코드를 직접 고치지 않았다.

### 최종 한계와 경계

새 fixture 테스트가 통과한 실제 빈 경로와 단일행 발췌 범위는 위와 같다. 전체 497건의 core 반영, 단일 실제 상세의 빈→DB→HTML, 빈 달의 backfill 빈 경로를 검증했으며 전체 497건의 상세 fanout E2E라고 주장하지 않는다. 상세의 null 행 표시/단계 의미 일반화, 브라우저 재관측, 실제 API 추가 호출, 3년 전체 적재는 수행하지 않았다.

새 합본 브랜치는 로컬만 사용했다. 실제 API 키·원본 env를 읽거나 복사하지 않았고, 영속 개발 DB·다른 담당자 브랜치·원본 사용자 변경을 수정하지 않았다. push/PR 생성/원격 머지/배포는 하지 않았다. 이 로컬 합본 head의 원격 CI와 agy의 최신 #4 독립 검수 완료 여부는 이 실행으로 대신하지 않는다.
