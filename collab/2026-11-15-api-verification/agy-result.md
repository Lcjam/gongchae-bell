# #4 공식 API 실제 계약 검증 결과 · agy

- **작업 경로**: `C:/Users/lcj/orca/workspaces/my-project/official-fixtures`
- **브랜치**: `docs/#4-official-fixtures` (기반 커밋: `7b3eb31aa218029f7f54f07895426c4bb8697ab1`)
- **검증 일시**:
  - 1차 시도(원문 환경변수 그대로 전달): `2026-11-15 14:35:22 KST` (`2026-11-15T05:35:22Z`)
  - 2차 시도(메모리 단일 percent-decode 교정 후 재검증): `2026-11-15 14:41:41 ~ 14:41:46 KST` (`2026-11-15T05:41:41Z ~ 05:41:46Z`)
- **호출 경로**: `.gitignore` 대상 `.tools/CaptureOfficialFixtures.java`에서 `#4`의 실제 `RecruitmentClient(ApiBudget, CollectorProperties, Clock)` + `ApiBudget`만 호출 (`src/`·테스트·CI 코드 변경 없음, 원본 `.env` 미수정, 임의 `curl`/`fetch` 우회 없음, 원격 push·머지·3년 전체 적재 없음)
- **영속 DB**: 로컬 `compose.dev.yml` PostgreSQL 17 컨테이너(`gongchae-bell-dev-postgres-1`, `Flyway` `V1`~`V3` 적용 완료)

---

## 1. 호출 이력 및 결과 요약

### 1-1. 1차 시도 (보존 기록, `2026-11-15 14:35:22 KST`)

| 구분 | 대상 엔드포인트 | 요청 파라미터 키·값 (비밀값 제외) | 결과 | 비고 |
|---|---|---|---|---|
| **1. 공식 `list`** (`REGULAR`) | `https://apis.data.go.kr/1051000/recruitment/list` | `serviceKey=[REDACTED]`, `resultType=json`, `ongoingYn=Y`, `numOfRows=1000`, `pageNo=1` | **`LIST_FAILURE=HTTP_STATUS`** | non-200 응답(`500..599`·`429` 외), 재시도 없이 중단 (`detail`·빈 기간 미호출) |

### 1-2. 2차 시도 — 메모리 단일 percent-decode 교정 (`2026-11-15 14:41:41 ~ 14:41:46 KST`)

- **키 정규화 방식**: 원본 `.env` 파일은 수정하지 않고, 프로세스 메모리에서 `COLLECTOR_SERVICE_KEY`가 `%`를 포함하고 모든 `%XX`가 유효한 16진수 2자리인지 검증한 뒤 UTF-8 `%XX`만 정확히 1회 디코딩했다(`KEY_NORMALIZATION=DECODED_PERCENT_ONCE`, 평문 `+`를 공백으로 바꾸는 `URLDecoder` 미사용). 이 값을 `CollectorProperties`에 넣고 기존 `RecruitmentClient`가 `URLEncoder.encode`를 1회 수행해 호출했다.
- 원본 키·정규화 키·재인코딩 키 모두 로그·예외·저장 fixture에서 비노출/제거(`secret_hits=0`)를 확인했다.

| 구분 | 대상 엔드포인트 | 요청 파라미터 키·값 (비밀값 제외) | 결과 | 저장 fixture (본문 원본) |
|---|---|---|---|---|
| **1. 공식 `list`** (`REGULAR`) | `https://apis.data.go.kr/1051000/recruitment/list` | `serviceKey=[REDACTED]`, `resultType=json`, `ongoingYn=Y`, `numOfRows=1000`, `pageNo=1` | **`HTTP 200` / `resultCode=200`** (`totalCount=497`, `result` 497건, `sn=307032` 추출) | [`fixtures/alio-official-list-sample.json`](../../fixtures/alio-official-list-sample.json) (`2,019,335` bytes) |
| **2. 공식 `detail`** (`DETAIL`) | `https://apis.data.go.kr/1051000/recruitment/detail` | `serviceKey=[REDACTED]`, `resultType=json`, `sn=307032` | **`HTTP 200` / `resultCode=200`** (`result` 단일 객체, `steps` 4건, `files` 3건) | [`fixtures/alio-official-detail-sample.json`](../../fixtures/alio-official-detail-sample.json) (`2,583` bytes) |
| **3. 빈 기간 `list`** (`BACKFILL`) | `https://apis.data.go.kr/1051000/recruitment/list` | `serviceKey=[REDACTED]`, `resultType=json`, `pbancBgngYmd=2010-01-01`, `pbancEndYmd=2010-01-31`, `numOfRows=1000`, `pageNo=1` | **`HTTP 200` / `resultCode=200`** (`totalCount=0`, `result=[]`) | [`fixtures/alio-official-list-empty-sample.json`](../../fixtures/alio-official-list-empty-sample.json) (`79` bytes) |

