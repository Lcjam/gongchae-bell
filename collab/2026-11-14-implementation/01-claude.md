# 01 · claude · 구현 준비 1차 독립 점검

> 2026-11-14 · 조율: codex · 범위: 구현 순서, 수집/DB 동시성 함정, Spring Boot 4.1.x · Gradle · JDK 21 버전/호환성 검증
> 읽은 문서: CLAUDE.md, docs/prd.md, docs/adr/0001~0015, docs/design/{erd,routes,architecture,wireframes}.md, collab/03-feasibility.md
> 다른 에이전트의 01 파일은 보지 않고 썼다. 구현 코드와 기존 변경은 건드리지 않았다.

## 0. 요약
1. **ADR-0001 그대로 가도 된다.** Spring Boot 4.1.2(4.1.x 최신), Gradle 9.9.1, Temurin 21.0.13 조합은 공식 원본 기준으로 호환된다. 다만 Boot 4의 모듈화 때문에 **starter 이름이 바뀌었고**, Flyway는 starter를 넣지 않으면 자동 설정이 돌지 않는다 (§1-2).
2. **설계대로 구현하면 깨지는 곳이 하나 있다.** 확인 메일을 비동기 작업(`EMAIL_CONFIRM`)으로 보내는데, 확인 토큰은 해시만 저장한다(ADR-0008). 발송기가 링크를 만들 수 없다 → **결정 필요** (§4-D1).
3. 동시성의 핵심은 "어떤 트랜잭션이 언제 커밋되는가"다. 선점·예산·카운터·상태 전이는 **JPA가 아니라 명시적 SQL(JdbcClient) + 짧은 트랜잭션**으로 두기를 권한다. 아래 함정 대부분이 여기서 생긴다 (§3).

## 1. 버전·호환성 검증 (2026-11-14 조회)

| 항목 | 확인값 | 출처 |
|---|---|---|
| Spring Boot 4.1.x 최신 | **4.1.2** (Maven Central 게시 2026-10-22). 4.2.0은 RC2까지만 있다 | `repo1.maven.org/.../spring-boot/maven-metadata.xml`, `4.1.2/*.pom` Last-Modified |
| Boot 4.1.2 Java 요구 | **Java 17 이상, 27까지 호환** → 21 OK | docs.spring.io/spring-boot/4.1/system-requirements.html |
| Boot 4.1.2 빌드 도구 | **Gradle 8.x(8.14 이상)와 9.x** | 같은 페이지 |
| Gradle 최신 | **9.9.1** (current, final). 배포판 SHA-256 `d8a18a7a841dd5df825d35a685fad6f2d7f12ac0c280e57b67c27b4a520ba228` | services.gradle.org/versions/current |
| Gradle 실행 JVM | **17~27** → 이 PC의 Java 17.0.12로 Gradle을 돌릴 수 있다 | docs.gradle.org/current/userguide/compatibility.html |
| Toolchain 자동 다운로드 | Gradle에는 기본 다운로드 저장소가 없다. **settings에 `org.gradle.toolchains.foojay-resolver-convention` 1.0.0을 넣어야** "Gradle이 21을 받아 쓴다"(ADR-0001)가 성립한다 | docs.gradle.org/current/userguide/toolchains.html, plugins.gradle.org 메타데이터 |
| JDK 21 최신 | **Temurin 21.0.13+9 LTS** (linux aarch64 JRE, 2026-10-23) | api.adoptium.net/v3/assets/latest/21/hotspot |
| Boot 지원 기간 | 4.1.x OSS 지원 종료 **2027-07-31**. 4.2.x 첫 릴리스 예정일 2026-11-30 | api.spring.io/projects/spring-boot/generations |
| Boot 4.1.2 BOM 관리 버전 | Spring Framework 7.0.10 · Hibernate 7.4.13 · **Flyway 12.4.0** · PostgreSQL JDBC 42.7.14 · Testcontainers 2.0.5 · Jackson 3.1.7(+Jackson 2 2.21.7) · Thymeleaf 3.1.5 · Tomcat 11.0.27 · JUnit 6.0.3 · Micrometer 1.17.2 | `spring-boot-dependencies-4.1.2.pom` |
| Testcontainers vs Docker 29 | 이 PC의 Docker Engine은 29.9.2. Testcontainers 2.0.2에서 기본 Docker API를 1.44로 올렸으므로 2.0.5는 Docker 29와 맞는다 | GitHub releases 2.0.2 노트 "Set default docker API version to 1.44" |

