# PR #10 리뷰 · claude (구현자 외 리뷰)

> 2026-11-15 · 조율: codex · 대상: PR #10 `feat/#5-catalog-web`, head `4ef78be3708e4810bc1f857cf0548aad1d28af37` (초안, CI [43654479301](https://github.com/Lcjam/gongchae-bell/actions/runs/43654479301) test·secrets 성공)
> 읽은 것: 전용 경로 `issue-5-catalog-web`의 CLAUDE.md, PRD FR-W1~W8·G2, `catalog` Java 8개, 템플릿 전체(공통·홈·목록·상세·기관·오류), `filter-toggle.js`, `app.css`·`application.yml` 변경, `CatalogUnitTest`, `CatalogIntegrationTest`, `agy-catalog-result.md`(커밋본), 원본의 `05-codex-catalog-review.md`. 연동 확인을 위해 #4 경로(`issue-4-collector-runtime`, `8db36bc`)의 `DetailCollector`와 data.go.kr 데이터셋 페이지의 공식 swagger를 읽었다.
> **읽기 전용**: 코드, 다른 브랜치, 사용자 변경을 고치지 않았고 테스트나 브라우저를 다시 돌리지 않았다. 리뷰 중에 `agy-catalog-result.md`가 작업 경로에서 커밋되지 않은 채 바뀌었는데, 그 변경분은 보지 않았다.

## 결론
**머지 전에 정해야 할 것은 2건(M1, M2)이다.** 둘 다 근거가 있는 연동·정확성 문제이고, 고치는 방향은 결정이 필요하다. 나머지 Low 6건은 이번 PR이나 후속 이슈에서 처리해도 된다. 이스케이프, 원문 링크 검증, 필터, 3년 통계 계산, 오류 화면 기준 시각은 코드와 테스트로 확인했고 문제를 찾지 못했다.

## Medium

### M1. 상세 `steps`의 실제 형식을 #5가 읽지 못하고, 원시 값을 이어 붙여 화면에 낸다 (#4 연동)
- **근거**
  - 공식 swagger(data.go.kr 데이터셋 15125273 페이지에 들어 있는 `detail_response`)의 `steps` 원소는 `{"step": {maxStepSn, aplyNope, cmpttRt, minStepSn, recrutNope, recrutPbancTtl, recrutPblntSn, recrutStepSn, rsnOcrnYmd, sortNo}}`이다. **단계 이름이나 설명 필드가 없다.** 지원자 수(`aplyNope`), 경쟁률(`cmpttRt`), 단계 일련번호, 공고 제목·번호 같은 값만 있다.
  - #4 `DetailCollector.java:53-54`(`8db36bc`)는 `step` 래퍼만 벗기고 객체를 그대로 `posting.steps`에 저장한다.
  - #5 `CatalogRepository.parseSteps`(:537-541)는 `recrutStepNm`, `stepNm`, `stepTitle`, `title`, `name`(제목)과 `recrutStepCn`, `stepCn`, …(설명)만 찾는다. swagger 형식에는 어느 것도 없으므로 `joinObjectValues`(:600-612)로 빠진다. 그 결과 단계마다 `"1 · 30 · 2.5 · 1 · 5 · <공고 제목> · 307032 · 2 · …"` 같은 원시 값이 "전형 단계" 목록에 나온다.
  - 테스트 시드는 공식 형식에 없는 키(`stepNm`, `stepCn`, `CatalogIntegrationTest.java:166`)를 써서 이 경로를 잡지 못한다.
- **첨부(`files`)는 맞는다.** swagger의 `{"file": {atchFileNm, atchFileType, recrutAtchFileNo, sortNo, url}}`을 #4가 벗겨 저장하고, #5는 `atchFileNm`과 `url`을 읽는다.
- **한계**: 실제 detail 응답은 아직 아무도 받지 못했다. 위 판단은 공식 swagger 기준이다.
- **제안(추측 구현 없이)**: (a) `joinObjectValues` 대체 출력을 없애고, 아는 키가 없으면 단계 목록 대신 "단계 정보 형식 확인 중" 같은 안내를 낸다. (b) 테스트 시드를 swagger 형식 객체로 바꿔 원시 값이 화면에 나오지 않음을 확인한다. (c) 필드 의미(지원자 수·경쟁률을 보여 줄지)는 #4가 실제 detail 응답을 1회 받은 뒤 정한다. 화면 문구와 FR-W3 해석이 걸리므로 조율자 결정 사항이다.