---

## 2. 영속 PostgreSQL `api_budget` / `api_attempt` 증거

2차 검증 직후 `gongchae-bell-dev-postgres-1`의 `gongchae` DB 조회 실측:

- **`flyway_schema_history`**: `1` (`catalog and ingestion`), `2` (`collection freshness index`), `3` (`collector runtime records`) 모두 `success = t`
- **`api_budget`**: `day = 2026-11-15`, `count = 4` (허용 상한 9회 이내 누적 4회)
- **`api_attempt`** (전체 4건):

| id | day | kind | attempt_no | started_at (UTC / KST) | finished_at (UTC / KST) | outcome |
|---:|---|---|---:|---|---|---|
| 1 | 2026-11-15 | `REGULAR` | 1 | `05:35:22.114541+00` (`14:35:22 KST`) | `05:35:22.597067+00` (`14:35:22 KST`) | `HTTP_STATUS` |
| 2 | 2026-11-15 | `REGULAR` | 1 | `05:41:41.93117+00` (`14:41:41 KST`) | `05:41:45.228328+00` (`14:41:45 KST`) | `SUCCESS` |
| 3 | 2026-11-15 | `DETAIL` | 1 | `05:41:45.383379+00` (`14:41:45 KST`) | `05:41:45.602367+00` (`14:41:45 KST`) | `SUCCESS` |
| 4 | 2026-11-15 | `BACKFILL` | 1 | `05:41:45.64758+00` (`14:41:45 KST`) | `05:41:46.008258+00` (`14:41:46 KST`) | `SUCCESS` |

---

## 3. 관측과 추정의 구분 (인증키 및 관측성)

1. **관측된 사실**:
   - `.env`의 `COLLECTOR_SERVICE_KEY`는 길이 `96`, `%` 포함(`hasPercent=True`), 모든 `%XX`가 유효한 16진수 이스케이프였다.
   - 원문 그대로 `RecruitmentClient`(`URLEncoder.encode` 1회)에 넣은 1차 시도는 `HTTP_STATUS`로 실패했으나, 유효 `%XX`를 1회만 percent-decode한 값을 `CollectorProperties`에 넣어 같은 `RecruitmentClient`로 호출한 2차 시도에서는 3개 엔드포인트가 모두 `HTTP 200` + `resultCode=200`으로 성공했다.
2. **추정(해석)**:
   - 환경변수에 들어 있던 값이 이미 percent-encoded 형태(포털의 Encoding 키 표기 등)였기 때문에, `docs/ops/collector.md`의 Decoding 키 전제하에 `RecruitmentClient`가 다시 `URLEncoder.encode`를 수행했던 1차 시도에서 이중 인코딩으로 거절됐을 가능성이 높다. (사용자가 잘못 입력했다고 단정하지 않으며, 포털이 Decoding/Encoding 두 표기를 제공하는 데서 생기는 입력 계약 경계 문제로 본다.)
   - **운영 관측성 참고**: `RecruitmentClient`가 비-5xx/비-429 HTTP 오류를 모두 `HTTP_STATUS` 하나로만 축약하므로, `api_attempt`와 로그만으로는 `400`/`401`/`403`/`404` 구분이 어렵다.

---

## 4. 실제 HTTP JSON 봉투 · 배열 · 빈 응답 형식 검증 결과

### 4-1. 공개 AJAX 응답 vs 공식 OpenAPI 응답 Provenance 구분

