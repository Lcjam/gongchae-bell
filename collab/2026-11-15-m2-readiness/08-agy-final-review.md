# M2 최소 계약 최종 검토 · agy

- **검토 대상**: `collab/2026-11-15-m2-readiness/04-claude-independent.md`, `05-claude-cross-review.md`, `06-codex-coordination.md`, `07-sol-review.md`와 기존 원본 문서(`docs/prd.md`, `docs/design/erd.md`, `docs/design/routes.md`, `docs/adr/0004·0007·0008·0013·0014·0015`)
- **범위**: 문서·계약 대조만 수행 (M2 구현·ADR/PRD 수정·env·개인정보·실발송·API·다른 브랜치 수정 없음)

---

## 1. 수용 (`06` 최소 계약 + `07` 보완 기준)

1. **확인 토큰(ADR-0008 유지)**: 32바이트 무작위 토큰 + DB SHA-256 해시·24시간 만료·1회 사용을 유지하고, DB 커밋 후 요청 메모리의 원문으로 제한된 동기 시도(10초 본문 포함 타임아웃, 최대 3회, 2·4초 대기, 동일 본문·멱등 키)를 수행하며 비동기 `SENDING` 회수·재발송에서 제외하는 경계를 수용한다.
2. **발송 직전 잠금(ADR-0004)**: HTTP 1회 시도 동안만 `subscription` `FOR SHARE` 및 조건 재확인을 유지하고, 시도 사이/429/상한 대기는 트랜잭션 밖 `RETRY` + `next_attempt_at`으로 넘기며, 영구 수신 오류의 `DISABLED` 전환은 별도 짧은 트랜잭션으로 분리해 잠금 승격 교착을 피하고, 모든 경로의 `구독 → 자식` 잠금 순서와 `claimed_at` 펜싱을 지키는 안을 수용한다.
3. **일일 상한·신규 기준·조건**: 이메일 공유 90통을 **UTC 날짜** 기준으로 매 수신자·매 시도 직전 원자 예약하고(`EMAIL_CONFIRM`은 다음 날 자동 재발송 미약속, 실알림만 `RETRY`), 양 채널 활성화 시각(`confirmed_at` = 이메일 확인 POST / 디스코드 테스트 성공)을 공통 `NEW` 하한으로 두며, 빈 조건 허용·비공백 잘못된 코드 오류 거부·1목적지 1활성(`PENDING`/`ACTIVE` 덮어쓰기 금지)을 수용한다.
4. **capability token POST 예외(ADR-0015)**: `Referrer-Policy: no-referrer`를 유지한 채, 쿠키/세션 없이 유효한 목적별 토큰으로 인증되는 확인·관리·해지 POST에 한해서만 `Origin: null`/부재를 허용하고 외부 불일치 `Origin`과 무토큰 POST(신청·재발송·웹훅 복구)는 엄격히 검사하며, 관리/해지 HMAC에는 24시간·1회 사용을 일괄 강제하지 않고 `RFC 8058`은 토큰+본문 전용 예외로 두는 경계를 수용한다.
5. **공유 해지 대장(ADR-0013)**: 동일 JVM 직렬화 + OS `FileChannel.lock()` + 완전 쓰기 루프 + `fsync` + 최초 생성 시 디렉터리 내구화 후 DB `DELETE`, 다음 쓰기가 잘린 줄과 붙지 않도록 개행 보정, 복원 시 비정상/잘린 줄 fail-closed(발송 재개 금지), 처리방침 "UUID는 개인정보 아님" 문구 비기재를 수용한다.

---

## 2. 충돌 및 필수 보완 (`#6`·`#7` 착수 전 고정 필요)

1. **기존 문서와의 명시적 충돌 정리 (`routes.md`, `ADR-0015`, `ADR-0004`)**:
   - `docs/design/routes.md`는 여전히 `POST /subscriptions/email`을 "`PENDING` + `EMAIL_CONFIRM` 작업"으로 적고 있고, `docs/adr/0015-no-spring-security.md`는 "상태를 바꾸는 **모든** POST에 Origin(없으면 Referer) 확인"으로 적혀 있다. 구현 시 두 문서와 `ADR-0004`(외부 HTTP 1회 동안 트랜잭션 내 `FOR SHARE` 예외 및 커넥션 점유 상한 ~10초)를 함께 개정해야 한다.
   - `erd.md` `notification.kind`의 `DISCORD_TEST`도 요청 스레드 동기 처리로 확정한 만큼(비동기 큐 미사용) 감사 기록 전용인지 enum에서 뺄지 함께 정리한다.

