# PR #23 독립 검수 · claude (추가 세션)

2026-11-19 · 요청: root codex, [`2026-11-19-resume/extra-claude-pr23-review.md`](../2026-11-19-resume/extra-claude-pr23-review.md)

## 대상

| 항목 | SHA |
|---|---|
| 검수한 불변 head | `b50d3d044f2502a6c8568ad4b15284c589758add` |
| 부모 잠금 수정 | `83dcf9cf900d58741d1890eea1cd22cb594b706d` |
| 최초 구현 | `1b7c23cde830424a0bff9f9219379af985dbc063` |
| 기준 main | `ed3f28f38623afeccd41006548a9e4f54ed0e301` |

검수 worktree `pr23-claude-review`(브랜치 `docs/#7-claude-review`, HEAD = b50d3d0, clean)에서 읽기만 했다. 앱 코드, 테스트, 설정은 고치지 않았다.

## 판정

- **83dcf9c 부모 잠금 수정: 수용.** 해지 경합으로 생기던 FK 23503과 그에 따른 정기 수집·묶음 트랜잭션 롤백을 막는다. 회귀 테스트는 정상 진입점으로 수정 전후를 실제로 구별한다(§2).
- **b50d3d0 전체: 새 P1 없음. 병합은 조건부.** 이미 agy에게 배정된 P2 두 건(§3 F1·F2)을 이 SHA에서 독립적으로 재현했다. 둘이 고쳐진 새 head의 차분을 다시 검수한 뒤 병합하자는 의견이다. 그 밖에 P3 세 건(F3~F5)과, sol이 먼저 찾은 깨진 문서 링크 1건(§4)이 있다. 병합을 막지는 않는다.

## 1. 독립 대조 결과 (요청한 관점별)

| 관점 | 결과 | 근거 위치 |
|---|---|---|
| R 전 자식 DML·잠금 금지 | 충족. R 전 A가 하는 일은 `loadAsyncJob`(잠금 없는 SELECT), 구독 `FOR SHARE`, item·공고 읽기, 본문·해시 계산뿐이다. R 전에 끝나는 경로(`NOT_ACTIVE`·`EXPIRED`·`NO_ITEMS`·`TARGET_CHANGED`·`BODY_CHANGED`)는 R을 부르지 않는다. 이때만 `lockClaim`을 잡는다 | `AlertDispatcher.java:77-123` |
| 부모→자식 순서 | 충족. A: subscription(SHARE) → notification(`lockReserved` FOR UPDATE) → attempt·item. B(R)의 notification UPDATE는 FK 열을 바꾸지 않아 부모 RI 잠금이 없다. attempt INSERT의 FK 잠금은 B가 이미 쥔 자기 행이다. 회수는 notification(SKIP LOCKED) → attempt → item 순서다. outbox는 subscription(KEY SHARE) → notification → item 순서다. 순환을 찾지 못했다 | `AlertDispatcher.java:85,142`, `DeliveryLedger.java:187-210,351-382`, `AlertOutbox.java:55-83` |
| claim·시도 펜싱 | 충족. R은 `status·claimed_at·attempts<3`를 확인한다. `lockReserved`는 여기에 `attempts=no`와 `key`까지 확인한다. `finish`와 `retry`는 `status·claimed_at`을 확인한다. `recordOutcome`은 A가 notification 잠금을 쥐고 있을 때만 불린다. 회수는 그 행을 SKIP LOCKED로 건너뛰므로 사이에 끼어들 수 없다 | `DeliveryLedger.java:191-195,240-246,261-299` |
| unknown 뒤 같은 full hash·typed target | 충족. unknown이 있으면 `(sn, kind)`별 탈락이나 빈 집합이 생길 때 R 없이 `UNCERTAIN/TARGET_CHANGED`로 끝낸다. 해시가 다르면 `BODY_CHANGED`로 끝낸다. 키는 모든 앞 시도가 확실한 거절일 때만 새로 만든다. 해시 대상은 from·to·subject·List-Unsubscribe 헤더(token_version 포함)·text 전체 요청 JSON이다 | `AlertDispatcher.java:103-120`, `AlertPayloadBuilder.java:48-80` |
| R 뒤 현재 D-3 자격·예약 보존 | 충족. R 뒤 KST 날짜로 다시 평가한다. 집합이나 해시가 다르면 시도 행에 `NOT_SENT`(확실한 비전송)를 기록하고 HTTP를 보내지 않는다. 예약은 환불하지 않는다. unknown이 있으면 `UNCERTAIN`, 없으면 `NO_ITEMS` 취소나 즉시 `RETRY`다. D-3는 생성, R 전, R 뒤 모두 `end_date = kstToday+3`로 검사한다 | `AlertDispatcher.java:144-176`, `AlertMatcher.java:123-152` |
| 생성/해지 경합 | 83dcf9c로 충족(§2). TX2 중 해지는 A의 `FOR SHARE`에 막혀 기다린다 | `AlertOutbox.java:55-58` |
| item 반환 | 충족. `FAILED`·`CANCELLED`일 때만 item을 지운다. 회수 경로는 `FAILED`일 때만 지운다. `SENT`·`UNCERTAIN`은 보존한다 | `DeliveryLedger.java:275-284,374-377` |
| 다음 창·23시간·최대 3회 | 충족. `SEND_CAP`은 R 전체가 롤백되어 시도로 세지 않는다. 다음 창은 UTC 일(이메일)이나 정시(Discord)다. `QUOTA_DAILY`는 다음 UTC 00:00이다. `retry`는 `created_at+23h > next`일 때만 허용하고, 아니면 `EXPIRED`다. 수명은 R 전과 R 뒤 모두 `now >= created_at+23h`로 검사한다 | `AlertDispatcher.java:94,130-139,145,200-223`, `DeliveryLedger.java:290-299` |
| 404 TX3 | 충족. A 커밋 뒤 별도 트랜잭션에서 `WHERE status='ACTIVE'`로 전환한다. 잠금 승격은 없다 | `AlertDispatcher.java:69-74,227-232` |
| RFC 8058 | 충족. `List-Unsubscribe`(one-click URL)와 `List-Unsubscribe-Post`가 있다. 엔드포인트와 Origin 예외는 #6에 이미 있다. 본문에도 해지 링크가 있다 | `AlertPayloadBuilder.java:54-78`, `OriginFilter.java:23-24` |
| 라벨·로그 경계 | 충족. 태그는 kind·outcome·channel·provider의 고정 값뿐이다. 로그에는 예외 클래스 이름만 남긴다. heartbeat URL은 오류 메시지에 넣지 않는다 | `AlertsMetrics.java`, `AlertWorker.java:59`, `DigestHeartbeat.java:181-191` |

