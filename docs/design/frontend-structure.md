# 공채벨(gongchae-bell) 프론트엔드 구조

Spring MVC와 Thymeleaf 서버 렌더링 웹 구조, 경로, 폼 계약, 화면 전환, 보안·상태 인터페이스를 정리한 문서다.

- 설계 기준 문서 [`prd.md`](../prd.md), [`wireframes.md`](wireframes.md), [`routes.md`](routes.md), [`architecture.md`](architecture.md), [`implementation-status.md`](../implementation-status.md)
- 2026-11-19 `main` 기준 SHA `fd106543aa90b59476e14f18f9be36e2b47757db`. M1 화면, PR #13 구독 화면, PR #23 알림 발송 워커(`48f6878cc57230bedd1693003b0e82eb9c8ae287`), PR #26 모바일 배지 줄바꿈(`32e71e4bb56c9137cb12f9b49088a66d949f988a`), PR #25 초기 운영 배포·적재·G4/사후 롤백 기록(`fd10654`)이 포함돼 있다.
- 구독 UI 계약은 PR #13(`ed3f28f38623afeccd41006548a9e4f54ed0e301` 병합)을 유지하며, PR #23에서 `ManageView.disabledReason` 표시(`WEBHOOK_404` 안내)와 백엔드 발송 워커가 병합됐다. 운영은 `ALERTS_ENABLED=false`·`COLLECTOR_ENABLED=false`를 유지 중이며 실제 이메일/Discord 사용자 발송·정기 수집 등 운영 게이트는 아직 남아 있다.

## 1. 기술 구성 및 파일 트리

- Java 21, Spring Boot `4.1.2` (`webmvc`, `thymeleaf`, `validation`) 기반이다.
- 정적 파일은 `src/main/resources/static/`에 두며 Bootstrap `v5.3.8` CSS, `app.css`, `filter-toggle.js`(`defer`)로 구성된다.
- `GongchaeBellApplication`이 `Asia/Seoul` `Clock` 빈을 등록해 날짜와 D-day를 KST로 계산한다.

```text
build.gradle.kts                      # [M1] WebMVC, Thymeleaf, Validation
src/main/resources/
├── application.yml                   # [M1] error, base-url / [PR13] alerts, forward-headers
├── static/
│   ├── css/
│   │   ├── app.css                   # [M1/PR26] 커스텀 스타일, 포커스·래핑·모바일 배지 줄바꿈
│   │   ├── bootstrap.LICENSE         # [M1] MIT 라이선스
│   │   └── bootstrap.min.css         # [M1] Bootstrap v5.3.8 CSS
│   └── js/
│       └── filter-toggle.js          # [M1] /jobs 모바일 필터 토글
└── templates/
    ├── error.html                    # [M1] 공통 오류
    ├── error/404.html                # [M1] 404
    ├── fragments/layout.html         # [M1] head/header/footer (PR13 /privacy 링크)
    ├── home.html                     # [M1] 홈 (/)
    ├── jobs.html                     # [M1/PR26] 목록 (/jobs, PR13 CTA 분기, PR26 모바일 배지)
    ├── job-detail.html               # [M1] 상세 (/jobs/{sn})
    ├── institution.html              # [M1] 기관 이력 (/institutions/{code})
    ├── privacy.html                  # [PR13] 개인정보처리방침 (/privacy)
    └── subscriptions/                # [PR13] 구독 흐름 템플릿
        ├── fragments.html            #   conditions(prefix), notice(channel)
        ├── new.html                  #   신청 (/subscriptions/new)
        ├── confirm.html              #   확인 (/subscriptions/confirm)
        ├── manage.html               #   관리 (/subscriptions/manage, PR23 disabledReason)
        ├── unsubscribe.html          #   해지 (/subscriptions/unsubscribe)
        ├── recover.html              #   복구 (/subscriptions/recover)
        └── notice.html               #   결과·오류·준비중 안내
src/main/java/kr/gongchae/bell/
├── catalog/
│   ├── CatalogController.java        # [M1] 웹 조회·SEO·404 라우팅
│   ├── CatalogRepository.java        # [M1] 목록·상세·기관·필터 조회
│   ├── CatalogViews.java             # [M1] 뷰 레코드, 상태·D-day·구분 라벨
│   ├── JobSearchCriteria.java        # [M1] normalize / [PR13] strict
│   ├── FreshnessService.java         # [M1] 기준 시각·2시간 지연 판정
│   ├── FreshnessModelInterceptor.java # [M1] 공통 freshness, lastCollected 주입
│   ├── SafeUrlValidator.java         # [M1] http/https 검증, 잡알리오 URL
│   └── CatalogMetrics.java           # [M1] 페이지뷰·원문 리다이렉트 집계
└── alerts/
    ├── AlertsConfiguration.java      # [PR13/PR23] alertsEnabled 주입, digestHeartbeat 등록
    ├── AlertsProperties.java         # [PR13] alerts, privacy 설정
    ├── OriginFilter.java             # [PR13] POST Origin/Referer 검사
    ├── SubscriptionController.java   # [PR13] /subscriptions/*, /privacy 라우트
    └── SubscriptionService.java      # [PR13/PR23] 신청·확인·관리(disabledReason)·해지·복구 로직
```

