# M2 #6·#7 최소 계약 수정판 (09 정정) · claude

> 2026-11-16 · 조율: codex(M2 계약 수정 라운드)
> 읽은 것: CLAUDE.md, 이 폴더 06~11. 외부 사실은 §9에 적은 공식 페이지만 근거로 쓴다(2026-11-16 열람).
> 하지 않은 것: 09·10·11, ADR, PRD 덮어쓰기. 코드·테스트·설정·DB 수정, 실제 API·발송, 개인정보·비밀값 열람, OCI·DNS 작업.
> **09에서 유지하는 것**(11이 수용): 요청별 UUID `batch_key`, 동기 종류는 비동기 회수에서 제외, DISABLED 목적지는 새 행으로 재신청, `strict` 조건 입구, V5 단일 작성자, `TOKEN_FAIL` 순서, 조건 탈락 항목 삭제, `DISCORD_TEST`는 성공 기록만.
> **이 문서가 09를 대신하는 곳**: §2(시도·예약·선점), §3(본문과 멱등 키), §4(결과·종결), §5(수명), §6(동기 경로), §7(수신자 오류). §3 결정 1·2는 철회한다.

## 0. 09에서 고친 것

| # | 09의 문제 | 이 문서 |
|---|---|---|
| 11-1 | `attempts`를 "실제 HTTP 수", "선점 때 +1", "예약 실패 때 증가 없음"으로 동시에 정의했다 | §2. 시도는 **예약 커밋 1건**이다. 선점과는 따로 센다. 예약 뒤 종료해도 환불하지 않는다. HTTP를 받은 일 한도 429도 시도다 |
| 11-2 | 고정 멱등 키에 바뀔 수 있는 본문을 보냈다 | §3. 결과를 모르는 시도가 있으면 키와 본문을 바꾸지 않는다. 본문이 바뀌었으면 보내지 않는다. 키를 새로 만드는 것은 앞선 시도가 모두 확실한 거절일 때뿐이다 |
| 11-3 | 마지막 시도 결과만으로 최종 상태를 정했다 | §4. 시도마다 결과를 남긴다. 결과를 모르는 시도가 하나라도 있으면 2xx를 받지 않는 한 `UNCERTAIN`이다. 5xx는 "결과 모름"이다 |
| 11-4 | 범용 400/422를 3회 누적하면 구독을 끄자고 했다 | §7. 철회한다. 수신자 귀책이 명확할 때만 끄고, 지금 공식 문서로는 그런 응답이 없다. 범용·계정·서버 오류는 작업 실패와 운영 지표로 처리한다. Discord 401은 작업 실패, 404는 즉시 비활성화다 |
| 10 §2-1 | 선점 펜싱이 빠졌다 | §2·§3: `claimed_at` 펜싱 |
| 10 §2-2 | `SEND_CAP` 안내가 구독 존재를 드러낸다 | §6: 경로마다 고정 응답 하나 |
| 10 §2-3 | 동기 최악 36초가 종료 유예 30초를 넘는다 | §6: 요청 전체 마감 25초 |
| 10 §2-4 | 이미 ACTIVE인 Discord 재신청, 동시 중복, 삭제 뒤 0행, 종류 최소 1, 종결 30일 파기 | §6·§8 |
| 10 §1-7·§5 | "Resend는 실패 응답을 캐시하지 않고 2xx만 저장한다"를 공식 사실로 적었다 | **정정(미확인).** 공식 멱등 키 페이지와 이메일 발송 페이지 어디에도 그 문장과 `idempotent-replayed` 헤더가 없다(§9). §3은 실패 응답이 저장되든 아니든 안전하게 짰다 |

## 1. 기록 구조 (V5, #6 단독 작성)

M2 테이블은 4개에서 5개가 된다. 늘어나는 것은 `notification_attempt` 하나다.

