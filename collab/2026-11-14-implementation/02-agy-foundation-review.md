# 구현 #2 (실행 기반 및 빈 상태) 사전 리뷰 · agy

> **검토 범위**: [`build.gradle.kts`](../../build.gradle.kts), [`settings.gradle.kts`](../../settings.gradle.kts), [`gradle/wrapper/gradle-wrapper.properties`](../../gradle/wrapper/gradle-wrapper.properties), [`src/`](../../src/), [`compose.dev.yml`](../../compose.dev.yml), [`README.md`](../../README.md), [`.github/workflows/ci.yml`](../../.github/workflows/ci.yml), [`collab/2026-11-14-implementation/issue-bootstrap.md`](issue-bootstrap.md)  
> **작성일**: 2026-11-14 · **검토자**: agy

---

## 1. 총평

이슈 #2 범위(Java 21 + Spring Boot 4.1.2 + Gradle Kotlin DSL 기반, Flyway 초기 스키마 `V1`, 홈/목록 빈 상태 렌더링, Actuator 관리 포트 분리, Testcontainers PostgreSQL 17 통합 테스트, ARM64 CI 및 gitleaks 설정, 로컬 개발용 `compose.dev.yml`)가 과설계 없이 깔끔하게 구성되어 있습니다. 특히 `gradle-wrapper.properties`의 SHA-256 검증(`distributionSha256Sum`), `compose.dev.yml` 및 `application.yml`의 루프백(`127.0.0.1`) 바인딩, `server.error.include-*=never` 설정이 보안 규칙에 잘 부합합니다.

다만 **이슈 #2 완료 기준 충족 및 CI/런타임 결함 방지**를 위해 아래 4건(필수 보완 2건, 권장 보완 2건)을 반영할 것을 제안합니다.

---

## 2. 필수 보완 사항 (정확성 · 실행 결함)