- PR #23(`#7` 알림 발송 워커)에서 추가된 내부 스케줄러·배치·발송·원장·메트릭 클래스(`AlertBatchGenerator`, `AlertDispatchProperties`, `AlertDispatcher`, `AlertMatcher`, `AlertOutbox`, `AlertPayloadBuilder`, `AlertScheduler`, `AlertWorker`, `AlertsMetrics`, `DeliveryLedger`)와 `ops/Heartbeat.java`는 백엔드 발송·관측 경로이며 웹 라우트나 폼 입력 계약은 바꾸지 않는다.

## 2. 공통 레이아웃, 모델 및 UI 규칙

| 프래그먼트 (`layout.html`) | 구성 요소 | 비고 |
|---|---|---|
| `head` | `charset`, `viewport`, `title`, `description`, `bootstrap.min.css`, `app.css` | `title`, `description` 바인딩 |
| `header` | 스킵 링크(`#main`), 로고(`/`), `채용공고`(`/jobs`), 지연 배너 | `freshness.delayed` 시 `role="status"` |
| `footer` | `freshness.display`, `수집 지연` 배지, 안내, 공공데이터포털, `/privacy`(PR13) | `freshness` 없을 때 `lastCollected` 폴백 |

- `FreshnessModelInterceptor`가 뷰에 `freshness`(`FreshnessStatus(display, delayed, available)`)와 `lastCollected`를 주입한다. `CatalogController.handleNotFound()`도 404에 같은 값을 넣는다.
- `AlertsConfiguration.AlertsModel`이 모든 뷰에 `alertsEnabled`(`alerts.enabled`, 기본 `false`)를 주입한다.
- 페이지네이션(`buildPageWindow`)은 `/jobs`와 `/institutions/{code}` 공통으로 20건 단위, 최대 5개 번호 창을 만든다. `totalPages <= 1`이면 생략하고 1페이지는 `page` 파라미터를 뺀다.
- `CatalogViews`는 `effectiveStatus`(`OPEN`·`CHECK`·`CLOSED`), `ddayLabel`(`마감`·`D-0`~`D-N`), `recrutSeLabel`(`R2010` 신입·`R2020` 경력·`R2030` 신입+경력·`미상`)을 계산한다.

## 3. 카탈로그·SEO·오류 화면 (M1)

