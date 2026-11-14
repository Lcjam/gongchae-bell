Java 21/Spring Boot 4.1.2 앱과 PostgreSQL 17 실행·검증 기반을 추가합니다. 홈과 목록은 데이터 미수집 상태를 표시하고, 관리 포트는 기본 루프백에서만 노출합니다.

Closes #2

- Gradle Wrapper 9.9.1 체크섬, Flyway catalog/ingestion 스키마, 개발 Compose
- 실제 PostgreSQL 및 HTTP 통합 테스트 4개: 마이그레이션, 동시 회차 제약, 완전 수집 시각, 포트 분리
- ARM64 CI와 비밀값 검사, 로컬 실행 README
- 실제 공개 API fixture와 후속 구현/출시 검증 현황

검증: Windows/Java 21/Docker에서 `gradlew.bat check bootJar` 통과, 로컬 관리 readiness/Prometheus 200, 홈 360px 가로 넘침 없음. ARM64 CI는 이 PR에서 실행합니다.

리뷰: [agy](collab/2026-11-14-implementation/02-agy-foundation-review.md), Claude 검토 진행 중. [반영·검증 기록](collab/2026-11-14-implementation/03-foundation-validation.md).

수집/필터/상세/기관/구독/발송/운영 배포는 후속 이슈 범위입니다. 현재 단계는 공개 출시가 아닙니다.
