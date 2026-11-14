## 배경
확정 PRD/설계는 있으나 실행 코드와 CI가 없다. M1 구현 전에 Java 21 및 Spring Boot 4.1 기반의 실행·검증 경로를 만든다.

## 할 일
- Gradle Kotlin DSL 및 검증 가능한 Wrapper, Java 21 toolchain
- Spring Boot 앱, PostgreSQL 17 개발 환경, Flyway 초기 catalog/ingestion 스키마
- health/readiness, HTML 기본 레이아웃과 빈 홈
- Testcontainers 실제 PostgreSQL 스키마 검증 및 웹 smoke test
- GitHub Actions ARM64 빌드/테스트, 비밀값 스캔
- README 실행 절차 및 환경변수 이름만 포함한 .env.example

## 완료 기준
- `./gradlew check bootJar` 통과 (Docker 사용, PostgreSQL 17)
- 실제 서버의 `/`, `/jobs`, `/actuator/health/readiness` 응답 확인
- 데이터베이스/관리 포트의 운영 공개 금지와 비밀값 미포함
- claude, agy 두 리뷰 결과를 반영하고 PR에 기록

후속 수집, 전체 화면, 알림, 운영/출시는 이 이슈 완료로 간주하지 않는다.
