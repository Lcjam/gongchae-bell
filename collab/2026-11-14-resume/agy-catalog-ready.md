# #5 공고 웹 화면 (`catalog`) 구현 경계 및 테스트 계획 · agy

> **작성일**: 2026-11-14 · **기준 문서**: [`CLAUDE.md`](../../CLAUDE.md), [`docs/prd.md`](../../docs/prd.md) (`FR-W1`~`FR-W8`, `G2`), [`docs/design/wireframes.md`](../../docs/design/wireframes.md), [`docs/design/routes.md`](../../docs/design/routes.md), [`docs/design/erd.md`](../../docs/design/erd.md), [`docs/adr/`](../../docs/adr/README.md), [`collab/2026-11-14-implementation/issue-catalog.md`](../2026-11-14-implementation/issue-catalog.md)  
> **기준 코드**: `src/main/java/kr/gongchae/bell/GongchaeBellApplication.java`, `catalog/CatalogController.java`, `db/migration/V1__catalog_and_ingestion.sql`, `V2__collection_freshness_index.sql`, `templates/`

---

## 1. 구현 범위 및 이슈 간 경계

### 1.1 `#5` 포함 범위
- **패키지**: `kr.gongchae.bell.catalog` 중심 (공통 메트릭/봇 판별 등 최소 보조만 `ops` 또는 `catalog` 내부에 두고, 패키지 분리 원칙 [`ADR-0002`](../../docs/adr/0002-single-app-feature-packages.md) 준수)
- **화면 및 라우트**:
  - `GET /`: 홈 (바로가기, 마감 임박 D-0~D-3, 오늘 시작한 공고, 알림 안내, 빈 상태)
  - `GET /jobs`: 목록 비교 (필터, 정렬, 페이징, URL 쿼리 유지, 빈 상태)
  - `GET /jobs/{sn}`: 공고 상세 (목록 필드 + 수집된 `steps`/`files` 또는 미수집 표시, 품질/원문/잡알리오/기관 링크, 404)
  - `GET /jobs/{sn}/source`: DB 저장 `src_url` 전용 리다이렉트 + 서버 측 클릭 집계(`G2`)
  - `GET /institutions/{code}`: 기관 최근 3년 채용 이력 (요약, 연도×월 시기 패턴 표, NCS 직무 분포 표, 공고 이력, 잡알리오 기관비교 링크, 404)
  - SEO/공통: 페이지별 `<title>`·`<meta name="description">`, 모든 화면 `lastCollected` 기준 시각, `/robots.txt` 유지, (Should) `/sitemap.xml`, 360px 반응형 레이아웃

### 1.2 `#5` 제외 범위 (타 이슈 침범 금지)
- **수집(`#3`, `#4`)**: 공공데이터 API 클라이언트, 정기/최초/상세 수집 스케줄러 및 명령은 구현하지 않는다. 웹 요청 경로에서는 외부 API를 절대 호출하지 않고(`FR-W1`), DB(`institution`, `posting`, `posting_code`, `code_name`, `collect_run`)만 조회한다.
- **알림·구독·개인정보(`M2`)**: `/subscriptions/**` 및 `/privacy` 컨트롤러·테이블(`subscription`, `notification`, `rate_limit`)은 범위 밖이다.
  - **임시 경계 처리**: M2 머지 전까지 헤더의 `[알림 받기]`, 홈의 알림 안내, 목록의 `[이 조건으로 알림 받기]` 버튼이 404 링크로 연결되지 않도록, `#5` 단계에서는 **준비 중 안내 문구(비활성 버튼 또는 안내 배지)**로 두거나 `/subscriptions/new` 연결 지점을 M2에서 활성화하도록 명확히 표시한다.
- **DB 스키마(`V1`, `V2`)**: 이미 적용된 `V1__catalog_and_ingestion.sql`과 `V2__collection_freshness_index.sql`은 수정하지 않는다. `V1`의 기존 인덱스(`posting_status_end`, `posting_begin`, `posting_institution_begin`, `posting_code_search`)와 `V2`의 `collect_run_latest_complete`를 우선 사용하고, 조회 성능상 추가 인덱스가 반드시 필요할 때만 확장 방향의 `V3` 마이그레이션을 추가한다(`pg_trgm` 등 확장은 기준선 측정 전 선제 도입하지 않는다).

---

## 2. 세부 구현 경계 및 계약 고정