### 1-1. 03-feasibility와 다른 점
- 03은 Flyway를 **13.12.1**로 적었다. 이건 Maven Central 최신값이고, **Boot 4.1.2가 관리하는 버전은 12.4.0**이다. 버전을 직접 지정해서 BOM을 덮어쓰지 말 것. `flyway-database-postgresql`도 BOM이 버전을 관리한다.
- Maven 메타데이터의 `<release>`는 지금 `4.2.0-RC2`다. `latest.release` 같은 동적 버전을 쓰면 RC가 들어온다. 버전은 고정한다.

### 1-2. Boot 4 모듈화로 바뀐 의존성 (구현 첫날 막히는 곳)
공식 마이그레이션 가이드 원문: "if you are using Flyway or Liquibase you used to only have the relevant third-party dependency. You now need to replace that with `spring-boot-starter-flyway`". 기술마다 test starter 짝이 있다.

| 용도 | main | test |
|---|---|---|
| 웹 MVC (`-starter-web`은 이름 변경 전 이름) | `spring-boot-starter-webmvc` | `spring-boot-starter-webmvc-test` |
| Thymeleaf | `spring-boot-starter-thymeleaf` | |
| JPA | `spring-boot-starter-data-jpa` | `spring-boot-starter-data-jpa-test` |
| Flyway | `spring-boot-starter-flyway` + `org.flywaydb:flyway-database-postgresql` | `spring-boot-starter-flyway-test` |
| Actuator / 지표 | `spring-boot-starter-actuator`, `micrometer-registry-prometheus` | |
| 검증 | `spring-boot-starter-validation` | |
| Testcontainers | | `spring-boot-testcontainers`, `org.testcontainers:testcontainers-postgresql`, `testcontainers-junit-jupiter` (2.x에서 아티팩트 이름 변경. 클래스는 `org.testcontainers.postgresql.PostgreSQLContainer`, 옛 패키지는 같은 jar에 남아 있다) |

- Jackson 기본이 3(`tools.jackson`)이다. 애노테이션은 `com.fasterxml.jackson.annotation` 그대로다. jsonb 컬럼(`conditions`, `changed_fields`, `steps`, `files`)용으로 Hibernate 7.4 jar에 `Jackson3JsonFormatMapper`가 있음을 확인했다. 첫 jsonb 엔티티에 왕복 통합 테스트 하나를 둔다.
- 임포트가 대량으로 깨지면 `spring-boot-starter-classic`으로 우회할 수 있지만, 새 프로젝트이므로 처음부터 모듈 starter를 쓰는 편이 낫다.

## 2. 구현 순서 제안
마일스톤 순서(M1 → M2 → M3)는 그대로 두고, M1 안에서 **환경 위험을 먼저 터뜨리는** 순서를 권한다.

