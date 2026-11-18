# PR13 응답 기준 시각 조회 예산 수정 결과

2026-11-18. 요청은 root Codex의 PR13 C4 추가 검수 `collab/reviews/pr-13-codex-c4.md` P2다. 단독 작성자 Claude, `issue-6-budget-fix`(`fix/#6-request-budget`)에서 고쳐 PR13 `feat/#6-subscriptions`에 올렸다.

## SHA와 CI

| 항목 | 값 |
|---|---|
| 시작 head | `7cb0068e1704247111e4d325faefb6a4f1b584c5` |
| 코드 커밋 | `d474cf7` (`fix: read the footer collection time inside the request budget (#6)`) |
| CI (코드 커밋) | [44274892246](https://github.com/Lcjam/gongchae-bell/actions/runs/44274892246) test(`check bootJar`, ARM64)·secrets 성공 |
| 최종 head | 이 파일을 담은 다음 커밋(문서만). PR #13 head로 확인 |
| PR | [#13](https://github.com/Lcjam/gongchae-bell/pull/13) draft 유지 |

## 재현 (수정 전 `7cb0068`)

정적 판정을 실제 MVC 경로로 먼저 확인했다. 별도 연결에서 `LOCK TABLE collect_run IN ACCESS EXCLUSIVE MODE`를 잡고 정상 이메일 신청 POST를 executor에서 실행했다. 확인 메일은 나갔고, 응답이 8초 관측 상한을 넘겨 `TimeoutException`으로 실패했다(새 시험 491행, 첫 요청). `finally`가 잠금을 푼 뒤에야 요청이 끝났다. Codex 판정과 다른 근거는 나오지 않았다.

## 수정

- `FreshnessModelInterceptor`에 요청 속성 `STATUS_SOURCE`를 두었다. 값이 `Supplier<FreshnessStatus>`이면 그것을 쓰고, 없으면 기존 `currentStatusSafe()`를 쓴다. 다른 화면(목록·상세·GET 등)의 동작은 그대로다
- `SubscriptionController.budgeted(request)`가 예산 시작과 함께 이 속성에 `service.freshness(start)`를 건다. 적용 경로는 시작 시각으로 25초 예산을 쓰는 세 POST, `/subscriptions/email`, `/subscriptions/discord`, `/subscriptions/recover/email`이다. 관리 링크 재발송은 이를 위해 `recoverEmail(email, ip, start)`로 컨트롤러의 시작 시각을 받는다
- `SubscriptionService.freshness(start)`는 디스코드 최종 저장과 같은 마지막 단계 방식으로 읽는다. `remaining - CLEANUP`에 풀 상한이 들어가야 연결을 받고, 받은 뒤 `cleanupWaits(start)`(2초 정리 마감과 원래 요청 마감 중 짧은 쪽)로 `lock_timeout`·`statement_timeout`을 걸고 조회한다. 부족·시간 초과·조회 실패(`DataAccessException`, 실제 풀 시간 초과가 오는 `TransactionException`)는 기존 `FreshnessStatus.unavailable()`("일시적으로 수집 시각을 확인할 수 없습니다")로 표시하고 경고 로그 `ALERT_FRESHNESS_UNAVAILABLE <예외 이름>`만 남긴다
- 조회는 응답 모델 단계에서만 일어나므로 발송·상태 기록 뒤에 결과를 바꾸지 않는다. 중복 발송이나 상태 반전 경로가 없다. 예외로 끝나는 요청(503 `busy`)은 `postHandle`을 거치지 않아 이 조회를 하지 않는다
- 2초 상한을 쓴 이유: 남은 예산 전체(최대 20초 이상)를 쓰면 메일이 이미 나간 뒤 표시용 조회 하나가 응답을 오래 붙잡는다. 정리 몫은 이미 계약에 있는 마지막 단계 몫이라 새 수치를 만들지 않았다
- 계약 §6 ④에 이 조회를 한 문장 추가했다

## 시험

| 시험 | 결과 |
|---|---|
| 새 `SubscriptionFlowIntegrationTest.theFooterCollectionTimeIsReadInsideTheRequestBudgetWhileCollectRunIsLocked` | `collect_run` ACCESS EXCLUSIVE 잠금 아래 실제 MVC POST. 정상 신청 200, 입력 오류 400, 관리 링크 재발송 200 모두 각각 5초 안에 끝나고 하단에 "확인할 수 없음" 표시. 예산 소진(남은 11초) 신청은 503으로 바로 끝남. 확인 메일은 1통. 잠금을 푼 뒤 신청은 실제 상태를 다시 표시. 각 요청은 `Future.get(8초)`로 관측하고 `finally`에서 잠금을 먼저 푼 뒤 executor를 닫는다. 수정 전 코드에서는 `TimeoutException`으로 실패, 수정 후 통과(7.2초) |
| 로컬 `./gradlew check bootJar` | **144 tests, 0 failures**, bootJar 생성 |
| CI 44274892246 | test(ARM64)·secrets(gitleaks no leaks) 성공 |

provider는 시험 대역, DB는 Testcontainers PostgreSQL 17만 썼다. 실제 API·메일·Discord·OCI·DNS·계정·env·키 접근은 0회다.

## 남은 범위와 관찰

- **관찰(이번 범위 밖, 수정하지 않음)**: 다른 예산 단계(`signupOptions`, 요청 제한 `acquire`, 신청 트랜잭션)에서 풀 획득이 실제로 5초 상한을 넘기면 Spring은 `CannotCreateTransactionException`(`TransactionException`)을 던진다. 컨트롤러 `busy()`는 `CannotAcquireLockException`·`QueryTimeoutException`만 처리하므로 이 경우 고정 503 안내 대신 일반 오류 화면(500)이 나간다. 예산 시간은 지켜지고(풀 진입 전 상한 확보) 쓰기는 커밋되지 않는다. 고정 응답을 원하면 `busy()` 대상에 `CannotCreateTransactionException`을 더하는 한 줄 수정이 가능하다. 판단은 root에 맡긴다. 메일 발송 단계는 `deliver()`가 `RuntimeException`을 잡아 영향이 없다
- `POST /subscriptions/recover/discord`는 시작 시각을 쓰는 예산 경로가 아니라(동기 발송 없음) 기존 조회를 그대로 쓴다. 확인·관리·해지 POST도 같다
- 실제 공개 HTTPS·고지·실발송 검증은 PR 본문의 실사용 전 게이트로 남는다. main 머지, #7 착수, Sol 재개는 하지 않았다