| 화면 | 경로 (GET) | 템플릿 / 응답 | 핵심 UI 및 동작 | 연결 흐름 |
|---|---|---|---|---|
| 홈 | `/` | `home.html` | Hero와 바로가기 3종, 마감 임박(`D-0`~`D-3`, 12건), 오늘 시작(20건), 하단 알림 준비 배지 | `/jobs`, `/jobs/{sn}`, `/institutions/{code}`, `/jobs?sort=...` |
| 목록 | `/jobs` | `jobs.html` | `#jobs-filter-panel`, 결과 건수, 알림 CTA(`alertsEnabled` 시 링크), 테이블/카드, `문자 주의`, 페이지네이션 | `/jobs?...`, `/jobs/{sn}`, `/institutions/{code}`, `/subscriptions/new?...` |
| 상세 | `/jobs/{sn}` | `job-detail.html` | 브레드크럼, `brokenText` 경고, 메타, 자격·우대·전형(`pre-line-text`), `detailStatus == 'DONE'`일 때 단계·첨부 | `/jobs/{sn}/source`, 잡알리오 외부 URL, `/institutions/{code}` |
| 원문 이동 | `/jobs/{sn}/source` | `302 Found` | `src_url` 검증 후 외부 리다이렉트, 불안전·미존재 시 `404` | 외부 원문 |
| 기관 이력 | `/institutions/{code}` | `institution.html` | 최근 3년(예정 제외) 요약 카드 2종, 연·월·직무 표 3종, 공고 이력(테이블/카드·페이징), 기관비교 링크 | `/jobs/{sn}` |
| SEO | `/robots.txt`, `/sitemap.xml` | `text/plain`, `application/xml` | `Allow: /`, `Disallow: /subscriptions/`, 사이트맵(기관 1,000건·공고 5,000건) | 크롤러 |
| 오류 | 미존재·오류 / 서버예외 | `error/404.html`, `error.html` | 404 안내, 서버 오류 안내(원시 예외·스택 비노출) | `/`, `/jobs` |

## 4. 목록 필터(`/jobs`), 폼 계약 및 뷰 모델

### 4.1 Query Key 및 `strict` 검증 차이

| Query Key | 허용 값 / 규칙 | `/jobs` 기본·보정 (`normalize`) | 구독 POST (`JobSearchCriteria.strict`) |
|---|---|---|---|
| `ncs`, `region`, `hire` | `CatalogRepository.findFilterOptions()` 코드 | `null` (무시) | 허용 목록 외 `400` |
| `career` | `newcomer`, `newonly`, `career` | `null` (무시) | 허용 값 외 `400` |
| `status` | `open`, `check`, `closed`, `all` | `"open"` | 구독 조건 제외 |
| `sort` | `deadline`, `begin` | `"deadline"` | 구독 조건 제외 |
| `q` | 제목 검색어 (`trim`, 최대 100자) | 100자 초과 절단 | 100자 초과 `400` (`error='q'`) |
| `page` | 1 이상 정수 (20건 단위) | 1 (초과 시 마지막 페이지) | 구독 조건 제외 |

- `GET /subscriptions/new` 프리필은 `normalize`를 쓰고, 저장·수정 `POST`만 `strict`를 적용한다.
- `/jobs` 빈 상태는 3가지다. 활성 필터가 있으면 `[필터 초기화]`(`/jobs`), 활성 필터 없이 진행 공고가 0건이면 `[전체 공고 보기]`(`/jobs?status=all`), 전체 공고가 0건이면 안내 문구만 표시한다.

### 4.2 서버 모델 키 및 폼·레코드