| # | 묶음 | 이유와 완료 기준 |
|---|---|---|
| 1 | 빌드 골격: Gradle wrapper 9.9.1(체크섬 고정) + foojay + toolchain 21 + Boot 4.1.2 + `Clock` 빈(Asia/Seoul) + Testcontainers PG17 스모크 테스트 + Actions `ubuntu-24.04-arm`에서 `./gradlew test` | Windows Docker 29, ARM 러너의 Docker, toolchain 다운로드가 한 번에 확인된다. 이후 모든 PR이 이 CI 위에서 돈다 |
| 2 | V1 마이그레이션: `institution`, `posting`, `posting_code`, `code_name`, `posting_change`, `collect_run`, `api_budget` | M1에 필요한 것만. 구독 계열은 M2에서 V2로 추가한다(확장 방향이라 이전 버전 호환이 자연스럽다) |
| 3 | 실제 응답 fixture + API 클라이언트·파서 | **봉투 형식 주의**: 03의 실데이터는 공개 경로(`opendata.alio.go.kr`)에서 받았다. 완결성 검사가 의존하는 `resultCode`, `totalCount`의 위치가 공식 API 봉투와 같은지는 아직 확인되지 않았다. 공식 API 응답 1건(사용자 키로 1회)으로 봉투를 확정하기 전에는 파서의 봉투 부분을 확정하지 않는다 |
| 4 | 정기 수집 코어: 회차 선점 → 예산 → 호출 → 완결성 → upsert·해시·변경·상태 | FR-C2(오류/빈/잘린 응답 → 상태 변화 0), FR-C4(동일 0 / 마감일 1 / decimalDay 0), FR-C5(두 앱 동시) 테스트를 이 단계에 둔다 |
| 5 | 최초 적재 명령 (월 슬롯, 페이지 반복, 재실행) | §3-2의 재실행 규칙이 필요하다. 정기 수집을 켜기 **전에** 돌린다 (§3-6) |
| 6 | 웹: 목록 → 상세 → 기관 → 홈 → 기준 시각 푸터, 404, robots | 목록 필터는 `posting_code`에 `EXISTS`. 날짜 계산은 모두 1번의 `Clock` |
| 7 | (Should) 상세 수집 | §3-4의 부분 UPDATE 규칙 |
| M2 | V2 구독 스키마 → 토큰·Origin 필터·요청 제한 → 이메일/디스코드 신청 → 발송 작업·선점·회수 → 정기 수집에 디스코드 NEW 연결 → 이메일 묶음·마감 임박 → 처리방침 | 시작 전에 §4-D1을 정해야 한다 |
| M3 | 이미지, compose, Caddy, deploy.sh, 관측, 백업 | §3-9의 종료 설정은 M1부터 넣어 둔다 |

## 3. 수집/DB 동시성 함정

공통 권고: 회차 선점, 예산·요청 제한 카운터, 발송 선점과 상태 전이는 **JdbcClient + `TransactionTemplate`**으로 짠다. JPA는 화면 조회와 단순 엔티티에만 쓴다. 이유는 아래 3-1, 3-4, 3-8이 모두 "트랜잭션 경계가 보이지 않는다"와 "JPA가 전체 행을 덮어쓴다"에서 생기기 때문이다. `@Transactional(REQUIRES_NEW)`를 같은 빈 안에서 부르면 프록시를 거치지 않아 무시된다는 점도 피할 수 있다.

### 3-1. 회차 선점 INSERT를 긴 트랜잭션 안에 두면 두 번째 앱이 멈춘다
- 문제: ADR-0003의 `INSERT ... ON CONFLICT DO NOTHING`은 상대 트랜잭션이 **커밋되지 않았으면 끝날 때까지 기다린다**. 선점과 API 호출, upsert를 한 트랜잭션에 넣으면 green의 스케줄러 스레드가 blue의 수집이 끝날 때까지 묶인다. blue가 롤백하면 green이 같은 회차를 다시 실행한다.
- 대응: 외부 호출이 있는 작업(REGULAR, BACKFILL, DETAIL)은 **선점을 짧은 트랜잭션으로 먼저 커밋**한다 → 호출 → 쓰기 트랜잭션 → 결과 갱신. 외부 호출이 없는 작업(DIGEST, DEADLINE 작업 생성)은 반대로 **선점, 작업 생성, 결과 기록을 한 트랜잭션**에 넣는다. 짧아서 대기가 문제되지 않고, 중간에 죽어도 그날 묶음이 통째로 사라지지 않는다 (3-2).

