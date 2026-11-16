# 수집 실행과 점검

수집기는 공식 `https://apis.data.go.kr/1051000/recruitment/`만 호출한다. 공개 경로 자동 대체와 공개 수집 운영 API는 없다.

## 운영 순서와 남은 게이트

1. **최초 적재를 먼저 끝낸다.** `COLLECTOR_ENABLED=false`인 상태에서 아래 "최초 적재" 명령으로 최근 3년을 적재하고, `strategy='START_SCAN'`인 명령 행이 `COMPLETE`인지 확인한다. V4 이전 월별 방식으로 끝난 명령(2026-11-15 `initial-001`·`initial-002`, 22,733건)은 시작월과 마감월이 같은 공고만 받았으므로 완료 근거가 아니다. 그 행과 공고는 지우지 않는다. 같은 개발 DB에서 `initial-003`(START_SCAN)이 2026-11-15 21:20~21:25 KST에 완료됐고, `initial-004` 재실행은 HTTP 0회였다([실행 기록](../../collab/2026-11-15-backfill/agy-start-scan-result.md), [root DB 검수](../../collab/2026-11-15-backfill/codex-start-scan-review.md)).
2. 그다음에 `COLLECTOR_ENABLED=true`로 정기 수집을 켠다. 순서를 바꾸면 그때 진행 중인 공고가 모두 `from_backfill=false`가 되어 상세 대상이 된다(2026-11-14 22:22 KST 공개 경로 1회 수집 기준 진행 공고 497건). 상세는 sweep당 30건 상한과 정기 몫 덕분에 예산은 지키지만, 그만큼 상세 수집이 밀린다(497건이면 시간당 30건씩 약 17시간).

공식 응답은 2026-11-15 14:41~14:46 KST에 agy가 1회씩 받아 보관했다([검증 기록](../../collab/2026-11-15-api-verification/agy-result.md), `fixtures/alio-official-*.json`, 영속 예산 4회: 실패 1·성공 3, Sol 독립 확인). 이 표본으로 확인한 것:
- 목록 봉투는 최상위 `{resultCode, resultMsg, totalCount, result[]}`이고 원소에 `item` 래퍼가 없다(진행 공고 497건).
- 상세는 `result` 단일 객체이고 `steps`·`files` 원소에 `step`·`file` 래퍼가 없다(sn 307032, steps 4·files 3).
- 빈 기간(2010-01-01~2010-01-31)은 `200` + `totalCount 0` + 빈 배열이다. `resultCode` 3이 아니므로 결과가 0건이어도 완료된다.
- `pbancEndYmd`는 마감일 상한처럼 동작한다. 그래서 최초 적재는 시작일 하한만 보낸다([routes.md](../design/routes.md) 주, 2026-11-15 20:11 KST 추가 검증 2회).
- 표본 하나씩이다. 다른 공고의 단계 의미·순서(`sortNo`가 0부터, 같은 번호가 여러 행)는 일반화하지 않는다.

다음은 아직 닫지 않은 **별도 게이트**다. 정기 수집을 운영에서 켜기 전에 각각 기록한다.
- ~~3년 최초 적재 실측(건수, 페이지 수, 오래된 코드·품질 표본)~~: 로컬 개발 DB에서 닫았다(위 기록). 운영 서버 DB에 적재할 때 다시 기록한다.
- 일 예산 500 경고와 연속 미완료 경보가 운영 디스코드로 실제 수신되는지(M3).

## 설정과 정기 수집

`COLLECTOR_ENABLED` 기본값은 false다. 공식 개발계정 환경이 준비된 앱에서만 true로 설정한다. `COLLECTOR_SERVICE_KEY`에는 포털의 일반 인증키를 **Decoding·Encoding 어느 표기로 넣어도 된다.** 설정을 읽을 때 한 번만 정규화한다: 값에 `%`가 있으면 모든 `%XX`가 유효한 16진수인지 확인하고 정확히 한 번 percent-decode한다(평문 `+`는 공백으로 바꾸지 않는다). `%`가 없으면 그대로 쓴다. 잘못된 `%` 표기(`%2`, `%G1`, UTF-8이 아닌 바이트)는 `INVALID_SERVICE_KEY`로 기동을 멈추고, 오류에 키를 넣지 않는다. 그다음 클라이언트가 한 번 URL 인코딩한다. 2026-11-15 공식 검증의 첫 시도는 Encoding 표기를 그대로 다시 인코딩해 `HTTP_STATUS`로 실패했고, 한 번 디코딩한 값으로는 성공했다(원인이 이중 인코딩이라는 것은 상태 번호가 없어 추정이다). 키를 명령 인자·커밋·명령 출력·로그에 넣지 않는다. 실제 API의 Java HTTP wire/debug 로깅도 활성화하지 않는다.

