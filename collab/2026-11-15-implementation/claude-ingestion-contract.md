# #3 → #4 수집 반영 계약 · claude

> 2026-11-15 · 구현: claude · 검수: codex · 읽는 사람: Sol(#4 수집 클라이언트·선점·예산·최초 적재)
> 브랜치 `feat/#3-ingestion-core` (main `9402015` 기반). 근거: [준비안](../2026-11-14-resume/claude-ingestion-ready.md)과 codex 검수 지시.
> **이 계약을 바꿀 때는 이 파일을 먼저 고치고 #4 담당에게 알린다.**

## 1. 공개 API (`kr.gongchae.bell.ingestion`)

```java
@Service
public class PostingIngestor {
    /** 정기 수집 1회분. 파싱 → 완결성 → 반영 → collect_run 마감까지 한다. */
    public RunResult applyRegular(long runId, String body, Instant observedAt);

    /** 최초 적재 한 페이지. 반영만 한다. 실패하면 IngestionException, 아무것도 반영하지 않는다. */
    public Counts upsertPage(String body, Instant observedAt);
}

public record Counts(int totalCount, int received, int inserted, int changed, int closed) {}
public record RunResult(String result, Failure failure, Counts counts) {}   // result: COMPLETE | INCOMPLETE | FAILED
public class IngestionException extends RuntimeException { public Failure failure(); }

public enum Failure {          // collect_run.result   // collect_run.error에는 name()만 저장
    NON_JSON,                  // FAILED       JSON 아님, 잘린 JSON, 최상위가 객체 아님
    RESULT_CODE_NOT_OK,        // FAILED       resultCode 없음 또는 200 아님 (원래 코드 값은 저장하지 않는다)
    INVALID_ENVELOPE,          // FAILED       totalCount가 정수가 아님, result가 배열이 아님
    INVALID_ROW,               // INCOMPLETE   필수값 누락·형식 오류 행이 하나라도 있음
    DUPLICATE_SN,              // INCOMPLETE   한 응답 안에 같은 recrutPblntSn
    COUNT_MISMATCH,            // INCOMPLETE   (정기만) totalCount ≠ 받은 건수
    EMPTY_SNAPSHOT,            // INCOMPLETE   (정기만) 받은 건수 0
    APPLY_ERROR;               // FAILED       (정기만) 반영 중 예기치 않은 예외. 예외는 다시 던진다
    public String result();
}
```

## 2. 호출 조건 (호출자 = #4)
- **트랜잭션 밖에서** 부른다. 안에서 부르면 `IllegalStateException`. 두 메서드가 직접 트랜잭션을 연다.
- `body`: `list` 응답 본문 문자열 그대로. `applyRegular`은 `ongoingYn=Y&numOfRows=1000&pageNo=1` 응답을 기대한다.
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
- 한 트랜잭션. **행 하나라도 `Failure`에 걸리면 페이지 전체를 반영하지 않고 `IngestionException`을 던진다.** 호출자는 그 달을 `INCOMPLETE` 또는 `FAILED`로 기록하고, **건너뛰고 COMPLETE로 처리하면 안 된다**(codex 지시).
- 페이지 단위라서 `COUNT_MISMATCH`, `EMPTY_SNAPSHOT`은 검사하지 않는다. 대신 `Counts.totalCount`, `Counts.received`를 돌려주니 호출자가 월 단위 합(받은 건수 합 = `totalCount`, 고유 sn 수)을 검사한다. 빈 페이지(`totalCount = 0`, `result = []`)는 0건 반영으로 정상 반환한다.
- 새 행은 `from_backfill = true`. 이미 있는 행은 정기 수집과 같은 규칙으로 비교·갱신하고 `from_backfill`은 바꾸지 않는다.
- 응답에 없는 행의 누락 판정(CHECK)은 하지 않는다. `collect_run`은 건드리지 않는다.
- `resultCode`가 200이 아니면(공식 문서의 3번 등 의미 미확인 코드 포함) `RESULT_CODE_NOT_OK`다. 빈 달이 200 + 빈 배열로 오는지 3번으로 오는지는 #4가 실응답으로 확인한다.

### 하지 않는 것
HTTP 호출, `serviceKey`, 재시도, `api_budget`, 회차 선점, 스케줄러, Healthchecks 핑, 상세 수집(`steps`·`files`·`detail_*` 컬럼은 읽지도 쓰지도 않는다), 새 공고 알림과 M2 outbox.

## 4. 받는 응답 형식
- 공식 봉투: `{"resultCode": 200, "resultMsg": "...", "totalCount": n, "result": [ ... ]}`. `resultCode`는 정수 또는 숫자 문자열.
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
| `prefCondCn`, `prefCn` | U+001F 문자가 없어야 한다(§6의 구분자) |

## 6. 저장 규칙 요약
- **우대(`pref`)**: `prefCondCn`과 `prefCn` 원문을 **U+001F(Unit Separator)로 이어** 한 컬럼에 둔다. 형식은 `<prefCondCn>U+001F<prefCn>`이고 null은 빈 문자열로 쓴다. 둘 다 null이면 `pref`도 null이다. 읽는 쪽(#5)은 `pref.split("\u001F", -1)`로 [요약, 상세]를 얻는다. null과 빈 문자열은 구분하지 않는다(실측 497건에서 빈 문자열 0건). 어느 쪽이 바뀌어도 해시와 `pref` 변경 기록에 잡힌다.
- **변경 관측**: PRD FR-C4의 12개 필드(코드 목록은 정렬)를 해시한다. 다르면 `posting_change` 1행 `{"필드": {"old", "new"}}`. `EDU` 코드, `ongoing_yn`, 기관명, 품질 플래그는 덮어쓰기만 한다.
- **상태**: 응답에 있는 행은 `end_date < 오늘` 또는 `ongoingYn = N`이면 CLOSED, 아니면 OPEN이다(마감 연장으로 다시 나타난 CLOSED도 OPEN이 된다). 정기 수집이 COMPLETE일 때만, 응답에 없는 OPEN·CHECK 행을 `end_date < 오늘`이면 CLOSED, 아니면 CHECK로 바꾼다. `closed`는 이번에 CLOSED로 **바뀐** 기존 행 수다(새로 들어온 행은 세지 않는다).
- **`src_url`**: 원문 문자열 그대로 저장한다(정규화 없음). `src_url_generic`은 http(s)로 파싱되는 URL 중 (a) 경로가 비거나 `/`이고 쿼리가 없거나, (b) **같은 응답에서 같은 기관(`pblntInstCd`)의 다른 공고가 문자열까지 같은 URL을 쓸 때** true다. 한 번 true면 URL이 바뀌기 전까지 유지한다.
- **`broken_text`**: 제목, 기관명, 자격, 우대, 전형 설명에 U+FFFD가 있거나 `?` 바로 뒤에 한글 음절이 오면 true다. **깨짐이 의심된다는 표시일 뿐 확정이 아니고, 원문을 고치거나 복구하지 않는다.** 실측에서 `「」`가 `?`로 바뀐 형태가 이 규칙에 걸렸다. 정상 문장의 `?` 뒤에 공백 없이 한글이 오면 오탐이 생길 수 있다.
- **동시성**: 반영 트랜잭션은 기존 행을 `sn` 순서로 `FOR UPDATE` 잠그고(정기 수집은 OPEN·CHECK 행도 함께), 새 행은 `sn` 순서로 `INSERT … ON CONFLICT DO NOTHING`한다. 다른 트랜잭션이 같은 새 `sn`을 먼저 넣었으면 그 행을 다시 잠가 기존 행으로 처리한다. `applyRegular`과 `upsertPage`, 또는 `upsertPage` 둘이 겹치는 `sn`으로 동시에 돌아도 된다(테스트로 확인).