| 테이블·열 | 내용 | 왜 필요한가 |
|---|---|---|
| `notification_attempt` (신규) | `id`, `notification_id` FK ON DELETE CASCADE, `attempt_no` 1~3, `started_at`, `finished_at`, `outcome`(null = 결과 모름), `http_status`. UNIQUE(`notification_id`, `attempt_no`) | ① HTTP 직전에 예약과 함께 커밋해야 한다. 종료 뒤에도 "보냈을 수 있다"가 남는다. #4 `api_attempt`와 같은 원칙이다. ② 앞선 불명확 시도를 뒤 결과가 지우지 못하게 한다(11-3). ③ FR-A5 "시도와 결과 기록". `notification` 한 행의 `last_error`로는 시도별 결과를 남길 수 없다 |
| `notification` 열 | `created_at` NOT NULL, `finished_at`(모든 종결), `attempts` 0~3(= 시도 행 수), `claimed_at`(펜싱 값), `idempotency_key` UUID NOT NULL, `body_sha256`(현재 키로 보낸 본문의 해시), `last_error` | 해시는 단방향이라 토큰 원문·암호문을 저장하지 않는다(ADR-0008 유지). ERD의 `sent_at`은 `finished_at`으로 바꾼다 |
| `subscription` | ERD에서 `perm_fail_count`를 뺀다(§7에서 쓰지 않음). 부분 유니크 `(channel, dest_hmac) WHERE status IN ('PENDING','ACTIVE')` | |
| `rate_limit` | `SEND_TOTAL`: 이메일은 키 `email`·UTC 일 창·상한 90, 디스코드는 키 `discord`·시간 창·상한 300. 예약은 상한 미만일 때만 +1 하는 단일 문장(`INSERT … ON CONFLICT DO UPDATE … WHERE count < cap RETURNING`) | |

## 2. 시도·예약·선점의 정의

| 용어 | 정의 | 상한 | 기록 |
|---|---|---|---|
| 선점 | TX1에서 `PENDING`·`RETRY` → `SENDING`, `claimed_at=now()`. **시도가 아니다** | 횟수 상한 없음. 수명 23시간 안에서만 | `claimed_at` |
| 예약 = 시도 | REQUIRES_NEW 트랜잭션 R 하나에서 다음을 함께 한다: ① 펜싱(`status='SENDING' AND claimed_at=:내 값`)과 `attempts < 3` 확인 + `attempts`+1. ② `rate_limit` +1(상한 안). ③ 시도 행 INSERT(`outcome` null). ④ 키·본문 해시 갱신(§3). 하나라도 실패하면 R 전체를 롤백한다 | 작업당 **3** | `notification_attempt` 1행 |
| HTTP | R이 커밋된 뒤에만 1회 보낸다. 그래서 공급자 요청 수 ≤ 시도 행 수 ≤ 3이다. R 커밋 뒤 HTTP 전에 종료해도 그 시도는 환불하지 않고 "결과 모름"으로 둔다 | | |
| 로컬 사전 거절 | R에서 `rate_limit`이 상한이면 롤백. HTTP·시도·예약 모두 없다 | 수명 안 | `last_error=SEND_CAP`, 다음 창으로 `RETRY` |
| 회수 | `SENDING` 5분 초과(비동기 종류만). 마지막 시도 행의 `outcome`이 null이면 `UNKNOWN_CRASH`로 적는다. `claimed_at=null`로 바꿔 옛 워커의 펜싱을 깨고, §4 규칙으로 `RETRY` 또는 종결한다 | 수명 안 | 시도 행 |

실제 HTTP를 받은 Resend `daily_quota_exceeded` 429도 시도이며 예약을 쓴다. HTTP가 없는 것은 우리 쪽 사전 상한 거절뿐이다.

## 3. 비동기 발송 트랜잭션과 본문·멱등 키 (#7)