| 화면 | 모델 키 | 타입 / 주요 필드 |
|---|---|---|
| 공통 | `freshness`, `lastCollected`, `alertsEnabled` | `FreshnessStatus(display, delayed, available)`, `String`, `boolean` |
| 홈 (`/`) | `title`, `description`, `closingSoon`, `startedToday` | `List<PostingSummary>` (`sn, instCode, instName, title, bgngDate, endDate, recrutSeLabel, effectiveStatus, statusLabel, ddayLabel, brokenText`) |
| 목록 (`/jobs`) | `filterOptions`, `criteria`, `result`, `hasAnyPosting`, `pageNumbers` | `Map<String, List<FilterOption(code, name)>>`, `JobSearchCriteria`, `JobSearchResult(items, totalCount, page, pageSize, totalPages)` |
| 상세 (`/jobs/{sn}`) | `title`, `description`, `job` | `PostingDetail` (`recrutNopeDisplay, aplyQlfc, pref, scrnprcdr, safeSrcUrl, srcUrlGeneric, alioUrl, detailStatus, steps, stepsUnknownFormat, files, filesUnknownFormat, ...`) |
| 기관 (`/institutions/{code}`) | `title`, `description`, `institution`, `pageNumbers` | `InstitutionDetailView(summary, yearlyRecruits, monthlyPatterns, ncsDistribution, history)` |
| 개인정보 (`/privacy`) | `title`, `description`, `privacy` | `AlertsProperties.Privacy(officer, contact, policyVersion, effectiveDate, hostingLocation, overseasBasis, resendRetention)` |
| 신청 (`new.html`) | `filterOptions`, `privacy`, `form`, `error`, `tab`, `email` | `form`은 `Conditions(ncs, region, hire, career, q, notifyNew, notifyDeadline)`, `error`는 `email`, `webhook`, `kinds`, `consent`, `age14`, `q`, `ncs`, `region`, `hire`, `career`이며 마지막 네 값은 일반 조건 오류 분기로 표시 |
| 확인·해지 | `title`, `token` | `token`은 쿼리 `t` 문자열 (hidden input으로 POST 전송) |
| 관리 (`manage.html`) | `title`, `view`, `form`, `error`, `filterOptions` | `view`는 `ManageView(id, channel, status, target, conditions, token, disabledReason)` (`target`은 마스킹 이메일 또는 `"Discord 웹훅"`, `status != 'ACTIVE'`이고 `disabledReason == 'WEBHOOK_404'`일 때 웹훅 404 재신청 안내 표시) |
| 결과 (`notice.html`) | `title`, `heading`, `message`, `detail`, `manageLink` | `manageLink`는 `"/subscriptions/manage?t=" + token` (선택 포함) |

## 5. 구독·개인정보 화면 및 폼 계약 (PR #13)

`GET`은 조회·확인 화면만 렌더링하고 모든 상태 변경은 `POST`로 처리한다. `alerts.enabled=false`일 때 `/subscriptions/new`·`/subscriptions/recover`의 `GET`은 `200` `notice.html`을 반환하고 `/subscriptions/confirm`·`/subscriptions/manage`·`/subscriptions/unsubscribe`의 `GET`과 모든 `/subscriptions/*` `POST`는 `503`을 반환한다(원클릭 해지는 빈 본문 `503`).

