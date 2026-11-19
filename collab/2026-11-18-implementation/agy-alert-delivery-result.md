# #7 공고 매칭·outbox·일일 묶음·비동기 발송 구현 결과 (`agy`)

- **단독 작성자**: `agy`
- **대상 브랜치 / Worktree**: `feat/#7-alert-delivery` (`C:/Users/lcj/orca/workspaces/my-project/issue-7-alert-delivery`)
- **기준 `main` SHA**: `ed3f28f38623afeccd41006548a9e4f54ed0e301` (PR #13 병합 완료 기준)
- **코드·테스트·문서 구현 커밋 SHA**: `1b7c23cde830424a0bff9f9219379af985dbc063` (`feat: implement alert matching, outbox, daily digests, async worker, and metrics (Refs #7)`)
- **Draft PR**: [#23](https://github.com/Lcjam/gongchae-bell/pull/23) (`main` 머지 및 `#7` close 미수행)
- **코드 커밋 CI Run**: [CI Run #44286584725](https://github.com/Lcjam/gongchae-bell/actions/runs/44286584725) (`test` · `deploy-verify` · `backup-verify` · `secrets` 전 Job `success`)
- **최종 HEAD 안내**: 이 결과 보고서 커밋(`docs: record #7 alert delivery implementation and CI (Refs #7)`)이 PR #23 최종 HEAD가 되며, 푸시 후 최종 HEAD의 CI 및 `mergeable` 상태까지 확인해 PR #23 본문에 반영합니다.

---

## 1. 구현 내역 (확정 계약 및 `03-codex` 자격 결정 준수)

### 1) 정기 수집 동일 TX outbox (`AlertOutbox`, `PostingIngestor`)
- `PostingIngestor`에 `RegularNewOutbox` 함수형 인터페이스를 도입하고(`ingestion` → `alerts` 역의존 없음), `applyRegular()` → `write()` 트랜잭션 내부에서 `!fromBackfill && snapshot`일 때 실제 신규 `INSERT` 성공 + `status = 'OPEN' AND end_date >= kstToday`인 공고 SN만 모아 `AlertOutbox.recordRegularNew()`를 호출합니다.
- `backfill`(`upsertPage`), `detail`, 기존 공고 변경(`changed`), `CLOSED`/`CHECK` 신규 적재, `CHECK`/`CLOSED` 재등장은 `DISCORD_NEW`에서 제외됩니다.
- `ALERTS_ENABLED=false`이면 즉시 무동작으로 반환하며, `applyRegular()` TX 롤백 시 `notification` / `notification_item`도 함께 롤백됩니다.

### 2) 공통 자격 경계 및 일일 묶음 (`AlertMatcher`, `AlertBatchGenerator`, `DigestHeartbeat`, `AlertScheduler`)
- 네 경로(`DISCORD_NEW` outbox, 이메일 미발송 `NEW`, `D-3`, `TX2` 재확인) 모두 **`p.status = 'OPEN' AND p.end_date >= :kstToday`**(`kstToday`는 검사 시점 KST)로 통일하고, `CatalogRepository.appendConditions()`를 그대로 재사용합니다.
- `NEW`는 `p.from_backfill = false AND p.first_seen_at >= s.confirmed_at`, `DEADLINE`은 실행 시점 기준 `p.end_date = :kstToday + 3`을 적용합니다.
- `AlertBatchGenerator`:
  - `08:00 KST` 이후(`runDigestIfDue`) `collect_run('DIGEST', kstDate)` 선점 앱 1대만 구독당 `EMAIL_DIGEST` 1통에 미발송 `NEW` + 당일 `D-3`을 합본 생성(`PK(subscription_id, sn, kind)`로 같은 SN도 별개 유지)하고 완료 커밋 뒤 `digestHeartbeat.ping()`(`heartbeat.digest-url` ← `HEARTBEAT_DIGEST_URL`)을 보냅니다.
  - `09:00 KST` 이후(`runDeadlineIfDue`) `collect_run('DEADLINE', kstDate)` 선점 앱만 `DISCORD_DEADLINE`을 생성합니다.

### 3) 비동기 발송 워커·잠금·재검증·회수 (`AlertWorker`, `AlertDispatcher`, `DeliveryLedger`)
- **트랜잭션 및 잠금 순서 (`TX1` · `TX2` · `TX3`)**:
  - `TX1`: `DeliveryLedger.recoverAsync()` 후 `claimDue(batchSize)`(`FOR UPDATE SKIP LOCKED`, 비동기 3종 전용)로 선점.
  - `TX2` 연결 `A`: `AlertMatcher.lockForShare(subscriptionId)`(`subscription FOR SHARE`) → 23시간 수명·현재 `(sn, kind)` 자격·결정적 `payload_sha256` 계산(모두 메모리 연산, **`R` 전 자식 DML/잠금/flush 없음**) → 연결 `B`(`REQUIRES_NEW`)에서 `DeliveryLedger.reserve` `R` 실행 → `A`에서 `lockReserved`(`notification FOR UPDATE`) 후 23시간 수명·자정 경계·자격 재검증 → HTTP 1회(10s) → `recordOutcome` → 종결/재시도.
  - `UNKNOWN` 이후 `(sn, kind)` 집합 또는 `payload_sha256`(`batch_key` 날짜, 본문, `token_version` 반영)이 달라지면 새 `R`·HTTP 없이 `UNCERTAIN`(`TARGET_CHANGED`/`BODY_CHANGED`)으로 끝내며 `notification_item`을 보존합니다.
  - `R` 뒤 자정 경과 등으로 탈락하면 예약·`SEND_TOTAL` 환불 없이 `NOT_SENT`를 기록하고 HTTP를 차단합니다(`CANCELLED` 또는 남은 항목만 `RETRY`).
  - `DeliveryLedger.recoverAsync()`에서 `FAILED` 종결 시 `notification_item`을 삭제 반환하도록 보완하고, 비종결 `RETRY` 전이(`retry`) 및 `finishAsync`(`FAILED`/`CANCELLED` 항목 반환, `SENT`/`UNCERTAIN` 보존)를 추가했습니다.
- **오류 분류·404 비활성화**:
  - Discord `404`(`WEBHOOK_404`)만 `TX2` 커밋 뒤 별도 짧은 `TX3`에서 `subscription`을 `DISABLED`(`disabled_reason='WEBHOOK_404'`)로 전환하고 `/subscriptions/manage`(`manage.html`)에 사유를 표시합니다.
  - `429`는 `max(2s/4s, retry_after)` 후 `RETRY`, Resend `QUOTA_DAILY`·로컬 `SEND_CAP`은 다음 창(`< created_at + 23h`일 때만 `RETRY`, 초과 시 `EXPIRED`), 일반 4xx·5xx는 기존 계약대로 구독을 끄지 않습니다.

### 4) 페이로드·RFC 8058·지표 (`AlertPayloadBuilder`, `AlertsMetrics`)
- `EMAIL_DIGEST`는 `List-Unsubscribe` / `List-Unsubscribe-Post: List-Unsubscribe=One-Click` 헤더, `/jobs?...` 조건 링크, 관리/해지 링크를 결정적으로 구성합니다.
- Discord는 `allowed_mentions: {"parse": []}`와 `2000`자 상한 요약 + `/jobs?...` 링크만 포함하며 관리·해지 토큰을 절대 넣지 않습니다.
- `AlertsMetrics`가 `/actuator/prometheus`에 `#15` 계약 게이지(`alerts_notification_backlog`, `alerts_notification_sending_stuck`, `alerts_notification_failed_last1h{kind}`, `alerts_notification_uncertain_last24h{kind}`) 및 PRD `G2`/`G7` 게이지·카운터·지연 타이머를 고정 라벨로 노출합니다(PII·웹훅·토큰·구독 ID 0건).

---

## 2. 실측 시험 조건과 결과

모든 시험은 대역(`MailTransport`, `WebhookTransport`, 가상/제어 `Clock`, 로컬 HTTP heartbeat 스텁)·실제 ALIO 응답 fixture(`fixtures/alio-public-list-sample.json`)·격리 Testcontainers `postgres:17`에서 수행했고, `COLLECTOR_ENABLED=false`로 실제 Resend·Discord·ALIO·개발DB·실서버에는 접근하지 않았습니다.

| 구분 | 실행 명령 / 대상 | 결과 |
|---|---|---|
| 전체 Java/Spring 테스트 & 패키징 | `.\gradlew.bat --no-daemon check bootJar` | **157 tests, 0 failures, 0 errors, 0 skipped** (`BUILD SUCCESSFUL in 2m 38s`) |
| `#7` 단위 시험 | `AlertPayloadBuilderTest` | **2/2 통과** (RFC 8058 헤더·결정적 해시·Discord 2000자 상한/멘션 차단) |
| `#7` 통합·경합 시험 | `AlertDeliveryIntegrationTest` | **7/7 통과** (outbox/backfill/CHECK/재등장/롤백, 08시·09시 슬롯 경쟁·만회·heartbeat, 두 워커 `SKIP LOCKED`, `claim`/`R`/HTTP 크래시 복구, `UNKNOWN` 후 본문/`token_version`/대상 탈락, `FAILED` 반환 vs `UNCERTAIN` 보존, 404 `TX3`·`manage.html`·429 `retry_after`·5xx·`QUOTA_DAILY`·`SEND_CAP`·23h·자정 `R` 이후 차단, 해지/조건 수정 잠금 경합 `Future.get(8s)`, RFC 8058 원클릭 해지 + 대장, Prometheus 지표/무PII) |
| 원장 회귀 시험 | `DeliveryLedgerTest` | **20/20 통과** (`#6` 계약 + `recoverAsync` `FAILED` item 반환/보존 포함) |
| Compose & 배포 스크립트 | `docker compose -f compose.prod.yml config -q` + `deploy-test.sh` (`ubuntu:24.04`) | 통과 (`ALL_DEPLOY_TESTS_PASSED`, 15/15) |
| 비밀값 스캔 | `gitleaks dir . --verbose` | `no leaks found` |
| 원격 ARM64 CI (`1b7c23cd`) | [Run #44286584725](https://github.com/Lcjam/gongchae-bell/actions/runs/44286584725) | `test` · `deploy-verify` · `backup-verify` · `secrets` 4개 Job 전 항목 `success` |

---

## 3. 운영 초기값 제안 (근거 포함)

1. **비동기 발송 워커 동시성 `2` (`ALERTS_WORKER_CONCURRENCY=2`, 배치 `20`, 폴링 `5s`, `@Scheduled` 스레드 풀 `4`)**:
   - `AlertDispatcher`는 한 작업 처리 동안 `A`(`subscription FOR SHARE` → `notification FOR UPDATE`)와 `B`(`reserve` `R`) **2개 Hikari 연결**을 동시에 씁니다.
   - 기본 Hikari `maximum-pool-size = 10`에서 동시성 `2`는 최악 시 **최대 4연결**만 점유해 웹 요청·수집·정리(`5m`)·Actuator/Prometheus용 **6연결 여유**를 확보하며 풀 자기 교착 위험을 피합니다(`ALERTS_WORKER_CONCURRENCY` 상한 `4`로 방어).
2. **`gongchae-digest` Healthchecks 유예 기간**:
   - 매일 `08:00 KST` 실행 + 당일 기동 시 매분 만회를 고려해 `#15` 운영 반영 시 `grace = 2시간`(또는 당일 배포/재기동 여유 30~60분)으로 설정하는 것을 제안합니다.
3. **`#15` (`PR #21`) 통합 시 인터페이스 정리**:
   - `#7`은 `PR #21` 미병합 상태 `main`에서 구현했으므로 `DigestHeartbeat` 빈을 독립 클래스(`heartbeat.digest-url` ← `HEARTBEAT_DIGEST_URL`)로 구현해 `#15`의 `Heartbeat` 빈과 충돌하지 않게 두었습니다. 추후 `main` 통합 후 두 빈 모두 `ops.Heartbeat` 공용 구현으로 합치거나 유지할 수 있습니다.

---

## 4. 남은 실제 환경 검증 범위 (후속 게이트)

- 실제 Resend 계정·발신 도메인 SPF/DKIM/DMARC·열람/클릭 추적 비활성화 및 실 수신함 도착·RFC 8058 원클릭 실동작 확인
- 실제 Discord 웹훅 2xx 등록·새 공고/D-3 실수신 및 웹훅 삭제 후 404 자동 비활성화 확인
- `#6` 실운영 개인정보 처리방침 7개 필수 값 확정 및 공개 HTTPS(`Referrer-Policy: no-referrer` 환경의 Chrome·Firefox·Safari `Origin`) 관측
- `#15` Grafana 대시보드 발송 패널·Prometheus 경보 규칙(`gongchae-digest`, `alerts_notification_*`) 연결 및 실서버 blue/green `deploy.sh` 반영·동시 실행 관측
