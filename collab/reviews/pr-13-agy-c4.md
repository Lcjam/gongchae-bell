# PR13 C4 수정 라운드 리뷰 · agy

2026-11-18. 대상은 `feat/#6-subscriptions` / `fix/#6-request-budget`의 head `6554ea6dec974a3677419e220eab4258beaee5ca`이며, 직전 head `7fa3f385773b3bcf2cb76b7a8f2766c1867cc432`와의 diff(11개 파일)와 `issue-6-budget-fix` 워크트리 소스를 읽기 전용으로 검수했다.

## 판정

**수정 요청.** C4-a·C4-b·C4-c의 핵심 메일·원장·조건 조회 경로와 잠금 회귀 테스트 종료 보장은 코드·문서·집중 테스트에서 모두 닫혔다. 다만 `SyncSender.testWebhook`의 재시도 루프가 `requirePoolRoom` 도입 이후 최소 잔여 시간(13초)과 기존 `MIN_START`(5초) 체크 사이의 틈에서 `QueryTimeoutException`을 던져, 실제 디스코드 10초 타임아웃 뒤 정상 실패 응답(502) 대신 503으로 새는 회귀 결함이 하나 있다.

---

## 결함 (수정 필요)

### [C4-R1] `SyncSender.testWebhook` 2·3회차 진입 시 `5s <= remaining < 13s` 구간에서 502 대신 503으로 빠짐
- **위치**: `SyncSender.java:195-198`, `SyncSender.java:206`, `SubscriptionService.java:381`, `SubscriptionController.java:107, 127-128, 311-315`
- **실패 시나리오**:
  1. `POST /subscriptions/discord`에서 `signupOptions`·`rate_limit`·`liveDestination`을 통과해 1회차 `webhooks.send`가 `HTTP_MAX`(10초) 타임아웃으로 실패한다(`remaining` ≈ 14.99초).
  2. `BACKOFF[0]`(2초)를 자고 나면 `remaining(start)`는 최대 약 12.99초(항상 13초 미만)가 된다.
  3. 2회차 루프(`no = 2`)에서 `SyncSender.java:195`의 `remaining(start).compareTo(MIN_START) < 0`(5초 미만)은 거짓이므로 통과한다.
  4. 바로 다음 줄 `SyncSender.java:196`의 `limits.acquire(..., () -> waitLimit(start).minus(MIN_HTTP))`가 `RateLimiter.requirePoolRoom`을 호출한다. `waitLimit(start).minus(MIN_HTTP)` = `remaining - poolWait(5s) - CLEANUP(2s) - MIN_HTTP(1s)` ≤ `4.99s` < `poolWait(5s)`이므로 **`QueryTimeoutException("SYNC_BUDGET_SPENT")`가 발생**한다.
  5. 같은 현상은 1회차 8초(`HTTP_5XX`) + 2초 대기 + 2회차 2초(`HTTP_5XX`) + 4초 대기 뒤 3회차(`remaining` = 9초) 진입 때도 재발한다(`SyncSender.java:206`도 `MIN_START` 5초만 보고 sleep를 실행한 뒤 3회차 196행에서 예외).
- **근거와 영향**:
  - `deliverMail`은 루프가 `try/catch (CannotAcquireLockException | QueryTimeoutException)`로 감싸져 있고 `SubscriptionService.deliver`가 `RuntimeException`을 잡아 고정 200 응답을 지키지만(`SyncSender.java:102-104`, `SubscriptionService.java:446-449`), `testWebhook`과 `registerDiscord`는 `QueryTimeoutException`을 잡지 않으므로 `SubscriptionController.busy`가 이를 받아 **503("요청이 몰려 제한 시간 안에 처리하지 못했습니다")**을 반환한다.
  - 계약 §6은 "발송 전 예산 소진은 503"이라 적었고, 이미 1회 이상 디스코드에 전송을 시도하고 실패한 경우 컨트롤러는 502("디스코드에 테스트 메시지가 전송되지 않아 등록하지 않았습니다")를 돌려주도록 설계되어 있다(`SubscriptionController.java:127-128`). 또한 `SyncSender.java:198`의 `if (timeout.compareTo(MIN_HTTP) < 0) break;`는 정상 경과 시간으로는 도달할 수 없는 죽은 분기가 된다.
