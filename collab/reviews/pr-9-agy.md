# PR #9 리뷰 (이슈 #3: 공고 파싱·완결성·변경 관측·상태 판정) · agy

- **대상**: [PR #9](https://github.com/Lcjam/gongchae-bell/pull/9) (`feat/#3-ingestion-core`, head `925aa18e4f5bd6f426b540ac20deb070328239a5`, 초안 PR)
- **검토 대상 워크트리**: `C:/Users/lcj/orca/workspaces/my-project/issue-3-ingestion-core` (읽기 전용 검토)
- **참조 문서**: `CLAUDE.md`, `docs/prd.md`(FR-C2~C4, FR-C7~C8), `docs/design/erd.md`, `docs/design/routes.md`, `collab/2026-11-15-review/03-codex-ingestion-review.md`, `collab/2026-11-15-implementation/claude-ingestion-contract.md`(v2), `claude-ingestion-result.md`, `claude-ingestion-review-response.md`
- **판정**: **Approve (머지 가능 — 검수 03 지적 5건 해결 확인; 경미한 보완 권장 2건 포함)**

---

## 1. 검수 03 지적 사항 반영 확인

| # | 검수 지적 (`03-codex-ingestion-review.md`) | 실제 코드·문서·테스트 확인 결과 | 근거 |
|---|---|---|---|
| 1 | `upsertPage`가 재파싱 없이 `PageResult(Counts, SortedSet<Long>)` 반환 | **해결.** `PageResult` 레코드가 `Counts`와 수정 불가 `SortedSet<Long>`(`Collections.unmodifiableSortedSet(new TreeSet<>(...))`)를 반환한다. 이미 DB에 존재하던 공고(`inserted=0`)도 검증·반영된 행이면 `sns`에 포함되며 `sns.size() == counts.received()`를 만족한다. | [`PageResult.java`](../../src/main/java/kr/gongchae/bell/ingestion/PageResult.java#L1-L10), [`PostingIngestor.java`](../../src/main/java/kr/gongchae/bell/ingestion/PostingIngestor.java#L97-L104), [`PostingIngestorIntegrationTest.java`](../../src/test/java/kr/gongchae/bell/ingestion/PostingIngestorIntegrationTest.java#L310-L323) |
| 2 | `U+001F` 우대 구분자/거부 제거 → 읽을 수 있는 라벨+줄바꿈 단락, 두 원문 변경 관측, 계약·ERD 정합성 | **해결.** `ListResponseParser.pref()`가 `[우대조건]\n<prefCondCn>`과 `[우대내용]\n<prefCn>`을 `\n\n`으로 잇고, 없는 쪽 단락은 생략하며 둘 다 없으면 `null`을 반환한다. 제어문자(`\u001F` 포함) 거부 로직이 없고 원문 그대로 저장되며, `pref`가 12개 비교 필드에 포함돼 요약 변경·상세 변경·요약 삭제 모두 `posting_change` 1건으로 관측된다. `erd.md` 및 계약 v2와 일치한다. | [`ListResponseParser.java`](../../src/main/java/kr/gongchae/bell/ingestion/ListResponseParser.java#L108-L119), [`erd.md`](../../docs/design/erd.md#L36), [`ListResponseParserTest.java`](../../src/test/java/kr/gongchae/bell/ingestion/ListResponseParserTest.java#L66-L82), [`PostingIngestorIntegrationTest.java`](../../src/test/java/kr/gongchae/bell/ingestion/PostingIngestorIntegrationTest.java#L98-L133) |
| 3 | `APPLY_ERROR` 통합 테스트 (전체 롤백 + `FAILED`/`APPLY_ERROR` + `COMPLETE` 시각 보존 + 예외 재전파) | **해결.** 임시 `CHECK (sn <> 307027) NOT VALID` 제약으로 앞선 행(`307021`, `307023`)의 기관명·마감일·변경이력·코드가 먼저 쓰인 뒤 중간 UPDATE에서 실패하게 만들고, `finally`에서 제약을 정리한다. `DataIntegrityViolationException` 재전파, 5개 공고 관련 테이블 전체 덤프 무변경, 마지막 `COMPLETE` 시각 유지, `collect_run`의 `FAILED`/`APPLY_ERROR`(`total_count=10`, `received=10`, `inserted=0`) 기록을 모두 검증했다. | [`PostingIngestor.java`](../../src/main/java/kr/gongchae/bell/ingestion/PostingIngestor.java#L80-L94), [`PostingIngestorIntegrationTest.java`](../../src/test/java/kr/gongchae/bell/ingestion/PostingIngestorIntegrationTest.java#L136-L164) |
| 4 | `FoundationIntegrationTest` Flyway 건수(`=2`) 고정 제거 | **해결.** `WHERE success AND version IN ('1', '2')`가 `"1"`, `"2"`를 포함하는지와 `WHERE NOT success`가 `0`인지만 검사하므로 이후 `V3+` 마이그레이션이 추가돼도 깨지지 않는다. | [`FoundationIntegrationTest.java`](../../src/test/java/kr/gongchae/bell/FoundationIntegrationTest.java#L46-L50) |
| 5 | 음수 `totalCount`를 `INVALID_ENVELOPE`로 거부 | **해결.** `ListResponseParser.parse()`가 `!total.isInt() || total.intValue() < 0 || !result.isArray()`를 검사해 `INVALID_ENVELOPE`를 던진다. 파서 단위 테스트와 `upsertPage` 통합 테스트(공고·기관 0건) 모두 검증됐다. | [`ListResponseParser.java`](../../src/main/java/kr/gongchae/bell/ingestion/ListResponseParser.java#L56-L60), [`ListResponseParserTest.java`](../../src/test/java/kr/gongchae/bell/ingestion/ListResponseParserTest.java#L93), [`PostingIngestorIntegrationTest.java`](../../src/test/java/kr/gongchae/bell/ingestion/PostingIngestorIntegrationTest.java#L331-L336) |

---

## 2. 영역별 독립 검토

### 2.1 완결성 (FR-C2) 및 파서 정합성
- **정기 수집 거절 규약**: `NON_JSON`, `RESULT_CODE_NOT_OK`, `INVALID_ENVELOPE` → `FAILED`, `INVALID_ROW`, `DUPLICATE_SN`, `COUNT_MISMATCH`, `EMPTY_SNAPSHOT` → `INCOMPLETE`로 구분되며(`Failure.java`), 어느 경우에도 공고/기관/코드/변경 테이블에 부분 반영이 일어나지 않는다.
- **컬럼 제약 정합성**: `V1__catalog_and_ingestion.sql`의 `varchar` 길이 및 `CHECK` 제약과 파서 검증이 일치한다(`pblntInstCd` <= 40, `recrutSe` <= 20, `ongoingYn` `Y`/`N`/`null`, `posting_code.code` <= 40, `code_name.type` 4종, `collect_run.error` <= 100). `pbancBgngYmd`/`pbancEndYmd`는 `ResolverStyle.STRICT`로 `uuuuMMdd`를 검사한다.
- **`upsertPage` 규약**: 빈 페이지(`result: []`, `totalCount >= 0`)는 정상 0건으로 처리하고, `COUNT_MISMATCH`와 누락 판정(`markMissing`)은 건너뛰되 봉투·행 오류·중복 `sn` 시 아무것도 반영하지 않고 `IngestionException`을 전파한다.

### 2.2 원자성 및 트랜잭션 경계
- `PostingIngestor.applyRegular()`와 `upsertPage()` 시작 시 `requireNoTransaction()`으로 호출자 트랜잭션 내부 진입을 차단해, 실패 기록용 별도 트랜잭션이 호출자 롤백에 휘말리는 사고를 막는다.
- `applyRegular()`의 `write()`와 `finish(runId, "COMPLETE", ...)`가 같은 `TransactionTemplate` 안에서 커밋되므로(L81-85), 웹의 마지막 완전 수집 기준 시각(`collect_run.finished_at`)이 공고 반영보다 먼저 또는 단독으로 앞서 나갈 수 없다.
- DB 쓰기 예외 시 내부 트랜잭션이 전 테이블을 롤백하고, 별도 `TransactionTemplate`에서 `FAILED`/`APPLY_ERROR`를 기록한 뒤(이 기록마저 실패하면 `addSuppressed`) 원래 예외를 재전파한다.

### 2.3 변경 관측 (FR-C4) 및 상세 보존 (FR-C7)
- PRD FR-C4 12개 비교 필드(`COMPARED`, L47-48)를 `Arrays.asList`(nullable 보존) 기반 JSON 배열 SHA-256으로 해시한다. `decimalDay`, 관측 시각, 코드 배열 순서는 제외된다.
- 비교 대상이 아닌 `EDU` 코드, `ongoing_yn`, `institution.name`, `broken_text`, `src_url_generic`은 값 변경 시 덮어쓰되 `posting_change`와 `last_changed_at`은 남기지 않는다. (`!row.codes().equals(oldCodes)` 비교 덕분에 `EDU`만 바뀌어도 `posting_code`는 정상 교체된다.)
- `insert()`/`update()` SQL에 `detail_status`, `steps`, `files`, `detail_collected_at`이 포함되지 않아 목록 재수집이 상세 데이터를 덮어쓰지 않는다.
- `from_backfill`과 `first_seen_at`은 `insert()`에서만 기록하고 `update()`에서는 건드리지 않아, 적재 공고가 정기 수집에서 다시 관측돼도 최초 적재 출처가 유지된다.

### 2.4 동시성 및 상태 전이 (FR-C3)
- **교착 방지**: 모든 쓰기 경로가 `institution`(`TreeMap` 코드 오름차순) → `code_name`(`TreeMap` type+code 오름차순) → `posting` (`ORDER BY sn FOR UPDATE`) → `rows`(`sn` 오름차순) 순서로 잠금을 획득한다.
- **동시 신규 `sn` 경합**: `INSERT ... ON CONFLICT (sn) DO NOTHING`이 0행을 반환하면(타 트랜잭션이 방금 커밋함), 해당 `sn`만 `lock()`과 `loadCodes()`로 재조회해 기존 행 갱신 경로로 넘긴다. `pg_stat_activity` 대기 관측 테스트와 `applyRegular`+`upsertPage` 동시 실행 테스트로 검증됐다.
- **KST 상태 전이**: `Clock`의 `ZoneId`(`Asia/Seoul`) 기준 `end_date < today` 또는 `ongoingYn == 'N'`이면 `CLOSED`, D-0(`end_date == today`)은 `OPEN`. 완전 정기 수집에서 빠진 `OPEN`/`CHECK` 공고는 마감 경과 시 `CLOSED`, 아니면 `CHECK`로 전이하고 `closed` 건수를 정확히 전이 건수로만 집계한다.
- **회차 이중 마감 방지**: `requireOpenRun()` 사전 확인에 더해 `finish()`가 `WHERE id = :id AND result IS NULL`과 `Assert.state(updated == 1)`을 트랜잭션 안에서 강제한다.

### 2.5 품질 표시 (FR-C8) 및 로그/보안
- `src_url`: 원문 문자열 그대로 저장하고, HTTP(S) 중 루트 경로(쿼리 없음)이거나 같은 응답 내 동일 기관(`instCode`+`srcUrl`)이 재사용한 URL만 `src_url_generic = true`로 표시한다. 이미 `true`인 경우 URL이 바뀌기 전까지 유지한다. 스킴 없음/파싱 실패 URL은 판별하지 않고 `#5`의 안전 링크 검증에 넘긴다.
- `broken_text`: `title`, `instNm`, `aplyQlfcCn`, `pref`, `scrnprcdrMthdExpln` 중 `U+FFFD` 또는 `\?[가-힣]`이 있을 때만 의심 플래그를 세우고 원문을 변형하지 않는다.
- **로그·에러 보안**: `collect_run.error`에는 `Failure` 열거형 이름만 저장되고, `fail()`의 WARN 로그는 `runId`, 실패 코드, 필드명, `sn`, 건수 또는 **정수형** `resultCode`만 출력한다(`resultCode`가 문자열일 때 예외 메시지 유출 차단). 응답 본문, 요청 URL, API 키는 일체 로깅·저장되지 않는다.

---

## 3. 새 발견 사항 (경미 · 비차단)

1. **[권장 보완] `ListResponseParser.parse(null)` 입력 시 `NON_JSON` 대신 `IllegalArgumentException` 발생**
   - **위치**: [`ListResponseParser.java`](../../src/main/java/kr/gongchae/bell/ingestion/ListResponseParser.java#L42-L48), [`PostingIngestor.java`](../../src/main/java/kr/gongchae/bell/ingestion/PostingIngestor.java#L67-L71)
   - **근거**: Spring `RestClient` 등 HTTP 클라이언트가 빈 응답(HTTP 204 또는 0바이트 200)에서 `body`로 `null`을 넘길 수 있는데, Jackson 3 `JsonMapper.readTree((String) null)`는 `JacksonException`이 아니라 `IllegalArgumentException("argument \"content\" is null")`을 던진다(JShell에서 Jackson 3.x `ObjectMapper.readTree((String) null)` 실측 확인). `applyRegular`는 `parser.parse(body)` 주변에서 `IngestionException`만 잡기 때문에 `collect_run`이 `FAILED / NON_JSON`으로 마감되지 않고 `result IS NULL` 미완료 상태로 남는다.
   - **권고**: `parse(String body)` 맨 앞에서 `if (body == null) throw new IngestionException(Failure.NON_JSON, null);` 처리(및 단위/통합 테스트 1건 추가)를 권장한다. (#4에서 빈 문자열 정규화를 하더라도 파서 방어가 안전하다.)

2. **[문서 포맷] `docs/design/routes.md` 외부 API 표 중간 단절**
   - **위치**: [`docs/design/routes.md`](../../docs/design/routes.md#L40-L49)
   - **근거**: `채용 detail` 행(L44)과 `호출 예산` 행(L47) 사이 L46에 본문 문단(`list 응답에서 쓰는 필드와 저장 규칙은 ...`)이 끼어들어 GFM 표가 둘로 깨졌다.
   - **권고**: L46 문단을 표 아래(L50 이후)로 내린다.

3. **[참고/선택] `loadCodes()` 정렬의 DB collation 의존성 및 비비교 필드 단독 테스트 부재**
   - `PostingIngestor.loadCodes()`(L208-220)는 SQL `ORDER BY sn, type, code` 결과를 `ArrayList`에 담고, `ListResponseParser.codes()`(L128)는 Java `TreeSet`으로 정렬한다. 현재 ALIO 코드(`R600001` 등 영숫자 대문자)에서는 동일하지만, 특수문자·대소문자가 섞인 코드가 들어오면 PostgreSQL DB collation과 Java `String::compareTo` 정렬 순서가 달라져 다른 필드 변경 시 `diff()`가 코드 순서 차이를 변경으로 잡을 수 있다. `loadCodes()`에서도 `TreeSet`으로 적재하면 DB collation 의존성을 완전히 없앨 수 있다.
   - 또한 `EDU` 코드 단독 변경과 `institution.name` 단독 변경 시 `posting_change` 미기록 여부는 코드상 명확하지만(L151-156, L266-270), 독립 통합 테스트 케이스는 없다(`writeErrorMidway...` 롤백 시나리오에만 기관명 변경 포함).

---

## 4. #4 후속 범위 분리 확인

아래 항목은 의도적으로 PR #9(#3) 범위 밖이며 이슈 `#4`(실수집·적재·예산·상세 연동)에서 다룰 사항으로 적절히 분리되어 있다.
- `apis.data.go.kr` HTTP 클라이언트, `serviceKey` 전달 및 로그 마스킹, 재시도/타임아웃
- `api_budget` 선예약 트랜잭션(500 경고 / 900 중단) 및 `collect_run` `(kind, slot)` 선점·스케줄링
- 공식 API 실응답 확인 후 `ListResponseParser`의 `{"item": ...}` 래퍼/평평한 원소 양립 분기 정리 및 빈 조회월의 `resultCode`(예: `3` vs `200 + []`) 확인 처리
- 월별 3년 최초 적재 러너(`PageResult.sns()` 합집합으로 월 고유 건수 및 페이지 간 중복 계산)와 상세(`detail`) 수집 및 알림 outbox(#7) 연결

---

## 5. 검증 실행 결과 (`925aa18e4f5bd6f426b540ac20deb070328239a5`)

- **로컬 실행 (직접 관측)**: 워크트리 `C:/Users/lcj/orca/workspaces/my-project/issue-3-ingestion-core`에서 `.\gradlew.bat --no-daemon check --rerun-tasks` 실행 → `BUILD SUCCESSFUL`
  - `FoundationIntegrationTest`: **4 tests**, 0 skipped, 0 failures, 0 errors
  - `ListResponseParserTest`: **8 tests**, 0 skipped, 0 failures, 0 errors
  - `PostingIngestorIntegrationTest`: **19 tests**, 0 skipped, 0 failures, 0 errors
  - 합계 **31 tests** 모두 통과, `git status -s` 변경 없음 확인.
- **원격 GitHub Actions CI**: [실행 43654425840](https://github.com/Lcjam/gongchae-bell/actions/runs/43654425840) (`headSha: 925aa18e4f5bd6f426b540ac20deb070328239a5`)
  - `test` (`./gradlew --no-daemon check bootJar`, ARM64): **success**
  - `secrets` (`gitleaks/gitleaks-action@v3.0.0`): **success**
- **DoD 점검**: 테스트·원격 CI·비밀값 스캔 통과, Flyway 무수정(`V1`/`V2` 유지), fixture 기반 실응답 표본(`alio-public-list-sample.json`, `alio-public-list-quality-sample.json`) 및 문서 정합성(위 `routes.md` 1줄 제외) 충족.
