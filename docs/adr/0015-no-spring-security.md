# ADR-0015 Spring Security를 넣지 않음 (Origin 검사)

- **상태**: 채택 (2026-11-08. codex와 agy는 도입을 제안했고, 조율자가 결정했다)
- **맥락**: 로그인이 없다. 상태 변경(구독 확인, 수정, 해지)의 권한은 폼 안의 토큰(ADR-0008)이다.
- **선택지**: Spring Security(쿠키 CSRF + 보안 헤더) / **도입하지 않고 Origin 검사와 Caddy 헤더**
- **결정**: Spring Security를 넣지 않는다.
  - 상태를 바꾸는 모든 POST에 `Origin`(없으면 `Referer`) 호스트가 우리 도메인인지 확인하는 필터를 둔다
  - 보안 헤더(HSTS, `X-Content-Type-Options`, `Content-Security-Policy`, `Referrer-Policy`)는 Caddy가 붙인다
- **결과**
  - 세션 기반 권한이 없으므로 CSRF로 위조할 대상이 없다. 공격자는 토큰 없이 수정이나 해지를 못 하고, 신청은 공격자가 직접 해도 결과가 같다
  - 의존성과 설정이 줄어든다
  - **로그인 기능을 추가하면 이 결정을 다시 검토한다**
