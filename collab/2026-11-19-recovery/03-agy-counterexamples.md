# #17 root 교차 검토 2대 반례 한정 검증 · agy

- **작성자 / 일자**: 추가 agy / 2026-11-19
- **대상**: [`02-codex-review.md`](02-codex-review.md)에서 root Codex가 제기한 2개 반례 (`#3` journal 시각 vs DB commit 순서, `#5` 복원 작업 취소 뒤 item 반환·재매칭·슬롯 문제)
- **제약**: 기존 `01`·`02` 원문 보존. 기간·IAM·스케줄·가격 재논의 없음. 실제 source·설정·서버·계정·env·키·provider 변경/조회 및 새 구현 시작 없음.

---

## 1. 검증 1: `journal` timestamp가 DB `commit`보다 앞서는 순서와 `D_lo >= T_w + m` 반례

### 1.1 실제 `main` 코드 확인 사실
- `SubscriptionController.java`(lines 197, 234, 243) → `SubscriptionService.unsubscribe()`(lines 294-307):
  - `SyncSender.DEADLINE` 예산을 쓰지 않으며, `application.yml`에도 HTTP 요청/서블릿 타임아웃 설정이 없다.
  - `SubscriptionService.java` line 85: `this.tx = new TransactionTemplate(manager);` — `TransactionTemplate` 타임아웃 미설정.
  - `tx.execute(...)` 내부(lines 298-303):
    1. `lockForToken(id)` (`SELECT ... FOR UPDATE`)
    2. `journal.append(id, clock.instant())` — **`clock.instant()`는 `append()` 인자로 먼저 평가**된 뒤 `synchronized (writers)` → `FileChannel.lock()` → 쓰기/`force(true)`/`syncDirectory()`를 거친다.
    3. `DELETE FROM subscription WHERE id = :id` — `limits.boundWaits(...)`(`lock_timeout`/`statement_timeout`) 미호출.
    4. `tx.execute` 반환 시 PostgreSQL `COMMIT` 수행.
- `application.yml`의 `hikari.connection-timeout: 5000`은 커넥션 풀 획득 대기만 제한하고 `tx.execute` 내부 실행·커밋 시간은 제한하지 않으며, `await-termination-period: 30s` / `compose.prod.yml` `stop_grace_period: 40s`도 종료 시에만 적용된다.

**결론(타임아웃 입증 여부)**: 현재 코드의 어떤 타임아웃도 `[clock.instant() 평가 → journal.append 완료 → DELETE → DB COMMIT]` 구간의 절대 시간 상한을 입증하지 못한다.

### 1.2 시간순 반례 대조 (root `02-codex-review.md` #3 검증 결과: **반례 성립**)
1. `00:00:00Z`: `unsubscribe(u)`가 `clock.instant()`를 평가해 **`t_journal = 00:00:00Z`** 를 잡는다.
2. `00:00:00Z ~ 01:05:30Z`: 지연 발생(경우 a: `writers`/`FileChannel.lock()`/디스크 I/O 대기로 `append` 자체가 늦게 완료됨, 또는 경우 b: `append`는 끝났으나 CASCADE `DELETE`/락 대기/JVM·DB 스톨로 DB `COMMIT`이 늦어짐).
3. `01:05:00Z`: `backup.sh`가 실행되어 `D_lo = 01:05:00Z`를 찍고 `pg_dump -Fc`가 `REPEATABLE READ` 스냅샷을 잡는다. 아직 `unsubscribe(u)`가 커밋되지 않았으므로 **덤프 안에는 `u`가 `ACTIVE`로 포함**된다.
   - 만약 경우 a(`append` 쓰기 지연이 `stage_erasure_log` `cp` 시각 `01:05:10Z`를 넘김)라면 **스냅샷 동봉 대장 `B`에도 `u`가 없다.**
   - 경우 b(`append`는 먼저 끝나 `B`에 `u \t 00:00:00Z`가 들어감)라면 `B`에는 `u`가 있으나 호스트 `L`의 `u` 줄 시각은 여전히 `00:00:00Z`다.
4. `01:06:00Z`: `unsubscribe(u)`가 `DELETE` 후 DB `COMMIT`을 마친다.
5. 이후 대장 정리: `T_w = 00:01:00Z`로 앞부분 prefix를 정리하면 `00:00:00Z < T_w`이므로 **`L`에서 `u` 줄이 지워진다.**
6. 이후 `01:05:00Z` 덤프 복원 + 재적용:
   - 게이트 판정 `D_lo (01:05:00Z) >= T_w (00:01:00Z) + m (01:00:00)` = `01:05:00Z >= 01:01:00Z` → **통과**.
   - 최신 대장 `L`만 재적용하면 `u`가 이미 지워져 있어 **삭제되지 않고 부활**한다. (경우 a에서는 `B ∪ L`을 써도 `B`에조차 `u`가 없어 부활한다.)

