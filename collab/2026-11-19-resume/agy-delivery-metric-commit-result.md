# #7 성공 지연 지표 커밋 경계 확인 및 수정 결과 (`agy`)

- **작성자**: `agy` (단독 작성자)
- **요청 근거**: `collab/2026-11-19-resume/agy-delivery-metric-commit-request.md`
- **작업 위치 / 브랜치**: `C:/Users/lcj/orca/workspaces/my-project/issue-7-metric-commit` / `fix/#7-metric-commit`
- **기준 `origin/main` SHA**: `c4465f5f3029550168337408f77e7ee6960e18d8` (`Merge pull request #27 from Lcjam/docs/#17-isolated-restore`)
- **수정 커밋 SHA**: `94f816e8784933cfa97b9f5c5083c446e90728ac` (`fix: defer delivery success metrics until SENT transaction commit (Refs #7)`)

---

## 1. 실제 실행 재현 결과 (관찰 확인)

- **원인 확인 (`AlertDispatcher.dispatchClaim` → `dispatchInTransaction`)**:
  - `AlertDispatcher.dispatchClaim(Claim)`은 외곽 트랜잭션 `tx.execute(status -> dispatchInTransaction(claim))` 안에서 실행됩니다.
  - 공급자 HTTP가 `SendOutcome.SUCCESS`를 반환하고 `DeliveryLedger.finishAsync(claim, true, null, false)`가 `UPDATE notification SET status = 'SENT'`를 실행한 직후에도 외곽 TX A는 아직 커밋 전입니다(`finishAsync`에는 자체 커밋 경계가 없음).
  - 기존 코드(`AlertDispatcher.java:190-196`)는 `ledger.finishAsync(...)`가 `true`를 돌려주는 즉시 `metrics.recordDeliveryLatency(...)`, `metrics.recordCollectionToDeliveryLatency(...)`, `metrics.recordDuplicateRisk(...)`를 호출하고 있었습니다.
- **실행 재현 (`AlertDeliveryIntegrationTest.successMetricsDoNotIncrementWhenOuterSentCommitFailsAndIncrementOnceCommitted`)**:
  - 격리 Testcontainers PostgreSQL 17과 정상 `AlertWorker.runOnce()` → `AlertDispatcher.dispatchClaim()` 경로에서 1차 `HTTP_5XX`(`anyUnknown = true`, `RETRY` 전환) 뒤, 2차 시도 직전에 `notification`의 `NEW.status = 'SENT'`에만 걸리는 `AFTER UPDATE ... DEFERRABLE INITIALLY DEFERRED` 제약 트리거(`trg_test_fail_sent_on_commit`)를 설치해 R(`REQUIRES_NEW`) 커밋과 가짜 Discord `SUCCESS` 응답 뒤 **외곽 TX A `COMMIT` 시점만 실패(`DEFERRED_SENT_COMMIT_FAIL`)**하게 만들었습니다.
  - **수정 전 실행 결과**: HTTP 전송(`SENT_WEBHOOKS` 1건), `R` 예약 영속화(`attempt_no = 2, outcome IS NULL`), `notification_item` 보존, `notification.status = 'SENDING'` 롤백, `alerts.delivery.attempts{kind="DISCORD_NEW",outcome="SUCCESS"}` `+1`까지는 의도와 같았으나, DB가 `SENT`가 아닌데도 **`AlertDeliveryIntegrationTest.java:1164`에서 `alerts.delivery.latency{kind="DISCORD_NEW"}` 증가량이 `expected: 0L but was: 1L`로 실패**하며 먼저 증가함이 재현됐습니다.

---

## 2. 최소 수정 내용

