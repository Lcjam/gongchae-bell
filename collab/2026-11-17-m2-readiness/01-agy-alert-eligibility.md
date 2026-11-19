# `#7` 알림 대상 공고 `OPEN`/`CHECK` 유효 경계 독립 검토 · agy

> 작성 일시: 2026-11-17 · 조율: codex · 대상: `#7` 착수 전 단일 쟁점(`'현재 유효 공고'`의 `OPEN`/`CHECK` 경계)
> 근거: `CLAUDE.md`, `docs/prd.md`(FR-C3·FR-C4·FR-A4), `agy-issue7-handoff.md` #3, 불변 `main`(`000dce5`: `PostingIngestor.java`), 불변 PR #13(`08cfdac3`: `CatalogRepository.java`, `V5__subscriptions.sql`, `12/13/14`).

## 핵심 의견: 네 단계 모두 **`p.status = 'OPEN' AND p.end_date >= :kstToday`**로 통일

알림의 `'현재 유효 공고'`는 목록 검색 기본 필터(`searchJobs("open")`: `OPEN`+`CHECK`)가 아니라 **홈 마감 임박(`CatalogRepository.findClosingSoon:94`: `p.status = 'OPEN'`) 기준**과 맞춰, **확인된 진행 상태(`OPEN`) + 마감 미경과(`end_date >= :kstToday`)만 허용하고 `CHECK`·`CLOSED`·기한 경과는 모두 제외**하는 단일 규칙을 제안한다.

### 근거 (`000dce5` / `08cfdac3` 대조)
1. **수집 상태 판정 (`PostingIngestor.java:139, 180-192`)**: 정기 수집 신규 `INSERT`는 `end_date < today` 또는 `ongoingYn='N'`이면 `CLOSED`, 그 외에는 `OPEN`만 넣으며 `CHECK`로 신규 삽입되는 경우는 없다. `CHECK`는 완전 수집 스냅샷에서 마감일 전에 빠진 행(`markMissing`)으로, 일시 누락일 수도 있고 원천이 `ongoingYn` 없이 조기 종료/취소한 공고일 수도 있는 **미확인 상태**다(FR-C3).
2. **홈·목록과의 역할 분리 (`CatalogRepository.java:94, 131`)**: `/jobs` 목록은 사용자가 직접 `'확인 필요'` 배지를 보고 판단하므로 `OPEN`·`CHECK`를 함께 보여주지만, 능동 푸시 알림은 미확인 공고 오발송을 피해야 하며 홈 `findClosingSoon`도 이미 `p.status = 'OPEN'`만 노출한다.
3. **단일 규칙의 이점**: `EMAIL_DIGEST`는 한 메일에 `NEW`와 `D-3`을 함께 담고 `TX2`는 `payload_sha256`·target-set 일치를 함께 검증하므로, `NEW`와 `D-3`의 `CHECK` 허용 기준이 다르면 분기와 회귀 위험만 늘어난다.

---

## 단계별 적용 규칙

| 단계 | 판정 기준 (`kstToday` = KST 오늘 날짜) | `CHECK` | `CLOSED` / `end_date < today` |
|---|---|---|---|
| **1. `NEW` 최초 outbox** (`PostingIngestor.applyRegular` TX) | `!fromBackfill && snapshot`에서 `insert(...) == true` AND **`status = 'OPEN'` AND `end_date >= today`** (Discord `notify_new`만 즉시 생성) | 발생 안 함 (제외) | **제외** (`ongoingYn='N'`·과거 마감 신규 적재분 outbox 미생성) |
| **2. 이메일 미발송 `NEW`** (`EMAIL_DIGEST` 08:00+만회) | `p.from_backfill = false` AND `p.first_seen_at >= s.confirmed_at` AND **`p.status = 'OPEN' AND p.end_date >= :kstToday`** AND `notification_item(NEW)` 미존재 | **제외** (일시 누락이면 `OPEN` 복귀 후 다음 날 선택) | **제외** (KST 자정 직후 `:07` 실행 전이라도 `end_date < :kstToday`로 즉시 차단) |
| **3. `D-3` 마감 임박** (`EMAIL_DIGEST` · `DISCORD_DEADLINE`) | **`p.status = 'OPEN' AND p.end_date = :batchDate + 3 AND p.end_date >= :kstToday`** (`notify_deadline = true`, `notification_item(DEADLINE)` 미존재). 홈 `findClosingSoon`과 일치 | **제외** | **제외** |
| **4. `TX2` 직전/후 재확인** (`R` 전 `A` 및 `lockReserved` 후) | 구독 `ACTIVE` + `notify_*` + `CatalogRepository.appendConditions` + **`p.status = 'OPEN' AND p.end_date >= :kstToday`** (`DEADLINE`은 `p.end_date = :batchDate + 3` 유지) | **제외** (탈락 처리) | **제외** (탈락 처리) |