즉, **`timestamp/clock` 여유 `m`과 `DB commit 완료`는 다른 조건이며, `m`만으로 commit 순서를 증명할 수 없다는 root의 반례가 맞다.**

### 1.3 단순 대안 비교 (구현 시작 전 선택지)
| 대안 | 동작 | 경우 a/b 차단 범위 | 복잡도 · 한계 |
|---|---|---|---|
| **1. 앱 정지 정리(quiescence) + 정지 시각 이전만 정리** | 앱 두 색(및 백업 타이머)을 완전히 멈춘 뒤, `T_w_new`를 **"정지 직전 실행 중 tx가 모두 종료된 시점 이전"**으로 제한해 정리하고, 정지 후 재기동 전에 새 덤프(또는 `D_lo > stop_time`)를 기준으로 삼음 | 정지 시점에 인플라이트 `unsubscribe` tx 자체가 0건이므로 `[append → commit]` 걸침이 제거됨(경우 a·b 모두 해소) | 가장 단순하며 `unsubscribe` 트랜잭션 계약 변경 불필요. 단, `T_w_new`가 정지 시점보다 과거여야 함(당일 백업 `D_lo` 직전까지 자르지 않음) |
| **2. `unsubscribe` 내부 데드라인 + DB timeout 강제 + 시각 포착 순서 조정** | `clock.instant()`를 `append()` 내 OS 잠금 획득 **직후**에 찍고, `tx`에 `TransactionTemplate.setTimeout` + `statement_timeout`/`lock_timeout`을 부여해 `m`이 실제로 상한보다 크도록 제한 | 정상 동작 시 `[append → commit]` 폭을 짧게 억제 | JVM GC 스톨이나 커밋 RPC 지연까지 절대 상한으로 보장하려면 PostgreSQL `idle_in_transaction_session_timeout` 등 추가 방어 필요. 단독 증명으로는 대안 1보다 약함 |
| **3. 복원 시 `B ∪ L` 병합 재적용 + 정리 시 DB 활성 tx 확인** | 복원 시 스냅샷 동봉 `B`의 전체(또는 미정리분)와 `L`을 합쳐 재적용하고, 정리 시 `pg_stat_activity`의 최장 활성 tx 시작 시각(`min(xact_start)`) 이전으로만 `T_w_new` 제한 | 경우 b는 `B ∪ L`로, 경우 a는 `T_w_new < min(xact_start)`로 차단 | 정지 정리(대안 1)에 비해 온라인 복잡도가 높음. 다만 `B`와 `L`을 함께 보는 방어는 정지 정리와도 병행 가능 |

---

## 2. 검증 2: 과거 작업 일괄 `FAILED`/`CANCELLED` 제안 vs `#7` item 반환·재매칭·슬롯 충돌

### 2.1 `feat/#7-alert-delivery` 및 `main` 코드 확인 사실
`main`(`V5__subscriptions.sql`, `DeliveryLedger.java`, `V1__catalog_and_ingestion.sql`, `HttpTransports.java`)과 `#7` 브랜치(`AlertOutbox.java`, `AlertBatchGenerator.java`, `AlertScheduler.java`, `AlertMatcher.java`, `DeliveryLedger.java`, `PostingIngestor.java`)를 읽기 대조한 결과:
1. **`#7`의 `FAILED`/`CANCELLED` item 반환 규칙(`DeliveryLedger.finishAsync` · `recoverAsync`)**:
   - `finishAsync()`는 `n.status IN ('FAILED', 'CANCELLED')`일 때 `DELETE FROM notification_item`을 실행해 **item을 반환**하고, **`SENT`와 `UNCERTAIN`일 때만 `notification_item`을 보존**한다.
2. **`AlertMatcher.findMatchingNew`의 재매칭 범위**:
   - `WHERE p.status = 'OPEN' AND p.end_date >= :kstToday AND p.from_backfill = false AND p.first_seen_at >= :confirmedAt AND NOT EXISTS (SELECT 1 FROM notification_item ni WHERE ni.subscription_id = :subId AND ni.sn = p.sn AND ni.kind = 'NEW')`.
3. **슬롯 만회 범위(`AlertBatchGenerator` · `AlertScheduler`)**:
   - `02-agy-claude-review.md` §1.5에서 언급한 "어제/지난 날짜 미실행 슬롯 자동 만회"는 **부정확한 설명이었으며 철회·정정**한다.
   - 실제 `#7` `AlertScheduler.scheduleDailyBatches()`는 `runDigestIfDue(clock.instant())`(`>= 08:00 KST`)와 `runDeadlineIfDue(clock.instant())`(`>= 09:00 KST`)로 **오늘 KST 날짜(`kst.toLocalDate()`)의 미선점 슬롯만** 만회한다. 지난 날짜(`yesterday` 등) 슬롯은 자동으로 채우지 않는다.
4. **Resend `Idempotency-Key`**:
   - `DeliveryLedger.reserve()`가 매 시도마다 전달된 `key`를 저장하므로, 백업 시점에 아직 `PENDING`(시도 0회)이던 행이 백업 뒤 첫 시도에서 생성한 `Idempotency-Key`는 복원 덤프에 존재하지 않는다.

