# #4 공식 API 실제 계약 검증 결과 · agy

- **작업 경로**: `C:/Users/lcj/orca/workspaces/my-project/official-fixtures`
- **브랜치**: `docs/#4-official-fixtures` (기반 커밋: `7b3eb31aa218029f7f54f07895426c4bb8697ab1`)
- **검증 일시**: `2026-11-15 14:35:22 KST` (`2026-11-15T05:35:22Z`)
- **호출 경로**: `.gitignore` 대상 `.tools/CaptureOfficialFixtures.java`에서 `#4`의 실제 `RecruitmentClient(ApiBudget, CollectorProperties, Clock)` + `ApiBudget`만 호출 (`src/` 및 테스트·CI 코드 변경 없음, 임의 `curl`/`fetch` 우회 없음, 원격 push/머지 없음)
- **영속 DB**: 로컬 `compose.dev.yml` PostgreSQL 17 컨테이너(`gongchae-bell-dev-postgres-1`, `Flyway` `V1`~`V3` 적용 완료)

---

## 1. 호출 내역 및 결과

| 구분 | 대상 엔드포인트 | 요청 파라미터 키·값 (비밀값 제외) | 결과 | 비고 |
|---|---|---|---|---|
| **1. 공식 `list`** (`REGULAR`) | `https://apis.data.go.kr/1051000/recruitment/list` | `serviceKey=[REDACTED]`, `resultType=json`, `ongoingYn=Y`, `numOfRows=1000`, `pageNo=1` | **`LIST_FAILURE=HTTP_STATUS`** | 1차 시도에서 non-200 응답(`500..599`·`429` 외)으로 재시도 없이 즉시 종료 |
| **2. 공식 `detail`** (`DETAIL`) | `https://apis.data.go.kr/1051000/recruitment/detail` | 미호출 (`sn` 미확보) | **미실행** | 포털 오류 반복 호출 금지 지침에 따라 중단 |
| **3. 빈 기간 `list`** (`BACKFILL`) | `https://apis.data.go.kr/1051000/recruitment/list` | 미호출 (`pbancBgngYmd=2010-01-01`, `pbancEndYmd=2010-01-31` 예정) | **미실행** | 선행 오류 후 추가 호출 중단 |

- 요청 URL 전문, 인증키, DB 접속 정보, 원본 예외/본문은 로그와 문서에 남기지 않았다.

---

## 2. 영속 PostgreSQL `api_budget` / `api_attempt` 증거

실행 직후 `gongchae-bell-dev-postgres-1`의 `gongchae` DB 조회 결과:

- **`flyway_schema_history`**: `1` (`catalog and ingestion`), `2` (`collection freshness index`), `3` (`collector runtime records`) 모두 `success = t`
- **`api_budget`**:
  - `day = 2026-11-15`, `count = 1`
- **`api_attempt`**:
  - `id = 1`, `day = 2026-11-15`, `kind = REGULAR`, `attempt_no = 1`
  - `started_at = 2026-11-15 05:35:22.114541+00` (`14:35:22.114541 KST`)
  - `finished_at = 2026-11-15 05:35:22.597067+00` (`14:35:22.597067 KST`)
  - `outcome = HTTP_STATUS`

---

## 3. 원인 단서 및 관측 포인트 (값 비노출 점검)

1. **인증키 형식 불일치 가능성 (이중 인코딩)**:
   - `.env`의 `COLLECTOR_SERVICE_KEY` 값을 출력하지 않고 구조 속성만 점검한 결과: 길이 `96`, `%` 문자 포함(`hasPercent=True`), `+`/`/`/`=` 및 앞뒤 공백 없음(`hasPlus=False`, `hasSlash=False`, `hasEq=False`, `hasTrimDiff=False`).
   - `%`가 포함된 형태는 공공데이터포털의 **일반 인증키(Decoding)**가 아니라 이미 URL 인코딩된 **Encoding 인증키**일 가능성이 높다.
   - `docs/ops/collector.md` 계약대로 `RecruitmentClient.get()`이 `URLEncoder.encode(serviceKey, UTF_8)`를 한 번 수행하므로, 이미 인코딩된 키가 들어가면 `%`가 `%25`로 이중 인코딩되어 포털이 거부(`HTTP_STATUS`, 예: 401/403/400)할 수 있다.
2. **운영 진단 관측성 한계 확인**:
   - `RecruitmentClient`가 비밀 보호를 위해 `4xx`/`3xx`를 모두 단일 고정 코드 `HTTP_STATUS`로만 축약하므로, `api_attempt`와 로그만으로는 `400` vs `401` vs `403` vs `404` 중 무엇이었는지와 포털 오류 메시지 종류를 구분할 수 없다.

---

## 4. 실제 봉투 · 배열 · 빈 응답 형식 검증 상태 및 출처 구분

- **공식 `apis.data.go.kr` 실응답 형식**: **미검증** (`HTTP_STATUS` 오류로 HTTP 200 JSON 본문을 수신하지 못함)
  - `list` 최상위 봉투 구조(`"data"` 래퍼 유무) 및 필드 구성: **미확인**
  - `detail` `result` / `steps` / `files` 구조 및 빈 배열·래퍼 형태: **미확인**
  - 빈 기간 조회의 `resultCode` / `totalCount=0` / `result=[]` 형식: **미확인**
- **기존 `fixtures/` 파일과의 provenance 구분**:
  - `fixtures/alio-public-list-sample.json`, `fixtures/alio-public-list-quality-sample.json`은 인증키 없는 공개 AJAX 경로(`opendata.alio.go.kr/new/odaApiMng/recrutInquiryAjaxList.do`)에서 2026-11-14에 취득한 응답이며, 공식 `apis.data.go.kr/1051000/recruitment` 실응답이 아니다.
  - 이번 시도에서는 유효한 공식 응답 본문을 수신하지 못했으므로 `fixtures/`에 새 JSON 본문을 추가하지 않았다(`fixtures/README.md`에만 검증 시도가 미확보로 끝난 사실을 기록).

---

## 5. 후속 필요 조치 (검수·사용자 확인)

1. `.env`의 `COLLECTOR_SERVICE_KEY`가 포털 **Decoding(일반 인증키)** 값인지 **Encoding** 값인지 사용자가 확인한다(`docs/ops/collector.md`는 Decoding 값 입력을 전제).
2. 키 교정(또는 포털 반영 대기 확인) 후 재시도를 승인받으면 같은 `.tools/CaptureOfficialFixtures` 경로로 `list` 1회 + `detail` 1회 + 빈 기간 1회를 재확인한다.
3. `#4` 운영 관측 개선 검토: 비밀값·URL·원본 본문을 노출하지 않으면서도 HTTP 상태 번호 범위/분류(예: `HTTP_401`, `HTTP_403`, `HTTP_404`, `HTTP_400`, `HTTP_3XX`)를 `api_attempt` 또는 로그 고정 코드에 남길지 검토할 만하다.
