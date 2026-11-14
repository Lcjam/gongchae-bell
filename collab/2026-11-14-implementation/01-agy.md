# 구현 준비 1차 독립 점검 (agy): 웹·알림 보안 수용 기준 및 공식 공개 응답 Fixture

> **점검 대상**: [`CLAUDE.md`](../../CLAUDE.md), [`docs/prd.md`](../../docs/prd.md), [`docs/design/`](../../docs/design/), [`docs/adr/`](../../docs/adr/README.md), [`collab/03-feasibility.md`](../03-feasibility.md)  
> **작성일**: 2026-11-14 · **역할**: 웹/알림 보안 수용 기준 누락 위험 독립 점검 및 공개 응답 원본 Fixture 확보

---

## 1. 실제 API Fixture 확인 및 공개 응답 원본 1회 확보 결과

### 1.1 확보 내역
- 점검 시작 시점 기준 저장소 내에 `fixtures/` 디렉터리 및 API 응답 fixture 파일이 없음을 확인했다.
- 공공데이터포털 API 키를 탐색하거나 사용하지 않고, 인증키 없이 접근 가능한 ALIO 공식 공개 채용조회 JSON 엔드포인트(`https://opendata.alio.go.kr/new/odaApiMng/recrutInquiryAjaxList.do?ongoingYn=Y&pageNo=1&numOfRows=10`)를 **단 1회** 호출하여 원본 응답을 저장했다.
  - 원본 파일: [`fixtures/alio-public-list-sample.json`](../../fixtures/alio-public-list-sample.json) (`HTTP 200`, `resultCode=200`, `totalCount=497`, 표본 10건, 21,001 bytes)
  - 출처·취득일시·스키마 메모: [`fixtures/README.md`](../../fixtures/README.md) (`2026-11-14 17:17:18+09:00` 취득)

### 1.2 실제 응답 원본과 설계(`erd.md`, `prd.md`) 대조에서 드러난 구현 함정 3가지
1. **최상위 JSON 래퍼(Wrapper) 차이**
   - 공개 AJAX 응답은 최상위에 `{"data": {"result": [...], "resultCode": 200, "totalCount": 497, "resultMsg": "성공했습니다."}, "pathParam": "recrut"}` 구조(`data` 래퍼 존재)를 갖는다.
   - 반면 공공데이터포털 게이트웨이(`apis.data.go.kr/1051000/recruitment/list`)는 `data` 래퍼 없이 루트에 `{"resultCode": 200, "resultMsg": "...", "totalCount": 497, "result": [...]}`를 반환한다([`collab/03-feasibility.md`](../03-feasibility.md) §4, §7-1).
   - **최소 대안**: 수집 DTO 역직렬화 시 루트 객체와 `data` 래퍼를 모두 수용하거나, 공식 API 클라이언트 단위 테스트에서는 `data` 내부 JSON 노드를 추출해 응답 본문으로 주입한다.
2. **우대사항 필드가 2개(`prefCondCn`, `prefCn`) 존재하며 결격사유(`disqlfcRsn`)가 분리돼 있음**
   - [`docs/design/erd.md`](../../docs/design/erd.md)에는 `aply_qlfc`, `pref`, `scrnprcdr` 3개 컬럼만 정의되어 있고, PRD `FR-C4`(변경 관측 비교 필드)에도 `자격, 우대, 전형 설명`으로만 적혀 있다.
   - 그러나 실제 응답(`recrutPblntSn=307027`, `307028` 등)에는 `aplyQlfcCn`(응시자격), `scrnprcdrMthdExpln`(전형절차) 외에 **`prefCondCn`(우대조건 요약)과 `prefCn`(우대내용 상세) 두 필드가 모두 채워져 오며**, `disqlfcRsn`(결격사유)도 별도 필드로 온다.
   - **최소 대안**: 구현 시작 전에 `posting.pref`에 `prefCn`과 `prefCondCn` 중 무엇을 매핑할지(혹은 두 컬럼 모두 두거나 결합할지)와 `content_hash` 계산 대상 필드명을 `recrutPbancTtl`, `pbancBgngYmd`, `pbancEndYmd`, `recrutSe`, `hireTypeLst`, `ncsCdLst`, `workRgnLst`, `recrutNope`, `srcUrl`, `aplyQlfcCn`, `prefCondCn`, `prefCn`, `scrnprcdrMthdExpln`처럼 **원천 JSON 키 이름으로 고정**해야 테스트와 구현 간 해시 불일치가 발생하지 않는다.