| 흐름 | Method & Path | 입력 (Query / Form) | 기본 응답 (`alerts.enabled=true`) 및 비활성 시 |
|---|---|---|---|
| 개인정보처리방침 | `GET /privacy` | 없음 | `200` `privacy.html` (`alertsEnabled=false` 배너, `PRIVACY_*` 미설정 시 `'미정 (운영 시작 전 설정)'`) |
| 신청 화면 | `GET /subscriptions/new` | Query `ncs`, `region`, `hire`, `career`, `q` | `200` `new.html` (`#email`/`#discord` 동시 렌더, `notifyNew=true`, `notifyDeadline=true`, `tab="email"`). 비활성 `200` `notice.html` |
| 이메일 신청 | `POST /subscriptions/email` | `email`(254), 조건 5종, `notifyNew`, `notifyDeadline`, `consent`, `age14` | 오류 `400` `new.html`, 정상·중복·상한 모두 `200` `notice.html`. 비활성 `503` |
| 디스코드 신청 | `POST /subscriptions/discord` | `webhook`(300), 조건 5종, `notifyNew`, `notifyDeadline`, `consent`, `age14` | 오류·웹훅404 `400`, 등록 `200` `notice.html`(`manageLink` 1회), 기등록 `200`, 실패 `502`. 비활성 `503` |
| 확인 화면·실행 | `GET/POST /subscriptions/confirm` | Query `t` / Form `t` | `GET` `200` `confirm.html`, `POST` 성공 `200` `notice.html`(`manageLink`), 무효 `400`. 비활성 `GET`/`POST` `503` |
| 관리 화면 | `GET /subscriptions/manage` | Query `t` | `200` `manage.html` (`ACTIVE`만 저장 활성), 무효 `400`. 비활성 `503` |
| 수정 / 해지 | `POST /subscriptions/manage` | Form `t`, `action`(`update`/`unsubscribe`), 조건 필드 | 성공·비활성구독·해지 `200` `notice.html`, 입력오류 `400` `manage.html`. 비활성 `503` |
| 해지 화면·실행 | `GET/POST /subscriptions/unsubscribe` | Query `t` / Form `t` | `GET` `200` `unsubscribe.html`, `POST` 해지 `200` `notice.html`, 무효 `400`. 비활성 `GET`/`POST` `503` |
| 원클릭 해지 | `POST /subscriptions/unsubscribe/one-click` | Query `t`, Body `List-Unsubscribe=One-Click` | 성공 `200 "unsubscribed"`, 무효 `400`, 차단 `429`. 비활성 빈 본문 `503` |
| 복구 화면 | `GET /subscriptions/recover` | 없음 | `200` `recover.html`. 비활성 `200` `notice.html` |
| 이메일 복구 | `POST /subscriptions/recover/email` | Form `email` | 오류 `400`, 유효 형식이면 가입 여부 무관 `200` `notice.html`. 비활성 `503` |
| 디스코드 복구 | `POST /subscriptions/recover/discord` | Form `webhook` | 오류 `400`, 일치 시 `200` `notice.html`(`manageLink`), 미일치 `200`. 비활성 `503` |

## 6. 반응형, 보안 및 상태 인터페이스

- **반응형·접근성 (360px)** `viewport`와 `.container` 최대 폭 `1080px`를 유지한다. `/jobs`와 `/institutions/{code}` 이력은 `d-none d-md-block` 테이블과 `d-md-none` 카드로 분기하고 `/jobs` 모바일 필터는 `filter-toggle.js`로 토글한다. `/jobs` 모바일 카드(`d-md-none`)는 상단 기관명 `.job-card-inst`(`min-width: 0; overflow-wrap: anywhere`), D-day·상태 묶음 `.flex-shrink-0`, 하단 메타 배지 래퍼 `.job-card-badges`(`.badge { line-height: 1.3 }`) 안의 직무·지역·고용형태 다중 값 배지 `.job-meta-badge`(`white-space: normal; text-align: left; overflow-wrap: anywhere; max-width: 100%`)로 360px 가용 폭 안에서 줄바꿈한다(`D-day`·`상태`·`구분`·`문자 주의` 등 짧은 배지는 `white-space: nowrap` 유지). `overflow-wrap: anywhere`, `.table-responsive`, `text-break`, 스킵 링크, `:focus-visible`, `aria-*`를 유지한다.
- **Escape·외부 링크** 출력은 `th:text`로 자동 이스케이프하며 `SafeUrlValidator`가 `http`/`https` 외 스킴과 제어문자·userinfo를 걸러낸다. 외부 링크는 `target="_blank" rel="noopener noreferrer"`를 부여하고 `/jobs/{sn}/source`는 `Referrer-Policy: no-referrer`를 내린다. 알 수 없는 상세 JSON은 안내 문구만 표시한다.
- **Origin·토큰 보호** `OriginFilter`가 상태 변경 요청의 `Origin`/`Referer`를 `app.base-url`과 대조해 불일치 시 `403`을 반환한다. 토큰 POST(`/confirm`, `/manage`, `/unsubscribe`, `/unsubscribe/one-click`)만 `Origin` 누락·`null`을 허용한다. 토큰 화면은 `Referrer-Policy: no-referrer`, `Cache-Control: no-store`, `X-Robots-Tag: noindex`를 내리고 `manageLink`는 `rel="noreferrer"`를 붙인다. 관리 화면은 이메일 마스킹과 `"Discord 웹훅"`만 노출하며 웹훅 오류 시 입력값을 다시 채우지 않는다.

