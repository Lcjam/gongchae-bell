# M1 공식 fixture 합본 검수 · claude

> 2026-11-15 · 조율: codex · 대상: Sol의 격리 로컬 브랜치 `test/#4-m1-integration`, **불변 커밋 `03e2c4ccaf48185f2f2fadc32bef3b4f18eb8b35`**
> 읽은 방법: `git show 03e2c4c:<path>`와 `git log`/`git diff`만 썼다. Sol의 작업 경로(`m1-integration`)의 워킹 파일은 열지 않았다. 테스트를 다시 돌리지 않았고 코드·브랜치·설정을 고치지 않았다. 실제 API 호출 0회.
> 읽은 것: `OfficialFixtureIntegrationTest.java`(신규 232줄), `sol-m1-integration-result.md`, 같은 커밋의 `application.yml`·`.env.example`·`IntegrationTestSupport`·`CatalogIntegrationTest` 머리, `e709922..03e2c4c`의 커밋 목록.

## 결론
**반환할 결함은 없다.** 새 테스트 3건은 보고서가 말하는 경로를 실제로 지나고, fixture 변형은 보고서에 적힌 범위(목록 한 행 발췌 + 봉투 재작성)뿐이며, 실제 API로 나갈 수 있는 경로는 막혀 있다. 아래 Low 5건은 이름·격리·합본 순서에 관한 것이고 결과를 뒤집지 않는다. 다만 **이 통과 결과는 `e709922` 기준**이고, 최신 #4(`56faf05`)와 합친 뒤의 수치는 아직 없다(아래 L4).

## 테스트별 확인

### 1. `official497ListPassesTheRealParserAndAtomicRegularApplyWithoutDetailFanout`
- **경로**: 공식 497건 본문 그대로 → 실제 `PostingIngestor.parsePage`(DB 무변경 확인, `count(posting)=0`) → 실제 `applyRegular` → `Counts(497, 497, 497, 0, 0)`, 공고 497행, 모두 `detail_status='NONE'`, 회차 행의 `total_count/received/inserted=497`. #3 계약 v3의 두 공개 메서드를 공식 본문으로 지나가는 것이 맞다.
- **fixture 변형**: 없음.
- **거짓 양성**: `api_attempt=0`, `REQUESTS=0`은 이 테스트가 `RegularCollector`·`DetailCollector`를 부르지 않으므로 **항상 참이다**. 이름의 "WithoutDetailFanout"이 검증하는 속성처럼 읽힌다(L1). 보고서는 "전체 목록을 RegularCollector에 넣는 흐름은 실행하지 않았다"고 정확히 적었다.
- `closed=0`은 고정 시각(14:41:46 KST)에 497건이 모두 마감 전이라는 실데이터 사실에 기대고, 그 시각은 실제 취득 시각과 같아서 맞다.

### 2. `officialDetailFlowsThroughCollectorBeansDatabaseAndHtmlAndSurvivesListReplay`
- **경로**: 실제 `applyRegular`(한 행) → **실제 Spring 빈** `DetailCollector.collect` → `AdvisoryLease`(e709922의 풀 밖 물리 연결)·`CollectionRuns`·`ApiBudget` → `@Primary` 대역 `RecruitmentClient`(실제 클래스, 루프백 URI) → 공식 상세 본문 → DB `DONE`, steps·files를 원본 JSON 트리와 **동등 비교**(래퍼가 없으므로 정확히 같아야 한다) → 실제 MockMvc `/jobs/307032` HTML. 목록 재반영 `Counts(1, 1, 0, 0, 0)` 뒤 상세 열이 그대로이고 다음 sweep에서 추가 HTTP 0건.
- **fixture 변형**: 497건 중 sn 307032 객체 하나를 **바꾸지 않고** 꺼내, 새 봉투 `{"resultCode":200,"totalCount":1,"result":[행]}`에 넣는다. 공식 봉투의 `resultMsg`는 빠지지만 파서가 쓰지 않는다. Jackson 트리 왕복이라 문자 이스케이프 표기는 바뀔 수 있으나 값은 같다. 상세 본문은 무변형. 보고서 표현과 일치한다.
- **거짓 양성 점검**
  - steps·files는 원본과 트리 비교라 "개수만 맞는" 거짓 양성이 없다.
  - 첨부 URL은 `?recrutAtchFileNo=` 한 개 인자라 HTML 이스케이프(`&amp;`)의 영향을 받지 않는다. 인자가 둘 이상인 URL이었다면 `contains`가 **실패**하는 쪽(거짓 음성)이라 안전하다.
  - 기준 시각 문자열은 DB에 저장된 `finished_at`을 읽어 같은 형식으로 만들어 비교하므로 실제 화면 값이다.
  - 전형 정보 문자열(`전형 정보 0/1`, `경쟁률: 0.0`)은 #5 `4334af8`의 현재 표시에 묶여 있다. 표시 정책이 바뀌면 이 테스트도 함께 바뀐다(L5).