| 단계 | 연결 | 내용 |
|---|---|---|
| TX1 선점 | A | `FOR UPDATE SKIP LOCKED`, 비동기 종류이고 `next_attempt_at <= now()`인 행. 커밋 |
| TX2 시작 | A | 구독을 `FOR SHARE`로 잠근다. `notification`은 잠그지 않고 읽기만 한다(R이 다른 연결에서 그 행을 갱신하기 때문). 펜싱이 맞지 않으면 롤백하고 끝낸다. 수명 23시간이 지났으면 종결(§5) |
| 재확인 | A | `ACTIVE`가 아니면 종결. **결과를 모르는 시도가 없을 때만** 조건 탈락 항목을 삭제한다. 남은 항목이 0이면 종결(§4) |
| 본문 | A | 현재 상태로 본문을 다시 만들고 SHA-256을 구한다. 아래 키 규칙 적용 |
| R 예약 | B(REQUIRES_NEW) | §2. 상한이면 TX2가 `RETRY`(다음 창)를 커밋하고 끝낸다 |
| HTTP | — | 1회, 10초. `FOR SHARE`를 쥔 채로 보낸다(ADR-0004) |
| 결과 | A | 시도 행 `outcome` 갱신(`WHERE outcome IS NULL`). `notification` 상태 갱신은 `WHERE status='SENDING' AND claimed_at=:내 값`으로 하고, 0행이면 롤백한다(선점 세대 바뀜). 커밋 |
| TX3 | A | Discord 404일 때만: 구독 `FOR UPDATE` → `DISABLED`(ADR-0004의 승격 회피) |

잠금 순서는 구독 → 자식이다. 회수 트랜잭션은 `notification`만 잠그므로 순환이 없다. 워커 한 스레드가 연결 2개(A, B)를 동시에 쓴다. 풀 크기는 워커 수 × 2 이상이어야 한다.

**키 규칙** (Resend가 실패 응답을 저장하는지 모른다는 전제):

| 앞선 시도 | 다음 시도 |
|---|---|
| 없음 | 작업의 키로 보내고 `body_sha256`을 기록한다 |
| 모두 **확실한 거절**(§4) | 아무것도 발송되지 않았으므로 **새 UUID 키와 현재 본문**으로 보낸다. 거절이 옛 키로 저장돼 있어도 걸리지 않고, 중복도 생기지 않는다 |
| **결과 모름**이 하나라도 있음 | 같은 키로만 보낸다. 다시 만든 본문의 해시가 `body_sha256`과 **같을 때만** 보낸다. 다르면(조건 수정, 공고 변경, 항목 삭제, `token_version` 변경) 보내지 않고 `UNCERTAIN`/`BODY_CHANGED`로 종결한다. 옛 본문 재발송(FR-A3 위반)과 다른 본문 추가 발송(중복) 모두 하지 않는다 |

Discord는 멱등 키가 없다. 같은 표를 쓰되 키만 생략한다. 결과를 모르는 시도 뒤 같은 본문을 재시도하면 중복될 수 있다(ADR-0004에서 수용). 본문은 현재 시각에 따라 바뀌는 값을 넣지 않고 `batch_key` 날짜 기준으로 만든다.

## 4. 결과 분류와 최종 상태

| 부류 | 시도 결과(`outcome`) | 다음 동작 |
|---|---|---|
| 성공 | 2xx (Resend, Discord `wait=true`) | `SENT` |
| **결과 모름** | null·`UNKNOWN_CRASH`, 요청을 보낸 뒤 타임아웃·연결 끊김, **모든 5xx**, Resend 409 두 종 | 시도가 남았으면 2초·4초 뒤 `RETRY`(같은 키, §3) |
| 확실한 거절, 재시도함 | 연결 수립 실패(`ConnectException`·연결 타임아웃), 429 속도 제한 | 429 속도 제한은 `max(예정 대기, 올림 retry-after)`, 해석할 수 없으면 예정 대기. 나머지는 2초·4초 |
| 확실한 거절, 다음 창 | Resend 429 `daily_quota_exceeded` | 다음 UTC 00:00 |
| 확실한 거절, 즉시 종결 | Resend 400·422 요청 오류, 401·403(키·도메인), 429 `monthly_quota_exceeded` / Discord 401, 404 | 종결. Discord 404는 TX3 비활성화. 나머지는 구독을 끄지 않고 경보 지표 |

