# 프론트엔드 디자인 리프레시 QA 기록 (2026-11-22, 합성 fixture 기준)

> **검증 성격 안내**: 이 문서는 PR #39 프론트엔드 디자인 리프레시의 **로컬 합성(synthetic) fixture 기반 단위·통합 시험 및 Chromium 브라우저 QA 기록**이다. **운영 사이트 실측, 실제 공공기관 데이터, 실제 사용자/운영자 정보, 운영 배포 완료 확인이 아니다.** 운영 배포 및 공개 HTTPS 실측은 별도 작업으로 관리한다.

- 관련 설계 문서: [`docs/design/frontend-structure.md`](../design/frontend-structure.md)
- 관련 이슈: `#19`

## 1. 대상 식별자

| 항목 | 값 |
|---|---|
| PR | [#39](https://github.com/Lcjam/gongchae-bell/pull/39) (`feat/#19-frontend-refresh`) |
| source / head SHA | `2c58aa1d0d916597ed63c750c4e2a7941499158b` (`feat: integrate frontend design refresh (Refs #19)`) |
| `main` merge SHA | `e0208ffd16c39b511cc0065dc8ee40e5c8b4f9c1` (2026-11-22T08:04:06Z 병합, base `7af853885e8f8db11cb81cbde343211076994664`) |
| PR #39 변경 범위 | 4개 파일 (`+370 / -32`): `src/main/resources/static/css/app.css`, `src/main/resources/templates/fragments/layout.html`, `AlertsDisabledIntegrationTest.java`, `SubscriptionFlowIntegrationTest.java` |
| 번들 CSS 기반 | Bootstrap `v5.3.8` ([발췌 근거](frontend-refresh-2026-11-22/bootstrap-contract.txt)) |

## 2. 검증 환경 및 합성 데이터 경계

- **데이터베이스·데이터**: 격리된 로컬 PostgreSQL 17 fixture DB에 넣은 **합성 공고 25행**을 사용했다. 공백 없는 긴 한글 기관명·긴 공고 제목·다중 값 직무/지역/고용 배지(`검증공사`, `한국공공기관긴이름모바일줄바꿈검증연구개발진흥재단부설공공데이터정보통신센터`, 기관코드 `FIXTURE`, 공고번호 `900001` 등)를 포함하며 실제 공고 수·실사용 성능 수치가 아니다.
- **외부 의존성 차단**: Primary **mock** `MailTransport` / `WebhookTransport`와 **고정 KST `Clock`(`2026-11-22 12:00`)**을 사용했다. collector·backfill·alert scheduler·heartbeat는 비활성 상태이며, HTTP 서버는 loopback(`http://127.0.0.1:18089` `alerts=false`, `http://127.0.0.1:18090` `alerts=true`)으로만 바인딩했다. fresh 상태(`11:55 KST`)와 delayed 상태(`08:10 KST`)를 전환해 검증했다.
- **시험용 합성 값 구별 (실제 secret·계정 아님)**:
  - `/privacy` 화면과 폼 입력에 표시되는 `버전 fixture`, `fixture officer`, `fixture@example.invalid`(RFC 2606 예약 도메인 `.invalid`), `local fixture`는 모두 시험용 자리표시자다.
  - 확인·해지 URL의 쿼리 문자열 `t=invalid-fixture`는 무효 토큰 오류 화면을 재현하기 위한 시험값이며 실제 토큰·비밀값이 아니다.
- **브라우저·뷰포트**: **Chromium** 단일 엔진, **`1280×900`**(데스크톱) 및 **`360×900`**(모바일) 두 폭에서 측정했다.

## 3. 사용자 원본 보존 및 최소 CSS 5줄 보정

사용자 원본(`feat/frontend-redesign`)의 디자인 토큰, 단일 파란 강조색, 캡슐형 버튼·배지, 반투명 sticky header, 회색 지연 배너, 전체폭 footer, `1080px` `.container`, 옅은 회색 홈 `.hero`, 폼 필드·라우트·`th:*`·`alertsEnabled` 조건 계약은 그대로 유지했다.

| 파일 | 사용자 원본 SHA-256 | `main`(`e0208ff`) SHA-256 | 비고 |
|---|---|---|---|
| `src/main/resources/templates/fragments/layout.html` | `07E1110F2BF90CA736893D4094090D066FB15020D4E2B1FC8B922D0D86FE5A32` | `07E1110F2BF90CA736893D4094090D066FB15020D4E2B1FC8B922D0D86FE5A32` | **원본과 바이트 동일** |
| `src/main/resources/static/css/app.css` | `55B81E59EED6E390083F2F29660001DFA6279D8D6472AAA3DB590F8879AFB1D7` | `87C7622B17511F4DFB025CAD83545EE27793F48AEFB365A3462CB4E1DA4A254A` | 브라우저 재현 기반 **5줄 보정** (논리 4항목) |

### CSS 5줄 보정 전후 수치

| # | 보정 내용 (`app.css`) | 보정 전 관측 (원본 CSS) | 보정 후 관측 (`2c58aa1` / `e0208ff`) |
|---|---|---|---|
| 1 | `.card { overflow-wrap: anywhere; }` (+1줄) | 360px 홈 긴 기관명 카드에서 `keep-all` + Bootstrap `.card` `word-wrap: break-word` 조합으로 우측 넘침: `clientWidth=360`, **`scrollWidth=590`** ([화면](frontend-refresh-2026-11-22/home-mobile-before.png), [JSON](frontend-refresh-2026-11-22/home-mobile-before.json)) | `clientWidth=360`, **`scrollWidth=360`** ([화면](frontend-refresh-2026-11-22/home-long-card-mobile.png), [JSON](frontend-refresh-2026-11-22/home-long-card-mobile.json)) |
| 2 | `.table { word-break: normal; }` (+1줄) | 1280px `/jobs?status=all` 표가 컨테이너 밖으로 확장: `table` 폭 **`1344.75px`**·첫 행 높이 **`779.75px`** ([화면](frontend-refresh-2026-11-22/jobs-desktop-before.png), [JSON](frontend-refresh-2026-11-22/jobs-desktop-before.json)) | `overflow=[]`([JSON](frontend-refresh-2026-11-22/jobs-desktop.json)), 같은 표 폭 **`1054px`**·첫 행 높이 **`142.75px`**(작성자 브라우저 측정, [화면](frontend-refresh-2026-11-22/jobs-desktop.png)) |
| 3 | `--success: #16a34a → #15803d`, `--bs-success-rgb: 22, 163, 74 → 21, 128, 61` (2줄) | 번들 Bootstrap 5.3.8 `.text-bg-success`(흰 글자 강제) 대비 **`3.30:1`** — AA(4.5:1) 미달 ([홈 전 JSON](frontend-refresh-2026-11-22/home-mobile-before.json), [목록 전 JSON](frontend-refresh-2026-11-22/jobs-mobile-before.json)) | 흰 글자 대비 **`5.02:1`** ([상세 JSON](frontend-refresh-2026-11-22/detail-mobile.json)) |
| 4 | `--accent-hover: #0077ed → #0066cc` (1줄) | 주 버튼 실제 hover 시 흰 글자 대비 **`4.32:1`**(작성자 측정) — AA(4.5:1) 미달 | 실제 hover 시 흰 글자 대비 **`5.57:1`** ([hover JSON](frontend-refresh-2026-11-22/hover-desktop.json)) |

- 반투명 `oklab` `.site-header` 배경 대비는 계산하지 않았다(JSON `contrast: null`).
- 주요 계산 대비(최종 CSS): 주 버튼/CTA `4.70:1`, hover `5.57:1`, 푸터 `4.66:1`, `text-bg-success` `5.02:1`, `text-bg-danger` `4.83:1`, `.text-secondary` `5.07:1`, 지연 배너·`.status-badge` `9.20:1`, `text-bg-warning` `10.95:1`, `text-bg-light` `14.17~19.29:1` (Sol·Claude 측정).
- `.table > :not(caption) > * > * { padding: 0.875rem 0.75rem }`는 `.table-sm`에도 적용돼 360px 기관 통계 표(`.table-responsive` 내부)의 가로 스크롤 폭이 넓어지나(연도별 451px·월별 660px·NCS 334px, Claude 측정), 페이지 자체는 `360/360`으로 넘치지 않으며 사용자 디자인 선택으로 유지했다.

## 4. 자동 시험 및 CI 결과

- **로컬 전체 시험** (Sol 보고, `2c58aa1` 기준, 상세 목록 [`local-tests.json`](frontend-refresh-2026-11-22/local-tests.json)): `gradlew check bootJar` **26개 suite, 176 tests, failures 0, errors 0, skipped 0**.
  - 기존 `CatalogIntegrationTest`(6), `CatalogUnitTest`(6), `SubscriptionFlowIntegrationTest`(15), `AlertsDisabledIntegrationTest`(1)를 유지하면서 새 헤더 CTA 조건 분기(`class="site-nav-cta"`) 단언 2건만 추가했다.
- **PR #39 (`2c58aa1d0d916597ed63c750c4e2a7941499158b`) GitHub Actions 7/7 `COMPLETED / SUCCESS`**:
  - CI [`44969203004`](https://github.com/Lcjam/gongchae-bell/actions/runs/44969203004): `test`, `deploy-verify`, `backup-verify`, `secrets` 모두 `SUCCESS`
  - Monitoring [`44969202984`](https://github.com/Lcjam/gongchae-bell/actions/runs/44969202984): `rules`, `compose-integration`, `alert-e2e` 모두 `SUCCESS`
- **병합 커밋 `main` `e0208ffd16c39b511cc0065dc8ee40e5c8b4f9c1` 워크플로 확인**: CI [`45001067523`](https://github.com/Lcjam/gongchae-bell/actions/runs/45001067523), Monitoring [`45001067454`](https://github.com/Lcjam/gongchae-bell/actions/runs/45001067454) 모두 `completed / success`.

## 5. 주체별 검증 범위 (측정한 사람 구분)

| 주체 | 역할 | 직접 수행·측정한 범위 |
|---|---|---|
| **Sol** | PR #39 소스 작성자 | 사용자 원본 통합, CSS 5줄 보정, 로컬 전체 `176` 시험 실행([`local-tests.json`](frontend-refresh-2026-11-22/local-tests.json)), 합성 fixture 25행 + mock provider + 고정 Clock 기반 Chromium 1280/360 전 화면 캡처·JSON 수집, 키보드 Tab/Enter(`.skip-link` → `#main` → 필터 버튼 → `#filter-ncs`), 스크롤 `1670px` sticky header 확인, 알림 폼 HTTP 400(`받을 알림을 하나 이상 고르세요.`)·무효 합성 토큰 오류 확인, 콘솔(`0`건, [`console.json`](frontend-refresh-2026-11-22/console.json))·네트워크 요약(162건 전부 local/data URL, CSS 101·JS 9 모두 200, 의도한 400/404만 오류, [`network-summary.json`](frontend-refresh-2026-11-22/network-summary.json)) |
| **Claude** | PR #39 비작성자 독립 검수 | source `2c58aa1` diff·원본 SHA-256 대조, 별도 읽기 전용 Chromium 탭으로 로컬 QA 서버(`127.0.0.1:18090`) 7개 화면 × 2폭(14회)의 DOM 순서(`A.skip-link → HEADER.site-header → DIV.delay-banner → MAIN → FOOTER.site-footer`), 넘침 없음, 주요 AA 대비, 360px 50자 메타 배지 3줄 줄바꿈, 360px 기관 표 폭 독립 측정 |
| **root Codex** | PR #39 비작성자 검수·병합 | CSS·layout·시험 diff 전수 검토, 원본 해시 대조, 핵심 스크린샷(`jobs-mobile`, `home-long-card-mobile`, `home-desktop`) 육안 확인, PR #39 CI 7/7 직접 조회 및 `main`(`e0208ff`) 병합 |
| **agy** | 문서·증거 동기화 PR 작성자 | `main` `e0208ff` 코드 대조 후 [`frontend-structure.md`](../design/frontend-structure.md) 동기화, 공개 가능한 원문 증거 선별·SHA-256 동일 복사, 내부 메타 포함 파일 제외, 링크·비밀값·`gitleaks` 점검. 브라우저 QA·Gradle 시험·운영 실측을 새로 실행하지 않음 |

## 6. 화면 사례 및 공개 증거 목록

아래 57개 파일(PNG 27 · JSON 29 · TXT 1)은 `docs/qa/frontend-refresh-2026-11-22/`에 원본 수치·이미지를 수정하지 않고 복사한 합성 QA 증거다. 작업 트리 복사 시점 57/57 전체의 raw SHA-256 일치를 확인했으며, 커밋된 Git blob 기준으로는 PNG 27/27과 텍스트 7/30이 원본과 raw 바이트 동일하고, 나머지 텍스트 23/30은 Git 개행 정규화(`CRLF → LF`)만 달라 개행 정규화 후 30/30 내용이 100% 동일하다(원본·증거 재가공 없음). 최종 모바일 캡처의 `clientWidth / scrollWidth`는 모두 `360 / 360`, 데스크톱은 `1265 / 1265`(스크롤바 제외, `.container` `1080px`)다.

| 화면 / 시나리오 | 1280px (데스크톱) | 360px (모바일) |
|---|---|---|
| 보정 전 비교 (`before`) | 목록 표 확장 [PNG](frontend-refresh-2026-11-22/jobs-desktop-before.png) · [JSON](frontend-refresh-2026-11-22/jobs-desktop-before.json) | 홈 긴 기관명 넘침(`scrollWidth=590`) [PNG](frontend-refresh-2026-11-22/home-mobile-before.png) · [JSON](frontend-refresh-2026-11-22/home-mobile-before.json) / 목록 보정 전 [JSON](frontend-refresh-2026-11-22/jobs-mobile-before.json) |
| 홈 (`/`) | [PNG](frontend-refresh-2026-11-22/home-desktop.png) · [JSON](frontend-refresh-2026-11-22/home-desktop.json) / hover 대비 `5.57` [JSON](frontend-refresh-2026-11-22/hover-desktop.json) | 기본(지연) [PNG](frontend-refresh-2026-11-22/home-mobile.png) · [JSON](frontend-refresh-2026-11-22/home-mobile.json) / 긴 카드 보정 후 [PNG](frontend-refresh-2026-11-22/home-long-card-mobile.png) · [JSON](frontend-refresh-2026-11-22/home-long-card-mobile.json) / fresh(배너 없음) [PNG](frontend-refresh-2026-11-22/fresh-home-mobile.png) · [JSON](frontend-refresh-2026-11-22/fresh-home-mobile.json) |
| 목록·필터 (`/jobs`) | [PNG](frontend-refresh-2026-11-22/jobs-desktop.png) · [JSON](frontend-refresh-2026-11-22/jobs-desktop.json) | `alerts=false` [PNG](frontend-refresh-2026-11-22/jobs-mobile.png) · [JSON](frontend-refresh-2026-11-22/jobs-mobile.json) / `alerts=true` [PNG](frontend-refresh-2026-11-22/jobs-enabled-mobile.png) · [JSON](frontend-refresh-2026-11-22/jobs-enabled-mobile.json) |
| 상세 (`/jobs/900001`) | [PNG](frontend-refresh-2026-11-22/detail-desktop.png) · [JSON](frontend-refresh-2026-11-22/detail-desktop.json) | [PNG](frontend-refresh-2026-11-22/detail-mobile.png) · [JSON](frontend-refresh-2026-11-22/detail-mobile.json) |
| 기관 이력 (`/institutions/FIXTURE`) | [PNG](frontend-refresh-2026-11-22/institution-desktop.png) · [JSON](frontend-refresh-2026-11-22/institution-desktop.json) | [PNG](frontend-refresh-2026-11-22/institution-mobile.png) · [JSON](frontend-refresh-2026-11-22/institution-mobile.json) |
| 개인정보 (`/privacy`, 합성 값) | [PNG](frontend-refresh-2026-11-22/privacy-desktop.png) · [JSON](frontend-refresh-2026-11-22/privacy-desktop.json) | [PNG](frontend-refresh-2026-11-22/privacy-mobile.png) · [JSON](frontend-refresh-2026-11-22/privacy-mobile.json) |
| 알림 신청 (`/subscriptions/new`) | [PNG](frontend-refresh-2026-11-22/signup-desktop.png) · [JSON](frontend-refresh-2026-11-22/signup-desktop.json) | 기본 폼 [PNG](frontend-refresh-2026-11-22/signup-mobile.png) · [JSON](frontend-refresh-2026-11-22/signup-mobile.json) / 400 입력 오류 [PNG](frontend-refresh-2026-11-22/signup-error-mobile.png) · [JSON](frontend-refresh-2026-11-22/signup-error-mobile.json) |
| 구독 안내·오류 (`alerts=false`·합성 토큰) | — | 비활성 안내 [PNG](frontend-refresh-2026-11-22/disabled-mobile.png) · [JSON](frontend-refresh-2026-11-22/disabled-mobile.json) / 확인 폼 [PNG](frontend-refresh-2026-11-22/confirm-mobile.png) · [JSON](frontend-refresh-2026-11-22/confirm-mobile.json) / 무효 토큰 오류 [PNG](frontend-refresh-2026-11-22/token-error-mobile.png) · [JSON](frontend-refresh-2026-11-22/token-error-mobile.json) / 해지 폼 [PNG](frontend-refresh-2026-11-22/unsubscribe-mobile.png) · [JSON](frontend-refresh-2026-11-22/unsubscribe-mobile.json) / 복구 폼 [PNG](frontend-refresh-2026-11-22/recover-mobile.png) · [JSON](frontend-refresh-2026-11-22/recover-mobile.json) |
| 404 오류 (`/does-not-exist`) | — | [PNG](frontend-refresh-2026-11-22/error404-mobile.png) · [JSON](frontend-refresh-2026-11-22/error404-mobile.json) |
| 키보드 포커스·스크롤 상태 | — | `.skip-link` 포커스 [PNG](frontend-refresh-2026-11-22/skip-focus-mobile.png) / 필터 `#filter-ncs` 포커스 링 [PNG](frontend-refresh-2026-11-22/filter-focus-mobile.png) / 스크롤 `1670px` sticky header [PNG](frontend-refresh-2026-11-22/sticky-header-mobile.png) |
| 공통 시험·콘솔·네트워크·번들 | [`local-tests.json`](frontend-refresh-2026-11-22/local-tests.json) · [`console.json`](frontend-refresh-2026-11-22/console.json) · [`network-summary.json`](frontend-refresh-2026-11-22/network-summary.json) · [`bootstrap-contract.txt`](frontend-refresh-2026-11-22/bootstrap-contract.txt) | — |

### 공개 제외 항목 (민감정보·중복 필터링)

- `filter-focus.json`: 로컬 도구 응답 래퍼에 내부 실행 메타 ID가 포함돼 공개 디렉터리에서 제외했다. 스크린샷([`filter-focus-mobile.png`](frontend-refresh-2026-11-22/filter-focus-mobile.png))만 공개하며, 원 JSON에 기록된 측정값은 `filterExpanded="true"`, `active="filter-ncs"`, `border="rgb(0, 113, 227)"`, `shadow="oklab(0.562905 -0.0462419 -0.187701 / 0.35) 0px 0px 0px 4px"`다.
- `hover-check-desktop.png` / `hover-check-desktop.json`: hover 적용 전 중복 캡처(기본 대비 `4.70`)이므로 제외하고 실제 hover 측정(`5.57`)인 [`hover-desktop.json`](frontend-refresh-2026-11-22/hover-desktop.json)과 [`home-desktop.png`](frontend-refresh-2026-11-22/home-desktop.png)만 남겼다.
- 로컬 워크스페이스의 빌드 로그(`build-frontend-*.log`)와 임시 fixture 실행기·시드 파일은 공개 저장소 증거에서 제외했다.

## 7. 미검증 범위 및 한계

- **실운영 배포·공개 HTTPS 실측 미포함**: 이 문서는 로컬 합성 fixture 기준이며, 실제 운영 서버 배포·실 DB 데이터·실제 공개 도메인 HTTPS 렌더 확인은 별도 작업 대상이다.
- **실외부 연동 미실행**: 실제 공공데이터포털 API 호출, Resend 이메일 발송, Discord 웹훅 발송은 실행하지 않았다. CI의 `deploy-verify`·`Monitoring` 잡은 격리 환경 검증이다.
- **교차 브라우저·실기기·보조기기 미검증**: Safari, Firefox, 실제 모바일 기기, 스크린리더 전면 검증, `prefers-reduced-motion` 브라우저 에뮬레이션은 수행하지 않았다.
- **구형 브라우저 `color-mix()` 폴백 미제공**: `.site-header` 배경·`--focus-ring`·`.alert-warning`/`.alert-danger` 배경이 `color-mix(in oklab, ...)`를 사용하며, 미지원 구형 브라우저(Chrome <111, Safari <16.2)용 별도 폴백은 없다(`:focus-visible` 2px outline은 유지).
- **접근성 범위 한계**: 입력 테두리 저대비 등까지 포함한 전면 WCAG 인증을 주장하지 않는다.
- **미캡처 흐름**: 유효 토큰 구독 관리 화면(`/subscriptions/manage`)과 HTTP 500 오류 화면은 기존 MVC 통합 시험으로 확인했으며 별도 브라우저 스크린샷은 없다.
