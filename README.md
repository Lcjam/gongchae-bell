# 공채벨

공공기관 채용공고에서 기관의 최근 3년 채용 이력까지 이어 보고, 관심 조건의 새 공고와 마감 임박 알림을 받는 서비스입니다.

현재는 **M1 구현 중**입니다. 실행 기반, PostgreSQL 스키마, 홈/목록 빈 상태만 구현됐습니다. 수집·필터·기관 상세·알림·공개 배포는 아직 제공하지 않습니다. 전체 요구사항은 [PRD](docs/prd.md), 구조는 [아키텍처](docs/design/architecture.md), 작업은 [Issues](https://github.com/Lcjam/gongchae-bell/issues)에서 확인할 수 있습니다.

## 로컬 실행

필수: Java 21, Docker (Linux 컨테이너). Gradle은 Wrapper로 실행합니다. 로컬에 Java 17만 있으면 Wrapper가 실행되며 Java 21 toolchain을 다운로드합니다. 최초 실행에는 네트워크가 필요합니다.

1. `.env.example`을 `.env`로 복사하고 `DB_USER`, `DB_PASSWORD`를 채웁니다. `DB_URL`은 `jdbc:postgresql://localhost:5432/gongchae`입니다. `.env`는 git에서 제외됩니다.
2. `docker compose -f compose.dev.yml up -d --wait`
3. 같은 `DB_USER`, `DB_PASSWORD`를 앱 실행 셸의 환경변수에 설정합니다. **Spring은 `.env`를 자동으로 읽지 않습니다.**
4. `./gradlew bootRun` (Windows PowerShell은 `.\gradlew.bat bootRun`)
5. <http://localhost:8080>을 엽니다. readiness는 <http://127.0.0.1:8081/actuator/health/readiness>입니다.

개발 DB는 `127.0.0.1:5432`에서만 열립니다. 관리 포트도 기본적으로 루프백에 바인딩됩니다. 운영 배포에서는 별도의 내부 Docker 네트워크를 사용하며 관리 포트를 호스트에 공개하지 않습니다.

## 검증

```sh
./gradlew check bootJar
```

Testcontainers가 PostgreSQL 17을 실행하여 Flyway 적용, 회차 키의 동시 선점 제약, 데이터 기준 시각과 화면 응답을 검증합니다. Docker가 없으면 테스트는 실패합니다. 외부 채용/발송 API는 호출하지 않습니다. 이 테스트는 전체 수집/발송 동시성 검증을 대신하지 않습니다.

JAR: `build/libs/gongchae-bell.jar`. CI는 ARM64에서 같은 명령을 실행합니다.

## 구성

- Java 21 / Spring Boot 4.1.2 / Gradle Kotlin DSL
- Thymeleaf / Bootstrap 5.3.8 (로컬 정적 파일, MIT 라이선스)
- PostgreSQL 17 / Flyway / Spring Data JPA
- Actuator / Prometheus / Testcontainers

실측하지 않은 성능·운영 수치를 완료 기록으로 쓰지 않습니다. 공개 출시 전 충족해야 할 조건은 [PRD §7](docs/prd.md#7-완료-기준-공개-출시-조건)에 있습니다.
