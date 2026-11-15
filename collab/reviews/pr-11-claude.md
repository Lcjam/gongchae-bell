# PR #11 리뷰 · claude (구현자 외 리뷰)

> 2026-11-15 · 조율: codex · 대상: PR #11 `feat/#4-collector-runtime`(base `feat/#3-ingestion-core`), head `309216a5335624e221a1d3390fd2bbc9d0a52d9e`, 초안. CI [43655122480](https://github.com/Lcjam/gongchae-bell/actions/runs/43655122480) test·secrets 성공
> 읽은 것: 전용 경로 `issue-4-collector-runtime`의 CLAUDE.md, PRD FR-C1·C5·C6·C7, `ingestion/runtime` 16개 파일 전체, V3, `application.yml`·`.env.example` 변경, 테스트 5개(이름과 핵심 본문), `docs/ops/collector.md`, `sol-collector-result.md`, `sol-collector-lock-proposal.md`. 상세 형식은 data.go.kr 데이터셋 15125273 페이지의 공식 swagger와 대조했다.
> **읽기 전용**: 코드와 브랜치를 고치지 않았고 테스트를 다시 돌리지 않았다. 내 #3 코드도 건드리지 않았다.
> ADR-0003(회차 UNIQUE INSERT)은 그대로 유효하다고 보고, 보조 잠금은 그 위에 더하는 보호로 검토했다.

## 결론
**머지 전에 고쳐야 할 것은 1건(H1)이다.** 상세 수집의 재시도가 일 예산을 다 써서 정기 수집을 멈출 수 있다. Medium 3건(Retry-After 상한, 상세 sweep의 스케줄 스레드 점유, 보조 잠금 연결 반환)은 이번 PR에서 고치는 편이 싸다. 회차 선점, 예산 예약, 키 보호, 최초 적재 재개, #3 계약 사용은 코드와 테스트 기준으로 맞다.

## High

### H1. 실패한 상세를 매시간 무제한 재시도해서 일 예산(900)을 소진하고, 그러면 정기 수집까지 막힌다
- **근거**
  - `DetailCollector.collect`(:25-27)의 대상은 `status='OPEN' AND from_backfill=false AND detail_status<>'DONE'`이다. **FAILED도 포함**되므로, 마감될 때까지 매 정기 회차(하루 24번)마다 다시 요청한다. 공고별 실패 횟수 상한이 없다.
  - 예산은 정기·최초 적재·상세가 같은 `api_budget` 하나를 쓰고(`ApiBudget.reserve`), 정기 수집 몫으로 남겨 두는 여유분이 없다. 상세가 900에 닿으면 다음 정기 회차의 `reserve`가 `BUDGET_EXHAUSTED`로 실패한다. 그러면 마지막 완전 수집 시각이 멈추고(FR-W5 지연 배너, "2회 연속 미완료" 경보), 그날은 회복되지 않는다.
  - 상세 응답 형식은 **실제 응답으로 확인되지 않았다**(결과 보고서 "미검증"). 실응답이 swagger와 조금만 달라도(예: 빈 `steps`가 배열이 아니라 null) `save`가 `INVALID_DETAIL`을 내고, **모든 신규 공고**가 FAILED로 쌓인다. 이 경우 HTTP 재시도는 없지만(200 응답) 공고마다 매시간 1회씩 호출한다.