### 3. `officialEmptyListIsIncompleteForRegularButCompleteZeroForBackfill`
- **경로**: 한 행 기준선 → 공식 빈 본문을 실제 `applyRegular`에 → `INCOMPLETE`/`EMPTY_SNAPSHOT`, 마지막 완전 수집 시각과 공고 행 불변. 이어서 **실제 빈** `BackfillCollector.collect`(잠금·회차·예산·루프백 HTTP·`parsePage`·의미 검사·`upsertPage`)로 2010-01 → 명령·월·페이지 3행이 `COMPLETE` 0/0/0, 공고·기준 시각 불변, BACKFILL `SUCCESS` 1회.
- **fixture 변형**: 없음. 기간은 실제 취득 기록과 같다.
- **거짓 양성**: 대역 서버가 경로와 `pbancBgngYmd`·`pbancEndYmd`·`pageNo=1`이 맞을 때만 200을 주고, 그 밖의 요청은 400 + `UNEXPECTED_REQUEST` 기록 → `@AfterEach`에서 실패로 잡는다. 잘못된 요청이 통과로 보일 길이 없다.

## 다른 테스트와의 격리
- `@Import` + `@TestPropertySource`라 **별도 Spring 컨텍스트**가 생긴다. 공용 컨테이너(`IntegrationTestSupport`의 정적 컨테이너)는 같이 쓰고, 테스트 전후로 공고 5개 표·`collect_run`·`api_attempt`·`api_budget`을 `TRUNCATE`한다. `FoundationIntegrationTest`의 "완전 수집 기록 없음" 검사를 깨뜨리는 잔여 행이 남지 않는다.
- 기존 공용 기반(`IntegrationTestSupport`)과 기존 테스트는 바뀌지 않았다(`e709922..03e2c4c`의 테스트 변경은 이 파일 하나와 #5 cherry-pick분뿐).
- `@Primary Clock`·`@Primary RecruitmentClient`는 이 컨텍스트 안에서만 쓰인다. 같은 설정을 쓰는 다른 클래스가 없어 캐시된 컨텍스트가 재사용되지 않는다. 다만 클래스가 끝나면 정적 대역 서버는 멈추고 컨텍스트는 JVM 끝까지 캐시에 남는다(L3).
- `CatalogIntegrationTest`는 여전히 자체 `@Container`를 쓴다(PR #10 리뷰 L5). 이번 커밋과 충돌하지 않는다.

## HTTP 보안 경계
- 대역 서버는 `127.0.0.1`의 임시 포트에만 열린다. 키는 공개 문자열 `fixture-only`다. `UNEXPECTED_REQUEST`에 쿼리 전체가 들어가지만 가짜 키뿐이다.
- 실제 공식 주소로 나갈 수 있는 경로: 같은 컨텍스트에 운영 `@Component RecruitmentClient`(공식 URI)도 만들어지지만, 이를 주입받는 세 수집기는 모두 `@Primary` 대역을 받는다. `CollectorScheduler`·`BackfillCommand`는 `collector.enabled=false`·`collector.backfill=false`로 **명시적으로** 꺼져 있고, `@TestPropertySource`는 OS 환경변수보다 우선하므로 셸에 `COLLECTOR_ENABLED=true`가 있어도 이 클래스에서는 켜지지 않는다. 앱은 `.env` 파일을 자동으로 읽지 않는다(`spring.config.import` 없음).
- 키의 wire 인코딩(`serviceKey`)과 `resultType=json`은 대역 서버가 검사하지 않는다. 이 경계는 `RecruitmentClientTest`가 따로 확인한다. 리다이렉트 금지는 운영 생성자의 `HttpClient` 설정인데, 이 테스트와 `RecruitmentClientTest` 모두 자체 `HttpClient`를 쓰므로 운영 생성자의 설정 자체는 어느 테스트도 확인하지 않는다(기존 공백, 이번 커밋 범위 밖).

## 보고서 대조
- 86 tests의 클래스별 건수는 `e709922`(71) + #5(12) + 신규 3과 맞는다.
- 머지 충돌 처리: `application.yml`에 스케줄러 종료 대기와 `app.base-url`이 모두 있고, `.env.example`은 키 이름만 합쳤다(`APP_BASE_URL` 포함). 보고서와 일치한다.
- "E2E라고 주장하지 않는다", "HTTP 200은 대역의 상태", "e709922 기반 합본에만 유효"라는 한정이 정확하다.

## Low
| # | 내용 | 제안 |
|---|---|---|
| L1 | 테스트 1의 이름 끝 "WithoutDetailFanout"은 테스트가 상세 수집기를 부르지 않아서 항상 참인 성질이다 | 이름에서 빼거나, 남기려면 "상세 수집기를 호출하지 않는 core 경로"라고 주석으로 한정 |
| L2 | (기존 공백, 이번 커밋 아님) `IntegrationTestSupport`와 `CatalogIntegrationTest`는 `collector.enabled`·`collector.backfill`을 고정하지 않는다. 개발자가 `.env`를 셸에 올린 채(`COLLECTOR_ENABLED=true`와 실제 키) `gradlew test`를 돌리면 그 컨텍스트들에서 정기 스케줄러가 켜지고, 실행이 KST :07을 지나면 **실제 API를 호출**할 수 있다. Sol의 새 테스트는 이 문제를 스스로 막았다 | 공용 기반의 `@SpringBootTest(properties=…)`나 `src/test/resources`에서 둘을 false로 고정. `IntegrationTestSupport`는 #3 파일이라 root가 담당을 정하면 claude가 처리할 수 있다 |
| L3 | 클래스가 끝나면 대역 서버는 멈추지만 그 서버를 가리키는 빈을 가진 컨텍스트는 캐시에 남는다. 지금은 재사용하는 클래스가 없어 무해하다 | 선택: `@DirtiesContext`로 클래스 끝에 컨텍스트와 `HttpClient`를 닫는다 |
| L4 | **최신 #4와 합칠 때**: (a) `collab/2026-11-15-api-verification/agy-result.md`는 Sol 쪽이 `b95fa67` 원문, #4 쪽(`304389c` 위 `5e2950b`)은 §5 정정본이라 충돌한다. 정정본을 남겨야 한다(Sol 검수의 필수 정정). (b) 최신 #4에도 공식 fixture 테스트 2건(`officialDetailSampleIsStoredWithoutWrappers`, `officialEmptyPeriodResponseCompletesAMonthWithZeroPostings`)이 있어 테스트 2·3과 일부 겹친다. 둘 다 남겨도 된다. (c) 최신 #4 단독 79건 기준으로 합본 기대 건수는 **79 + #5 12 + 신규 3 = 94건**이다. 최신 #4의 상세 실패 처리·월 집계·키 정규화 변경은 이 3건의 성공 경로를 바꾸지 않는다(`fixture-only`에는 `%`가 없고, 빈 달은 받아들인 0건 페이지로 끝난다)는 것이 내 코드 판단이지만 실행으로 확인한 것은 아니다 | Sol 재검증에서 (a) 정정본 유지, (c) 실제 건수 기록 |
| L5 | HTML 기대 문자열이 #5의 현재 전형 표시(`전형 정보 0/1`, `경쟁률: 0.0`)에 묶여 있다. 실측 상세 4행 중 숫자가 모두 null인 2행은 "형식 확인" 안내로 나간다(보고서에 기록됨) | 표시 정책을 바꿀 때 이 테스트를 함께 고친다. 결함 아님 |
