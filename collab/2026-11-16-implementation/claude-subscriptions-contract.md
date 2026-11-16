# #6 구독·보안 구현 계약 · claude

> 2026-11-16 · 구현: claude(#6 단독 작성자) · 검수: root(codex)와 별도 리뷰어
> 근거: [12 수정 계약](../2026-11-15-m2-readiness/12-claude-revised-contract.md), [13 보완 네 가지](../2026-11-15-m2-readiness/13-sol-revised-review.md), [14 착수 결정](../2026-11-15-m2-readiness/14-codex-implementation-decision.md), [개인정보 출처 검토](../2026-11-16-m2-privacy/sol-source-review.md). 06~08의 수용점은 그대로다.
> **이 계약을 바꿀 때는 이 파일을 먼저 고치고 #7 담당에게 알린다.** 코드 위치는 `kr.gongchae.bell.alerts`다.

## 1. 테이블 (V5)

`subscription`, `notification`, `notification_attempt`, `notification_item`, `rate_limit` 다섯 개다. 열과 제약은 [ERD](../../docs/design/erd.md#구독과-발송)에 있다. 원본은 `V5__subscriptions.sql`이다. 새 테이블만 만들므로 이전 버전 앱과 함께 돌 수 있다. #7이 스키마를 더 바꿔야 하면 V6으로 낸다.

## 2. 시도·예약·선점 (`DeliveryLedger`)

| 용어 | 구현 |
|---|---|
| 선점 | 비동기: `claimDue(limit)`가 `PENDING`·`RETRY`이고 `next_attempt_at <= now`인 **비동기 종류만** `FOR UPDATE SKIP LOCKED`로 잡아 `SENDING`, `claimed_at=now`로 바꾼다. 동기(`createSync`): 만들 때부터 `SENDING`이고 그 요청이 선점한 것이다. **선점은 시도가 아니다** |
| 예약 R = 시도 | `reserve(claim, key, payloadHash, totalKey, limit, lockTimeout)`가 **자기 연결(REQUIRES_NEW)**에서 한 번에 한다: ① 펜싱(`status='SENDING' AND claimed_at=:claim`)과 `attempts < 3`을 확인하며 `attempts+1`, ② 키·해시를 기록, ③ `SEND_TOTAL` 1을 상한 안에서만 예약, ④ 시도 행 INSERT(`outcome` null). 하나라도 실패하면 전부 롤백한다. 반환은 `Reserved(attemptId, no)`, `Fenced`, `Capped` 중 하나다 |
| HTTP | R이 커밋된 뒤에만 1회 보낸다. 그래서 공급자 요청 수 ≤ 시도 행 수 ≤ 3이다. R 뒤에 종료해도 예약과 시도는 환불하지 않는다. 그 시도는 null(결과 모름)로 남는다 |
| 로컬 사전 거절 | R이 `Capped`를 돌려준다. HTTP·시도·예약 모두 없다 |
| 결과 기록 | `recordOutcome(attemptId, result)`는 `outcome IS NULL`일 때만 쓴다. 먼저 기록된 결과(회수의 `UNKNOWN_CRASH` 포함)는 덮어쓰지 않는다 |
| 종결 | `finish(claim, sent, reason, cancel)`(펜싱): 2xx면 `SENT`, 아니면 결과 모름 시도가 하나라도 있으면 `UNCERTAIN`, 아니면 `cancel`이면 `CANCELLED`, 아니면 `FAILED`다. `last_error=reason`, `finished_at`을 쓰고 `claimed_at`을 지운다. 0행이면 선점이 끊긴 것이다 |
| 동기 정리 | `sweepSync()`: 동기 종류의 `SENDING`이 5분을 넘으면 null 시도를 `UNKNOWN_CRASH`로 바꾸고, 재발송 없이 `UNCERTAIN` 또는 `FAILED`로 끝낸다(`INTERRUPTED`) |
| 비동기 회수(#7용) | `recoverAsync()`: **비동기 종류만** 대상이다. 5분 넘은 `SENDING`의 null 시도를 `UNKNOWN_CRASH`로 바꾸고 `claimed_at=null`로 펜싱을 끊는다. 시도가 3 미만이고 `created_at + 23h`가 남았으면 `RETRY`(`next_attempt_at=now`), 아니면 종결 규칙으로 끝낸다(`ATTEMPTS_EXHAUSTED` 또는 `EXPIRED`) |

## 3. 트랜잭션 경계 (13-3, 14-3)

연결 A는 바깥 트랜잭션, B는 R이다. 순서를 지켜야 교착과 오래된 선점의 전송이 없다.

1. **A**: 부모 `subscription`을 `FOR SHARE`로 잠그고 자격을 확인한다(동기는 `MailJob.stillEligible`). 읽기와 본문 계산만 한다. **R 전에는 notification·attempt·item에 DML도, 잠금도, ORM 자동 flush도 두지 않는다.**
2. **B(R)**: 위 표대로 하고 커밋한다.
3. **A**: `lockReserved(claim, no, key)`로 notification을 `FOR UPDATE` 잠그고 선점·상태·예약 번호·키가 그대로인지 확인한다. 자격과 남은 시간을 다시 확인한 뒤 HTTP 1회를 보낸다. 결과 기록(`recordOutcome`)과 상태 변경(`finish`)도 이 잠금 아래에서 하고 커밋한다. R이 쓴 `attempts`·키·해시는 A가 엔티티 flush로 덮어쓰지 않는다(이 구현은 JdbcClient의 부분 UPDATE만 쓴다).
4. 결과·회수·종결·파기의 잠금 순서는 **subscription → notification → attempt/item**이다. 회수는 notification을 `SKIP LOCKED`로 잡으므로, HTTP 중인 A의 행은 건너뛴다.
5. 디스코드 404의 비활성화(#7)는 A가 커밋한 뒤 별도 짧은 트랜잭션에서 subscription을 `FOR UPDATE`로 잡아 한다.

워커 하나가 연결 2개(A, B)를 동시에 쓴다. 풀 획득 대기는 5초로 묶었다(`spring.datasource.hikari.connection-timeout: 5000`). A와 R은 `lock_timeout`·`statement_timeout`을 남은 시간으로 건다(`boundWaits`).

## 4. 결과 분류 (`SendOutcome`)

| 부류 | 값 | 동작 |
|---|---|---|
| 성공 | `SUCCESS` (2xx) | `SENT` |
| 결과 모름 | `TIMEOUT`, `IO_ERROR`, **모든 5xx**(`HTTP_5XX`), Resend 409(`HTTP_409`), `UNKNOWN_CRASH`, null | 같은 키로만 재시도. 종결 시 `UNCERTAIN` |
| 확실한 거절, 재시도 | `CONNECT_FAILED`(연결 수립 실패), `RATE_LIMITED`(429), `NOT_SENT`(예약했지만 시간이 모자라 보내지 않음) | 2초·4초 대기, 429는 `retry-after`/`retry_after` |
| 확실한 거절, 다음 창 | `QUOTA_DAILY`(Resend `daily_quota_exceeded`, HTTP를 받았으므로 시도·예약에 포함) | 비동기는 다음 UTC 00:00, 동기는 종결 |
| 확실한 거절, 종결 | `QUOTA_MONTHLY`, `REQUEST_4XX`(400·422 등), `ACCOUNT_4XX`(401·403), `REDIRECTED`(3xx, 따르지 않음), `WEBHOOK_404`, `WEBHOOK_401`, `BLOCKED_ADDRESS` | 종결. **구독을 끄는 것은 `WEBHOOK_404`(#7)뿐**이다. 범용·계정 4xx는 작업 실패와 지표로만 남긴다 |

Resend 공식 오류 표에는 수신자 주소 전용 오류가 없다(12 §7). 그래서 이메일 구독을 자동으로 끄는 경로는 만들지 않았다. "Resend는 실패 응답을 저장하지 않는다"는 주장은 공식 문서에 없어 채택하지 않았다.

## 5. 키와 본문 (12 §3, 14-2)

- 키: 작업마다 UUID다(`notification.idempotency_key`). 해시: **실제 공급자 요청 바이트 전체**의 SHA-256이다(`payload_sha256`). 수신자·발신자·제목·본문·토큰 링크가 모두 들어간다. 원문·암호문 본문과 토큰은 저장하지 않는다.
- 앞선 시도가 모두 확실한 거절이면 새 UUID 키를 쓴다. 보낸 것이 없으니 중복이 생길 수 없다.
- 결과 모름 시도가 있으면 **자격을 먼저 다시 본다**: 구독·종류·조건·공고 유효성. #6에서는 토큰·버전·주소다. 대상 집합이나 자격이 하나라도 다르면, 해시가 같아도 새 R과 HTTP 없이 `UNCERTAIN`으로 끝낸다. 같을 때만 같은 키와 같은 해시로 재시도하고, 해시가 다르면 `UNCERTAIN`/`BODY_CHANGED`다. 남겨 둔 item은 중복 방지용일 뿐 전송 허가가 아니다.
- 본문에는 현재 시각에 따라 바뀌는 값을 넣지 않는다(#7: `batch_key` 날짜 기준).

## 6. 수치 (ADR-0014에 반영)

| 항목 | 값 |
|---|---|
| 작업당 시도 | 최대 3. 실패 뒤 대기 2초, 4초 |
| 동기 요청 예산 | 요청 시작부터 단조 시계로 25초. 남은 시간이 5초 미만이면 새 시도를 시작하지 않는다. HTTP 타임아웃 = min(10초, 남은 시간 − 정리 몫 2초)이고 R 뒤 HTTP 직전에 다시 계산한다. 1초 미만이면 보내지 않고 `NOT_SENT`. 429의 대기가 예정 대기보다 길면 그 요청은 끝낸다. graceful shutdown은 30초다 |
| 비동기 수명(#7) | `created_at + 23시간`(자체 정책이며 Resend 키 24시간 보관보다 짧음). 넘거나 다음 시도가 넘으면 HTTP 없이 종결 |
| 회수 | `SENDING` 5분 초과 |
| 발송 상한 | 이메일 90/UTC 날짜, 디스코드 300/시간. 시도마다 R에서 예약 |
| 요청 제한 | 신청·재발송·웹훅 테스트: IP당 10분 10회, 대상당 1시간 3회. 토큰 실패: IP당 10분 30회(넘으면 토큰을 검사하지 않고 429) |
| 정리 주기 | 5분(`alerts.maintenance-interval`). PENDING 만료, DISABLED 30일, 종결 작업 30일, 끝난 제한 창, 동기 정리 |

## 7. 동기 경로 세 개

| 경로 | 행 | 자격 재확인(`stillEligible`) | 응답 |
|---|---|---|---|
| 확인 메일 `EMAIL_CONFIRM` | 신청 트랜잭션에서 PENDING과 함께 만든다(`batch_key`=UUID) | PENDING, 이 요청 토큰의 해시, 만료 전 | 고정 응답 |
| 관리 링크 `EMAIL_RECOVER` | ACTIVE를 찾았을 때만 | ACTIVE, 같은 `token_version`, 같은 주소 | 고정 응답(있든 없든, 실패든) |
| 디스코드 테스트 `DISCORD_TEST` | 성공했을 때만 사후에 `SENT`와 시도 행을 넣는다 | 없음(행이 없음). 시도마다 디스코드 시간당 상한을 예약 | 성공·이미 등록·404·실패 |

`EMAIL_CONFIRM`이 `SENT`여도 구독은 `PENDING`이다. 유효한 확인 POST만 `ACTIVE`로 바꾼다(13-1, 14-1). 루프 중 해지·만료로 행이 지워지면 0행 갱신으로 조용히 끝난다.

## 8. #7이 쓸 것과 지킬 것

- `DeliveryLedger.claimDue`·`reserve`·`lockReserved`·`recordOutcome`·`finish`·`recoverAsync`와 `RateLimiter.EMAIL_TOTAL`·`DISCORD_TOTAL`.
- `CatalogRepository.appendConditions`: 목록과 같은 술어다. 별칭은 `p`.
- 조건 탈락 item 삭제는 결과 모름 시도가 없을 때만 한다. `FAILED`(확실한 미발송)로 끝난 작업의 item은 반환(삭제)하고 `UNCERTAIN`은 남긴다(12 §4).
- 디스코드 404 비활성화(TX3), RFC 8058 헤더, 묶음 생성, outbox 훅, 지표.
- 웹훅 URL 복호화는 `AlertsCrypto.decryptWebhook(id, enc, keyId)`이다. 키 ID가 다르면 실패한다.
