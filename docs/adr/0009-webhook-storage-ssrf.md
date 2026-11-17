# ADR-0009 웹훅 저장(AES-GCM)과 SSRF 방어

- **상태**: 채택 (2026-11-08)
- **맥락**: 사용자의 디스코드 웹훅 URL은 그 자체가 비밀값이다(토큰 포함). 사용자가 입력한 URL로 서버가 요청을 보내므로 SSRF 위험이 있다 (PRD FR-A2).
- **선택지**: 평문 / **JDK AES-256-GCM** / 별도 키 관리 서비스 · SSRF 대응: HttpClient5 + DnsResolver / **JDK HttpClient + 사전 조회**
- **결정**
  - **저장**: AES-256-GCM, 암호화할 때마다 12바이트 새 nonce, AAD = 구독 ID, `webhook_key_id` 기록. 중복 판별과 URL 재입력 관리용으로 `dest_hmac` = HMAC-SHA256(별도 키, 정규화한 URL)
  - **검증**: URL을 파싱해서 scheme `https`, 호스트가 정확히 `discord.com` 또는 `discordapp.com`, 기본 포트, 경로가 `/api/webhooks/{숫자}/{토큰}`, userinfo 없음을 확인한다
  - **전송**: JDK HttpClient(`Redirect.NEVER`). 전송 직전에 `InetAddress`로 조회한 모든 주소가 사설, 루프백, 링크로컬, 사이트로컬(IPv4/IPv6)이 아닌지 확인한다. `wait=true`, `allowed_mentions: {"parse": []}`
- **결과**: 의존성이 없다. 남는 위험은 조회 시점과 연결 시점 사이의 DNS 변경인데, 허용 호스트가 디스코드 도메인으로 고정돼 있어서 디스코드 DNS를 공격자가 통제할 때만 생긴다. 유사 도메인, userinfo, IP 주소, 리다이렉트 입력을 테스트로 막는다. 키를 잃으면 디스코드 구독을 재등록해야 하므로 키 백업을 runbook에 둔다.

## 개정 (2026-11-16, #6)
- 호스트 `discordapp.com`도 받되 `discord.com`으로 정규화한다. 그래서 같은 웹훅의 두 표기가 `dest_hmac` 하나가 된다. 경로 토큰은 `[A-Za-z0-9._-]{1,100}`이고 쿼리·프래그먼트는 거부한다. 화면·로그·`toString`에는 토큰이 나오지 않는다.
- 막는 주소: 루프백, any-local, 링크로컬, 사설(site-local), 멀티캐스트, 0/8, 100.64/10(CGNAT), 198.18/15, 255.x, IPv6 fc00::/7. IPv4-mapped IPv6는 IPv4로 검사한다. 조회에 실패해도 막는다. 3xx는 따르지 않고 실패로 본다.
- 주소 확인과 요청은 전달받은 전송 타임아웃 하나를 나눠 쓴다(2026-11-17, PR #13 수정 C5). 조회는 별도 데몬 스레드에서 돌고 타임아웃까지만 기다린다. 끝나지 않으면 막은 것으로 보고(`BLOCKED_ADDRESS`) 요청을 시작하지 않는다. 확인 뒤 남은 시간이 없으면 `NOT_SENT`다. JDK 조회는 중단할 수 없으므로, 버려진 조회 스레드는 OS 리졸버의 타임아웃에 끝난다.
- 등록은 테스트를 먼저 한다. 2xx면 `ACTIVE` 행과 `DISCORD_TEST` 성공 기록을 함께 넣는다. 실패하면 웹훅에 관해 아무것도 저장하지 않는다. 이미 PENDING·ACTIVE인 웹훅에는 테스트를 보내지 않는다. 구현은 [#6 계약](../../collab/2026-11-16-implementation/claude-subscriptions-contract.md).