---

## 재등장 및 `unknown` target-set/hash 규칙 유지

- **재등장(`CHECK → OPEN`, `CLOSED → OPEN`)으로 `NEW`가 새로 생기는가?**  
  **아니오.** `PostingIngestor.write` (`146-162`행)에서 재등장은 `old != null` 분기의 `UPDATE`이며 `first_seen_at`과 `from_backfill`은 갱신되지 않는다. 따라서 `insert(...) == true`인 신규 SN 전용 outbox 훅은 발동하지 않고, 이미 보낸 공고는 `PK(subscription_id, sn, 'NEW')`도 중복을 막는다.  
  *(유일한 예외: 최초 `OPEN` 관측 후 아직 어느 `EMAIL_DIGEST`에도 담기기 전에 `CHECK`로 빠져 `08:00`에 제외됐다가 `OPEN`으로 복귀한 경우, `notification_item`이 아직 없으므로 다음 `08:00` 묶음에서 **최초 1회** 선택될 수 있다 — 중복이 아닌 미발송분의 지연 발송.)*
- **`CLOSED`/지나간 마감 배제 + `unknown` target-set/hash 규칙 (`13 §2`, `14 §2`)**:
  - KST 자정 경과 후 다음 정기 수집(`:07`) 전까지는 DB `status`가 `'OPEN'`으로 남아 있을 수 있으므로, 4단계 모두 **`p.status = 'OPEN' AND p.end_date >= :kstToday`**를 함께 검사한다.
  - `TX2`에서 `anyUnknown == true`일 때 **재계산한 적격 target-set이 기존 `notification_item` 집합과 하나라도 다르면**(`CHECK` 전락, `CLOSED`/마감 경과, `D-3` 연장·경과, 조건/종류 수정), `payload_sha256` 일치 여부와 무관하게 `R`·HTTP 없이 **`UNCERTAIN`으로 종결하고 `notification_item` 전부를 보존**한다(재선택 중복 차단).
  - `anyUnknown == false`일 때만 탈락 항목의 `notification_item`을 (`R` 이후 `lockReserved()` 아래 `notification → item` 순서로, 또는 0건 시 `CANCELLED`와 함께) 삭제해 추후 `OPEN` 복귀·조건 복구 시 재선택될 수 있게 한다.

---

## 최소 반례 3건 검증

1. **완전수집 1회 누락 후 다시 나타남 (`OPEN → CHECK → OPEN`)**:
   - *경로*: `16:07` 신규 `OPEN` 적재(`first_seen_at=16:07`) → 다음 날 `07:07` 누락(`CHECK`) → `08:00` `EMAIL_DIGEST` 실행 → `08:07` 재등장(`OPEN`).
   - *결과*: `08:00`에 `CHECK`이므로 제외 → `08:07` 재등장은 `UPDATE`라 `DISCORD_NEW` 재발생 없음 → 다음 날 `08:00`에 `status='OPEN'`, `end_date >= today`, `notification_item` 없음이므로 이메일 **1회 정상 포함**. 반면 `OPEN+CHECK`를 허용하면 조기 취소된 공고도 `08:00`에 오발송할 수 있다.
2. **`CHECK`인 `D-3` (`status = 'CHECK'`, `end_date = today + 3`)**:
   - *경로*: 마감 4일 전 원천 목록에서 내려가 `CHECK`인 공고가 오늘 `D-3`에 도달.
   - *결과*: `OPEN`만 허용하면 홈 `findClosingSoon`과 동일하게 **발송 제외**(미확인/조기 종료 공고의 헛알림 차단). 다만 일시적 `07:07` 1회 누락이었다면 `08:00`/`09:00` `D-3` 알림을 놓치고 다음 날 `D-2`가 되므로 복구되지 않는 한계는 남는다(미확인 푸시 차단과의 트레이드오프).
3. **보낸 뒤 `unknown` 상태에서 상태 변경 (`TIMEOUT`/5xx → `OPEN → CHECK` 또는 `CLOSED`)**:
   - *경로*: `EMAIL_DIGEST`가 `[sn=10, sn=20]`으로 1차 시도 `TIMEOUT`(`anyUnknown=true`, `body_sha256` 저장). 재시도 전 정기 수집에서 `sn=10`이 `CHECK` 또는 `CLOSED`(혹은 `D-3`→`D-2` 자정 경과)로 변함.
   - *결과*: `TX2` 재확인에서 현재 target-set이 `[20]`으로 축소(≠ `[10, 20]`). `anyUnknown == true`이므로 `[10, 20]` 본문을 그대로 재전송하지도 않고 `[20]`만으로 본문을 바꾸어 재전송하지도 않으며, **`R`/HTTP 0회로 `UNCERTAIN` 종결 및 `[10, 20]` `notification_item` 모두 보존**해 `sn=10`/`sn=20` 중복 발송을 막는다.