3. **원문 링크(`srcUrl`)의 비표준 포트 및 기관 대표 목록 URL 판별**
   - 표본 10건 중 `recrutPblntSn=307028`의 `srcUrl`은 `https://www.knuch.kr:442/content/04info/11_01.asp?proc_type=view&b_num=5099...`로 **비표준 포트(`:442`)**를 사용하는 정상 개별 공고 링크다.
   - 반면 `307032`와 `307022`는 서로 다른 공고인데도 동일한 게시판 목록 URL(`https://www.knuh.kr/content/04information/02_01.asp`)을 가리키고, `307027`은 루트 도메인(`https://foodinfo.career.co.kr/`)만 가리킨다.
   - **최소 대안**: `FR-W6` 원문 링크 허용 검사에서는 `http`/`https` 스킴과 유효 호스트만 검사하고 **비표준 포트를 차단하지 않아야 한다**(디스코드 웹훅 URL 검증(ADR-0009)의 기본 포트 제한과 혼동 주의). 또한 `FR-C8`의 `src_url_generic` 판별 규칙은 루트 경로(`/`, `/index.*`)뿐 아니라 쿼리스트링(글 번호 파라미터)이 없는 고정 경로(`.asp`, `.do`, `/recruit` 등) 또는 동일 기관 내 다수 공고에 중복 등장하는 URL을 포괄하도록 설계해야 한다.

---

## 2. 웹·알림 보안 수용 기준의 누락 위험 점검

PRD(`FR-W1`, `FR-W6`, `FR-A1`~`FR-A8`, §6 보안, §7 완료 기준)와 설계·ADR(`routes.md`, `erd.md`, `ADR-0004`~`0015`) 사이에서 구현 시 빠지기 쉬운 보안 경계 조건 7가지를 점검했다.

### 위험 1. Origin 검사 필터([ADR-0015](../../docs/adr/0015-no-spring-security.md))와 RFC 8058 원클릭 해지(`FR-A8`) 충돌 및 우회 경계
- **원인**:
  1. `routes.md`는 "모든 POST는 Origin 검사"라고 규정하고 `POST /subscriptions/unsubscribe/one-click?t=`(FR-A8)를 포함하고 있다. 그러나 Gmail·Yahoo 등 메일 클라이언트가 RFC 8058 원클릭 해지 POST를 보낼 때는 브라우저가 아니므로 **`Origin`과 `Referer` 헤더가 전송되지 않는다**. 필터를 일괄 적용하면 원클릭 해지가 403으로 실패한다.
  2. 반대로 관리/해지 페이지에는 `Referrer-Policy: no-referrer`(`FR-A3`, `routes.md`)가 설정되므로, 브라우저가 해당 페이지에서 폼 POST를 보낼 때 `Referer`는 생략되고 `Origin`만 전송된다. 만약 sandboxed iframe이나 `data:` URI를 통해 क्र로스 사이트 POST가 발생하면 `Origin: null`(문자열 `"null"`)과 빈 `Referer`가 들어온다.
- **수용 기준 보완 및 최소 대안**:
  - 일반 상태 변경 POST(`/subscriptions/email`, `/confirm`, `/discord`, `/manage`, `/unsubscribe`, `/recover/*`)에서는:
    1. `Origin` 헤더가 있으면 `URI`로 파싱해 설정된 허용 Origin의 `(scheme, host, port)`와 **정확히 일치**하는지 검사한다(`Origin: null` 또는 불일치 시 즉시 403).
    2. `Origin`이 없을 때만 `Referer`를 파싱해 `(scheme, host, port)` 일치를 검사한다.
    3. **`Origin`과 `Referer`가 둘 다 없으면 즉시 403으로 거부**한다.
  - 예외: `POST /subscriptions/unsubscribe/one-click` 경로만 Origin 검사에서 제외하되, 해당 엔드포인트는 오직 `purpose=ONE_CLICK_UNSUBSCRIBE`(또는 `UNSUBSCRIBE`) HMAC 토큰 검증이 통과했을 때만 동작하게 제한한다.

