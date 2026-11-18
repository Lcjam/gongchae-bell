# 알림 구독 운영 (#6)

알림 구독(신청·확인·관리·해지·복구)과 요청 안 발송(확인 메일, 관리 링크 메일, 디스코드 등록 테스트)을 다룬다. 실제 공고 알림의 매칭·묶음·비동기 발송은 #7이다. 구현 계약은 [#6 계약](../../collab/2026-11-16-implementation/claude-subscriptions-contract.md)에 있다.

## 켜기 전에 닫을 게이트

`ALERTS_ENABLED`의 기본값은 `false`다. 꺼져 있으면 신청 화면은 준비 중 안내를 보이고 모든 구독 POST는 503이다. 개인정보도 받지 않는다. 켜면 아래 설정이 모두 있어야 앱이 시작한다. 설정이 다 있다고 해서 법적·운영적으로 준비됐다는 뜻은 아니다. **실제 개인정보를 받기 전에** 다음을 확인하고 기록한다([출처 검토](../../collab/2026-11-16-m2-privacy/sol-source-review.md)).

- 개인정보처리방침 값: 담당(성명 또는 부서), 연락처, 버전과 시행일, 앱·DB 호스팅 위치, 국외 이전 근거와 거부 방법, Resend 실제 계정 기준 보관 기간. 짐작한 값이나 자리표시자로 채우지 않는다.
- Resend: 실제 계정의 DPA, 발신 도메인 SPF·DKIM·DMARC, 열람·클릭 추적 꺼짐. 같은 계정에 다른 송수신이 없어야 공유 상한 90이 무료 한도 100 안에 든다.
- Discord: 국외 처리와 법적 관계(위탁인지, 독립 처리자인지)를 확정한다.
- 브라우저: `Referrer-Policy: no-referrer` 페이지의 POST가 보내는 `Origin`을 실제 HTTPS와 Firefox·Safari에서 확인한다(지금은 Chromium 루프백 관측뿐이다).
- 프록시: Caddy만 `X-Forwarded-For`를 넘기고, 외부 요청이 그 헤더를 직접 넣지 못한다. 접근 로그에서 쿼리 `t`를 지운다.
- 해지 대장: 처음 켜기 전에 운영자가 빈 파일을 만든다(예: `install -m 600 /dev/null <경로>` 뒤 `sync`). 앱은 대장을 만들지 않는다. 알림을 켰을 때 그 경로에 파일이 없으면 `ALERTS_ERASURE_LOG_MISSING`으로 시작하지 않는다. 그 호스트 파일이 blue·green 두 컨테이너에 같은 파일로 바인드되고 restic 백업에 들어가는지 확인한다. Docker는 없는 호스트 경로를 디렉터리로 만들기 때문에, 파일을 먼저 만들어야 한다. 대장 정리 시점은 아래 "해지 대장"을 본다.
- 백업 사본(OCI·NAS·PC)이 실제로 14일 뒤 지워지는지 확인한다.

## 설정 (값은 환경변수·GitHub Secrets에만)

| 이름 | 내용 |
|---|---|
| `ALERTS_ENABLED` | `true`일 때만 받고 보낸다 |
| `ALERTS_TOKEN_KEY` | 관리·해지 HMAC 키. base64, 32바이트 이상 |
| `ALERTS_HMAC_KEY` | 목적지·요청 제한 HMAC 키. 위와 다른 값, base64, 32바이트 이상 |
| `ALERTS_WEBHOOK_KEY`, `ALERTS_WEBHOOK_KEY_ID` | 웹훅 URL AES-256-GCM 키(base64, 정확히 32바이트)와 그 ID(`[A-Za-z0-9_-]{1,40}`) |
| `ALERTS_ERASURE_LOG` | 해지 대장 파일 경로. DB 볼륨과 다른 호스트 디렉터리. 켜기 전에 빈 파일로 준비해 둔다 |
| `RESEND_API_KEY`, `ALERTS_MAIL_FROM` | Resend API 키, 발신 주소(인증된 도메인) |
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
- 복원:
  1. 알림을 끈 채(`ALERTS_ENABLED=false`) 덤프를 복원한다.
  2. 다음 명령을 실행한다.
     ```
     java -jar gongchae-bell.jar --spring.main.web-application-type=none --alerts.erasure-reapply=true
     ```
     `ALERTS_ERASURE_LOG`에는 백업에서 되살린 대장 파일을 지정한다. 로그 `ERASURE_REAPPLY_COMPLETE deleted=N`이 나오고 종료 코드가 0인지 확인한다.
  3. 결과를 확인한 뒤 알림을 켠다.
- 아래 경우 명령은 실패한다. 완료 로그가 없고, 종료 코드가 0이 아니며, 아무것도 지우지 않는다. 운영자가 원인을 확인한 뒤 다시 실행한다. 그동안 알림을 켜지 않는다.
  - 경로에 파일이 없음(경로 오타, 대장을 되살리지 않음, 디렉터리를 가리킴): `ERASURE_LOG_MISSING`. 빈 대장으로 보지 않는다. 해지가 한 번도 없었던 경우에도 준비해 둔 빈 파일이 백업에 있어야 한다.
  - 대장에 잘리거나 형식이 틀린 줄: `ERASURE_LOG_TORN_LAST_LINE` 또는 `ERASURE_LOG_BAD_LINE_<줄 번호>`.
- 정리: 자동 정리는 없다. 해지 시각 이전에 만든 모든 백업 사본의 삭제가 확인된 뒤에만 그 줄을 지울 수 있다. 파일을 다시 쓰는 정리는 두 앱을 멈춘 상태에서 한다. 사본 삭제를 확인할 방법과 주기를 정하기 전까지 대장은 정리하지 않는다(처리방침에도 '미정'으로 표시).

## 발송 기록 읽기

- `notification`의 상태와 `last_error`, `notification_attempt.outcome`을 함께 본다. `UNCERTAIN`은 공급자가 처리했을 수도 있다는 뜻이다(5xx·타임아웃·종료). 재발송하지 않는다.
- `SEND_CAP`은 우리 상한에 걸린 것이다(HTTP 없음). `QUOTA_DAILY`는 Resend 일 한도에 걸린 것이다(HTTP 있음). `REQUEST_4XX`·`ACCOUNT_4XX`는 요청이나 계정 설정 문제다. 셋 다 구독을 끄지 않는다.
- 지표와 경보는 #7에서 붙인다.