### M2. 시작일이 오늘 이후인 진행 공고가 기관 페이지 통계와 이력에서 빠진다
- **근거**: `CatalogRepository.findInstitutionDetail`의 모든 쿼리(:292, :315, :340, :372, :398)가 `bgng_date BETWEEN :fromDate AND :toDate(=오늘)`다. 실측(공개 경로 진행 공고 497건, 2026-11-14 22:22 KST 1회 수집, #3 준비 때 저장한 스냅숏)에서 **시작일이 수집일 이후인 공고가 4건**(시작일 2026-11-16, 2027-01-18) 있었다. 이 공고들은 `/jobs`(진행 중)에는 나오지만, 그 기관 페이지의 "최근 3년 고유 공고 수", 연도·월별 표, 직무 분포, 이력에는 없다. 사용자가 목록 → 기관으로 이동하면 방금 본 공고가 기관 이력에 없다.
- **선택지**: (a) 상한을 없애고 `bgng_date >= :fromDate`로 둔다. 기준 기간 문구를 "최근 3년 이후 시작한 공고"로 바꾸고, 연도·월 표에 미래 연도 행이 생길 수 있다. (b) 지금처럼 두되 화면에 "오늘까지 시작한 공고"라고 명시한다. PRD FR-W4 문구("최근 3년, 공고 시작일 기준")만으로는 정해지지 않으므로 조율자 결정이 필요하다. 어느 쪽이든 경계 테스트(시작일 = 내일)를 하나 둔다.

## Low
| # | 내용 | 근거 | 제안 |
|---|---|---|---|
| L1 | G2 화면 조회 수에 404가 섞인다 | `jobDetail`(:96)과 `institution`(:132)이 조회 전에 `recordPageView`를 부른다. 없는 `sn`이나 기관을 훑는 봇 요청도 `job_detail`·`institution` 조회로 센다 | 조회 성공 뒤에 기록 |
| L2 | 새 환경변수 `APP_BASE_URL`이 `.env.example`에 없다 | `application.yml`의 기본값은 `http://localhost:8080`이다. 운영에서 빠뜨리면 `sitemap.xml`이 localhost URL을 낸다 | `.env.example`에 키 이름 추가, runbook 환경변수 목록에 기록(M3) |
| L3 | 빈 상태 문구가 틀릴 수 있다 | 기본 필터(`status=open`)에서 진행 공고가 0건이면, 마감 공고가 DB에 있어도 "아직 수집된 공고가 없습니다"가 나온다(`jobs.html:108`) | 전체 공고 수가 0일 때만 그 문구를 쓴다 |
| L4 | JS 없이는 360px에서 필터를 열 수 없다 | 필터 폼이 기본 `d-none`이고 `filter-toggle.js`로만 열린다 | `<details>`/`<summary>`로 바꾸면 JS 없이도 열린다. 선택 사항 |
| L5 | 합본 뒤 테스트 격리 | `CatalogIntegrationTest`는 자체 `@Container`와 고정 `Clock` 설정을 쓰고, `@BeforeEach`로 지운 뒤 시드만 넣는다(정리 없음). 지금은 별도 컨텍스트라 문제없다. #3의 공용 `IntegrationTestSupport`로 옮기면 남긴 COMPLETE 회차 때문에 `FoundationIntegrationTest`의 빈 기준 시각 검사가 실행 순서에 따라 깨진다 | 공용 기반으로 옮길 때 `@AfterEach` 정리를 함께 넣는다 |
| L6 | 지연 배너가 잠깐 깜빡일 수 있다(관측) | 기준이 "마지막 COMPLETE 후 2시간 초과"다. 정기 수집이 한 번만 실패해도 다음 회차가 끝나기 전 수십 초~수 분 동안 배너가 뜬다. PRD 경보 조건은 "2회 연속 미완료"다 | codex 지시(2시간)를 따른 것이라 결함은 아니다. 운영에서 거슬리면 기준을 다시 정한다 |

## 확인했고 문제를 찾지 못한 것
- **이스케이프(FR-W6)**: 템플릿에 `th:utext`, `th:inline`, 전처리 `__${}__`가 없다(grep). 원천 문자열은 모두 `th:text`/`th:content`로 출력된다. XSS 페이로드 테스트(`jobDetailEscapesHtml…`)가 있다.
- **원문 링크**: `/jobs/{sn}/source`는 DB 값으로만 이동한다(`url` 쿼리 인자를 무시하는 테스트 있음). `SafeUrlValidator`는 http(s) 스킴, 호스트 필수, userinfo 거부, 제어 문자 거부를 한다. `ResponseEntity.location(URI)`는 `toASCIIString()`으로 비ASCII 문자를 인코딩한다. 포트 `:442`가 그대로 통과한다(테스트). 무효 URL이면 원문 버튼을 숨기고 잡알리오 링크를 낸다. 실측 497건 중 http(s)로 파싱되지 않는 `srcUrl` 19건이 이 경로를 탄다.
- **#3 우대 형식(v2)**: `pref`를 나누지 않고 `pre-line`으로 그대로 출력한다. 라벨 단락 형식과 맞는다. 다만 테스트 시드는 `'우대사항'` 고정 문자열이라, #3 형식은 합본 뒤 `PostingIngestor`로 fixture를 넣고 화면까지 보는 테스트에서 확인하는 편이 좋다.
- **품질 표시**: `broken_text` 배너가 "포함되어 있을 수 있습니다"로 의심 표현이다. `src_url_generic`이면 잡알리오 링크와 설명을 함께 낸다.
- **필터(FR-W1)**: 코드 필터는 `code_name` 허용 목록 + 유형별 `EXISTS`, `career`의 R2010/R2030/R2020 매핑, `open`은 `OPEN·CHECK`이면서 `end_date >= 오늘`, `closed`는 마감일이 지난 OPEN까지 포함, `LIKE` 와일드카드 이스케이프(`ESCAPE '\'`), 페이지 범위 보정, 정렬 동점에 `sn`. 테스트가 결과 번호와 순서를 정확히 비교하고 페이지 간 중복이 없음을 확인한다.
- **홈(FR-W2)**: 마감 임박은 `OPEN`이면서 D-0~D-3, 오늘 시작은 `bgng_date = 오늘`(상태 무관).
- **기관 통계(FR-W4)**: 숫자 인원만 합산하고 비숫자는 건수로 따로 센다. 직무는 `COUNT(DISTINCT sn)`. 잡알리오 기관비교 링크가 있다. "실제 채용 실적" 문구가 없다(테스트). M2 외의 경계(3년 전 당일 포함, 전날 제외)는 테스트가 확인한다.
- **기준 시각(FR-W5)**: 인터셉터가 일반 화면과 Boot `/error` dispatch에 모두 기준 시각을 넣는다. DB 조회가 실패하면 "일시적으로 수집 시각을 확인할 수 없습니다"로 대체해서 오류 화면이 다시 실패하지 않는다. 실제 HTTP로 404와 500을 확인하는 테스트가 있다.
- **360px**: 목록·이력 표는 모바일에서 숨기고 카드로 낸다. 기관 표 3개는 `.table-responsive` 안에 있다. agy의 Orca 브라우저 기록에 `scrollWidth = innerWidth = 360`이 있다. 나는 브라우저를 다시 돌리지 않았다.
- **운영**: 지표 라벨은 `page`(고정값)와 `traffic`(3종)뿐이라 카디널리티가 늘지 않는다. 화면 요청은 외부 API를 부르지 않는다. 새 마이그레이션은 없다.
