# PR #13 (`feat/#6-subscriptions`, `08cfdac3`) 독립 코드 검수 · agy

> 검수 일시: 2026-11-16 · 검수 대상: PR [#13](https://github.com/Lcjam/gongchae-bell/pull/13) (`OPEN`, `draft`, `base: main`) 불변 헤드 `08cfdac3de9d6282393f379d062314d5f3440432` (코드 커밋 `b0a7ab7ed38e1f5fb9ffcc48aac01013c4c03a51`, `origin/main` 대비 61개 파일 변경).
> 기준 문서: `CLAUDE.md`, `docs/prd.md`(FR-A1~A3, A6~A8), `collab/2026-11-15-m2-readiness/{12,13,14}`, `collab/2026-11-16-m2-privacy/sol-source-review.md`, `08cfdac3`의 `claude-subscriptions-{contract,result}.md` · `docs/ops/alerts.md` · `V5__subscriptions.sql` · ADR/ERD/`routes.md`.
> 검수 방법: `issue-6-subscriptions` 워크트리 고정 커밋 `08cfdac3`의 `git show`/`git diff` 직접 대조, GitHub Actions 원격 CI/Gitleaks 메타데이터 확인. 앱 소스·설정·SQL·브랜치·PR·DB·실제 API/서버는 수정하거나 호출하지 않았다.

## 판정 요약

**조건부 수용 (필수 수정 1건 + 권고 수정 2건)**.  
전반적인 계약(12/13/14) 이행도와 보안·잠금·펜싱·해지 대장·테스트 밀도는 매우 높다. 근거 없는 새 보장을 추가하거나 범위를 몰래 축소한 흔적은 보이지 않으며, 미검증 게이트도 문서와 코드(`ALERTS_ENABLED=false` 기본값, 필수 설정 미입력 시 기동 거부)에 일관되게 반영됐다. 다만 아래 **1건의 동기 예산 경계 버그**(`SyncSender.deliverMail`가 풀/선행 대기 후 잔여 시간이 `CLEANUP` 2초 이하로 줄어든 경우 `statement_timeout = 1ms`를 걸어 정상 종결 대신 예외 중단·5~10분 `SENDING` 방치를 유발할 수 있음)는 계약 13-4·14-4("connection/lock/statement 대기 포함 및 최종 정리 몫 확보")에 직접 걸리므로 머지 전 수정을 권고한다.

---

## 1. 필수 수정 (머지 전)

### [M-1] `SyncSender.deliverMail`: DB 대기 후 잔여 시간이 `<= CLEANUP(2초)`일 때 `1ms` 타임아웃 및 불필요한 `reserve` 진입

- **위치**:
  - `src/main/java/kr/gongchae/bell/alerts/SyncSender.java:74-96`
  - `src/main/java/kr/gongchae/bell/alerts/DeliveryLedger.java:225-229` (`boundWaits`)
- **문제**:
  1. `SyncSender.java:74`는 `tx.execute` 바깥에서 `left(start) < MIN_START(5초)`만 확인한다. 이어지는 `tx.execute(...)`가 HikariCP 연결 획득(`application.yml:9` `connection-timeout: 5000ms`) 등에서 3초 이상 대기해 람다 내부에 `left(start) <= 2초`로 진입하면, `SyncSender.java:76`의 `left(start).minus(CLEANUP)`가 `0` 이하가 된다.
  2. `DeliveryLedger.boundWaits(Duration limit)`(`DeliveryLedger.java:226`)는 `Math.max(1, limit.toMillis())`로 1ms를 하한으로 잡는다. 결과적으로 바깥 트랜잭션 `A`에 `lock_timeout = '1ms', statement_timeout = '1ms'`가 설정되어 바로 다음 `stillEligible`(`SyncSender.java:77`)·`history`(`81`) 또는 `R`(`88`)이 `QueryTimeoutException`으로 깨진다.
  3. 예외는 `SubscriptionService.deliver`(`SubscriptionService.java:390-391`)에서 `ALERT_SYNC_SEND_ABORTED`로 삼켜지므로 요청 응답(500)은 막지만, `notification` 행은 `DEADLINE`으로 깔끔히 종결되지 않고 `SENDING` 상태로 남아 5~10분 뒤 `AlertsMaintenance`의 `sweepSync()`까지 방치된다.
  4. 또한 `left(start).minus(CLEANUP) < MIN_HTTP(1초)`(`left < 3초`)인 상태로 `SyncSender.java:88` `ledger.reserve(...)`에 들어가면, `R`이 `SEND_TOTAL` 1건과 `notification_attempt` 행을 커밋한 직후 `96`행에서 `timeout < MIN_HTTP`에 걸려 HTTP 0회로 `NOT_SENT`를 남기고 소진한다(단, 97~99행의 `finish` 역시 76행에서 잡힌 촉박한 `statement_timeout` 영향을 받는다).
  5. `DeliveryLedgerTest`는 `VirtualTimer`를 `mail.steps`와 `reserve()` 내부에서만 전진시켰기 때문에 `tx.execute` 진입 전 연결 대기로 `left(start) <= 3초`가 되는 경로를 타지 않았다.
- **재현 시나리오**:
  `subscribeEmail` 요청 시작(`t=0`) 후 선행 처리·이전 라운드·Hikari 풀 대기가 겹쳐, `74`행 통과 시점(`left = 5.1s`) 직후 `tx.execute` 연결 획득에 3.2초가 걸려 `t = 23.1s`(`left = 1.9s`)에 `76`행 진입 → `boundWaits(-100ms)`가 `statement_timeout = 1ms` 설정 → `stillEligible` SQL이 1ms 초과로 실패 → `SENDING` 상태 방치.
- **구체적 최소 수정안**:
  - `SyncSender.deliverMail` `tx.execute` 진입 직후(`76`행 앞) `left(start).minus(CLEANUP).compareTo(MIN_HTTP) < 0`이면 `boundWaits`를 `CLEANUP` 이상(예: `CLEANUP`)으로 잡아 `ledger.finish(claim, false, "DEADLINE", false)` 후 `Step.DONE` 반환.
  - `88`행 `ledger.reserve(...)` 호출 직전에도 `if (left(start).minus(CLEANUP).compareTo(MIN_HTTP) < 0)`를 다시 검사해 HTTP를 시작할 예산(`MIN_HTTP` + `CLEANUP`)이 없을 때는 예약/할당량 소모 없이 `finish(..., "DEADLINE", false)`로 종료(`testWebhook`의 `145`~`147`행도 동일하게 `acquire` 전 `timeout < MIN_HTTP` 검사 권장).
  - `76`·`88`행의 `boundWaits` 인자도 `MIN_HTTP` 미만이 전달되지 않도록 보정하고, `finish`·`recordOutcome` 경로는 최소 `CLEANUP` 몫 안에서 실행되게 유지한다.

---

## 2. 권고 수정 (소규모 · 경계 보강)

### [R-1] `SubscriptionService.recoverEmail`: `SELECT`~`createSync` 사이 해지 경합 시 FK 예외 가능

- **위치**: `src/main/java/kr/gongchae/bell/alerts/SubscriptionService.java:274-278`
- **문제**:
  `recoverEmail`의 트랜잭션 내부 `SELECT id, token_version FROM subscription ... WHERE ... AND status = 'ACTIVE'`(`275`행)에 `FOR SHARE`가 없다. `SELECT` 직후 `ledger.createSync(row.getKey(), "EMAIL_RECOVER")` (`277`행, `INSERT INTO notification`) 실행 전에 동시 `unsubscribe` 요청이 해당 구독을 `DELETE`하고 커밋하면 `notification.subscription_id` 외래키 위반(`DataIntegrityViolationException`)이 발생하고, `deliver()` 바깥이라 고정 응답(`ACCEPTED`) 대신 500이 날 수 있다.
- **최소 수정안**: `275`행 `SELECT` 끝에 `FOR SHARE`를 추가해 `unsubscribe`의 `FOR UPDATE`와 직렬화한다(이후 `deliver` 내부 `stillEligible`도 같은 `FOR SHARE` 잠금 순서를 따른다).

### [R-2] `HttpTransports.seconds`: `retry-after >= 86400`을 `null`로 버려 2초 뒤 즉시 재시도

- **위치**: `src/main/java/kr/gongchae/bell/alerts/HttpTransports.java:59-65`
- **문제**:
  `seconds < 86_400 ? Duration.ofMillis(...) : null` 조건 때문에 서버가 24시간 이상의 `retry-after`(예: `86400`, `90000`)를 주면 `null`을 반환한다. 그러면 `SyncSender.deliverMail:121`과 `testWebhook:154`가 `retryAfter() == null`로 판단해 대기 후(2초) 재시도한다.
- **최소 수정안**: `seconds >= 0`일 때 `>= 86_400`을 `null`로 버리지 말고 상한(예: `Duration.ofDays(1)`)으로 클램프한다(무한/비유한수는 `Double.isFinite(seconds)`로 차단).

### [R-3] 비활성화 상태(`ALERTS_ENABLED=false`)일 때 `GET /confirm`, `GET /unsubscribe` 안내 일관성

- **위치**: `src/main/java/kr/gongchae/bell/alerts/SubscriptionController.java:127-133, 205-211`
- **내용**: `form`, `managePage`, `recoverPage`는 `ALERTS_ENABLED=false`면 즉시 `unavailable(model)`을 반환하지만 `confirmPage`와 `unsubscribePage`는 검사 없이 확인/해지 버튼 폼을 그린 뒤 POST 시점에만 503을 반환한다. 기능·보안 결함은 아니나 UI 일관성을 위해 두 GET도 `if (!properties.enabled()) return unavailable(model);`을 두는 편이 깔끔하다.

---

## 3. 축별 독립 검수 결과

| 검수 항목 | 코드 대조 결과 | 비고 / 테스트 근거 |
|---|---|---|
| **토큰·목적·만료·일회성** (`AlertsCrypto`, `SubscriptionService`) | **충족**. 확인 토큰 32B 무작위 + SHA-256 해시만 DB 저장(`UNIQUE`), 확인 POST의 단일 `UPDATE ... WHERE confirm_token_hash=:h AND status='PENDING' AND confirm_expires_at>:now`만 `ACTIVE` 전이·토큰 제거. `EMAIL_CONFIRM=SENT`는 `PENDING` 유지. 관리·해지는 길이 접두사 기반 `HMAC-SHA256(purpose, id, version)` + `MessageDigest.isEqual`, 목적 분리(`MANAGE`≠`UNSUBSCRIBE`). | `AlertsSecurityTest`, `SubscriptionFlowIntegrationTest` |
| **Origin·`TOKEN_FAIL` 순서·RFC 8058** (`OriginFilter`, `SubscriptionController`) | **충족**. `TOKEN_POSTS` 4개 경로만 `Origin: null`/미포함/자사 Origin 허용, 나머지는 자사 Origin(또는 동일 출처 Referer)만 허용. 외부 Origin 403 거부는 컨트롤러 진입 전이라 `TOKEN_FAIL`에 합산되지 않음. 토큰 경로는 `TOKEN_FAIL` 30회/10분 사전 차단(429) → 검증 → 실패 시 +1 순서 준수. 원클릭 해지는 본문 `List-Unsubscribe=One-Click` 정확 일치 검증. | `SubscriptionFlowIntegrationTest` (`originRulesAndTokenFailureGateKeepTheirOrder`, `unsubscribeWrites...OneClickWorks`). 실제 HTTPS·Firefox/Safari는 미검증 게이트로 명시됨. |
| **동의·고지·14세·엄격 조건** (`new.html`, `fragments.html`, `privacy.html`, `JobSearchCriteria.strict`) | **충족**. 동의·만 14세 기본 비체크 + 서버 검사(`InvalidInput`) + `consent_version`/`consented_at`/`age_confirmed CHECK`. `privacy.html` 무토큰 열람 가능, 미설정 시 `'미정 (운영 시작 전 설정)'`, `ALERTS_ENABLED=true`면 7개 고지 설정·키 3종·대장·메일 설정 중 하나라도 누락 시 기동 거부. 알림 종류 ≥ 1 (`CHECK (notify_new OR notify_deadline)`), 코드/검색어(100자) 엄격 검증. | `AlertsSecurityTest`, `AlertsDisabledIntegrationTest`, `SubscriptionFlowIntegrationTest` |
| **요청 제한·동기 예약 R·환불 없음** (`RateLimiter`, `DeliveryLedger`) | **충족**. IP 10/10m, 대상 3/1h(모두 범위별 HMAC 키). `SEND_TOTAL` 이메일 90/UTC일(`epoch` 정렬 = UTC 자정)·디스코드 300/시간. `reserve()`(`REQUIRES_NEW`)가 펜싱(`status='SENDING' AND claimed_at=:claim`) + `attempts < 3` + 키·해시 기록 + `INSERT ... ON CONFLICT DO UPDATE WHERE count < max` + `notification_attempt(NULL)` 커밋을 원자 처리, 상한 초과 시 전체 롤백(HTTP 0, 시도 0). 일 한도 HTTP 429(`QUOTA_DAILY`)도 시도로 기록. | `DeliveryLedgerTest` (`aReservationSurvivesACrash...`, `theLocalCeilingReservesNothing...`, `aDailyQuota429...`) |
| **SSRF·DNS·리다이렉트·AES-GCM** (`WebhookUrl`, `AddressGuard`, `HttpTransports.Discord`, `AlertsCrypto`) | **충족 (계약 범위 내)**. `https` + `discord.com`/`discordapp.com`(정규화 통일) + 기본 포트 + userinfo/query/fragment 거부 + 엄격 경로 정규식 + `toString()` 토큰 마스킹. 전송 직전 `AddressGuard`가 루프백·사설·링크로컬·멀티캐스트·`0/8`·`255.x`·`100.64/10`·`198.18/15`·IPv6 `fc00::/7`·IPv4-mapped IPv6(`Inet4Address`)·DNS 실패 차단. `Redirect.NEVER`. AES-256-GCM 12B nonce + AAD=구독 ID + 키 ID 검증. **잔여 한계**: ADR-0009에 명시된 대로 `AddressGuard` DNS 조회 후 `HttpClient`가 재조회(TOCTOU)하나 호스트가 `discord.com`으로 고정됨. 웹훅 키 로테이션/재암호화 도구는 미구현(단일 `webhookKeyId`만 복호화)으로 결과·운영 문서에 솔직히 기재됨. | `AlertsSecurityTest`, `TransportTest` |
| **잠금 순서·펜싱·삭제 경합·`UNKNOWN`·`payload_sha256`·최대 3/25초** (`DeliveryLedger`, `SyncSender`) | **M-1 제외 충족**. 모든 경로가 `subscription` → `notification` → `attempt` 잠금 순서 준수, `R` 전 `A`의 자식 DML/잠금/ORM flush 없음(`JdbcClient` 부분 UPDATE만 사용), `R` 뒤 `lockReserved()`(`FOR UPDATE`)로 선점·예약·키 재검증 후 HTTP~결과 커밋까지 잠금 유지. `sweepSync()`·`recoverAsync()`는 `SKIP LOCKED` + `claimed_at=NULL`로 충돌·구세대 선점 차단. `UNKNOWN`(5xx/타임아웃/IO/409/NULL/`UNKNOWN_CRASH`)은 후속 4xx로 덮어쓰지 않고 `UNCERTAIN` 종결, 재시도 전 자격 재확인 + 전체 요청 JSON 바이트 `payload_sha256` 일치 검증(`BODY_CHANGED` 차단), 전원 확실한 거절(`allDefinite()`)일 때만 새 UUID 키 허용. 루프 중 구독 삭제 시 0행으로 정상 종료. | `DeliveryLedgerTest` (`afterAnUnknownAttempt...`, `synchronousKindsAreNeverClaimed...`, `aDeletedSubscription...`). 단 M-1의 `tx.execute` 진입 전 대기 경계 보완 필요. |
| **해지 대장 `fsync` + 교착/프로세스 잠금 + fail-closed 복원** (`ErasureJournal`, `ErasureReapplyCommand`) | **충족**. 구독 `FOR UPDATE` 잠금 아래 DB 삭제 전에 `synchronized(writers)` + OS `FileChannel.lock()`(READ+WRITE) + 잘린 끝줄 보정(`\n`) + 전체 바이트 쓰기 + `force(true)` + POSIX 디렉터리 `force(true)` 수행, 실패 시 예외로 DB 삭제 롤백. 복원은 `readStrict()`로 전체 줄(`uuid\tInstant`, 소문자 정규 UUID) 선검증 후 손상 시 0건 삭제(fail closed)로 중단. **운영 유의**: `Files.notExists(path)`는 `List.of()`를 반환하므로 복원 시 `ALERTS_ERASURE_LOG` 경로 오타·마운트 누락 여부를 운영자가 먼저 확인해야 함. | `ErasureJournalTest`, `DeliveryLedgerTest` (`restoreReapplies...`), `SubscriptionFlowIntegrationTest` |
| **보존/파기·V5 호환·M1 회귀·로그 비노출** (`AlertsMaintenance`, `V5__subscriptions.sql`, `CatalogRepository`, `JobSearchCriteria`) | **충족**. `AlertsMaintenance`가 5분마다 만료 `PENDING`, 30일 경과 `DISABLED`, 30일 경과 종결 `notification`(CASCADE), 종료된 `rate_limit` 창(IP ≤15분, 대상 ≤1시간 5분), 5분 초과 동기 `SENDING` 정리. `V5`는 신규 5테이블만 추가해 이전 M1 앱과 blue/green 공존 가능. `JobSearchCriteria.strict`·`CatalogRepository.appendConditions`는 `#5` 목록 동작을 그대로 유지하면서 술어만 공용화. 로그·`toString()`·오류 응답(`include-message: never`)에 키·이메일·웹훅·토큰 미노출 확인(`git grep` 대조). | `DeliveryLedgerTest`, M1 기존 96건 포함 CI 통과 |

---

## 4. 문서·실측 범위·CI/비밀값 대조

1. **근거 없는 보장 / 조용한 범위 축소 여부**:
   - `claude-subscriptions-contract.md`, `claude-subscriptions-result.md`, `docs/ops/alerts.md`, `docs/design/{erd,routes}.md`, `docs/implementation-status.md`, `docs/adr/*`를 코드와 대조한 결과, 범위 축소나 과장 주장은 없다.
   - `claude-subscriptions-result.md:106`에 적힌 "목록 CTA 주소에 빈 인자가 붙는다(`?ncs=&region=…`)"는 설명은 `jobs.html:105`와 `SubscriptionFlowIntegrationTest:331` 기준 실제로 `null` 파라미터가 생략(`/subscriptions/new?ncs=R600020`)되므로 사소한 보고 메모 오차로 보인다(기능 영향 없음).
   - `claude-subscriptions-result.md:103-107`의 의도적 축소·예외(디스코드 등록 테스트 실패 시 DB 행 미생성·`rate_limit`만 기록, 이메일 수신자 오류 자동 비활성화 없음, 전역 Hikari 연결 대기 5초 설정 미실측, `alerts.mail.base-url` 테스트 전용 속성) 모두 계약 12·13·14와 일치한다.
2. **CI 및 비밀값 검사 확인 (원격 직접 조회)**:
   - 커밋 `b0a7ab7ed38e1f5fb9ffcc48aac01013c4c03a51`: GitHub Actions run `43811896339` (`test`: `./gradlew --no-daemon check bootJar` success, `secrets`: `gitleaks/gitleaks-action@v3.0.0` success).
   - 커밋 `08cfdac3de9d6282393f379d062314d5f3440432`: GitHub Actions run `43812124266` (`test`: success, `secrets`: success).
   - 신규 테스트 31건(`AlertsDisabledIntegrationTest` 1, `AlertsSecurityTest` 5, `ErasureJournalTest` 2, `TransportTest` 3, `DeliveryLedgerTest` 12, `SubscriptionFlowIntegrationTest` 8) 및 기존 M1 96건 모두 공급자 대역/루프백 서버/격리 PostgreSQL 17 기준 검증이며 `COLLECTOR_ENABLED=false`다.
3. **실사용 전 별도 게이트 (미검증 범위 유지 확인)**:
   - `#7` 범위(outbox 훅, 일별 묶음/마감 알림, 비동기 워커 TX1·TX2·TX3, 조건 탈락 item 삭제·반환, 디스코드 404 비활성화, RFC 8058 메일 헤더, 지표/경보).
   - 실제 Resend·Discord API 실응답/멱등 재생·수신함 배달·실제 메일 클라이언트 원클릭 해지 동작(실발송 승인 후).
   - 실제 HTTPS 환경과 Firefox·Safari에서의 `no-referrer` 페이지 POST `Origin: null` 관측.
   - 처리방침 7개 필수 운영 값, Resend DPA/도메인 SPF·DKIM·DMARC/추적 off/계정 단독 사용, Discord 국외 처리·법적 지위 확정, Caddy `X-Forwarded-For` 차단 및 `?t=` 접근 로그 제거, 해지 대장 호스트 마운트·restic 백업·백업 사본 14일 삭제 확인, Hikari 5초 타임아웃의 동시 부하 실측.

---

## 5. 교차 검수 반영 및 판정 정정 (2026-11-16 · `pr-13-codex.md` 대조)

`collab/reviews/pr-13-codex.md`(root C1~C6)와 불변 헤드 `08cfdac3` 소스를 다시 대조해 **기존 판정("조건부 수용: 필수 1건 M-1")을 "수정 후 재검수 (root C1~C6 전건 필수 수정 동의)"로 정정**한다. 상세 실행 순서·계약 대조는 [agy-subscriptions-cross-review.md](../2026-11-16-implementation/agy-subscriptions-cross-review.md)에 남겼다.

- **C2 수용·기존 판정 정정**: 기존 §3에서 `Files.notExists(path) -> List.of()`를 "운영 유의"로만 둔 것은 fail-closed(ADR-0013)에 부족하다. 복원 시 경로 오타·마운트 누락이 `ERASURE_REAPPLY_COMPLETE deleted=0`·종료 코드 0으로 통과한다. 초기화/운영된 0바이트 빈 파일만 정상 빈 대장으로 허용하고 부재 경로는 복원 실패로 중단해야 한다.
- **C3 수용·기존 판정 정정**: `ErasureJournal.java:48-61`에서 `channel.lock()` 해제(`60`행) 뒤에 `forceDirectory()`(`61`행)가 실행되고, 잠금 전 `Files.notExists()`로 `created`를 판단하므로, 다른 JVM(blue/green)이 `created=false`로 진입해 디렉터리 엔트리 fsync 없이 DB `DELETE`를 커밋할 수 있다. 잠금 안에서 매번(또는 잠금 내 상태 확인 후) 부모 디렉터리를 fsync해야 한다.
- **C4 수용·기존 판정 보강**: 기존 M-1(`SyncSender.deliverMail:74-96`의 `1ms` `boundWaits` 및 잔여 `< 3s`에서의 `reserve`)에 더해, `SubscriptionService`의 선행 `RateLimiter.acquire`(`113-114, 269-270, 327-330`)·`signupEmail`(`119`)·`recoverEmail`(`274`)·`registerDiscord` 저장 TX(`340`)·`testWebhook`의 `SEND_TOTAL` 예약(`SyncSender.java:145`)·`SyncSender.end`(`129`) 모두 DB `lock_timeout`/`statement_timeout` 제한이 없다(`hikari.connection-timeout: 5000`은 풀 대기만 제한). 계약 13-4·14-4 및 `SyncSender.java:15-19`에 맞게 단조 시계 예산으로 묶되, 종결 정리(`finish`/`end`)에는 `1ms` 고갈 대신 `CLEANUP` 몫을 남겨야 한다.
- **C5 수용·기존 판정 정정**: `HttpTransports.java:120-124`의 `guard.allows(url.host())`가 동기 `InetAddress.getAllByName`으로 블로킹한 뒤 경과 시간을 차감하지 않고 원래 `timeout`으로 HTTP를 시작한다. 전달된 단일 `timeout` 안에서 DNS 조회와 HTTP를 함께 묶고, DNS 시간 소진/지연으로 HTTP를 시작하지 못한 경우에는 `TIMEOUT`(UNKNOWN)이 아닌 비전송/차단 결과(`BLOCKED_ADDRESS`/`NOT_SENT`)로 분류해야 한다.
- **C1·C6 수용**: 기존 `R-1`·`R-2`를 필수 수정으로 승격한다(`recoverEmail` `FOR SHARE` 누락 시 동시 해지 경합으로 500 노출 가능, `HttpTransports.seconds`의 `>= 86400` `null` 반환 시 조기 2초 재시도).

