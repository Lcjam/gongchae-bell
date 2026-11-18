# PR #13 (`08cfdac3`) 교차 검수 보고서 · agy

> 작성 일시: 2026-11-16 · 조율: codex · 대상: PR #13 불변 헤드 `08cfdac3de9d6282393f379d062314d5f3440432` (코드 `b0a7ab7ed38e1f5fb9ffcc48aac01013c4c03a51`)
> 대조 파일: `CLAUDE.md`, `collab/reviews/pr-13-codex.md`, `collab/reviews/pr-13-agy.md`, `collab/2026-11-15-m2-readiness/{12,13,14}`, `docs/adr/0013`, `docs/adr/0014`, `docs/ops/alerts.md` 및 `08cfdac3` 불변 소스(`git show`).
> 원칙: 읽기 분석만 수행(구현·테스트·설정·DB·실제 공급자 무변경/무호출).

## 결론 요약

`collab/reviews/pr-13-codex.md`의 **C1~C6 분석을 불변 소스 `08cfdac3`과 실행 순서로 재검증한 결과, C2·C3·C4·C5(및 C1·C6) 모두 실제 경합·대기 결함으로 확인됐다.**
기존 [pr-13-agy.md](../reviews/pr-13-agy.md)에서 M-1만 필수 수정으로 보고 C2("운영 유의"), C3("충족"), C4 선행 경로(미탐지), C5("TOCTOU 한계만 명시"), R-1/R-2("권고")로 분류했던 판정은 **오판·과소평가**였으므로, [pr-13-agy.md §5](../reviews/pr-13-agy.md#5-교차-검수-반영-및-판정-정정-2026-11-16--pr-13-codexmd-대조)에 **"수정 후 재검수 (C1~C6 전건 필수 수정 수용)"**로 명시 정정했다.

---

## 항목별 비판적 재검증

### 1. C2 · 복원 대장 파일 부재(`Files.notExists`) → 성공 0 처리 (`ErasureJournal.java:66-68`, `ErasureReapplyCommand.java:46-55`)

- **재검증 내용**:
  - `ErasureJournal.readStrict()`(`68`행)는 `if (Files.notExists(path)) return List.of();`를 반환하고, `ErasureReapplyCommand.reapply()`(`47-54`행)는 예외 없이 `deleted=0`을 반환해 `run()`(`38-39`행)이 `ERASURE_REAPPLY_COMPLETE deleted=0`을 로그로 남기고 정상 종료(exit 0)한다.
  - `ErasureJournalTest.java:58` 역시 `missing.log`에 대해 `isEmpty()`를 기대하고 있으나, 이는 구현자의 단위 테스트 가정일 뿐 ADR-0013(`docs/adr/0013-erasure-journal-host-file.md`)이나 계약(`12`/`13`/`14`)의 요구사항이 아니다.
- **운영자 수동 확인만으로 fail closed라 볼 수 있는지 판단**:
  - **불가(C2 동의).** ADR-0013과 `ErasureReapplyCommand.java:18-20`이 표방하는 fail closed는 "손상·불신뢰 상태에서 삭제·재가동을 막아 해지자 부활을 차단"하는 안전장치다. DB 복구 장애 대응 중 `ALERTS_ERASURE_LOG` 환경변수 오타나 복원 컨테이너 바인드 마운트 누락은 가장 흔한 운영 실수인데, `deleted=0` + exit 0은 복원 스크립트가 정상 통과한 것처럼 오인시켜 해지된 구독이 `ACTIVE`로 복구되는 사고로 바로 이어진다.
- **수정 시 계약·운영 유의점**:
  - `ErasureJournal.append` (`49`행 `CREATE`)는 첫 해지 요청이 들어오기 전까지 파일을 만들지 않으므로, "해지 이력이 0건인 초기 운영 상태의 복원"을 위해 **0바이트 빈 파일은 유효한 빈 대장으로 허용**하고 **파일 자체가 존재하지 않는 경우(`Files.notExists`)는 복원 실패(`CorruptJournal`/예외)로 중단**하는 C2의 구분이 정확하다.
  - 아울러 운영 절차(`docs/ops/alerts.md`) 및/또는 `ALERTS_ENABLED=true` 기동 시 빈 대장 파일 초기화(0바이트 생성)를 맞추면 첫 해지 전 복원에서도 오탐 실패가 나지 않는다.

### 2. C3 · 첫 파일 생성의 `forceDirectory`가 `FileLock` 바깥에 위치 (`ErasureJournal.java:47-62`)

- **재검증 실행 순서**:
  `ErasureJournal.java:48-61`:
  ```java
  boolean created = Files.notExists(path); // 48행 (락 밖)
  try (FileChannel channel = FileChannel.open(path, CREATE, READ, WRITE);
       FileLock lock = channel.lock()) {   // 49행 (OS 락 진입)
      ...
      channel.force(true);                 // 59행 (파일 데이터/메타데이터만 fsync)
  }                                        // 60행 (channel/FileLock 닫히며 OS 락 해제)
  if (created) forceDirectory(path.toAbsolutePath().getParent()); // 61행 (락 밖)
  ```
- **경합 재현 (서로 다른 JVM: blue / green)**:
  1. 최초 상태에서 **JVM A**가 `48`행을 지나 `created=true`를 얻고 `49`행 `FileChannel.open(..., CREATE)`로 VFS 디렉터리 엔트리를 생성(아직 디스크에 fsync되지 않음)한 뒤 `channel.lock()`을 잡는다.
  2. **JVM B**가 `48`행을 실행하면 A가 이미 VFS 엔트리를 만들었으므로 `Files.notExists(path)`는 **`false` (`created=false`)**다. B는 `channel.open` 후 `49`행 `channel.lock()`에서 대기한다.
  3. A가 줄을 쓰고 `59`행 `channel.force(true)`를 마친 뒤 `60`행에서 `try-with-resources`를 빠져나와 **OS `FileLock`을 해제**한다.
  4. A가 `61`행 `forceDirectory()` 호출 직전에 CPU 선점·지연되거나 비정상 종료하면, 기다리던 **JVM B가 즉시 OS 락을 획득**한다.
  5. B는 자기 줄을 쓰고 `59`행 `channel.force(true)`(파일만) 실행 뒤 `created == false`이므로 **`forceDirectory()` 없이 `append()`를 정상 반환**하고, `SubscriptionService.unsubscribe:247`의 **`DELETE FROM subscription` DB 트랜잭션을 커밋**한다.
  6. 또한 과거 프로세스가 `open(CREATE)` 직후 0바이트 파일만 남기고 죽은 경우에도 다음 호출부터 `created=false`가 되어 디렉터리 엔트리가 끝내 fsync되지 않을 수 있다.
- **판단**:
  - **C3에 전적으로 동의한다.** "잠금 전 관측(`Files.notExists`)으로 생략하지 않고 OS `FileLock` 안에서 매번 `forceDirectory`를 호출한다"는 root의 해법이 위 두 시나리오(선점·추월 및 이전 크래시 후 0바이트 잔존)를 모두 깔끔하게 닫는다.

### 3. C4 · 선행 `rate_limit` / 가입·복구·Discord TX 및 `SyncSender.end()`의 25초 대기 예산 누락 (`SubscriptionService.java`, `RateLimiter.java`, `SyncSender.java`)

- **재검증 내용 (`08cfdac3` 소스 기준)**:
  - `SyncSender.java:15-19` Javadoc과 계약 `13-sol-revised-review.md:46` / `14-codex-implementation-decision.md:12`는 **신청 TX·connection/lock/statement 대기·R·HTTP·backoff·최종 정리** 전체를 25초 예산에 포함한다고 명시했다.
  - 그러나 실제 `08cfdac3` 코드에서는 `SyncSender.deliverMail` 내부의 `A`(`76`행)·`R`(`88`행)만 `DeliveryLedger.boundWaits()`를 부를 뿐, 다음 경로들은 **`lock_timeout`/`statement_timeout`이 전혀 설정되지 않는다**(`hikari.connection-timeout: 5000`은 커넥션 풀 대기만 제한하며, 일단 커넥션을 잡으면 PostgreSQL 행 잠금·SQL 대기는 무제한):
    1. `SubscriptionService.subscribeEmail`: `limits.acquire`(`113-114`행, `REQUIRES_NEW` 2회), `signupEmail` TX(`119`행, `INSERT ... ON CONFLICT`·`SELECT ... FOR UPDATE`).
    2. `SubscriptionService.recoverEmail`: `limits.acquire`(`269-270`행, 2회), `tx.execute`(`274-277`행).
    3. `SubscriptionService.registerDiscord`: `limits.acquire`(`327-330`행, 4회), `testWebhook` 내부 `limits.acquire(SEND_TOTAL)`(`SyncSender.java:145`), 성공 후 저장 `tx.execute`(`340-364`행).
    4. `SyncSender.end`(`128-130`행): `tx.executeWithoutResult(status -> ledger.finish(...))` — `boundWaits` 미적용.
  - 더불어 기존 `pr-13-agy.md` M-1에서 지적한 `SyncSender.deliverMail:74-96`의 버그(`left(start) <= CLEANUP(2s)`일 때 `boundWaits`가 `1ms`로 떨어져 `stillEligible`·`finish`가 timeout 예외를 내고 `SENDING`으로 방치, 그리고 `< MIN_HTTP + CLEANUP (3s)`일 때 `R`을 호출해 불필요하게 `SEND_TOTAL`과 `NOT_SENT` 시도를 소모함)도 `SyncSender.testWebhook:145-147`(`acquire` 후 `timeout < MIN_HTTP` 검사)과 함께 같은 원인이다.
- **판단 및 수정 경계 유의점**:
  - **C4에 동의한다.** 단, 수정 시 `SyncSender.finish`/`end` 등 **종결 정리 SQL**에는 `1ms`로 굶기지 않도록 최소 정리 시간(`CLEANUP` 또는 `boundWaits` 하한)을 남겨야 하며, 반대로 전송 전 경로(`acquire`, `signupEmail`, `R`)는 남은 예산(`left - CLEANUP`)이 부족하면 즉시 중단해야 한다(계약 13-4). 또한 `subscribeEmail`/`recoverEmail`에서 선행 `RateLimiter`/`signupEmail`이 잠금 대기로 25초 예산 초과 실패할 때, 이미 `notification`이 커밋되기 전인지 커밋 후인지에 따라 고정 응답(또는 503)이 일관되게 처리되는지 확인이 필요하다.

### 4. C5 · Discord `AddressGuard` 동기 DNS 조회가 전송 `timeout` 예산 밖 (`HttpTransports.java:120-124`, `AddressGuard.java:18-24`)

- **재검증 내용**:
  - `HttpTransports.Discord.send`(`HttpTransports.java:120-124`):
    ```java
    if (!guard.allows(url.host())) return SendOutcome.Result.of(SendOutcome.BLOCKED_ADDRESS); // 121행
    HttpRequest request = HttpRequest.newBuilder(target.apply(url)).timeout(timeout)...       // 122행
    Exchange exchange = exchange(http, request, timeout);                                    // 124행
    ```
  - `AddressGuard.allows` (`18-24`행)는 동기 `InetAddress.getAllByName(host)`를 호출하며 타임아웃이 없다(리눅스 glibc 리졸버 기본 재시도 시 수십 초 블로킹 가능).
  - `guard.allows`가 예컨대 12초 걸려도 `122`·`124`행은 남은 시간을 차감하지 않고 원래 `timeout`(최대 10초) 전체를 그대로 `exchange()`에 다시 넘긴다. 한 시도에서 `12s + 10s = 22s`가 소요될 수 있어 `SyncSender.testWebhook`의 `HTTP_MAX(10s)` 및 전체 `25s` 예산을 초과한다.
- **판단 및 구현 유의점**:
  - **C5에 동의한다.** ADR-0009의 "호스트 고정을 전제로 한 DNS 재조회(TOCTOU) 수용"과 별개로, **지연 대기 시간 제한(계약 13-4·14-4)** 관점에서 `guard.allows` + `exchange`는 전달된 단일 `timeout`을 공유하고 경과 시간을 차감해야 한다.
  - **중요 분류 조건**: DNS 단계에서 `timeout`을 소진하거나 실패해 **HTTP 요청(`exchange`)을 아예 시작하지 못한 경우**에는 `SendOutcome.TIMEOUT`(`Kind.UNKNOWN`)을 반환하면 안 된다(HTTP를 보낸 적이 없는데 `UNCERTAIN`으로 분류됨 — 특히 후속 `#7`에서 오판 유발). ADR-0009("조회 실패 차단")·계약 12 §4에 맞게 `BLOCKED_ADDRESS` 또는 `CONNECT_FAILED`/`NOT_SENT` 등 **확실한 비전송 결과**로 끝내야 한다.