### 위험 2. 디스코드 웹훅 SSRF 방어([ADR-0009](../../docs/adr/0009-webhook-storage-ssrf.md), `FR-A2`)에서 JDK `InetAddress` 기본 메서드의 사각지대
- **원인**:
  - ADR-0009는 "전송 직전에 `InetAddress`로 조회한 모든 주소가 사설, 루프백, 링크로컬, 사이트로컬(IPv4/IPv6)이 아닌지 확인한다"고 정의했다.
  - 그러나 Java 표준 라이브러리의 `InetAddress.isSiteLocalAddress()`, `isLoopbackAddress()`, `isLinkLocalAddress()`, `isAnyLocalAddress()`, `isMulticastAddress()`만 호출하면 다음 위험 대역이 **`false`(정상 공인 IP로 오판)**를 반환한다:
    1. **IPv6 Unique Local Address (`fc00::/7`, 즉 `fc00::`~`fdff::`)**: JDK `isSiteLocalAddress()`는 폐기된 `fec0::/10`만 검사하고 `fc00::/7`은 걸러내지 못한다.
    2. **IPv4-mapped IPv6 주소 (`::ffff:127.0.0.1`, `::ffff:10.0.0.1`, `::ffff:169.254.169.254`)** 및 **IPv4-compatible IPv6 주소**.
    3. **CGNAT 대역 (`100.64.0.0/10`)**, **Benchmarking (`198.18.0.0/15`)**, **Broadcast (`255.255.255.255`)**, **Documentation/Reserved 대역**.
- **수용 기준 보완 및 최소 대안**:
  - IP 검증기에서 `InetAddress.getAddress()` 바이트 배열을 직접 검사하여 IPv4(`127.0.0.0/8`, `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`, `169.254.0.0/16`, `100.64.0.0/10`, `0.0.0.0/8`, `224.0.0.0/4` 이상)와 IPv6(`::1`, `::`, `fc00::/7`, `fe80::/10`, `ff00::/8`, IPv4-mapped 대역)를 명시적 비트마스크로 차단한다.
  - 단위 테스트 fixture에 `fc00::1`, `fd12:3456:789a::1`, `[::ffff:127.0.0.1]`, `[::ffff:169.254.169.254]`, `100.64.0.1`, `0.0.0.0`을 반드시 포함한다.
  - 또한 웹훅 URL 정규화 시 `discordapp.com`과 `discord.com`이 서로 다른 `dest_hmac`을 만들어 중복 구독이나 관리 링크 복구(`POST /subscriptions/recover/discord`)가 실패하지 않도록, 검증 통과 후 **`https://discord.com/api/webhooks/{id}/{token}` 형태로 정규화한 뒤 `dest_hmac`을 계산**해야 한다.

### 위험 3. 관리·해지 HMAC 토큰([ADR-0008](../../docs/adr/0008-tokens.md))의 권한(Purpose) 혼용과 URL 쿼리 노출(`FR-A3`, `FR-A6`)
- **원인**:
  1. 모든 이메일 하단에는 해지 링크가 포함된다(`FR-A3`). 만약 해지 링크의 토큰과 관리(조건 수정) 링크의 토큰이 구분되지 않거나, 해지 토큰으로 `GET/POST /subscriptions/manage`를 호출할 수 있으면 이메일 전달/노출 시 제3자가 구독 조건을 임의로 변조할 수 있다.
  2. `GET /subscriptions/manage?t=...` 또는 `GET /subscriptions/confirm?t=...` 화면에서 폼을 제출(`POST`)할 때, `<form action="/subscriptions/manage?t=...">`처럼 쿼리스트링에 토큰을 남겨 두면 POST 요청의 URL에도 토큰이 남아 프록시·애플리케이션 로그 마스킹 누락 위험이 커진다.