## 2. 부모 잠금 수정(83dcf9c) 상세

**수정 전 결함(코드로 확인):** `INSERT … SELECT FROM subscription WHERE status='ACTIVE'`는 커밋되지 않은 해지(FOR UPDATE + DELETE)를 보지 못한다. 그래서 옛 ACTIVE 행을 읽고 INSERT를 진행한다. 이어지는 FK 검사는 해지가 커밋될 때까지 기다리다가 23503으로 실패한다. 이 INSERT는 정기 수집(`applyRegular`)이나 묶음 생성과 같은 트랜잭션에 있다. 따라서 해지 한 건이 공고 반영 전체와 다른 구독자의 outbox까지 롤백한다.

**수정:** 자식 INSERT 전에 `SELECT 1 … AND status='ACTIVE' FOR KEY SHARE`를 실행한다. KEY SHARE는 FOR UPDATE와 충돌하므로 진행 중인 해지를 기다린다. READ COMMITTED에서는 해지 커밋 뒤 행을 다시 평가하고, 행이 없으면 건너뛴다. 해지가 늦게 시작하면 해지 쪽이 수집 커밋까지 기다린 뒤 CASCADE로 지운다. KEY SHARE는 TX2의 `FOR SHARE`와 충돌하지 않는다. 해지(`SubscriptionService.java:314` FOR UPDATE)와 조건 수정은 막는다. #6 `recoverEmail`이 쓰는 기존 방식과 같다(`SubscriptionService.java:325-343`). 새 전역 잠금은 없다.

**회귀 테스트:** `outboxAndDigestSerializeWithConcurrentUnsubscribeWithoutRollback`(`AlertDeliveryIntegrationTest.java:873`)은 실제 진입점 세 가지를 쓴다. `ingestor.applyRegular`, `batchGenerator.generateEmailDigest`, `subscriptions.unsubscribe`다. 대기 확인은 `pg_stat_activity`에서 질의 문자열 `SELECT 1 FROM subscription`이 Lock 대기인지로 한다. 수정 전 코드에서는 대기가 FK 내부 SPI에서 일어나므로 최상위 질의가 INSERT 문으로 보인다. 그러면 `awaitLockWait`가 5초 뒤 실패한다. 즉 이 테스트는 수정 전후를 실제로 구별한다(추론이며 수정 전 코드로 실행하지는 않았다). 공고 보존, 생존 구독 1건, 해지 구독 0 HTTP, 반대 순서(생성이 먼저 잡음) 직렬화까지 확인한다.

