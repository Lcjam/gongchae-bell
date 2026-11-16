# #6 구독·보안·확인 경로 구현 결과 · claude

> 2026-11-16 · 조율: codex · 구현: claude(#6 단독 작성자) · 검수: root와 구현하지 않은 리뷰어
> 브랜치 `feat/#6-subscriptions`. Orca 워크트리 `issue-6-subscriptions`는 `origin/main` `000dce5`(M1 PR #11 merge 포함)에서 새로 만들었다.
> 계약: [#6 계약](claude-subscriptions-contract.md). 근거 기록(root 원본을 그대로 복사): [06](../2026-11-15-m2-readiness/06-codex-coordination.md)·[07](../2026-11-15-m2-readiness/07-sol-review.md)·[08](../2026-11-15-m2-readiness/08-agy-final-review.md)·[12](../2026-11-15-m2-readiness/12-claude-revised-contract.md)·[13](../2026-11-15-m2-readiness/13-sol-revised-review.md)·[14](../2026-11-15-m2-readiness/14-codex-implementation-decision.md), [개인정보 출처 검토](../2026-11-16-m2-privacy/sol-source-review.md).
> 하지 않은 것: 실제 ALIO·Resend·Discord 호출, 사용자 데이터·env 비밀값 열람, 서버·DNS·키 변경, 영속 개발 DB·볼륨 접근, root·#4·agy·Sol 브랜치 수정, 원격 머지. #7(매칭·묶음·outbox·비동기 워커)은 구현하지 않았다.

## 커밋과 CI

| 커밋 | 내용 | 원격 CI |
|---|---|---|
| `b0a7ab7ed38e1f5fb9ffcc48aac01013c4c03a51` | #6 구현, 테스트, 문서, 근거 기록 사본 | [43811896339](https://github.com/Lcjam/gongchae-bell/actions/runs/43811896339) `test`(ARM64 `check bootJar`)·`secrets` 성공 |
| (이 문서) | 결과 기록 | 문서만 |

기준은 `origin/main` `000dce5`이고, 이 브랜치에서 처음 만든 커밋이 위 구현 커밋이다. rebase와 force push는 하지 않았다.

## 무엇을 만들었나

| 영역 | 파일 | 내용 |
|---|---|---|
| 스키마 | `V5__subscriptions.sql` | 5개 테이블(`subscription`, `notification`, `notification_attempt`, `notification_item`, `rate_limit`). 새 테이블만 |
| 설정 | `AlertsProperties`, `application.yml`, `.env.example` | `ALERTS_ENABLED`(기본 false). 켜면 키 3개, 대장 경로, Resend 키·발신 주소, 처리방침 값 7개가 모두 있어야 시작한다(없으면 고정 코드로 실패, 값은 출력하지 않음) |
| 토큰·암호 | `AlertsCrypto` | 확인 토큰 32바이트(해시만 저장), 관리·해지 HMAC(목적별), 목적지·제한 키 HMAC(별도 키), 웹훅 AES-256-GCM(AAD = 구독 ID, 키 ID 확인) |
| 제한 | `RateLimiter` | DB 고정 창, 상한 안에서만 증가, 자기 트랜잭션 커밋, UTC 날짜·정시 정렬 |
| 발송 기록 | `DeliveryLedger` | 예약 R, 잠금 재확인, 결과 1회 기록, 종결 규칙, 동기 정리, 비동기 선점·회수(#7용) |
| 요청 안 발송 | `SyncSender` | 25초 예산, 최대 3회, 키·해시 규칙, 디스코드 등록 테스트 |
| 공급자 | `HttpTransports`, `SendOutcome`, `WebhookUrl`, `AddressGuard` | Resend·Discord JDK HttpClient(`Redirect.NEVER`, 요청 전체를 타임아웃으로 묶음), 결과 분류, 정확한 웹훅 URL, 공인 주소 검사 |
| 구독 | `SubscriptionService`, `SubscriptionController` | 이메일 신청·확인·관리·조건 수정·해지·원클릭 해지·관리 링크 복구, 디스코드 등록·복구 |
| 보안 | `OriginFilter` | Origin 규칙과 토큰 POST 예외. 토큰 실패 제한 순서는 서비스에 있다 |
| 해지 대장 | `ErasureJournal`, `ErasureReapplyCommand` | 잠금·전부 쓰기·줄바꿈 보정·fsync, 엄격한 읽기, 복원 재적용(fail closed) |
| 보존 | `AlertsMaintenance` | 5분마다 미확인 만료, 비활성 30일, 종결 작업 30일, 끝난 제한 창, 동기 정리 |
| 화면 | `templates/subscriptions/*`, `privacy.html`, `jobs.html`, `layout.html` | 신청(이메일·디스코드), 고지·필수 동의·만 14세, 확인, 관리, 해지, 복구, 안내, 처리방침, 목록 CTA, 푸터 처리방침 링크 |
| 공유 소스 | `JobSearchCriteria.strict`, `CatalogRepository.appendConditions` | 같은 허용 목록에 거부 모드를 더했다. 목록의 술어를 꺼내 #7이 쓸 수 있게 했다. 목록 동작은 그대로다(기존 #5 테스트 통과) |

## 14가 정한 핵심 조건의 반영

| 조건 | 구현 | 테스트 |
|---|---|---|
| 1. 확인 메일 SENT는 PENDING 유지, 유효 확인 POST만 ACTIVE | 확인 POST의 UPDATE 하나가 현재 해시·만료·PENDING을 검사하고 토큰을 지우며 `confirmed_at`을 쓴다 | `onlyAValidConfirmPostActivates…`, `aFreshKeyOnly…`(SENT 뒤 PENDING) |
| 2. unknown 뒤 자격 불일치는 해시와 무관하게 차단, 요청 전체 해시 | 매 라운드에서 A가 부모 `FOR SHARE` 아래 자격을 먼저 본다. 자격이 바뀌었으면 R·HTTP 없이 종결(결과 모름이 있으면 `UNCERTAIN`). 해시는 Resend 요청 JSON 바이트 전체에 대해 계산 | `afterAnUnknownAttemptAChangedEligibility…`(HTTP 1회, 예약 1) |
| 3. R 전 A의 자식 DML·잠금 금지, R 뒤 notification 잠금·펜싱 재검사 → HTTP, 순서 notification → attempt/item | `SyncSender.deliverMail`: 부모 확인과 이력 읽기 → R → `lockReserved` → 자격·시간 재확인 → HTTP → 결과·종결을 같은 잠금 아래에서. JdbcClient 부분 UPDATE만 쓰므로 ORM flush 덮어쓰기가 없다. 회수는 `SKIP LOCKED` | `synchronousKindsAre…Fenced`(옛 선점은 R·finish 모두 0행), `aReservationSurvivesACrash…` |
| 4. 25초 = 신청·DB·잠금·R·HTTP·대기·정리 전체, R 뒤 다시 검사, 환불 없음 | 시작 시각은 서비스 진입 때. `boundWaits`로 lock·statement timeout을 남은 시간에 묶는다. 풀 대기는 5초. R 뒤 남은 시간으로 HTTP 타임아웃을 다시 계산하고, 1초 미만이면 `NOT_SENT` | `theWholeRequestStaysInsideTwentyFiveSeconds…`(타임아웃 2회 경로, R 대기 9초·12초 경로) |
| 최대 3, 23시간 | `attempts < 3` 펜싱, `recoverAsync`의 수명 판정 | `atMostThreeAttempts…`, 회수 테스트 |

## 테스트

로컬 실행은 Windows x64, Java 21.0.13, Gradle 9.9.1, Testcontainers PostgreSQL 17이고, 셸에 `COLLECTOR_ENABLED=false COLLECTOR_BACKFILL=false`를 두었다. `gradlew check bootJar`는 **BUILD SUCCESSFUL**이었고 **127 tests / 0 failures / 0 errors / 0 skipped**다. 이후 Gitleaks 때문에 시험 키를 런타임 계산으로 옮겼고, `SubscriptionFlowIntegrationTest` 8건을 다시 돌려 통과했다.
- 기존 M1 96건(Catalog 6+6, Foundation 4, 수집 80)은 회귀 없이 통과했다.
- 새 31건:

| 클래스 | 건수 | 종류 |
|---|---:|---|
| `AlertsSecurityTest` | 5 | 단위: 설정 거부, 토큰, AES-GCM, 웹훅 URL, 주소 검사 |
| `ErasureJournalTest` | 2 | 단위: 동시 쓰기, 잘린 줄 |
| `TransportTest` | 3 | 루프백 HTTP 대역: Resend 헤더·분류·타임아웃·연결 거부, Discord 사설 주소·리다이렉트·429·404·401 |
| `DeliveryLedgerTest` | 12 | PostgreSQL + 가상 단조 시계 |
| `SubscriptionFlowIntegrationTest` | 8 | Spring MockMvc, 알림 켜짐, 공급자 대역 |
| `AlertsDisabledIntegrationTest` | 1 | 기본 설정(알림 꺼짐) |

명시 경계별 테스트:
- **토큰**: 가짜·만료·재사용 확인 토큰, 다른 구독 ID로 바꾼 관리 토큰, 관리 토큰으로 해지 시도 → 모두 거부. GET(확인·해지)은 상태를 바꾸지 않는다. 목적·버전·변조는 단위 테스트로 확인.
- **Origin과 TOKEN_FAIL**: 무토큰 POST는 Origin·Referer가 없거나 외부·null이면 403이다. 같은 출처 Referer는 통과한다. 토큰 POST는 null과 Origin 없음을 받고 외부 Origin은 403인데, 이 403은 실패 횟수에 들어가지 않는다. 30회 실패 뒤에는 유효 토큰도 그 IP에서 429이고, 다른 IP는 통과한다.
- **덮어쓰기·동시성·삭제**: 활성 주소의 재신청은 메일도 조건 변경도 없다. 같은 주소로 두 요청이 동시에 와도 행은 1개다. 해지하면 작업·시도가 CASCADE로 지워진다. 루프 중 삭제되면 조용히 끝난다.
- **SSRF**: 닮은 호스트, userinfo, IP 리터럴, 명시 포트, 쿼리, 키릴 문자를 거부한다. 사설·루프백·링크로컬·CGNAT·ULA·mapped 주소는 요청 전에 막는다(대역 서버 요청 0회). 302를 따르지 않는다.
- **암호화**: AAD가 다르거나 키 ID가 다르면 실패한다. 키가 없거나 짧으면 시작을 거부한다. nonce가 매번 다르다.
- **예약**: 상한이면 예약도 시도도 HTTP도 없다. R 뒤 바깥이 롤백(종료)돼도 예약과 null 시도가 남고, 정리 뒤 `UNCERTAIN`/`INTERRUPTED`가 되며 재발송하지 않는다. 일 한도 429는 시도와 예약에 들어간다. 최대 3회이고, 25초 예산을 지킨다.
- **결과 모름과 4xx**: 타임아웃 뒤 4xx는 `UNCERTAIN`이다. 범용 4xx만 있으면 `FAILED`이고 구독은 그대로다. 결과 모름 뒤에는 같은 키, 확실한 거절 뒤에는 새 키를 쓴다. 긴 `Retry-After`면 요청을 끝낸다.
- **동기·비동기 분리**: 비동기 선점·회수는 동기 행을 건드리지 않는다. 옛 선점의 R·finish는 0행이다.
- **해지 대장**: 8개 스레드로 200줄을 동시에 써도 모든 줄이 온전하다. 잘린 줄은 줄바꿈 보정으로 다음 줄과 붙지 않는다. 읽기는 잘리거나 형식이 틀린 줄에서 실패한다. 복원은 손상 시 하나도 지우지 않고, 정상이면 전부 지운다.
- **파기**: 미확인 만료, 비활성 30일, 종결 30일, 끝난 제한 창.
- **화면**: 고지, 만 14세, 미체크 동의, 목록 필터로 채운 선택, 처리방침 값, 목록 CTA, 필수 동의·종류 누락 거부. 알림이 꺼져 있으면 준비 중 안내와 503이고 처리방침에 '미정'이 보인다.

## 브라우저 확인 (Orca 내장 Chromium, 360×800 모바일 뷰포트)

| 방법 | 결과 |
|---|---|
| 앱 | 이 브랜치 jar. DB는 버리는 Postgres 컨테이너(개발 DB 아님). 키는 시험용 값, 메일 주소는 닫힌 루프백 포트(`http://127.0.0.1:9/`) |
| 화면 | `/subscriptions/new`, `/privacy`, `/jobs`, 신청 결과 화면 모두 `scrollWidth = innerWidth = 360`. 동의와 만 14세 체크는 비어 있다 |
| 실제 폼 제출 | 브라우저 폼 POST가 Origin 검사를 통과했다 → 고정 응답. DB 기록: 구독 PENDING, `EMAIL_CONFIRM` FAILED/ATTEMPTS_EXHAUSTED, 시도 3회 모두 `CONNECT_FAILED`, 실제 대기 2초·4초로 6.1초, 발송 상한 예약 3 |
| 로그 | 앱 로그에 입력한 주소와 API 키 문자열 0건 |
| 정리 | 탭, 앱 프로세스, 컨테이너를 정리했다. 실행 디렉터리도 지웠다 |

이 확인으로 고정 응답 문구의 문제를 찾아 고쳤다. "확인 메일을 보냈습니다"는 발송 실패·상한·활성 주소에서는 사실이 아니라서, "구독 중이 아닌 주소라면 확인 메일을 보냅니다"로 바꿨다. 관리 링크 재발송 문구도 같은 이유로 바꿨다.

## 보존·정리의 구체안 (14가 요구한 것)

| 대상 | 구현한 규칙 | 최장 잔존(앱이 도는 동안) | 고지 |
|---|---|---|---|
| IP·대상 HMAC 카운터 | `window_end`가 지나면 5분 주기로 삭제 | IP 키 15분, 이메일·웹훅 키 1시간 5분 | 처리방침 2항 |
| 미확인·비활성·종결 작업 | 24시간·30일·30일 + 5분 주기 | 규칙 + 5분 | 처리방침 2항, 신청 고지 |
| 해지 대장 | 자동 정리 없음 | 정하지 않음 | 처리방침: '미정', 모든 백업 사본 삭제 확인 뒤 정리 |
| 백업 | 설계 14일(ADR-0012) | 사본별 실제 삭제는 미확인 | "설계상 14일"로만 표기 |

해지 대장을 정리하지 않은 이유: 해지 시각 이전 백업이 모든 사본(OCI·NAS·PC)에서 지워졌는지 확인할 수 없다. 또 두 앱이 같은 파일에 쓰므로, 파일을 다시 쓰는 정리는 앱을 멈춘 상태에서만 안전하다. 그래서 운영 절차와 함께 게이트로 남겼다([운영](../../docs/ops/alerts.md#해지-대장과-복원-adr-0013)).

## 계약과 다르게 하거나 더 작게 한 것

- **디스코드 등록 테스트의 시도 감사**: 행이 성공 뒤에만 생기므로, 실패한 테스트의 시도는 DB 행이 아니라 `rate_limit` 예약과 지표로만 남는다. 12 §6과 13에서 수용한 예외다. 검증되지 않은 웹훅 URL을 저장하지 않기 위해서다.
- **이메일 자동 비활성화 없음**: Resend 공식 오류 표에 수신자 전용 응답이 없다. 그래서 #6에는 이메일 구독을 끄는 코드가 없다. 디스코드 404 비활성화는 비동기 발송이 있는 #7에서 한다. 등록 테스트의 404는 등록하지 않는 것으로 끝난다.
- **DB 풀 획득 대기 5초**를 전역으로 걸었다(기본 30초). 25초 예산 때문이다. 목록 화면에도 적용되지만 지금 부하에서는 영향이 없을 것으로 본다. 실측하지 않았다.
- **목록 CTA 주소**에 빈 인자가 붙는다(`?ncs=&region=…`). 폼은 빈 값을 '전체'로 받는다. 보기만의 문제라 고치지 않았다.
- `alerts.mail.base-url`은 시험·점검용 설정이고 환경변수 이름은 두지 않았다(기본 `https://api.resend.com/`).

## 남은 일과 실사용 전 게이트

- **#7**: outbox 훅, 묶음 생성(08:00·09:00, 당일 만회), 비동기 워커(TX1·TX2·TX3), 조건 탈락 item 삭제와 반환, 디스코드 404 비활성화, RFC 8058 헤더, 지표·경보.
- **실사용 전**([운영 문서](../../docs/ops/alerts.md#켜기-전에-닫을-게이트)):
  - 처리방침 실제 값(담당·연락처·호스팅 위치·국외 이전 근거·공급자 보관).
  - Resend DPA·도메인 인증·추적 꺼짐·계정 단독 사용.
  - Discord 법적 관계.
  - 실제 HTTPS와 다른 브라우저의 Origin 관측.
  - Caddy 프록시 헤더와 `t` 로그 제거.
  - 해지 대장 경로·백업.
  - 백업 사본 삭제 확인.
- 웹훅 키 교체 도구가 없다(키 ID 하나만 읽음).
- 실제 Resend·Discord 응답, 수신함 배달, 실제 메일에서 원클릭 해지가 되는지는 확인하지 않았다(실발송 승인 뒤).

## PR

[#13](https://github.com/Lcjam/gongchae-bell/pull/13)(draft, base `main`, `Refs #6`). root 검수와 구현하지 않은 리뷰어 검수 전이며 머지하지 않는다.
