# 02 · claude · #2 실행 기반 사전 리뷰

> 2026-11-14 · 조율: codex · 범위: issue-bootstrap.md(#2)의 실행 기반과 빈 상태 화면
> 읽은 파일: build.gradle.kts, settings.gradle.kts, gradle/wrapper/*, src/ 전체(V1, V2 포함), compose.dev.yml, README.md, .github/workflows/ci.yml, .github/PULL_REQUEST_TEMPLATE.md, .env.example, .gitattributes, .gitignore와 erd.md의 변경분, docs/implementation-status.md
> 17:26에 바뀐 `ci.yml`, `CatalogController.java`, `FoundationIntegrationTest.java`는 다시 읽고 그 버전을 기준으로 썼다. agy의 02 리뷰는 읽지 않았다. 코드는 고치지 않았다.
> D1(확인 토큰)은 요청대로 이 리뷰에서 다루지 않는다.

## 결론
**머지 전에 고칠 것은 1건(CI 액션 런타임)이다.** 나머지는 권장 수정이거나 후속 이슈로 넘길 것이다. 빌드 구성, Boot 4 starter, Flyway, 관리 포트 분리는 의도대로 동작하는 것을 직접 실행해서 확인했다.

## 직접 확인한 것
| 확인 | 결과 |
|---|---|
| Wrapper jar SHA-256 | `621008af…cdaf8`. services.gradle.org의 9.9.1 `wrapperChecksum`과 같다. 배포판 `distributionSha256Sum`도 공식 값과 같다 |
| `gradlew` 실행 비트 | index에 `100755`로 올라가 있다 (`core.filemode=false`인 Windows에서도 CI의 `./gradlew` 권한 문제 없음) |
| 로컬 테스트 | `build/test-results`: FoundationIntegrationTest 3개 통과, 실패 0 (codex 실행, 08:23Z). 17:26 이후 4개짜리 테스트는 이 결과에 없다 → 다시 실행해서 기록할 것 |
| 실제 서버 | `build/libs/gongchae-bell.jar`(17:27 빌드, V2 포함)를 **복사해서** 별도 포트(18080/18081)와 임시 PostgreSQL 17로 실행 (17:30 KST). Flyway가 마이그레이션 2개를 적용했다. `/` 200, `/jobs` 200, `/robots.txt` 200 text/plain, `/nope` 404, `/jobs/abc` 404. 관리 포트 `/actuator/health/readiness` 200 `{"status":"UP"}`, liveness 200, `/actuator/prometheus` 200(16.7KB, `http_server_requests_seconds_count` 있음). 앱 포트의 `/actuator/health`와 `/actuator/prometheus`는 404. 확인한 뒤 앱과 컨테이너를 내렸다 |
| Boot 4 starter | `spring-boot-starter-actuator` 4.1.2 POM이 `spring-boot-starter-micrometer-metrics`를 끌어온다 → Prometheus 엔드포인트가 실제로 생긴다(위 실행에서 확인) |
| 저장소 설정 | 공개 저장소. GitHub secret scanning과 push protection은 **이미 켜져 있다**. Actions 실행 기록은 아직 없다 |

## 차단 (머지 전)

### B1. CI의 모든 액션이 node20 런타임이다 → GitHub 호스트 러너에서 node20은 제거됐다
- 근거
  - 고정한 다섯 액션의 `action.yml`이 모두 `using: node20`이다: `actions/checkout@v4`, `actions/setup-java@v4`, `gradle/actions/setup-gradle@v4`, `actions/upload-artifact@v4`, `gitleaks/gitleaks-action@v2`
  - GitHub 공식 변경 공지(github.blog/changelog/2025-09-19-deprecation-of-node-20-on-github-actions-runners)의 2026-08-25 편집자 주: "Updated the Node20 removal date to **September 23rd, 2026**". 오늘은 2026-11-14다
  - gitleaks-action v3.0.0 릴리스 노트: node20이 러너에서 제거되면 "`gitleaks-action@v2` stops working regardless of any opt-out flag"
- 영향: 이 워크플로는 GitHub에서 한 번도 돌지 않았다. 첫 PR에서 secrets job이 실패할 가능성이 높다. 나머지 v4 액션은 러너가 node24로 강제 실행해 줄 수도 있지만 확인되지 않았다. 이슈 완료 기준인 "CI 통과"를 아직 증명할 수 없다.
- 최소 수정: 메이저만 올린다. 아래 태그는 모두 `using: node24`임을 확인했다(2026-11-14 latest release 기준).
  ```yaml
  actions/checkout@v7            # latest v7.0.1
  actions/setup-java@v6          # latest v6.3.0
  gradle/actions/setup-gradle@v6 # latest v6.4.1
  actions/upload-artifact@v7     # latest v7.0.2
  gitleaks/gitleaks-action@v3    # latest v3.0.0, 입력·동작 변경 없음
  ```
  첫 PR의 Actions 실행 링크를 PR 본문에 남겨야 완료 기준이 닫힌다.
- 참고(차단 아님): gitleaks는 PR에서 `GET /pulls/{n}/commits`를 부른다. 워크플로가 `contents: read`만 주지만 공개 저장소의 공개 자원이라 권한 없이 읽힌다. PR 코멘트 작성은 실패해도 경고로만 처리된다(`core.warning`). 개인 계정 저장소이므로 `GITLEAKS_LICENSE`는 필요 없다(README 원문).

## 권장 (이번 PR에서 고치면 가장 싸다)

### R1. 검증용으로 띄운 앱이 깨져 있다. 그 프로세스에서 얻은 응답을 증거로 쓰지 말 것
- 관찰: PID 10672가 17:24:51에 `build/libs/gongchae-bell.jar`로 기동했고, 17:27:16에 같은 경로의 jar가 다시 빌드됐다. 그 뒤 처음 로드되는 클래스마다 `NoClassDefFoundError: ch/qos/logback/classic/spi/ThrowableProxy`(`.tools/app.stderr.log`)가 나고 요청 스레드가 죽는다. 지금 `/`만 200이고 `/jobs`, `/robots.txt`, 8081 readiness는 **응답 없이 타임아웃**된다.
- 코드 결함은 아니다(위 "실제 서버" 행처럼 jar를 복사해서 띄우면 정상이다). 다만 이슈 완료 기준인 "실제 서버의 `/`, `/jobs`, readiness 응답 확인"을 이 프로세스로 기록하면 틀린 증거가 된다.
- 수정: PID 10672를 내리고, jar를 복사해서 실행하거나 `bootRun`으로 다시 확인한다. 기록에는 커밋 SHA와 시각을 남긴다. 나는 이 프로세스를 건드리지 않았다.

### R2. `collect_run.error varchar(100)` → 실패 기록이 실패한다
- 긴 예외 메시지를 그대로 넣으면 `value too long` 오류로 결과 UPDATE가 롤백된다. 그러면 실패한 회차가 FAILED가 아니라 `result IS NULL`(실행 중처럼 보임)로 남는다. 운영 경보("정기 수집 2회 연속 미완료")와 G3 집계가 원인을 잃는다.
- 수정: `error text`. 배포된 적이 없으므로 V1을 고치는 편이 가장 싸다(로컬 dev DB는 볼륨을 지우고 다시 만든다). V1을 고정하고 싶으면 V3에 `ALTER COLUMN error TYPE text`를 둔다. PostgreSQL에서 varchar → text는 테이블을 다시 쓰지 않는다.

### R3. `.env.example`의 빈 `DB_URL=`
- `.env`를 셸에 통째로 불러오면 `DB_URL`이 빈 문자열로 **설정된** 상태가 된다. `${DB_URL:jdbc:...}`의 기본값은 키가 없을 때만 쓰이므로 URL이 빈 값이 되어 기동에 실패한다. 비밀값이 아니니 예시 값 `jdbc:postgresql://localhost:5432/gongchae`를 넣어 둔다.

### R4. README 필수 조건 문구
- "필수: Java 21"과 바로 뒤의 "로컬에 Java 17만 있으면 … 21 toolchain을 다운로드"가 서로 어긋난다. "Java 17 이상(Gradle 실행용). Java 21은 toolchain이 받는다"가 사실이다(Gradle 9.9.1은 JVM 17~27에서 실행됨, 01-claude §1).

## 후속 (이 이슈 밖, 해당 이슈에 넘길 것)
| # | 내용 | 넘길 곳 |
|---|---|---|
| F1 | 테스트 클래스가 늘면 `@Container static` + Spring 컨텍스트 캐시 조합에서 앞 클래스가 끝나며 컨테이너가 내려가고, 캐시된 컨텍스트가 죽은 DB를 가리킨다. 두 번째 `@SpringBootTest` 클래스를 만들기 전에 공용 컨테이너(`spring-boot-testcontainers`의 `@ServiceConnection` + `@TestConfiguration` 또는 static 시작)로 옮긴다 | catalog 또는 ingestion 첫 PR |
| F2 | `posting.bgng_date`, `end_date`가 NOT NULL이다. 03은 현재 진행 공고(504건)만 확인했고 PRD §8은 오래된 데이터의 품질 차이를 가정한다. NOT NULL은 유지하되, 날짜가 비거나 형식이 틀린 행은 파서가 건너뛰고 `collect_run`에 건수로 남긴다는 규칙을 정한다. 아니면 적재 한 페이지 트랜잭션이 행 하나 때문에 통째로 실패한다 | ingestion |
| F3 | 기준 시각(`freshness`)을 핸들러마다 호출한다. FR-W5는 "모든 페이지"(상세, 기관, 오류 화면 포함)이므로 화면이 늘 때 `@ControllerAdvice`로 옮긴다 | catalog |
| F4 | `MANAGEMENT_ADDRESS` 기본값이 `127.0.0.1`이다. 컨테이너 안에서는 Prometheus와 deploy.sh가 닿지 않으므로 운영 compose에서 반드시 `0.0.0.0`(내부망 전용)으로 준다. runbook 환경변수 목록에 넣는다 | 운영 기반 |
| F5 | PRD §6 "의존성 취약점 점검": 저장소의 `dependabot_security_updates`가 꺼져 있다. 설정 하나로 켤 수 있다 | 운영 기반 또는 별도 chore |
| F6 | 브라우저의 404는 Boot 기본 오류 화면이다. 와이어프레임의 404·제한 초과·서버 오류 화면은 catalog에서 만든다 | catalog |

## 문제없음으로 본 것
- Boot BOM을 `implementation(platform(...))`로만 걸었다. `runtimeClasspath`와 `testRuntimeClasspath`가 `implementation`을 상속하므로 `runtimeOnly`(Flyway DB 모듈, JDBC, Prometheus)와 `testRuntimeOnly`의 버전도 BOM을 따른다. Flyway 버전을 직접 지정하지 않았다(01-claude §1-1과 일치)
- 관리 포트 분리, `show-details: never`, 오류 메시지·스택 숨김, `open-in-view: false`, `ddl-auto: validate`, graceful shutdown 30초(ADR-0014)
- compose 개발 DB는 `127.0.0.1`에만 바인딩된다. 자격증명은 `.env`에서만 받는다. `.gitignore`가 `.env*`(예시 파일 제외), `.tools/`, `build/`를 막는다
- V1은 ERD의 공고·실행·예산 테이블과 컬럼, 인덱스가 일치한다. erd.md에 구현 현황을 적었다. V2(완전 수집 부분 인덱스, `code_name.type` CHECK)는 확장 방향 변경이다
- 기준 시각 테스트는 INCOMPLETE가 표시 시각을 바꾸지 않는 것을 확인한다(FR-W5). 슬롯 동시 선점 테스트는 DB 제약만 증명한다. README도 그 한계를 적었다
- robots.txt가 `/subscriptions/`를 막는다(토큰 페이지)
