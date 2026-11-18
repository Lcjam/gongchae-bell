# PR13 C4 추가 수정 결과

2026-11-18. 요청은 `claude-c4-request.md`(root Codex)다. Sol이 `fix/#6-request-budget`에서 시작했고 Codex 사용량 한도로 커밋 전에 멈췄다. root Claude가 그 변경을 이어받아 검증·커밋·push했다.

## SHA

| 항목 | 값 |
|---|---|
| 시작 head (PR13) | `7fa3f385773b3bcf2cb76b7a8f2766c1867cc432` |
| 코드 커밋 | `6554ea6dec974a3677419e220eab4258beaee5ca` |
| 1차 결과 문서 커밋 | `68c7767` |
| CI (코드 커밋) | [44223952032](https://github.com/Lcjam/gongchae-bell/actions/runs/44223952032) test(ARM64)·secrets 성공 |
| agy C4-R1 수정 | `10fb394`, CI [44225118487](https://github.com/Lcjam/gongchae-bell/actions/runs/44225118487) 성공 |
| agy 2라운드 메모 수정 | `1a0b01f`, CI [44225868874](https://github.com/Lcjam/gongchae-bell/actions/runs/44225868874) 성공 |
| 이 결과 문서 갱신 | 이 파일을 담은 다음 커밋 (PR13 head) |

## 세 항목의 수정

1. **조건 조회부터 같은 예산.** `SubscriptionController`가 POST 처리 첫머리에서 `service.start()`로 단조 시계를 시작하고, `signupOptions(start)`가 그 예산 안에서 `findFilterOptions`를 한 번 읽는다(풀 진입 확인 + `lock_timeout`·`statement_timeout`). 조건 검증과 잘못된 입력 재표시는 같은 옵션을 재사용해서 두 번째 조회가 없다. 서비스의 `subscribeEmail`·`registerDiscord`는 시작 시각을 인자로 받는다.
2. **연속 SQL마다 남은 시간.** `DeliveryLedger.reserve`는 notification UPDATE, `SEND_TOTAL` upsert, attempt INSERT 직전마다 `boundWaits(waitLimit.get())`를 다시 건다. 신청 INSERT 뒤 `createSync`, 기존 행 갱신, 관리 링크 재발송, 디스코드 최종 저장(구독·notification·attempt INSERT)도 문장마다 다시 잰다. 최종 쓰기는 `cleanupWaits(start)`가 2초 정리 마감 하나를 공유하고 원래 25초 마감으로도 자른다.
3. **풀 진입 전 확보.** `RateLimiter`가 기동 시 `spring.datasource.hikari.connection-timeout`(현재 5초)을 읽고, 새 연결을 받는 모든 지점(`acquire`, `reserve`, A, 신청·재발송·디스코드 저장, 종결 `end`)이 그 상한이 남은 몫에 들어가는지 먼저 확인한다. 부족하면 연결을 요청하지 않고 `SYNC_BUDGET_SPENT`로 끝난다. 일반 단계의 `waitLimit`은 최종 연결 5초와 정리 2초를 미리 뺀다. 설정값이 0(무제한)이면 새 연결을 시작하지 않는다. 전역 풀 설정은 바꾸지 않는다.

부모→자식 잠금 순서, R 독립 원자 커밋·펜싱·최대 3시도·unknown 보존·예약 환불 없음은 그대로다. 계약 §6, ADR-0014, `docs/ops/alerts.md`의 "풀 대기는 예산 밖" 서술을 "정상 풀·잠금·문장 대기는 예산 안"으로 고쳤다.

## 집중 검증

로컬 Windows x64, Java 21, Testcontainers PostgreSQL 17. `./gradlew test --tests 'kr.gongchae.bell.alerts.*'` **46 tests, 0 failures** (11:31).

| 요청한 회귀 | 테스트 |
|---|---|
| 조건 조회가 먼저 시간을 쓴 경우 | `conditionLookupUsesThePostBudgetAndInvalidInputReusesItsOptions`: 조회가 14초를 쓰면 이메일·디스코드 신청 모두 503, 메일·웹훅 0, 구독·카운터 0. 잘못된 입력은 400이고 조회는 요청당 1회 |
| 조건 조회가 DDL 잠금에 막힌 경우 | `aConditionQueryBlockedByADdlLockTimesOutAndTheHarnessAlwaysReleasesIt`: 12초 안에 503 |
| R 안 두 잠금 대기가 연속된 경우 | `sequentialReservationLocksShareOneDecreasingBudgetAndRollbackTogether`: notification 잠금 2초 대기 뒤 카운터 잠금은 남은 몫만 기다리고 전체 5.5~7.5초에 실패, 시도·카운터 변화 없음 |
| 풀 진입 때 남은 시간이 부족한 경우 | `insufficientPoolRoomStartsNoNewAttemptAndKeepsTheFinalConnectionShare`, `poolAdmissionUsesTheConfiguredUpperBoundIncludingAnUnboundedPool`: 연결 요청 없이 실패, 종결용 연결 1회만 |
| 기존 `SubscriptionFlowIntegrationTest` 410~430 구조 | 잠금을 쥐는 테스트 6곳 모두 요청을 executor에서 시간 상한으로 관측하고 `finally`에서 잠금을 먼저 해제한 뒤 executor를 닫는다 |

전체 `check bootJar`는 로컬에서 다시 돌리지 않았고 CI로 확인했다. 요청대로 C1~C6 변이 확인은 반복하지 않았다. 이번 세 항목을 되돌렸을 때 새 테스트가 실패하는지도 직접 변이로 확인하지 않았다(agy 리뷰에서 확인 요청).

## 남은 한계

- GC·OS 정지, 응답하지 않는 DB는 25초 물리 상한을 넘길 수 있다.
- 백오프 대기가 OS 정지로 길어져 남은 시간이 풀 상한 아래로 떨어지면 `end()`가 연결을 요청하지 않고 예외로 빠진다. 이때 동기 작업 행은 `SENDING`으로 남고 5분 회수(`sweepSync`)가 재발송 없이 종결한다. 이미 커밋된 R의 null 시도는 결과 모름으로 남는다.
- 풀 상한을 보수적으로 확보하므로 실제 풀 대기가 짧아도 HTTP timeout이 줄고(현재 최대 7초를 남김) 재시도 수가 줄 수 있다.
- 실제 고지 게시, Resend·Discord 실발송, 실서버 HTTPS·Caddy 로그 확인은 PR13 본문의 "실사용 전 게이트"로 남는다. 실제 API·메일·Discord·OCI·DNS·계정·env·키 접근은 0회다.

## 리뷰와 후속 수정

- **agy 1라운드** `collab/reviews/pr-13-agy-c4.md`: 수정 요청. **C4-R1** 디스코드 등록 테스트가 1회차 10초 타임아웃 뒤 재시도할 때 `MIN_START`(5초) 검사는 통과하고 새 풀 진입 확인(남은 13초 필요)에서 `QueryTimeoutException`이 나 502 대신 503이 나갔다. Claude가 코드로 재현을 확인하고 `10fb394`에서 고쳤다. 이메일·디스코드 재시도 루프가 백오프 sleep 전에 `roomForNext`(MIN_START + 다음 시도의 풀 상한·요청·정리 몫)를 확인하고, 못 들어가면 sleep 없이 끝난다(디스코드 `TEST_FAILED` 502, 이메일 A 안에서 `DEADLINE`). 1회차 전 예산 부족은 그대로 503이다. 낡은 Javadoc 두 곳과 계약 §6 ③도 고쳤다. 새 테스트 `aWebhookRetryThatCouldNotBeAdmittedEndsAsAFailedTestWithoutSleeping`은 수정을 되돌린 변이에서 `SYNC_BUDGET_SPENT`로 실패하는 것을 확인하고 원복했다. alerts 47건 통과
- **agy 2라운드** `collab/reviews/pr-13-agy-c4-round2.md`: 수용. 비차단 관찰로 sleep 전 확인을 몇 ms 차로 통과한 뒤 sleep이 OS 오차로 길어지면 2회차가 다시 503으로 빠질 수 있는 좁은 창을 짚었다. `1a0b01f`에서 디스코드 재시도를 sleep 뒤 `roomForNext(start, 0)`로 다시 재게 했다. 같은 테스트에 1ms 초과 sleep 시나리오를 넣었고, 가드를 되돌린 변이에서 실패를 확인하고 원복했다. alerts 47건 통과
- Codex 리뷰는 복귀 후. draft 유지, main 머지와 #7 착수는 하지 않았다
