# #5 공고 웹 화면 (`catalog`) 구현 및 리뷰 반영 결과

- **작업 경로**: `C:/Users/lcj/orca/workspaces/my-project/issue-5-catalog-web`
- **브랜치**: `feat/#5-catalog-web`
- **기반 커밋**: `94020156e983a9e1c8617290f32b63275157ce33` (`main`)
- **PR URL**: https://github.com/Lcjam/gongchae-bell/pull/10 (Draft PR, Issue `#5` 연결, 미머지)
- **커밋 이력**:
  - `origin/main` (PR `#9`, `0bf3d40edf20d4948c919a977acccc47f668baee`) 병합 커밋: `d6ddf774da58d49ea54b36cde80d89dc119cfa51` (`merge: origin/main (#3 ingestion, PR #9) 병합 및 implementation-status 충돌 해소 (#5)`)
  - PR #10 리뷰(M1/M2/L1~L3) 문서·최종 반영 커밋: `4334af809e3d8ce97205e29dcf9a22d28fcddaa6`
  - PR #10 리뷰(M1/M2/L1~L3) 반영 코드 커밋: `e2f0bef8eab140a6e292c9905fe36a7731bc4f55` (`fix: PR #10 리뷰 M1/M2 및 L1~L3 반영 (#5)`)
  - 1차 결과 기록 커밋: `4ef78be3708e4810bc1f857cf0548aad1d28af37` (`docs: #5 공고 웹 화면 준비안 및 구현·검증 결과 기록 (#5)`)
  - 1차 검수 수정 커밋: `bab38a7f5a95bcc7d966902db4de5f8ddae5c2cf` (`fix: 오류 화면 기준 시각·2시간 지연 안내·수용 기준 테스트 보강 (#5)`)
  - 1차 구현 커밋: `13daeacc1c395af7374b33ea05ac82489c22e68a` (`feat: 공고 웹 화면(catalog)과 3년 기관 이력 구현 (#5)`)
- **상태**: `origin/main` 병합 충돌 해소 및 `origin feat/#5-catalog-web` 푸시·원격 CI 검증 완료 (PR `#10` `MERGEABLE` / `CLEAN`, 두 검수 확인 전 원격 머지 금지 유지)

---

## 1. PR #10 교차 리뷰 결정(`M1`, `M2`, `L1`~`L3`) 반영 내역

| 항목 | 결정 및 변경 내용 | 수정 파일 |
|---|---|---|
| **M1 (detail `steps`·`files`)** | `joinObjectValues` 원시 값 이어붙이기와 추측성 키(`stepNm`, `stepCn` 등)·단순 문자열 폴백을 제거했다. 공식 `data.go.kr` swagger `detail_response` 문서 및 `#4 DetailCollector` 저장 계약(`step`/`file` 래퍼 제거 객체)에 따라 의미가 확인된 `sortNo`(`전형 정보 {sortNo}`), `recrutNope`(`모집 인원`), `aplyNope`(`지원자 수`), `cmpttRt`(`경쟁률`)만 라벨과 함께 출력한다. `files`도 `atchFileNm`·`url`(`SafeUrlValidator` 검증)만 읽는다. 모르는 JSON 형식/파싱 오류 시 원시 값을 나열하지 않고 형식 확인 불가 안내를 표시한다. (**주의**: 공식 문서 기반 테스트 fixture 검증이며 실제 `detail` API 실응답 수신 검증이 아님을 명시) | `CatalogRepository.java`, `CatalogViews.java`, `job-detail.html`, `docs/implementation-status.md`, `CatalogUnitTest.java`, `CatalogIntegrationTest.java` |
| **M2 (기관 3년 기준일 유지)** | `bgng_date BETWEEN (today - 3 years) AND today` 쿼리 범위를 유지하고, 기관 페이지 상단에 `오늘까지 시작한 공고 기준`과 함께 시작일이 오늘 이후인 예정 공고는 목록 화면에서 먼저 볼 수 있으나 시작일 도래 전까지 이력 통계·목록에 포함되지 않음을 안내했다. `bgng_date = 내일` 경계 통합 테스트를 추가했다. | `institution.html`, `CatalogIntegrationTest.java` |
| **L1 (404 G2 카운터 제외)** | `CatalogController.jobDetail`, `institution`에서 `repository` 조회 성공(존재 확인) 이후에만 `metrics.recordPageView(...)`를 호출하도록 순서를 이동해 404 요청이 정상 상세/기관 조회수로 집계되지 않게 했다. | `CatalogController.java`, `CatalogIntegrationTest.java` |
| **L2 (`APP_BASE_URL` 안내)** | `.env.example`에 `APP_BASE_URL=` 키를 추가하고, `README.md`에 기본값(`http://localhost:8080`) 및 운영 시 `sitemap.xml`을 위해 정확한 공개 외부 주소를 설정해야 함을 기록했다. (runbook 연결은 M3에서 반영) | `.env.example`, `README.md` |
| **L3 (`/jobs` 빈 결과 구분)** | `CatalogRepository.hasAnyPosting()`을 추가해 기본 `status=open`에서 진행 공고가 0건이라도 DB에 마감 공고 등이 존재하면 `아직 수집된 공고가 없습니다` 대신 `현재 진행 중인 공고가 없습니다` + `전체 공고 보기`(`/jobs?status=all`) 링크를 안내하도록 개선했다. | `CatalogController.java`, `CatalogRepository.java`, `jobs.html`, `CatalogIntegrationTest.java` |

