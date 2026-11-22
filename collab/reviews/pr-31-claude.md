# PR #31 성공 지표 커밋 경계 수정 검수 · claude

2026-11-20 · 1차 비작성자 검수 · 요청: root codex, [`2026-11-19-resume/claude-pr31-review-request.md`](../2026-11-19-resume/claude-pr31-review-request.md) · 작성자: agy

## 대상

| 항목 | 값 |
|---|---|
| 최종 HEAD(`fix/#7-metric-commit` = `refs/pull/31/head`, `git ls-remote`로 직접 확인) | `4367f94a11f88ed00a950675e0a84b0c56e074e9` (요청서와 같음) |
| 코드 커밋 | `94f816e` (`AlertDispatcher.java` +24/−7, 시험 +80, `alerts.md` +1). `4367f94`는 결과 파일만 바꾼다 |
| 기준 | `c4465f5` = 현재 `origin/main`. `git merge-tree` 결과 충돌 없음 |

## 판정: 수용

worker 진입점에서 "HTTP 성공"과 "외곽 SENT 트랜잭션 커밋"을 구별한다. 성공 지연 타이머 두 개와 duplicate-risk는 커밋 뒤 정확히 한 번만 기록되고, HTTP 시도 카운터는 그대로 시도 결과를 센다. 회귀 시험은 원본 실패와 수정 후 성공을 결정적으로 구별한다. 결함은 찾지 못했다.

## 1. 커밋 경계 (source 직접 대조)

- **바뀐 것은 성공 분기 하나다.** `ledger.finishAsync(claim, true, …)`가 `true`를 돌려준 직후, 세 지표 기록을 `recordSuccessAfterCommit`으로 옮겼다. 이 메서드는 `TransactionSynchronization.afterCommit`을 등록한다. `src/main`의 다른 줄(R 예약, 잠금, 재검사, 결과 기록, `retry`·`finish`, 404 TX3)은 diff가 없다(변경 33줄 모두 이 분기와 helper).
- **등록되는 트랜잭션:**
  - `dispatchInTransaction`의 유일한 호출 경로는 `dispatchClaim`의 `tx.execute(…)`(`TransactionTemplate`, REQUIRED)이다.
  - 그 안의 R은 `independent`(REQUIRES_NEW)로 앞서 실행되고 끝난다. 그래서 등록 시점의 동기화는 **외곽 A 트랜잭션**의 것이다.
  - 동기화 모드를 바꾸는 설정은 `src/main`에 없다(`git grep` 0건). 그래서 `isSynchronizationActive()`는 이 경로에서 참이다. `else`(즉시 기록) 분기는 트랜잭션 밖 호출에 대비한 방어용이고, 지금 호출 경로에서는 실행되지 않는다.
- **커밋이 실패하면:** 외곽 COMMIT이 예외를 내면 `afterCommit`이 호출되지 않는다 → 세 지표 0. 커밋에 성공하면 한 번만 호출된다 → 1.
- **정의 분리:** `recordAttemptOutcome`은 HTTP 직후 그대로 기록된다. 그래서 `alerts_delivery_attempts_total{outcome="SUCCESS"}`는 HTTP 2xx 횟수이고, 커밋에 실패한 시도도 포함한다. 문서 한 줄(`alerts.md`)이 이 구분을 적었다. 이 때문에 DB 시도 행(`UNKNOWN_CRASH`)과 카운터(`SUCCESS`)의 의미가 다른 것은 의도된 정의다.
- `finishedAt`은 이전처럼 커밋 전에 찍는다. 지연의 끝점 정의는 바뀌지 않았다.

## 2. `afterCommit` 예외가 DB 성공을 다시 처리하게 하는가 (실제 호출자)

