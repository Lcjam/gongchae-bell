# 공채벨

공공기관 채용공고에서 기관의 최근 3년 채용 이력까지 이어 보고, 관심 조건의 새 공고와 마감 임박 알림을 받는 서비스입니다.

현재는 **M1·M2 핵심 소스 및 M3 운영 기반 일부가 `main`에 병합됐으나, [PRD §7](docs/prd.md#7-완료-기준-공개-출시-조건) 공개 출시 전체는 미완료** 상태입니다(2026-11-22, `main` `2090c4c` 기준 · 상세 [구현 및 출시 검증 현황](docs/implementation-status.md)).

- **M1 수집·웹**: 실행 기반, PostgreSQL 스키마, 공고 웹 화면(`/` · `/jobs` · `/jobs/{sn}` · `/institutions/{code}` · `/robots.txt` · `/sitemap.xml`)과 수집 실행기(기본값 꺼짐 `COLLECTOR_ENABLED=false`)가 병합됐습니다. 운영 DB 최초 3년 적재는 2026-11-19에 2023-11-19~2026-11-19 범위 36,879건(37페이지/HTTP 37회, 2분 20초)으로 완료했고([운영 기록](collab/2026-11-19-resume/claude-runtime-result.md), 2026-11-15 로컬 개발 DB 36,768건 실측과 구별), 정기 수집 `COMPLETE` 운영 관측은 아직 없습니다. 프론트엔드 디자인 리프레시([PR #39](https://github.com/Lcjam/gongchae-bell/pull/39))와 구조 문서·합성 QA 기록([PR #41](https://github.com/Lcjam/gongchae-bell/pull/41), [구조](docs/design/frontend-structure.md), [합성 QA](docs/qa/frontend-refresh-2026-11-22.md))도 병합됐습니다.
- **M2 알림**: 구독·관리·해지(#6, [PR #13](https://github.com/Lcjam/gongchae-bell/pull/13))와 알림 매칭·발송·관측(#7, [PR #23](https://github.com/Lcjam/gongchae-bell/pull/23)·[PR #31](https://github.com/Lcjam/gongchae-bell/pull/31)) 소스가 병합됐으나 기본값은 꺼짐(`ALERTS_ENABLED=false`)이며, 실제 이메일·Discord 전체 흐름(신청→확인/수정/발송/해지)과 개인정보·백업·초기 표지 운영 게이트가 남아 있습니다.
- **M3 운영 기반**: 수동 blue/green 배포 및 사후 롤백·외부 G4 성공, 모니터링 디렉터리 mount(9규칙) 및 공통 전달 경로 훈련, `e0208ff` 배포 및 제한 성능 실측(S1·S2, [기록](collab/2026-11-19-resume/claude-main-e020-deploy-result.md)), OCI 밖 Docker 비의존 Windows 네이티브 PC pull 소스([PR #38](https://github.com/Lcjam/gongchae-bell/pull/38), `2090c4c` 병합)가 준비됐습니다. `main` 자동 배포·실패 유도 G4, 분리된 웹/배치 경보, 실제 OCI 자동 백업·PC 영구 설치/작업 스케줄러·초기 표지/정비 도구·격리 복원 리허설(#17 미완료)·훈련 포스트모템·홍보 규정 확인은 미완료입니다.

전체 요구사항은 [PRD](docs/prd.md), 구조는 [아키텍처](docs/design/architecture.md), 작업은 [Issues](https://github.com/Lcjam/gongchae-bell/issues)에서 확인할 수 있습니다.

## 로컬 실행

필수: Java 17 이상(Gradle 실행용), Docker (Linux 컨테이너). Gradle은 Wrapper로 실행하고 앱 컴파일·실행용 Java 21은 toolchain이 다운로드합니다. 최초 실행에는 네트워크가 필요합니다.

1. `.env.example`을 `.env`로 복사하고 항목을 채웁니다. `DB_USER`, `DB_PASSWORD`는 직접 정하고, `DB_URL`에는 `jdbc:postgresql://localhost:5432/gongchae`를 넣습니다. **DB_URL을 빈 문자열로 내보내면 시작할 수 없습니다.** `APP_BASE_URL`은 기본값이 `http://localhost:8080`이며, 운영 환경에서는 `sitemap.xml`이 올바른 주소를 생성하도록 실제 공개 외부 주소(예: `https://example.com`)를 정확히 설정해야 합니다. `.env`는 git에서 제외됩니다.
2. `docker compose -f compose.dev.yml up -d --wait`
3. 같은 `DB_USER`, `DB_PASSWORD`(및 필요 시 `APP_BASE_URL`)를 앱 실행 셸의 환경변수에 설정합니다. **Spring은 `.env`를 자동으로 읽지 않습니다.**
4. `./gradlew bootRun` (Windows PowerShell은 `.\gradlew.bat bootRun`)
5. <http://localhost:8080>을 엽니다. readiness는 <http://127.0.0.1:8081/actuator/health/readiness>입니다.

개발 DB는 `127.0.0.1:5432`에서만 열립니다. 관리 포트도 기본적으로 루프백에 바인딩됩니다. 운영 배포에서는 별도의 내부 Docker 네트워크를 사용하며 관리 포트를 호스트에 공개하지 않습니다.

## 검증

```sh
./gradlew check bootJar
```

Testcontainers가 PostgreSQL 17을 실행하여 Flyway 적용, 회차 키의 동시 선점 제약, 데이터 기준 시각과 화면 응답을 검증합니다. Docker가 없으면 테스트는 실패합니다. 외부 채용/발송 API는 호출하지 않습니다. Spring 통합 테스트 컨텍스트는 `collector.enabled=false`·`collector.backfill=false`로 고정돼 있어, 셸에 수집 설정이 켜져 있어도 스케줄러나 최초 적재가 시작되지 않습니다(최초 적재 명령 테스트만 대역 클라이언트로 켭니다). 이 테스트는 전체 수집/발송 동시성 검증을 대신하지 않습니다.

JAR: `build/libs/gongchae-bell.jar`. CI는 ARM64에서 같은 명령을 실행합니다.

## 구성

- Java 21 / Spring Boot 4.1.2 / Gradle Kotlin DSL
- Thymeleaf / Bootstrap 5.3.8 (로컬 정적 파일, MIT 라이선스)
- PostgreSQL 17 / Flyway / Spring Data JPA
- Actuator / Prometheus / Testcontainers

실측하지 않은 성능·운영 수치를 완료 기록으로 쓰지 않습니다. 공개 출시 전 충족해야 할 조건은 [PRD §7](docs/prd.md#7-완료-기준-공개-출시-조건)에 있습니다.