- **종결 상태**: 2xx가 있으면 `SENT`다. 2xx 없이 끝날 때 결과를 모르는 시도가 하나라도 있으면 `UNCERTAIN`, 없으면 `FAILED`다(시도 0회 포함). 재확인 탈락(`NOT_ACTIVE`, `NO_ITEMS`, `SUPERSEDED`)도 같은 규칙을 따른다. 결과를 모르는 시도가 없을 때만 `CANCELLED`이고, 있으면 `UNCERTAIN`이다.
- `last_error`에는 종결 사유를 하나 적는다: `NOT_ACTIVE`, `NO_ITEMS`, `SUPERSEDED`, `BODY_CHANGED`, `EXPIRED`, `ATTEMPTS_EXHAUSTED`, `SEND_CAP`, `QUOTA_DAILY`, `QUOTA_MONTHLY`, `REQUEST_4XX`, `ACCOUNT_4XX`, `WEBHOOK_401`, `WEBHOOK_404`, `INTERRUPTED`.
- 결과를 모르는 시도 뒤에 `SENT`가 되면 중복 위험 지표를 +1 한다. 이메일은 같은 키·같은 본문이므로 Resend 멱등 처리에 맡긴다. Discord는 중복이 생겼을 수 있다.
- **항목 반환**: `FAILED`로 끝난 비동기 작업은 그 작업의 `notification_item`을 같은 트랜잭션에서 삭제한다. 그러면 다음 이메일 묶음이 아직 유효한 공고를 다시 고를 수 있다. `UNCERTAIN`은 항목을 남겨 재선택에 따른 중복을 막는다. Discord NEW 항목은 첫 관측 때만 생기므로 삭제해도 다시 생기지 않는다(누락으로 남고 지표로 본다).

## 5. 최대 수명과 UTC 이월의 한계

- **수명**: `created_at` + 23시간. 우리 정책이며 공급자 보장이 아니다. Resend 공식 문서의 "멱등 키 24시간 보관"보다 짧게 잡아, 한 키의 모든 시도가 24시간 안에 끝나게 했다.
- 선점 때 수명이 지났거나, 다음 `next_attempt_at`이 수명을 넘으면 그 자리에서 종결한다: `FAILED`/`EXPIRED`(결과 모름 없음, 항목 반환) 또는 `UNCERTAIN`/`EXPIRED`.
- 이메일 묶음은 08:00 KST(= 23:00 UTC)에 생긴다. 사전 상한이나 일 한도 429로 막히면 1시간 뒤 UTC 00:00에 다시 시도한다.
  - **한계**: 당일 만회 등으로 09:00 KST(00:00 UTC) 뒤에 생긴 작업은 다음 창이 거의 23시간 뒤라 대부분 `FAILED`/`EXPIRED`로 끝난다. NEW 항목은 반환돼 다음 날 묶음으로 간다. 그날의 D-3 마감 항목은 다음 날 D-2라 선택되지 않으므로 잃는다.
  - Discord는 시간 창이라 다음 정시에 다시 시도한다. 수명을 넘기면 NEW 알림을 잃는다.
  - 모두 `last_error`와 지표로 보이게 남긴다.

## 6. 동기 경로 (#6)

