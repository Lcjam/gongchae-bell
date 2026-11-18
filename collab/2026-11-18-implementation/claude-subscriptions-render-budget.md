# PR13 응답 기준 시각 조회 예산 수정 결과

2026-11-18. 요청은 root Codex의 PR13 C4 추가 검수 `collab/reviews/pr-13-codex-c4.md` P2다. 단독 작성자 Claude, `issue-6-budget-fix`(`fix/#6-request-budget`)에서 고쳐 PR13 `feat/#6-subscriptions`에 올렸다.

## SHA와 CI

| 항목 | 값 |
|---|---|
| 시작 head | `7cb0068e1704247111e4d325faefb6a4f1b584c5` |
| 코드 커밋 | `d474cf7` (`fix: read the footer collection time inside the request budget (#6)`) |
| CI (코드 커밋) | [44274892246](https://github.com/Lcjam/gongchae-bell/actions/runs/44274892246) test(`check bootJar`, ARM64)·secrets 성공 |
| 1단계 결과 문서 | `919cf70` |
| 2단계 코드 커밋 (풀 실패 응답) | `c005ee1` |
| main(#14) 병합 | `97c7415`, CI [44280281080](https://github.com/Lcjam/gongchae-bell/actions/runs/44280281080) test·deploy-verify·secrets 성공 |
| 최종 head | 이 파일을 담은 다음 커밋(문서만). PR #13 head로 확인 |
| PR | [#13](https://github.com/Lcjam/gongchae-bell/pull/13) draft 유지 |

## 재현 (수정 전 `7cb0068`)

정적 판정을 실제 MVC 경로로 먼저 확인했다. 별도 연결에서 `LOCK TABLE collect_run IN ACCESS EXCLUSIVE MODE`를 잡고 정상 이메일 신청 POST를 executor에서 실행했다. 확인 메일은 나갔고, 응답이 8초 관측 상한을 넘겨 `TimeoutException`으로 실패했다(새 시험 491행, 첫 요청). `finally`가 잠금을 푼 뒤에야 요청이 끝났다. Codex 판정과 다른 근거는 나오지 않았다.

## 수정

- `FreshnessModelInterceptor`에 요청 속성 `STATUS_SOURCE`를 두었다. 값이 `Supplier<FreshnessStatus>`이면 그것을 쓰고, 없으면 기존 `currentStatusSafe()`를 쓴다. 다른 화면(목록·상세·GET 등)의 동작은 그대로다
- `SubscriptionController.budgeted(request)`가 예산 시작과 함께 이 속성에 `service.freshness(start)`를 건다. 적용 경로는 시작 시각으로 25초 예산을 쓰는 세 POST, `/subscriptions/email`, `/subscriptions/discord`, `/subscriptions/recover/email`이다. 관리 링크 재발송은 이를 위해 `recoverEmail(email, ip, start)`로 컨트롤러의 시작 시각을 받는다
- `SubscriptionService.freshness(start)`는 디스코드 최종 저장과 같은 마지막 단계 방식으로 읽는다. `remaining - CLEANUP`에 풀 상한이 들어가야 연결을 받고, 받은 뒤 `cleanupWaits(start)`(2초 정리 마감과 원래 요청 마감 중 짧은 쪽)로 `lock_timeout`·`statement_timeout`을 걸고 조회한다. 부족·시간 초과·조회 실패(`DataAccessException`, 실제 풀 시간 초과가 오는 `TransactionException`)는 기존 `FreshnessStatus.unavailable()`("일시적으로 수집 시각을 확인할 수 없습니다")로 표시하고 경고 로그 `ALERT_FRESHNESS_UNAVAILABLE <예외 이름>`만 남긴다
- 조회는 응답 모델 단계에서만 일어나므로 발송·상태 기록 뒤에 결과를 바꾸지 않는다. 중복 발송이나 상태 반전 경로가 없다. 예외로 끝나는 요청(503 `busy`)은 `postHandle`을 거치지 않아 이 조회를 하지 않는다
- 2초 상한을 쓴 이유: 남은 예산 전체(최대 20초 이상)를 쓰면 메일이 이미 나간 뒤 표시용 조회 하나가 응답을 오래 붙잡는다. 정리 몫은 이미 계약에 있는 마지막 단계 몫이라 새 수치를 만들지 않았다
- 계약 §6 ④에 이 조회를 한 문장 추가했다

## 시험

| 시험 | 결과 |
|---|---|
| 새 `SubscriptionFlowIntegrationTest.theFooterCollectionTimeIsReadInsideTheRequestBudgetWhileCollectRunIsLocked` | `collect_run` ACCESS EXCLUSIVE 잠금 아래 실제 MVC POST. 정상 신청 200, 입력 오류 400, 관리 링크 재발송 200 모두 각각 5초 안에 끝나고 하단에 "확인할 수 없음" 표시. 예산 소진(남은 11초) 신청은 503으로 바로 끝남. 확인 메일은 1통. 잠금을 푼 뒤 신청은 실제 상태를 다시 표시. 각 요청은 `Future.get(8초)`로 관측하고 `finally`에서 잠금을 먼저 푼 뒤 executor를 닫는다. 수정 전 코드에서는 `TimeoutException`으로 실패, 수정 후 통과(7.2초) |
| 로컬 `./gradlew check bootJar` | **144 tests, 0 failures**, bootJar 생성 |
| CI 44274892246 | test(ARM64)·secrets(gitleaks no leaks) 성공 |

provider는 시험 대역, DB는 Testcontainers PostgreSQL 17만 썼다. 실제 API·메일·Discord·OCI·DNS·계정·env·키 접근은 0회다.

## 2단계: 풀 획득 실패 응답 (root 후속 배정)

root가 아래 관찰을 받아 고치도록 배정했다. 실제 풀 획득이 Hikari 상한(5초)을 넘기면 Spring은 `CannotCreateTransactionException`을 던진다(트랜잭션이 시작되지 않음). 이를 기존 의미와 대조해 두 곳을 고쳤다.

| 위치 | 수정 전 | 수정 후 |
|---|---|---|
| `SubscriptionController.busy()` | `CannotAcquireLockException`·`QueryTimeoutException`만 처리. 풀 실패는 일반 오류 화면(500) | 풀 실패도 같은 고정 503 안내. 숨기지 않도록 `ALERT_REQUEST_BUSY <예외 클래스> cause=<근본 원인 클래스>` 경고 로그를 남긴다. 메시지는 접속 정보가 섞일 수 있어 남기지 않는다. 근본 원인 클래스로 풀 포화(`SQLTransientConnectionException`)와 DB 연결 거부를 구분할 수 있다 |
| `SyncSender.deliverMail` | 잠금·문장 시간 초과만 잡음. A의 연결이나 R의 연결 B를 못 받으면 예외가 `deliver()`까지 올라가 작업 행이 5분 회수까지 `SENDING`으로 남음 | 같은 경로로 `end(DEADLINE)` 종결. 종결용 연결도 못 받으면 기존처럼 `deliver()`가 로그만 남기고 회수 경로가 정리한다 |

쓰기 의미는 그대로다. 실패한 단계는 시작되지 않았으므로 커밋된 것이 없고, 이미 센 요청 제한과 예약은 환불하지 않는다. 디스코드 테스트 도중 실제 풀 실패는 기존 시간 초과와 같이 503이다(과부하 신호). 예산 계산으로 미리 막는 경로는 1~3라운드에서 이미 502·503 경계를 정했다.

| 시험 | 결과 |
|---|---|
| `DeliveryLedgerTest.aConnectionThePoolCannotHandOverEndsTheJobAsDeadline` | 풀 대역이 1번째(A) 또는 2번째(R의 B) 획득에서 `CannotCreateTransactionException`을 던지면 작업이 `FAILED|DEADLINE|0`, 시도·발송·카운터 0 |
| `SubscriptionFlowIntegrationTest.aFullPoolAnswersBusyWithinItsBoundAndWritesNothing` | 실제 Hikari 풀의 연결을 최대 개수만큼 쥔 채 실제 MVC 신청 POST. 5.8초(Hikari 5초 상한)에 503 고정 안내, 구독·요청 제한·메일 0. 쥔 연결은 `finally`에서 먼저 반납 |
| 변이 확인 | 두 수정을 각각 되돌리면 두 시험이 `CannotCreateTransactionException`(MVC 쪽은 처리되지 않은 요청 실패)으로 실패하는 것을 확인하고 원복 |
| 로컬 `check bootJar` | 146 tests, 0 failures |

코드 커밋 `c005ee1`을 push한 뒤 PR13이 CI를 받지 못했다. root가 #14를 main에 병합해 `application.yml`이 충돌(`CONFLICTING`)했고, GitHub는 충돌한 PR의 `pull_request` 워크플로를 돌리지 않는다. main을 PR13 브랜치에 병합했다(`97c7415`). 두 쪽 모두 `forward-headers-strategy`를 넣었으므로 #14 계약인 main의 env 재정의 형식(`${SERVER_FORWARD_HEADERS_STRATEGY:native}`)을 택했다. 병합 뒤 로컬 `check bootJar` **148 tests, 0 failures**(#14의 `DeploymentConfigIntegrationTest` 포함), CI [44280281080](https://github.com/Lcjam/gongchae-bell/actions/runs/44280281080) test·deploy-verify·secrets 성공, PR 상태 `MERGEABLE`.

## 남은 범위와 관찰
- `POST /subscriptions/recover/discord`는 시작 시각을 쓰는 예산 경로가 아니라(동기 발송 없음) 기존 조회를 그대로 쓴다. 확인·관리·해지 POST도 같다
- 실제 공개 HTTPS·고지·실발송 검증은 PR 본문의 실사용 전 게이트로 남는다. main 머지, #7 착수, Sol 재개는 하지 않았다
