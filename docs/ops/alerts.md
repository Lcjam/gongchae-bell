# 알림 구독 및 발송 운영 (#6·#7)

알림 구독(신청·확인·관리·해지·복구), 요청 안 발송(#6), 공고 매칭·outbox·일일 묶음·비동기 발송 워커(#7)를 다룬다. 구현 계약은 [#6 계약](../../collab/2026-11-16-implementation/claude-subscriptions-contract.md)과 [알림 자격 결정](../../collab/2026-11-17-m2-readiness/03-codex-alert-eligibility-decision.md)에 있다.

## 켜기 전에 닫을 게이트

`ALERTS_ENABLED`의 기본값은 `false`다. 꺼져 있으면 신청 화면은 준비 중 안내를 보이고 모든 구독 POST는 503이다. 개인정보도 받지 않는다. 켜면 아래 설정이 모두 있어야 앱이 시작한다. 설정이 다 있다고 해서 법적·운영적으로 준비됐다는 뜻은 아니다. **실제 개인정보를 받기 전에** 다음을 확인하고 기록한다([출처 검토](../../collab/2026-11-16-m2-privacy/sol-source-review.md)).

- 개인정보처리방침 값: 담당(성명 또는 부서), 연락처, 버전과 시행일, 앱·DB 호스팅 위치, 국외 이전 근거와 거부 방법, Resend 실제 계정 기준 보관 기간. 짐작한 값이나 자리표시자로 채우지 않는다.
- Resend: 실제 계정의 DPA, 발신 도메인 SPF·DKIM·DMARC, 열람·클릭 추적 꺼짐. 같은 계정에 다른 송수신이 없어야 공유 상한 90이 무료 한도 100 안에 든다.
- Discord: 국외 처리와 법적 관계(위탁인지, 독립 처리자인지)를 확정한다.
- 브라우저: `Referrer-Policy: no-referrer` 페이지의 POST가 보내는 `Origin`을 실제 HTTPS와 Firefox·Safari에서 확인한다(지금은 Chromium 루프백 관측뿐이다).
- 프록시: Caddy만 `X-Forwarded-For`를 넘기고, 외부 요청이 그 헤더를 직접 넣지 못한다. 접근 로그에서 쿼리 `t`를 지운다.
- 해지 대장: 처음 켜기 전에 준비한다. 준비는 감독 정비 작업(아직 미구현)이 아래 파일 도구 `init`으로 하며, 그 작업이 병합·검수되기 전에는 운영에서 만들지 않는다. 빈 파일을 손으로 만들지 않는다(`init`이 거부한다). 앱은 대장을 만들지 않는다. 알림을 켰을 때 그 경로에 파일이 없으면 `ALERTS_ERASURE_LOG_MISSING`으로 시작하지 않는다. 그 호스트 파일이 blue·green 두 컨테이너에 같은 파일로 바인드되고 restic 백업에 들어가는지 확인한다. Docker는 없는 호스트 경로를 디렉터리로 만들기 때문에, 파일이 먼저 있어야 한다. 대장 정리 시점은 아래 "해지 대장"을 본다.
- 백업 사본(OCI·NAS·PC)이 실제로 14일 뒤 지워지는지 확인한다.

## 설정 (값은 환경변수·GitHub Secrets에만)

| 이름 | 내용 |
|---|---|
| `ALERTS_ENABLED` | `true`일 때만 받고 보낸다 |
| `ALERTS_TOKEN_KEY` | 관리·해지 HMAC 키. base64, 32바이트 이상 |
| `ALERTS_HMAC_KEY` | 목적지·요청 제한 HMAC 키. 위와 다른 값, base64, 32바이트 이상 |
| `ALERTS_WEBHOOK_KEY`, `ALERTS_WEBHOOK_KEY_ID` | 웹훅 URL AES-256-GCM 키(base64, 정확히 32바이트)와 그 ID(`[A-Za-z0-9_-]{1,40}`) |
| `ALERTS_ERASURE_LOG` | 해지 대장 파일 경로. DB 볼륨과 다른 호스트 디렉터리. 켜기 전에 감독 작업이 `erasure-journal init`으로 준비한다 |
| `RESEND_API_KEY`, `ALERTS_MAIL_FROM` | Resend API 키, 발신 주소(인증된 도메인) |
| `HEARTBEAT_DIGEST_URL` | 일일 이메일 묶음(`DIGEST`) 슬롯 선점 완료 후 GET 핑 URL (선택, 비어 있으면 끔) |
| `ALERTS_WORKER_CONCURRENCY`, `ALERTS_BATCH_SIZE`, `ALERTS_SCHEDULER_ENABLED` | 비동기 워커 동시성(기본 `2`, 최대 `4`), 1회 선점 배치(기본 `20`), 스케줄러 켜기(기본 `true`) |
| `PRIVACY_OFFICER`, `PRIVACY_CONTACT`, `PRIVACY_POLICY_VERSION`, `PRIVACY_EFFECTIVE_DATE`, `PRIVACY_HOSTING_LOCATION`, `PRIVACY_OVERSEAS_BASIS`, `PRIVACY_RESEND_RETENTION` | 처리방침에 그대로 보이는 값. 동의 기록의 `consent_version`은 `PRIVACY_POLICY_VERSION`이다 |

키는 `openssl rand -base64 32`처럼 만들어 비밀번호 관리자와 서버 환경변수에만 둔다. 키를 잃으면 이렇게 된다:
- 토큰 키: 이미 보낸 관리·해지 링크가 모두 무효가 된다.
- 웹훅 키: 디스코드 구독을 다시 등록해야 한다(ADR-0009).

## 키 교체

- 관리·해지 토큰 키: 새 키로 바꾼 뒤 `UPDATE subscription SET token_version = token_version + 1`. 옛 링크는 모두 무효가 된다. 이메일 구독자는 다음 메일이나 '관리 링크 다시 받기'로 새 링크를 받는다.
- 웹훅 키: 지금은 키 ID 하나만 읽는다. 키를 바꾸면 기존 디스코드 구독을 복호화하지 못한다. 재암호화 도구는 아직 없다(필요하면 후속 이슈).

## 요청 제한과 상한 (ADR-0014)

| 항목 | 값 |
|---|---|
| 신청(이메일·디스코드)·관리 링크 재발송·웹훅 테스트 | IP당 10분 10회, 대상(이메일·웹훅)당 1시간 3회 |
| 토큰 실패 | IP당 10분 30회. 넘으면 그 IP의 토큰 요청은 검사 없이 429 |
| 발송 상한 | 이메일 하루 90통(UTC 날짜), 디스코드 시간당 300회. 시도마다 예약하며 환불하지 않는다 |
| 요청 안 발송 | 조건 조회 전부터 요청당 최대 3회 이하, 25초 예산. 정상적인 풀·잠금·문장 대기를 포함한다. 연결 진입 전에 Hikari 상한(현재 5초), 일반 단계에서는 추가로 최종 연결 5초와 정리 SQL 전체 2초를 확보한다. 각 SQL 직전 남은 몫을 다시 계산한다. 발송 전 예산 소진은 503 "지금은 요청을 처리하지 못했습니다"다. 풀 예약으로 실제 재시도 수가 줄 수 있다. GC·OS 정지·DB 불응 때의 물리적 상한과 기록 실패 시 durable 정리 경로는 [계약 §6](../../collab/2026-11-16-implementation/claude-subscriptions-contract.md#6-수치-adr-0014에-반영)에 적었다 |

## 보존과 정리

`AlertsMaintenance`가 5분마다(`alerts.maintenance-interval`) 두 앱에서 같은 문장을 돌린다. 문장은 멱등이다.

| 대상 | 규칙 | 최장 잔존(앱이 도는 동안) |
|---|---|---|
| 미확인 구독 | 확인 링크 만료(24시간) 뒤 삭제 | 24시간 + 5분 |
| 비활성 구독 | `disabled_at` 30일 뒤 삭제 | 30일 + 5분 |
| 발송 작업·시도 | 종결 30일 뒤 삭제 | 30일 + 5분 |
| 요청 제한 카운터 | 창이 끝나면 삭제 | IP 키 15분, 대상 키 1시간 5분 |
| 해지 | 요청 즉시 대장 기록 → 삭제 | 즉시(백업·공급자 사본은 별도) |

앱이 멈춰 있던 동안의 정리는 다시 시작한 뒤에 한다. 미확인 만료와 비활성 삭제는 해지 대장에 쓰지 않는다.

## 해지 대장과 복원 (ADR-0013)

- 해지하면 구독 행 잠금 아래에서 `<구독 UUID>\t<UTC 시각>` 한 줄을 쓰고, 파일과 디렉터리를 fsync한다. 디렉터리 fsync는 POSIX에서만 하며, 매번 파일 잠금을 쥔 채로 한다. 그 뒤에 행을 지운다. 대장 쓰기가 실패하면(파일이 없거나 fsync 실패) 해지 요청이 실패하고 아무것도 지우지 않는다.
- **파일 도구** `java -jar gongchae-bell.jar erasure-journal <명령> <파일>` (#17, [ADR-0013](../adr/0013-erasure-journal-host-file.md)). `main`의 첫 인자로 갈라져 Spring·DB 없이 돈다. 파서와 표지 규칙은 앱과 같은 코드다.
  - `version`은 `ERASURE_TOOL v1`을 출력한다. `check`는 잠금 없이 읽기만 하는 strict 검사다(읽기 전용 사본도 된다). 문법만 보며 디스크 반영은 보장하지 않는다.
  - `verify`는 대장 L을 앱과 같은 OS 잠금 아래 strict 검사하고, 소유자·그룹·권한 계약을 확인하고, 파일과 디렉터리를 fsync한다.
  - `init`은 파일이 없을 때만 새 표지 한 줄짜리 파일을 만든다(`0640`, 디렉터리와 같은 소유자·그룹). 이미 표지 한 줄이면 `ERASURE_INIT_ALREADY`다. 빈 파일, 다른 내용, 손상된 파일은 거부한다.
  - `mark`는 마지막 줄이 해지 줄일 때 새 표지 한 줄을 붙인다. 기존 바이트·소유자·그룹·권한은 그대로다. 마지막 줄이 표지면 `ERASURE_MARK_NOOP`이다. 없거나 빈 대장은 거부한다(첫 표지는 `init`의 일이다).
  - 쓰기 순서: 같은 디렉터리의 임시 파일에 전부 쓰기 → 다시 읽어 strict 검사 → 권한·소유자·그룹 확인 → 파일 fsync → 게시(`init`은 기존 파일을 덮지 않는 `link`, `mark`는 `rename`) → 디렉터리 fsync → 다시 열어 바이트 확인. NOOP·ALREADY·`verify`도 파일·디렉터리 fsync와 권한 확인을 한다. 디렉터리 fsync가 실패한 게시는 같은 명령을 다시 실행하면 마저 끝난다. 고른 임시 파일 이름이 이미 있으면 그 파일은 건드리지 않고 `ERASURE_TOOL_TEMP_EXISTS`로 멈춘다. 도구가 지우는 임시 파일은 그 실행이 직접 만든 것뿐이다. 남은 임시 파일은 `verify`의 `temps=`로 보고만 한다.
  - 출력은 고정 코드와 숫자 한 줄뿐이다. UUID, 대장 내용, 경로, 환경값, 예외 원문은 출력하지 않는다. 종료 코드는 `0` 완료(NOOP·ALREADY 포함), `2` 사용법, `3` 거부(도구가 파일을 바꾸지 않음), `4` I/O 실패다. `…_UNCONFIRMED`는 새 파일은 게시됐지만 디스크 반영을 확인하지 못했다는 뜻이며, 같은 명령을 다시 실행한다. 도구가 잡는 잠금은 게시 뒤 다시 읽을 때까지 모두 한 번만 시도하고 기다리지 않는다. 처음 잠금이 잡혀 있으면 `ERASURE_TOOL_LOCK_BUSY`(파일 불변), 게시 뒤 다시 읽을 때 잡혀 있으면 `…_UNCONFIRMED`다.
  - Linux POSIX 전용이다. 파일의 소유자·그룹은 디렉터리와 같아야 하고 모드는 `0600`이나 `0640`이어야 한다. 새 파일을 게시할 때(`init`의 새 파일, `mark`의 표지 추가) 만든 임시 파일의 소유자·그룹이 맞지 않으면 게시 전에 `ERASURE_TOOL_OWNER_MISMATCH`·`ERASURE_TOOL_GROUP_MISMATCH`로 거부한다. 예를 들어 앱 소유 대장에 root가 `mark`하면 거부된다. 실행한 사용자 자체를 거부하는 검사는 아니므로 root의 `verify`·NOOP·ALREADY는 이 검사에 걸리지 않는다. UID 1000으로 실행하는 일은 감독 작업의 게이트다.
  - **하지 않는 일**: 앱과 수동 writer의 정지, DB 연결 0, 보존 백업 사본 확인. 옛 파일에 쥔 잠금으로 교체 뒤의 writer를 막지도 못한다. 이 조건은 다음 감독 작업이 맡는다. **감독 작업이 병합·검수되기 전에는 운영에서 `init`·`mark`를 실행하지 않는다.**
- 복원 (전체 단계와 복사·정리 명령은 [백업과 복구 §7](backup-restore.md#7-격리-복원-리허설-및-장애-복구-절차-adr-0012--adr-0013--fail-closed)):
  1. **운영 DB에 직접 덮어쓰지 않고** 전용 일회용 `postgres:17` 격리 DB에 `ALERTS_ENABLED=false`, `COLLECTOR_ENABLED=false`, 외부 연결 차단(`--internal`) 상태로 덤프를 복원한다. (운영 `/opt/gongchae-bell/.env`를 통째로 `source`하지 않고, 이미지·태그는 `APP_IMAGE` 1줄 추출 + 배포 상태 파일에서 읽는다.)
  2. OCI 호스트에는 Java나 `/app/gongchae-bell.jar`가 없으므로 **앱 컨테이너(`-u 1000:1000`)로만** 실행한다. 적용할 대장 L 1개만 담은 `$RESTORE_WORK/reapply`(`sudo install`로 `1000:1000` `0700`/`0600`)를 `:rw`로, 복원한 덤프와 같은 snapshot의 동봉 대장 B 1개만 담은 `$RESTORE_WORK/bundle`(`0500`/`0400`)을 `:ro`로 마운트해 격리 DB에 재적용한다(덤프·메타파일은 마운트하지 않는다):
     ```bash
     docker run --rm --network "<격리-internal-net>" \
       -u 1000:1000 \
       -v "$RESTORE_WORK/reapply:/restore:rw" \
       -v "$RESTORE_WORK/bundle:/bundle:ro" \
       -e DB_URL="jdbc:postgresql://<격리-pg>:5432/<격리-db>" \
       -e DB_USER="<격리-user>" \
       -e DB_PASSWORD="<격리-pw>" \
       -e ALERTS_ENABLED=false \
       -e COLLECTOR_ENABLED=false \
       -e HEARTBEAT_COLLECTOR_URL= \
       -e ALERTS_ERASURE_LOG=/restore/erasure.log \
       "${APP_IMAGE}:${TARGET_TAG}" \
       --spring.main.web-application-type=none \
       --alerts.erasure-reapply=true \
       --alerts.erasure-bundle-log=/bundle/erasure.log
     ```
     - **coverage 게이트**: 명령은 L과 B를 같은 strict 파서로 읽고, B가 L에 덮이는지 확인한 **뒤에만** DB 행을 지운다. L 첫 줄이 표지가 아니면 B 전체가 L의 바이트 앞부분이어야 하고, L 첫 줄이 표지 H이면 B에 H가 있고 B의 H부터 끝까지가 L의 앞부분이어야 한다. 표지 줄은 구독 ID가 아니므로 지우지 않는다. 규칙과 한계는 [ADR-0013 개정 2026-11-20](../adr/0013-erasure-journal-host-file.md)에 있다.
     - **대장 출처 선택**: 호스트가 살아 있는 사고나 리허설에서는 **살아 있는 최신 호스트 대장(`/var/lib/gongchae-bell/erasure/erasure.log`)**을 사용하고(리허설 시에는 운영 앱과 잠금 간섭을 피하기 위해 사본으로 검증), **절대 백업 안의 과거 사본으로 호스트 대장을 덮어쓰지 않는다.** VM·호스트 자체를 잃었을 때만 사용 가능한 백업(OCI 또는 NAS/PC 사본) 중 대장이 포함된 **가장 최신** snapshot의 `/erasure.log`만 별도 디렉터리에 추출해 사용한다.
     - L은 기존 OS 잠금(`FileChannel.lock()`) 때문에 쓰기 가능(`READ, WRITE`, `1000:1000`, `0600`) 열기가 필요하므로 `:rw`로 둔다. B는 잠금 없이 읽으므로 `:ro`로 충분하다.
     - 로그 `ERASURE_COVERAGE_VERIFIED rule=…`(게이트 통과)와 `ERASURE_REAPPLY_COMPLETE deleted=N`(삭제 완료), 종료 코드 `0`을 모두 확인한다. 앞의 것이 없는 `COMPLETE`는 게이트 없는 옛 이미지의 결과이므로 통과로 보지 않는다.
  3. **사후 검증·전환 직전 재적용·발송 재개 후속 게이트**:
     - 격리 DB의 `subscription` 테이블에 대장 UUID가 `0`건 남았는지 SQL로 직접 대조한다.
     - **실제 장애 복구 전환 시에는** 리허설 사본 재적용 이후 전환 직전까지 웹(`/subscriptions/unsubscribe*`)을 통해 들어온 추가 해지를 반영하기 위해, `app-blue`·`app-green` **모든 운영 앱 컨테이너를 중지한 뒤** `/var/lib/gongchae-bell/erasure:/erasure:rw`를 직접 마운트해 멱등 재적용과 0건 대조를 1회 더 통과해야 한다.
     - 실제 발송 재개(`ALERTS_ENABLED=true`) 전에는 호스트 대장 보존·배치(`1000:1000`, `0600`), 키 정합성(웹훅 키 ID·HMAC·토큰 키), 실운영 DB 전환 완료, 정리(`sudo rm -rf "$RESTORE_WORK"`)를 별도로 확인한다.
- 아래 경우 명령은 실패한다. 완료 로그가 없고, 종료 코드가 0이 아니며, 아무것도 지우지 않는다. 운영자가 원인을 확인한 뒤 다시 실행한다. 그동안 알림을 켜지 않으며 **missing·손상 대장을 빈 대장으로 대체해 통과시키지 않는다.** (단, 복원 DB `subscription` 행 수 `0` **AND** `backup-meta.txt` `erasure_included=false` **AND** M2 미활성 이력이 모두 확인되는 M1 전용 백업만 대장 재적용을 건너뛰며, 대장 파일이 존재하면 `0`건이어도 손상 검사를 건너뛰지 않는다.)
  - 경로에 파일이 없음(경로 오타, 대장을 되살리지 않음, 디렉터리를 가리킴): `ERASURE_LOG_MISSING`. 빈 대장으로 보지 않는다. 해지가 한 번도 없었던 경우에도 준비해 둔 대장 파일이 백업에 있어야 한다.
  - 대장에 잘리거나 형식이 틀린 줄: `ERASURE_LOG_TORN_LAST_LINE` 또는 `ERASURE_LOG_BAD_LINE_<줄 번호>`.
  - B 인자가 없거나 B 파일이 없음: `ERASURE_BUNDLE_REQUIRED`, `ERASURE_BUNDLE_MISSING`. B의 잘린·틀린 줄은 `ERASURE_BUNDLE_TORN_LAST_LINE`, `ERASURE_BUNDLE_BAD_LINE_<줄 번호>`.
  - 같은 표지가 한 파일에 두 번: `ERASURE_LOG_DUPLICATE_MARKER`, `ERASURE_BUNDLE_DUPLICATE_MARKER`.
  - B가 L에 덮이지 않음(순서 역전, B가 L보다 김, L이 비어 다시 만들어짐, L의 첫 표지 이전 덤프): `ERASURE_COVERAGE_NOT_PREFIX`, `ERASURE_COVERAGE_MARKER_ABSENT`.
  - 오류 메시지는 고정 코드와 줄 번호뿐이며 UUID나 대장 내용을 담지 않는다.
- 정리: 자동 정리는 없다. 해지 시각 이전에 만든 모든 백업 사본의 삭제가 확인된 뒤에만 그 줄을 지울 수 있다. 파일을 다시 쓰는 정리는 두 앱을 멈춘 상태에서 한다. 사본 삭제를 확인할 방법과 주기를 정하기 전까지 대장은 정리하지 않는다(처리방침에도 '미정'으로 표시).

## 발송 기록·비동기 워커·지표 (#7)

- **매칭 및 묶음 생성**:
  - **정기 신규 (`DISCORD_NEW`)**: 정기 수집(`PostingIngestor.applyRegular`) 트랜잭션 안에서 처음 INSERT된 `OPEN`(`end_date >= kstToday`) 공고만 outbox에 적재한다. backfill·변경·`CLOSED`/`CHECK`·재등장은 제외하며 수집 롤백 시 함께 롤백된다.
  - **이메일 묶음 (`EMAIL_DIGEST`)**: 매일 `08:00 KST` 이후 매분 만회 검사로 `collect_run(DIGEST, 날짜)`를 선점한 앱 1대만 생성한다. `confirmed_at` 이후 non-backfill 미발송 `NEW`와 실행일 기준 `end_date = kstToday + 3` `DEADLINE`을 구독당 1통으로 합본하고 완료 커밋 뒤 `digestHeartbeat.ping()`을 보낸다.
  - **디스코드 마감 임박 (`DISCORD_DEADLINE`)**: 매일 `09:00 KST` 이후 `collect_run(DEADLINE, 날짜)` 선점 앱이 실행일 기준 `D-3`을 생성한다.
- **비동기 워커 (`AlertWorker` / `AlertDispatcher`)**:
  - 기본 동시성은 `2`(배치 `20`, 폴링 `5s`, 최대 `4`)로, 워커당 `TX2`(`A`: 구독 `FOR SHARE`) + `R`(`B`: `REQUIRES_NEW`) 최대 `4`개 연결만 사용해 기본 Hikari 풀 `10`개에서 연결 고갈을 막는다.
  - `R` 전·후 KST 현재 자격(`(sn, kind)` 집합)과 결정적 `payload_sha256`을 재검증한다. `UNKNOWN` 이후 대상·본문·`token_version` 변화 시 새 `R`/HTTP 없이 `UNCERTAIN`으로 끝내며 item을 보존한다. `FAILED`/`CANCELLED`로 끝난 작업의 item만 반환(삭제)한다.
  - Discord `404`만 `TX2` 커밋 뒤 별도 짧은 `TX3`에서 `DISABLED`(`disabled_reason='WEBHOOK_404'`)로 전환하고 구독 관리 화면(`/subscriptions/manage`)에 사유를 표시한다. `429`는 `retry_after` 이후 `RETRY`, `5xx`/연결 실패는 재시도만 한다.
  - `EMAIL_DIGEST`는 RFC 8058 `List-Unsubscribe` / `List-Unsubscribe-Post` 헤더를 포함하며, Discord는 `allowed_mentions: {"parse": []}`와 `2000`자 제한을 적용한다.
- `notification`의 상태와 `last_error`, `notification_attempt.outcome`을 함께 본다. `UNCERTAIN`은 공급자가 처리했을 수도 있다는 뜻이다(5xx·타임아웃·종료). 재발송하지 않는다.
- `SEND_CAP`은 우리 상한에 걸린 것이다(HTTP 없음). `QUOTA_DAILY`는 Resend 일 한도에 걸린 것이다(HTTP 있음). `REQUEST_4XX`·`ACCOUNT_4XX`는 요청이나 계정 설정 문제다. 셋 다 구독을 끄지 않는다.
- **노출 지표 (`/actuator/prometheus`, 고정 라벨만 사용, PII 없음)**:
  - `alerts_notification_backlog`: `PENDING`·`RETRY` 상태에서 `next_attempt_at`이 5분 이상 지난 비동기 작업 수(게이지)
  - `alerts_notification_sending_stuck`: `SENDING` 상태에서 `claimed_at`이 5분을 넘긴 작업 수(게이지)
  - `alerts_notification_failed_last1h{kind}`, `alerts_notification_uncertain_last24h{kind}`, `alerts_notification_sent_last24h{kind}`, `alerts_notification_duplicate_risk_last24h{kind}`: 최근 1시간/24시간 종결 작업 수(게이지, 6종 `kind`)
  - `alerts_subscriptions_active{channel}`, `alerts_subscriptions_disabled{channel}`, `alerts_send_total_used{provider}`: 채널별 구독 수·일/시간 발송 상한 사용량(게이지)
  - `alerts_delivery_attempts_total{kind,outcome}`, `alerts_delivery_duplicate_risk_total{kind}`: 발송 시도 결과 및 `UNKNOWN` 경유 후 `SENT` 누적 카운터
  - `alerts_delivery_delay_max_seconds`: 현재 미완료 비동기 작업(`PENDING`·`RETRY`·`SENDING`, 대상 `EMAIL_DIGEST`·`DISCORD_NEW`·`DISCORD_DEADLINE`) 중 가장 오래된 작업의 `notification.created_at` 기준 대기 경과 초(게이지, 없으면 `0`)
  - `alerts_delivery_latency_seconds{kind}`: **작업 생성(`notification.created_at`) → 발송 성공(`SENT`) 완료 지연**(타이머). 집계 단위·분모는 성공 발송된 비동기 작업 1건이며, 대상 `kind`는 `EMAIL_DIGEST`·`DISCORD_NEW`·`DISCORD_DEADLINE`이다. 이메일 묶음(`EMAIL_DIGEST`)은 08:00 KST(또는 만회) 작업 생성 이후 발송까지 구간만 재며 묶음 전 대기는 포함하지 않는다.
  - `alerts_delivery_collection_latency_seconds{kind}`: **공고 수집 반영(`posting.first_seen_at`) → 발송 성공(`SENT`) 완료 지연**(PRD G7 타이머). 집계 단위·분모는 성공 발송된 `NEW` 공고-구독 항목 1건(`notification_item.kind = 'NEW'`)이며, 대상 `kind`는 `NEW` 항목을 포함한 `DISCORD_NEW`·`EMAIL_DIGEST`다(`DEADLINE` 전용 작업과 `NEW`+`DEADLINE` 혼합·동일 `sn`의 `DEADLINE` 항목은 제외). 이메일 묶음의 08:00 KST 이전 수집→묶음 대기 시간도 이 지표에 포함된다.
  - 성공 지연 타이머(`alerts_delivery_latency_seconds`, `alerts_delivery_collection_latency_seconds`)와 `alerts_delivery_duplicate_risk_total`은 `SENT` 외곽 트랜잭션이 커밋 확정(`afterCommit`)된 뒤에만 기록하므로, 공급자 HTTP `2xx` 뒤 DB 커밋 실패·롤백 시 증가하지 않는다(`alerts_delivery_attempts_total`은 실행된 발송 시도 결과 기준으로 기록).