- **권장 수정 방향**:
  - `attempts`가 비어 있는 1회차 진입 전(발송 전)에 `remaining < 13s`이면 기존처럼 503이 맞지만(`conditionLookupUsesThePostBudgetAndInvalidInputReusesItsOptions`와 일치), 이미 1회차 시도가 나간 뒤(`!attempts.isEmpty()`)에는 다음 시도의 풀·정리·HTTP 최소 몫(`waitLimit(start).minus(MIN_HTTP).compareTo(limits.poolWait()) < 0` 또는 sleep 전 동일 조건)이 부족하면 `limits.acquire`를 부르기 전에 `break`해서 502 `TEST_FAILED`로 끝나게 한다. 실제 DB 잠금·풀 경합으로 `limits.acquire` 내부에서 타임아웃이 난 경우는 기존대로 503으로 둔다.

---

## 항목별 점검 결과

### 1. C4 남은 세 항목이 닫혔는가
- **① 조건 조회부터 같은 단조 시계 예산·오류 재표시 재사용**: **충족.**
  - `SubscriptionController.java:74-78, 89`(이메일)와 `101-106, 111`(디스코드)이 엔드포인트 진입 직후 `service.start()`로 시계를 시작하고 `service.signupOptions(start)`를 호출한다.
  - `SubscriptionService.signupOptions(start)`(`91-98`)는 `limits.requirePoolRoom(waits)`로 풀 진입 상한을 확인한 뒤 `tx.execute` 안에서 `limits.boundWaits(waits.get())`로 `code_name` 조회 SQL을 묶는다.
  - 조회한 `options`를 `service.conditions(..., options)`에 전달하고, `InvalidInput` 시 `newPage(model, options, ...)`로 재사용하므로 잘못된 입력 화면 렌더링에서 `findFilterOptions()`를 중복 호출하지 않는다. (`SubscriptionController.manage`는 동기 발송 경로가 아니므로 7-인자 `conditions` 유지가 범위와 맞다.)
- **② 연속 SQL마다 남은 시간 재계산**: **충족.**
  - `DeliveryLedger.reserve` (`91-114`): `UPDATE notification`(94) 직전, `SEND_TOTAL` `limits.acquireHere`(102) 직전, `INSERT notification_attempt`(108) 직전마다 `boundWaits(waitLimit.get())`를 다시 호출한다.
  - `SubscriptionService.signupEmail` (`151-188`): `INSERT ON CONFLICT`(154), 성공 시 `createSync`(166), `SELECT ... FOR UPDATE`(169), `UPDATE subscription`(176), 갱신 후 `createSync`(184) 각각 직전에 `boundWaits(waits.get())`를 호출한다.
  - `SubscriptionService.recoverEmail` (`313-323`): `SELECT ... FOR KEY SHARE`(314)와 `createSync`(320) 각각 직전에 `boundWaits(waits.get())`를 호출한다.
  - `SubscriptionService.registerDiscord` 최종 저장 (`388-417`): `cleanupWaits(start)` 한 개 인스턴스를 만들어 `INSERT subscription`(390), `INSERT notification`(402), `INSERT notification_attempt` 루프(409)마다 같은 2초 마감과 원 요청 마감의 최솟값(`min(2s - elapsed, remaining(start))`)으로 다시 묶는다.
  - `SyncSender.attempt` (`115-160`): `stillEligible`, `history`, `lockReserved`, 재확인 `stillEligible`, `recordOutcome`, `finish`(`stop`/`finished`) 각 문장 직전마다 남은 몫 또는 공유 `cleanupWaits(start)`로 `boundWaits`를 호출한다.
- **③ 풀 진입 전 Hikari 획득 상한 확보와 최종 정리 몫**: **이메일·원장·최종 저장 경로 충족 (`testWebhook` 재시도 분기는 위 결함 C4-R1).**
  - `RateLimiter.java:46-52, 71-76`: `@Value("${spring.datasource.hikari.connection-timeout:30000}")`로 기동 시 풀 대기 상한(`application.yml:10`은 `5000`ms)을 읽고, `0`(무제한)은 `Long.MAX_VALUE`로 취급해 `requirePoolRoom`이 요청 예산보다 크거나 무제한인 풀로의 진입을 거부한다.
  - `SyncSender.waitLimit(start)` (`78`)가 `remaining(start) - poolWait(5s) - CLEANUP(2s)`를 반환하므로, 일반 단계(`signupOptions`, `limits.acquire`, `signupEmail`, `recoverEmail`, `liveDestination`, `deliverMail` A 진입)는 모두 최종 연결 획득 5초 + 정리 2초 + 자기 자신의 연결 획득 5초(합 12초 이상)를 확보한다. `reserve`는 `waitLimit - MIN_HTTP`(합 13초 이상)를 확보한다.
  - `registerDiscord` 최종 저장(`SubscriptionService.java:387`)도 `remaining(start) - CLEANUP >= poolWait`(7초 이상)를 확인한 뒤 새 연결에 진입한다.

