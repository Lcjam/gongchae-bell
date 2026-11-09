# ADR-0010 요청 제한과 API 예산: DB 카운터

- **상태**: 채택 (2026-11-08)
- **맥락**: 신청, 재발송, 웹훅 테스트, 토큰 시도를 IP 기준과 대상 기준으로 제한하고 전체 발송에도 상한을 둬야 한다(PRD FR-A7). 외부 API는 하루 1,000회 한도다(G6). blue/green 중에는 앱이 둘이다.
- **선택지**: 앱 메모리(Bucket4j, Caffeine) / **DB 카운터**
- **결정**
  - `rate_limit(scope, key_hmac, window_start, count)`: 고정 창마다 `INSERT ... ON CONFLICT DO UPDATE SET count = count + 1 RETURNING count`로 증가시키고 한도를 넘으면 거부한다. 키는 IP나 대상의 HMAC이라 원문을 저장하지 않는다. IP는 Caddy가 넘겨준 실제 접속 주소만 믿는다
  - `api_budget(day, count)`: 외부 API 요청 **직전에 별도 트랜잭션으로 증가**시킨다(예약). 500이면 경보, 900이면 그날 호출을 중단한다. 시도별 상세는 로그와 지표로 본다
- **결과**: 두 앱과 재시작에 걸쳐 합산된다. 라이브러리가 필요 없다. 요청마다 DB 쓰기가 생기지만 해당 요청(신청 등)은 빈도가 낮다. 창이 끝난 행은 주기적으로 지운다.