- **추정 규모**(03의 "하루 신규 약 34건"은 1년치 건수에서 나눈 추정이다): 형식 불일치가 생기면 첫날 누적 호출이 약 34×24/2 ≈ 400건이다. 둘째 날부터 FAILED가 하루 68건 이상 쌓이므로, 하루 호출이 900을 넘어 정기 수집이 막힌다. 일시 오류일 때도 시도마다 최대 3회를 예약한다.
- **첫 운영 순서 위험**: 최초 적재 전에 정기 수집을 켜면 진행 공고 전부가 `from_backfill=false`가 된다. 실측(공개 경로 진행 공고 497건, 2026-11-14 22:22 KST)을 기준으로 첫 sweep에서만 약 500건을 호출한다. `collector.md`에는 "최초 적재 → 정기 수집 활성화" 순서가 적혀 있지 않다.
- **제안**(구현 방식은 Sol 판단, 둘 이상 조합 권장)
  1. 정기 수집 몫을 남긴다: 오늘 예산 사용량이 `stopLimit − (정기 몫, 예: 남은 시간 수 × 3)` 이상이면 상세 sweep을 시작하지 않거나 중단한다.
  2. 공고별 상세 실패 상한: 예를 들어 같은 `sn`의 실패한 DETAIL 회차가 N번이면 더 시도하지 않는다. `collect_run`의 DETAIL 슬롯이 `.../{sn}`이라 마이그레이션 없이 셀 수 있다.
  3. sweep당 최대 호출 수.
  4. 런북에 "최초 적재 완료 후 `COLLECTOR_ENABLED=true`"를 적는다.
  - 테스트: 상세가 항상 `INVALID_DETAIL`인 대역으로 여러 슬롯을 돌린 뒤에도 정기 수집이 예산을 얻는지 확인한다.

## Medium

### M1. 429 `Retry-After`를 상한 없이 그대로 기다린다
- `RecruitmentClient.retryAfter`(:78-83)는 서버가 준 초나 날짜를 그대로 쓴다. `Retry-After: 86400`이면 스케줄 스레드가 하루 동안 잠든다. 정기 수집이면 그 시간 슬롯이 `result IS NULL`로 남고, 최초 적재면 보조 잠금을 쥔 채로 멈춘다. 기존 테스트는 7초만 확인한다(`rateLimitUsesServerDelay`).
- 제안: 상한(예: 60초)을 두고, 넘으면 `HTTP_RATE_LIMIT`로 실패시켜 다음 회차에 맡긴다. 큰 값으로 테스트를 하나 추가한다.

### M2. 상세 sweep이 정기 수집과 같은 스케줄 스레드에서 끝날 때까지 돈다
- `RegularCollector.collect`(:36)는 완전 수집 직후 `details.collect`를 동기로 부른다. 스케줄 스레드 풀 크기는 설정이 없어 Boot 기본값(1)이다. 상세 1건의 최악 소요는 약 36초(10초 × 3 + 대기 2초 + 4초)이므로, 대상 100건이면 한 시간에 가깝다. 그러면 다음 `:07` 실행이 늦어지고, 슬롯을 실행 시점의 `clock.instant()`로 계산하므로 한 시간이 통째로 빠질 수 있다(`collector_regular_claimed_last24h` < 24).
- 같은 이유로 종료 설정도 확인이 필요하다. `spring.task.scheduling.shutdown.await-termination`/`await-termination-period`가 없다. blue/green 전환이 `:07`과 겹칠 때 진행 중인 정기 수집이 기다려지는지 끊기는지는 Spring 6.1의 스케줄러 생명주기 동작에 달려 있는데, 테스트로 확인되지 않았다(01-claude §3-9에서 권고한 설정).
- 제안: sweep에 시간이나 건수 상한을 둔다(H1의 3번과 함께). 또는 상세를 별도 `@Scheduled` 작업과 별도 스레드로 분리한다. 종료 대기 30초(ADR-0014)를 설정한다.

### M3. 보조 잠금 연결: unlock이 실패해도 잠금을 쥔 연결이 풀로 돌아갈 수 있다
- `AdvisoryLease.Lease.close`(:40-44)는 `try(connection; statement)` 구조라서 `pg_advisory_unlock`이 `SQLException`으로 실패해도 `connection.close()`가 실행되고, Hikari 풀에 연결이 반납된다. Hikari는 반납할 때 세션 advisory lock을 풀지 않는다. 연결이 정말 끊긴 경우(예: `pg_terminate_backend`, 테스트가 확인하는 경우)는 Hikari가 그 연결을 버린다. 하지만 연결이 살아 있는데 unlock만 실패하면, 잠금이 풀 안의 유휴 연결에 남는다. 그러면 그 연결이 `maxLifetime`(기본 30분)으로 은퇴할 때까지 최초 적재와 상세 sweep이 소유자도 보이지 않는 채 `ALREADY_RUNNING`/건너뜀이 된다. 그 연결을 다른 코드가 빌리면 잠금 소유자가 엉뚱한 작업이 된다.
- 제안: close에서 예외가 나면 반납하지 말고 물리 연결을 버린다(`connection.abort(Runnable::run)` 또는 Hikari `evictConnection`). unlock 실패를 흉내 낸 테스트를 하나 둔다. 선택: `verify()`가 살아 있음만이 아니라 `pg_locks`에서 자기 `pg_backend_pid()`의 잠금 보유까지 확인하면 의미가 더 분명해진다.