### 3-2. 실패한 회차를 다시 실행할 방법이 없다
- 문제: UNIQUE(`kind`, `slot`)만 있으면, 최초 적재의 어떤 달이 `FAILED`/`INCOMPLETE`이거나 실행 중 죽어서 `result IS NULL`로 남았을 때 같은 달을 다시 선점할 수 없다. routes.md의 "완료된 달은 다시 실행할 때 건너뛴다"는 "완료 안 된 달은 다시 한다"를 전제한다. 정기 수집은 다음 시각이 이어 받으니 문제없지만 BACKFILL은 막힌다.
- 대응(최소): BACKFILL 선점만 재선점을 허용한다.
  ```sql
  INSERT INTO collect_run(kind, slot, started_at) VALUES ('BACKFILL', :month, now())
  ON CONFLICT (kind, slot) DO UPDATE SET started_at = now(), finished_at = NULL, result = NULL, error = NULL
  WHERE collect_run.result IN ('FAILED','INCOMPLETE')
     OR (collect_run.result IS NULL AND collect_run.started_at < now() - interval '1 hour')
  RETURNING id;
  ```
  이렇게 하면 시도 기록은 덮어써진다. 시도 이력이 필요하면 ADR-0003의 "수동 재실행은 별도 슬롯 키"를 따라 `2024-03#2`처럼 키를 바꾼다. 어느 쪽이든 ADR-0003에 한 줄 보충이 필요하다.

### 3-3. 예산 확인과 증가가 분리되면 두 앱이 900을 넘긴다
- 대응: 확인과 증가를 한 문장으로, **별도 트랜잭션에서 커밋**한다. 날짜는 KST.
  ```sql
  INSERT INTO api_budget(day, count) VALUES (:kstToday, 1)
  ON CONFLICT (day) DO UPDATE SET count = api_budget.count + 1
  WHERE api_budget.count < 900
  RETURNING count;   -- 행이 없으면 중단, count >= 500이면 경고 지표
  ```
- 재시도(ADR-0014: 최대 3회)도 시도마다 이 문장을 거친다. 수집 트랜잭션 안에서 증가시키면 수집이 롤백될 때 호출 기록도 사라져 G6 집계가 틀린다. `rate_limit`, 전체 발송 상한(`SEND_TOTAL`)도 같은 패턴이다.

### 3-4. 같은 공고 행을 쓰는 작업이 셋이다 (REGULAR, BACKFILL, DETAIL)
- 문제: 회차 유니크는 같은 종류끼리만 막는다. 정기 수집과 상세 수집, 수동 적재는 동시에 돌 수 있다. JPA는 기본으로 **모든 컬럼을 UPDATE**하므로, 상세 수집이 읽은 뒤 정기 수집이 제목을 바꾸면 상세 수집의 저장이 제목을 옛 값으로 되돌린다(잃어버린 갱신). 새 공고를 둘이 동시에 INSERT하면 PK 위반이 난다.
- 대응
  - 상세 수집은 `UPDATE posting SET steps=?, files=?, detail_status=?, detail_collected_at=? WHERE sn=?`처럼 **자기 컬럼만** 쓴다. 목록의 빈 배열이 상세를 덮지 않는다는 FR-C7 규칙도 이걸로 자연스럽게 지켜진다 (목록 경로는 이 컬럼을 아예 쓰지 않는다).
  - 목록 경로(REGULAR, BACKFILL)는 기존 행을 `SELECT ... WHERE sn = ANY(:sns) ORDER BY sn FOR UPDATE`로 잠그고 비교한 뒤 쓴다. 새 행은 `INSERT ... ON CONFLICT (sn) DO NOTHING` 후 다시 읽는다. **sn 순서로 잠가야** 두 작업 사이에 교착이 생기지 않는다.
  - 해시는 정규화한 값(다중값 코드는 정렬)으로 계산한다. 나중에 해시 정의를 바꾸면 모든 공고가 "변경"으로 잡히므로, 그때는 변경 기록 없이 해시만 다시 계산하는 마이그레이션을 쓴다.

