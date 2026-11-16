# `START_SCAN` 최초 3년 적재 실제 재검증 결과 (`agy`)

- **검수 대상 커밋 (PR #11, draft·미병합 유지 상태에서 실행)**:
  - `issue-4-collector-runtime` (`feat/#4-collector-runtime`) `007c1dd464012446f74dfed4c79c65e00b35163b` (코드 `2d9e263939f4ce9f27506e775a674d741cd17571`)
  - 소스·테스트·설정·브랜치·PR 무변경 상태에서 `gradlew.bat --no-daemon clean bootJar` 실행(`check` 미반복)으로 `007c1dd` 직접 빌드 산출물 확보
  - 실행 JAR: `issue-4-collector-runtime/build/libs/gongchae-bell.jar` (`60,232,977` bytes, SHA-256 `FA7B3EFA9A89099498512FAA1DA13956C7FD4A9A5ABAD69088A3AB5E239A750C`)
  - JDK: `C:/Users/lcj/orca/projects/my-project/.tools/jdk-21.0.13+9`
- **관측 시각 (KST)**:
  - 사전 DB 확인 및 `pg_dump` 백업: `2026-11-15 21:18 ~ 21:19`
  - `initial-003` (`START_SCAN` 본 실행): `2026-11-15 21:20:46.505 ~ 21:25:36.503` (`290.0s`)
  - `initial-004` (`--collector.backfill-restart-pages=false` 0-HTTP 재실행): `2026-11-15 21:26:19.764 ~ 21:26:31.094` (`11.33s`)
- **대상 기간 (고정)**: `2023-11-15` ~ `2026-11-15`
- **사전 백업 (ignored 경로, 비밀번호·데이터 비출력)**:
  - `C:/Users/lcj/orca/workspaces/my-project/m1-integration/.tools/backups/pre-start-scan-20261115.dump` (`11,971,246` bytes, `pg_dump -Fc`, `.gitignore:5:.tools/` 제외 확인, `pg_restore --list` `TOC Entries: 51` 정상 확인)

---

## 1. 실행 전 환경·DB 재확인 및 Flyway V4 적용

1. **사전 DB 재확인 (`gongchae-bell-dev-postgres-1`, `gongchae`, `PostgreSQL 17.12`)**:
   - 실행 전 `java%` 프로세스 `0`건 확인.
   - `flyway_schema_history`: `V1` (`catalog and ingestion`), `V2` (`collection freshness index`), `V3` (`collector runtime records`) 모두 `success=t`.
   - 테이블 건수: `posting` `22,733`건(전원 `from_backfill=t, detail_status=NONE`, `CLOSED=22,732`, `OPEN=1`), `institution` `352`, `posting_code` `122,039`, `code_name` `56`, `posting_change` `0`, `collect_run` `76`건(`BACKFILL/COMPLETE`, `command/initial-001` `id=1`, `command/initial-002` `id=76`, `strategy IS NULL`), `api_budget(2026-11-15)` `43`, `api_attempt` `43`건(`BACKFILL SUCCESS` `40`, `DETAIL SUCCESS` `1`, `REGULAR HTTP_STATUS` `1`, `REGULAR SUCCESS` `1`).
   - `sn=307032`: 실행 전 DB `0`건 (`initial-001` 월별 조회에서 월 걸침 및 `end > 2026-11-15`로 제외되어 있었으며, `detail` 표본은 `fixtures/`·테스트에만 존재).
2. **Flyway V4 additive 마이그레이션 적용 확인 (`initial-003` 기동 시)**:
   - `21:20:52.907 KST`: `Migrating schema "public" to version "4 - backfill start scan"` → `0.075s` 완료 (`flyway_schema_history` `V1~V4` 전원 `success=t`).
   - 기존 `collect_run` `id=1..76` (`strategy IS NULL`, `result='COMPLETE'`) `76`건과 `api_attempt` `id=1..43` `43`건 전량 무변경 보존.

---

## 2. 실행 요약 (`initial-003` vs 재실행 `initial-004`)

| 구분 | `initial-003` (`START_SCAN` 본 실행) | `initial-004` (재실행 0-HTTP 검증) |
|---|---|---|
| 실행 옵션 (`non-web`) | `--spring.main.web-application-type=none --collector.enabled=false --collector.backfill=true --collector.backfill-slot=initial-003 --collector.backfill-from=2023-11-15 --collector.backfill-to=2026-11-15` | `--collector.backfill-slot=initial-004` + `--collector.backfill-restart-pages=false` |
| 종료코드 / 결과 로그 | `0` / `BACKFILL_COMPLETE` | `0` / `BACKFILL_COMPLETE` |
| 소요 시간 (프로세스 / `collect_run`) | `290.0s` / `278.10s` (`12:20:57.79` ~ `12:25:35.90 UTC`) | `11.33s` / `0.13s` (`12:26:30.39` ~ `12:26:30.52 UTC`) |
| `command` 행 (`collect_run`) | `id=77`, `command/initial-003`, `COMPLETE`, `strategy=START_SCAN`, `scan_generation=NULL` | `id=116`, `command/initial-004`, `COMPLETE`, `strategy=START_SCAN`, `scan_generation=NULL` |
| `scan` 행 (`collect_run`) | `id=78`, `initial-003/scan`, `COMPLETE`, `strategy=START_SCAN`, `scan_generation=78` (`received_sns` `36,772`, `applied_sns` `36,768`) | `0`건 (`initial-004/%` 0건, `id=78` 완료 scan 스냅숏 재사용) |
| 하위 `page` 행 | `37`건 (`id=79..115`, `page_no=1..37`, 전원 `COMPLETE`, `scan_generation=78`) | `0`건 |
| `raw` (`total / received / unique`) | `36,772 / 36,772 / 36,772` | `36,772 / 36,772 / 36,772` |
| `applied_count` | `36,768` | `36,768` |
| `inserted / changed / closed` | `14,035 / 0 / 0` (37페이지 합계) | 변동 `0` (`posting_change=0`) |
| `api_budget(2026-11-15)` 전후 | `43` → `80` (**+37 HTTP**) | `80` → `80` (**+0 HTTP**) |
| `api_attempt` 추가 | `id=44..80` `37`건 (전원 `BACKFILL / attempt_no=1 / SUCCESS`) | `0`건 추가 |

- **예산 및 비대상 경계 확인**:
  - `warningLimit=500` 미도달, 비정기 가용 상한 `828`(`stopLimit=900` 중 정기 몫 `72` 보존) 중 `80` 사용(`748` 잔여).
  - `collect_run` 전체 `116`건(`legacy 76 + initial-003 39 + initial-004 1`) 모두 `kind='BACKFILL'`이며, `REGULAR`·`DETAIL`·`DIGEST`·`DEADLINE` 추가 실행 `0`건.
  - `initial-004` 실행 후에도 `posting` `36,768`, `posting_change` `0`, `api_budget` `80`으로 완전한 0-HTTP 및 DB 멱등을 확인했다.

### 2-1. `initial-003` 페이지별 내역 (`page_no=1..37`, `total_count=36,772`, `scan_generation=78`)

| `page_no` | `received` | `unique_count` | `applied_count` | `inserted` | `changed` | `closed` | 처리(s) |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 | 1,000 | 1,000 | **996** | 716 | 0 | 0 | 11.73 |
| 2 | 1,000 | 1,000 | 1,000 | 368 | 0 | 0 | 8.21 |
| 3 | 1,000 | 1,000 | 1,000 | 346 | 0 | 0 | 6.85 |
| 4 | 1,000 | 1,000 | 1,000 | 384 | 0 | 0 | 6.85 |
| 5 | 1,000 | 1,000 | 1,000 | 329 | 0 | 0 | 7.26 |
| 6 | 1,000 | 1,000 | 1,000 | 419 | 0 | 0 | 7.77 |
| 7 | 1,000 | 1,000 | 1,000 | 492 | 0 | 0 | 8.42 |
| 8 | 1,000 | 1,000 | 1,000 | 413 | 0 | 0 | 8.20 |
| 9 | 1,000 | 1,000 | 1,000 | 376 | 0 | 0 | 7.35 |
| 10 | 1,000 | 1,000 | 1,000 | 396 | 0 | 0 | 6.52 |
| 11 | 1,000 | 1,000 | 1,000 | 262 | 0 | 0 | 5.85 |
| 12 | 1,000 | 1,000 | 1,000 | 343 | 0 | 0 | 11.77 |
| 13 | 1,000 | 1,000 | 1,000 | 472 | 0 | 0 | 8.19 |
| 14 | 1,000 | 1,000 | 1,000 | 429 | 0 | 0 | 7.46 |
| 15 | 1,000 | 1,000 | 1,000 | 263 | 0 | 0 | 6.79 |
| 16 | 1,000 | 1,000 | 1,000 | 292 | 0 | 0 | 6.47 |
| 17 | 1,000 | 1,000 | 1,000 | 312 | 0 | 0 | 7.14 |
| 18 | 1,000 | 1,000 | 1,000 | 440 | 0 | 0 | 7.63 |
| 19 | 1,000 | 1,000 | 1,000 | 393 | 0 | 0 | 7.77 |
| 20 | 1,000 | 1,000 | 1,000 | 407 | 0 | 0 | 8.84 |
| 21 | 1,000 | 1,000 | 1,000 | 404 | 0 | 0 | 7.12 |
| 22 | 1,000 | 1,000 | 1,000 | 499 | 0 | 0 | 9.21 |
| 23 | 1,000 | 1,000 | 1,000 | 326 | 0 | 0 | 6.56 |
| 24 | 1,000 | 1,000 | 1,000 | 336 | 0 | 0 | 6.14 |
| 25 | 1,000 | 1,000 | 1,000 | 349 | 0 | 0 | 6.33 |
| 26 | 1,000 | 1,000 | 1,000 | 337 | 0 | 0 | 7.67 |
| 27 | 1,000 | 1,000 | 1,000 | 428 | 0 | 0 | 6.97 |
| 28 | 1,000 | 1,000 | 1,000 | 342 | 0 | 0 | 6.76 |
| 29 | 1,000 | 1,000 | 1,000 | 359 | 0 | 0 | 6.44 |
| 30 | 1,000 | 1,000 | 1,000 | 331 | 0 | 0 | 6.26 |
| 31 | 1,000 | 1,000 | 1,000 | 367 | 0 | 0 | 6.90 |
| 32 | 1,000 | 1,000 | 1,000 | 366 | 0 | 0 | 9.00 |
| 33 | 1,000 | 1,000 | 1,000 | 401 | 0 | 0 | 7.05 |
| 34 | 1,000 | 1,000 | 1,000 | 380 | 0 | 0 | 7.59 |
| 35 | 1,000 | 1,000 | 1,000 | 403 | 0 | 0 | 9.72 |
| 36 | 1,000 | 1,000 | 1,000 | 248 | 0 | 0 | 5.23 |
| 37 | **772** | 772 | 772 | 307 | 0 | 0 | 5.47 |
| **합계** | **36,772** | **36,772** | **36,768** | **14,035** | **0** | **0** | **-** |

---

## 3. DB 적재 결과, raw vs DB 구분, 및 `sn=307032` 확인

- **테이블 총건수 (`initial-003`·`initial-004` 후)**:
  - `posting`: **`36,768`**건 (`22,733` + `14,035`)
  - `institution`: **`353`**개 (+1)
  - `posting_code`: **`205,711`**건 (+83,672)
  - `code_name`: **`56`**건 (전원 사용)
  - `posting_change`: **`0`**건 (기존 `22,733`건 내용 변동 `0`건)
- **raw vs DB 선별 구분**:
  - **raw 수신 (`received_sns`)**: `36,772`건 (하한 `bgng_date >= 2023-11-15` 전량 통과).
  - **미래 시작 제외 (`received_sns EXCEPT applied_sns`, `scan` `id=78` 대조)**: `1`페이지의 **`4`건**(`sn={306937, 306938, 306939, 306940}`)이 `bgng_date > 2026-11-15`로 DB 반영에서 제외됐다. DB `posting WHERE bgng_date > '2026-11-15'`는 `0`건, `< '2023-11-15'`도 `0`건이다.
- **월 걸침 / 목표일 뒤 마감 / 상태 (DB 기준)**:
  - `bgng_date` 범위: `2023-11-15` ~ `2026-11-14`, `end_date` 범위: `2023-11-17` ~ `2027-08-11`, 역전(`bgng_date > end_date`) `0`건.
  - **월 걸침**(`date_trunc('month', bgng_date) <> date_trunc('month', end_date)`): **`13,693`**건 (`initial-001` `0`건 → `+13,693`건, 전체의 약 37.2%).
  - **목표 종료일(`2026-11-15`) 뒤 마감**(`end_date > '2026-11-15'`): **`491`**건(전원 `ongoing_yn='Y'`, `status='OPEN'`).
  - 상태 분포: `OPEN` **`492`**건(`ongoing_yn='Y'`, `2026-11-15` 마감 `1`건 + 이후 마감 `491`건), `CLOSED` **`36,276`**건(`ongoing_yn='N'`), `CHECK` `0`건.
- **`from_backfill` 및 `sn=307032` 관측**:
  - 전체 `36,768`건 모두 `from_backfill=true`, `detail_status='NONE'`, `detail_collected_at IS NULL`, `steps='[]'`, `files='[]'`.
  - `sn=307032`는 `initial-003` 실행 전 DB에 존재하지 않았으며(당시 `detail_status<>'NONE'` `0`건), 이번 `START_SCAN`으로 신규 적재되었다(`bgng_date=2026-11-13`, `end_date=2026-11-20`, `status='OPEN'`, `ongoing_yn='Y'`, `from_backfill=true`, `detail_status='NONE'`). 기존 `from_backfill`/`detail_status` 덮어쓰기나 손실은 없다.

---

## 4. 연도별·코드·품질·오래된 기간 표본 검수 (원문·개인정보 미기재)

### 4-1. 연도별 적재·월 걸침·품질 분포 (`bgng_date` 기준)

| 연도 | 공고 수 | 월 걸침 | `broken_text` | `src_url_generic` | SN 범위 |
|---|---:|---:|---:|---:|---|
| 2023 (`11-15~`) | 1,201 | 546 | 53 | 537 | `263526..285430` |
| 2024 | 12,382 | 4,509 | 424 | 5,559 | `263777..305501` |
| 2025 | 12,454 | 4,645 | 541 | 5,907 | `279024..306485` |
| 2026 (`~11-14`) | 10,731 | 3,993 | 522 | 5,167 | `293037..307032` |
| **합계** | **36,768** | **13,693** | **1,540** | **17,170** | `263526..307032` |

### 4-2. 필수 필드·참조 무결성·고아 코드 검사

- 필수 필드 누락·공백(`title`, `inst_code`, `bgng_date`, `end_date`, `src_url`, `ongoing_yn`, `recrut_se`, `content_hash`) 및 날짜 역전: **모두 `0`건**.
- 고아 `posting.inst_code` (`institution` 미존재): **`0`건** (미사용 `institution` `0`건).
- 고아 `posting_code.sn` (`posting` 미존재): **`0`건**.
- 미매핑·고아 `posting_code` ↔ `code_name`: **`0`건** (미사용 `code_name` `0`건).
- 코드 유형별 매핑 (`posting_code` 행 수 / 보유 고유 공고 수 / 고유 코드 수):
  - `NCS`: `60,982`행 (`36,768`공고 전부, `25`종)
  - `REGION`: `56,834`행 (`36,768`공고 전부, `19`종) — `R3027(전남광주)` `479`건, `R3030(해외)` `171`건 포함
  - `HIRE`: `39,798`행 (`36,768`공고 전부, `5`종)
  - `EDU`: `48,097`행 (`36,768`공고 전부, `7`종)
  - `recrut_se`: `R2030` `22,738`건, `R2010` `11,713`건, `R2020` `2,303`건, `R2040` `14`건

### 4-3. 오래된 기간 및 품질 경계 표본 (고정 조건 SN만 기록)

- **가장 오래된 반월 구간(`2023-11-15..2023-11-30`)**: 총 **`488`**건 (기존 `initial-001` `159`건 + 월 걸침 복구 **`329`**건).
  - 최구 시작일 5건(`ORDER BY bgng_date ASC, sn ASC`): `sn={263526, 263796, 263797, 263798, 263799}` (`2023-11-15` 시작, `CLOSED`).
  - 최구 월 걸침 5건: `sn=263837` (`2023-11-15..2024-11-14`), `sn={263900, 263902, 263903}` (`2023-11-16..2023-12-01`), `sn=263904` (`2023-11-16..2023-12-08`).
- **최신 시작일 5건(`ORDER BY bgng_date DESC, sn DESC`)**: `sn=307028` (`2026-11-14..2026-11-19`, `OPEN`), `sn={307032, 307031, 307030, 307029}` (`2026-11-13..2026-11-20/30`, `OPEN`).
- **품질 특이 조건 앞 3건**:
  - `broken_text=true` (`1,540`건): `sn={263866, 263876, 263902}`
  - `src_url_generic=true` (`17,170`건): `sn={263526, 263796, 263798}`
  - `recrut_nope IS NULL` (`10`건): `sn={270126, 282630, 283211}`
  - `recrut_nope = 0` (`31`건): `sn={268059, 271555, 272393}`
  - `aply_qlfc` 빈값 (`1`건): `sn={292106}` (`pref`/`scrnprcdr` 빈값 `0`건)
  - `REGION=R3027` (`479`건): `sn={302875, 302898, 302900}`
  - `REGION=R3030` (`171`건): `sn={263909, 264504, 264905}`
  - `recrut_se=R2040` (`14`건): `sn={266125, 267722, 269923}`

---

## 5. 로그 비밀값 검사 및 종료 상태

- **로그 위치 (`.gitignore` 제외)**:
  - `C:/Users/lcj/orca/workspaces/my-project/m1-integration/.tools/backfill-initial-003.log` (`44`줄, `5,141` bytes)
  - `C:/Users/lcj/orca/workspaces/my-project/m1-integration/.tools/backfill-initial-004.log` (`43`줄, `4,910` bytes)
- **비밀값·자격증명 변형 검사**:
  - `COLLECTOR_SERVICE_KEY` 및 `DB_PASSWORD` (원문·디코딩·인코딩·재인코딩 4종 변형)와 `serviceKey=`, `password=`, `username=` 리터럴: 두 로그 모두 **`0`건** 확인 (`initial-003` 로그의 `DB_USER` 문자열 일치는 DB/앱명과 동일한 단어가 Spring 로거 접두어·로컬 JDBC DB명에 포함된 경우뿐). `WARN`/`ERROR` `0`건.
- **종료 프로세스·워크트리 상태**:
  - 완료 후 `Win32_Process` `java%` 프로세스 `0`건 확인.
  - `issue-4-collector-runtime`, `m1-integration` 및 저장소 소스·테스트·설정·브랜치 변경 없음.