### 2.1 [테스트/실행] `FoundationIntegrationTest`의 `/actuator/health/readiness` 검증 누락 및 고정 관리 포트(`8081`) 충돌 위험
- **위치**: [`src/test/java/kr/gongchae/bell/FoundationIntegrationTest.java`](../../src/test/java/kr/gongchae/bell/FoundationIntegrationTest.java#L40-L51), [`src/main/resources/application.yml`](../../src/main/resources/application.yml#L22-L25)
- **문제**:
  1. [`issue-bootstrap.md`](issue-bootstrap.md#L14)의 완료 기준은 **"실제 서버의 `/`, `/jobs`, `/actuator/health/readiness` 응답 확인"**입니다. 그러나 현재 `FoundationIntegrationTest`는 기본 `WebEnvironment.MOCK`(`MockMvc`)으로만 동작하여 앱 컨텍스트의 `/actuator/prometheus`가 404인지만 확인하고(L50), 정작 **관리 포트의 `/actuator/health/readiness`가 200(`UP`)을 반환하는지 검증하는 코드가 없습니다.**
  2. `application.yml`에 `management.server.port: 8081`이 환경변수 치환 없이 고정되어 있어, 향후 `WebEnvironment.RANDOM_PORT` 테스트를 추가하거나 로컬에서 개발 서버(`8081` 점유)를 띄운 채 테스트를 돌리면 포트 충돌이 발생할 수 있습니다.
- **제안**:
  - `application.yml`의 관리 포트를 `port: ${MANAGEMENT_PORT:8081}`로 열어 둡니다.
  - 통합 테스트를 `@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")`로 구성하거나 별도 검증 메서드를 추가해:
    - 앱 포트(`@LocalServerPort`)에서는 `/`, `/jobs`, `/robots.txt`가 200이고 `/actuator/health/readiness`와 `/actuator/prometheus`가 404임을 확인하고,
    - 관리 포트(`@LocalManagementPort`)에서는 `/actuator/health/readiness`가 200(`"status":"UP"`)임을 실제 HTTP 요청으로 검증합니다.

### 2.2 [CI/실행] Windows 워크트리에서 생성된 `gradlew`의 실행 권한(`100755`) 누락 시 Linux CI 실패 위험
- **위치**: [`gradlew`](../../gradlew), [`.github/workflows/ci.yml`](../../.github/workflows/ci.yml#L20)
- **문제**:
  - Windows 환경에서 생성된 `gradlew`를 그대로 `git add`하면 Git 인덱스 파일 모드가 `100644`(실행 권한 없음)로 기록됩니다. 이 상태로 푸시하면 `ubuntu-24.04-arm` 러너에서 `./gradlew --no-daemon check bootJar` 실행 시 `Permission denied`로 즉시 실패합니다.
- **제안**:
  - 커밋 전 반드시 `git add --chmod=+x gradlew` (또는 `git update-index --chmod=+x gradlew`)를 적용해 `git ls-files -s gradlew`가 `100755`인지 확인합니다.

---

## 3. 권장 보완 사항 (성능 · 스키마 · 운영 안전성)

### 3.1 [성능/격리] `CatalogController.freshness()` 쿼리의 인덱스 부재 및 `/robots.txt` 불필요 실행
- **위치**: [`src/main/java/kr/gongchae/bell/catalog/CatalogController.java`](../../src/main/java/kr/gongchae/bell/catalog/CatalogController.java#L21-L27), [`src/main/resources/db/migration/V1__catalog_and_ingestion.sql`](../../src/main/resources/db/migration/V1__catalog_and_ingestion.sql#L61-L75)
- **문제**:
  1. `CatalogController`의 `@ModelAttribute void freshness(Model model)`는 같은 컨트롤러의 모든 핸들러 실행 전에 호출됩니다. 따라서 `@ResponseBody`인 `GET /robots.txt`(L43) 요청 시에도 매번 DB 쿼리(`SELECT max(finished_at) FROM collect_run ...`)가 실행됩니다.
  2. 모든 웹 화면 요청마다 실행되는 `SELECT max(finished_at) FROM collect_run WHERE kind = 'REGULAR' AND result = 'COMPLETE'`에 대해, 현재 `V1__catalog_and_ingestion.sql`에는 `UNIQUE (kind, slot)`만 있고 `finished_at`이나 `result` 인덱스가 없어 `REGULAR` 이력이 쌓일수록 매 요청마다 테이블/인덱스 스캔 비용이 증가합니다.
  3. `GongchaeBellApplication`에 `Clock` 빈(`Asia/Seoul`)을 등록해 두었으나 `CatalogController`에서는 `ZoneId.of("Asia/Seoul")`을 다시 하드코딩하고 있습니다.
- **제안**:
  - `V1__catalog_and_ingestion.sql`에 부분 인덱스 `CREATE INDEX collect_run_latest_complete ON collect_run(finished_at DESC) WHERE kind = 'REGULAR' AND result = 'COMPLETE';`를 추가합니다.
  - `/robots.txt`는 `@ModelAttribute`의 영향을 받지 않도록 별도 컨트롤러로 분리하거나, `freshness`를 뷰 반환 메서드에서만 호출합니다.
  - `CatalogController`에서 `Clock` 빈을 주입받아 타임존을 일관되게 사용합니다.

### 3.2 [스키마/화면/CI] `V1` 스키마 제약 일관성, 푸터 출처 표기, CI 타임아웃
- **위치**:
  - [`src/main/resources/db/migration/V1__catalog_and_ingestion.sql`](../../src/main/resources/db/migration/V1__catalog_and_ingestion.sql#L46-L58): `posting_code`에는 `ON DELETE CASCADE`와 `type CHECK` 제약이 있으나, `code_name.type`에는 `CHECK (type IN ('NCS','REGION','HIRE','EDU'))`가 없고 `posting_change.sn`에는 `ON DELETE CASCADE`가 없습니다. 제약 일관성을 맞추는 것이 안전합니다.
  - [`src/main/resources/templates/fragments/layout.html`](../../src/main/resources/templates/fragments/layout.html#L19-L22): [`docs/design/wireframes.md`](../../docs/design/wireframes.md#L9) 공통 레이아웃에 명시된 `출처: 공공데이터포털` 표기가 푸터에 빠져 있으므로 한 줄 포함을 권장합니다.
  - [`.github/workflows/ci.yml`](../../.github/workflows/ci.yml#L9-L27): `test` 및 `secrets` 잡에 `timeout-minutes: 15`(또는 `10`)를 명시해 컨테이너 기동이나 외부 다운로드 지연 시 러너가 무한 대기하지 않도록 방어합니다.
