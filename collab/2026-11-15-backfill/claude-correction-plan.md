# 최초 적재(FR-C1) 누락 수정안 · claude (독립 제안)

> 2026-11-15 · 조율: codex · 기준 코드: `feat/#4-collector-runtime` `c76edad`(PR #11 head, 코드 기준 `dc8d016`)
> 읽은 것: 이 폴더의 [`agy-result.md`](agy-result.md)(실측), 현재 코드, 공식 명세 페이지 1개, 저장소의 공식 응답 fixture. 다른 검증 결과 파일은 읽지 않았다.
> 하지 않은 것: 실제 API 호출 0회, 앱 코드·설정·마이그레이션·env·실DB 변경 없음, agy 작업과 Sol 브랜치에 손대지 않음.

## 1. 결함

| 근거 | 내용 |
|---|---|
| 실측 ([agy-result.md](agy-result.md) §3·§5) | 37개 월 요청(`pbancBgngYmd`=월초, `pbancEndYmd`=월말)으로 22,733건을 받았다. 이 중 시작월과 마감월이 다른 공고는 0건이다. 모든 월에서 `max(end_date) <= range_to`였다. 2026-11-01~15 요청은 73건이었고 진행 중 공고는 1건이었다 |
| 비교 (저장소 fixture, 로컬 계산) | [`fixtures/alio-official-list-sample.json`](https://github.com/Lcjam/gongchae-bell/blob/c76edad132cec9e4986eccaea1e127fdff487de2/fixtures/alio-official-list-sample.json)(#4 브랜치, 공식 `ongoingYn=Y`, 497건, 2026-11-15 14:41 KST 취득). SN 내림차순으로 정렬돼 있다: 시작월≠마감월인 공고 150건(30%), 시작일 최댓값 2027-01-18(오늘 이후 시작 공고 존재), 마감일 최댓값 2050-12-31, 2027년 이후 마감 19건 |
| 공식 명세 ([data.go.kr/data/15125273/openapi.do](https://www.data.go.kr/data/15125273/openapi.do), 2026-11-15 열람) | `pbancBgngYmd` 옵션, "채용공시 시작일 기준 조회시작일 [YYYY-MM-DD]". `pbancEndYmd` 옵션, "채용공시 시작일 기준 조회종료일 [YYYY-MM-DD]". 정렬 순서, `numOfRows` 상한, 경계 포함 여부는 적혀 있지 않다. 페이지 변환본만 읽었고 swagger 원문은 보지 못했다 |
| 현재 코드 | `RecruitmentClient.backfillList`(`RecruitmentClient.java:43`)가 매달 `pbancEndYmd=월말`을 보낸다. `BackfillCollector`(`:32`)가 `BackfillRange.months()`로 나눈 달마다 이 요청을 쓴다 |

명세 문구는 두 날짜를 모두 "시작일 기준"이라고 적는다. 실측은 `pbancEndYmd`가 마감일 상한으로 동작할 때의 결과와 같다. 메커니즘은 확정하지 않는다. 아래 수정은 `pbancEndYmd`를 보내지 않으므로 그 해석에 기대지 않는다.

**결과**: 실측 37개월은 "시작일과 마감일이 모두 그 달 안"인 공고만 담았다. 결과의 `COMPLETE`는 응답 봉투 기준으로만 맞다. FR-C1의 "공시 시작일 기준 최근 3년 전체"를 증명하지 못한다. 위 30% 비율로 보면 PRD 추정(약 3.5만 건)과의 차이도 설명된다. 이 비율은 진행 중 공고 표본의 값이라 과거 전체에 그대로 적용되는지는 추정이다.

## 2. 수정안: 시작일 하한 하나로 전 기간을 한 번 훑는다

**조회 계약**: `list?pbancBgngYmd=<from>&numOfRows=1000&pageNo=n`. `pbancEndYmd`는 보내지 않는다(명세상 옵션). `pageNo` 1부터 `totalCount`를 다 받을 때까지 읽는다. 월 분할은 없앤다. 상한을 서버에 맡길 방법이 없으므로 상한은 로컬에서 거른다.

**페이지마다 반영 전에 하는 검사**(#3 계약 v3 그대로: `parsePage` → 검사 → `upsertPage`):
1. 원본(raw) 완결성: 기존 `validate`를 그대로 쓴다. 페이지 사이 `totalCount` 불변, 페이지 크기 `min(1000, T-(n-1)·1000)`, 앞 페이지와 SN 중복 없음. 끝에서 고유 SN 수가 `T`와 같아야 한다.
2. 하한: 모든 행의 시작일이 `from` 이상이어야 한다. 아니면 `BACKFILL_RANGE_MISMATCH`로 페이지 전체를 거부한다. 기존 `ParsedPage.beginsWithin(from, LocalDate.MAX)`로 된다. 서버의 하한 동작을 매 실행 확인하는 장치다.
3. 상한(로컬 필터): 시작일이 `to` 이후인 행은 반영하지 않는다. 예: 2027-01-18 시작 공고. 원본 집계에는 남고 반영 집합에서만 빠진다. 이 행은 이후 정기 수집이 `from_backfill=false`로 넣는다.

**두 집합을 분리한다**:

| 집합 | 정의 | 쓰임 |
|---|---|---|
| 원본 수신 | 받은 모든 행의 SN. `total_count=T`, `received`, `unique_count`, `received_sns` | 완결성 검사와 재개 시 중복 검사. 기존 열의 의미("받은 것")를 그대로 둔다 |
| 범위 내 반영 | 시작일이 `[from, to]` 안이라 `upsertPage`에 넘긴 행의 SN | FR-C1의 "고유 공고 수". 새 열 `applied_count`, `applied_sns` |

명령 행과 scan 행은 두 값을 모두 남긴다. 원본 수 − 반영 수 = 상한 밖으로 거른 행 수다(하한 밖은 실패하므로 0).

## 3. 전략 버전·재개·기존 37개월 보존

**V4 (이 수정에 예약, 추가 열만, V1~V3 무변경)**
```sql
ALTER TABLE collect_run
    ADD COLUMN strategy varchar(20) CHECK (strategy IN ('START_SCAN')),
    ADD COLUMN applied_count integer CHECK (applied_count >= 0),
    ADD COLUMN applied_sns jsonb CHECK (jsonb_typeof(applied_sns) = 'array');
```
- 기존 행은 `strategy IS NULL`(월 분할 방식)로 남는다. 이전 버전 앱은 새 열을 모르고도 그대로 동작한다(무중단 배포 조건). M2 마이그레이션은 V5로 민다.
- **잘못된 skip 방지**: `CollectionRuns.completedPage`·`completedMonth`(`CollectionRuns.java:47-63`)는 지금 `kind='BACKFILL'`과 기간만으로 찾는다. 이를 `strategy='START_SCAN'`으로 좁힌다. 그러면 실DB의 월 37행·페이지 37행(`initial-001`)은 새 방법의 재개 근거로 절대 쓰이지 않는다. 기간이 우연히 한 달과 같아도 마찬가지다(예: 2026-11-01~15). 이 충돌 사례를 테스트로 고정한다.
- **행 구조**: 명령 `command/<slot>` → scan `<slot>/scan` → 페이지 `<slot>/scan/<n>`. 기존 "명령 → 월 → 페이지"에서 월 자리를 scan 하나로 바꾸는 것이라 `BackfillCollector`의 흐름(잠금, 회차, 체크포인트, 실패 집계)은 그대로다. 슬롯 정규식에 `/`가 없어서 기존 `<slot>/<날짜>` 슬롯과 겹치지 않는다.
- **재개**: 기존 규칙을 그대로 쓴다. 같은 기간의 `START_SCAN` 완료 페이지는 `T`가 같을 때만 이어 붙인다. `T`가 바뀌었으면 `BACKFILL_COUNT_CHANGED`로 멈추고, 새 슬롯과 `--collector.backfill-restart-pages=true`로 처음부터 읽는다. 완료된 scan 행이 있으면 HTTP 없이 그 행의 두 집합을 재사용한다(FR-C1 "두 번 적재해도 고유 공고 수가 같다").
- **데이터 보존**: 실DB의 공고 22,733건과 기존 `collect_run` 행(명령 2, 월 37, 페이지 37)은 지우거나 고치지 않는다. 새 scan은 기존 공고를 같은 반영 경로로 다시 upsert한다. 내용이 같으면 변경 기록이 0건이고 상세 열도 건드리지 않는다(#3 기존 보장). 빠졌던 공고만 새로 들어간다. `from_backfill`은 새로 넣는 행만 `true`이고 기존 행 값은 유지된다.
- **완료 판정**: 운영 문서의 "명령 행이 `COMPLETE`인지 확인"을 "`strategy='START_SCAN'`인 명령 행"으로 바꾼다. `initial-001`·`initial-002`는 결함 있는 방식의 기록으로 남기고 FR-C1 근거로 쓰지 않는다.

## 4. 변경 범위 (구현 배정 뒤)

| 파일 | 변경 |
|---|---|
| `RecruitmentClient.backfillList` | `(from, page)`로 바꾸고 `pbancEndYmd`를 뺀다 |
| `ParsedPage` | `startingOnOrBefore(to)` 추가: 같은 `totalCount`에 상한 안 행만 담은 `ParsedPage`. #3 공개 API에 메서드 1개 추가 |
| `BackfillCollector` | 월 루프 → scan 하나. 하한 검사, 상한 필터 뒤 `upsertPage`, 두 집합 집계 |
| `CollectionRuns` | 조회에 `strategy` 조건 추가, `strategy`·`applied_*` 기록, `Month` → scan 기록 |
| `BackfillRange.months()` | 삭제(쓰는 곳이 없어짐) |
| `V4__backfill_start_scan.sql` | 위 3열 |
| 문서 | `routes.md` 최초 적재 행(월 단위 → 시작일 하한 scan, 명세와 실측 불일치 기록), `erd.md` V4, `docs/ops/collector.md` 절차·완료 판정·기존 행 의미, `implementation-status.md` FR-C1 |

설정 키, 예산 구조, 다른 수집기는 바꾸지 않는다.

**호출량**: `ceil(T/1000)`회. 이때 `T`는 `from` 이후 시작한 공고 전체에 오늘 이후 시작 공고까지 더한 수다. PRD 추정 3.5만 건이면 약 36회로 기존 37회와 비슷하다. 실제 `T`는 agy 검증값으로 확정한다. KST 일 상한은 900이고 정기 몫 72를 빼면 828이다. 오늘 41회를 이미 썼다.

## 5. 회귀 테스트 (Testcontainers + 루프백 대역, 실제 API 0회)

대역 서버는 실측 동작을 흉내 낸다. `pbancEndYmd`가 오면 마감일 상한으로 거르고, 없으면 시작일 하한만 적용한다. 요청에 `pbancEndYmd`가 있으면 `UNEXPECTED_REQUEST`로 기록해 테스트를 실패시킨다.

1. **결함 재현 red→green**: 1월 25일 시작·2월 5일 마감 공고가 있을 때, 이전 코드에서는 빠지고 새 코드에서는 반영된다. 반영 집합, `applied_count`, 공고 행으로 확인한다.
2. 상한 필터: `to` 이후 시작 행은 원본 `T`·`unique_count`에 들어가고 반영 집합과 `posting`에는 없다. 완결성은 통과한다.
3. 하한 위반: `from` 이전 시작 행이 한 줄이라도 있으면 `BACKFILL_RANGE_MISMATCH`이고 공고 변경이 0이다(기존 `rejectedPages…` 형식).
4. **기존 행 skip 금지**: V3 방식 `COMPLETE` 월·페이지 행(`strategy NULL`)을 미리 넣는다. 기간이 그 달과 정확히 같은 경우도 넣는다. 새 실행은 HTTP를 하고 반영한다.
5. 재개: 2페이지 실패 → 새 슬롯이 1페이지 체크포인트를 재사용한다(`T` 같음). `T`가 바뀌면 `COUNT_CHANGED` → `restart-pages`로 끝난다. 완료 scan 재실행은 HTTP 0회, 두 집합 동일.
6. 보존: 정기 수집으로 들어온 공고(`from_backfill=false`, 상세 `DONE`)를 scan이 다시 받아도 `from_backfill`과 상세 열이 그대로다.
7. 실패 집계: 명령·scan·페이지 행에 원본과 반영 합계가 남는다(기존 2차 수정 규칙과 같은 방식).
8. 공식 fixture: agy가 `pbancEndYmd` 생략 응답 본문을 fixture로 남기면, 그 본문으로 하한 검사 통과와 상한 필터 결과를 확인한다(DoD "실제 응답 fixture"). 전 페이지는 대역으로 만든다.

기존 테스트 중 월 분할을 전제한 것만 고친다: `BackfillRuntimeTest` 11건의 월 → scan, `RecruitmentClientTest.backfillUsesMonthBoundaries…`, `BackfillCommandIntegrationTest`, `BackfillRangeTest`의 월 분할 1건, `RuntimeDatabaseTest.completedPageCheckpoint…`, `PostingIngestorIntegrationTest`의 `beginsWithin` 사례. **Sol의 후속 통합 테스트**(`fc752ad7`의 `OfficialFixtureIntegrationTest` 3번)도 월 요청과 `pbancEndYmd`를 요구하므로 후속 PR에서 같이 맞춰야 한다.

## 6. 채택 전 확인 (agy 검증 결과로 판단, 본인 호출 없음)

| # | 확인할 것 | 아니면 |
|---|---|---|
| G1 | `pbancEndYmd`를 빼고 `pbancBgngYmd=2023-11-15`만 보냈을 때 `resultCode=200`이고, 응답에 오늘 이후 마감 공고와 시작월≠마감월 공고가 있다. 서버가 숨은 상한을 두지 않는다는 증거다 | 이 안을 쓰지 않는다. 먼 미래 상한 같은 대안은 별도 실측과 결정 전에는 쓰지 않는다 |
| G2 | 그 요청의 `totalCount`와 1페이지 행 수(1000) | 호출량과 실행 시간을 다시 잡는다 |
| G3 | 가능하면 마지막 페이지(`pageNo=ceil(T/1000)`) 1회: 행 수 = `T-(n-1)·1000` | 깊은 페이지가 막히면 이 안을 쓰지 않는다 |
| G4 | 1페이지 행 순서(SN 내림차순 여부)를 기록만 한다 | 아래 R1 판단 자료 |

## 7. 남는 위험 (막지 않고 기록)

- **R1. scan 중 데이터 변화**: 한 번 훑는 데 페이지당 약 4초 × 약 36페이지, 약 2~3분이 걸린다(월 처리 시간 실측에서 나온 추정). 새 공고가 들어와 `T`가 바뀌면 `COUNT_CHANGED`로 멈춘다. 행이 밀려 중복이 생기면 `DUPLICATE_SN`으로 멈춘다. 감지되지 않는 경우는 하나다. 같은 몇 분 안에 이미 읽은 구간에서 한 행이 빠지고 아직 읽지 않은 구간에 한 행이 들어와 `T`가 그대로이면 1건이 빠질 수 있다. 줄이려면 완료 뒤 새 슬롯으로 한 번 더 훑어 원본 SN 집합을 비교한다(약 36회 추가, 선택).
- **R2. 실패 확률**: `T`에 매일 새로 시작하는 공고가 포함되므로, 근무 시간에는 실행 중 `T`가 바뀔 수 있다. KST 야간 실행을 권한다. 실패 한 번에 최대 약 36회를 쓰고 이미 반영한 행은 그대로 둔다(멱등 upsert).
- **R3. 기간 밖 진행 공고**: 3년보다 먼저 시작해 아직 진행 중인 공고(fixture 최소 시작일 2020-07-21)는 FR-C1 범위 밖이다. 정기 수집이 `from_backfill=false`로 넣고 상세 대상이 된다. 기존 설계 그대로다.

## 8. root가 정할 것

1. G1~G3 결과로 이 안의 채택 여부.
2. 재실행 기간: 기존과 같은 `2023-11-15`~`2026-11-15`(실측과 비교 가능) 또는 실행일 기준 기본값.
3. R1의 2회 scan 대조를 완료 조건에 넣을지 여부.
4. V4 번호를 이 수정에 확정하고 M2를 V5로 미루는지 여부.