- **수용 기준 보완 및 최소 대안**:
  - HMAC 입력 메시지 포맷을 `"{purpose}:{subscriptionId}:{tokenVersion}"`으로 고정하고, `purpose`(`MANAGE` vs `UNSUBSCRIBE`)가 엔드포인트와 일치할 때만 허용하며, `MessageDigest.isEqual`로 상수 시간 비교를 수행한다.
  - 확인·관리·해지 화면의 `<form method="post">`는 쿼리스트링이 없는 경로(`action="/subscriptions/manage"`)로 전송하고, 토큰은 `<input type="hidden" name="t" ...>` 본문 필드로만 전달한다.
  - Spring Boot 기본 에러 속성(`server.error.include-message=never`, `include-binding-errors=never`, `include-stacktrace=never`)을 고정해 예외 화면이나 로그에 입력값(이메일, 웹훅 URL, 토큰)이 반사되지 않게 한다(`FR-A6`).

### 위험 4. 요청 제한([ADR-0010](../../docs/adr/0010-rate-limit-and-budget.md), `FR-A7`)의 트랜잭션 롤백 무력화 및 클라이언트 IP 스푸핑
- **원인**:
  1. `TOKEN_FAIL`(IP당 10분 30회)이나 `WEBHOOK_TEST` 실패 시 도메인 서비스나 컨트롤러가 예외를 던져 트랜잭션이 롤백되면, 같은 트랜잭션 안에서 실행된 `rate_limit` 테이블의 `INSERT ... ON CONFLICT DO UPDATE`까지 함께 롤백되어 횟수 제한이 작동하지 않는다.
  2. 또한 `TOKEN_FAIL`을 `POST` 요청에서만 세고 `GET /subscriptions/manage?t=`·`GET /subscriptions/confirm?t=` 조회 시의 토큰 불일치에서 세지 않으면, 공격자가 `GET`으로 토큰이나 상태를 무제한 탐색할 수 있다.
  3. Caddy 뒤에서 동작할 때 클라이언트가 임의로 보낸 `X-Forwarded-For` 헤더를 앱이 그대로 신뢰하면 IP 기반 Rate Limit을 우회할 수 있다.
- **수용 기준 보완 및 최소 대안**:
  - `api_budget`과 동일하게 **`rate_limit` 카운터 증가는 반드시 독립 트랜잭션(`Propagation.REQUIRES_NEW`)으로 즉시 커밋**한다.
  - `TOKEN_FAIL` 카운터는 토큰을 검증하는 모든 `GET` 및 `POST` 엔드포인트에서 검증 실패 시 즉시 차감한다.
  - Caddy는 외부 요청의 기존 `X-Forwarded-For`를 신뢰하지 않고 실제 소켓 IP로 덮어쓰며, 앱은 로컬/Docker 내부 프록시에서 온 헤더만 신뢰하도록 구성하고 헤더 위조 통합 테스트를 추가한다.

### 위험 5. 이메일 구독·복구 시 타이밍 차이와 상태 덮어쓰기를 통한 구독 여부 노출(`FR-A1`, `FR-A3`, `FR-A7`)
- **원인**:
  - `FR-A1`은 "이미 구독 중인 주소로 신청해도 같은 응답을 돌려주고, 기존 활성 구독을 덮어쓰지 않는다", `routes.md`는 `POST /subscriptions/recover/email`에서 "등록된 이메일이면 관리 링크를 보낸다. 응답은 항상 같다"고 규정한다.
  - 만약 HTTP 요청 처리 스레드 안에서 조건부로 외부 Resend API를 동기 호출하면:
    1. 실제 발송이 일어나는 경우(수백 ms)와 무시되는 경우(수 ms)의 **응답 시간(Timing) 차이**로 이메일 구독 여부가 노출된다.
    2. `subscription` 테이블의 `(channel, dest_hmac)`이 일반 인덱스로만 되어 있어(`erd.md`), 동시 신청 시 중복 행이 생기거나 `ACTIVE` 행을 실수로 덮어쓸 위험이 있다.
