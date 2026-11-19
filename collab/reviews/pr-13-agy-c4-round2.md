# PR13 C4 리뷰 2라운드 · agy

2026-11-18. 대상은 `feat/#6-subscriptions` / `fix/#6-request-budget`의 head `10fb394`(워크트리 `issue-6-budget-fix`)이며, `git diff 68c7767 10fb394`(5개 파일)와 관련 소스·계약 문서·집중 테스트 실행 결과를 읽기 전용으로 검수했다.

## 판정

**수용.** 1라운드 결함 `C4-R1`과 Javadoc 불일치가 모두 해소되었고, 이메일 루프의 변경된 경계도 기존 테스트 기대 및 계약 §6 ③과 일치한다.

---

## 확인 항목별 결과

### 1. C4-R1 종료 여부
- `SyncSender.roomForNext(long start, Duration wait)`(`SyncSender.java:91-94`)가 `remaining(start).minus(wait).compareTo(MIN_START) >= 0`과 함께 `waitLimit(start).minus(wait).minus(MIN_HTTP).compareTo(limits.poolWait()) >= 0`을 확인한다. 현재 설정(`poolWait=5s`, `CLEANUP=2s`, `MIN_HTTP=1s`) 기준으로 다음 시도 대기 뒤에도 `remaining >= 13s`가 남지 않으면 `testWebhook`(`SyncSender.java:216`)이 sleep 없이 즉시 `break`한다.
- 1회차가 이미 실행되어 `attempts`가 비어 있지 않은 상태에서는 `QueryTimeoutException`을 던지지 않고 시도 목록을 반환하므로 `SubscriptionService.registerDiscord`(`381-384`)가 `State.TEST_FAILED`, `SubscriptionController.subscribeDiscord`(`127-128`)가 **502**로 응답한다.
- 1회차 진입 전(`attempts`가 비어 있을 때) 예산 부족은 기존대로 `SyncSender.java:206`의 `limits.acquire` → `RateLimiter.requirePoolRoom`에서 `QueryTimeoutException("SYNC_BUDGET_SPENT")`가 발생해 **503**("발송 전 예산 소진은 503")을 유지한다.

### 2. 이메일 루프(`deliverMail` / `attempt`) 경계와 계약 정합성
- `SyncSender.attempt`(`169`)도 `if (!roomForNext(start, wait)) return finished(claim, "DEADLINE", cleanup);`를 적용했다.
- `DeliveryLedgerTest.theWholeRequestStaysInsideTwentyFiveSecondsIncludingWaitsInsideR`(`269-277`) 기준으로 1회차 `10s` 타임아웃 직후 `remaining=15s`, `waitLimit=8s`, `wait=2s`에서 `waitLimit - 2s - 1s = 5s >= 5s` 경계를 통과해 2회차(`6s`)를 수행하고 `UNCERTAIN|DEADLINE|2`, timeout `[10s, 6s]`, `timer.nanos = 18s` 기대를 그대로 만족한다.
- 선행 DB 작업으로 `10s` HTTP 후 `remaining < 15s`인 경우에도 예전처럼 `2s` sleep 뒤 새 A 트랜잭션을 열어 `reserve`의 `requirePoolRoom`에서 롤백하고 `end()`로 빠지는 대신, 이미 열린 A 안에서 sleep·추가 연결 없이 `finished(claim, "DEADLINE", cleanup)`로 종결한다. 최종 상태(`UNCERTAIN|DEADLINE|1` 또는 직전 확정 거절 시 `FAILED|DEADLINE|1`)와 계약 §6 ③("재시도는 대기 전에 다음 시도의 풀 상한·요청·정리 몫이 남는지 확인하고, 남지 않으면 대기 없이 끝낸다")에 부합한다.
- `Kind.STOP`/`NEXT_WINDOW`(`164-165`), `MAX_ATTEMPTS`(`166`), 긴 `Retry-After`(`168`) 분기는 모두 `roomForNext`보다 앞서 유지된다.

