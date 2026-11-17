# PR #13 수정 결과 (C1~C6) · claude

> 2026-11-17 · 조율·검수: codex(root) · 구현: claude(#6 단독 작성자)
> 대상: `feat/#6-subscriptions`, 수정 전 불변 head `08cfdac3de9d6282393f379d062314d5f3440432`(코드 `b0a7ab7`)
> 검수 근거: [root 검수](../reviews/pr-13-codex.md), [agy 검수](../reviews/pr-13-agy.md). 두 파일은 root 원본을 그대로 복사했다.
> 하지 않은 것: 실제 Resend·Discord·DNS 호출, 서버·키·개인정보 값 조회나 변경, 기존 개발 DB(정지 상태 그대로)와 볼륨 접근, root·다른 워크트리 수정, 원격 머지, #7 구현. 테스트는 모두 Testcontainers의 일회용 PostgreSQL 17과 루프백·메모리 대역에서 돌렸다.

## 커밋과 CI

| 커밋 | 내용 | 원격 CI |
|---|---|---|
| `af62adb2065117c6f3d0d099c303e3e3def6652d` | C1~C6, agy R-3, 테스트, 계약·ADR·운영 문서, 검수 사본 | [44099813628](https://github.com/Lcjam/gongchae-bell/actions/runs/44099813628) `test`(ARM64 `check bootJar`)·`secrets` 성공 |
| 이 문서 커밋 | 이 결과 문서와 결과 문서의 후속 링크 | PR #13 본문과 root 사본에 기록 |

PR [#13](https://github.com/Lcjam/gongchae-bell/pull/13)은 draft로 둔다. 머지하지 않았다.

## 대응 요약

| 항목 | 원인 | 수정 | 회귀 검증 |
|---|---|---|---|
| **C1** 재발송과 해지 경합 시 500 | `recoverEmail`이 ACTIVE 행을 잠그지 않고 읽은 뒤 자식 `notification`을 넣었다. 그 사이 해지가 커밋되면 FK 위반이 났다 | 읽기를 `FOR KEY SHARE`로 바꿨다. 행을 읽을 때부터 작업 행을 넣을 때까지 부모를 잡는다. 해지(`FOR UPDATE`→`DELETE`)는 이 트랜잭션을 기다리거나, 먼저 커밋되면 읽기가 행을 찾지 못한다 | `aRecoveryRacingAnUnsubscribeAnswersTheSameAndSendsNothing` |
| **C2** 대장 파일이 없어도 성공 | `readStrict`가 파일이 없으면 빈 목록을 돌려줬다 | 정규 파일이 아니면 `ERASURE_LOG_MISSING`으로 실패한다(경로 없음·디렉터리 모두). 앱은 대장을 만들지 않는다(쓰기에 `CREATE` 없음). 알림을 켜면 시작할 때 파일이 있는지 본다(`ALERTS_ERASURE_LOG_MISSING`). 운영자가 처음 켜기 전에 빈 파일을 만든다. 그래서 "있는 빈 파일"만 해지 없음이고, "파일 없음"은 언제나 경로나 마운트 오류다 | `restoreReappliesTheWholeJournalOrDeletesNothingWhenItIsMissingMisplacedOrCorrupt`, `aMissingFileOrAWrongPathIsNeverAnEmptyJournal`, `anUnsubscribeWithoutItsJournalFileDeletesNothing` |
| **C3** 첫 파일의 디렉터리 fsync가 잠금 밖 | 디렉터리 force를 "새로 만든 경우"에만, OS 잠금을 푼 뒤에 했다 | 쓸 때마다 파일 force 다음에 디렉터리 force를 **OS 잠금 안에서** 한다(POSIX. Windows는 디렉터리를 열 수 없어 건너뛴다). 실패하면 예외가 그대로 해지 요청까지 올라가 아무것도 지우지 않는다 | `everyAppendForcesTheDirectoryWhileStillHoldingTheFileLockAndAFailureReachesTheCaller` |
| **C4** 동기 25초 예산 밖의 DB 대기 (agy M-1 포함) | 요청 제한·신청·재발송·디스코드 저장 트랜잭션에 lock·statement 제한이 없었다. A는 풀 대기 뒤 남은 시간을 다시 재지 않아, 1ms 제한으로 다음 문장이 깨지고 행이 `SENDING`으로 남았다. R 전에도 다시 재지 않았다 | 아래 "C4 세부" | 가상 타이머 3건 + 격리 PG 실제 잠금 3건(아래) |
| **C5** 디스코드 주소 확인이 전송 타임아웃 밖 | 동기 DNS 조회 뒤에 원래 타임아웃 전부로 HTTP를 시작했다 | `AddressGuard.allows(host, timeout)`: 조회를 데몬 스레드에서 돌리고 타임아웃까지만 기다린다. 끝나지 않으면 막는다(`BLOCKED_ADDRESS`, 요청 없음). HTTP는 (타임아웃 − 조회 시간)만 쓴다. 남은 시간이 없으면 `NOT_SENT`다. 모든 주소 검사, 리다이렉트 금지, 실패 시 차단은 그대로다 | `theAddressCheckAndTheRequestShareOneTimeoutAndAStalledLookupStartsNoRequest` |
| **C6** 긴 Retry-After를 버림 (agy R-2) | 86400초 이상을 null로 돌려줘, 기본 2초·4초 뒤에 다시 보냈다 | `BigDecimal`로 유한한 음이 아닌 십진수만 읽는다. 1일 이상은 1일(`MAX_WAIT`)이다. NaN·무한대·음수·16진수·`1d`·HTTP 날짜·문자열은 null이다. 호출자는 지시가 예정 대기보다 길면 요청 안에서 다시 보내지 않는다 | `aValidLongProviderWaitStaysLongAndOnlyUnreadableValuesAreDropped`, `aProviderWaitOfADayEndsTheRequestAfterOneRequestForMailAndWebhookTests` |
| **agy R-3** 꺼짐 상태 GET 안내 | 확인·해지 GET은 폼을 그렸고, POST만 503이었다 | 두 GET도 꺼져 있으면 503과 준비 중 안내를 보인다 | `AlertsDisabledIntegrationTest` |
| 결과 보고의 빈 인자 서술 | agy는 null 인자가 생략된다고 봤다 | **이견, 보고가 맞다.** 실제 응답 HTML은 `/subscriptions/new?ncs=R600020&region=&hire=&career=&q=`이다(이번 실행에서 확인). 테스트가 이 링크를 그대로 확인하고, 이 링크로 연 신청 화면이 정상(200, 직무 선택 유지)인지도 확인한다. 동작은 바꾸지 않았다 | `signupPagesShowNoticeConsentAndListCtaAndRefuseMissingConsentOrKinds` |

### C1 세부

- 잠금 선택: `FOR KEY SHARE`. FK 확인이 쓰는 것과 같은 최소 잠금이다. 해지의 `FOR UPDATE`·`DELETE`와는 충돌하고, 키가 아닌 열의 갱신과는 충돌하지 않는다. 그사이 상태나 `token_version`이 바뀌어도 A의 `stillEligible`(`FOR SHARE`)이 R 전과 HTTP 전에 잡는다. `FOR SHARE`를 쓰면 같은 행의 `confirm`류 갱신까지 막는데, 그럴 이유가 없었다.
- 다른 동기 경로: 신청은 새 행을 직접 넣거나 기존 행을 `FOR UPDATE`로 잡는다. 디스코드 등록은 같은 트랜잭션에서 새 행을 넣는다. R의 시도 행은 같은 R이 잠근 `notification` 아래에 넣는다. 그래서 같은 경합이 없다. 잠금 순서(subscription → notification → attempt)는 그대로다.
- 검증: 격리 PG의 다른 연결이 해지의 DB 문장(행 `FOR UPDATE`, `DELETE`)을 커밋하지 않고 쥔다. 재발송 요청이 잠금을 기다리는지 `pg_stat_activity`로 확인한 뒤 커밋한다. 결과: 200, 미구독 주소의 응답과 바이트 단위로 같음, 메일 0통, `EMAIL_RECOVER` 행 0, 구독 0. 순서가 반대인 경우(작업을 만든 뒤 해지)는 기존 `aDeletedSubscriptionEndsTheLoopQuietly…`가 덮는다(CASCADE 뒤 0행 갱신으로 조용히 끝남).

### C2·C3 세부

- 복원 명령 `run()`은 실패하면 완료 로그 없이 예외를 다시 던진다. Spring Boot는 `ApplicationRunner`가 실패하면 실행 실패로 끝내므로 종료 코드가 0이 아니다. 테스트는 `run()`이 던지는지, 출력에 `ERASURE_REAPPLY_COMPLETE`가 없는지, 구독 수가 그대로(2)인지 확인한다. 세 경우는 파일 없음, 디렉터리 경로, 잘린 끝줄이다. 별도 프로세스로 띄워 종료 코드를 재지는 않았다.
- 순서 검증: `syncDirectory`를 덮어쓴 대역 안에서, 같은 파일의 다른 채널로 `tryLock`을 시도한다. `OverlappingFileLockException`이 나면 잠금이 아직 잡혀 있다는 뜻이다. 두 번의 쓰기 모두 `[true, true]`였다(첫 쓰기만이 아니라 매번). 실제 POSIX 디렉터리 force는 Linux CI의 다른 대장 테스트에서 실행된다. 로컬 Windows에서는 건너뛴다. 두 JVM이 동시에 쓰는 실제 경합은 재현하지 않았다.
- 새 운영 절차: 처음 켜기 전에 빈 대장 파일을 만든다. Docker는 없는 호스트 경로를 디렉터리로 만들기 때문이다. [운영 문서](../../docs/ops/alerts.md)의 게이트와 복원 절차, [ADR-0013 개정](../../docs/adr/0013-erasure-journal-host-file.md)에 적었다. 대장 압축·보관 도구는 넣지 않았다.

### C4 세부

| 단계 | 대기 상한 | 다시 재는 시점 |
|---|---|---|
| 요청 제한 카운트(신청·재발송·웹훅 테스트) | 남은 시간 − 정리 몫 2초. `RateLimiter.acquire(…, waitLimit)`이 연결을 받은 뒤 읽는다 | 각 카운트마다 |
| 신청 INSERT, 기존 행 `FOR UPDATE` | 같은 값. 문장마다 다시 건다 | 각 문장 직전 |
| 재발송 조회(`FOR KEY SHARE`), 디스코드 중복 조회(이제 트랜잭션 안) | 같은 값 | 트랜잭션에 들어간 뒤 |
| A: 자격 확인, 이력 | 같은 값. 문장마다 다시 건다 | A에 들어가자마자 남은 시간이 5초 미만이면 시도 없이 `DEADLINE`(정리 몫 안에서 종결) |
| R | 남은 시간 − 2초 − 요청 1회 몫 1초. 연결 B를 받은 **뒤에** 읽는다 | R 직전에 5초 미만이면 예약하지 않는다. R 안에서 몫이 없으면 아무것도 예약하지 않고 롤백한다 |
| R 뒤 `lockReserved`·자격 | 요청 몫이 남았으면 (남은 시간 − 3초), 아니면 2초 | HTTP 직전에 타임아웃을 계산한다. 1초 미만이면 `NOT_SENT` |
| 결과 기록·종결·디스코드 등록 INSERT·`end()` | 정리 몫 2초 | — |

- `boundWaits`는 1ms 미만이면 문장을 시작하지 않고 `QueryTimeoutException("SYNC_BUDGET_SPENT")`를 던진다(1ms 하한 제거, agy M-1).
- 대기 상한에 걸리면(`CannotAcquireLockException`·`QueryTimeoutException`):
  - 발송 루프는 정리 몫 안에서 `DEADLINE`으로 끝낸다. 이미 커밋된 R의 null 시도는 계약대로 결과 모름이므로 `UNCERTAIN`이다.
  - 발송 전 단계는 503 "지금은 요청을 처리하지 못했습니다"로 끝난다. 그 단계의 쓰기는 커밋되지 않는다. 이미 센 요청 제한과 예약은 환불하지 않는다.
  - 다른 DB 오류는 잡지 않는다. 예전처럼 500이거나 정리(5분)로 간다.
- 백오프·마감·`RATE_LIMITED` 판정을 A 안으로 옮겼다. 그래서 종결을 새 연결 없이 A의 연결로 쓴다. `end()`는 지나치게 길어진 sleep이나 중단, 대기 상한에서만 쓴다.
- **예산이 묶지 못하는 것**(문서에서 지우지 않고 명시):
  - 풀 대기: 연결마다 Hikari 5초. 받은 뒤에 다시 잰다.
  - GC·OS 정지.
  - 응답하지 않는 DB.
  - 이때 응답은 25초를 넘을 수 있다. 최악은 대략 연결 하나의 풀 대기에 마지막 쓰기의 풀 대기와 2초를 더한 만큼이다(추정, 측정 아님). [SyncSender](../../src/main/java/kr/gongchae/bell/alerts/SyncSender.java) 주석, [계약 §6](claude-subscriptions-contract.md#6-수치-adr-0014에-반영), [ADR-0014](../../docs/adr/0014-initial-settings.md), [운영 문서](../../docs/ops/alerts.md)에 적었다.
- 검증:
  - 가상 타이머, 트랜잭션 진입 경계(`SlowPool`이 `getTransaction`마다 정해 둔 풀 대기만큼 가상 시간을 민다): `aPoolWaitBeforeEitherTransactionIsMeasured…`.
    - A가 21초 뒤에 연결을 받으면 `FAILED|DEADLINE|0`이다. 1ms 실패나 `SENDING` 방치가 없다.
    - R이 22초 뒤에 연결을 받으면 예약 0, 시도 0, HTTP 0, `FAILED|DEADLINE|0`이다.
  - 가상 타이머, R 앞뒤: `theWholeRequestStays…` 갱신.
    - R 시작이 24초면 예약하지 않는다(수정 전: `NOT_SENT` 시도와 상한 1이 더 쓰였다).
    - R 커밋 뒤 22초가 지나면 `NOT_SENT`이고 `FAILED|DEADLINE|1`이다.
  - 격리 PG의 실제 행 잠금:
    - A의 자격 확인이 다른 트랜잭션의 `FOR UPDATE`를 기다린다(5초 남음, 상한 3초). 테스트 3.18초에 `FAILED|DEADLINE|0`, 메일 0.
    - 요청 제한 행을 다른 트랜잭션이 갱신 중이다(상한 2초). 2.12초에 예외가 나고 카운트가 늘지 않는다. 몫 0이면 문장 없이 `SYNC_BUDGET_SPENT`다.
    - 같은 주소의 커밋 전 신청 INSERT 뒤에서 MockMvc로 신청한다(시작 직후 20초가 지난 것으로 시계를 민다). 3.03초에 503이 나고, 구독·작업 0, 메일 0이다.
  - 같은 값의 `lock_timeout`·`statement_timeout` 중 어느 쪽이 먼저 터지는지는 정해지지 않았다(실측: 요청 제한 테스트에서 `statement_timeout`이 먼저). 두 예외는 똑같이 처리된다.

### C5 세부

- 멈춘 조회(5초)에 타임아웃 300ms를 주면 `BLOCKED_ADDRESS`가 2초 안에 돌아오고, 스텁 서버 요청은 0이다.
- 조회 500ms, 타임아웃 1000ms, 스텁 응답 900ms인 경우: 남은 약 500ms만 HTTP에 쓰므로 `TIMEOUT`이다(전체 1.7초 미만). 수정 전에는 HTTP가 1000ms 전부를 받아 성공했을 경우다.
- `shortcut:` 조회마다 스레드 하나를 쓴다. 버려진 조회는 OS 리졸버 타임아웃에 끝난다. 조회가 늘면 제한된 풀로 바꾼다(코드 주석). 등록 요청은 IP·웹훅 제한(10분 10회, 1시간 3회)과 요청당 3회로 묶여 있다.

## 변이 확인 (고친 결함을 하나씩 되돌려 새 테스트가 잡는지)

로컬에서 결함을 하나씩 되돌리고 해당 테스트만 돌린 뒤 원래대로 복원했다. 마지막에 `git diff`로 복원을 확인했다.

| 되돌린 결함 | 결과 |
|---|---|
| C1 `FOR KEY SHARE` 제거 | 실패: `DataIntegrityViolationException`(INSERT INTO notification…) → 요청 처리 실패. 검수가 지적한 500이 그대로 재현된다 |
| C2 없는 파일을 빈 대장으로 | 실패: 예외가 나야 하는데 나지 않음 |
| C3 디렉터리 force를 잠금 밖으로 | 실패: `[false, false]` (기대 `[true, true]`) |
| C4 R의 상한을 R 전에 계산 | 실패: `FAILED|DEADLINE|1`(기대 `…|0`, 시간이 없는데 예약함) |
| C4 대기 상한 예외를 `DEADLINE`으로 끝내지 않음 | 실패: `QueryTimeoutException`이 호출자까지 올라감 |
| C4 신청 경로의 상한 해제 | **멈춤**: 상한이 없으니 요청이 테스트가 쥔 행을 끝없이 기다렸다. 약 10분 뒤 테스트 JVM을 직접 종료했다(실패로 기록) |
| C5 조회에 타임아웃 없음 | 실패: `NOT_SENT`(기대 `BLOCKED_ADDRESS`), 5초 대기 |
| C6 1일 이상을 null로 | 실패: `[86400] expected 24H but was null` |

## 검증

- 로컬 `./gradlew check bootJar`(코드 커밋 `af62adb` 내용): **138개 통과, 실패 0**(M1 96 + alerts 42. alerts는 이전 31에서 11 늘었다). bootJar가 생성됐다. Docker Desktop을 이 검증 때문에 켰고, 기존 개발 DB 컨테이너는 정지 상태 그대로 두었다.
- Gitleaks 8.30.1: 바뀐 파일과 검수 사본을 검사했다. 누출 없음.
- 원격 CI: 코드 커밋 `af62adb` → run 44099813628, `test`·`secrets` 모두 성공.

## 바뀐 문서

- [계약](claude-subscriptions-contract.md):
  - §2 R의 `waitLimit`.
  - §3 부모 잠금과 `FOR KEY SHARE` 근거.
  - §4 Retry-After 해석과 `BLOCKED_ADDRESS`·`NOT_SENT` 범위.
  - §6 동기 예산(묶는 것과 못 묶는 것).
  - §7 재발송 잠금.
- [ADR-0013 개정](../../docs/adr/0013-erasure-journal-host-file.md): 대장 준비, 파일 없음은 실패, 매번 잠금 안 디렉터리 fsync.
- [ADR-0009 개정](../../docs/adr/0009-webhook-storage-ssrf.md): 주소 확인이 전송 타임아웃을 함께 씀.
- [ADR-0014](../../docs/adr/0014-initial-settings.md): 동기 예산과 Retry-After 상한.
- [운영 문서](../../docs/ops/alerts.md): 대장 준비 게이트, 복원 실패 경우, 503 응답과 풀 대기 한계.

## 이견·한계 (root 재판단용)

- 빈 인자 서술: 위 표대로 보고가 맞았다. 동작은 그대로 두었다.
- 대기 상한에 걸린 뒤 R의 null 시도는 HTTP 전이었어도 `UNCERTAIN`이다. HTTP 전인지 기록하지 않는 보수적 처리이고, 계약(null = 결과 모름)과 같다.
- 디스코드 등록 테스트가 성공한 뒤 마지막 INSERT가 2초 상한에 걸리면 503이다. 테스트 메시지는 도착했지만 등록은 저장되지 않는다. 다시 시도하면 테스트 메시지가 한 번 더 간다.
- 503 "처리하지 못함"과 고정 응답의 차이: 같은 주소로 발송 중인 A가 행을 `FOR SHARE`로 쥐고 있을 때만 생길 수 있는 시간 차이다. 그 대기가 남은 예산(약 23초)을 넘어야 응답이 달라진다. 이론상의 경로로만 남긴다.
- 대장 준비라는 운영 단계가 새로 생겼다. 대신 마운트가 빠지면 시작에서 바로 드러나고, 컨테이너 안에 새 대장이 조용히 생기지 않는다.

## 실사용 전 게이트 (바뀌지 않음)

실제 provider 발송, HTTPS·Firefox·Safari의 Origin, 처리방침 운영 값, 외부 처리와 보관, 해지 대장 운영·복원 리허설(이제 빈 파일 준비 포함), Caddy 로그와 IP 신뢰, 풀 5초의 부하 측정은 별도 미검증 게이트다. #7(outbox, 비동기 알림)은 시작하지 않았다.
