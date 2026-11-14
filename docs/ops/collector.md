# 수집 실행과 점검

수집기는 공식 `https://apis.data.go.kr/1051000/recruitment/`만 호출한다. 공개 경로 자동 대체와 공개 수집 운영 API는 없다.

## 설정과 정기 수집

`COLLECTOR_ENABLED` 기본값은 false다. 공식 개발계정 환경이 준비된 앱에서만 true로 설정한다. `COLLECTOR_SERVICE_KEY`에는 포털의 **일반 인증키(Decoding)** 값을 환경변수로 넣는다. 클라이언트가 한 번 URL 인코딩한다. 키를 명령 인자·커밋·명령 출력·로그에 넣지 않는다. 실제 API의 Java HTTP wire/debug 로깅도 활성화하지 않는다.

정기 수집은 KST 매시 7분, `ongoingYn=Y&numOfRows=1000&pageNo=1` 한 요청이다. 완결성 검증과 공고 반영/회차 마감은 #3 코어가 담당한다. 실패 응답은 추가 페이지나 공개 경로로 바꾸지 않는다. 이전 마지막 완전 수집 시각이 유지된다. 같은 시간 슬롯의 앱은 하나만 HTTP를 호출한다. 완전 수집 뒤 신규 진행 공고의 상세를 수집한다. 성공한 상세는 다시 요청하지 않고, 실패한 상세는 다음 정기 슬롯에 재시도한다. 최초 적재로 처음 발견한 공고는 상세 대상에서 제외한다.

HTTP 요청/연결 타임아웃은 10초, 최초 포함 최대 3회, 일반 재시도 대기는 2초/4초다. 429는 Retry-After(초 또는 HTTP 날짜)를 따른다. 5xx·연결 오류·타임아웃만 일반 재시도한다. 3xx는 따라가지 않고 4xx는 429 외에는 재시도하지 않는다. 본문이 JSON이 아니거나 논리 오류인 200 응답은 코어에서 실패하며 자동 재요청하지 않는다. [공식 공개 명세](https://www.data.go.kr/data/15125273/openapi.do)의 상세 result 객체와 steps/file 래퍼를 지원하되 실제 공식 응답은 아직 미검증이다.

## 호출 예산과 시도

KST 날짜별 `api_budget.count`를 HTTP **직전 REQUIRES_NEW 트랜잭션**으로 예약한다. 같은 트랜잭션에 `api_attempt(day,kind,attempt_no,started_at)`를 넣어 최초 적재/정기/상세/재시도를 구분한다. 외부 반영이 롤백돼도 예약은 보존한다. 500번째 예약에서 `API_BUDGET_WARNING` 고정 로그, count>=900이면 추가 예약과 HTTP를 거부한다. 두 앱의 899 경합에서도 외부 시도 합계는 최대 900이다. 초기값은 `COLLECTOR_WARNING_LIMIT`/`COLLECTOR_STOP_LIMIT`으로 낮출 수 있으며 900보다 높일 수 없다.

`api_attempt.outcome=SUCCESS`는 HTTP 200 수신을 뜻하며 공고 반영 성공과 다르다. 파싱/반영 결과는 `collect_run`에서 본다. outcome이 null이면 예약 직후 종료하거나 결과 저장 전에 종료한 시도다. 원격 수신 여부를 추측해 예약을 환불하지 않는다. API별 재시작 후 시도·재시도·미완료를 검증하기 위해 합계 카운터 외에 이 표를 둔다. 본문, URL, 키, 원본 예외는 저장하지 않는다.

## 최초 적재

환경변수로 DB와 API 키를 주입한 로컬 운영 셸에서 실행한다. 아래 값은 예시이며 실측 적재 기록이 아니다.

```powershell
java -jar build/libs/gongchae-bell.jar --spring.main.web-application-type=none --collector.enabled=false --collector.backfill=true --collector.backfill-slot=initial-001 --collector.backfill-from=2023-11-15 --collector.backfill-to=2026-11-15
```

기간을 생략하면 실행 KST 날짜부터 3년 전 날짜까지를 포함한다. 첫/마지막 달은 부분 월이다. 재개 시에는 처음 기록한 기간을 명시하고 새 슬롯(예: initial-002)을 쓴다. 슬롯은 영문/숫자/밑줄/하이픈 1~40자다. 이미 사용한 슬롯은 외부 요청 없이 종료한다. 완료한 달은 건너뛰며 미완료 달은 완료 페이지 다음부터 이어 간다. 월의 totalCount와 받은 건수 합·고유 SN 수가 모두 같아야 완료한다. 페이지 날짜 오류 한 건도 페이지 전체 실패다. 실패 페이지를 건너뛰고 달을 완료하지 않는다. 과거 공고 상세는 호출하지 않는다.

미완료 달의 upstream totalCount가 바뀌어 기존 페이지와 합칠 수 없으면 멈춘다. 기간과 새 슬롯을 지정하고 `--collector.backfill-restart-pages=true`로 **미완료 달의 페이지**를 처음부터 다시 읽는다. 완료 달은 이 옵션에서도 건너뛴다. 중복 반영은 #3 코어가 멱등 처리한다.

명령/월/페이지의 기간·페이지·원격 totalCount·받은 건수·고유 SN 집합과 고유 건수를 `collect_run`에 남긴다. null 결과의 종료된 기존 실행은 이력으로 보존한다. 세션 잠금의 채택 전 제안과 한계는 [검토 초안](../../collab/2026-11-15-implementation/sol-collector-lock-proposal.md)에 있다.

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

Grafana 경고 조건은 일 예산 >=500, 최근 미완료 >=2, 마지막 완전 수집 경과 시간이다. 실제 운영 디스코드 경보 연결·수신 시험은 M3 운영 구성과 실제 환경 준비 뒤 수행한다. 현재 문서만으로 수신 검증 완료라고 표시하지 않는다. 같은 DB의 blue/green 지표는 sum 대신 max로 읽는다. 실제 공식 봉투 1회 확인, 3년 적재와 오래된 코드/품질 표본 검수도 환경 준비 뒤 별도 기록한다.