### 2. 회귀 점검 (잠금 순서·R·`end()`/`requirePoolRoom` 경로)
- **잠금 순서·원자 커밋·펜싱·시도/카운터 보존**: 유지됨.
  - `stillEligible`(`subscription FOR SHARE`) → `R`(`REQUIRES_NEW`, `UPDATE notification` 펜싱 → `SEND_TOTAL` → `attempt INSERT` NULL, 실패 시 전부 롤백) → `A` `lockReserved`(`notification FOR UPDATE`) 순서가 그대로다.
  - `MAX_ATTEMPTS = 3`, `recordOutcome`의 `outcome IS NULL` 조건, `finish`의 `UNKNOWN_NAMES` 기준 `UNCERTAIN` 보존, `SEND_TOTAL`·`rate_limit` 미환불 모두 변경 없이 보존되었다.
- **`remaining < 5s`에서 `end()`가 `requirePoolRoom` 예외로 빠지는 경로**: **계약의 "durable null과 기존 정리 경로"와 부합함.**
  - OS/GC 정지 등으로 `sleep`이나 `mail.send`가 길어져 `remaining(start) < 5s`가 된 채 `end(claim, reason, start)`(`SyncSender.java:175-181`)에 도달하면, `limits.requirePoolRoom(() -> remaining(start))`가 `QueryTimeoutException("SYNC_BUDGET_SPENT")`를 던져 5초짜리 새 풀 획득 대기로 요청이 25초 밖으로 더 밀리는 것을 막는다.
  - `SubscriptionService.deliver`(`446-449`)가 이 `RuntimeException`을 잡아 경고 로그(`ALERT_SYNC_SEND_ABORTED QueryTimeoutException`)만 남기고 정상 반환하므로 사용자 고정 응답(200)은 유지된다.
  - DB에는 `R`이 이미 커밋한 `attempt(outcome = NULL)`(또는 이미 기록된 결과)과 `notification(status = 'SENDING')`이 그대로 남으며, 5분 경과 후 `AlertsMaintenance`의 `DeliveryLedger.sweepSync()`(`155-172`)가 `NULL` 시도를 `UNKNOWN_CRASH`로 바꾸고 `UNCERTAIN` 또는 `FAILED` (`last_error = 'INTERRUPTED'`)로 종결한다(동기 재발송 없음).
- **경미한 관찰 (비차단)**:
  - `SyncSender.java:128`은 `waitLimit(start).compareTo(MIN_HTTP) > 0`, `135`는 `timeout.compareTo(MIN_HTTP) < 0`를 써서 `waitLimit == 1000ms` 경계에서 1ms 보수적으로(`NOT_SENT`) 동작한다.
  - `DeliveryLedger.java:89`, `RateLimiter.java:60` Javadoc은 아직 "연결을 받은 뒤 `waitLimit`을 읽는다"로 되어 있으나 실제 코드는 연결 진입 전 `requirePoolRoom`에서도 읽는다.

### 3. 테스트 검증 (결함 탐지력과 잠금 테스트 종료 보장)
- **집중 테스트 직접 실행 결과** (`issue-6-budget-fix`, `--rerun-tasks`, 격리 Testcontainers PostgreSQL 17 + provider 대역):
  - `AlertsDisabledIntegrationTest`: 1건 통과 (0 fail, 0 err, 0 skip)
  - `DeliveryLedgerTest`: 18건 통과 (0 fail, 0 err, 0 skip)
  - `SubscriptionFlowIntegrationTest`: 13건 통과 (0 fail, 0 err, 0 skip)
  - 총 32건 실행·통과. 전체 Gradle `check`/`bootJar`/CI는 이번 라운드에서 직접 실행하지 않았다.