## 보조 세션 잠금 자체의 타당성과 위험 (ADR-0003 위에 더하는 보호)
| 항목 | 판단 |
|---|---|
| 필요성 | 타당하다. 최초 적재는 명령마다 슬롯이 달라서(`command/{slot}`, `{slot}/{month}/{page}`) UNIQUE INSERT만으로는 서로 다른 운영자 명령이 같은 페이지를 함께 받는 것을 못 막는다(예산 이중 사용). 상세도 슬롯이 `{시간}/{sn}`이라, sweep이 한 시간을 넘거나 blue/green이 겹치면 같은 `sn`을 두 앱이 요청할 수 있다. 두 경우 모두 데이터는 코어가 멱등 처리하므로 손상은 없고, 손해는 예산뿐이다 |
| 범위 | 회차 소유권은 계속 UNIQUE INSERT가 갖는다. 잠금은 선점 **전에** 잡고(실패하면 슬롯을 쓰지 않음), 정기 수집 경로에는 쓰지 않는다. 이 범위가 유지되면 ADR-0003과 충돌하지 않는다 |
| 정상 해제 | 같은 연결로 unlock한 뒤 반납한다. 맞다. 단 실패 경로는 M3 |
| 세션 단절 | PostgreSQL이 즉시 잠금을 푼다. 원래 앱이 HTTP나 페이지 반영 도중이면, 새 실행과 최대 한 페이지·한 상세 요청이 겹칠 수 있다. 데이터는 멱등, 예산은 이중이다. 초안의 한계 서술과 일치한다 |
| 반쯤 열린 TCP | 서버 쪽 세션이 TCP keepalive가 감지할 때까지(리눅스 기본 약 2시간) 살아 있어 잠금이 남는다. 그동안 최초 적재·상세가 막힌다. 같은 VM의 Docker 내부망이라 가능성은 낮다. DB를 다른 호스트로 옮기면 `tcp_keepalives_idle`이나 pgjdbc keepalive 설정이 필요하다는 점을 ADR에 적는다 |
| 연결 수 | 잠금당 연결 1개가 따로 필요하다(Hikari 기본 10). 최초 적재는 웹이 꺼진 별도 프로세스라 실제로는 앱당 최대 1개다. PgBouncer transaction 모드에서는 쓸 수 없다(초안에 있음) |
| 기록 | 채택되면 ADR-0003을 고치지 말고 **새 ADR**(예: 0016 "보조 세션 잠금")에 범위, 연결 반환·단절 정책, 한계를 적는다 |