### 2.1 시간대, D-day, 조회 시점 마감 판정
- **단일 `Clock` 사용**: `GongchaeBellApplication`의 `Clock` 빈(`Asia/Seoul`)만 주입받아 `LocalDate.now(clock)`을 계산한다. `LocalDate.now()` 직접 호출 금지(UTC 컨테이너 환경에서 KST 00:00~09:00 날짜 어긋남 방지).
- **D-day 계산**: DB에는 `decimalDay`를 저장하지 않으므로, 매 조회 시 `ChronoUnit.DAYS.between(kstToday, posting.endDate)`로 계산해 표시한다(`0` → `D-Day` 또는 `D-0`, 양수 `N` → `D-N`, 음수 → `마감`).
- **수집 실패 기간의 마감 정합성**: 수집이 실패하는 동안 `end_date < kstToday`가 된 공고가 목록의 진행 중 필터나 홈 마감 임박에 남지 않도록, **조회 쿼리 시점**에서 진행 중 여부를 판별한다.
  - 홈 마감 임박: `status = 'OPEN' AND end_date BETWEEN :kstToday AND :kstTodayPlus3` (`CHECK`·`CLOSED` 제외)
  - 홈 오늘 시작: **`bgng_date = :kstToday` 그대로 조회** (`FR-W2` "공고 시작일 기준"). `CLOSED`나 `CHECK` 공고를 임의로 제외하지 않고, 해당 상태 배지만 함께 표시한다.
  - 상세/목록의 표시 상태: `end_date < :kstToday`이면 저장된 `status`가 `OPEN`/`CHECK`여도 화면 배지는 `"마감"`으로 표시한다.