### 2.2 충돌 증명 및 대안 (`FAILED`/`CANCELLED` 철회 → `UNCERTAIN`)
- **반례 성립**: `02-agy-claude-review.md` §1.5의 "복원 DB의 과거 `PENDING`/`RETRY`/`SENDING` 작업을 일괄 `FAILED` 또는 `CANCELLED`로 마감" 제안은:
  1. 백업 뒤~장애 사이 외부 발송이 이미 성공했을 수 있는 작업을 **definite 비발송 실패(`FAILED`/`CANCELLED`)로 잘못 단정**하며,
  2. `#7`의 item 반환 규칙과 결합되거나 item이 함께 지워지면 `notification_item(kind='NEW')`이 비어 **다음 `EMAIL_DIGEST`(`AlertBatchGenerator` 오늘/내일 슬롯)가 `findMatchingNew`로 동일 공고를 다시 매칭해 중복 발송을 일으킨다.**
- **수정 제안 (`UNCERTAIN` 마감 + item 보존)**:
  - 복원 DB 내 백업 시점 잔류 `PENDING`/`RETRY`/`SENDING` 행(및 `outcome IS NULL`인 `notification_attempt`)은 `FAILED`/`CANCELLED`가 아니라 **`status = 'UNCERTAIN'`**, `finished_at = now()`, `claimed_at = NULL`, `last_error = 'RESTORE_UNCERTAIN'`으로 종결하고 **`notification_item` 행은 삭제하지 않고 유지**한다(`V5__subscriptions.sql` CHECK 제약과 `#7`의 `SENT`/`UNCERTAIN` item 보존 계약에 모두 부합).

### 2.3 계층별 중복 방지 실제 범위 (코드 기반 정확한 경계)
| 대상 / 계층 | 처리 방법(발송 재개 전) | 실제로 막는 중복 범위 | 막지 못하는 범위 (잔여 한계) |
|---|---|---|---|
| **1. 복원된 `notification` 행 + `notification_item`** (`PENDING`/`RETRY`/`SENDING`) | `status = 'UNCERTAIN'` 마감 + `notification_item` 보존 (`SYNC_KINDS` `SENDING`도 함께 마감) | 복원 덤프에 이미 들어 있던 미완료 작업의 재실행, 그리고 그 작업에 묶여 있던 `(subscription_id, sn, NEW/DEADLINE)`의 **`findMatchingNew` / `findMatchingDeadline` 재매칭** | 백업 시점에 아직 `notification` / `notification_item` 자체가 생성되지 않았던 건(예: 백업 `03:00` 이후 `08:00` 배치·수집에서 생성된 건) |
| **2. 오늘 `collect_run` 슬롯** (`('DIGEST', kstToday)`, `('DEADLINE', kstToday)`) | 당일 복원 시(`08:00`/`09:00` 이후 복원 & 백업 `03:00`에 오늘 슬롯 미존재) 발송 재개 전 오늘 슬롯을 사전 마킹(`COMPLETE`/스킵)할지 정책 선택 | 당일 재개 직후 `AlertBatchGenerator`가 오늘 `EMAIL_DIGEST`·`DISCORD_DEADLINE`을 **즉시 다시 생성**하는 것을 차단 (`DISCORD_DEADLINE`은 내일이면 `end_date = kstToday + 3` 조건이 지나 자연 소멸) | `DIGEST` 슬롯만 스킵해도 `notification_item(kind='NEW')`이 없으면 **다음 날 `EMAIL_DIGEST`에서 `findMatchingNew`가 그 미기록 `OPEN` 공고를 다시 잡아냄** (당일 차단일 뿐, 이미 `08:00`에 발송됐으나 덤프에 `notification_item`이 없는 공고의 익일 다이제스트 재포함은 슬롯 마킹만으로 못 막음) |
| **3. 복원 후 첫 `REGULAR` 수집으로 새로 들어오는 공고** (`PostingIngestor` → `AlertOutbox.recordRegularNew`) | (A) `ALERTS_ENABLED=true`로 수집 → 중복 발송 가능성 허용, 또는 (B) 첫 `REGULAR` 보충 수집만 `ALERTS_ENABLED=false`로 돌려 공고를 채운 뒤 발송 재개(공백 신규 공고 알림 누락 감수) | (B)를 택할 때만 백업~장애 사이 이미 수집·발송됐던 신규 공고의 **`DISCORD_NEW` 및 다음 `EMAIL_DIGEST` 중복 발송** 억제 가능 (구현된 DB 상태만으로는 차단 불가) | 백업 DB에 행 자체가 없는 공고이므로, 어떤 DB 테이블 정리만으로도 백업 이후 신규 공고의 `DISCORD_NEW` 중복과 장애 중 미수집 공고 누락을 동시에 0으로 만들 수 없음 (PRD FR-A4 중복 비보장 한계) |