| 상태 / 오류 | 조건 | HTTP | UI 표시 |
|---|---|---|---|
| 미수집·문자주의·지연 | `detail_status != 'DONE'`, `brokenText`, `freshness.delayed` | `200` | `"상세 정보 미수집"`, `"문자 주의"`, 헤더 지연 배너 |
| 입력·웹훅404·토큰오류 | 폼 검증 실패, 디스코드 404, 무효·만료 `t` | `400` | 인라인 오류 또는 `notice.html` |
| Origin 불일치 / 요청 제한 | 타 도메인 POST / 반복 요청 제한 | `403` / `429` | 차단 또는 `"요청이 너무 많습니다"` |
| 테스트 실패 / 지연·비활성 | 웹훅 테스트 실패 / 잠금·시간초과·`ALERTS_ENABLED=false` | `502` / `503` | `notice.html` (원클릭 비활성 빈 `503`) |
| 페이지 없음 / 서버 오류 | 미존재 경로·ID / 처리되지 않은 예외 | `404` / `5xx` | `error/404.html` / `error.html` |

## 7. 구현 상태 요약

| 구분 | 기준 | 현재 상태 |
|---|---|---|
| M1 웹 카탈로그 | `main` 포함, 최초 구현 `000dce555e9eb69ef3184dd5f80aa1d499a1f602`, 모바일 배지 수정 PR #26(`32e71e4bb56c9137cb12f9b49088a66d949f988a`) | 홈, 목록(`/jobs` 모바일 배지 줄바꿈 포함), 상세, 원문 이동, 기관 이력, SEO, 오류 화면, 레이아웃, CSS/JS 반영 완료 |
| PR #13 구독·개인정보 | `main` 포함, 병합 `ed3f28f38623afeccd41006548a9e4f54ed0e301` | `/privacy`, `/subscriptions/*`, 푸터 링크, `jobs.html` CTA, 동기 신청·웹훅 검증·테스트 발송·확인·관리·해지·복구 구현 (`home.html` 알림 섹션은 준비 배지 유지, 운영 `ALERTS_ENABLED=false`) |
| `#7` 알림 발송 워커 (PR #23) | `main` 포함, 병합 `48f6878cc57230bedd1693003b0e82eb9c8ae287` | 새 공고 매칭, 이메일 일일 묶음, 디스코드 회차별 발송, D-3 마감 임박, 비동기 발송·재시도·404 비활성화(`disabledReason`)·수집→발송 지연 지표·`HEARTBEAT_DIGEST_URL` 백엔드 코드 병합 완료 (`ALERTS_ENABLED=false` 유지로 실제 운영 발송 미개시) |
| M3 공개 배포·관측 | `main` `fd10654`([`codex-runtime-review.md`](../../collab/2026-11-19-resume/codex-runtime-review.md)) | 공개 HTTPS·운영 DB 최초 적재(36,879건)·모니터링 설치·외부 G4 전환/사후 롤백(`836a3c0`↔`517eb6b`) 및 `32e71e4` 공개 모바일 360px 재검수 완료. 단 `ALERTS_ENABLED=false`·`COLLECTOR_ENABLED=false` 유지 중이며 실제 Resend·Discord 사용자 발송, 정기 수집 활성화, 운영 디스코드 채널 사람 확인, UptimeRobot/Healthchecks, 백업·복원, 부하 등 운영·공개 출시 게이트는 미완료 |
