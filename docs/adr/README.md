# ADR 목록

결정 하나에 ADR 하나. 형식: 상태 · 맥락 · 선택지 · 결정 · 결과(트레이드오프). 3자 논의 기록은 [설계 종합](../../collab/2026-11-08-design/final.md)에 있다.

| # | 결정 |
|---|---|
| [0001](0001-java-spring-gradle.md) | Java 21 + Spring Boot 4.1 + Gradle |
| [0002](0002-single-app-feature-packages.md) | 단일 앱, 기능별 패키지 |
| [0003](0003-schedule-dedup-by-slot.md) | 스케줄 중복 방지: 회차 유니크 INSERT |
| [0004](0004-dispatch-claiming.md) | 발송 선점: SKIP LOCKED + 구독 행 잠금 + 회수 |
| [0005](0005-blue-green-caddy.md) | 무중단 배포: Caddy upstream 명시 전환 |
| [0006](0006-observability.md) | 관측: Prometheus + Grafana + 외부 감시 |
| [0007](0007-email-resend.md) | 이메일 발송: Resend HTTP API |
| [0008](0008-tokens.md) | 토큰: 확인은 무작위, 관리·해지는 HMAC 파생 |
| [0009](0009-webhook-storage-ssrf.md) | 웹훅 저장(AES-GCM)과 SSRF 방어 |
| [0010](0010-rate-limit-and-budget.md) | 요청 제한과 API 예산: DB 카운터 |
| [0011](0011-multivalue-codes-join-table.md) | 다중값 코드: 조인 테이블 |
| [0012](0012-backup-restic-pull.md) | 백업: restic + OCI Object Storage + NAS·PC pull |
| [0013](0013-erasure-journal-host-file.md) | 복원 후 해지 유지: 호스트 파일 대장 |
| [0014](0014-initial-settings.md) | 초기 설정값 |
| [0015](0015-no-spring-security.md) | Spring Security를 넣지 않음 (Origin 검사) |
