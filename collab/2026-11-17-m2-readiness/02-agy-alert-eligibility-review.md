# 알림 `OPEN`/`CHECK` 유효 경계 교차 검토 · agy

> 작성 일시: 2026-11-17 · 조율: codex · 대조: `CLAUDE.md`, `01-agy-alert-eligibility.md`, `01-codex-alert-eligibility.md`, `000dce5`(`PostingIngestor.java`), `08cfdac3`(`CatalogRepository.java`, `JobSearchCriteria.java`)

## 1. 사실 오류 · 계약상 필수 사항 · 순수 제품 트레이드오프 구분

| 구분 | 판정 | 근거 (`000dce5` / `08cfdac3` · 계약 `12/13/14`) |
|---|---|---|
| **사실 확인** | **양측 사실 오류 없음** (단, 대조 기준 차이 존재) | `01-codex`는 목록 `searchJobs("open")`의 `OPEN/CHECK` 선례(`CatalogRepository.java:131`)를 들었고, 홈 `findClosingSoon`의 `OPEN`-only 선례(`CatalogRepository.java:94`)는 다루지 않았다. `01-agy`는 반대로 `findClosingSoon`을 기준으로 삼고 `JobSearchCriteria.strict:67`의 `"open"` 맥락을 약하게 봤다. |
| **계약상 필수 사항** (양안 완전 합의, 변경 불가) | ① 최초 `NEW` outbox는 정기 수집 `insert(...) == true` 신규 SN만(backfill·`CLOSED` 제외, `CHECK→OPEN`/`CLOSED→OPEN` 재등장으로 `NEW` 재생성 금지)<br>② 모든 단계에서 `CLOSED` 및 `end_date < :kstToday` 즉시 배제<br>③ `NEW`는 `from_backfill=false` + `first_seen_at >= confirmed_at` + 미중복 item, `D-3`는 `end_date = :batchDate + 3`<br>④ `TX2`에서 `anyUnknown == true`면 target-set 불일치·`payload_sha256` 불일치 시 `R`/HTTP 없이 `UNCERTAIN` 종결 및 item 보존 | `PostingIngestor.java:139-192`, `V5__subscriptions.sql:65-71`, `13 §2`, `14 §2`, PRD `FR-C3·C4·A4` |
| **순수 제품 트레이드오프** (유일한 쟁점) | `p.status = 'OPEN'` vs `p.status IN ('OPEN','CHECK')` | **미확인·조기종료 의심 공고 푸시 차단**(`OPEN`-only) vs **원천 1회 누락 시 `D-3` 기회 보존 및 목록 `open` 일관성**(`OPEN+CHECK`). 스키마·잠금·멱등 계약과 무관한 단일 술어 선택이다. |

---

## 2. 각 입장을 철회·수정하게 하는 근거 (반례 조건)

- **`OPEN+CHECK`(`01-codex`)를 철회하게 하는 근거**:
  1. 코드베이스 자체 선례: `CatalogRepository`는 이미 `/jobs` 목록(`searchJobs`)에서만 `'확인 필요'` 경고 배지와 함께 `CHECK`를 노출하고, 능동 추천 영역인 홈 `findClosingSoon`(`94`행)에서는 **`CHECK`를 제외한 `OPEN`만** 쓴다. 알림 본문에 상태 경고를 동적으로 넣으면 `OPEN↔CHECK` 변화 때 `payload_sha256`이 달라져 `unknown` 재시도가 `BODY_CHANGED`로 끊기고, 상태를 숨기면 조기 철회된 `CHECK` 공고를 정상 마감 임박처럼 푸시하게 된다.
  2. 실제 정기 수집 관측에서 `CHECK` 행의 다수가 일시 누락이 아니라 **원천이 `ongoingYn=N` 없이 조기 내린 마감/취소 공고**로 확인되면(마감 4일 전 내려간 공고가 `D-3`에 계속 푸시됨), `OPEN+CHECK`는 철회해야 한다.
- **`OPEN`-only(`01-agy`)를 철회하게 하는 근거**:
  1. 실제 정기 수집 관측에서 ALIO `ongoingYn=Y` API가 유효한 공고를 1~2회 간헐 누락했다 복구(`OPEN → CHECK → OPEN`)하는 빈도가 높고, 특히 `07:07`~`09:00 KST` 구간 깜빡임으로 유효한 `D-3` 알림이 실제로 탈락하는 사례가 확인되면 `OPEN`-only를 철회하고 `OPEN+CHECK`(본문 상태 고정)로 전환해야 한다.

