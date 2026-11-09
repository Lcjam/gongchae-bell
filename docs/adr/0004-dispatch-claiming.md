# ADR-0004 발송 선점: SKIP LOCKED + 구독 행 잠금 + 회수

- **상태**: 채택 (2026-11-08)
- **맥락**: 두 앱이 같은 발송 작업을 동시에 보내면 안 된다. 해지나 조건 수정과 발송이 겹쳐도 해지한 사람에게 새로 발송하면 안 된다 (PRD FR-A3, FR-A4).
- **선택지**: 조건부 UPDATE만 사용 / **`FOR UPDATE SKIP LOCKED` 선점** / 외부 큐
- **결정**
  1. 선점: `PENDING`과 `RETRY` 중 `next_attempt_at <= now()`인 행을 `FOR UPDATE SKIP LOCKED LIMIT n`으로 고른다 → `SENDING`, `claimed_at = now()` → 커밋
  2. 전송 직전에 **구독 행을 `FOR SHARE`로 잠근다**. 상태가 `ACTIVE`인지, 조건이 아직 맞는지 다시 확인하고, 맞지 않으면 `CANCELLED`로 둔다. 해지와 수정은 같은 구독 행을 `FOR UPDATE`로 잠근다 → 둘이 직렬화된다
  3. 전송 결과에 따라 `SENT`, `RETRY`(+`next_attempt_at`), `FAILED`로 바꾼다
  4. 회수: `SENDING`인 채로 회수 시간을 넘긴 작업은 `UNCERTAIN`을 기록하고 `RETRY`로 되돌린다
- **결과**: 외부 전송 뒤 상태를 저장하기 전에 죽으면 중복 발송이 생길 수 있다(exactly-once 불가). 이메일은 Resend 멱등 키(ADR-0007)로 줄이고, 중복과 결과 불명확은 **지표로 센다**. 이미 `SENDING`인 외부 전송은 해지로 취소할 수 없다는 점을 처리방침에 적는다.
