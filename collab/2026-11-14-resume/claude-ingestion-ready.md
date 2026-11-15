# #3 수집 코어 착수 준비 · claude

> 2026-11-14 · 조율: codex · 대상: [이슈 #3](https://github.com/Lcjam/gongchae-bell/issues/3) "공고 파싱·완결성·변경 관측·상태 판정" (FR-C2~C4, FR-C8)
> 읽은 것: CLAUDE.md(재개 역할 분담 포함), docs/prd.md, docs/design/erd.md, issue-ingestion.md(#3), issue-collector.md(#4), 현재 src(V1·V2, CatalogController, FoundationIntegrationTest), fixtures/, collab/2026-11-14-resume/agy-catalog-ready.md(#5 경계 확인용), foundation-closeout-sol.md
> 구현 파일은 건드리지 않았다. 아래 "실측"은 키 없는 공개 경로를 **1회** 호출해 scratchpad에만 저장하고 분석한 결과다. 저장소에는 넣지 않았다.

## 0. 요약
1. **#3은 "응답 문자열 → DB 반영"까지만 맡는다.** HTTP, 키, 예산, 회차 선점, 스케줄러, 최초 적재, 상세 수집은 #4다. 둘 사이의 경계는 메서드 두 개다(§1).
2. **새 마이그레이션은 필요 없다.** V1 컬럼으로 충분하다. 생기면 #4, #5와 V3 번호를 먼저 맞춘다.
3. 모호한 매핑 9개에 실측 근거와 권장 기본값을 붙였다(§2, §6). 그중 **D-B(정기 수집의 잘못된 행은 응답 전체를 불완전으로 처리)**는 Sol이 지적한 대로, 내가 02 리뷰 F2에서 낸 "행 건너뛰기" 제안을 정기 수집에 한해 **철회**하는 내용이다.
4. 테스트는 실제 표본을 코드로 변형해서 만든다. Testcontainers는 두 번째 테스트 클래스가 생기기 전에 공용 컨테이너로 옮긴다(§5).

## 1. 작업 경계

| 항목 | 담당 |
|---|---|
| 응답 파싱(봉투, 행), 완결성 판정, 공고·기관·코드·코드명 upsert, 변경 관측, 상태 판정, 품질 플래그, **정기 수집 회차의 결과 기록** | **#3** |
| HTTP 클라이언트, `serviceKey`, 타임아웃·재시도, `api_budget`, 회차 선점 INSERT, `@Scheduled`, 최초 적재 명령과 월·페이지 반복, 상세 수집, 공식 API 봉투의 실응답 확인 | #4 |
| 조회 시점 마감 표시, D-day, `SafeUrlValidator`, 품질 배지, 잡알리오 링크 생성(`recruitview.do?idx={sn}`) | #5 (agy-catalog-ready.md와 일치) |
| "새 공고" 판정(첫 COMPLETE 정기 회차를 기준선으로 보고 그 뒤의 `first_seen_at`만) | #7. #3은 컬럼만 정확히 채운다 |

**#3 → #4 인터페이스 (제안)**. 패키지는 `kr.gongchae.bell.ingestion`(ADR-0002).
```java
// 정기 수집 1회분 전체. #4는 회차를 선점해 runId를 얻은 뒤 응답 본문을 그대로 넘긴다.
RunResult applyRegular(long runId, String body, Instant observedAt);
// 최초 적재 한 페이지. 누락 판정과 회차 결과 기록은 하지 않는다. #4가 월 단위로 합산해서 기록한다.
UpsertCounts upsertPage(String body, Instant observedAt);   // fromBackfill = true
```
- `observedAt`은 호출자가 넘긴다(#4는 `clock.instant()`). KST 날짜는 `observedAt.atZone(clock.getZone())`로 구한다. 테스트는 시각만 바꿔 넘기면 되므로 `Clock` 빈을 덮어쓸 필요가 없다.
- `applyRegular`은 `collect_run`을 `UPDATE … WHERE id = :runId AND result IS NULL`로 마감한다. 영향 행이 0이면 예외를 던져 롤백한다(이미 마감된 회차를 다시 마감하지 않기 위해).
- `upsertPage`의 실패 처리 방식(행 오류 시 페이지 전체 실패인지)은 #4가 3년치 표본을 본 뒤 정한다. #3은 정기 수집 규칙만 확정한다.

## 2. 필드 매핑 (응답 `result[*]` → 컬럼)

실측 조건: `opendata.alio.go.kr/.../recrutInquiryAjaxList.do?ongoingYn=Y&pageNo=1&numOfRows=1000`, 2026-11-14 22:22 KST, 1회. `resultCode=200`, `totalCount=497`, 받은 건수 497, 행마다 키 30개. 공개 경로라 공식 API와 봉투가 다르다(§3).

| 응답 | 컬럼 | 규칙 | 실측 |
|---|---|---|---|
| `recrutPblntSn` | `posting.sn` | 정수. 없으면 응답 불완전 | 497/497 정수, 중복 0 |
| `pblntInstCd` / `instNm` | `posting.inst_code`, `institution(code, name)` | 기관을 먼저 upsert한다. 이름이 바뀌면 `institution.name`만 갱신한다(공고 변경 아님) | 빈 값 0 |
| `recrutPbancTtl` | `title` | 원문 그대로 | 빈 값 0 |
| `pbancBgngYmd` / `pbancEndYmd` | `bgng_date` / `end_date` | `YYYYMMDD` 엄격 파싱 | 형식 오류 0. 시작일 최솟값 20200721, 마감 최댓값 20501231(상시 채용으로 보임, 19건이 2027년 이후) |
| `recrutSe` | `recrut_se` | 원문 코드. 표시명은 #5가 3개 고정값으로 처리(`code_name` CHECK에 없음) | R2030 291, R2010 159, R2020 47 |
| `recrutNope` | `recrut_nope_raw`, `recrut_nope` | raw는 문자열로 보관. 정수 노드이거나 `^\d+$` 문자열이면 숫자 | 497/497 정수 |
| `aplyQlfcCn` | `aply_qlfc` | 원문 그대로 | |
| `prefCn` | `pref` | **D-A** 참조 | `prefCn` 빈 값 0, `prefCondCn` null 29 |
| `scrnprcdrMthdExpln` | `scrnprcdr` | 원문 그대로 | |
| `srcUrl` | `src_url` | **원문 문자열 그대로**(trim 금지). 판별은 §6 | 스킴 https 419, http 59, **없음 19**. 스킴 없는 값 예: `www.kps.co.kr`, `.`, `,` → 표시 안전성은 #5가 처리 |
| `ongoingYn` | `ongoing_yn` | 상태 판정에 사용 | 497/497 Y |
| `ncsCdLst`, `workRgnLst`, `hireTypeLst`, `acbgCondLst` | `posting_code(type=NCS/REGION/HIRE/EDU)` | 쉼표 분리, 정렬, 중복 제거 | 공백·중복 코드 0 |
| `*NmLst` | `code_name` | 코드 수와 이름 수가 같을 때만 같은 순서로 짝지어 upsert. 다르면 이름만 건너뛴다(공고는 반영) | 개수 불일치 0 |
| `decimalDay`, `disqlfcRsn`, `nonatchRsn`, `replmprYn`, `pbadmsStdInstCd`, `recrutSeNm`, `steps`, `files` | 저장 안 함 | `steps`, `files`는 목록 경로에서 **쓰지 않는다**(FR-C7 보존) | 목록의 `steps`/`files`는 모두 빈 배열 |

## 3. 응답 봉투와 완결성 판정 (FR-C2)

**봉투**: data.go.kr 데이터셋 페이지(15125273)에 들어 있는 공식 swagger의 `list` 200 응답은 `{ result: [ … ], resultCode: integer, resultMsg, totalCount: integer }`이다. 다만 swagger는 배열 원소를 **`{ "item": { …필드 } }`로 감싸서** 정의했고, 공개 경로의 실제 응답은 감싸지 않은 평평한 객체다. 공식 응답 원문은 아직 아무도 보지 못했다(#4 완료 기준). 그래서 다음을 권한다.
- 운영 파서는 공식 봉투(최상위 `resultCode`/`totalCount`/`result`)만 받는다. 공개 경로의 `{"data": …}` 래퍼는 **테스트 헬퍼에서만** 벗긴다(PRD §8 "대체 경로는 자동으로 섞지 않는다").
- 원소는 `item`이 있으면 그 안을, 없으면 그대로 읽는다(한 줄). 둘 다 테스트한다. #4가 실응답을 받으면 안 쓰는 쪽을 지운다.
- `resultCode`는 정수와 숫자 문자열을 모두 받는다(swagger에서도 list는 integer, detail은 string으로 서로 다르다).
- swagger에 적힌 오류 코드 키는 0, 1, 2, 3, 5, 6, 7, 10, 11이다(페이지의 한글 설명은 원문부터 깨져 있어서 읽을 수 없다). 게이트웨이 수준 오류(키 미등록, 한도 초과)는 JSON이 아닐 수 있다 → 파싱 실패로 처리한다.

**판정** (모두 공고 테이블 변화 0, 마지막 완전 수집 시각 유지):

| 경우 | 회차 결과 | `error` 코드 |
|---|---|---|
| JSON이 아님, 잘린 JSON | FAILED | `NON_JSON` |
| `resultCode` ≠ 200 | FAILED | `RESULT_CODE_<n>` |
| `totalCount` 없음 | INCOMPLETE | `NO_TOTAL_COUNT` |
| `totalCount` ≠ 받은 건수 | INCOMPLETE | `COUNT_MISMATCH` |
| 받은 건수 0 (정기 수집) | INCOMPLETE | `EMPTY_SNAPSHOT` (진행 공고가 0건일 리 없으므로, 받아들이면 전부 CHECK가 된다) |
| `sn` 중복 | INCOMPLETE | `DUPLICATE_SN` |
| 행의 `sn`·`pblntInstCd`·`instNm`·제목·날짜 중 하나라도 없거나 형식 오류 | INCOMPLETE | `INVALID_ROW` (**D-B**) |
| 위에 해당하지 않음 | COMPLETE | null |

- `error`에는 위 상수만 넣는다(erd.md 규칙, 100자 이하). 원본 예외 메시지는 넣지 않는다. 코드는 enum 하나로 둔다.
- 실패 기록은 공고 반영과 **별도 트랜잭션**에서 한다(반영 트랜잭션은 롤백되므로).
- 저장소의 10건 표본은 `totalCount=497`, 받은 건수 10이므로 **그대로 넣으면 `COUNT_MISMATCH` 케이스**가 된다. 완전 수집 테스트는 헬퍼가 `totalCount`를 10으로 맞춘다.

## 4. 상태 판정 (FR-C3)과 변경 관측 (FR-C4)

`today` = `observedAt`의 KST 날짜. 마감은 `end_date < today`(D-0은 아직 진행).

| 대상 | 규칙 |
|---|---|
| 응답에 있는 행 | `end_date < today` 또는 `ongoingYn = N` → CLOSED, 그 외 → OPEN. 기존 CHECK도 OPEN이 된다. 기존 CLOSED가 마감일 이후로 다시 나오면 **D-C** |
| 응답에 없고 저장 상태가 OPEN/CHECK인 행 (정기 수집, COMPLETE일 때만) | `end_date < today` → CLOSED, 그 외 → CHECK (**D-D**) |
| 최초 적재 페이지 | 응답에 있는 행만 같은 규칙. 누락 판정 없음. 새 행은 `from_backfill = true` |
| `collect_run.closed` | 이번 회차에 CLOSED로 바뀐 수 |

**변경 관측**
- 비교 필드 12개(PRD FR-C4): `title`, `bgng_date`, `end_date`, `recrut_se`, `HIRE`, `NCS`, `REGION`, `recrut_nope_raw`, `src_url`, `aply_qlfc`, `pref`, `scrnprcdr`. 코드 목록은 정렬해서 비교한다. 모집 인원은 raw로 비교한다.
- `content_hash` = 위 12개를 고정 순서의 JSON 배열로 직렬화한 값의 SHA-256 hex(64자, 컬럼 길이와 맞음). 해시가 같으면 필드 비교를 건너뛴다.
- 해시가 다르면 필드별 `{old, new}`를 `posting_change.changed_fields`에 1행으로 쓰고 `last_changed_at = observedAt`을 넣는다. 관측 시각은 `observedAt`이다.
- 비교 대상이 아닌 값(`EDU` 코드, `ongoing_yn`, 기관명, 품질 플래그)은 바뀌면 덮어쓰기만 하고 변경 기록은 남기지 않는다(**D-G**).
- 모든 반영 행은 `last_seen_at = observedAt`. 새 행은 `first_seen_at = last_seen_at = observedAt`이다.

**트랜잭션**: [01-claude §3-4](../2026-11-14-implementation/01-claude.md)대로 한다. 정기 수집은 `SELECT … FROM posting WHERE sn = ANY(:sns) OR status IN ('OPEN','CHECK') ORDER BY sn FOR UPDATE`로 잠근 뒤 비교한다. 새 행은 `INSERT … ON CONFLICT (sn) DO NOTHING`. 기존 코드 행은 `SELECT … WHERE sn = ANY(:sns)` 한 번으로 읽는다. 코드 집합이 바뀐 공고만 해당 `sn`의 코드를 지웠다가 다시 넣는다. 조율용 SQL은 JdbcClient로 짠다.

## 5. 테스트 계획

**기반**
- 공용 컨테이너: `IntegrationTestSupport`(추상 클래스)에 `static final PostgreSQLContainer`를 static 블록에서 `start()`하고 `@DynamicPropertySource`를 둔다. `FoundationIntegrationTest`는 이걸 상속하도록 바꾼다(자체 `@Container` 제거). 새 의존성이 필요 없다. 이렇게 하면 Spring 컨텍스트 하나를 모든 클래스가 공유하고, 앞 클래스가 끝나면서 컨테이너가 내려가는 문제가 없다(02 리뷰 F1).
- 수집 테스트의 `@BeforeEach`: `TRUNCATE posting_change, posting_code, posting, code_name, institution, collect_run RESTART IDENTITY CASCADE`.
- fixture 헬퍼(테스트 전용): 실제 표본을 Jackson 트리로 읽어 `data` 래퍼를 벗기고, 행 변형(마감일 변경, `decimalDay` 변경, 코드 순서 섞기, 행 삭제·복제, `totalCount`·`resultCode` 변경, 문자열 잘라내기)을 메서드로 제공한다. 손으로 쓴 JSON 파일을 늘리지 않는다. "실제 응답으로 만든 fixture"(DoD)를 지키면서 출처가 한 파일로 유지된다.
- **품질 표본 추가**: 현재 10건에는 깨진 문자 사례가 없다. 공개 경로에서 1회 받아 실제 행 2건(`307009` prefCn, `306968` aplyQlfcCn, 2026-11-14 22:22 KST 기준)을 **원문 그대로** 잘라 `fixtures/`에 넣고 README에 출처와 시각을 적는다. 이미 받은 사본이 내 scratchpad에 있으니 구현자가 원하면 넘긴다. 다시 받아도 호출은 1회다.

**케이스** (이슈 #3 완료 기준 → 테스트)

| # | 검증 | 기대 |
|---|---|---|
| 1 | 파서: 실제 표본 10건 | 10행. 코드 정렬, 날짜, 모집 인원 숫자, 코드명 짝 |
| 2 | 파서: 원소 `item` 래퍼 / 평평한 원소 | 같은 결과 |
| 3 | 동일 입력 두 번 반영 | 두 번째: `posting_change` 0, `inserted` 0, `changed` 0 |
| 4 | 마감일만 변경 | `posting_change` 정확히 1행, `{"end_date":{"old":…,"new":…}}` |
| 5 | `decimalDay`만 변경 | 0행 |
| 6 | 코드 순서만 변경(`R600002,R600007` → 역순) | 0행 |
| 7 | 실패 7종(§3 표: NON_JSON, 잘린 JSON, RESULT_CODE, COUNT_MISMATCH, EMPTY, DUPLICATE_SN, INVALID_ROW) | `posting`·`posting_code`·`posting_change`·`institution` **전체 덤프가 반영 전과 동일**, 회차 결과와 코드 기록, 기준 시각(`max(finished_at) … COMPLETE`) 불변 |
| 8 | KST 마감: `observedAt = 2026-11-19T15:30:00Z`(KST 11-20 00:30) | 표본 307028(마감 11-19) CLOSED, 307032(마감 11-20) OPEN. UTC 날짜로 계산하면 307028이 OPEN이 되어 실패한다 |
| 9 | 누락 CHECK: 완전 응답에서 마감 전 공고 1건 제거 | 그 공고 CHECK. 다시 나타나면 OPEN. 마감일이 지난 뒤 빠지면 CLOSED. `ongoingYn=N` 행은 CLOSED |
| 10 | 불완전 응답에서 공고 제거 | 상태 변화 없음(7번에 포함 가능) |
| 11 | 원문 보존 | `src_url`이 바이트 단위로 같다(307028의 `:442` 포트와 인코딩된 쿼리 포함) |
| 12 | 개별 링크 판별(§6 D-E) | 표본 10건에서 generic = 307027(루트), 307032·307022(공유 2), 307031·307030·307029(공유 3). 아님 = 307028·307026·307023·307021 |
| 13 | 깨진 문자(§6 D-F) | 품질 표본 2건은 `broken_text = true`이고 본문은 바뀌지 않는다. 기본 표본 10건은 false |
| 14 | 상세 보존 | `steps`·`files`·`detail_status=DONE`을 미리 넣고 목록 반영 → 그대로 |
| 15 | 최초 적재 페이지 | 새 행 `from_backfill = true`, 응답에 없는 기존 OPEN 행은 바뀌지 않음 |
| 16 | (권장) 같은 `sn`을 겹쳐 가진 두 반영을 두 스레드로 동시에 실행 | 둘 다 커밋, PK 위반이나 교착 없음 |

회차 선점, 두 앱 동시 실행, 예산은 #4의 테스트다.

## 6. 결정 필요 (구현 전에 codex 검수, 필요하면 agy 확인)

| # | 쟁점 | 권장 기본값 | 근거 |
|---|---|---|---|
| D-A | `pref`에 무엇을 넣나 | `prefCn`만. `prefCondCn`은 저장하지 않는다 | 실측 `prefCn` 497/497 존재, `prefCondCn` null 29. 표본 307028에서 `prefCondCn`은 "우대내용 확인 요망"이고 내용은 `prefCn`에 있다. 01-agy와 01-codex가 합의를 요청한 항목 |
| D-B | 정기 수집에서 필수값이 없거나 형식이 틀린 행 | **응답 전체를 INCOMPLETE**(`INVALID_ROW`)로 하고 아무것도 반영하지 않는다. 02 리뷰 F2의 "행 건너뛰기"는 정기 수집에 한해 철회한다 | FR-C2 "필수 식별자 존재일 때만 완전 수집", codex PRD 01 "잘못된 날짜·누락값을 임의로 채우지 않는다". 실측 497건에 해당 행 0 → 멈출 위험이 낮다. 생기면 "2회 연속 미완료" 경보로 드러난다. 최초 적재(오래된 데이터)의 정책은 #4가 표본 검수 뒤 정한다 |
| D-C | CLOSED 공고가 미래 마감일, `ongoingYn=Y`로 다시 나타남(마감 연장) | OPEN으로 되돌린다. erd.md 상태 전이에 `CLOSED → OPEN`을 추가한다 | FR-C3은 마감 조건만 정한다. 데이터가 진행 중이라고 말하는데 CLOSED로 두면 목록에서 사라진다. PRD FR-A4도 "마감 연장"을 예상한다 |
| D-D | D-0 공고가 마감 당일 응답에서 빠짐 | PRD 그대로 CHECK(마감일이 아직 지나지 않았으므로). 다음 날 CLOSED | 마감 시각이 지나면 당일에 빠질 수 있다. 오늘이 토요일이라 실측으로 확인하지 못했다. 운영에서 저녁마다 D-0이 CHECK로 바뀌는지 보고 필요하면 다시 정한다 |
| D-E | `src_url_generic` 판별 | http(s)로 파싱되는 URL 중 ① 경로가 비거나 `/`이고 쿼리가 없음, 또는 ② **같은 응답에서 2개 이상의 공고가 같은 URL을 씀**. 한 번 true가 되면 URL이 바뀌기 전까지 유지한다. 스킴이 없거나 파싱이 안 되는 값은 판별하지 않는다(#5가 무효로 처리) | 실측 497건: 공유 225, 루트 142, 합계 **264(53%)**. 03의 "기관 첫 화면 101/504"보다 넓다. 공유 URL은 정의상 한 공고를 가리키지 않는다. 잡알리오 링크를 원문 링크와 "함께" 보여 주므로 오탐 비용이 작다. 코드에 `shortcut:` 주석으로 "응답 범위 내 공유만 본다"를 남긴다 |
| D-F | `broken_text` 판별 | 저장하는 텍스트(제목, 기관명, 자격, 우대, 전형 설명)에 `U+FFFD`가 있거나 `?` 바로 뒤에 한글 음절이 오면(`\?[가-힣]`) true | 실측에서 깨짐의 실체는 `「」`가 `?`로 바뀐 형태다(예: `?병역법? 제76조`). 저장 필드 기준 10/497건. `U+FFFD`는 0건이지만 같은 정규식에 한 항목만 더하면 된다. 글 속 정상 물음표는 뒤에 공백이나 줄바꿈이 온다 |
| D-G | 비교 대상이 아닌 값(`EDU`, 기관명, `ongoing_yn`)의 변경 | 덮어쓰기만 하고 변경 기록은 남기지 않는다 | FR-C4 비교 필드 목록에 없다. PRD는 바꾸지 않는다 |
| D-H | swagger의 `item` 래퍼 | 둘 다 받고, #4가 실응답을 확인하면 하나를 지운다 | §3 |
| D-I | 텍스트 정규화 | 하지 않는다(원문 그대로 해시) | 실측에서 114개 필드 값이 끝에 공백이나 줄바꿈을 가진다. 원천이 공백만 고쳐도 변경 1건이 생긴다. 관측 기록일 뿐이고 알림과 무관하므로 운영에서 빈도를 보고 정한다 |

## 7. 작업 방식
- 기준 브랜치: #8(`feat/#2-app-foundation`, 현재 head `a5f687c`)이 **main에 머지된 뒤** main에서 `feat/#3-ingestion-core`를 만든다. 먼저 시작해야 하면 #8 head에서 분기하고 머지 후 rebase한다.
- 예상 파일: `ingestion/ListResponseParser.java`, `ingestion/PostingIngestor.java`(+ 작은 record와 실패 enum), 테스트 3개(`IntegrationTestSupport`, 파서 단위 테스트, 반영 통합 테스트), `FoundationIntegrationTest` 상속 변경, `fixtures/` 품질 표본과 README, `docs/design/erd.md`(D-A 매핑, D-C 전이, 오류 코드 목록).
- #4, #5와 병렬로 진행하면 Flyway 번호가 충돌할 수 있다. #3은 마이그레이션 없이 끝내는 것을 목표로 한다.

## 8. 확인하지 못한 것
- 공식 API(`apis.data.go.kr`)의 실제 응답 봉투와 원소 래퍼. 키가 필요하다(#4).
- swagger 오류 코드 0~11의 의미. 데이터셋 페이지의 설명이 원문부터 깨져 있다.
- D-0 공고가 마감 당일 응답에서 빠지는 시각(D-D). 평일 운영 관측이 필요하다.
- 3년치 오래된 데이터의 형식 품질. #4의 표본 검수 대상이다.
