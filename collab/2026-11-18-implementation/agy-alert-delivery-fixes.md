# PR #23 (`#7` 알림 발송) 1차 검수 수정 결과 (`agy`)

- **단독 작성자**: `agy`
- **대상 브랜치 / Worktree**: `feat/#7-alert-delivery` (`C:/Users/lcj/orca/workspaces/my-project/issue-7-alert-delivery`)
- **직전 제출 HEAD**: `0471389cd4b4186d8f0686602404ef6cbdc4092a` (`1b7c23cd` + 결과 보고서)
- **코드·회귀 수정 커밋 SHA**: `83dcf9cf900d58741d1890eea1cd22cb594b706d` (`fix: lock parent subscription FOR KEY SHARE before async outbox inserts (Refs #7)`)
- **Draft PR**: [#23](https://github.com/Lcjam/gongchae-bell/pull/23) (`main` 머지 및 `#7` close 미수행)
- **코드 수정 커밋 CI Run**: [CI Run #44289745420](https://github.com/Lcjam/gongchae-bell/actions/runs/44289745420) (`test` · `deploy-verify` · `backup-verify` · `secrets` 전 Job `success`)
- **최종 HEAD 안내**: 이 수정 보고서 커밋(`docs: record PR #23 outbox parent lock fix and CI (Refs #7)`)이 PR #23의 최종 HEAD가 되며, 푸시 후 최종 HEAD의 4개 잡 CI 및 `mergeable=MERGEABLE` 상태를 확인해 PR #23 본문에 갱신합니다.

---

## 1. 리뷰 지적사항(root Codex P2) 수정 내역

### `AlertOutbox` 자식 생성 전 부모 `FOR KEY SHARE` 직렬화
- **원인**: `AlertOutbox.createJobWithItems`가 명시적 부모 잠금 없이 `INSERT INTO notification ... SELECT ... FROM subscription s WHERE s.id = :subId AND s.status = 'ACTIVE'`를 실행해, PostgreSQL `READ COMMITTED`에서 동시 해지(`DELETE FROM subscription`)가 진행·커밋될 때 `SELECT` 스냅샷과 FK 검사(`RI_FKey_check_ins`) 사이 경합으로 `SQLSTATE 23503 (notification_subscription_id_fkey)`가 발생해 외곽 `PostingIngestor` 또는 일일 묶음 TX 전체가 롤백될 수 있었습니다.
- **수정 (`src/main/java/kr/gongchae/bell/alerts/AlertOutbox.java`)**:
  - `createJobWithItems` 내부에서 자식 `notification` INSERT 직전에 `#6 계약 §3`의 `recoverEmail`과 동일한 부모 최소 잠금 **`SELECT 1 FROM subscription WHERE id = :subId AND status = 'ACTIVE' FOR KEY SHARE`**를 먼저 획득하도록 수정했습니다.
  - 동시 해지가 먼저 `FOR UPDATE` + `DELETE`를 잡고 커밋하면 `FOR KEY SHARE`가 잠금 해제 뒤 재평가에서 0행을 받아 해당 구독만 건너뛰고(`return false`), 외곽 정기 수집(`PostingIngestor`) 공고 반영과 다른 구독 outbox·일일 묶음을 보존합니다.
  - 반대로 생성이 `FOR KEY SHARE`를 먼저 잡으면 동시 해지(`FOR UPDATE`)가 생성 TX 커밋을 기다린 뒤 `DELETE FROM subscription`과 `ON DELETE CASCADE`로 새 알림 작업·항목까지 삭제합니다.
  - `FOR KEY SHARE`는 `TX2`의 `FOR SHARE`와 호환되므로 장시간 배타 잠금이나 새 재선점 정책 없이 기존 계약(R 전 child DML 금지, 잠금 순서 `subscription → notification → item`)을 그대로 유지합니다.

---

## 2. 회귀 시험 및 CI 검증 결과

### 1) 집중 회귀 (`AlertDeliveryIntegrationTest.outboxAndDigestSerializeWithConcurrentUnsubscribeWithoutRollback`)
- **시험 환경**: 격리 Testcontainers `postgres:17`, 실제 `PostingIngestor.applyRegular` + `AlertBatchGenerator.generateEmailDigest` + `SubscriptionService.unsubscribe`, 대역 `WebhookTransport`/`MailTransport`, `Future.get(8s)` 관측 상한 및 `finally` `rollback()` 정리.
- **검증 내용**:
  1. **`PostingIngestor.applyRegular` 중간 해지 커밋 경합**: 별도 연결이 `discDeleting`을 `FOR UPDATE` + `DELETE`로 잡은 상태에서 `applyRegular`가 `pg_stat_activity` `Lock` 대기까지 도달한 뒤 해지 커밋 → FK 23503 미발생, 공고(`307032`) `OPEN` 보존, `discSurvivor` `DISCORD_NEW` 생성 및 웹훅 1회 발송, `discDeleting` 0행·HTTP 0회.
  2. **`AlertBatchGenerator.generateEmailDigest` 중간 해지 커밋 경합**: 동일 패턴으로 `emailDeleting` 해지 중 `generateEmailDigest`가 실패 없이 1건(`emailSurvivor`) 생성 완료, `emailDeleting` HTTP 0회.
  3. **생성 선행(`FOR KEY SHARE` 선점) vs 동시 `SubscriptionService.unsubscribe`**: 첫 구독에 `FOR KEY SHARE`가 잡힌 상태에서 `unsubscribe`가 대기하다가 생성 TX 커밋 후 `UNSUBSCRIBED`로 완료되어 새로 생성된 `notification`·`notification_item`이 cascade 삭제되고 워커 실행 시 HTTP 0회 확인.
- **공유 DB 정리**: `AlertDeliveryIntegrationTest`에 `@AfterEach` DB `TRUNCATE` 정리 추가(`FoundationIntegrationTest` 등 동일 JVM 공유 컨테이너 테스트 간 간섭 제거).

### 2) 전체 빌드·테스트·스캔·CI
- `.\gradlew.bat --no-daemon check bootJar` (`83dcf9cf`): **158 tests, 0 failures, 0 errors, 0 skipped** (`BUILD SUCCESSFUL in 2m 47s`; `AlertDeliveryIntegrationTest` 8/8, `AlertPayloadBuilderTest` 2/2, `DeliveryLedgerTest` 20/20, `FoundationIntegrationTest` 4/4 포함)
- `gitleaks dir . --verbose`: `no leaks found`
- GitHub Actions ARM64 CI (`83dcf9cf900d58741d1890eea1cd22cb594b706d`): [CI Run #44289745420](https://github.com/Lcjam/gongchae-bell/actions/runs/44289745420) — `test` · `deploy-verify` · `backup-verify` · `secrets` 4개 Job 전 항목 `success`

---

## 3. 남은 실제 환경 검증 범위 (후속 운영 게이트)

- 실제 Resend·Discord 웹훅 발송, 발신 도메인 SPF/DKIM/DMARC·열람/클릭 추적 비활성화·원클릭 해지 실동작 확인
- `#6` 실운영 개인정보 처리방침 7개 필수 값 확정 및 공개 HTTPS `Origin` 관측
- `#15` Grafana 발송 패널·경보 규칙·Healthchecks `gongchae-digest` URL 연결 및 실서버 blue/green 적용·운영 관측
