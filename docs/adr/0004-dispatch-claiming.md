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

## 개정 (2026-11-16, #6 · 계약 12·13·14)
- **시도와 선점을 나눈다.** 시도는 별도 트랜잭션 R의 커밋 하나다. R은 펜싱(`status='SENDING' AND claimed_at`), `attempts < 3`, 발송 상한 예약, 시도 행(결과 null)을 함께 커밋한다. HTTP는 R 뒤에만 보내므로 공급자 요청 수는 시도 행 수 이하다. 예약은 환불하지 않는다.
- **잠금 순서**: 바깥 A가 구독 `FOR SHARE`(읽기와 본문 계산만) → R(다른 연결) → A가 notification `FOR UPDATE`로 선점·예약을 다시 확인 → HTTP 1회와 결과 커밋까지 그 잠금을 유지한다. 결과·회수·종결은 notification → attempt/item 순서다. `FOR SHARE`는 HTTP 1회(최대 10초) 동안 유지되고, 그동안 DB 연결 둘을 점유한다. 디스코드 404 비활성화는 별도 짧은 트랜잭션이다.
- **회수**: 비동기 종류만 대상이다. null 시도를 `UNKNOWN_CRASH`로 바꾸고 `claimed_at`을 지워 옛 워커의 펜싱을 끊는다. 시도와 수명(23시간)이 남으면 `RETRY`, 아니면 종결한다. 동기 종류(확인·관리 링크 메일)는 재발송하지 않고 종결만 한다.
- **종결**: 2xx면 `SENT`, 아니면 결과 모름 시도(5xx, 타임아웃, 409, 종료)가 하나라도 있으면 `UNCERTAIN`(종결 상태), 아니면 `CANCELLED`(대상 탈락) 또는 `FAILED`다. 자세한 것은 [#6 계약](../../collab/2026-11-16-implementation/claude-subscriptions-contract.md).