*(참고: `L4` 모바일 필터 토글 JS 유지, `L5` 합본 시 공용 테스트 컨테이너 격리 정리, `L6` 2시간 지연 배너 기준 유지는 `10-review-decisions.md` 결정에 따라 현 구조 유지)*

---

## 2. 변경 및 추가된 파일 (`main` 대비 누적 25개)

### Java (`src/main/java/kr/gongchae/bell/catalog/`)
- `CatalogController.java`: `/`, `/jobs`, `/jobs/{sn}`, `/jobs/{sn}/source`, `/institutions/{code}`, `/robots.txt`, `/sitemap.xml` 라우트, `PostingNotFoundException` / 타입 불일치 404 처리, `/jobs` 빈 DB vs 진행 0건 구분(`hasAnyPosting`), 조회 성공 후 G2 메트릭 기록
- `CatalogRepository.java`: 홈(마감 임박 `OPEN` D-0~D-3, 오늘 시작 `bgng_date = kstToday`), `/jobs` 다중 필터·정렬·페이지네이션 및 `hasAnyPosting()`, 공고 상세(`parseSteps`/`parseFiles` 공식 swagger 확인 필드 라벨링 및 미확인 형식 안전 처리), 기관 3년 통계(`[today-3y, today]` 기준 총합·연도별 합계/제외 건수·연도별 월별 공고 수·직무별 `DISTINCT sn`·페이지네이션 이력), sitemap URL 조회
- `CatalogViews.java`: 화면 DTO(`PostingDetail`에 `stepsUnknownFormat`, `filesUnknownFormat` 포함) 및 KST(`Asia/Seoul`) 기준 D-day/실효 상태 계산
- `CatalogMetrics.java`: `gongchae_page_views_total{page,traffic}` 및 `gongchae_source_redirects_total{generic,traffic}` 카운터, `user`/`bot`/`internal` 분류
- `FreshnessService.java`: `REGULAR` + `COMPLETE` 최신 `finished_at` 조회, 2시간 초과 지연 판별, `currentStatusSafe()` 폴백
- `FreshnessModelInterceptor.java`: 일반 HTML 뷰와 `/error` dispatch 모두에 `freshness` / `lastCollected` 모델 공급
- `JobSearchCriteria.java`: 허용 목록 외 파라미터 무시, `LIKE` 와일드카드 이스케이프, 검색어 길이 제한(`100`자), 양의 정수 페이지 파싱
- `SafeUrlValidator.java`: http/https 스킴·userinfo 차단·호스트 필수 검사(비표준 포트 예: `:442` 허용) 및 ALIO 상세 폴백 URL 생성

### 설정·정적 리소스·템플릿·문서 (`.env.example`, `README.md`, `docs/`, `src/main/resources/`)
- `.env.example`, `README.md`: `APP_BASE_URL` 키 및 운영 외부 주소 설정 안내 추가
- `application.yml`: `app.base-url` (`APP_BASE_URL`, 기본값 `http://localhost:8080`)
- `static/css/app.css`, `static/js/filter-toggle.js`
- `templates/fragments/layout.html`, `home.html`, `jobs.html`, `job-detail.html`, `institution.html`, `error/404.html`, `error.html`
- `docs/implementation-status.md`: `#5` 구현 범위와 함께 `detail` 단계·첨부 화면은 공식 swagger 문서 계약 fixture 검증이며 실응답 미수신 상태임을 구분 기록

### 테스트 (`src/test/java/kr/gongchae/bell/catalog/`)
- `CatalogUnitTest.java`: 6건 (`SafeUrlValidator`, KST `Clock` 기준 D-day/실효 상태, 허용 목록/와일드카드 이스케이프, 트래픽 분류, 2시간 지연·회복·DB 오류 폴백, 공식 swagger `steps`/`files` 라벨링 및 미확인 형식 raw 값 미출력)
- `CatalogIntegrationTest.java`: 6건 (홈, `/jobs` 다중 필터·정렬·페이징, 상세 XSS 이스케이프·`steps`/`files` 공식 계약 fixture 및 미확인 형식 안내, 기관 3년 연도별/직무별/페이지네이션 + `bgng_date = 내일` 제외 경계, 404 G2 카운터 불변 + HTTP 오류 기준 시각/지연/복구, 마감 공고만 있는 DB의 `/jobs` 빈 상태 구분)