정기 수집은 KST 매시 7분, `ongoingYn=Y&numOfRows=1000&pageNo=1` 한 요청이다. 완결성 검증과 공고 반영/회차 마감은 #3 코어가 담당한다. 실패 응답은 추가 페이지나 공개 경로로 바꾸지 않는다. 이전 마지막 완전 수집 시각이 유지된다. 같은 시간 슬롯의 앱은 하나만 HTTP를 호출한다. 완전 수집 뒤 신규 진행 공고의 상세를 수집한다. 최초 적재로 처음 발견한 공고는 상세 대상에서 제외한다. 성공한 상세는 다시 요청하지 않는다. 상세는 정기 수집을 굶기지 않도록 다음 상한을 함께 쓴다(초기값, [ADR-0014](../adr/0014-initial-settings.md)).

| 상한 | 값 | 동작 |
|---|---|---|
| 공고당 실패 | 3회 | 같은 공고의 상세 회차가 `FAILED`로 3번 끝나면 더 요청하지 않는다. 예산 소진(`BUDGET_EXHAUSTED`)과 종료(`INTERRUPTED`)는 공고 탓이 아니라 세지 않는다. 공고의 `detail_status`는 `FAILED`로 남고 `collector_detail_gave_up` 지표에 나온다 |
| sweep당 건수 | 30건 | 남은 공고는 다음 정기 슬롯에서 이어 간다 |
| sweep당 시간 | 10분 | **새 상세 요청의 시작 제한**이다. 매 공고 요청 전에 확인하고, 이미 시작한 요청은 끝까지 간다. 그 한 건은 HTTP 상한만으로 최대 약 150초가 더 걸린다(타임아웃 10초 × 3 + `Retry-After` 대기 60초 × 2). 그래서 sweep은 "10분 + 진행 중 한 건의 종료 시간"이다 |
| 정기 몫 | 하루 72회 | 아래 "호출 예산" |

상세 sweep은 정기 수집과 같은 스케줄 스레드에서 이어서 돈다. 위 상한으로 한 시간 슬롯을 넘기지 않는다. 스레드 풀 크기는 바꾸지 않았다. 앱 종료 때 스케줄러는 진행 중인 회차를 **30초까지** 기다린다(`spring.task.scheduling.shutdown`, ADR-0014). 그 안에 끝나지 않은 회차는 `result IS NULL`로 남고 `collector_runs_unfinished`에 나온다.

