# #5 공고 웹 화면 (`catalog`) 구현 및 1차 검수 반영 결과

- **작업 경로**: `C:/Users/lcj/orca/workspaces/my-project/issue-5-catalog-web`
- **브랜치**: `feat/#5-catalog-web`
- **기반 커밋**: `94020156e983a9e1c8617290f32b63275157ce33` (`main`)
- **최종 커밋 SHA**: `bab38a7f5a95bcc7d966902db4de5f8ddae5c2cf` (`fix: 오류 화면 기준 시각·2시간 지연 안내·수용 기준 테스트 보강 (#5)`)
  - 1차 구현 커밋: `13daeacc1c395af7374b33ea05ac82489c22e68a`
- **상태**: 로컬 커밋 완료 (원격 push 및 PR 머지 없음)

---

## 1. 변경 및 추가된 파일 (누적 22개)

### Java (`src/main/java/kr/gongchae/bell/catalog/`)
- `CatalogController.java`: `/`, `/jobs`, `/jobs/{sn}`, `/jobs/{sn}/source`, `/institutions/{code}`, `/robots.txt`, `/sitemap.xml` 라우트, `PostingNotFoundException` / 타입 불일치 404 처리(안전한 기준 시각 공급 포함), G2 트래픽 메트릭 기록
- `CatalogRepository.java`: 홈 3섹션(마감 임박 `OPEN` D-0~D-3, 오늘 시작 `bgng_date = kstToday`), `/jobs` 다중 필터(`ncs`, `region`, `hire`, `career`, `status`, `q`)·정렬(`deadline`, `begin`)·페이지네이션, 공고 상세(`steps`/`files` JSONB 파싱 포함), 기관 3년 통계(총합 및 연도별 모집 예정 인원 합계·비숫자 제외 건수·연도별 월별 공고 수·직무별 `DISTINCT sn` 집계·페이지네이션 이력), sitemap URL 조회
- `CatalogViews.java`: 화면 DTO 및 KST(`Asia/Seoul`) 기준 D-day/실효 상태(`OPEN`/`CHECK`/`CLOSED`) 계산
- `CatalogMetrics.java`: `gongchae_page_views_total{page,traffic}` 및 `gongchae_source_redirects_total{generic,traffic}` 카운터, `user`/`bot`/`internal`(`X-Gongchae-Internal` 헤더 또는 모니터링 프로브 UA) 분류
- `FreshnessService.java`: `REGULAR` + `COMPLETE` 최신 `finished_at` 조회, `Clock` 기반 2시간 초과 지연 판별(`DELAY_THRESHOLD = 2h`), DB 장애 시 오류 화면 재귀 실패를 막기 위한 `currentStatusSafe()` 폴백 제공
- `FreshnessModelInterceptor.java` (1차 검수 추가): `HandlerInterceptor` + `WebMvcConfigurer`로 일반 HTML 뷰와 Spring Boot `/error` dispatch(`BasicErrorController.errorHtml`) 모두에 `freshness` / `lastCollected` 모델 공급 (리다이렉트/`@ResponseBody` 제외, `currentStatusSafe()` 사용)
- `JobSearchCriteria.java`: 허용 목록 외 파라미터 무시, `LIKE` 와일드카드(`\`, `%`, `_`) 이스케이프, 검색어 길이 제한(`100`자), 양의 정수 페이지 파싱
- `SafeUrlValidator.java`: 공백 제거, ISO 제어문자 차단, URI 구문 확인, `http`/`https` 스킴만 허용(대소문자 무시), `rawUserInfo` 차단, 공백 아닌 `host` 필수(비표준 포트 예: `:442` 허용; 브라우저 이동 목적상 사설/예약 IP 대역 차단은 수행하지 않음), ALIO 상세 폴백 URL 생성

### 설정·정적 리소스·템플릿 (`src/main/resources/`)
- `application.yml`: `app.base-url` (`APP_BASE_URL`, 기본값 `http://localhost:8080`) 위치 정렬 수정 (`spring:` 하위 속성 분리)
- `static/css/app.css`: 색상/레이아웃 스타일 및 `.pre-line-text`, `.job-card-title`의 `overflow-wrap: anywhere` 적용
- `static/js/filter-toggle.js`: Bootstrap JS 미번들 환경에서 360px 모바일 필터 패널(`#jobs-filter-panel`) 토글 및 `aria-expanded`/버튼 문구 전환
- `templates/fragments/layout.html`: 페이지별 `<title>`과 `<meta name="description">` (`canonical` 태그 없음), 공통 내비게이션, 상단 2시간 수집 지연 안내 배너(`role="status"`), 푸터 `데이터 기준` 시각 및 `수집 지연` 배지
- `templates/home.html`: 히어로/바로가기(`신입 지원 가능`, `정보통신 직무`), 마감 임박(D-0 ~ D-3), 오늘 시작한 공고(시작일 기준, 마감/확인필요 포함), `알림 기능 준비 중` 안내(`M2` 표기 제거)
- `templates/jobs.html`: 360px 접힘 필터 토글, 필터 폼(직무 NCS·근무 지역·고용 형태·채용 구분·진행 상태·정렬·공고 제목; 학력 필터는 목록에 없고 상세 화면에만 표시), `이 조건으로 알림 받기 (준비 중)` 안내(`M2` 표기 제거), 데스크톱 표/모바일 카드, 필터 상태 유지 페이지네이션
- `templates/job-detail.html`: 기간·D-day·모집 인원·직무(NCS)·근무 지역·고용 형태·학력 조건, 원문 보기(`/jobs/{sn}/source`) 및 일반 URL 안내/ALIO 링크, 기관 이력 링크, 지원 자격/우대/전형 설명, 전형 단계·첨부파일(미수집/실패 시 안내)
- `templates/institution.html`: 최근 3년(공고 시작일 기준) 기준 기간, 고유 공고 수, 공고상 모집 예정 인원 합계 및 비숫자 제외 건수, 연도별 모집 예정 인원 합계 표, 시기 패턴(연도별 월별 공고 수 표), 직무(NCS) 분포 표(다중 직무 중복 안내 포함), 페이지네이션된 3년 공고 이력(모바일 카드 + 데스크톱 표), 잡알리오 기관비교 링크
- `templates/error/404.html`, `templates/error.html`: 404 및 일반 오류 화면 (`layout`의 `header`/`footer`를 통해 기준 시각 및 지연 안내 표시)

### 테스트 및 문서
- `src/test/java/kr/gongchae/bell/catalog/CatalogUnitTest.java`: 5건 (`SafeUrlValidator`, KST `Clock` 기준 D-day/실효 상태, 허용 목록/와일드카드 이스케이프, 트래픽 분류, 2시간 지연·회복·DB 오류 시 `currentStatusSafe()` 비예외 폴백)
- `src/test/java/kr/gongchae/bell/catalog/CatalogIntegrationTest.java`: 5건 (PostgreSQL 17 Testcontainers + `RANDOM_PORT` 실제 HTTP 오류 dispatch 검증 포함; 홈 섹션/`M2` 미노출, `/jobs` `ncs`·`region`·`hire`·`career=newcomer|newonly|career`·`status`·`q`·`sort=deadline|begin`·페이지 간 비중복 HTML 구조 검증, 상세 XSS 이스케이프/`javascript:` 차단/`:442` 리다이렉트, 기관 3년 연도별 합계/비숫자 제외/직무별 `DISTINCT sn`/2페이지 분리 검증, G2 메트릭·`/sitemap.xml`·실제 HTTP `/does-not-exist`(404) & `/__test-error`(500) 기준 시각·2시간 초과 지연 안내 및 회복 검증)
- `README.md`, `docs/implementation-status.md`: `#5` 구현 상태 반영

---

## 2. 검증 결과 (최종 커밋 기준 실측)

### 2-1. Gradle 자동 테스트 및 JAR 빌드
- 명령: `.\gradlew.bat --no-daemon check bootJar` -> `BUILD SUCCESSFUL in 32s`
- JUnit XML (`build/test-results/test/TEST-*.xml`):
  - `kr.gongchae.bell.catalog.CatalogUnitTest`: 5 tests, 0 failures, 0 errors, 0 skipped (`0.411s`)
  - `kr.gongchae.bell.catalog.CatalogIntegrationTest`: 5 tests, 0 failures, 0 errors, 0 skipped (`4.012s`)
  - `kr.gongchae.bell.FoundationIntegrationTest`: 4 tests, 0 failures, 0 errors, 0 skipped (`12.446s`)
  - **합계**: 14 tests, 0 failures, 0 errors, 0 skipped

### 2-2. Orca embedded browser 검증 (`bab38a7` 코드 기준 `build/libs/gongchae-bell.jar`)
- 임시 PostgreSQL 17 컨테이너(`127.0.0.1:15432`)에 `fixtures/alio-public-list-sample.json` 10건 및 검증용 시드 데이터 25건을 적재하고 `gongchae-bell.jar` 기동 후 Orca CLI 내장 브라우저(`--page 8d950131-3e78-41c2-a4a9-7f519f1eab16`)로 검증 수행:
  - **목록 -> 상세 -> 기관 탐색 흐름**:
    - `/jobs` 초기 화면 확인 (`7f774d4a-fc33-4474-9699-81666ae3d1be-screenshot.png`) -> `2026년 의료요원(진료교수, 진료의사) 28차 초빙(긴급) 공고` 링크(`@e243`) 포커스/엔터 이동 -> `/jobs/307032` 상세 확인 (`4a8e8cd3-d7b7-4389-b75e-5042f85fac46-screenshot.png`) -> `이 기관 공고 이력`(`@e288`) 클릭 -> `/institutions/C0008` 이동 및 3년 요약/연도별·월별·NCS 표·이력 확인
  - **360x800 모바일 (`--width 360 --height 800 --scale 1 --mobile`)**:
    - `/jobs`: 초기 접힘 상태(`button "필터 열기" [expanded=false]`) 확인 (`d5546b74-0fcb-4ab7-9d01-ebb6fed62484-screenshot.png`) -> `@e811` 클릭 시 `#jobs-filter-panel` 열림(`필터 닫기 [expanded=true]`, `scrollWidth=360 == innerWidth=360`) 확인 (`3243e111-5238-4c0f-83bc-2cdf5ea9b1f8-screenshot.png`) -> 검색어 `의료요원` 입력 후 `검색` 클릭 -> `검색 결과 1건` 확인
    - `/jobs/307032`: `scrollWidth=360, innerWidth=360`
    - `/institutions/C0008`: `scrollWidth=360, innerWidth=360` (`a6b7f7cd-4592-432a-aa0e-52e2d422819c-screenshot.png`)
    - `/` (지연 시뮬레이션: `finished_at = now() - interval '3 hours'`): 상단 `수집 지연 안내:` 배너 및 푸터 `데이터 기준 2026.11.15 05:37 KST 수집 지연` 노출, `scrollWidth=360, innerWidth=360` (`91a3deaa-220d-420e-b4f5-b46294668317-screenshot.png`)
    - `/does-not-exist`: 404 오류 화면에서도 상단 `수집 지연 안내:`와 푸터 `데이터 기준 2026.11.15 05:37 KST 수집 지연` 정상 노출 확인
  - **콘솔 오류**: `orca console --limit 50` 결과 `entries: []`
- 검증 후 Orca 임시 탭, 임시 Java 프로세스, 임시 PostgreSQL 컨테이너 정리 완료

---

## 3. 연동 확인 및 후속 경계

1. **`#3` 우대 계약 v2 확인**: `#3` 우대 저장 형식이 라벨+줄바꿈 텍스트로 확정되어, 기존 `job-detail.html`의 `.pre-line-text`(`white-space: pre-line`) 출력 그대로 표시하며 별도의 `U+001F` split 코드는 추가하지 않았습니다.
2. **`#3`/`#4` 정식 반영 후 최종 연동 테스트**: `#4`의 `V3` 마이그레이션 및 상세 수집(`steps`/`files`)·`#3` 수집 데이터 적재가 `main`에 합류된 뒤 공용 통합(합본) 테스트 및 상세 JSONB 키 교차 검증을 진행합니다. 현재 `CatalogRepository`의 `parseSteps`/`parseFiles`는 후보 키를 허용하는 상태로 유지했습니다.

---

## 4. 미변경 경계 준수 및 비고

- 원본 작업폴더(`C:/Users/lcj/orca/projects/my-project`) 및 기존 사용자 변경 미수정
- `collab/2026-11-14-resume/agy-catalog-ready.md` 보존
- Flyway 마이그레이션(`V1`, `V2`) 수정·추가 없음
- 수집(`ingestion`), 구독·알림(`subscription`), 배포 코드 미변경
- 참고 관측: 360px `/institutions/C0008` 연도별 표의 첫 번째 열 헤더(`2026년`)가 줄바꿈(`2026` / `년`)되지만 가로 넘침(`scrollWidth=360`)이나 가독성 손상은 없어 현 상태에서 유지했습니다.