---

## 3. 최소 결정 제안 (MVP · 추가 기능/변수 없음)

- **원칙**: 새 가입 필터·재알림 기능·별도 정책 설정 변수를 두지 않고, 4개 경계(`NEW` outbox, 이메일 `NEW`, `D-3`, `TX2` 재확인)에 **하나의 고정 SQL 조건**만 둔다.
- **최소 제안**: **`p.status = 'OPEN' AND p.end_date >= :kstToday`** (`OPEN`-only)로 시작한다.
  - 홈 `findClosingSoon`과 기준이 일치하고, 상태 배지 없는 푸시 채널에서 조기 철회(`CHECK`) 공고 오발송·`OPEN↔CHECK` 본문 해시 변동 위험을 피할 수 있다.
  - 반대로 사용자가 "정기 수집 1회 누락 때문에 `D-3`가 빠지는 것보다 `CHECK` 오발송 위험을 감수하겠다"고 결정하더라도, `01-codex`처럼 본문에 상태를 동적 표기하지 않고 `p.status IN ('OPEN','CHECK') AND p.end_date >= :kstToday` 한 줄로 바꾸면 기존 `12/13/14` 계약과 모순 없이 동작한다. 실제 정기 수집 가동 후 `CHECK`의 성격(일시 깜빡임 vs 조기 종료)이 관측되면 단일 SQL 술어 1줄로 조정할 수 있다.

---

## 4. Sol 독립안·Codex 교차 검수 대조 및 자정 경계 정정 (`2026-11-17`)

- **대조 확인**: `01-sol-alert-eligibility.md`, `02-codex-alert-eligibility-review.md` 모두 `p.status = 'OPEN' AND p.end_date >= :kstToday` 단일 경계와 공통 불변식(`insert == true`만 `DISCORD_NEW`, 재등장 `NEW` 미생성, `CLOSED`/과거 마감 배제, `unknown` 시 target-set/전체 `payload_sha256` 차단 및 item 보존)에 일치함을 확인했다. `01-sol`의 "목록 `open`과 홈 `findClosingSoon`이 원래 다른 역할의 술어이며 `OPEN`-only는 PRD 기확정이 아니라 제품 선택(일시 누락 D-3 손실 수용)"이라는 구분도 정확하다.
- **Sol 지적(`batch_key` vs `kstToday + 3`) 수용 및 `agy-issue7-handoff.md` 정정**:
  - 기존 `01-agy-alert-eligibility.md`와 `agy-issue7-handoff.md`(#2·#3·#4)에서 `D-3` 생성·`TX2` 재확인을 `:batchDate + 3`으로 적은 것은 **오류**였으며, **자격 검사는 실행 시점 `p.end_date = :kstToday.plusDays(3)`**로, **`batch_key`는 본문 날짜 결정성(`payload_sha256` 유지)에만 사용**하도록 `agy-issue7-handoff.md`를 정정한다.
  - **자정 경계 구체 반례**: `11-17` `08:00 KST`(`EMAIL_DIGEST`, `batch_key='2026-11-17'`) 또는 `09:00 KST`(`DISCORD_DEADLINE`)에 `end_date=2026-11-20`인 공고(`D-3`)로 작업이 생성된 뒤 `SEND_CAP`/`QUOTA_DAILY`/`429`/`TIMEOUT`으로 `23h` 수명 내인 `11-18 00:05 KST`~`07:00 KST` 직전까지 재시도되는 경우를 본다. KST 자정이 지나 `kstToday = 2026-11-18`이 된 시점에 `:batchDate + 3`(`2026-11-20`)으로 검사하면 이미 `D-2`인 공고가 계속 통과해 오발송된다(`ADR-0003` "실행일 기준 D-3만", `12 §5` "다음 날 D-2라 잃음", `13 §2` 현재 자격 재검사 위반). `:kstToday + 3`으로 검사하면 자정 이후 `TX2`가 `D-2` 탈락을 즉시 잡아 `!anyUnknown`이면 item 삭제/종결(또는 남은 `NEW`만으로 재구성·새 키), `anyUnknown`이면 item 보존 후 `UNCERTAIN` 종결한다.
  - 반대로 같은 날(`11-17` 자정 전 `09:05`~`23:59 KST`) 재시도에서는 `kstToday == batchDate`이므로 본문 날짜는 `batch_key`로 고정돼 `payload_sha256` 멱등성이 그대로 유지된다.