- **회귀 탐지력·하네스 종료 보장**:
  - `DeliveryLedgerTest.insufficientPoolRoomStartsNoNewAttemptAndKeepsTheFinalConnectionShare` (`296-315`)는 `SlowPool.acquisitions`로 풀 진입 차단을 검증하므로 `requirePoolRoom`을 되돌리면 즉시 실패한다.
  - `DeliveryLedgerTest.poolAdmissionUsesTheConfiguredUpperBoundIncludingAnUnboundedPool` (`318-326`)는 `10_000`과 `0`(무제한) 설정의 진입 거부를 검증한다.
  - `DeliveryLedgerTest.sequentialReservationLocksShareOneDecreasingBudgetAndRollbackTogether` (`380-412`)는 `notification` 2초 잠금 대기 뒤 `SEND_TOTAL` 잠금 대기가 합산 ~6초(`5.5s ~ 7.5s`, `reservation.get(5s)`) 안에 실패·롤백됨을 검증하므로 `acquireHere` 앞 `boundWaits`를 빼면 타임아웃으로 실패한다. (단, `attempt INSERT` 3번째 문장 및 `signupEmail`·`registerDiscord` 최종 저장 내부의 연속 문장별 별도 다중 잠금 경합 테스트는 없고 코드 검토로 확인했다.)
  - `SubscriptionFlowIntegrationTest.conditionLookupUsesThePostBudgetAndInvalidInputReusesItsOptions` (`448-462`)는 `findFilterOptions()` 지연 시 `subscribeEmail`·`registerDiscord` 503 및 잘못된 입력 400 시 `verify(catalog, times(3))`를 확인하므로 C4-a를 되돌리면 실패한다.
  - `SubscriptionFlowIntegrationTest` (`416-445`, `465-478`)와 `DeliveryLedgerTest` (`329-377`, `380-412`) 모두 별도 executor에서 요청을 실행해 `Future.get(timeout)`로 상한 안에 관측하고, 안쪽 `try/finally`에서 `holder.rollback()` / `other.rollback()`을 먼저 실행한 뒤 바깥 `try-with-resources`가 executor를 닫도록 고쳐져 있어 회귀 시에도 잠금이 해제되며 종료된다.
  - 다만 위 결함 `C4-R1`(`testWebhook` 1회차 타임아웃 뒤 2회차 진입 시 503 누출)을 잡는 디스코드 다회 타임아웃 테스트는 빠져 있다.

### 4. 문서 정합성
- `collab/2026-11-16-implementation/claude-subscriptions-contract.md` (§2 16행, §6 62행), `docs/adr/0014-initial-settings.md` (30행), `docs/ops/alerts.md` (45행)가 POST 조건 조회 전 시작, 잘못된 입력 옵션 재사용, 연결 진입 전 Hikari 5초 상한 확인, 일반 단계의 최종 연결 5초 + 정리 2초 확보, R 및 연속 SQL 재측정, 단일 2초 정리 마감, GC·OS 정지·DB 불응의 물리적 한계 및 durable null/기존 정리 경로, 보수적 풀 예약으로 인한 재시도·HTTP timeout 감소 가능성을 코드와 일치하게 기술하고 있다.
- 위 C4-R1이 수정되면 계약 §6의 "발송 전 예산 소진은 503"과 디스코드 테스트 실패 시 502 응답의 경계가 코드와 완전히 일치하게 된다.

---

## 확인한 것과 확인하지 못한 것
- **확인한 것**: `7fa3f38..6554ea6` 전체 git diff, `RateLimiter`·`DeliveryLedger`·`SyncSender`·`SubscriptionService`·`SubscriptionController`·`HttpTransports`·`CatalogRepository`·`application.yml`·계약 §2/§6·ADR-0014·`docs/ops/alerts.md`·관련 테스트 3개 파일 소스, 격리 Testcontainers 기반 집중 테스트 32건 직접 재실행(`--rerun-tasks`) 및 XML 결과.
- **확인하지 못한 것**: 전체 `./gradlew check`/`bootJar`/GitHub Actions CI 결과, 임시 소스 변이 실행(C4-R1은 코드·수식 추적으로 확인), 실제 Resend·Discord·PostgreSQL 풀 경합 부하 실측.
