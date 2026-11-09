# ADR-0009 웹훅 저장(AES-GCM)과 SSRF 방어

- **상태**: 채택 (2026-11-08)
- **맥락**: 사용자의 디스코드 웹훅 URL은 그 자체가 비밀값이다(토큰 포함). 사용자가 입력한 URL로 서버가 요청을 보내므로 SSRF 위험이 있다 (PRD FR-A2).
- **선택지**: 평문 / **JDK AES-256-GCM** / 별도 키 관리 서비스 · SSRF 대응: HttpClient5 + DnsResolver / **JDK HttpClient + 사전 조회**
- **결정**
  - **저장**: AES-256-GCM, 암호화할 때마다 12바이트 새 nonce, AAD = 구독 ID, `webhook_key_id` 기록. 중복 판별과 URL 재입력 관리용으로 `dest_hmac` = HMAC-SHA256(별도 키, 정규화한 URL)
  - **검증**: URL을 파싱해서 scheme `https`, 호스트가 정확히 `discord.com` 또는 `discordapp.com`, 기본 포트, 경로가 `/api/webhooks/{숫자}/{토큰}`, userinfo 없음을 확인한다
  - **전송**: JDK HttpClient(`Redirect.NEVER`). 전송 직전에 `InetAddress`로 조회한 모든 주소가 사설, 루프백, 링크로컬, 사이트로컬(IPv4/IPv6)이 아닌지 확인한다. `wait=true`, `allowed_mentions: {"parse": []}`
- **결과**: 의존성이 없다. 남는 위험은 조회 시점과 연결 시점 사이의 DNS 변경인데, 허용 호스트가 디스코드 도메인으로 고정돼 있어서 디스코드 DNS를 공격자가 통제할 때만 생긴다. 유사 도메인, userinfo, IP 주소, 리다이렉트 입력을 테스트로 막는다. 키를 잃으면 디스코드 구독을 재등록해야 하므로 키 백업을 runbook에 둔다.
