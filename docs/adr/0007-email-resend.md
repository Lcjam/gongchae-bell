# ADR-0007 이메일 발송: Resend HTTP API

- **상태**: 채택 (2026-11-08, 사용자 결정)
- **맥락**: 이메일 알림(확인 메일 + 하루 1회 묶음)에는 발송 서비스가 필요하다. OCI Email Delivery는 무료 한도가 문서끼리 충돌하고(월 3,000 대 한도표 0), 발송 리전도 보장되지 않아 제외했다.
- **선택지**: Resend / Brevo(하루 300, EU 저장, 무료 브랜딩, 로그 기본 무기한)
- **결정**: **Resend**를 HTTP API(`POST https://api.resend.com/emails`)로 호출한다. SDK 없이 JDK HttpClient를 쓴다. `Idempotency-Key`는 발송 작업 ID로 둔다.
- **결과**
  - 무료 한도는 하루 100통, 월 3,000통이다([공식](https://resend.com/pricing)). 전체 발송 상한을 하루 90통으로 둔다(ADR-0014). 이메일 구독자가 늘어 한도에 닿으면 유료 전환이나 서비스 변경을 다시 결정한다
  - **저장 위치가 미국이다** → 처리방침에 수탁자(Resend), 위탁 업무, 국외 이전을 고지한다(PRD FR-A6). 메일과 로그 보관 30일([공식](https://resend.com/security))도 함께 적는다
  - 사용자 도메인에 Resend 발신 인증(SPF, DKIM) 레코드를 등록해야 한다
  - 비동기 반송은 MVP 밖이다. API가 수신 주소 오류를 즉시 돌려주면 그 구독을 비활성화하고, 5xx와 연결 오류는 재시도만 한다(PRD FR-A5)