### 3-5. 수집이 실패하는 동안 마감일이 지난 공고가 "진행 중"으로 남는다
- 문제: FR-C3은 "마감일(KST) 경과로 마감"이고 FR-C2는 "실패한 수집에서는 상태 변화 0건"이다. 마감 전이를 수집 트랜잭션에서만 하면, API가 하루 실패할 때 지난 공고가 목록과 홈 "마감 임박"에 남는다.
- 대응(최소): 마감일 판정은 **조회 시점에** 한다. 목록·홈·상세의 "진행 중"은 `status <> 'CLOSED' AND end_date >= :kstToday`로 거른다. 저장된 `status`는 `ongoingYn`과 CHECK 전이용으로 쓰고, 정기 수집 때 `end_date < today`인 행을 CLOSED로 맞춘다. 두 요구를 모두 지키고 별도 작업이 필요 없다.

### 3-6. 첫 정기 수집이 진행 중 공고 전부를 "새 공고"로 보낸다
- 문제: FR-A4의 새 공고 = "정기 수집에서 처음 관측, 최초 적재 공고 제외". 최초 적재 전에 정기 수집이 돌거나, 적재 범위 밖(시작일이 3년보다 오래된 진행 공고 등)의 공고가 있으면 첫 회차에 수백 건이 새 공고 알림으로 나간다.
- 대응: (1) 운영 순서를 runbook에 고정한다: 최초 적재 완료 → 정기 수집 활성화. (2) 코드 방어: `COMPLETE`인 REGULAR 회차가 하나도 없으면 그 회차는 **기준선**으로 보고 NEW 작업을 만들지 않는다. 테스트 하나로 고정한다.

### 3-7. 이메일 묶음의 "전날 이후" 창이 놓친 날과 신규 구독자를 잘못 다룬다
- 문제: 서버가 08:00~23:59 내내 꺼져 있으면 ADR-0003의 당일 만회도 못 한다. 다음 날 묶음이 "전날 이후"만 보면 그 사이 공고가 빠진다. 반대로 07:59에 확인한 구독자는 첫 묶음에 지난 하루 치를 받는다.
- 대응: 후보 = `first_seen_at > 구독의 confirmed_at` 이고 `from_backfill = false` 이고 `notification_item`에 아직 없는 공고. 이미 보낸 공고는 `notification_item` PK가 걸러 주므로 "전날"이라는 창이 필요 없다. 오래된 것까지 쌓이지 않게 하한(예: 7일)만 둔다. 조건을 수정했을 때 기준 시각을 바꿀지는 정하지 않았다 (현재 설계로는 수정 직후 묶음에 기존 공고 중 새 조건에 맞는 것이 들어갈 수 있다).

### 3-8. 발송 선점과 해지의 경합
- 순서 문제: 선점 커밋 후 `FOR SHARE`를 잡기 전에 해지가 끝나면 구독 행과 작업 행이 CASCADE로 사라진다. 이때 발송기가 JPA `save()`로 상태를 쓰면 **삭제된 작업 행을 다시 INSERT하려다** FK 위반이 난다.
  - 대응: 상태 전이는 `UPDATE notification SET status='SENT' ... WHERE id=? AND status='SENDING' AND claimed_at=?`처럼 조건부로 쓰고 **영향 행 수를 확인**한다. `claimed_at` 조건은 회수(5분) 뒤 다른 앱이 다시 선점한 작업을 옛 발송기가 덮어쓰는 것도 막는다. `FOR SHARE`에서 구독 행이 없으면 보내지 않고 끝낸다.