- **수용 기준 보완 및 최소 대안**:
  - 확인 메일(`EMAIL_CONFIRM`)과 관리 링크 복구 메일 모두 HTTP 요청 스레드에서 외부 API를 직접 호출하지 않고, **`notification` 테이블에 발송 작업을 적재한 뒤 즉시 동일한 성공 화면을 반환**한다(발송 워커가 비동기 처리). 이를 위해 `notification.kind`에 관리 링크 재발송용 구분(`EMAIL_RECOVER` 또는 `EMAIL_CONFIRM` 공용)을 명시한다.
  - 이메일 주소는 `trim().toLowerCase(Locale.ROOT)`로 정규화한 후 `dest_hmac`을 계산하고, 기존 `ACTIVE` 구독이 존재할 때 `POST /subscriptions/email`이 들어오면 기존 행의 `conditions`·`status`·`token_version`을 변경하지 않는다.

### 위험 6. 원문 이동(`GET /jobs/{sn}/source`) 및 화면 링크의 스킴·제어문자 검증(`FR-W6`)
- **원인**:
  - `routes.md`의 `GET /jobs/{sn}/source`는 DB에 저장된 `posting.src_url`로 리다이렉트하며 서버 측 집계를 수행한다.
  - 원천 API 데이터(`srcUrl`)에 `javascript:...`, `data:...`, `//...`, 또는 개행문자(`\r\n`)가 포함된 악성/오류 문자열이 들어올 경우, 화면의 `href` 렌더링이나 `/jobs/{sn}/source` 응답에서 XSS·헤더 인젝션·비정상 리다이렉트가 발생할 수 있다.
- **수용 기준 보완 및 최소 대안**:
  - 공통 URL 검증 유틸리티를 두어 `URI` 파싱 후 **(a) scheme이 소문자 기준 정확히 `http` 또는 `https`인지, (b) host가 비어 있지 않은지, (c) 문자열에 제어문자(`\r`, `\n`, `\0` 등)가 없는지** 검증한다.
  - 허용되지 않는 `srcUrl`은 화면에서 원문 링크 버튼을 렌더링하지 않고 `GET /jobs/{sn}/source` 호출 시에도 리다이렉트 대신 400/404 또는 잡알리오 상세 링크 안내로 처리한다(악성 문자열 fixture 테스트 필수).

### 위험 7. 백업 복원 후 `erasure.log` 재적용 전 발송 스케줄러 조기 실행 경합([ADR-0013](../../docs/adr/0013-erasure-journal-host-file.md), PRD §7)
- **원인**:
  - ADR-0013의 복원 절차는 "발송을 멈춘 채 덤프를 복원한다 → 대장의 UUID를 모두 다시 삭제한다 → 검증한 뒤 발송을 재개한다"이다.
  - 하지만 앱에 발송 중지 스위치(설정값)가 없다면, 복원된 DB에 앱 컨테이너가 연결되는 즉시 매분 도는 발송 스케줄러가 기동하여 `erasure.log` 재적용 전에 해지자에게 메일/웹훅을 발송할 수 있다.
  - 또한 평상시 해지 처리(`POST /subscriptions/unsubscribe`)에서 DB 삭제가 먼저 커밋되고 `erasure.log` 파일 쓰기가 실패하면, 이후 백업 복원 시 해지 기록이 유실된다.
- **수용 기준 보완 및 최소 대안**:
  - 해지 처리 순서를 **① 구독 행 `FOR UPDATE` 잠금 → ② `erasure.log`에 `subscription_id, timestamp` 기록 및 `FileChannel.force(true)`(fsync) 성공 확인 → ③ DB 구독 삭제 커밋**으로 강제한다(파일 기록 실패 시 예외를 발생시켜 트랜잭션을 롤백하고 운영 경보 지표 증가).
  - 환경변수(`APP_DISPATCH_ENABLED=false` 또는 `APP_SCHEDULING_ENABLED=false`)로 스케줄러를 끈 채 앱(또는 대장 재적용 명령)을 실행할 수 있도록 설정 스위치를 구현하고 백업 복원 통합 테스트에 포함시킨다.