1. **[`src/main/java/kr/gongchae/bell/alerts/AlertDispatcher.java`](../../src/main/java/kr/gongchae/bell/alerts/AlertDispatcher.java)**
   - `recordSuccessAfterCommit(AsyncJob job, List<PostingSummary> newPostings, OffsetDateTime finishedAt)` 헬퍼를 추가했습니다.
   - `SendOutcome.SUCCESS`로 `ledger.finishAsync(claim, true, null, false)`가 `true`를 반환했을 때 `alerts.delivery.latency`, `alerts.delivery.collection.latency`(대상 `NEW` 공고별), `alerts.delivery.duplicate.risk`(`job.anyUnknown()`) 기록을 `TransactionSynchronizationManager.registerSynchronization`의 `afterCommit()` 훅으로 옮겨 **외곽 `SENT` 트랜잭션이 실제 DB 커밋을 마친 뒤에만** 기록되도록 수정했습니다(활성 동기화가 없는 호출 컨텍스트에서는 즉시 기록).
   - **유지된 불변식**: `alerts.delivery.attempts` (`recordAttemptOutcome`)는 실제 실행된 HTTP/전송 시도 결과 단위로 유지해 성공 지표와 정의를 섞지 않았습니다. `R`(`REQUIRES_NEW`), HTTP 호출, `UNKNOWN`/`UNCERTAIN`·`notification_item` 보존, Discord `404`의 별도 짧은 `TX3`(`disableWebhook404`), `subscription FOR SHARE` → `R` → `notification FOR UPDATE` 잠금 순서는 변경하지 않았습니다.
2. **[`src/test/java/kr/gongchae/bell/alerts/AlertDeliveryIntegrationTest.java`](../../src/test/java/kr/gongchae/bell/alerts/AlertDeliveryIntegrationTest.java)**
   - 회귀 테스트 `successMetricsDoNotIncrementWhenOuterSentCommitFailsAndIncrementOnceCommitted()`를 추가해 ① 1차 `HTTP_5XX` → ② `deferred` 제약 트리거로 `SENT` 커밋 실패 시 `SENT_WEBHOOKS=1`·`R(attempt 2, outcome IS NULL)`·`notification_item` 보존·`notification.status='SENDING'`·`attempts{outcome=SUCCESS}=+1`과 동시에 **`alerts.delivery.latency` / `alerts.delivery.collection.latency` / `alerts.delivery.duplicate.risk` 증가량 `0`** 확인 → ③ 트리거 제거 후 복구·3차 정상 커밋 시 DB `SENT` 전환 및 위 세 성공 지표가 각각 **정확히 `1`회만** 증가함을 검증했습니다.
3. **[`docs/ops/alerts.md`](../../docs/ops/alerts.md)**
   - `#7` 노출 지표 목록에 성공 지연 타이머(`alerts_delivery_latency_seconds`, `alerts_delivery_collection_latency_seconds`)와 `alerts_delivery_duplicate_risk_total`이 `SENT` 외곽 트랜잭션 커밋 확정(`afterCommit`) 후에만 기록되며, `alerts_delivery_attempts_total`은 실행된 발송 시도 결과 기준으로 기록됨을 1줄 명시했습니다.

---

## 3. 검증 결과 및 한계

- **전체 Gradle 테스트 (`gradle test --console=plain`, 최종 수정 후 실행)**:
  - **169개 테스트 전체 통과** (`TOTAL: tests=169, failures=0, errors=0, skipped=0`, `BUILD SUCCESSFUL in 2m 29s`), 이 중 `AlertDeliveryIntegrationTest` **10건 통과**(신규 커밋 실패 회귀 테스트 + 기존 `collectionToDeliveryLatencyTracksNewPostingsAcrossPreBundleWaitAndMixedKinds` 혼합 `NEW`/`DEADLINE` 지연 회귀 포함).
- **시크릿 스캔**: `gitleaks dir --verbose src/main/java/kr/gongchae/bell/alerts src/test/java/kr/gongchae/bell/alerts docs/ops/alerts.md` (`no leaks found`).
- **범위 및 한계**:
  - `afterCommit()`은 Spring `PlatformTransactionManager`가 DB `connection.commit()` 성공을 반환한 직후 메모리상 `MeterRegistry`에 기록합니다. DB `COMMIT` 성공 뒤 JVM이 즉시 강제 종료되면 해당 인스턴스의 스크랩 전 인메모리 카운터/타이머 값은 다음 프로세스에 이어지지 않는다는 Prometheus 스크랩 특성 한계는 기존과 동일합니다(DB 영속 게이지 `alerts.notification.sent.last24h`·`duplicate.risk.last24h`는 DB 기준으로 복구 후에도 유지).
  - 실제 외부 provider(Resend/Discord), 운영 DB, 운영 키, backup·monitoring 작성자 브랜치에는 접근·변경하지 않았습니다.