- 잠금 시간 문제: ADR-0004는 전송 직전에 구독 행을 `FOR SHARE`로 잡고 그 아래에서 외부 전송을 한다. ADR-0014의 "타임아웃 10초, 최대 3회, 간격 2·4초"를 이 잠금 안에서 그대로 돌리면 최대 약 36초 동안 트랜잭션과 커넥션을 잡고, 그동안 해지 POST가 기다린다.
  - 대응: 발송은 **잠금 안에서 1회만 시도**한다. 재시도는 작업 상태(`RETRY` + `next_attempt_at`)로 한다. 인라인 재시도는 공공데이터 API 호출에만 쓴다. 해지 쪽에는 `SET LOCAL lock_timeout`을 짧게 둬서 오래 기다리면 "잠시 후 다시" 응답을 준다.
- 빈 작업: 디스코드 NEW 작업을 먼저 만들고 항목을 `ON CONFLICT DO NOTHING`으로 넣으면, 항목이 모두 이미 보낸 것일 때 빈 작업이 남아 빈 메시지가 나간다. 항목 삽입 수가 0이면 작업을 지운다.

### 3-9. 스케줄러와 종료
- Spring Boot의 기본 스케줄러 스레드는 **1개**다(`spring.task.scheduling.pool.size`). 매분 발송, 매분 만회 확인, 매시 수집이 한 스레드를 나눠 써서 수집이 길어지면 발송이 밀린다. 풀을 3 이상으로 둔다. 최초 적재는 `@Scheduled`가 아니라 명령으로 실행한다(routes.md와 같다).
- `@Scheduled(cron = ..., zone = "Asia/Seoul")`을 명시한다. 컨테이너 기본 시간대는 UTC라서, 빠뜨리면 07분 수집은 맞아도 08:00 묶음이 17:00 KST에 돈다. 슬롯 키, D-day, "오늘 시작", 예산 날짜도 모두 KST `Clock`에서 계산한다. `LocalDate.now()`를 직접 쓰면 00:00~09:00 KST에 날짜가 하루 틀린다.
- 종료: `spring.task.scheduling.shutdown.await-termination=true`, `await-termination-period=30s`(ADR-0014의 30초). 넣지 않으면 blue/green 전환 때 옛 색의 수집이나 발송이 중간에 끊겨 회차가 `result IS NULL`로 남고 발송이 `UNCERTAIN`으로 늘어난다.
- 커넥션: 앱 두 개 × Hikari 기본 10 = 20. PostgreSQL 기본 `max_connections` 100 안이다. 발송 잠금을 3-8처럼 짧게 하면 풀 고갈 위험은 없다.

### 3-10. 해지 대장 파일 (blue/green 동시 기록)
- 두 색이 같은 호스트 디렉터리를 마운트해서 같은 `erasure.log`에 덧붙인다. 한 줄을 **한 번의 append + sync**(`Files.write(..., CREATE, APPEND, SYNC)`)로 쓰면 줄이 섞이지 않는다. 파일 기록 뒤 DB 커밋이 실패하면 대장에만 ID가 남는데, 복원 때 그 구독이 삭제되는 방향이라(사용자가 해지를 원했으므로) 안전한 쪽의 오류다.

### 3-11. PRD가 요구하는 동시성 테스트와 만드는 법
| PRD | 테스트 |
|---|---|
| FR-C5 같은 수집 1회 | 같은 Testcontainers DB에 Spring 컨텍스트 2개(스케줄러 끔)를 띄우고, `CyclicBarrier`로 같은 슬롯의 수집을 동시에 호출 → `collect_run` 1행, API 대역 호출 1회 |
| FR-A4 두 앱 동시 발송 | 같은 방식으로 발송기 2개 → 작업당 전송 1회 |
| FR-A4 선점 후 종료 | `SENDING` + 오래된 `claimed_at` 행을 직접 넣고 회수 → `RETRY`, UNCERTAIN 지표 +1 |
| FR-A4 발송 후 종료 | 대역이 전송은 성공시키고 상태 저장 전에 예외 → 회수 후 재전송 시 같은 멱등 키 |
| §7 해지와 발송 경합 | 발송기가 `FOR SHARE`를 잡은 상태에서 해지 → 해지가 전송 뒤에 끝나는지, 선점과 `FOR SHARE` 사이에 해지 → 전송 0회 |
| FR-C6 재시작 후 일 합계 | 컨텍스트를 닫았다 다시 띄워 `api_budget` 합계 유지 |