## Low
| # | 내용 | 근거·제안 |
|---|---|---|
| L1 | 검증 실패 페이지도 데이터는 이미 반영돼 있다 | `BackfillCollector.month`(:71-76)는 `upsertPage`(커밋됨) 뒤에 `validate`/`withinRange`를 한다. 그래서 `COUNT_CHANGED`·`DUPLICATE`·`RANGE_MISMATCH` 페이지는 FAILED로 남아도 그 행은 `from_backfill=true`로 저장돼 있다. 본문을 한 번만 파싱한다는 #3 계약상 피할 수 없고, 데이터는 실제 공고이며 반영이 멱등이라 해롭지 않다. "실패 페이지 = 미반영"이 아니라는 점을 `collector.md`에 적으면 된다 |
| L2 | 빈 달의 응답 형식 미확인 | 공식 API가 빈 달에 `resultCode` 3 같은 값을 주면 #3이 `RESULT_CODE_NOT_OK`로 거부하고, 그 달은 계속 실패해 최초 적재가 영영 끝나지 않는다. 실응답 1회 확인 목록에 "빈 달 1건 호출"을 넣는다 |
| L3 | DETAIL 회차 행이 계속 쌓인다 | 상세 시도마다 `collect_run` 행이 생긴다(H1 상황이면 하루 수백~수천 행). `api_attempt`와 함께 보존 기간이 정해져 있지 않다. 지금 문제는 아니고 M3 운영 문서에서 정한다 |
| L4 | 상세 `steps` 화면 연동 | #4는 swagger 형식(`step` 래퍼를 벗긴 `aplyNope`, `cmpttRt`, `recrutStepSn` 등) 그대로 저장한다. 이 객체에는 단계 이름이 없다. #5 화면 쪽 문제로 [pr-10-claude M1](pr-10-claude.md)에 적었고, codex가 #5에 라벨 표시를 지시했다. #4 쪽 수정은 필요 없다 |

## 확인했고 문제를 찾지 못한 것
- **키 보호**: `URLEncoder`로 한 번만 인코딩하고(테스트), URI와 키를 로그·예외·`api_attempt`에 남기지 않는다. `CollectionFailure`는 고정 코드 메시지에 원인과 스택이 없다. `CollectorProperties.toString`이 키를 숨긴다(record 기본 `toString`이면 키가 나온다). Actuator 노출 범위는 바뀌지 않았다.
- **예산(FR-C6)**: 재시도 포함 모든 시도 전에 `REQUIRES_NEW`로 `count < stop` 조건부 증가와 `api_attempt`를 함께 커밋한다. 두 인스턴스의 899 경합에서 1건만 예약하는 테스트, 롤백·재시작 후 보존, KST 날짜 변경, 500 경고 1회. `stopLimit`은 900을 넘을 수 없다.
- **두 앱 선점(FR-C5)**: 정기 슬롯 선점을 짧은 트랜잭션으로 먼저 커밋하고 HTTP를 부른다(01-claude §3-1의 함정을 피함). 코어가 회차를 마감하고, 런타임은 HTTP·예산 실패만 `result IS NULL` 조건으로 마감하므로 이중 마감이 없다. 실제 런타임 인스턴스 2개 → HTTP 대역 1회 → 실제 #3 반영 1회 테스트가 있다.
- **최초 적재 재개(FR-C1)**: 명령·월·페이지 슬롯, 완료 월·페이지 재사용, `totalCount` 변경 감지와 명시적 재시작, `PageResult.sns`로 페이지 간 중복과 고유 수 계산(`inserted` 미사용), 기간 고유 수는 합집합, `IngestionException`이면 INCOMPLETE/FAILED이고 COMPLETE가 아님. 체크포인트 앞뒤 종료 주입 후 재개 테스트, 시작일이 월을 넘어 바뀐 경우(received=2, unique=1) 테스트.
- **#3 계약 사용**: 트랜잭션 밖에서 호출하고, 목록 본문을 다시 파싱하지 않으며, `observedAt`은 응답을 받은 뒤의 시각이다.
- **상세 JSON 계약(FR-C7)**: swagger의 `result` 객체, `steps[].step`, `files[].file`을 받고, 자기 컬럼만 `detail_status<>'DONE'` 조건으로 갱신한다. 실패해도 저장된 배열을 지우지 않는다. 과거 적재 공고와 DONE은 다시 부르지 않는다(테스트). 실응답 미확인은 보고서에 표시돼 있다.
- **V3**: 기존 표에 nullable 컬럼 추가와 새 표 하나뿐인 확장 방향 변경이다. V1·V2는 그대로다. #3의 Flyway 검사 완화 덕분에 합본 59개 테스트가 통과했다(Sol 보고).
- **지표**: DB 기록에서 읽으므로 재시작에도 유지된다. 라벨은 `kind` 3종뿐이다. blue/green 중복은 문서대로 `max`로 읽는다.
