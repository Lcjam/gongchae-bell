# #3 → #4 수집 반영 계약 · claude

> 2026-11-15 · 구현: claude · 검수: codex · 읽는 사람: #4 수집 실행기 담당(Sol → 2026-11-15부터 claude)
> 브랜치 `feat/#3-ingestion-core` (main `9402015` 기반). 근거: [준비안](../2026-11-14-resume/claude-ingestion-ready.md)과 codex 검수 지시.
> **이 계약을 바꿀 때는 이 파일을 먼저 고치고 #4 담당에게 알린다.**

## 변경 이력
| 판 | 날짜 | 바뀐 점 |
|---|---|---|
| v1 | 2026-11-15 `497db48` | 최초 |
| v2 | 2026-11-15 `67ed7dc` (codex 검수 03 반영) | ① `upsertPage` 반환을 `Counts` → `PageResult(Counts counts, Set<Long> sns)`로 바꾼다. ② `pref`를 U+001F 연결에서 **라벨과 줄바꿈으로 된 읽을 수 있는 텍스트**로 바꾸고, U+001F가 들어 있어도 거부하지 않는다. ③ 음수 `totalCount`는 `INVALID_ENVELOPE`. `applyRegular`의 시그니처와 동작은 그대로다 |
| **v3** | 2026-11-15 (codex `collab/2026-11-15-review/11-review-decisions.md` 1번, 원본 작업 경로에 있음) | ① 최초 적재를 **파싱과 반영으로 나눈다**: `ParsedPage parsePage(String body)`(DB 접근 없음) → 호출자가 페이지 크기·월 총수·페이지 간 중복·요청 기간을 검사 → `Counts upsertPage(ParsedPage page, Instant observedAt)`. 검사에서 거부한 페이지는 아무것도 바꾸지 않는다. 파싱은 여전히 한 번이다. ② `PageResult`를 없앤다. `sns`는 `ParsedPage.sns()`에서 반영 전에 얻는다. 기존 `upsertPage(String, Instant)`는 남기지 않는다(호환 래퍼 없음). ③ `body == null`(빈 HTTP 본문)은 `NON_JSON`. 전에는 `IllegalArgumentException`이 새어 나가 정기 회차가 마감되지 않았다(agy PR #9 리뷰 3-1). `applyRegular`의 시그니처는 그대로다 |

## 1. 공개 API (`kr.gongchae.bell.ingestion`)

```java
@Service
public class PostingIngestor {
    /** 정기 수집 1회분. 파싱 → 완결성 → 반영 → collect_run 마감까지 한다. */
    public RunResult applyRegular(long runId, String body, Instant observedAt);

    /** 최초 적재 한 페이지를 파싱·검증만 한다. DB에 접근하지 않는다. 실패하면 IngestionException. */
    public ParsedPage parsePage(String body);

    /** 호출자가 받아들인 페이지를 한 트랜잭션으로 반영한다. */
    public Counts upsertPage(ParsedPage page, Instant observedAt);
}

/** 파싱·검증을 마쳤지만 아직 반영하지 않은 페이지. 생성자는 패키지 전용이라 parsePage로만 얻는다. */
public final class ParsedPage {
    public int totalCount();                 // 그 페이지 응답의 totalCount(월 전체 건수)
    /** 이 페이지의 모든 recrutPblntSn. 이미 저장된 공고도 포함한다. 수정할 수 없는 정렬된 집합이고,
     *  페이지 안 중복은 DUPLICATE_SN으로 거부되므로 sns().size()가 이 페이지의 행 수다. */
    public SortedSet<Long> sns();
    /** 모든 행의 pbancBgngYmd가 [from, to] 안에 있으면 true. 빈 페이지는 true. */
    public boolean beginsWithin(LocalDate from, LocalDate to);
}

public record Counts(int totalCount, int received, int inserted, int changed, int closed) {}
public record RunResult(String result, Failure failure, Counts counts) {}   // result: COMPLETE | INCOMPLETE | FAILED
public class IngestionException extends RuntimeException { public Failure failure(); }

public enum Failure {          // collect_run.result   // collect_run.error에는 name()만 저장
    NON_JSON,                  // FAILED       JSON 아님, 잘린 JSON, 최상위가 객체 아님
    RESULT_CODE_NOT_OK,        // FAILED       resultCode 없음 또는 200 아님 (원래 코드 값은 저장하지 않는다)
    INVALID_ENVELOPE,          // FAILED       totalCount가 0 이상의 정수가 아님, result가 배열이 아님
    INVALID_ROW,               // INCOMPLETE   필수값 누락·형식 오류 행이 하나라도 있음
    DUPLICATE_SN,              // INCOMPLETE   한 응답 안에 같은 recrutPblntSn
    COUNT_MISMATCH,            // INCOMPLETE   (정기만) totalCount ≠ 받은 건수
    EMPTY_SNAPSHOT,            // INCOMPLETE   (정기만) 받은 건수 0
    APPLY_ERROR;               // FAILED       (정기만) 반영 중 예기치 않은 예외. 예외는 다시 던진다
    public String result();
}
```

## 2. 호출 조건 (호출자 = #4)
- `applyRegular`과 `upsertPage`는 **트랜잭션 밖에서** 부른다. 안에서 부르면 `IllegalStateException`. 두 메서드가 직접 트랜잭션을 연다. `parsePage`는 DB에 접근하지 않으므로 어디서 불러도 된다.
- `body`: `list` 응답 본문 문자열 그대로. `null`(빈 본문)이면 `NON_JSON`이다. `applyRegular`은 `ongoingYn=Y&numOfRows=1000&pageNo=1` 응답을 기대한다.
- `observedAt`: 호출자의 `clock.instant()`. 마감 판정의 "오늘"은 `observedAt`을 `Clock` 빈의 시간대(Asia/Seoul)로 바꾼 날짜다. 공고의 `first_seen_at`, `last_seen_at`, `last_changed_at`, `posting_change.observed_at`이 이 값이다.
- `runId`: #4가 선점(ADR-0003 INSERT)해서 얻은 `collect_run.id`. 그 행은 `result IS NULL`이어야 한다.

## 3. 결과 보장

### `applyRegular`
| 경우 | DB 결과 | 반환 / 예외 |
|---|---|---|
| 완전 수집 | 공고·기관·코드·코드명·변경 기록과 `collect_run`(`finished_at = now()`, `result = COMPLETE`, `total_count`, `received`, `inserted`, `changed`, `closed`)이 **한 트랜잭션**으로 커밋된다 | `RunResult("COMPLETE", null, counts)` |
| `Failure` 해당 | 공고 쪽 테이블 변화 0. `collect_run`만 별도 트랜잭션으로 마감한다(`result = failure.result()`, `error = failure.name()`, 파싱된 경우 `total_count`·`received`) | `RunResult(failure.result(), failure, counts 또는 null)` |
| 반영 중 예기치 않은 예외 | 공고 쪽 롤백. `collect_run`을 `FAILED`/`APPLY_ERROR`로 마감하려고 시도한다 | 원래 예외를 다시 던진다 |
| `runId` 행이 없거나 이미 마감됨 | 아무것도 바꾸지 않는다 | `IllegalStateException` |

마지막 완전 수집 시각(`max(finished_at) WHERE kind='REGULAR' AND result='COMPLETE'`)은 COMPLETE일 때만 바뀐다.

### `upsertPage` (최초 적재)
순서는 **`parsePage` → 호출자 검사 → `upsertPage`**다.
- `parsePage`: **행 하나라도 `Failure`에 걸리면 `IngestionException`을 던진다.** DB에 아무것도 쓰지 않는다. 호출자는 그 달을 `INCOMPLETE` 또는 `FAILED`로 기록하고, **건너뛰고 COMPLETE로 처리하면 안 된다**(codex 지시).
- 페이지 단위라서 `COUNT_MISMATCH`, `EMPTY_SNAPSHOT`은 코어가 검사하지 않는다. 호출자가 **반영 전에** `ParsedPage`로 검사한다(v3, codex 11 결정 1번).
  - `totalCount()`: 그 페이지 응답의 `totalCount`. 앞 페이지와 다르면 월 총수가 바뀐 것이다.
  - `sns()`: 그 페이지의 고유 공고 번호. 크기가 기대 페이지 크기(`min(1000, totalCount − (pageNo − 1) × 1000)`)와 같아야 하고, 앞 페이지들의 번호와 겹치면 페이지 간 중복이다. 여러 페이지 `sns()`의 합집합 크기가 월의 고유 수다.
  - `beginsWithin(from, to)`: 요청한 기간 밖에서 시작한 행이 있으면 false.
  - 하나라도 어긋나면 **`upsertPage`를 부르지 않는다.** 그래서 실패 페이지에 섞인 기존 공고의 변경도 커밋되지 않는다.
- `upsertPage`: 한 트랜잭션. 반환 `Counts`의 `inserted`는 **DB에 없던 행만** 센다. 이미 저장된 공고가 다시 오면 0이 되므로 고유 수 계산에 쓰면 안 된다.
- **같은 본문을 #4에서 다시 파싱하지 않는다.** `parsePage` 한 번, `upsertPage` 한 번이다.
- 빈 페이지(`totalCount = 0`, `result = []`)는 `sns()`가 비어 있고 `upsertPage`가 `Counts(0, 0, 0, 0, 0)`을 돌려준다.
- 새 행은 `from_backfill = true`. 이미 있는 행은 정기 수집과 같은 규칙으로 비교·갱신하고 `from_backfill`은 바꾸지 않는다.
- 응답에 없는 행의 누락 판정(CHECK)은 하지 않는다. `collect_run`은 건드리지 않는다.
- `resultCode`가 200이 아니면(공식 문서의 3번 등 의미 미확인 코드 포함) `RESULT_CODE_NOT_OK`다. 빈 달이 200 + 빈 배열로 오는지 3번으로 오는지는 #4가 실응답으로 확인한다.

### 하지 않는 것
HTTP 호출, `serviceKey`, 재시도, `api_budget`, 회차 선점, 스케줄러, Healthchecks 핑, 상세 수집(`steps`·`files`·`detail_*` 컬럼은 읽지도 쓰지도 않는다), 새 공고 알림과 M2 outbox.

## 4. 받는 응답 형식
- 공식 봉투: `{"resultCode": 200, "resultMsg": "...", "totalCount": n, "result": [ ... ]}`. `resultCode`는 정수 또는 숫자 문자열. `totalCount`는 0 이상의 정수(JSON 정수)여야 한다.
- 배열 원소는 평평한 객체 또는 공식 swagger처럼 `{"item": { ... }}`. 둘 다 받는다. #4가 실응답을 확인하면 쓰지 않는 쪽을 지우도록 알려 준다.
- 공개 경로의 `{"data": { ... }}` 래퍼는 받지 않는다(PRD §8, 대체 경로를 섞지 않음). 테스트 헬퍼만 벗긴다.

## 5. 행 검증 (하나라도 어기면 `INVALID_ROW`)
| 필드 | 조건 |
|---|---|
| `recrutPblntSn` | 정수 또는 숫자만 있는 문자열 |
| `pblntInstCd` | 비어 있지 않음, 40자 이하 |
| `instNm`, `recrutPbancTtl` | 비어 있지 않음 |
| `pbancBgngYmd`, `pbancEndYmd` | `yyyyMMdd`로 엄격 파싱 |
| `ongoingYn` | 없음, `Y`, `N` 중 하나 |
| `recrutSe` | 20자 이하 |
| 코드 목록의 각 코드 | 40자 이하 |
| 모든 텍스트 필드 | 값이 객체나 배열이 아니어야 한다(문자열, 숫자, null만) |

텍스트 내용(제어 문자 포함)은 검사하지 않는다. v1에 있던 "`prefCondCn`·`prefCn`에 U+001F 금지"는 v2에서 없앴다.

## 6. 저장 규칙 요약
- **우대(`pref`)**: 두 원문을 라벨이 붙은 단락으로 이어 한 컬럼에 둔다. 화면은 따로 나눌 필요 없이 줄바꿈을 살려(`white-space: pre-line` 등) 그대로 보여 주면 된다.
  ```text
  [우대조건]
  <prefCondCn 원문>

  [우대내용]
  <prefCn 원문>
  ```
  - null인 쪽은 그 단락(라벨 포함)을 빼고, 둘 다 null이면 `pref`도 null이다. 빈 문자열은 라벨만 있는 단락이 된다.
  - 원문은 바꾸지 않는다(줄바꿈 `\r\n`, 앞뒤 공백, 제어 문자 그대로). 단락 사이 구분은 `\n\n`이다.
  - 라벨이 늘 붙으므로 어느 쪽 원문이 바뀌거나 생기거나 사라져도 `pref` 값이 달라지고, 해시와 `pref` 변경 기록에 잡힌다. 원문 안에 `\n\n[우대내용]\n` 같은 글이 그대로 들어 있는 경우까지 두 원문으로 되돌릴 수 있다고 보장하지는 않는다(표시용 형식이다).
- **변경 관측**: PRD FR-C4의 12개 필드(코드 목록은 정렬)를 해시한다. 다르면 `posting_change` 1행 `{"필드": {"old", "new"}}`. `EDU` 코드, `ongoing_yn`, 기관명, 품질 플래그는 덮어쓰기만 한다.
- **상태**: 응답에 있는 행은 `end_date < 오늘` 또는 `ongoingYn = N`이면 CLOSED, 아니면 OPEN이다(마감 연장으로 다시 나타난 CLOSED도 OPEN이 된다). 정기 수집이 COMPLETE일 때만, 응답에 없는 OPEN·CHECK 행을 `end_date < 오늘`이면 CLOSED, 아니면 CHECK로 바꾼다. `closed`는 이번에 CLOSED로 **바뀐** 기존 행 수다(새로 들어온 행은 세지 않는다).
- **`src_url`**: 원문 문자열 그대로 저장한다(정규화 없음). `src_url_generic`은 http(s)로 파싱되는 URL 중 (a) 경로가 비거나 `/`이고 쿼리가 없거나, (b) **같은 응답에서 같은 기관(`pblntInstCd`)의 다른 공고가 문자열까지 같은 URL을 쓸 때** true다. 한 번 true면 URL이 바뀌기 전까지 유지한다.
- **`broken_text`**: 제목, 기관명, 자격, 우대, 전형 설명에 U+FFFD가 있거나 `?` 바로 뒤에 한글 음절이 오면 true다. **깨짐이 의심된다는 표시일 뿐 확정이 아니고, 원문을 고치거나 복구하지 않는다.** 실측에서 `「」`가 `?`로 바뀐 형태가 이 규칙에 걸렸다. 정상 문장의 `?` 뒤에 공백 없이 한글이 오면 오탐이 생길 수 있다.
- **동시성**: 반영 트랜잭션은 기존 행을 `sn` 순서로 `FOR UPDATE` 잠그고(정기 수집은 OPEN·CHECK 행도 함께), 새 행은 `sn` 순서로 `INSERT … ON CONFLICT DO NOTHING`한다. 다른 트랜잭션이 같은 새 `sn`을 먼저 넣었으면 그 행을 다시 잠가 기존 행으로 처리한다. `applyRegular`과 `upsertPage`, 또는 `upsertPage` 둘이 겹치는 `sn`으로 동시에 돌아도 된다(테스트로 확인).