## 4. 결정이 필요한 것 (설계 문서와 어긋남)

### D1. 확인 메일을 비동기로 보내는데 확인 토큰 원문이 없다 — **M2 전에 결정 필요**
- 근거: routes.md `POST /subscriptions/email` → "`PENDING` + `EMAIL_CONFIRM` 작업". ADR-0008 "확인 토큰: 32바이트 무작위, DB에는 SHA-256 해시만". ERD의 `notification`에는 본문이나 토큰을 담는 컬럼이 없다. 발송기는 해시에서 링크를 만들 수 없다.
- 선택지
  - **(a) 권장**: 확인 토큰도 관리 토큰처럼 `HMAC(토큰 키, "confirm" | 구독 ID | token_version)`으로 파생한다. 만료는 `confirm_expires_at`, 1회 사용은 `PENDING → ACTIVE` 전이(이미 ACTIVE면 무효)로 지킨다. 재시도해도 본문이 같아서 Resend 멱등 키와 잘 맞는다. `confirm_token_hash` 컬럼과 UNIQUE가 필요 없어진다. → ADR-0008, ERD 수정.
  - (b) 신청 요청 안에서 동기로 발송한다. 토큰은 지금 설계 그대로 두고 `EMAIL_CONFIRM` 작업이 없어진다. 대신 Resend 지연과 장애가 신청 화면에 그대로 드러나고, 재시도와 발송 상한 집계를 따로 만들어야 한다.
  - (c) 발송 시점에 새 무작위 토큰을 만들어 해시를 바꾼다. 재시도 때마다 본문이 바뀌어 같은 멱등 키로 다른 본문을 보내게 된다 (Resend가 이를 어떻게 처리하는지는 확인하지 않았다).
- 규칙상 ADR 변경이므로 3자 확인 뒤 사용자 결정 대상이다.

### D2. ADR-0003 보충 (3-1, 3-2)
- BACKFILL은 실패하거나 멈춘 달을 다시 선점할 수 있어야 한다. DB만 쓰는 일일 작업은 선점과 작업 생성을 한 트랜잭션에 둔다. ADR 결정을 바꾸는 게 아니라 결과 절에 두 줄을 보충하는 수준이다.

### D3. 마감 판정 위치 (3-5)
- "조회 시점의 `end_date >= today`"를 FR-C3의 구현 방식으로 ERD 상태 전이 절에 적어 둘 것을 제안한다. PRD 수용 기준은 바뀌지 않는다.

## 5. 확인하지 못한 것
- 공식 API(`apis.data.go.kr`)의 실제 응답 봉투. 키가 필요해서 호출하지 않았다 (§2-3).
- GitHub `ubuntu-24.04-arm` 러너에서 Testcontainers가 바로 도는지. 첫 CI 실행으로 확인한다.
- Flyway 12.4.0이 PostgreSQL 17을 "검증된 버전"으로 인식하는지(경고 로그 여부). 첫 마이그레이션 실행 로그로 확인한다.
- `eclipse-temurin:21-jre` arm64 이미지는 03(2026-11-08)의 확인을 그대로 인용했고 다시 조회하지 않았다.
- `spring.task.scheduling.*` 속성 이름은 Boot 3.x까지의 이름이다. 4.1에서 바뀌지 않았는지 구현할 때 설정 메타데이터로 확인한다.