2. **동기 `EMAIL_CONFIRM`의 DB 기록 충돌·재시도 경계 (`06` §1 / `07` §2 보강)**:
   - `erd.md` `notification`에는 `UNIQUE (subscription_id, kind, batch_key)` 제약이 있으므로, 동기 `EMAIL_CONFIRM` 시도·결과를 `notification`에 남길 경우 재신청 시 충돌하지 않는 `batch_key`(예: 신청 요청별 단조 증가/UUID)를 정하거나 별도 시도 기록 구조를 명확히 해야 한다(`SENDING` 자동 회수 워커 조회는 `kind <> 'EMAIL_CONFIRM'` 또는 동기 기록 테이블 분리로 원천 차단).
   - 동기 재시도 `1~3`회 사이 `Idempotency-Key`로 쓸 고정 ID(발송 전 생성)를 정하고, **재시도(2·3회차) 직전 해당 `PENDING` 행의 `confirm_token_hash`가 아직 현재 스레드가 보낸 토큰과 일치하는지 확인**해 동시 재신청으로 이미 무효화된 이전 세대 확인 메일을 계속 재시도하며 UTC 90통 몫을 낭비하지 않게 막는다.

3. **비동기 작업(`#7`) 재시도·간격·`UNCERTAIN` 상한 미확정 (`05` §6-6 / `07` 잔여)**:
   - 동기 확인 메일(요청 내 최대 3회)과 달리 `EMAIL_DIGEST`·`DISCORD_NEW`·`DISCORD_DEADLINE`의 작업 단위 `RETRY` 최대 횟수·백오프 간격·`UNCERTAIN` 반복 상한 및 `FAILED` 전이 조건이 `06`과 `ADR-0014`에 아직 빠져 있다. `#7` 구현 전에 반드시 숫자와 수명(예: Resend 멱등 키 24h TTL 내)을 확정해야 무한 재시도를 막는다.

4. **조건 재확인 탈락 `notification_item` 처리 · `DISABLED` 재신청 · `#5` 필터 공유 주의**:
   - 발송 직전 조건 재확인에서 제외된 항목은 `notification_item` 행을 **삭제**해야(`05` C5), 이후 사용자가 조건을 되돌렸을 때 미발송 공고까지 PK(`subscription_id, sn, kind`)에 막혀 영구 누락되는 버그를 피한다.
   - `WHERE status IN ('PENDING','ACTIVE')` 부분 유니크 인덱스로 `1 목적지 1 활성`을 강제할 때, `DISABLED` 행(30일 보관)이 남은 목적지의 재신청(새 `PENDING` 행 허용 vs 차단)을 `#6` 계약에 명시한다.
   - `#5` 목록 필터(`JobSearchCriteria`)는 `FR-W1`에 따라 허용 목록 밖 값을 무시하지만, `#6` 구독 조건 입력은 비공백 잘못된 코드를 오류로 거부해야 한다. **술어·코드 집합은 공유하되 검증 모드(무시 vs 거부)는 분리**하도록 구현 경계에 적는다.

5. **`TOKEN_FAIL` 레이트 리밋과 `Origin: null` 허용 필터 순서 (`ADR-0010`·`ADR-0015`)**:
   - `Origin: null`/부재가 허용된 capability-token POST 경로에서도, 위조·만료·타목적 토큰 실패 시 `TOKEN_FAIL` IP당 10분에 30회 제한(`ADR-0014`)이 우회되지 않고 반드시 집계·차단되도록 필터/컨트롤러 순서를 고정한다. (운영자 수동 삭제가 있는 경우 `erasure.log` 동일 경로를 거치고, `PENDING` 만료·`DISABLED` 30일 파기는 `erasure.log` 대상에서 제외한다는 점도 `#6` 범위에 유지한다.)

6. **M2 마이그레이션 번호 (`V5` 확정 반영)**:
   - `#4` 백필 수정이 `V4`를 예약·사용하기로 확정되었으므로, M2(`#6` 구독·레이트리밋·발송 기반) 첫 Flyway 마이그레이션은 **`V5`**로 `erd.md` 및 이슈 계약에 고정한다.