**남는 경계(P3, F4):** KEY SHARE는 FOR NO KEY UPDATE와 충돌하지 않는다([PG 17 행 잠금 충돌표](https://www.postgresql.org/docs/17/explicit-locking.html#ROW-LOCK-COMPATIBILITY)). 그래서 커밋 전인 TX3 404 비활성화(`UPDATE subscription SET status='DISABLED'`)와는 직렬화되지 않는다. 이때는 작업이 생기고 TX2가 `NOT_ACTIVE`로 취소한다(HTTP 0, item 반환). 동작에 해는 없다. 다만 주석의 "disabled concurrently … skip it cleanly"는 커밋된 비활성화에만 맞다.

## 3. 문제 목록

| # | 등급 | 위치 | 문제 | 재현·근거 | 담당 |
|---|---|---|---|---|---|
| F1 | P2 | `compose.prod.yml:11-40`(`x-app-common.environment`), `deploy/.env.prod.example`, `application.yml:67-70` | `ALERTS_WORKER_CONCURRENCY`, `ALERTS_BATCH_SIZE`, `ALERTS_SCHEDULER_ENABLED`가 Compose에 없다. `env_file`도 없고 환경변수를 명시적으로 나열하는 방식이다. 그래서 `.env`에 값을 적어도 컨테이너에 전달되지 않고 늘 기본값(2·20·true)이 쓰인다. `docs/ops/alerts.md:28`은 조정할 수 있다고 적었다. 특히 두 앱 중 한 대의 스케줄러를 끄거나 동시성을 낮추는 운영 조치가 불가능하다 | `grep -c` 결과 compose 0건, example 0건. HEARTBEAT_DIGEST_URL만 추가됐다(`compose.prod.yml:39`) | agy(배정됨) |
| F2 | P2 | `AlertDispatcher.java:189`, `AlertsMetrics.java:86-91`, `AlertBatchGenerator.java:237,246` | `alerts.delivery.latency{kind}`의 시작점은 `notification.created_at`이다. EMAIL_DIGEST의 created_at은 08:00 묶음 시각이다. 그래서 이메일 NEW의 "수집 반영부터 발송까지"(PRD G7)에서 묶음 전 대기(최대 약 24시간)가 빠진다. DISCORD_NEW는 created_at이 수집 시각이라 맞다. 같은 지표 이름인데 kind마다 의미가 다르다 | 코드 경로 대조. 예: 11-18 10:00 KST 최초 관측 → 11-19 08:00 묶음 생성 → 08:00:05 발송이면 지표는 약 5초, 실제는 약 22시간 | agy(배정됨) |
| F3 | P3 | `AlertDispatcher.java:182,234-241`, `AlertsCrypto.java:106-113` | 웹훅 복호화와 URL 파싱이 R 커밋 뒤에 일어난다. key id가 맞지 않거나 복호화에 실패하면 `IllegalStateException`이 나고 A가 롤백된다. R이 남긴 시도 행은 null로 남는다. 5분 뒤 회수가 `UNKNOWN_CRASH`로 적고, 같은 키로 최대 3회 반복한 뒤 `UNCERTAIN`으로 끝난다. HTTP는 한 번도 나가지 않았는데 "결과 모름"으로 기록된다. 그 결과 SEND_TOTAL discord를 3회 쓰고, item이 반환되지 않으며, uncertain 지표가 부풀어 오진을 부른다. 순수 계산이므로 R 전으로 옮기면 된다. 실패하면 R 없이 `FAILED`로 끝낸다 | 코드 경로. 키 교체를 잘못 설정했을 때만 일어난다. 메일 키가 비어 있는 경우는 시작 검사(`AlertsProperties.java:38`)가 막는다 | 작성자 판단 |
| F4 | P3 | `AlertOutbox.java:53-54` | §2 끝. 주석의 "disabled concurrently"를 "committed disable"로 좁히거나, TX2가 취소한다는 문장을 더한다 | PG 문서 충돌표 | 작성자 판단 |
| F5 | P3 | `docs/ops/alerts.md:87-91` | 새 종결 사유 `TARGET_CHANGED`(14-2·03-4)와 시도 결과 `NOT_SENT`(R 뒤 탈락)가 운영 문서의 사유 목록에 없다. 운영자가 `last_error`를 읽을 때 근거가 없다 | `grep` 0건(ADR-0009의 NOT_SENT는 다른 맥락) | 작성자 판단 |

**결함으로 보지 않은 것:** `collect_run(DIGEST|DEADLINE, 날짜)` 슬롯을 잡은 뒤 생성이 실패하거나 종료되면 그날 다시 만들지 않는다(`AlertBatchGenerator.java:232,256-259`). 기존 수집 슬롯과 같은 방식이고, heartbeat 누락으로 드러난다. ADR-0014의 "놓치면 당일 1회 만회"는 서버가 꺼져 있던 경우를 다룬다. 생성 실패 뒤 수동 재생성 절차는 runbook에서 볼 일이다.

## 4. 기존 리뷰·작성자 기록과 교차 대조

§1~§3을 쓴 뒤 [`pr-23-codex.md`](pr-23-codex.md), [`pr-23-sol.md`](pr-23-sol.md), 작성자 기록(`collab/2026-11-18-implementation/agy-alert-delivery-result.md`, `agy-alert-delivery-fixes.md`, PR 트리 안에 있음)을 읽었다.

| 항목 | codex | sol | 이 검수 | 대조 결과 |
|---|---|---|---|---|
| 해지·outbox FK 23503 경합 | P2, 격리 PG 재현 | P2-1, 격리 PG 재현(슬롯 재선점 0건 포함) | 83dcf9c 수정·회귀 테스트 수용(§2) | 일치. 두 재현이 지적한 결함을 수정이 닫는다. 수정 전 SQL은 직접 실행하지 않았다 |
| 워커 env 3종 Compose 미전달 | P2 수용 | P2-2, compose config 진단 | F1 | 일치. 나는 grep만 했다. 실행 근거는 sol의 진단이다. b50d3d0에서도 그대로다 |
| 이메일 NEW 지연 시작점 | P2 수용 | P2-3 | F2 | 일치. b50d3d0에서도 그대로다 |
| `docs/ops/alerts.md:3`의 `collab/2026-11-17-m2-readiness/03-…` 링크 | — | 비차단 관찰 | 놓쳤다 | sol 관찰을 b50d3d0에서 재확인했다. 트리에 해당 경로가 0건이라 PR 독자에게는 깨진 링크다. P3로 수용한다 |
| 슬롯 실패 뒤 당일 재생성 없음 | 새 재선점 정책을 요구하지 않음 | FK 경합의 결과로 서술 | 결함 아님(§3 끝) | 일치. FK 원인은 83dcf9c로 사라졌다. 다른 실패에는 기존 슬롯 계약을 유지한다 |
| R 뒤 복호화 예외의 unknown 기록 | — | — | F3 | 이 검수만 지적했다. 잘못된 키 설정에서만 일어나는 P3다 |
| KEY SHARE와 커밋 전 404 비활성화 | — | — | F4 | 이 검수만 지적했다. 동작은 무해하고 주석만 부정확하다 |
| `TARGET_CHANGED`·`NOT_SENT`의 운영 문서 누락 | — | — | F5 | 이 검수만 지적했다. 문서 P3다 |
| R 전후 자격·typed 대상·full hash·펜싱·item 반환·TX3·RFC 8058·라벨 | 수용 | — | 수용(§1) | 일치 |

**작성자 기록 대조:** fixes 기록의 원인 설명(SELECT 스냅샷과 `RI_FKey_check_ins` 사이 경합), 수정, 세 경합 시나리오 설명은 소스·테스트와 맞는다. 158 tests, gitleaks, CI #44289745420은 작성자의 보고이며 나는 다시 확인하지 않았다. 작성자는 result §3에서 동시성 2·풀 여유를 운영 초기값으로 제안했다. 그런데 F1 때문에 그 값은 지금 운영에서 바꿀 수 없다. F1을 고치면 이 제안도 실제로 조정할 수 있게 된다.

## 5. 후속 head에서 볼 것

- F1: 양색 앱 environment에 세 키가 실제로 전달되는가(merged config). example·문서와 일치하는가.
- F2: NEW 공고 반영 시각부터 발송까지를 관측하는 지표가 생겼는가. 시작점이 다른 합성 사례 테스트가 있는가. 기존 `created_at` 기반 지표의 의미가 문서에 적혔는가.
- 그 diff가 §1의 잠금 순서와 R 전 무DML 불변식을 깨지 않는가. 특히 지표 계산을 위해 TX2 안에 posting·item 조회나 잠금을 더하는 경우.
- P3(F3~F5, 깨진 링크)는 이번 수정에 묶을지 작성자와 root가 정한다.

## 6. 확인 범위와 하지 않은 것

- **직접 한 것:** 위 SHA의 diff·소스·테스트 읽기, `grep` 대조, 계약 12·13·14, 03 결정, PRD FR-A3·A4·A5·A8·G7 대조.
- **실행하지 않은 것:** Gradle 빌드·테스트, Docker·Testcontainers·PG 진단. 위 결론 가운데 PG 동작에 관한 것은 공식 문서와 코드 경로로 추론했다. 최종 CI 전 잡 success와 MERGEABLE은 root의 조회 결과를 인용한 것이며 내가 직접 조회하지 않았다.
- **접근하지 않은 것:** 실제 env·키·Resend·Discord·개발 DB·서버·OCI·DNS·결제. 실제 외부 발송.
