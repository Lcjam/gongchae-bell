# 공식 `list` 날짜 조회 계약 검증 및 최소 수정안 (`agy`)

- **검증 일시(KST)**: 2026-11-15 20:11 (최초 3년 부분 적재 `22,733`건·`collect_run` `76`건 보존 상태)
- **실행 경로**: `C:/Users/lcj/orca/workspaces/my-project/m1-integration` (`fc752ad7e78c776ac14448c42662dd1b8fffa1cd`)의 `.gitignore` 대상 `.tools/ProbeQueryContract.java`에서 기존 `RecruitmentClient` + `ApiBudget`만 호출 (`src/`·테스트·DB 기존 행·`.env` 미변경, 상세 API 미호출, 응답 본문은 `.gitignore`된 `.tools/captured/`에만 비밀값 제거 검증 후 보관)

---

## 1. 공식 명세 vs 실측 프로브 비교

### 1-1. 공식 문서(`data.go.kr/data/15125273/openapi.do`) 명세
- root·Claude·Sol이 확인한 공식 요청변수 표:
  - `pbancBgngYmd`, `pbancEndYmd` **둘 다 옵션(Optional)** 파라미터로 기재되어 있음.
  - 명세 설명상 둘 다 **채용공시시작일 기준 조회 시작일/종료일**로 설명되어 있음.

### 1-2. 실측 결과 비교 (`initial-001` vs Probe 1 vs Probe 2)

| 구분 | 파라미터(키·전체 URL 비노출) | `resultCode` / `totalCount` | 페이지 건수 | 월 걸침 건수 | `pbancBgngYmd` 최소~최대 | `pbancEndYmd` 최소~최대 | `ongoingYn` 분포 | 비고 |
|---|---|---|---:|---:|---|---|---|---|
| **기존 월 분할 (`initial-001`, 37회)** | 월별 `pbancBgngYmd=월초`, `pbancEndYmd=월말` (`2023-11-15..2026-11-15`) | `200` / 합계 `22,733` | 전체 `22,733` | **`0`** | `20231115` ~ `20261111` | `20231117` ~ `20261115` | `{N: 22732, Y: 1}` | 모든 월에서 `max(end_date) <= 월말` |
| **Probe 1 (`2023-11-15..2026-11-15`, p1)** | `pbancBgngYmd=2023-11-15`, `pbancEndYmd=2026-11-15`, `numOfRows=1000`, `pageNo=1` | `200` / **`36,277`** | `1,000` | **`371`** (`sn=306986` `20261030..20261103` 등) | `20250722` ~ `20261111` (`>20261115`: `0`) | `20260130` ~ `20261115` (`>20261115`: **`0`**) | `{N: 999, Y: 1}` | 월 분할 대비 `+13,544` (`~37.3%` 누락 확인) |
| **Probe 2 (`2023-11-15` 하한만, p1)** | `pbancBgngYmd=2023-11-15`, **`pbancEndYmd` 생략**, `numOfRows=1000`, `pageNo=1` | `200` / **`36,772`** | `1,000` | **`374`** (`sn=306986` 등) | `20250722` ~ `20270118` (`>20261115`: **`4`**) | `20260622` ~ `20270122` (`>20261115`: **`381`**) | `{N: 618, Y: 382}` | Probe 1 대비 `+495` (`end > 2026-11-15` 포함) |

- **Probe 1 vs Probe 2 페이지 1 SN 대조**:
  - 교집합 `619`건(Probe 1에서 `sn >= min(Probe 2)`인 `619`건 전부가 Probe 2에 포함됨).
  - Probe 2에만 있는 `381`건 **전부(`381/381`)** `ongoingYn='Y'`이고 `pbancEndYmd > 20261115`였다.
  - Probe 2에서 `pbancBgngYmd > 20261115`인 미래 시작 예정 공고 `4`건 확인: `sn={306937, 306940}`(`20261116..20261120`), `sn={306938, 306939}`(`20270118..20270122`).
- **응답 스키마(Schema) 확인**:
  - 최상위 키(4개): `['resultCode', 'resultMsg', 'totalCount', 'result']` (`item` 래퍼 없음, `#3` `ListResponseParser` 계약과 일치).
  - `result[]` 원소 키(30개): `['recrutPblntSn', 'pblntInstCd', 'pbadmsStdInstCd', 'instNm', 'ncsCdLst', 'ncsCdNmLst', 'hireTypeLst', 'hireTypeNmLst', 'workRgnLst', 'workRgnNmLst', 'recrutSe', 'recrutSeNm', 'prefCondCn', 'recrutNope', 'pbancBgngYmd', 'pbancEndYmd', 'recrutPbancTtl', 'srcUrl', 'replmprYn', 'aplyQlfcCn', 'disqlfcRsn', 'scrnprcdrMthdExpln', 'prefCn', 'acbgCondLst', 'acbgCondNmLst', 'nonatchRsn', 'ongoingYn', 'decimalDay', 'files', 'steps']`.