| 구분 | 출처 | 최상위 봉투 | `result` 내부 구조 | 비고 |
|---|---|---|---|---|
| **공개 AJAX (`alio-public-list-*.json`)** | `opendata.alio.go.kr/new/odaApiMng/recrutInquiryAjaxList.do` (2026-11-14) | `{"data": {"result": [...], "resultCode": 200, "totalCount": 497, "resultMsg": ...}, "pathParam": "recrut"}` | `"item"` 래퍼 없는 30개 필드 객체 배열 | `#3` 단위 테스트(`ListFixture`)는 `"data"` 래퍼를 벗겨 사용 |
| **공식 OpenAPI `list` (`alio-official-list-sample.json`)** | `apis.data.go.kr/1051000/recruitment/list` (2026-11-15) | **`{"resultCode": 200, "resultMsg": "성공했습니다.", "totalCount": 497, "result": [...]}`** (`"data"`·`"pathParam"` 없음) | `"item"` 래퍼 없는 30개 필드 객체 497건 (`steps=[]`, `files=[]` 497건 전부 빈 배열, 필수 필드/날짜 오류 0건) | `#3 ListResponseParser.parse()` 직접 파싱 확인: `totalCount=497`, `rowsSize=497` |
| **공식 OpenAPI `detail` (`alio-official-detail-sample.json`)** | `apis.data.go.kr/1051000/recruitment/detail?sn=307032` (2026-11-15) | `{"resultCode": 200, "resultMsg": "성공했습니다.", "result": {...}}` | `result`는 단일 30개 필드 객체 (`"item"` 래퍼 없음, `ongoingYn=null`). `steps`(4건)·`files`(3건) 원소에 `"step"`/`"file"` 래퍼 없음 | `#4 DetailCollector` 조건(`resultCode=200`, `recrutPblntSn=307032`, `steps`/`files` 배열) 일치 확인 |
| **공식 OpenAPI 빈 기간 (`alio-official-list-empty-sample.json`)** | `apis.data.go.kr/1051000/recruitment/list` (`2010-01-01~2010-01-31`, 2026-11-15) | `{"resultCode": 200, "resultMsg": "성공했습니다.", "totalCount": 0, "result": []}` | 빈 배열 `[]` (`resultCode=200`) | `#3 ListResponseParser.parse()` 직접 파싱 확인: `totalCount=0`, `rowsSize=0` |

### 4-2. 공식 `detail` (`sn=307032`) `steps`·`files` 실측 구조 (`#4`·`#5` 후속 참고)

1. **`files` 배열 (3건, `"file"` 래퍼 없음)**:
   - 필드: `recrutAtchFileNo`, `sortNo`, `atchFileNm`, `atchFileType`, `url` (`https://opendata.alio.go.kr/recruit/downloadAtchFile?recrutAtchFileNo=...`)
   - 현재 `#4 DetailCollector`와 `#5 CatalogRepository`가 기대하는 `atchFileNm`·`url` 계약과 일치한다.
2. **`steps` 배열 (4건, `"step"` 래퍼 없음)**:
   - 필드: `recrutStepSn`, `recrutPblntSn`, `recrutPbancTtl`, `recrutNope`, `aplyNope`, `cmpttRt`, `rsnOcrnYmd`, `sortNo`, `minStepSn`, `maxStepSn`
   - **실측 특이점**:
     - `sortNo`가 0부터 시작(`0`, `1`)하고, 동일 `sortNo`에 `minStepSn`~`maxStepSn` 범위의 여러 행(`cmpttRt=null`과 `cmpttRt=0.0` 등)이 반복된다.
     - `recrutPbancTtl`에는 공고 제목이 아니라 전형 구분 라벨(`"진료교수"`, `"진료의사"`)이 들어가 있으며, `recrutNope`·`aplyNope`·`rsnOcrnYmd`는 `null`로 올 수 있다.
     - (`#5` PR `#10`은 공식 문서 기준으로 `recrutPbancTtl` 출력을 보류한 상태이므로, 합본/후속 검수 시 이 실측 구조를 바탕으로 라벨/중복 표시 정책을 결정할 수 있다.)

---

## 5. 비밀값 점검 및 변경 범위 확인

- Python 교차 검사: 저장한 `fixtures/alio-official-*.json` 3건에서 `.env`의 `COLLECTOR_SERVICE_KEY`(원문·디코딩·재인코딩 및 16자 접두사)·`DB_USER`·`DB_PASSWORD` 부분문자열 `secret_hits=0`.
- `gitleaks dir --redact` 검사 통과 후 로컬 `docs/#4-official-fixtures` 브랜치의 **로컬 커밋 `b95fa67`**(2026-11-15 14:44:33 KST)로 보존했다. 원격에는 push하지 않았다. (2026-11-15 claude 정정: 원래 문구 "커밋·푸시 없이 보존"은 사실과 달랐다. Sol 검수 지적. `git ls-remote origin`에 이 브랜치가 없음을 확인했다. #4 브랜치에는 claude가 `5b78c55`·`b95fa67`을 cherry-pick해 옮겼다.)
- 앱 소스(`src/`), 설정(`application.yml`), Flyway 스크립트, 원본 `.env`, Claude 수정 브랜치(`feat/#3-ingestion-core`, `feat/#4-collector-runtime`), 3년 전체 적재 모두 건드리지 않았다.
