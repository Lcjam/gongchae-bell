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

---

## 4. 2라운드 수정: 운영 env 전달·`NEW` 수집 반영→발송 지연 관측·자격 결정 원문 보존·`main` 통합 (2026-11-19)

- **배정 요청**: `collab/2026-11-18-implementation/agy-alert-observability-fixes-request.md` (근거: `pr-23-sol.md`, `pr-23-codex.md` P2-2·P2-3, 자격 결정 링크 보존)
- **이전 1라운드 보존**: `83dcf9cf900d58741d1890eea1cd22cb594b706d` (`FOR KEY SHARE` 부모 잠금 및 경합 회귀) · 최종 1라운드 문서 HEAD `b50d3d044f2502a6c8568ad4b15284c589758add`
- **2라운드 코드·설정·문서 커밋 SHA**: `cc0d6dfac61b7490a19c8b2e32f1e22feb9be2cd` (`fix: pass worker env in prod compose, track NEW collection-to-delivery latency, preserve eligibility docs (Refs #7)`)
- **`origin/main` 통합 커밋 SHA**: `a37b7516ef58febd1f3c62391d49c1224744b0aa` (`Merge remote-tracking branch 'origin/main' into feat/#7-alert-delivery` — PR #22 `52c172c` · PR #21 `836a3c0` · PR #24 `517eb6b` 포함)
- **코드·통합 커밋 원격 CI (`a37b7516ef58febd1f3c62391d49c1224744b0aa`)**:
  - `CI` [Run #44500207465](https://github.com/Lcjam/gongchae-bell/actions/runs/44500207465): `test` · `deploy-verify` · `backup-verify` · `secrets` 전 잡 `success`
  - `Monitoring` [Run #44500207540](https://github.com/Lcjam/gongchae-bell/actions/runs/44500207540): `rules` · `compose-integration` · `alert-e2e` 전 잡 `success`
- **최종 문서 HEAD 안내**: 이 섹션이 반영된 문서 커밋이 PR #23의 최종 HEAD이며, 푸시 후 최종 HEAD의 CI·Monitoring 통과 및 `MERGEABLE` 상태를 확인해 PR #23 본문과 root 사본을 동기화합니다.

### 1) 워커 운영 env 3종 prod Compose 전달 (P2-2)
- `compose.prod.yml`의 `x-app-common.environment`에 `ALERTS_WORKER_CONCURRENCY: ${ALERTS_WORKER_CONCURRENCY:-2}`, `ALERTS_BATCH_SIZE: ${ALERTS_BATCH_SIZE:-20}`, `ALERTS_SCHEDULER_ENABLED: ${ALERTS_SCHEDULER_ENABLED:-true}`를 추가해 `app-blue`·`app-green` 양 색 컨테이너에 모두 전달되도록 연결했습니다.
- `deploy/.env.prod.example`에는 키 이름(`ALERTS_WORKER_CONCURRENCY=`, `ALERTS_BATCH_SIZE=`, `ALERTS_SCHEDULER_ENABLED=`)만 추가했고, `docs/runbook.md` §2 표에 기본값(`2`·`20`·`true`, 동시성 상한 `4`)을 맞춰 적었습니다.
- `.github/workflows/ci.yml` `deploy-verify` 잡에 `ALERTS_WORKER_CONCURRENCY=1 ALERTS_BATCH_SIZE=3 ALERTS_SCHEDULER_ENABLED=false` 비기본 값으로 `app-blue`·`app-green` merged JSON을 검사하는 검증을 추가했습니다.
- `AlertDispatchPropertiesTest`(`ApplicationContextRunner` + `ConfigDataApplicationContextInitializer`)로 실제 `application.yml`에서 기본값(`2`·`20`·`true`, `AlertScheduler` 빈 등록), 비기본값(`1`·`3`·`false`, `AlertScheduler` 빈 미등록), 상한 초과(`5` → `ALERTS_WORKER_CONCURRENCY_TOO_HIGH`)를 검증했습니다.

### 2) `NEW` 공고 수집 반영→발송 지연 관측 및 지표 정의 명확화 (P2-3)
- **수집 반영 시각 전달 (`AlertMatcher`, `AlertDispatcher`)**:
  - `AlertMatcher.PostingSummary`에 `OffsetDateTime firstSeenAt`을 추가하고 `findStillEligible()` 조회 시 `p.first_seen_at`을 함께 읽습니다(payload 직렬화·해시 계산에는 쓰지 않으며 기존 4인자 생성자 호환 유지).
  - `AlertDispatcher`가 `SendOutcome.SUCCESS`로 `ledger.finishAsync()`를 커밋하면 `alerts.delivery.latency`를 작업 단위로 기록한 뒤, `rechecked.newPostings()`의 각 `NEW` 항목에 대해 `alerts.delivery.collection.latency`를 기록합니다.
- **지표별 정의·집계 단위·대상·분모 (`AlertsMetrics`, `docs/ops/alerts.md`)**:
  - `alerts_delivery_latency_seconds{kind}`: **작업 생성(`notification.created_at`) → 발송 성공(`SENT`) 완료 지연**(타이머). 집계 단위·분모는 성공 발송 비동기 작업 1건, 대상 `kind`는 `EMAIL_DIGEST`·`DISCORD_NEW`·`DISCORD_DEADLINE`입니다(`EMAIL_DIGEST`는 08:00 KST/만회 묶음 생성 이후 구간만 측정).
  - `alerts_delivery_collection_latency_seconds{kind}`: **공고 수집 반영(`posting.first_seen_at`) → 발송 성공(`SENT`) 완료 지연**(PRD G7 타이머). 집계 단위·분모는 성공 발송된 `NEW` 공고-구독 항목 1건(`notification_item.kind = 'NEW'`), 대상 `kind`는 `NEW` 항목을 포함한 `DISCORD_NEW`·`EMAIL_DIGEST`입니다(`DEADLINE` 전용 작업과 `NEW`+`DEADLINE` 혼합·동일 `sn`의 `DEADLINE` 항목은 제외).
  - `alerts_delivery_delay_max_seconds`: 미완료 비동기 작업(`PENDING`·`RETRY`·`SENDING`, 비동기 3종)의 `notification.created_at` 기준 최대 대기 경과 초(게이지, 없으면 `0`).
- **회귀 시험 (`AlertDeliveryIntegrationTest.collectionToDeliveryLatencyTracksNewPostingsAcrossPreBundleWaitAndMixedKinds`)**:
  - 합성 시각(`07:05 KST` 수집 `307032` = `NEW`+`DEADLINE`, `07:30 KST` 수집 `307031` = `NEW` 전용, backfill `307030` = `DEADLINE` 전용, `08:00 KST` `EMAIL_DIGEST` 생성, `08:00:05 KST` 발송)에서 `alerts.delivery.latency{kind="EMAIL_DIGEST"}`는 작업 2건 합계 `10s`(`5s`+`5s`), `alerts.delivery.collection.latency{kind="EMAIL_DIGEST"}`는 `emailMixed`의 `NEW` 항목 2건만 계산해 `3305s + 1805s = 5110s`(`max >= 3305s`)로 기록되며 `307032`의 `DEADLINE` 중복·`307030`·`emailDeadlineOnly`가 섞이지 않음을 검증했습니다. `DISCORD_NEW`(`4s`·`2s`)와 `DISCORD_DEADLINE`(`alerts.delivery.latency` `6s`, `collection.latency` `0건`), `/actuator/prometheus` 노출도 함께 확인했습니다.

### 3) 자격 결정 파일 보존 및 최신 `origin/main`(PR #21) 접점 통합
- root `collab/2026-11-17-m2-readiness/`의 확정 결정 `03-codex-alert-eligibility-decision.md`와 직접 참조 원문 7건(`01-*`, `02-*`, `agy-issue7-handoff.md`)을 내용 수정 없이 동일 SHA-256으로 브랜치 트리에 보존해 `docs/ops/alerts.md` 상대 링크가 저장소 안에서 열리도록 했습니다.
- PR #21이 `main`(`836a3c0`)에 병합됨에 따라 `origin/main`(`517eb6b`)을 통합하고, `application.yml`의 `heartbeat.collector-url`·`heartbeat.digest-url` 중복 키를 단일 `heartbeat:` 블록으로 정리했습니다.
- 중복 구현이던 `DigestHeartbeat` 클래스를 제거하고, `AlertsConfiguration`에서 공용 `kr.gongchae.bell.ops.Heartbeat` 빈(`digestHeartbeat`)을 생성해 `AlertBatchGenerator`가 `@Qualifier("digestHeartbeat")`로 주입받도록 통합했습니다(`docs/ops/monitoring.md:135`·`RegularCollector` 계약과 일치). `docs/implementation-status.md`의 운영 기반 병합 상태를 최신 `main`에 맞췄습니다.

### 4) 2라운드 로컬·원격 검증 요약 (`a37b7516`)
- `.\gradlew.bat --no-daemon check bootJar`: **25 suites, 168 tests, 0 failures, 0 errors, 0 skipped** (`BUILD SUCCESSFUL in 2m 49s`; `AlertDeliveryIntegrationTest` 9/9, `AlertDispatchPropertiesTest` 3/3, `MonitoringContractIntegrationTest`·`HeartbeatTest` 포함)
- `docker compose -f compose.prod.yml -f compose.monitoring.yml` config 검증: `.env.prod.example` 기본값(`2`·`20`·`true`) 및 합성 비기본값(`1`·`3`·`false`) 모두 `app-blue`·`app-green` 양 색 환경에 도달 확인
- `deploy/tests/deploy-test.sh` (`ubuntu:24.04`): `ALL_DEPLOY_TESTS_PASSED` (`[17/17]`)
- `promtool check config` / `promtool test rules gongchae.test.yml` (`prom/prometheus:v3.16.0`): `SUCCESS`
- `gitleaks dir . --verbose`: `no leaks found`
- GitHub Actions (`a37b7516`): `CI` [44500207465](https://github.com/Lcjam/gongchae-bell/actions/runs/44500207465) 전 4잡 `success`, `Monitoring` [44500207540](https://github.com/Lcjam/gongchae-bell/actions/runs/44500207540) 전 3잡 `success`