- **보관 파일 비밀값 검사**: `.tools/captured/probe1-full-range-p1.json` (`2,386,807` bytes), `probe2-lower-only-p1.json` (`2,329,935` bytes) 모두 `COLLECTOR_SERVICE_KEY` / `DB_PASSWORD` 문자열 `0`건 확인.

---

## 2. 영속 `api_budget` / `api_attempt` 증거 (최대 3회 허용 중 2회 사용)

- **DB 누적 현황 (`gongchae-bell-dev-postgres-1`, `gongchae`)**:
  - `api_budget(2026-11-15)`: 실행 전 `41` → Probe 1 후 `42` → Probe 2 후 **`43`** (`+2`회; 허용 3회 중 1회 미사용 유보)
  - 신규 `api_attempt` 행:
    - `id=42`: `kind='BACKFILL'`, `attempt_no=1`, `started_at=2026-11-15 11:11:34.305977+00`, `finished_at=2026-11-15 11:11:36.181094+00`, `outcome='SUCCESS'`
    - `id=43`: `kind='BACKFILL'`, `attempt_no=1`, `started_at=2026-11-15 11:11:36.337618+00`, `finished_at=2026-11-15 11:11:37.764696+00`, `outcome='SUCCESS'`
  - 기존 부분 적재 데이터 보존 확인: `posting=22,733`, `collect_run=76` 변동 없음.

---

## 3. 관측과 추정의 구분 (날짜 파라미터 쌍의 실제 동작)

1. **관측된 사실**:
   - **공식 문서와 실제 동작 불일치**: 공식 문서 표는 `pbancEndYmd`를 "채용공시시작일 기준 조회 종료일"이라고 적었으나, 실제 API는 `pbancEndYmd`를 넘기면 **공고 마감일(`posting.pbancEndYmd`) 상한**으로 필터링했다(Probe 1에서 `END_GT_20261115=0`·`Y=1`인 반면, `pbancEndYmd`를 생략한 Probe 2에서 즉시 `pbancEndYmd > 20261115`인 `381`건·`totalCount +495`건이 포함됨. `initial-001` 37개 월 구간에서도 전 월 `max(end_date) <= 월말`, 월 걸침 `0`건).
   - **`pbancEndYmd` 생략 가능(필수 아님)**: `pbancBgngYmd=2023-11-15`만 넘긴 Probe 2가 `HTTP 200`, `resultCode=200`, `totalCount=36,772`로 정상 응답했으며, 오늘(`2026-11-15`) 이후 시작 예정 공고(`2026-11-16`, `2027-01-18`)까지 반환했다.
   - **`pbancBgngYmd` 하한**: 37개 월 분할 전 구간(`22,733`건)과 Probe 1·2 1페이지(`min=20250722`)에서 `pbancBgngYmd < 요청 시작일`인 행이 `0`건이었고, 과거 빈 구간(`2010-01-01..2010-01-31`)은 `totalCount=0`을 반환했다.
2. **추정(해석)**:
   - 업스트림 `recruitment/list`는 `posting.pbancBgngYmd >= :pbancBgngYmd` AND (전달 시) `posting.pbancEndYmd <= :pbancEndYmd` 조건으로 동작하는 것으로 추정된다(각 파라미터가 시작일 하한과 마감일 상한을 각각 제한하며, 전체 기간 `36,772`건은 뒷페이지 끝까지 조회하지 않아 `2023-11-15` 직전 공고 포함 여부의 직접 전수 대조는 미실시 — 다만 37개 월 구간 실측으로 하한은 이미 검증됨).

---

## 4. 가장 작은 완전 수정안 제안 (설계 제안, 코드 미수정)

월 분할은 `pbancEndYmd=월말` 필터 때문에 **월 경계 공고 약 37%(`~1.35만`건)를 구조적으로 누락**하고, `pbancEndYmd=period.to()` 단일 구간 조회조차 **마감일이 `period.to()` 이후인 진행 공고 약 `495`건을 누락**시킨다(정기 수집 시 `from_backfill=false`가 되어 불필요한 상세 sweep 큐 유입). 가장 작은 완전 수정안은 다음 4가지다.

### (1) 월 분할 폐기 (`BackfillCollector`, `BackfillRange`)
- `BackfillCollector.collect(...)`에서 `period.months()` 반복을 제거하고, `period`(`2023-11-15..2026-11-15`) 단일 기간을 `pageNo = 1..` (`numOfRows = 1000`)로 순차 페이지네이션한다(`36,772`건 기준 약 `37` HTTP 호출로 기존 월 분할과 호출량 동일, 일 잔여 가용 예산 내).
- `totalCount == 0`인 빈 기간도 1페이지에서 `received == expected (0 == 0)`로 즉시 종료되므로 동일 계약으로 처리된다.