| 항목 | 계약 |
|---|---|
| 마감 | 요청 하나의 전체 마감은 **시작 + 25초**다(graceful shutdown 30초 안). "최대 3회"는 3회 **이하**라는 뜻이다. 남은 시간이 5초 미만이면 새 시도를 시작하지 않는다. 시도별 타임아웃은 min(10초, 남은 시간)이다. 대기는 2초·4초를 유지한다. 그래서 타임아웃이 두 번 나면 실제로 2회에서 끝난다 |
| 이메일 신청 TX | 같은 트랜잭션에서 다음을 한다: 제한(IP·대상) 확인 → `INSERT … ON CONFLICT (channel, dest_hmac) WHERE status IN ('PENDING','ACTIVE') DO NOTHING`. 충돌하면 기존 행을 `FOR UPDATE`로 잡고, ACTIVE면 메일 없이 끝낸다. PENDING이면 토큰 해시·만료·조건·동의를 교체한다. 마지막으로 `EMAIL_CONFIRM` 행을 넣는다(`SENDING`, `claimed_at`, `created_at`, UUID 키). 커밋 |
| 루프 | 매 시도 직전에 현재 해시를 재검사한다(09와 같음: PENDING·해시·만료, 재발송이면 ACTIVE·`token_version`). 그다음 §2의 R → HTTP → 결과 기록. 키 규칙과 종결 규칙은 §3·§4와 같다. 다만 동기는 다음 창으로 미룰 수 없다. 사전 상한(`SEND_CAP`), 일 한도 429, 마감 초과는 그 자리에서 §4 규칙으로 종결한다. 본문은 메모리의 같은 문자열이라 해시가 늘 같다 |
| 삭제된 경우 | 루프 중 해지·만료로 행이 지워지면 R의 펜싱 갱신이나 결과 갱신이 0행이 되거나 FK 위반이 난다. 이것을 정상 종료로 보고 조용히 멈춘다(500을 내지 않음) |
| 정리 | 동기 종류의 `SENDING`이 5분을 넘으면 마지막 시도 null을 `UNKNOWN_CRASH`로 적고 §4 규칙으로 종결한다. 재발송하지 않는다. 시도가 없으면 `FAILED`/`INTERRUPTED` |
| **응답** | 이메일 신청과 관리 링크 재발송은 각각 **고정 응답 한 가지**다. 신규·PENDING·ACTIVE·미등록, 발송 성공·실패, `SEND_CAP` 모두 같은 문구다. 한도 상태를 경우별로 따로 알리지 않는다. 응답 시간 차는 숨기지 않는다(07) |
| Discord 등록 | URL 검증 → `WEBHOOK_TEST` 제한 → `dest_hmac`이 PENDING·ACTIVE에 이미 있으면 **테스트를 보내지 않고** "이미 등록됨, 관리 링크는 복구 화면에서" 응답(URL 자체가 자격이다). 없으면 테스트 루프(마감 25초, 시도마다 `rate_limit` discord +1을 REQUIRES_NEW로 예약). 2xx면 한 TX에서 구독 `ACTIVE`(`confirmed_at=now()`)를 `ON CONFLICT … DO NOTHING`으로 넣는다. 0행이면 동시 등록이므로 같은 "이미 등록" 응답이다. 성공하면 `DISCORD_TEST` 행(`SENT`)과 시도 행을 결과와 함께 사후 기록한다. 실패하면 행을 만들지 않는다(검증 안 된 웹훅 URL을 남기지 않기 위해). 그래서 실패한 테스트의 시도 기록은 `rate_limit` 예약과 지표뿐이다. 이것이 §2 감사의 유일한 예외다 |
| 조건 검증 | `strict`: 비공백의 잘못된 코드는 거부하고 빈 채용 조건은 허용한다. **알림 종류는 {NEW, DEADLINE}의 비어 있지 않은 부분집합**이어야 한다 |

## 7. 수신자 오류와 비활성화 (공식 문서로 판단한 것)