### 2.2 목록(`/jobs`) 필터·정렬·페이징 URL 계약 (`FR-W1`, `routes.md`)
| 파라미터 | 허용 값 | DB 조건 / 동작 | 비허용 값 처리 |
|---|---|---|---|
| `ncs` | `code_name` (`type='NCS'`)에 존재하는 코드 | `EXISTS (SELECT 1 FROM posting_code pc WHERE pc.sn = p.sn AND pc.type = 'NCS' AND pc.code = :ncs)` | 무시 (필터 미적용, 200) |
| `region` | `code_name` (`type='REGION'`)에 존재하는 코드 | `EXISTS (... type = 'REGION' AND pc.code = :region)` | 무시 |
| `hire` | `code_name` (`type='HIRE'`)에 존재하는 코드 | `EXISTS (... type = 'HIRE' AND pc.code = :hire)` | 무시 |
| `career` | `newcomer`, `newonly`, `career` | `newcomer` → `recrut_se IN ('R2010','R2030')`, `newonly` → `'R2010'`, `career` → `'R2020'` | 무시 |
| `status` | `open`(기본), `check`, `closed`, `all` | `open` → `status IN ('OPEN','CHECK') AND end_date >= :kstToday` (`CHECK` 공고는 품질/확인 필요 배지 표시), `check` → `status = 'CHECK' AND end_date >= :kstToday`, `closed` → `status = 'CLOSED' OR end_date < :kstToday`, `all` → 조건 없음 | 기본값(`open`) 폴백 |
| `q` | 트림 후 1~100자 문자열 | `title ILIKE :escapedPattern ESCAPE '\'` (`%`, `_`, `\` 이스케이프) | 빈 값 무시, 초과 시 100자 절삭 |
| `sort` | `deadline`(기본), `begin` | `deadline` → `ORDER BY end_date ASC, sn ASC`, `begin` → `ORDER BY bgng_date DESC, sn DESC` | 기본값(`deadline`) 폴백 |
| `page` | `1` 이상 정수 (기본 `1`) | 고정 페이지 크기(예: `20`), `OFFSET (page - 1) * size LIMIT size` | `< 1` 또는 비숫자 → `1` |

- **중복 행 방지**: 다중 코드(`NCS`, `REGION`, `HIRE`)를 `JOIN`하지 않고 `EXISTS` 서브쿼리로 필터링해 한 공고가 여러 번 출력되거나 `COUNT(*)`가 부풀려지지 않게 한다([`ADR-0011`](../../docs/adr/0011-multivalue-codes-join-table.md)).
- **URL 쿼리 보존**: 필터 폼(`method="get"`)과 페이지 이동 링크는 유효한 현재 파라미터를 그대로 유지하며, 빈 파라미터는 정리하거나 일관되게 유지한다.
- **표시명 조회**: 목록의 필터 드롭다운 선택지는 `code_name` 테이블에서 조회해 렌더링한다(`code_name`이 빈 초기 상태에서도 500 오류 없이 빈 선택지로 동작해야 함).

### 2.3 상세(`/jobs/{sn}`) 및 원문 이동(`/jobs/{sn}/source`) 보안·품질 경계
- **404 처리**: 존재하지 않는 `sn`이면 404를 반환한다. 숫자가 아닌 `/jobs/abc` 등 경로 타입 불일치도 500이 아닌 400/404로 깔끔히 처리한다.
- **안전한 출력 (`FR-W6`)**:
  - 모든 원천 필드(`title`, `inst.name`, `aply_qlfc`, `pref`, `scrnprcdr`, `steps`, `files`, `code_name.name`)는 **Thymeleaf `th:text`만** 사용한다(`th:utext` 전면 금지).
  - 줄바꿈(`\r\n`, `\n`) 표시는 HTML `<br>` 치환(`th:utext`)이 아니라 CSS `white-space: pre-line`으로 처리한다.
- **안전한 링크 검증 (`SafeUrlValidator`)**:
  - `URI.create` 파싱(제어문자 `\r`, `\n`, `\0` 사전 거부) → `scheme`이 소문자 기준 정확히 `http` 또는 `https` → `host`가 null/blank가 아님 → userinfo(`user:pass@`) 차단 여부 확인.
  - **주의**: [`fixtures/alio-public-list-sample.json`](../../fixtures/alio-public-list-sample.json)의 `recrutPblntSn=307028`(`https://www.knuch.kr:442/...`)처럼 **비표준 포트를 사용하는 정상 공공기관 공고가 실존하므로, 웹 원문 링크 검증에서는 비표준 포트를 차단하지 않는다** (디스코드 웹훅 SSRF 검증 [`ADR-0009`](../../docs/adr/0009-webhook-storage-ssrf.md)와 혼동 금지).
  - `files` jsonb 내부에 첨부 URL이 있을 경우에도 동일한 `http`/`https` 검증 통과 시에만 `href`로 렌더링한다.
- **원문 이동 엔드포인트 (`GET /jobs/{sn}/source`)**:
  - 요청 쿼리나 파라미터에서 대상 URL을 일절 받지 않고, 오직 DB `posting.src_url`만 조회한다(오픈 리다이렉트 원천 차단).
  - `src_url`이 없거나 `SafeUrlValidator`를 통과하지 못하면 리다이렉트하지 않고 `404`를 반환한다(상세 화면에서도 해당 `[원문 보기]` 버튼을 숨긴다).
  - 유효하면 G2 클릭 카운터를 증가시키고 `302 Found` (`Location: <src_url>`)로 응답한다.
- **품질/상세 상태 표시**:
  - `broken_text = true` → `"품질 경고: 깨진 문자 있음 → 원문을 확인하세요"` 배지 표시.
  - `src_url_generic = true` 또는 `src_url` 무효 → `[잡알리오에서 보기]` (`https://job.alio.go.kr/recruitview.do?idx={sn}`) 함께 표시.
  - `detail_status = 'DONE'`일 때만 `steps`·`files` 표시, `NONE` 또는 `FAILED`이면 `"상세 정보 미수집"` 표시(`FR-C7`, `FR-W3`).

### 2.4 기관(`/institutions/{code}`) 통계 경계 (`FR-W4`)
- **404 처리**: 존재하지 않는 `institution.code`는 404.
- **기간 고정**: `[kstToday.minusYears(3), kstToday]` (`bgng_date` 기준)를 계산하고 화면 상단에 실제 기준 날짜 범위를 명시한다.
- **연도별 모집 예정 인원 합계 표 + 전체 요약**:
  - 3년 창 내 연도(`EXTRACT(YEAR FROM bgng_date)`)별로 `SUM(recrut_nope) FILTER (WHERE recrut_nope IS NOT NULL)`와 비숫자 제외 건수 `COUNT(*) FILTER (WHERE recrut_nope IS NULL)`를 **연도별 표**로 제공하고, 요약 영역에도 전체 합계 및 제외 건수를 함께 표시한다.
  - 문구는 `"공고상 모집 예정 인원 합계"`를 사용하며 `"실제 채용 실적"`이라는 표현을 쓰지 않는다.
- **표 2종 + 페이지네이션된 전체 3년 이력**:
  - 연도 × 1~12월 공고 수 표(공고 없는 월은 `0`, 차트 없음). 각 셀의 합계는 3년 고유 공고 수와 일치한다.
  - NCS 직무 분포 표: `posting_code` (`type='NCS'`) 기준 `COUNT(DISTINCT pc.sn)`으로 직무별 공고 수를 집계하고, `code_name` `LEFT JOIN`으로 명칭 누락 시 코드 문자열로 fallback한다. 공고 하나가 여러 NCS 코드를 가질 수 있으므로 **다중 직무 공고는 각 직무에 모두 집계되며 직무별 건수의 합이 전체 고유 공고 수보다 클 수 있음을 표 안내 문구로 명시**한다.
  - 공고 이력 목록: 최근 3년 전체 공고 이력을 **최신 N건으로 자르지 않고 `?page=` 페이지네이션으로 전부 접근 가능**하게 제공한다(`ORDER BY bgng_date DESC, sn DESC`, 비허용/범위 외 `page`는 `1` 폴백).
- **외부 연계 링크**: 잡알리오 기관비교(`https://job.alio.go.kr/orgcompare.do`) 링크 제공.

### 2.5 G2 서버 측 지표 및 SEO (`FR-W7`, `ADR-0006`)
- **카디널리티 제한 및 트래픽 분류**: Prometheus 카운터에 `sn`, `inst_code`, `q`, URL 쿼리, IP 등 무제한 라벨을 넣지 않는다([`ADR-0006`](../../docs/adr/0006-observability.md)).
  - 화면 요청 집계: `gongchae_page_views_total{page="home|jobs|job_detail|institution", traffic="user|bot|internal"}`
    - `internal`: 내부 점검·G4 외부 배포 시험·합성 모니터링 요청(예: 설정된 전용 시험 헤더/User-Agent 또는 내부 헬스체크 프로브)을 분리 집계한다.
    - `bot`: 일반 크롤러/봇 User-Agent를 분리 집계한다.
    - `user`: 위 두 분류에 해당하지 않는 일반 사용자 화면 요청만 집계한다.
  - 원문 이동 집계: `gongchae_source_redirects_total{generic="true|false", traffic="user|bot|internal"}`로 동일하게 내부 점검/시험·봇을 분리한다. (`/actuator/**`는 집계 대상에서 제외)
- **기준 시각 공통 모델**: 모든 HTML 화면(`/`·`/jobs`·`/jobs/{sn}`·`/institutions/{code}`)에서 푸터의 `lastCollected`가 정상 출력되도록 공통 조회 컴포넌트(예: `FreshnessService` 또는 `@ControllerAdvice` 대상 지정)로 통일하고, `/robots.txt`·`/jobs/{sn}/source`·`/actuator/**`에는 DB 조회가 발생하지 않게 분리한다.
- **모바일 360px (`FR-W8`)**: `/jobs` 필터 폼은 모바일(`md` 미만)에서 Bootstrap Collapse로 접히며, 목록/기관 표는 모바일에서 가로 넘침(`scrollWidth > 360`)이 없도록 `table-responsive` 또는 카드형 반응형 마크업을 사용한다.

---

## 3. 테스트 및 검증 계획

### 3.1 단위 테스트 (외부·DB 의존 없음)
1. **`SafeUrlValidator` / 제어문자 테스트**:
   - 허용: `http://...`, `https://...`, 비표준 포트 `https://www.knuch.kr:442/content/04info/11_01.asp?proc_type=view&b_num=5099` (`fixtures/alio-public-list-sample.json` 실제 사례).
   - 차단: `javascript:alert(1)`, `JAVASCRIPT:alert(1)`, `data:text/html,<script>...`, `vbscript:...`, `//evil.com/path`, `ftp://...`, `https://user:pass@example.com`, `\r`/`\n`/`\0` 포함 문자열, 빈 문자열, null.
2. **KST `Clock` 기반 날짜·D-day·마감 판정 테스트**:
   - `Clock.fixed`로 `2026-11-14T23:30:00Z`(KST `2026-11-15 08:30`) 설정 시 UTC 날짜(`11-14`)가 아닌 KST 날짜(`11-15`) 기준으로 `D-0`, `D-3`, 오늘 시작, `end_date < kstToday` 마감 판정이 맞는지 검증.
3. **목록 검색 파라미터 정규화 테스트**:
   - 비허용 `career`, `sort`, `status`, 음수/비문자 `page` 폴백, `q` 길이 절삭 및 `%`·`_`·`\` 리터럴 이스케이프 검증.

### 3.2 통합 테스트 (`@SpringBootTest` + PostgreSQL 17 Testcontainers + `MockMvc`)
`fixtures/alio-public-list-sample.json`의 필드 패턴(다중 NCS, 비표준 포트 URL, generic URL)과 경계 케이스를 시드 데이터로 사용해 검증한다.

1. **홈(`/`)**:
   - 마감 임박 영역에 KST `D-0`~`D-3`이면서 `status='OPEN'`인 공고만 노출(`D-4`, `CLOSED`, `CHECK`, `end_date < today` 제외).
   - 오늘 시작한 공고(`bgng_date = kstToday`) 노출 시 `CLOSED`·`CHECK` 상태 공고도 임의로 누락되지 않고 상태 배지와 함께 노출되는지, 각 영역 빈 상태 문구가 맞는지 검증.
2. **목록(`/jobs`)**:
   - `ncs`, `region`, `hire`, `career`(`newcomer`=`R2010+R2030`, `newonly`=`R2010`, `career`=`R2020`), `status`(`open`/`check`/`closed`/`all`), `q`(제목 검색 및 `%` 입력 시 전체 매칭 방지), `sort`(`deadline`/`begin`), `page` 조합 검증.
   - 다중 코드를 가진 공고가 NCS/지역 필터에 걸려도 결과 목록과 카운트에 **1건**으로만 집계되는지 검증.
   - 허용 목록 밖의 `ncs=INVALID&career=bad&sort=hack&page=-5` 요청이 500/400 없이 200으로 무시되는지, 페이지 링크에 유효 필터 쿼리가 유지되는지 검증.
3. **상세(`/jobs/{sn}`) & 원문 이동(`/jobs/{sn}/source`)**:
   - `detail_status='DONE'`이면 `steps`/`files` 노출, `NONE`/`FAILED`이면 `"상세 정보 미수집"` 노출.
   - `broken_text=true` 경고 배지, `src_url_generic=true` 시 `recruitview.do?idx={sn}` 링크 노출.
   - 악성 `src_url`(`javascript:alert(1)`)과 XSS 제목(`<script>alert('xss')</script>`)이 DB에 있을 때 HTML에 원문 보기 링크가 렌더링되지 않고 `<script>`가 이스케이프(`&lt;script&gt;`)되는지 검증.
   - `/jobs/{sn}/source`: 유효 `src_url`이면 `302` + `Location` 일치 + Prometheus 클릭 카운터 `+1`, 임의 파라미터 `?url=https://evil.com` 무시, 무효/null `src_url`이면 `404` + 카운터 미증가, 없는 `sn`은 상세/원문 모두 `404`.
4. **기관(`/institutions/{code}`)**:
   - 기준일 3년 이내 공고만 포함하고 3년 하루 전 공고는 제외하는지 검증.
   - 연도×1~12월 표 셀 합계가 3년 고유 공고 수와 일치하는지 검증.
   - NCS 직무 분포는 다중 NCS 소속 공고가 각 직무에 `COUNT(DISTINCT sn)`으로 정확히 반영되는지(따라서 직무별 합이 고유 공고 수를 초과할 수 있음)와 다중 소속 중복 안내 문구가 표시되는지 검증.
   - **연도별** 모집 예정 인원 합계(`SUM(recrut_nope)`)와 비숫자 제외 건수(`recrut_nope IS NULL`) 표가 연도별로 정확히 나뉘어 표시되고 `"실제 채용 실적"` 문구가 없는지 검증.
   - 기관 공고 이력이 1페이지를 초과할 때 `?page=1`, `?page=2`로 3년치 전체 이력을 빠짐없이 탐색할 수 있는지 검증.
   - 없는 기관 코드 `404` 검증.
5. **공통·SEO·G2**:
   - 모든 HTML 페이지에 `<title>`, `<meta name="description">`, 푸터 `lastCollected` 존재 검증.
   - `MeterRegistry`에서 `gongchae_page_views_total`과 `gongchae_source_redirects_total`이 `traffic="user|bot|internal"`(일반 요청, 봇 UA, 내부 점검·시험 요청)으로 각각 분리 증가하는지 확인.

### 3.3 브라우저 검증 (구현 완료 후 실서버 확인)
- 로컬 PostgreSQL + 앱 기동 상태에서 표본 데이터를 넣고 데스크톱(`1280×800`)과 모바일(`360×800`) 뷰포트로 `/`, `/jobs`(필터 토글·검색·정렬·페이징), `/jobs/{sn}`, `/institutions/{code}`를 직접 탐색.
- 전 화면 `document.documentElement.scrollWidth <= window.innerWidth` (`360`) 및 깨진 링크(`404` 버튼) 없음을 확인.