---

## 3. 검증 결과 (`e2f0bef` 기준 실측)

### 3-1. Gradle 자동 테스트 및 JAR 빌드
- 명령: `.\gradlew.bat --no-daemon check bootJar --rerun-tasks` -> `BUILD SUCCESSFUL in 28s`
- JUnit XML (`build/test-results/test/TEST-*.xml`):
  - `kr.gongchae.bell.catalog.CatalogIntegrationTest`: 6 tests, 0 failures, 0 errors, 0 skipped (`3.225s`)
  - `kr.gongchae.bell.catalog.CatalogUnitTest`: 6 tests, 0 failures, 0 errors, 0 skipped (`0.329s`)
  - `kr.gongchae.bell.FoundationIntegrationTest`: 4 tests, 0 failures, 0 errors, 0 skipped (`10.974s`)
  - **합계**: 16 tests, 0 failures, 0 errors, 0 skipped

### 3-2. Orca embedded browser 검증 (`e2f0bef` 빌드 `build/libs/gongchae-bell.jar`)
- 임시 PostgreSQL 17 컨테이너(`127.0.0.1:15433`)에 검증 데이터를 적재하고 Java 21로 `gongchae-bell.jar`(`--server.port=18082`) 기동 후 Orca 내장 브라우저(`--page 6e4a670f-cdb9-4bc3-8c25-9cfe9c04d437`)로 변경 화면을 재검증함:
  - `/jobs/307028` (공식 swagger 계약 형태 `steps`/`files`): `전형 정보 1`, `모집 인원: 3명`, `지원자 수: 21명`, `경쟁률: 7.0`, `채용공고문.pdf` 출력 및 미확인 원본 필드(`숨김제목`, `recrutStepSn`) 미노출 확인
  - `/jobs/307029` (미확인 JSON 형태 `steps`/`files`): `전형 단계 정보의 형식을 확인할 수 없어 표시하지 않았습니다.`, `첨부파일 정보의 형식을 확인할 수 없어 표시하지 않았습니다.` 출력 및 raw 값(`RAW_SECRET_STEP`, `RAW_SECRET_FILE`) 미노출 확인
  - `/institutions/C0008` (오늘 시작 2건 + KST 내일 시작 `307030` 1건 시드): `기준 기간: 최근 3년 (2023-11-15 ~ 2026-11-15, 오늘까지 시작한 공고 기준)` 및 미래 시작 공고 안내 문구 출력, 내일 시작 공고 제외(`전체 2건`, `4명`) 확인
  - `/jobs` (전체 공고를 `CLOSED`로 전환 후 기본 조회): `현재 진행 중인 공고가 없습니다` 및 `전체 공고 보기` 링크 출력(`아직 수집된 공고가 없습니다` 미출력), `orca console` 오류 0건 확인
- *(이전 1차 수정본 `bab38a7`에서 360x800 모바일 필터 토글·목록→상세→기관 흐름·지연 배너/404 레이아웃(`scrollWidth=360 == innerWidth=360`) 검증 완료)*
- 검증 후 임시 Orca 탭, 임시 Java 프로세스, 임시 PostgreSQL 컨테이너(`gongchae-pg-verify`) 정리 완료

### 3-3. Gitleaks 및 원격 CI 검증
- **로컬 Gitleaks 8.30.1** (`e2f0bef` 커밋 직후):
  - `gitleaks git --redact` (19 commits, ~1.69 MB): `no leaks found`
  - `gitleaks dir --redact .` (~1.41 MB): `no leaks found`
