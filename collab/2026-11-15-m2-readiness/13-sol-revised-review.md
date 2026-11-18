# M2 수정 계약 12 독립검수 · sol

2026-11-16. `CLAUDE.md`, 이 폴더 06~12를 대조했다. 실코드·ADR/PRD·DB·env·개인정보·키에 접근하지 않았고 테스트·실제 발송/API·OCI 조작은 하지 않았다. **12는 기존 모순 대부분을 닫았으나 아래 네 가지를 구현 계약에 명시한 뒤 수용할 수 있다.** 전부 기존 보장을 구현 가능한 순서로 고정하는 보완이며 새 기능은 요구하지 않는다.

## 수용하는 수정

- **5번째 `notification_attempt`는 최소 필요성이 있다.** HTTP 전에 예약과 함께 durable하게 남는 시도, 중간 종료, 과거의 미해결 unknown, FR-A5 시도별 결과를 표현한다. `last_error` 하나로 이를 대체할 수 없다. 세 번 이하의 시도 행·FK cascade·고정 코드와 30일 파기를 두고 본문/토큰 사본을 저장하지 않는 범위는 적절하다. 기존 합의인 Discord 등록 테스트의 성공 전용 기록 예외도 명시돼 있다.
- 선점과 시도(예약 커밋)를 분리하고 R 전체 실패 시 롤백, 예약 뒤 종료해도 환불하지 않는 정의는 11-1을 닫는다. daily-quota HTTP 429도 시도/예약에 포함한다. R 뒤 outer TX가 실패해도 예약·null outcome이 남는 것이 의도된 보수적 감사다.
- 과거 unknown을 마지막 연결 거부/4xx로 지우지 않고 모든 5xx를 unknown으로 보는 점, unknown 때 새 키를 만들지 않는 점, 확실한 미발송만 항목을 반환하는 점은 11-2·3을 개선했다. Resend는 같은 키·같은 요청, Discord는 중복 가능성 관측이라는 한계도 구분했다.
- 범용 400/422 누적 비활성화·`perm_fail_count` 철회, 계정/요청 오류의 작업 실패·지표 처리와 Discord 404/401 구분은 수용한다. PRD 변경이나 메시지 문자열 분류기를 추가할 필요는 없다.
- 공급자 “실패 응답 미저장/2xx만 캐시/`idempotent-replayed`” 주장 철회는 맞다. 이번 [공식 멱등 문서](https://resend.com/docs/dashboard/emails/idempotency-keys)와 [오류 표](https://resend.com/docs/api-reference/errors)에서도 그 헤더/캐시 단정과 `invalid_from_address`는 찾지 못했다. 실제 발송 전의 문서 불확실성을 명확히 남기는 방향을 수용한다.
- 요청별 UUID, 고정 응답, ACTIVE 재신청 미덮어쓰기, 동기 종류 비동기 재발송 제외, 삭제 후 0행 정상 종료, 알림 종류 최소 1개, 모든 종결 30일 파기를 유지한 것도 적절하다.

## 필수 명시 보완

### 1. 이메일 ACTIVE는 발송 SENT가 아니라 유효한 확인 POST로만 전이

12 §7 L114의 “확인 메일 발송이 성공해야 ACTIVE가 된다”는 06·07의 확인 토큰 계약과 다르게 구현될 여지가 있다. 예를 들어 수신자가 전혀 링크를 누르지 않았는데 Resend 2xx/`EMAIL_CONFIRM=SENT`만으로 ACTIVE가 되면 double opt-in이 사라진다.

**최소 수정:** “신청·발송 결과와 구독 활성화는 분리한다. `EMAIL_CONFIRM=SENT`여도 구독은 PENDING이다. PENDING·현재 확인 토큰 hash·24시간 만료·미사용 조건을 만족하는 확인 POST가 토큰 소비와 `confirmed_at` 기록을 원자적으로 수행할 때만 ACTIVE다.”로 고정한다. 발송 결과가 unknown이었어도 실제로 도착한 유효 링크의 확인 POST는 처리할 수 있어야 한다. HTTP 성공이 수신함 배달 성공을 보장한다고도 쓰지 않는다.

### 2. unknown 때 “item 미삭제”와 “현재 자격 재검증”을 분리

12 §3 L52는 unknown이 있으면 조건 탈락 item을 삭제하지 않는다고만 한다. 본문 hash가 같아도 **현재 조건에 맞지 않을 수 있다**. 첫 시도 timeout 뒤 사용자가 NEW를 끄거나 q/지역 조건을 바꿨는데 기존 item·공고 제목·링크는 그대로라면, 조건을 본문에 쓰지 않는 템플릿의 hash는 같을 수 있다. L67의 hash 비교만으로는 그 전송을 막지 못한다. 현재 유효성을 잃은 NEW 공고도 같은 문제다.

**최소 수정:** unknown 유무와 관계없이 현재 구독·알림 종류·조건·공고 유효성으로 대상 집합을 다시 계산한다. unknown일 때는 감사/중복 방지용 item을 DB에서 보존하되, **대상 집합 또는 현재 발송 자격 불일치 자체를 `UNCERTAIN`/고정 사유로 종결하고 HTTP·새 예약을 하지 않는다**고 적는다. hash 비교는 그 다음의 추가 검사다. 해시는 HTML 조각만이 아니라 실제 공급자 요청의 수신자/발신자/제목/본문/토큰 링크/헤더 등 멱등 비교에 영향을 주는 전송 payload 전체를 대상으로 고정해야 한다. 본문 저장은 요구하지 않는다.

### 3. R 전후의 자식 잠금·HTTP 펜싱·회수 잠금 순서 고정

`REQUIRES_NEW` R 자체는 독립 커밋·추가 연결 방식으로 가능하다. 그러나 12 L59의 “회수는 notification만 잠그므로 순환 없음”은 L42의 attempt UPDATE와 맞지 않는다. L56은 결과를 **attempt → notification** 순서로 갱신하고, 회수는 **notification → attempt** 순서다.

재현 가능한 순서: 워커 A가 HTTP 부근에서 5분 이상 정지 → 회수 C가 notification 잠금 획득 → A가 깨어나 attempt outcome을 갱신해 그 행 잠금 획득 → C는 같은 attempt를 기다림 → A는 notification을 기다림. 이때 실제 행 교착이 생긴다. 또한 R 직후 장시간 정지한 A는 회수/재선점 뒤에도 HTTP 전 펜싱 검사 없이 전송할 수 있다. 결과 UPDATE 펜싱만으로 외부 전송 자체를 되돌릴 수는 없다.

**최소 명시안:**

1. A의 R 이전 단계는 `subscription FOR SHARE`와 읽기/본문 계산만 수행한다. L52의 item 삭제와 JPA 자동 flush 등 자식 DML은 뒤로 미룬다. A가 R이 갱신할 notification/attempt를 미리 잠그거나 미커밋으로 만들면 안 된다.
2. R 커밋 후 A에서 **notification을 잠근 뒤** claim·상태·예약 번호/키·현재 자격·마감 시각을 다시 확인한다. claim이 바뀌었으면 HTTP 0회로 끝낸다. 잠금은 해당 1회 HTTP와 결과 커밋까지 유지한다. R을 호출할 때부터 notification을 잠그면 독립 연결 B가 자기 외부 TX를 기다리는 문제가 생기므로 순서를 바꾸면 안 된다.
3. 결과·회수·종결/파기의 순서를 notification → attempt/item으로 통일한다(구독이 필요한 경로는 그 앞에 구독). 그 뒤 조건 탈락 item 삭제를 수행한다. 결과 0행/회수와 충돌했으면 해당 outer 변경을 롤백한다.

다른 동등한 잠금 설계를 택해도 좋지만, “순환 없음” 선언만으로 대체하지 않는다. R은 구독 잠금을 승격하거나 외부 A가 가진 자식 잠금을 기다리지 않아야 한다. R이 쓴 attempts/key/hash를 A의 오래된 엔티티 flush가 덮어쓰지 않도록 결과를 명시적 부분 UPDATE로 처리하거나 최신 값을 재조회해야 한다. [PostgreSQL 17 행 잠금·일관된 순서](https://www.postgresql.org/docs/17/explicit-locking.html), [Spring REQUIRES_NEW의 독립 TX·풀 점유](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/tx-propagation.html).

### 4. 25초는 HTTP timeout 합계만으로 보장되지 않음

L99의 총 마감 방향은 수용한다. 다만 검사/예약 R에서 DB 풀이나 행 잠금을 8초 기다린 뒤, 기다리기 전에 계산한 10초 timeout으로 HTTP를 시작하면 25초를 넘길 수 있다. 마지막 HTTP가 마감까지 전부 쓰면 결과 DB 기록 시간도 남지 않는다.

**최소 수정:** 전체 deadline에 신청 TX·connection/lock/statement 대기·R·HTTP·backoff·최종 정리를 포함한다. R 반환 뒤 HTTP 직전에도 단조 시계 기준 잔여시간을 다시 검사하고, 실제 대기/timeout을 남은 예산에 묶으며 최종 정리 몫을 확보한다. 예약 커밋 뒤 전송을 시작하지 못한 경우에도 환불하지 않고 고정 결과로 남기며, 기록이 불가능하면 이미 durable한 null/unknown 정리 경로를 쓴다. 5초 미만 새 시도 금지·작업당 최대 3회는 유지한다. 23시간은 `now >= expires_at`/`next_attempt_at >= expires_at`에서 HTTP 없이 종결하도록 경계값을 고정하고, R 후에도 만료를 재검사한다. 23시간은 공급자 24시간 보장과 다른 **자체 정책**이며 UTC 이월/D-3 소실 한계는 12 §5대로 남긴다.

## 구현에서 확인할 범위와 실사용 게이트

보완 후 **#6 개발 대역 구현은 진행 가능**하다. 개인정보 고지의 미확정만으로 기술 계약 전체를 보류할 이유는 없다. 아래 검증은 로컬 provider 대역/fixture·격리 DB로 수행하며 실제 Resend/Discord·사용자 데이터를 쓰지 않는다.

- R 커밋 직후 종료/outer rollback에도 카운터·시도 행이 함께 남음, 상한 거절 시 둘 다 0, daily HTTP 429는 소비, 실제 HTTP ≤ durable 시도 ≤ 3.
- 부모 SHARE를 가진 외부 A와 R의 진행, 5분 정지·회수와 결과의 동시 실행, 삭제 cascade, 풀 경쟁에서 교착/오래된 claim 전송/예약 값 덮어쓰기 없음. 풀 `2×워커`는 동기 요청·다른 TX의 점유를 제외한 값이므로 실제 동시 사용과 획득 timeout도 확인.
- timeout → 연결 거부/4xx/5xx 후 종결은 과거 unknown 보존, unknown 뒤 조건/종류/현재 유효성/토큰 버전/전송 payload 변화 시 HTTP 0, 동일 자격·동일 payload만 같은 키 재시도. 모든 앞 시도가 확실한 거절일 때만 새 키 허용.
- 메일 `SENT`만으로 PENDING 유지, 유효 확인 POST만 ACTIVE, GET/가짜/만료/사용된 확인 토큰은 활성화 불가. 조기 2xx·연속 timeout·긴 429·DB 대기를 조합해 3회/25초 및 23시간 경계 검증.

법정 고지·담당 연락처·상태별/공급자/백업 보유·국외 이전 근거, 실제 계정 공유90 전제와 domain/추적 설정, 수신함/실제 provider 응답, 실제 HTTPS 브라우저는 **실사용 수집·발송 전의 별도 게이트**다. 자세한 공백은 [개인정보 출처 검토](../2026-11-16-m2-privacy/sol-source-review.md)에 남겼다. 원문/키/본문 저장이나 반송 webhook·새 서비스로 해결 범위를 넓히지 않는다.