### 3. 새 테스트의 결함 탐지력
- `DeliveryLedgerTest.aWebhookRetryThatCouldNotBeAdmittedEndsAsAFailedTestWithoutSleeping`(`440-452`): 선행 `1s` + 1회차 `TIMEOUT`(`10s`)으로 `remaining=14s`, `waitLimit=7s`가 된 상태에서 `attempts = [TIMEOUT]`, `requests = [10s]`, 경과 시간 `11s`(백오프 `2s` sleep 없음)를 검증한다.
- 수식상 `SyncSender.java:216`을 이전 조건(`remaining.minus(wait) < MIN_START`)으로 되돌리면 `14s - 2s = 12s >= 5s`가 참이 되어 `2s`를 sleep한 뒤(`remaining=12s`, `waitLimit=5s`) 2회차 `limits.acquire`(`waitLimit - 1s = 4s < 5s`)에서 `QueryTimeoutException("SYNC_BUDGET_SPENT")`로 실패한다.

### 4. 문서·Javadoc 정합성 및 경미한 관찰
- `DeliveryLedger.reserve`(`89-90`), `RateLimiter.acquire`(`60-61`) Javadoc이 풀 진입 전 확인(`requirePoolRoom`)과 문장별 `boundWaits` 재계산 동작에 맞게 수정되었다.
- `collab/2026-11-16-implementation/claude-subscriptions-contract.md` §6 ③의 추가 문장이 구현과 일치한다. `MIN_HTTP` 1ms 경계(`SyncSender.java:138` `>` vs `145, 208` `<`)는 보수적 방향이므로 변경하지 않은 판단이 타당하다.
- **경미한 관찰 (비차단)**: `SyncSender.testWebhook`은 `deliverMail`과 달리 `QueryTimeoutException`을 잡지 않으므로, 1회차가 약 `9.995s` 안팎에서 실패해 `roomForNext`가 몇 ms 여유로 통과한 뒤 `Thread.sleep(wait)`이 OS 타이머 오차로 몇 ms 더 길게 자면 `no >= 2` 루프 시작(`SyncSender.java:205`)의 `remaining < MIN_START`(5s) 검사는 통과하고 `206`행 `limits.acquire`의 `requirePoolRoom`에서 `QueryTimeoutException`(503)으로 빠질 수 있는 좁은 경계 창(`[13s, 13s + sleep 오차]`)이 이론상 남는다. `205`행에 `!attempts.isEmpty() && !roomForNext(start, Duration.ZERO)` 가드를 두면 실제 풀·잠금 경합 503을 건드리지 않고 sleep 오차까지 닫을 수 있으나, 1라운드에서 제시한 수정 선택지("또는 sleep 전 동일 조건")대로 구현되었고 `[5s, 13s)` 8초 구간의 결정적 회귀는 완전히 닫혔으므로 차단 사유로 보지 않는다.

---

## 확인한 것과 확인하지 못한 것
- **확인한 것**:
  - `git diff 68c7767 10fb394` 전체 diff 및 `SyncSender`·`DeliveryLedger`·`RateLimiter`·`SubscriptionService`·`SubscriptionController`·계약 §6·`DeliveryLedgerTest`·`SubscriptionFlowIntegrationTest` 소스.
  - `issue-6-budget-fix` 워크트리(`10fb394`, clean)에서 `./gradlew.bat test --tests "kr.gongchae.bell.alerts.*" --rerun-tasks` 직접 실행: **47건 통과**(0 failure, 0 error, 0 skipped — `AlertsDisabledIntegrationTest` 1, `AlertsSecurityTest` 5, `DeliveryLedgerTest` 19, `ErasureJournalTest` 4, `SubscriptionFlowIntegrationTest` 13, `TransportTest` 5).
- **확인하지 못한 것**:
  - 임시 소스 변이 실행(읽기 전용 지침에 따라 코드/수식 추적으로 확인), 전체 Gradle `check`/`bootJar`/CI 재실행, 실제 Resend·Discord·PostgreSQL 풀 경합 부하 실측.