- Resend [오류 표](https://resend.com/docs/api-reference/errors)에는 **수신자 주소 전용 오류가 없다.** `invalid_from_address`도 없다. 수신자를 언급하는 것은 403 `validation_error` 두 문구뿐이다. "테스트 메일은 본인 주소로만"과 "`from` 도메인 미인증"으로, 둘 다 **계정·도메인 설정 오류**다.
- [이메일 발송 문서](https://resend.com/docs/api-reference/emails/send-email)는 `to`를 "Recipient email address … Max 50"로만 설명한다. 잘못된 수신 주소가 동기 오류로 돌아오는지는 적혀 있지 않다.
- 결론: 지금 공식 문서로는 **수신자 귀책이 명확한 동기 응답을 구별할 수 없다.** FR-A5의 "API가 수신 주소 오류를 즉시 돌려주면 비활성화"는 그대로 두고, 그 조건에 해당하는 응답이 현재 없음을 ADR-0007에 기록한다. 분류기, 메시지 문자열 파싱, 누적 비활성화, 비동기 반송 처리는 만들지 않는다.
  - 400·422는 작업 `FAILED`/`REQUEST_4XX`, 401·403은 `FAILED`/`ACCOUNT_4XX`이고 경보 지표를 올린다.
  - 주소 형식은 신청 때 검사하고, 확인 메일 발송이 성공해야 ACTIVE가 된다.
- Discord: 404(10015)는 즉시 `DISABLED`, 401(50027)은 작업 `FAILED`/`WEBHOOK_401`이고 구독은 그대로다. PRD 변경은 필요 없다.

## 8. 파기

`notification`은 `status IN ('SENT','FAILED','CANCELLED','UNCERTAIN')`이고 `finished_at` + 30일이 지나면 지운다(동기 종류 포함). 시도 행은 CASCADE로 함께 지워진다. `notification_item`은 `notification_id`만 null이 되고 구독이 살아 있는 동안 남는다(ADR-0014). PENDING 만료와 DISABLED 30일 파기는 `erasure.log` 대상이 아니다(08).

## 9. 외부 사실 (공식 페이지, 2026-11-16 원문 확인)

| 사실 | 원문 | URL |
|---|---|---|
| 키 보관 24시간 | "Idempotency keys are kept in the system for 24 hours." | https://resend.com/docs/dashboard/emails/idempotency-keys |
| 같은 키 재요청 | "our API will give the same response, without actually sending the email again." 어떤 응답(성공·실패)이 저장되는지는 **명시 없음** | 같은 곳 |
| 본문이 다른 같은 키 | 409 `invalid_idempotent_request`: "Retrying this request is useless without changing the idempotency key or payload." | 같은 곳 |
| 진행 중 같은 키 | 409 `concurrent_idempotent_requests`: "it is safe to retry this request later if needed." | 같은 곳 |
| "실패 응답은 저장하지 않음", `idempotent-replayed` | 두 페이지(멱등 키·이메일 발송) 어디에도 **없음** → 10의 주장은 미확인 | 위 두 URL |
| 수신자 전용 오류 | 없음. 403 `validation_error` "You can only send testing emails to your own email address" 등 계정·도메인 문구뿐 | https://resend.com/docs/api-reference/errors |
| 일 한도 | 무료 100/일·3,000/월, UTC 자정 초기화 | https://resend.com/docs/knowledge-base/account-quotas-and-limits |
| Discord | 404 웹훅은 다시 쓰지 말 것. 429 `retry_after`는 초 단위 소수. 10015·50027 | https://docs.discord.com/developers/topics/rate-limits, https://docs.discord.com/developers/topics/opcodes-and-status-codes |

## 10. 원천 문서 변경 (09 §5에 더하거나 바꿀 것, 지금은 고치지 않음)

| 파일 | 변경 |
|---|---|
| `docs/design/erd.md` | `notification_attempt` 추가. `notification`에 `created_at`·`finished_at`·`body_sha256` 추가, `sent_at` 삭제. `subscription.perm_fail_count` 삭제. 상태 전이(§4), 파기(§8), V5 |
| ADR-0004 | §3의 TX1·TX2·R·TX3, `claimed_at` 펜싱, 회수가 `claimed_at=null`, 연결 2개 |
| ADR-0007 | §3 키 규칙, 본문 해시, 수신자 전용 오류가 없다는 사실과 비활성화하지 않는 이유(§7) |
| ADR-0014 | 시도 = 예약 커밋(작업당 3), 선점은 수명으로만 제한, 수명 23시간과 UTC 이월 한계, 동기 마감 25초 |
| `docs/design/routes.md` | 신청·재발송은 고정 응답, Discord 기존 등록 처리 |
| PRD | 변경 없음 |

## 11. 구현 분할 (09 §6 보정)

- **#6 (선행)**: V5(5개 테이블). 예약 R(펜싱·상한·시도 행·키/해시)과 §4 분류·종결 규칙을 공용 코드로 둔다. 동기 세 경로(마감 25초, 고정 응답, 0행 처리, Discord 기존 등록·`ON CONFLICT`), 동기 정리, 30일 파기, `strict` 조건(알림 종류 ≥ 1).
- **#7 (후행, #6 머지 뒤 main에서)**: TX1·TX2·TX3, 비동기 회수, 본문 재생성과 키 규칙 적용, 항목 반환, 수명·이월, 중복 위험·만료·실패 지표.
- 공용 파일은 그 시점 담당 한 명만 고친다.

## 12. 별도 게이트 (이 기술안에서 확정하지 않음)

- Resend·Discord 실제 응답 관측: 같은 키 재요청의 실제 응답, 잘못된 수신 주소에서 실제로 무엇이 오는지. 실발송 승인 뒤에만 한다.
- 처리방침의 법정 문구, 보유 기간, 수탁자·국외 이전 고지, 담당 연락처.
- 공유 90통의 전제인 "계정에 다른 송수신 없음", 발신 도메인 인증.
- Origin `null`은 Chromium 루프백까지만 관측했다. Firefox·Safari·실제 HTTPS는 아직이다.