### (2) `pbancEndYmd` 생략 조회 + 로컬 시작일 상한 필터 (`RecruitmentClient`, `ParsedPage` / `PostingIngestor`)
- `RecruitmentClient.backfillList(LocalDate from, int page)`로 변경해 `pbancBgngYmd=from`만 전달하고 `pbancEndYmd`를 생략한다(진행 중이며 마감이 `period.to()` 이후인 ~495건 포함 확보).
- **완결성 검증과 로컬 상한 필터 분리(핵심)**:
  - Probe 2에서 확인됐듯 업스트림 `totalCount`(`36,772`)에는 `bgng_date > period.to()`인 미래 예정 공고(`4`건 이상)도 포함된다.
  - 따라서 `BackfillCollector.validate()`와 페이지 체크포인트(`total_count`, `received`, `received_sns`)는 **업스트림 원본 페이지 전체 행** 기준으로 계산해 페이지 크기 일치(`1000` / 마지막 페이지 잔여분)·중복 SN 검사를 통과시키고, 하한 검증만 `!row.begin().isBefore(period.from())`으로 유지한다.
  - DB 반영(`ingestor.upsertPage(...)`) 직전에는 `parsed.startingOnOrBefore(period.to())` 등으로 **`bgng_date <= period.to()`인 행만 남겨 업서트**한다(`PostingIngestor.write`는 이미 `rows.isEmpty()`일 때 `0`건 반환으로 안전하게 동작함). 이렇게 하면 `period.to()` 이후 시작 예정 공고는 최초 적재에 들어가지 않아 이후 `REGULAR`에서 정상 신규 공고(`from_backfill=false`)로 다룰 수 있고, 반대로 `bgng_date <= period.to()`인 진행 중 공고는 `from_backfill=true`로 안전하게 적재된다.

### (3) 기존 37개월 `COMPLETE` 체크포인트 재사용 방지 (`CollectionRuns` / `BackfillCollector`)
- 현재 DB(`collect_run` `id=1..76`, `initial-001`·`initial-002`)의 `result='COMPLETE'` 행들은 `pbancEndYmd=월말` 불완전 범위의 스냅숏이므로, 수정된 백필이 **절대로 재사용(skip)하면 안 된다**.
  - 특히 전체 기간(`2023-11-15..2026-11-15`)과 달리 단일 월(`2024-01-01..2024-01-31`)을 재실행하면 기존 `completedPage(from, to, page)`(`WHERE kind='BACKFILL' AND range_from=:f AND range_to=:t AND page_no=:p AND result='COMPLETE'`)가 옛 불완전 페이지(`752`건)를 그대로 재사용할 위험이 있다.
  - `22,733`건과 기존 `collect_run` `1..76`행은 부분 적재 감사 이력으로 DB에 보존(`DELETE`/`DROP` 불필요)하고, 새 방식의 체크포인트만 분리 매칭해야 한다. (`posting` 재적재는 `#3 PostingIngestor` 업서트가 멱등이므로 기존 `22,733`건 위에서 그대로 덮어쓰고 누락된 ~`1.4만`건을 추가 적재한다.)

### (4) M2 전 `V4` 마이그레이션 예약 필요 여부
두 선택지 중 하나를 조율로 결정하면 되며, 스키마 무변경 안(옵션 A)을 우선 검토할 수 있다:

- **옵션 A (`V4` 불필요 — `V3` 유지, M2가 `V4` 사용)**:
  - `collect_run.slot` (`varchar(100)`)은 사용자 슬롯(`[A-Za-z0-9_-]{1,40}`) 외에 앱이 prefix를 부여해 저장한다.
  - 수정 후 페이지 체크포인트 슬롯 형식을 예: `bgng/<slot>/<page>` (또는 `v2/<slot>/<page>`)로 바꾸고 `CollectionRuns.completedPage` 조건에 `AND slot LIKE 'bgng/%'`를 추가한다(기존 `initial-001/<yyyy-mm-dd>/<page>`와 겹치지 않음).
  - 장점: Flyway `V1~V3`를 건드리지 않아 기존 배포/테스트 DB와 즉시 호환되며 M2(`#6`/`#7`)의 `V4` 계획을 변경할 필요가 없다.
- **옵션 B (`V4` 선점 — `query_mode` 컬럼 추가, M2는 `V5`로 이동)**:
  - `V4__collect_run_query_mode.sql`로 `ALTER TABLE collect_run ADD COLUMN query_mode varchar(32);`와 해당 부분 인덱스를 추가하고, 신규 백필은 `query_mode='BGNG_FROM_ONLY'`만 조회·기록한다(기존 76행은 `NULL`로 남아 skip 대상에서 원천 제외).
  - 장점: 슬롯 문자열 규칙에 의존하지 않는 명시적 스키마 분리. 단, M2 전 `V4` 번호를 소비하므로 M2 설계(`#6`/`#7` 첫 마이그레이션)를 **`V5`**로 예약 변경해야 한다.