- **원격 GitHub Actions CI**: https://github.com/Lcjam/gongchae-bell/actions/runs/43655881555 (`headSha: e2f0bef8eab140a6e292c9905fe36a7731bc4f55`, `conclusion: success`)
  - `test` (`ubuntu-24.04-arm`, `./gradlew --no-daemon check bootJar`): `success` (`56s`, https://github.com/Lcjam/gongchae-bell/actions/runs/43655881555/job/132634166541)
  - `secrets` (`ubuntu-latest`, `gitleaks/gitleaks-action@v3.0.0`): `success` (`5s`, https://github.com/Lcjam/gongchae-bell/actions/runs/43655881555/job/132634166296)

---

## 4. 문서 계약 fixture vs 실제 API 구분 및 후속 경계

1. **문서 계약 fixture vs 실제 API 검증 구분**:
   - `list` 목록 화면·품질 표시는 `fixtures/alio-public-list-sample.json` 공개 경로 실제 응답(10건 표본)과 스키마 시드 데이터를 기반으로 검증했다.
   - `detail` 전형 단계(`steps`)·첨부파일(`files`) 표시는 아직 실제 API `detail` 실응답을 수신하지 못한 상태에서 `data.go.kr` 공식 swagger 문서 스펙과 `#4 DetailCollector` 저장 계약에 맞춰 만든 테스트 fixture로 검증했다. 실제 `detail` 응답 구조가 문서와 다르면 추측 출력 대신 "형식을 확인할 수 없어 표시하지 않았습니다" 안내를 유지하고 별도 질문으로 확인한다.
2. **`#3`/`#4` 합본 후 통합 과제 (`L5` 등)**:
   - `#3`/`#4`가 `main`에 합류된 뒤 `#3`의 공용 `IntegrationTestSupport` 기반으로 통합 시 테스트 종료 정리(`@AfterEach`)를 함께 적용하고, `DetailCollector`가 `file`/`step` 래퍼를 제거해 저장한 실데이터/계약 fixture로 엔드투엔드 합본 검증을 수행한다.

---

## 5. 미변경 경계 준수

- 원본 작업폴더(`C:/Users/lcj/orca/projects/my-project`) 및 기존 사용자 변경 미수정
- `collab/2026-11-14-resume/agy-catalog-ready.md` 보존
- Flyway 마이그레이션(`V1`, `V2`) 수정·추가 없음
- 수집(`ingestion`), 구독·알림(`subscription`), 배포 코드 미변경

---

## 6. `origin/main` (PR `#9`) 병합 및 최신 CI 검증

- **병합 대상**: `origin/main` `0bf3d40edf20d4948c919a977acccc47f668baee` (PR `#9` 머지 커밋)
- **병합 커밋**: `d6ddf774da58d49ea54b36cde80d89dc119cfa51` (`merge: origin/main (#3 ingestion, PR #9) 병합 및 implementation-status 충돌 해소 (#5)`) — 기존 커밋 이력 보존 (`rebase`/`force-push` 미사용)
- **충돌 범위 및 해소**:
  - `docs/implementation-status.md` 1개 파일만 충돌 발생 (`+1/-7` 최소 정리: `#3` `FR-C7~C8` 완료 상태와 `#5` `FR-W1~W8` 완료 상태 양쪽 보존, `#4` 상세 수집기 미완료 경계 유지)
  - 앱 코드·테스트·Flyway 충돌 없음
  - Sol 격리 브랜치 `fc752ad7e78c776ac14448c42662dd1b8fffa1cd`의 테스트 기반 `collector.enabled=false`/`backfill=false` 보완은 별도 후속 테스트 PR 반영 방침에 따라 본 병합에 중복 cherry-pick하지 않음
- **최종 diff 요약 (`git diff --stat origin/main..d6ddf77`)**: `25 files changed, 3291 insertions(+), 48 deletions(-)` (`#5` 공고 웹·문서·설정 범위만 유지)
- **로컬 통합 검증 (`COLLECTOR_ENABLED=false`, `COLLECTOR_BACKFILL=false`, `.\gradlew.bat --no-daemon check bootJar --rerun-tasks`)**:
  - 결과: `BUILD SUCCESSFUL in 28s` (실제 API/수집 미호출)
  - 총 **44 tests, 0 failures, 0 errors, 0 skipped**:
    - `CatalogIntegrationTest`: 6건 통과 (`3.232s`)
    - `CatalogUnitTest`: 6건 통과 (`0.299s`)
    - `FoundationIntegrationTest`: 4건 통과 (`9.159s`)
    - `ListResponseParserTest`: 8건 통과 (`0.173s`)
    - `PostingIngestorIntegrationTest`: 20건 통과 (`2.435s`)
- **비밀 스캔**:
  - `gitleaks dir --redact`: 1.57 MB 스캔, `no leaks found`
  - `gitleaks git --redact`: 42 commits (8.28 MB) 스캔, `no leaks found`
- **원격 CI 및 PR 상태**:
  - 병합 커밋 `d6ddf774da58d49ea54b36cde80d89dc119cfa51` CI: [43686364439](https://github.com/Lcjam/gongchae-bell/actions/runs/43686364439) — `test` (`59s`) `pass`, `secrets` (`9s`) `pass`
  - PR `#10` 상태: `MERGEABLE` / `CLEAN` (원격 머지 금지 유지, 본 결과 기록 커밋으로 최종 HEAD 및 CI 갱신)
