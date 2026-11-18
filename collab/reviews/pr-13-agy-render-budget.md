# PR #13 응답 기준 시각 수정 독립 검수 (`agy`)

- **검수자**: `agy` (비작성자 독립 검수, 읽기 전용)
- **PR / 브랜치**: [#13](https://github.com/Lcjam/gongchae-bell/pull/13) `feat/#6-subscriptions` (Draft)
- **기준선 (Baseline)**: `7cb0068e1704247111e4d325faefb6a4f1b584c5`
- **주요 검수 대상 불변 SHA**: `919cf703f047e2e2967d3332866b421d34f5033c` (코드 커밋 `d474cf7fadf91171b10d566e2fb224dc4910e03a` 포함)
- **검수 중 추가 제출된 후속 SHA (차분 추가 확인)**:
  - `c005ee11a36ea3517d2eb0e4d0d0a05bff3449c1` (`fix: answer a pool timeout with the fixed 503 and end the send job (#6)`)
  - `97c7415ffb67ff47fc2643ff8b1323716ce78767` (`chore: merge main (#14 deploy) into PR #13 (#6)`, 현 PR #13 원격 head)
- **검수 기준 문서**: `collab/reviews/pr-13-codex-c4.md`, `collab/2026-11-18-implementation/claude-subscriptions-render-budget.md`

---

## 판정

**수용 (blocking P0/P1/P2 결함 없음)** — 불변 SHA `919cf703f047e2e2967d3332866b421d34f5033c` 기준으로 Codex C4 재검수 P2(응답 후반 `collect_run` 기준 시각 조회의 동기 요청 예산 편입)가 정상 신청·입력 오류·관리 링크 재발송 MVC 경로 전반에 일관되게 해결됐습니다.

검수 중 추가로 올라온 풀 오류 응답 커밋(`c005ee11`) 및 `main` 머지(`97c7415f`) 차분도 읽기 전용으로 추가 확인했으며, 기존 예산·정리 계약을 훼손하지 않습니다.

---

## 직접 검증 범위와 결과

### 1) 불변 SHA `919cf703f047e2e2967d3332866b421d34f5033c` (정적 대조 + 격리 실행 + CI)
- **정적 코드 대조 (`git diff 7cb0068e..919cf703`, `git show`)**:
  - `SubscriptionController.budgeted(HttpServletRequest)` (`src/main/java/kr/gongchae/bell/alerts/SubscriptionController.java:325-330`)가 `service.start()`로 25초 단조 예산을 시작하며 요청 속성 `FreshnessModelInterceptor.STATUS_SOURCE`에 `() -> service.freshness(start)`를 바인딩합니다.
  - 적용 경로는 시작 시각 예산을 쓰는 세 POST 전체입니다: `POST /subscriptions/email`(`L77`), `POST /subscriptions/discord`(`L104`), `POST /subscriptions/recover/email`(`L268`, `service.recoverEmail(email, ip(request), start)`).
  - **정상 신청 / 재발송 / 입력 오류 / 횟수 제한**:
    - 정상 신청(`200`), 관리 링크 재발송(`200`), `InvalidInput` 입력 오류(`400`, `newPage(...)` 또는 `notice(...)`), `RATE_LIMITED`(`429`, `limited(...)`) 모두 컨트롤러 정상 반환 경로로 `FreshnessModelInterceptor.postHandle`(`src/main/java/kr/gongchae/bell/catalog/FreshnessModelInterceptor.java:45-51`)을 통과하며 바인딩된 `STATUS_SOURCE`를 사용합니다.
    - `ALERTS_ENABLED=false` 비활성 분기는 `budgeted(request)` 호출 전 `503 unavailable(model)`을 반환하므로 기존 비활성 경로와 동일하게 동작합니다.
  - **남은 예산 / 풀 상한 / statement·lock 제한 (`SubscriptionService.freshness(long start)`, `src/main/java/kr/gongchae/bell/alerts/SubscriptionService.java:100-111`)**:
    - 연결 진입 전 `limits.requirePoolRoom(() -> sender.remaining(start).minus(SyncSender.CLEANUP))`로 남은 시간이 `poolWait(5s) + CLEANUP(2s) = 7s` 이상인지 확인하며, `connection-timeout=0`(무제한 풀) 설정에서도 `Long.MAX_VALUE` 비교로 즉시 차단됩니다.
    - `tx.execute` 진입 직후 `limits.boundWaits(sender.cleanupWaits(start).get())`로 `lock_timeout`과 `statement_timeout`을 `min(2s, remaining(start))`로 제한(1ms 미만 시 즉시 `QueryTimeoutException`)한 뒤 `freshness.currentStatus()`를 1회 조회합니다.
    - 예산 부족·풀 시간 초과(`TransactionException`)·잠금/쿼리 시간 초과(`DataAccessException`) 시 `ALERT_FRESHNESS_UNAVAILABLE <예외 단순 클래스명>` 경고 로그만 남기고 `FreshnessService.FreshnessStatus.unavailable()`("일시적으로 수집 시각을 확인할 수 없습니다")를 반환합니다.
  - **발송·상태 보존**: `freshness(start)`는 컨트롤러의 DB 커밋과 `deliver(...)` 완료 이후 MVC 뷰 모델 보강 단계(`postHandle`)에서만 호출되므로, 기준 시각 조회 실패나 예산 부족이 메일 재발송·트랜잭션 롤백·상태 반전을 일으키지 않습니다.
  - **타 화면 호환성**:
    - `STATUS_SOURCE`가 없는 기존 화면(`CatalogController` 목록·상세, `GET /subscriptions/*`, `/privacy`, `POST /subscriptions/confirm`, `POST /subscriptions/manage`, `POST /subscriptions/unsubscribe`, `POST /subscriptions/recover/discord`)은 모두 `FreshnessModelInterceptor`의 기본 `freshnessService.currentStatusSafe()` 경로를 사용하며, 이미 모델에 `"freshness"`를 채우는 404 예외 처리기(`CatalogController.handleNotFound`, `CatalogController.java:171-181`) 등에서만 인터셉터의 중복 조회를 건너뜁니다(`FreshnessModelInterceptor.java:45`).
    - `POST /subscriptions/unsubscribe/one-click`(`ResponseEntity<String>`)은 `modelAndView == null`, `redirect:`/`forward:` 뷰는 조기 반환으로 영향이 없습니다.
  - **회귀 시험 검증 (`SubscriptionFlowIntegrationTest.theFooterCollectionTimeIsReadInsideTheRequestBudgetWhileCollectRunIsLocked`)**:
    - 별도 연결에서 `LOCK TABLE collect_run IN ACCESS EXCLUSIVE MODE` 유지 하에 정상 이메일 신청(`200`), 잘못된 지역 입력(`400`), 이메일 재발송(`200`) 모두 `within(...)`(`Future.get(8s)`, 5초 미만 단언)과 `"일시적으로 수집 시각을 확인할 수 없습니다"` 렌더링을 검증합니다.
    - `SKIP_AFTER_START=14s`(잔여 11s -> 조건 조회 `waitLimit=4s < poolWait 5s`) 시 `503` 즉시 종료, `MAILS` 1건 유지(중복 발송 없음), `finally`의 `holder.rollback()` 선행 해제 후 executor 정리, 잠금 해제 후 정상 기준 시각 복귀까지 모두 확인했습니다.
- **격리 임시 디렉터리 직접 재실행 (`919cf703f047e2e2967d3332866b421d34f5033c`)**:
  - `git archive`로 임시 경로에 추출해 `./gradlew --no-daemon test --tests kr.gongchae.bell.alerts.SubscriptionFlowIntegrationTest --tests kr.gongchae.bell.alerts.DeliveryLedgerTest` 실행(종료 후 임시 경로 삭제, 원본·타 worktree 무변경).
  - 결과: `DeliveryLedgerTest` **19 tests, 0 failures, 0 errors, 0 skipped** (`22.407s`), `SubscriptionFlowIntegrationTest` **14 tests, 0 failures, 0 errors, 0 skipped** (`35.346s`; `theFooterCollectionTimeIsReadInsideTheRequestBudgetWhileCollectRunIsLocked`: `6.102s`, `aBoundedWaitThatRunsOutAnswersBusyInsideTheBudgetAndKeepsNothingOfTheSignup`: `8.039s`, `conditionLookupUsesThePostBudgetAndInvalidInputReusesItsOptions`: `0.052s`).
- **GitHub Actions CI (`919cf703f047e2e2967d3332866b421d34f5033c`)**:
  - [CI Run #44275408120](https://github.com/Lcjam/gongchae-bell/actions/runs/44275408120): `test`(`check bootJar`, ARM64)·`secrets`(`gitleaks`) 모두 `success`.

### 2) 검수 중 추가 제출된 후속 변경 (`919cf703..97c7415f`, 읽기 전용 차분 + CI 확인)
- **코드 커밋 `c005ee11a36ea3517d2eb0e4d0d0a05bff3449c1`**:
  - `SubscriptionController.busy(...)` 대상에 `CannotCreateTransactionException`을 추가하고 클래스명 전용 로그(`ALERT_REQUEST_BUSY <예외클래스> cause=<근본원인클래스>`)와 함께 고정 `503` 안내를 반환하도록 보강했습니다(이전에는 Hikari 5초 풀 타임아웃 시 500 발생 가능).
  - `SyncSender.deliverMail(...)` 시도 루프 catch에 `CannotCreateTransactionException`을 추가해 풀 타임아웃으로 시도 트랜잭션(`A` 또는 내부 `R`)을 열지 못했을 때 `end(claim, "DEADLINE", start)`로 작업을 종결해 `SENDING` 잔류를 막습니다(풀 고갈이 계속되면 `end`의 예외를 `SubscriptionService.deliver`가 잡아 요청은 고정 응답 유지).
  - `DeliveryLedgerTest.aConnectionThePoolCannotHandOverEndsTheJobAsDeadline`과 `SubscriptionFlowIntegrationTest.aFullPoolAnswersBusyWithinItsBoundAndWritesNothing` 회귀가 추가됐습니다.
- **머지 커밋 `97c7415ffb67ff47fc2643ff8b1323716ce78767`**:
  - `main`(`be30f69`, PR #20 배포 구성) 머지 및 `src/main/resources/application.yml` 충돌 해소. `spring.datasource.hikari.connection-timeout: 5000`은 그대로 보존되고 `forward-headers-strategy: ${SERVER_FORWARD_HEADERS_STRATEGY:native}`가 반영됐습니다.
- **GitHub Actions CI (`97c7415ffb67ff47fc2643ff8b1323716ce78767`)**:
  - [CI Run #44280281080](https://github.com/Lcjam/gongchae-bell/actions/runs/44280281080): `test`(`check bootJar`, ARM64)·`deploy-verify`·`secrets` 전 항목 `success`.

---

## 경미 관찰 사항 (참고, 차단 아님)

- **`503 busy()` 응답 시 하단 푸터 문구 Fallback (`SubscriptionController.java:315-319`, `fragments/layout.html:30`)**:
  - `SubscriptionController.busy(...)`가 `@ExceptionHandler`로 `"subscriptions/notice"`를 반환할 때 Spring MVC는 `HandlerInterceptor.postHandle`을 호출하지 않습니다. 따라서 DB/풀 부하 상황에서 `collect_run` 조회가 건너뛰어져 예산과 안전성에는 문제가 없으나, 모델에 `freshness`와 `lastCollected`가 모두 비어 있어 `fragments/layout.html:30`이 `"일시적으로 수집 시각을 확인할 수 없습니다"` 대신 기본값 `"아직 완전 수집 기록이 없습니다"`를 표시합니다. `7cb0068e`부터 있던 기존 동작이며 기능·예산 결함은 아닙니다(필요 시 추후 `busy()` 모델에 `FreshnessStatus.unavailable()` 1줄 추가 검토 가능).

---

## 미검증 범위

- `c005ee11` / `97c7415f` 델타는 로컬 격리 재실행 대신 읽기 전용 `git diff`와 GitHub Actions CI Run `#44280281080` 통과로 확인했습니다.
- 실제 Resend 메일 발송, 실제 Discord 웹훅 호출, 공개 HTTPS 도메인·방침 고지·운영 DB/VM 검증은 PR #13 실사용 전 게이트 범위로 남습니다.