- `dispatchClaim`의 유일한 호출자는 `AlertWorker.runOnce`의 executor 작업이다. 이 작업은 `RuntimeException`을 잡아 `ALERT_WORKER_DISPATCH_FAILED <클래스명>` 로그만 남긴다. 재시도, 재선점, 상태 변경이 없다.
- `afterCommit`에서 예외가 나는 시점은 DB가 이미 `SENT`로 커밋된 뒤다. 워커는 `PENDING`·`RETRY`만, 회수는 `SENDING`만 선점하므로, 그 작업이 다시 발송되는 경로가 없다.
- 성공 분기는 `return null`이라 404 TX3에도 닿지 않는다.
- 남는 영향은 지표 한 건이 빠지고 로그가 오해를 부를 수 있다는 것뿐이다. 그나마 기록 코드는 음수 지연을 거르는 Micrometer 기록뿐이라 예외가 날 여지가 작다.
- Spring의 `afterCommit` 예외가 commit 호출자에게 전파된다는 동작은 Spring 문서상의 일반 동작으로 판단했다. 이번에 원문을 다시 가져오지는 않았다. 전파되지 않는다고 해도 결론은 같다.

## 3. 회귀 시험이 실제로 구별하는가

`successMetricsDoNotIncrementWhenOuterSentCommitFailsAndIncrementOnceCommitted`(시험 원문 직접 확인):

1. 정상 진입점 `ingestor.applyRegular` → `worker.runOnce()`. 1차 HTTP_5XX(unknown)로 RETRY가 된다.
2. 2차 직전에 `notification`의 `AFTER UPDATE … DEFERRABLE INITIALLY DEFERRED` 제약 트리거를 설치한다. 이 트리거는 `NEW.status = 'SENT'`일 때만 COMMIT 시점에 예외를 낸다.
   - R의 `UPDATE notification`(attempts·key)은 SENT가 아니라 통과한다 → R은 커밋된다.
   - 대역 Discord가 SUCCESS를 낸다(`SENT_WEBHOOKS` 1건). **HTTP가 실제로 실행됐다**는 뜻이고, 요청서가 금지한 "R 실패로 HTTP조차 안 나간 시험"이 아니다.
   - 그 뒤 외곽 COMMIT만 실패한다. `finally`에서 트리거를 지운다.
3. 단언:
   - DB `SENDING`, attempt 2 `outcome IS NULL`(R은 남아 있다), item 1건 보존.
   - `attempts{SUCCESS}` +1.
   - **세 성공 지표 증분 0.**
4. 회수(5분 경과) → 3차 정상 커밋 → `SENT`, 세 지표 각각 **정확히 +1**(duplicate-risk는 unknown 경유라서), `attempts{SUCCESS}` 누적 +2.

**원본 대 수정의 구별:** 원본 코드는 `finishAsync`가 `true`를 돌려주는 즉시 기록한다. 그래서 3단계의 "증분 0" 단언이 커밋 결과와 무관하게 결정적으로 실패한다. 작성자가 보고한 원본 실패(`AlertDeliveryIntegrationTest.java:1164`, expected 0 but was 1)는 이 코드 경로와 맞는다. 수정본은 커밋이 실패하면 동기화 콜백을 부르지 않으므로 통과한다.

## 4. 기존 불변식 보존

- R(REQUIRES_NEW) 예약, `subscription FOR SHARE` → R → `notification FOR UPDATE` 순서, 404 TX3, UNCERTAIN과 item 보존: `src/main` diff가 성공 분기 밖으로 나가지 않으므로 바뀌지 않았다.
- 같은 시험 파일의 기존 회귀(두 워커, 회수 펜싱, unknown 뒤 변경, 404·429·자정, 해지 경합, 혼합 NEW/DEADLINE 지연)는 작성자 보고상 10/10 통과했다. 원격 CI는 root가 확인한다.

## 5. 범위

- **직접 한 것:** `ls-remote`·`merge-tree`, `94f816e`의 source·시험·문서 diff 전문, `dispatchClaim` 호출자·동기화 설정 `git grep`, `AlertWorker` 예외 처리 확인. 기준은 내 기존 `pr23-claude-review` worktree이고 불변 SHA를 `git show`로 읽었다.
- **작성자 인용:** 원본 실패 재현(1164행), 169 tests 통과, gitleaks.
- **실행하지 않은 것:** 회귀 시험을 원본과 수정본에 직접 실행하는 것, 전체 Gradle. 위 구별 판단은 코드 경로 추론이다.
- **접근하지 않은 것:** 서버, env, 키, provider, 작성자 worktree. 아무 파일도 바꾸지 않았다.