HTTP 연결 및 본문 완료까지의 요청 타임아웃은 10초, 최초 포함 최대 3회, 일반 재시도 대기는 2초/4초다. 429의 `Retry-After`는 **서버가 지시한 시간보다 일찍 재요청하지 않는다**. 지시가 60초 이하(초 또는 HTTP 날짜, 지난 날짜는 0초)면 그만큼 기다린 뒤 재시도한다. 60초를 넘거나 읽을 수 없는 값(음수, `+5`, 너무 큰 수, 빈 값, 날짜 형식 오류)이면 기다리지 않고 `HTTP_RATE_LIMIT`로 그 회차를 끝내고 다음 회차에 맡긴다. 헤더가 없으면 일반 대기(2초/4초)를 쓴다. 5xx·연결 오류·타임아웃만 일반 재시도한다. 3xx는 따라가지 않고 4xx는 429 외에는 재시도하지 않는다. 본문이 JSON이 아니거나 논리 오류인 200 응답은 코어에서 실패하며 자동 재요청하지 않는다. 상세는 [공식 공개 명세](https://www.data.go.kr/data/15125273/openapi.do)의 `step`/`file` 래퍼와 실제 응답의 래퍼 없는 형식을 모두 받는다. 실제 응답은 위 표본 1건으로 확인했다.

## 호출 예산과 시도

KST 날짜별 `api_budget.count`를 HTTP **직전 REQUIRES_NEW 트랜잭션**으로 예약한다. 같은 트랜잭션에 `api_attempt(day,kind,attempt_no,started_at)`를 넣어 최초 적재/정기/상세/재시도를 구분한다. 외부 반영이 롤백돼도 예약은 보존한다. 500번째 예약에서 `API_BUDGET_WARNING` 고정 로그, count>=900이면 추가 예약과 HTTP를 거부한다. 두 앱의 899 경합에서도 외부 시도 합계는 최대 900이다. **정기 수집 몫 72회(하루 24회 × 최대 3회 시도)를 남긴다**: 상세와 최초 적재는 같은 카운터에서 `count < 900 − 72 = 828`일 때만 예약된다(같은 조건부 UPDATE 한 문장이라 원자적이다). 그래서 상세나 적재가 828에 닿아도 그날 정기 수집 72회는 항상 예약할 수 있다. `COLLECTOR_STOP_LIMIT`을 낮추면 상세·적재 한도도 같이 줄고, 72 이하로 낮추면 상세·적재는 예약하지 못한다. 초기값은 `COLLECTOR_WARNING_LIMIT`/`COLLECTOR_STOP_LIMIT`으로 낮출 수 있으며 900보다 높일 수 없다.

`api_attempt.outcome=SUCCESS`는 HTTP 200 수신을 뜻하며 공고 반영 성공과 다르다. 파싱/반영 결과는 `collect_run`에서 본다. outcome이 null이면 예약 직후 종료하거나 결과 저장 전에 종료한 시도다. 원격 수신 여부를 추측해 예약을 환불하지 않는다. API별 재시작 후 시도·재시도·미완료를 검증하기 위해 합계 카운터 외에 이 표를 둔다. 본문, URL, 키, 원본 예외는 저장하지 않는다.

## 최초 적재

환경변수로 DB와 API 키를 주입한 로컬 운영 셸에서 실행한다. 아래 명령은 2026-11-15 개발 DB 실측(`initial-003`)에 쓴 것과 같다. 슬롯은 DB마다 새로 고른다. 그 개발 DB에서는 `initial-001`~`initial-004`를 이미 썼다.

```powershell
java -jar build/libs/gongchae-bell.jar --spring.main.web-application-type=none --collector.enabled=false --collector.backfill=true --collector.backfill-slot=initial-003 --collector.backfill-from=2023-11-15 --collector.backfill-to=2026-11-15
```

**조회 (START_SCAN)**: `list?pbancBgngYmd=<from>&numOfRows=1000&pageNo=n`을 1페이지부터 원격 `totalCount`를 다 받을 때까지 읽는다. `pbancEndYmd`는 보내지 않는다. 결과에는 목표 종료일 이후 시작하는 공고도 들어 있다. 이 행은 받고 검사하지만 반영하지 않으며, 이후 정기 수집이 `from_backfill=false`로 넣는다. 첫 페이지에 반영할 행이 하나도 없어도 끝까지 읽는다. 기간을 생략하면 실행 KST 날짜부터 3년 전 날짜까지다. 과거 공고 상세는 호출하지 않는다. 호출 수는 `ceil(totalCount/1000)`이다(2026-11-15 기준 37).

**페이지 검사는 모두 반영 전에 한다**([#3 계약](../../collab/2026-11-15-implementation/claude-ingestion-contract.md)). 본문을 한 번 파싱(`parsePage`)하며 반영하지 않을 행까지 **모든 raw 행**을 검사한다. 이어서 다음을 확인한다:

1. 앞 페이지와 같은 `totalCount`
2. 기대 페이지 크기(`min(1000, T-(n-1)·1000)`)
3. 앞 페이지들과의 raw SN 중복
4. 시작일이 `from` 이상

하나라도 어긋나면 그 페이지 전체를 거부하고, 이미 저장된 공고의 내용도 바꾸지 않는다. 통과하면 같은 파싱 결과에서 시작일이 `to` 이하인 행만 골라(`startingOnOrBefore`) `upsertPage`로 반영한다. 끝에서 raw 고유 SN 수가 `totalCount`와 같아야 완료한다.

**재개와 세대**: scan 행과 페이지 행에는 **조회 세대**(`scan_generation`)가 있다.
- 새 슬롯으로 다시 실행하면 같은 기간의 가장 최근 scan을 본다.
  - 그 scan이 `COMPLETE`면 HTTP 없이 **그 scan 행 자신의 두 SN 집합**(raw, 반영)으로 끝낸다.
  - 아니면 그 세대를 이어 간다. 1페이지부터 연속으로 완료된 페이지만 재사용하고, 첫 빈 페이지부터는 끝까지 새로 받는다.
- `--collector.backfill-restart-pages=true`는 완료 여부와 상관없이 **새 세대**로 1페이지부터 다시 읽는다. 다른 세대의 페이지는 `totalCount`가 같아도 섞지 않는다.
- 원격 `totalCount`에는 새로 등록되는 공고도 들어가므로 실행 중이나 재개 사이에 바뀔 수 있다. 그러면 `BACKFILL_COUNT_CHANGED`로 멈춘다. 이때는 새 슬롯과 `restart-pages=true`로 다시 읽는다. 이미 반영한 행은 #3 코어가 멱등 처리한다. 공고 등록이 적은 시간에 실행하는 것이 낫다.
- V4 이전 월별 행(`strategy` null)은 기간이 같아도 재개·완료 근거로 쓰지 않는다.

**기록**: 명령·scan·페이지 행에 기간, 페이지, raw `totalCount`·`received`·`unique_count`·`received_sns`, 반영 `applied_count`·`applied_sns`를 남긴다. 페이지 행의 `inserted`·`changed`·`closed`는 반영한 행에 대한 값이다. scan 행과 명령 행은 받아들인 페이지(검사·반영·체크포인트를 마친 페이지)만 센다. 실패로 끝나도 그때까지의 합계를 남기고, 실패한 페이지 내역은 페이지 행에 있다. FR-C1의 "고유 공고 수"는 `applied_count`다. 새로 발생한 HTTP 시도 수는 `api_attempt`로 구분한다. null 결과의 종료된 기존 실행은 이력으로 보존한다.

**보장하지 않는 것**: 원천은 스냅숏 토큰을 주지 않는다. 그래서 실행 중 `totalCount`를 그대로 둔 채 한 행이 빠지고 다른 행이 들어오는 교체는 감지하지 못할 수 있다. 감지하는 변화(`totalCount` 변경, 중복, 페이지 크기)에서는 멈춘다.

세션 잠금의 범위와 한계는 [ADR-0016](../adr/0016-supplemental-session-lock.md)에 있다. 잠금은 Hikari 풀 밖의 전용 물리 연결을 쓰고, 해제가 실패해도 그 연결을 닫아 세션과 함께 잠금을 끝낸다. 잠금마다 DB 연결이 하나 더 필요하다(풀 최대 크기에 포함되지 않음).

## 운영 지표

다음 관리 포트 Prometheus 지표는 DB 기록을 읽으므로 앱 재시작 뒤에도 유지된다. 모두 개인정보 없는 고정 라벨이다.

| 지표 | 용도 |
|---|---|
| `collector_api_attempts_today` | KST 일 예산. 500 경고/900 중단 상태 |
| `collector_api_attempts_kind_today{kind}` | REGULAR/BACKFILL/DETAIL별 당일 시도 |
| `collector_api_retries_today` | attempt_no>1인 당일 재시도 |
| `collector_api_unfinished_today` | 결과를 남기지 못한 예약 |
| `collector_last_complete_epoch` | 마지막 정기 완전 수집 시각. 미완료에서는 불변 |
| `collector_regular_incomplete_consecutive` | 최근 완전 수집 이후 미완료/진행 회차 |
| `collector_regular_claimed_last24h`, `collector_regular_complete_last24h` | 예정 24회 대비 실제 선점/완료. blue/green scrape는 합산하지 않고 max 사용 |
| `collector_runs_unfinished` | 중단되거나 아직 진행 중인 회차 |
| `collector_detail_gave_up` | 상세 실패 3회 상한에 걸려 더 요청하지 않는 진행 공고 수. 0이 아니면 공고 상세 형식이나 공급자 상태를 확인한다 |

Grafana 경고 조건은 일 예산 >=500, 최근 미완료 >=2, 마지막 완전 수집 경과 시간이다. 실제 운영 디스코드 경보 연결·수신 시험은 M3 운영 구성과 실제 환경 준비 뒤 수행한다. 현재 문서만으로 수신 검증 완료라고 표시하지 않는다. 같은 DB의 blue/green 지표는 sum 대신 max로 읽는다. 실제 공식 봉투 1회 확인, 3년 적재와 오래된 코드/품질 표본 검수도 환경 준비 뒤 별도 기록한다.
