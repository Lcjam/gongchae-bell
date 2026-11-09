# 설계 독립안 · Codex

2026-11-08 · [확정 PRD v1.0](../../docs/prd.md), [지침](../../CLAUDE.md), [실현 가능성 검증](../03-feasibility.md) 기준. 다른 에이전트의 독립안은 읽지 않았다. 아래는 종합할 **결정 제안**이며 실제 배포·계정 가입·API 호출은 하지 않았다. 새 설정값은 성과 수치가 아닌 초기 정책이다.

## 1. 아키텍처 결정

**Java 21, Spring Boot 단일 애플리케이션, PostgreSQL을 작업 원장으로 사용한다.** OCI A1 2 OCPU·12GB는 가정이며 실서버·디스크·계정 유형은 미확인이다. 정상 앱 하나, 배포 순간에만 blue/green을 함께 띄운다.

| 구성 | 역할·필요한 이유 | 공개 |
|---|---|---|
| Caddy | 기존 도메인의 HTTPS·리버스 프록시·앱 전환. 인증서 데이터 볼륨 유지 | 웹 포트만 |
| app-blue / app-green | 동일 이미지의 웹·수집·알림 실행. 별도 worker 컨테이너를 늘리지 않음 | Docker 내부 |
| PostgreSQL 17 | 공고·구독·아웃박스·작업 선점·호출 예산·요청 제한의 원장 | 내부 |
| Prometheus | HTTP·수집·발송 지표 저장. 실제 운영 수치를 남기기 위해 필요 | 내부 |
| Grafana | 대시보드·내장 Alerting. 별도 알림 서버 컨테이너 없이 운영 경보 구성 | 내부·SSH 터널 |
| 호스트의 백업 timer + restic CLI | DB 덤프 암호화·보관·외부 사본. 상시 백업 컨테이너 대신 실행 시에만 사용 | 없음 |
| UptimeRobot / Healthchecks.io | 각각 외부 웹 HTTP 감시 / 수집·일일 알림·백업 작업 누락 감지 | 외부 서비스 |

최초 운영은 Caddy·앱·DB·Prometheus·Grafana로 구성한다. 호스트·컨테이너 메모리/CPU/디스크는 docker stats·호스트 도구로 기록하고 exporter 컨테이너는 추가하지 않는다. 자원 제한은 배포 겹침 시험 후 확정하며 03의 메모리 추정을 보장으로 쓰지 않는다.

**패키지:** catalog(공고·기관·검색), ingestion(원천·변환·수집), subscriptions(동의·토큰·관리·파기), notifications(매칭·작업·발송), operations(예산·작업 조율·관측). 각 기능 안에 web/application/persistence를 두고, 외부 연동은 해당 기능의 client로 둔다. 범용 이벤트 프레임워크는 만들지 않는다.

### 추가 의존성의 이유

기존 스택 외 추가는 아래로 제한하고 버전은 Boot BOM과 호환 검증 후 고정한다.

- Spring Security: 로그인 기능 없이 폼 CSRF·보안 헤더·경로 경계를 적용하기 위해 필요.
- Actuator + Micrometer Prometheus registry: PRD의 지표·readiness 제공에 필요.
- Apache HttpClient 5: 실제 연결에 쓰는 DNS 결과 검증과 리다이렉트 차단을 적용하기 위해 필요. 웹훅 전용 클라이언트에 사용자 지정 DnsResolver를 연결한다. [공식 API][DNS]
- AWS SDK v2 S3와 동기 URLConnection 전송 모듈: 아래 파기 대장의 독립 저장·재시도를 위해 필요. 요청 서명을 직접 구현하지 않는다. OCI S3 호환성은 공식 지원 범위를 따르며 **이 조합의 실제 연동은 미확인**이다. [OCI 문서][S3]
- restic: 암호화·원격 저장·보관 정리를 직접 작성하지 않기 위한 운영 CLI. [공식 백엔드 문서][RESTIC]

Spring JDBC는 기존 JPA/JDBC 구성을 이용한다. 암호화·HMAC은 JDK, 요청 제한·작업 조율은 SQL로 처리하며 그 용도의 새 라이브러리나 컨테이너는 넣지 않는다.

### CI/CD와 blue/green

1. PR에서 fixture·Testcontainers·비밀값 검사를 실행한다. main 머지 후 ARM64 이미지에 commit SHA를 붙여 GHCR에 게시한다. 배포 job은 동시 실행하지 않고 서버에서도 배포 잠금을 잡는다.
2. 사용하지 않는 색상의 앱을 새 이미지로 시작한다. 새 앱의 Flyway가 **구버전과 호환되는 추가 변경만** 적용한다. CI에서 구버전의 새 스키마 접근도 검증한다.
3. 내부 readiness(DB 접근 포함)와 핵심 화면을 확인한다. 실패하면 새 앱만 종료하고 기존 앱을 유지한다.
4. Caddy 설정의 upstream을 새 색상으로 바꾸고 reload한다. 공식 문서상 설정 reload 실패 시 기존 설정을 유지한다. 이것과 애플리케이션의 전체 무중단은 구분한다. [Caddy API][CADDY]
5. 외부 연속 요청으로 G4를 검사하고 이상 시 기존 upstream으로 복귀한다. 기존 앱을 검증 구간까지 유지한 뒤 SIGTERM·graceful shutdown으로 종료한다. 실패한 배포를 이유로 DB DDL을 자동 역실행하지 않는다.
6. 두 앱의 스케줄러가 겹치는 경우도 DB 조율로 방어한다. 폼은 서버 메모리 세션에 의존하지 않는 CookieCsrfTokenRepository와 Origin 검사로 구성해 교체 중 세션 유실을 피한다. 쿠키는 Secure·HttpOnly·SameSite와 host 범위를 제한한다. [Spring Security][CSRF]

## 2. 데이터 모델과 상태

공개 공고 데이터와 개인정보를 분리한다. 시각은 timestamptz, 날짜는 date로 저장하고 화면·일일 알림 기준은 KST다. 아래 컬럼명은 우리 DB의 설계명이며 원천 필드와 동일하다는 뜻이 아니다.

| 테이블 | PK·유니크·주요 내용 | 핵심 인덱스 |
|---|---|---|
| institution | PK 공시기관코드. 이름은 변경 가능. 원천 pblntInstCd로 연결 | 이름 검색은 필요 시 측정 후 |
| job_posting | PK 공고번호. 기관 FK, 공시 시작/마감일, 채용구분, 코드 배열, 모집 인원 원문/해석값, 자격·우대·전형 설명, 원본 링크, 해시, 상태·최초/변경/마지막 관측 | (상태, 마감일, 번호), (시작일, 번호), (기관, 시작일, 번호), 직무·지역·고용형태 배열 GIN |
| posting_detail | PK/FK 공고번호. steps·files JSONB, 수집 상태·시각. 목록 갱신과 분리 | 미수집/실패 대상 |
| posting_change | PK ID, UNIQUE(공고번호, 수집실행). 변경 필드·관측 시각 | (공고번호, 관측 시각) |
| collect_run / collect_stage | 실행 UUID PK, UNIQUE(종류, 논리 실행시각). stage PK(실행, 공고번호)는 초기 적재 재개용 | 실행시각·결과. 완료 stage는 삭제 |
| api_call | PK ID. 실행·종류·예약/호출시각·정규화 결과. 인증키·URL 원문 없음 | 호출시각 |
| job_lease | PK 작업명. 소유자·만료시각·증가하는 fence 값 | PK |
| subscription | UUID PK, 채널, 상태, 조건 JSONB·버전, 알림 종류, 암호화 목적지·키 ID, 목적지 HMAC, 동의문 버전·동의/확인시각·14세 이상 확인 | UNIQUE(채널, 목적지 HMAC), (상태, 채널) |
| subscription_token | PK 토큰 해시, 구독 FK, 목적·세대·키 ID·만료·사용시각. 원문 토큰 없음 | (구독, 목적), 만료시각 |
| notification_item | PK ID, 구독·공고 FK, 종류·원천 회차·상태·연결 작업. **UNIQUE(구독, 공고, 종류)** | (구독, 상태), 원천 회차 |
| dispatch_job | UUID PK, 구독 FK, 종류·논리 회차, 상태·선점버전·임대만료·다음시도·조건버전·암호화 본문 snapshot | UNIQUE(구독, 발송군, 논리 회차), 발송 가능 상태의 (다음시도, ID) 부분 인덱스 |
| dispatch_attempt / callback_receipt | 시도 PK ID·UNIQUE(작업, 시도번호). callback은 사건 fingerprint PK. 응답 코드·공급자 message ID 등 최소 정보 | 작업·시각. 원문 이메일·응답 body는 저장하지 않음 |
| rate_limit_bucket / quota_counter | 제한은 PK(범위, 대상 HMAC, 시간창), 예산은 PK(서비스, 집계창). 조건부 증가로 예약 | 만료시각 |
| erasure_journal | PK 구독 UUID, 파기 요청시각·독립 저장 확인. 주소·관리 토큰 없음 | 확인 상태·시각 |
| daily_page_count | PK(날짜, 라우트, 요청 분류). 서버 측 요청·원문 이동 집계 | PK |

기관의 월별·연도별 공고 수와 NCS 분포는 원본 공고에서 집계하고 별도 통계 저장소는 만들지 않는다. 모집 예정 인원은 해석 가능한 값만 합산하며 누락 건수를 함께 표시한다. 제목 포함 검색은 우선 매개변수화한 ILIKE로 처리하고 실행계획·기준선을 보고 최적화한다.

### 상태 전이와 정합성

- **공고:** ONGOING에서 마감일 경과/확인된 N으로 CLOSED, 마감 전 목록 이탈은 NEEDS_REVIEW. 원천이 다시 Y·유효 기간을 주면 재판정한다. 불완전 수집은 공고 상태·마지막 완전 성공 시각을 바꾸지 않는다.
- **구독:** 이메일 PENDING_CONFIRM에서 유효 확인 POST 후 ACTIVE, Discord는 테스트 생성 확인 후 ACTIVE. 해지·영구 실패는 발송을 중단하고 파기 절차를 거쳐 삭제한다. 재등록은 새 UUID를 사용한다.
- **발송:** PENDING에서 원자 선점해 SENDING, 수락 확인 시 SENT(열람 보장 아님). 일시 실패는 RETRY, 종료 실패는 FAILED, 해지·부적합은 CANCELLED. 임대 만료·응답 유실은 결과 불명확을 기록하고 제한 재시도한다. 외부 부작용의 exactly-once는 주장하지 않는다.
- **변경 관측:** PRD의 비교 필드를 정규화해 해시한다. decimalDay·관측시각·무의미한 배열 순서는 제외한다. 목록의 빈 steps/files는 상세를 삭제하지 않는다.

**작업 조율:** 주기 작업은 job_lease의 DB 시각 기반 조건부 갱신으로 선점한다. heartbeat로 연장하고 DB 반영 트랜잭션에서 fence를 검사해 만료된 소유자의 뒤늦은 갱신을 거절한다. 발송은 구독 행 잠금 뒤 FOR UPDATE SKIP LOCKED로 작업을 선점한다. 해지·조건 수정도 같은 구독 행을 잠가 직렬화한다. 외부 I/O 동안 전체 큐 잠금은 잡지 않는다. [PostgreSQL 큐 선점 근거][PGLOCK]

수집 결과와 매칭된 notification_item 삽입은 같은 트랜잭션이다. 일일 이메일은 누적 항목을 한 통으로 묶고, 선점 시 현재 상태·조건·공고 유효성을 다시 검사한다. 조건 수정은 대기 항목 재필터에 적용하고 과거 전체 공고를 소급 발송하지 않는다. 해지 확정 뒤 새 선점은 금지하되 이미 SENDING인 외부 전송의 취소는 보장하지 않는다.

### 토큰·암호화·파기

- 웹훅 URL과 이메일 목적지는 JDK AES-256-GCM으로 암호화한다. 매 암호화마다 새 nonce, 구독 UUID·용도를 AAD에 사용하고 key_id를 남긴다. 키·lookup HMAC 키는 DB/백업 밖 환경변수로 분리한다. 키 교체와 이전 백업 복구를 함께 시험한다. [JCA][JCA]
- Discord는 허용 호스트를 검증한 뒤 정규화한 URL로 중복 HMAC을 만든다. 이메일은 공백 제거·도메인 소문자화만 하고 임의의 점 제거 같은 사업자별 정규화는 하지 않는다.
- **관리 토큰 해시만 저장하면서 링크 재발급:** HMAC 전용 키로 구독 UUID·목적·세대를 입력해 토큰을 재생성하고 DB에는 그 해시만 저장한다. 원문을 저장하지 않아도 이메일에 관리 링크를 넣을 수 있다. 확인 토큰도 목적·세대를 분리하고 DB에서 만료·단회 사용을 검사한다. 세대 변경·삭제는 기존 링크를 무효화한다.
- 해지 시 구독과 연결된 토큰·알림 항목·발송 snapshot·시도/수신 사건을 파기한다. 동의 이력도 무기한 별도 보관하지 않는다. 남기는 장기 지표는 주소와 구독을 연결할 수 없는 집계다.
- **복원 후 해지 유지:** 해지 완료 응답 전에 UUID만 담은 암호화 파기 대장을 별도 OCI Object Storage prefix에 확정한다. 같은 구독 행 잠금 아래 외부 기록 확인 후 DB 파기를 마친다. 외부 기록 실패 시 완료라고 응답하지 않고 재시도를 안내한다. 복원 시 모든 발송을 먼저 막고 최신 대장을 적용한 뒤 해제한다. 대장을 확인할 수 없으면 알림은 계속 중단한다.
- 파기 대장도 보호 대상이다. 삭제 전 상태가 든 마지막 백업이 소멸할 때까지만 유지한다. 백업 14일 보관과 공급자 측 삭제 지연은 처리방침에서 별도로 설명한다. 이것이 더 단순한 ‘복원 때 모든 구독 삭제’보다 구현은 늘지만 활성 구독 복구를 유지할 수 있다.

## 3. 라우트와 흐름

| 공개 범위 | GET: 조회만 | POST: 상태 변경 |
|---|---|---|
| 공개 제품 | /, /jobs, /jobs/{sn}, /institutions/{code}, /privacy, /robots.txt. sitemap.xml은 Should | 없음 |
| 원문 이동 | /jobs/{sn}/source: 저장된 허용 링크로 이동·서버 측 집계. 임의 redirect URL 인자 금지 | 없음 |
| 이메일 신청 | /subscriptions/new?필터 | /subscriptions/email: 동의·연령 확인, PENDING 및 확인 메일 작업 |
| 이메일 확인 | /subscriptions/confirm?token: 확인 버튼만 | /subscriptions/confirm: 단회 토큰 사용과 ACTIVE 전환 |
| 관리·해지 | /subscriptions/manage?token, /subscriptions/unsubscribe?token: 조건/해지 확인 화면 | /subscriptions/manage: 조건 수정, /subscriptions/unsubscribe: 해지·파기 |
| 관리 링크 재발급 | /subscriptions/recover | /subscriptions/recover/email: 일관된 응답, 기존 이메일에만 발급. /subscriptions/recover/discord: 입력 URL의 HMAC 일치 후 재발급 |
| Discord 등록 | 신청 화면의 채널 탭 | /subscriptions/discord: 주소 검증·제한 후 테스트 작업. /subscriptions/discord/complete: 등록 시 발급한 단기 증표로 완료 상태·관리 링크 한 번 확인 |
| 공급자 callback | 없음 | /callbacks/brevo: TLS+설정한 Bearer 인증, 크기 제한·사건 중복 검사. 이 경로만 폼 CSRF 예외 |
| Should 원클릭 해지 | 일반 GET 해지 화면 | /subscriptions/one-click: 해지 전용 토큰 검증. RFC 8058 구현 시에만 CSRF 예외 |
| 운영 전용 | Actuator health/readiness/prometheus, Grafana | 초기 적재·재수집·파기 재시도는 SSH 내부 명령으로만 실행. 공개 운영 API·상태 화면 없음 |

관리/확인 URL의 토큰은 앱·Caddy·HTTP 클라이언트 로그·Referer에서 제거한다. 민감 화면은 no-store·no-referrer·noindex, 정적 자원은 자체 제공한다. 폼 CSRF 쿠키와 구독 관리 권한은 별개의 검증이다. 등록 완료 증표로 다른 구독을 읽거나 설정을 바꿀 수 없게 한다.

### 외부 API 명세

| 연동 | 요청·주기 | 호출량·검증 |
|---|---|---|
| 채용 list 초기 | 인증키, 공시 시작일 범위 3년, numOfRows=1000, 페이지, 진행 여부 미지정 | PRD의 약 36회는 추정. 실제 totalCount·페이지를 기록하고 900회 차단 전에 중단·재개. pageNo·serviceKey라는 **정확한 요청 인자명은 03에 명시되지 않아 Swagger 재확인 필요** |
| 채용 list 정기 | ongoingYn=Y, numOfRows=1000, 매시간 | 정상 하루 24회. resultCode·식별자·완결성 검사. 잘린 응답은 추가 페이지를 자동 호출하지 않음 |
| 채용 detail | sn=공고번호, 신규 진행 공고만; 성공 대상 반복 제외 | 03의 신규 약 30회/일은 예상. 초기·재시도·수동 포함 실제 시도를 기록. 500 경고·900 중단은 PRD 설정 |
| Discord | 검증한 /api/webhooks/{id}/{token}, POST, wait=true·allowed_mentions 제한 | 새 공고는 회차별, 마감 임박은 일일. 404 중단, 429는 서버 지시까지 대기. 실제 연결 DNS를 검사하고 리다이렉트 금지. [공식 문서][DISCORD] |
| Brevo 제안 | POST https://api.brevo.com/v3/smtp/email, api-key 헤더, 검증된 sender·수신자 한 명·본문. messageId 기록 | 이메일은 하루 한 통의 공고 묶음. 확인·관리 메일도 전체 한도에 포함. [API][BREVOAPI] |
| 공급자 callback | delivered·hardBounce·softBounce 등 필요한 사건만 수신 | 설정한 Bearer 검증 후 message-id/사건 fingerprint와 내부 작업을 연결. 순서가 바뀌어도 비활성 구독을 자동 활성화하지 않음. [사건][BREVOEVENT], [인증][BREVOAUTH] |

원천 필드 매핑은 03의 공고번호·기관코드·제목·날짜·구분·NCS/지역/고용 코드·인원·링크·자격/우대/전형 설명으로 제한한다. 확정되지 않은 이름·nullable/타입은 구현 전에 fixture와 Swagger로 맞춘다. 화면 조회·CI·부하 시험에서 외부 API를 호출하지 않는다.

## 4. 페이지별 텍스트 와이어프레임

| 페이지 | 구성 블록 |
|---|---|
| 공통 | 서비스명 / 공고 찾기 / 알림 신청, 데이터 기준 시각, 출처·한계, 개인정보·문의 링크 |
| 홈 | 소개 / 진행 중 D-0~D-3 / 오늘 시작 / 신입·정보통신 바로가기 / 알림 안내 / 각 영역 빈 상태 |
| 목록 | 필터·정렬 / 결과 수와 조건 요약 / 현재 조건으로 알림 신청 / 공통 컬럼 목록 / 페이징 |
| 공고 상세 | 제목·기관·기본 조건 / 날짜·기한 / 자격·우대·전형 설명 / 상세 수집 상태·첨부 링크 / 원문·기관 이력 |
| 기관 | 기관명·최근 3년 범위 / 공고 수·예정 인원과 누락 / 연도·월 표 / NCS 분포 표 / 이력 목록 / 공식 기관비교 링크 |
| 알림 신청 | 현재 필터 / 새 공고·마감 선택 / 이메일 또는 Discord / 고지·14세 이상·동의 / 신청 / 중복 여부를 숨긴 완료 안내 |
| 확인·등록 완료 | 확인할 구독 요약·명시적 POST 버튼 / 성공·만료·실패 안내. Discord 완료 시 관리 링크 한 번 제공 |
| 구독 관리 | 마스킹 목적지·상태 / 기존 조건과 종류 / 수정 버튼 / 해지 확인 버튼 / 링크 재발급 안내 |
| 개인정보 | 목적·항목·상태별 보유·파기·위탁/국외·백업·권리·연락처·정책 버전 |
| 오류·빈 상태 | 404, 검색 없음, 확인 만료, 제한 초과, 상세 미수집·지연. 비밀값·원시 오류는 표시하지 않음 |

공개 차트·관리자 UI·로그인·나란히 비교는 추가하지 않는다.

## 5. 초기 설정안

모두 **운영 정책 제안**이다. 법정 보존기한·실측 성능·사업자 한도라는 뜻이 아니다. 통합 시험과 실제 자원을 보고 조정하되 설정·변경 이유를 기록한다.

| 항목 | 초기안·이유 |
|---|---|
| Java·앱 | Java 21, 힙 512MiB부터. 컨테이너 한도는 RSS·native·배포 겹침 실측 후 확정 |
| 수집·일일 발송 | 매시 00분 수집, KST 08:10 일일 묶음. 수집 완료와 분리해 실행하고 전날 미발송 신규는 다음 일일 묶음에 포함 |
| 미실행 만회 | 당일 놓친 일일 실행은 한 번 만회. 마감 알림은 실제 실행일 D-3만 보내고 이미 지난 건은 제외. 마감 연장 재알림은 하지 않음: (구독, 공고, 종류) 유니크 유지 |
| 확인·관리 토큰 | 확인 24시간·단회, 미확인도 24시간 후 파기. 관리 30일, 재발급 시 세대 교체. 기존 이메일 재신청은 활성 조건을 덮어쓰지 않음 |
| HTTP·재시도 | 전체 요청 10초, 최초 포함 최대 3회 시도, 일시 오류 간격 2초·4초. 429는 고정 간격보다 공급자 지시를 우선 |
| 작업 임대 | 90초, 20초마다 연장. 임대 만료 후 fence를 잃은 작업은 DB 결과 반영 금지. 외부 수락 여부 불명확 재시도는 별도 지표 |
| 종료 | graceful shutdown 30초, 컨테이너 종료 유예 40초. 배포 중 실제 요청과 작업 종료로 검증 |
| 신청·재발급·테스트 | IP당 10회/10분, 목적지당 3회/시간. 관리 토큰 실패 IP당 30회/10분. DB 조건부 카운터로 두 앱 합산 |
| 전체 발송 | 메일 240회/이동 24시간, Discord 300회/시간을 초기 상한으로 예약. 공급자 한도 여유·확인 메일 남용 방지 목적이며 공급자 공식 한도와 별개 |
| 요청 제한의 IP | Caddy가 확정한 실제 peer만 신뢰. IP 원문 대신 키 있는 HMAC 사용, 시간창 종료 후 정리. 개인정보 항목·기간에 반영 |
| 개인정보 정리 | 해지·비활성 확정 시 연결 데이터 파기, 미확인은 만료 후 파기. 활성 구독의 발송 상세 이력은 7일, 이후 비식별 집계만 유지 |
| 관측·백업 | Prometheus 원본 14일, 일일 DB 백업 14일(PRD). 배포·훈련의 측정 조건·집계 결과는 저장소에 별도 보존 |

조건 변경은 아직 선점되지 않은 항목을 재필터하고, 이미 SENDING인 건의 취소는 보장하지 않는다. 최초 적재는 새 공고 알림에서 제외한다. 이메일 일일 회차는 (구독, 날짜) 유니크로 두 알림 종류를 한 통에 담는다.

## 6. 이메일 서비스 결정안과 미확인

**Brevo의 Transactional API를 우선 제안**한다. 직접 SMTP 서버를 운영하거나 공급자의 주소록·자동화 시스템을 복제하지 않는다.

| 선택지 | 공식 확인 사실 | 결정·트레이드오프 |
|---|---|---|
| Brevo | 무료 일 300회. DB 처리·저장은 EU, 프랑스·독일·벨기에를 명시. Bearer 인증 callback과 반송 사건 제공. [한도][BREVOFREE], [위치][BREVOLOC], [인증][BREVOAUTH] | 선택 제안. 일일 묶음+확인 메일에 여유가 있으나 **국외 이전이 있고** 로그·메일 미리보기는 기본 무기한 보관이므로 설정 변경·파기 검증 필수 |
| Resend | 무료 월 3,000회·일 100회. 저장은 미국이며 발송 리전 선택과 다름. 이메일·로그 30일 보관. 요청 멱등 키는 24시간 지원. [가격][RESENDPRICE], [보관][RESENDSEC], [멱등][RESENDIDEM] | 대안. 재시도 기능은 좋지만 일 한도가 작고 국외 보관·파기 고지는 여전히 필요 |

Brevo는 공식 도움말상 삭제가 즉시 끝나지 않으며 배치 처리로 최대 72시간 걸릴 수 있다. [공식 로그 관리][BREVOLOG] **우리 DB 즉시 파기와 수탁자 삭제 완료를 같은 시점으로 고지하면 안 된다.** 계정의 송신 활성화, 발신 도메인 승인, 무료 계정의 보관 설정·삭제 실제 동작, 전체 하위 처리 위치, 발신 인증·실제 수신은 미확인이다. 공개 발송 전 확인하고 처리방침·파기 runbook에 반영해야 한다. 이 조건을 충족하지 못하면 서비스 선정은 확정하지 않는다.

callback 인증을 검사하고 사건을 중복 처리하지 않는다. 메일 열람·클릭 추적은 사용하지 않는 설정을 확인한다. G7은 공급자 수락·확인된 후속 결과·결과 불명확을 구분하며 수신함·열람을 API 성공으로 대신하지 않는다.

PRD FR-A5의 “SMTP·API 5xx가 N회 연속이면 비활성”은 N=3을 초기 제안한다. 다만 HTTP API 5xx는 사업자 일시 장애일 수도 있어 정상 구독이 비활성화될 위험이 있다. **이 문구는 조율자가 재확인할 쟁점**이며 여기서 PRD를 변경하지 않는다.

## 7. 백업·관측 흐름

**백업:** 호스트 timer가 PostgreSQL 17의 pg_dump custom 형식으로 덤프한 뒤 restic으로 암호화해 OCI S3 호환 저장소와 외부 저장소에 각각 보관한다. 두 사본 성공·검증 후 heartbeat를 보내며 한쪽 실패는 경보한다. 덤프 임시 파일은 접근을 제한하고 정리한다. pg_dump·restic·OCI S3의 개별 기능은 문서 근거가 있으나 **현재 서버의 종단 간 조합은 아직 시험하지 않았다.** [pg_dump][DUMP], [restic][RESTIC], [OCI][S3]

OCI 밖 1순위 후보는 사용자가 관리하는 상시 켜진 NAS/서버의 SFTP 저장소다. 없으면 개인 PC의 정기 암호화 사본 수신을 후보로 두되 PC 미접속을 감지해야 한다. 실제 위치·용량·수신 가능성은 사용자 확인 전 미확인이다. 새 유료 저장소를 임의로 가입하지 않는다.

복구는 격리 DB에 덤프 복원, 환경변수 키 복구, 최신 파기 대장 적용, 해지·활성·기관 집계·화면 검증 순서다. 파기 대장 확인 전 외부 발송을 금지한다. restic 키·목적지 암호화 키를 DB 덤프와 함께 저장하지 않고 별도 복구 경로를 시험한다. 스냅샷 보관뿐 아니라 실제 데이터 정리도 검증한다. [restic 보관 정리][FORGET]

**관측:** Prometheus는 두 색상 앱을 수집하되 비활성 색상 하나의 부재를 전체 장애로 알리지 않는다. HTTP histogram, DB의 마지막 완전 수집·발송 대기·시도·실패·결과 불명확, 호출 예산을 본다. 이메일·토큰·구독 ID를 metric label로 넣지 않는다. Grafana 내장 Alerting으로 운영 Discord에 보낸다. 외부 무료 관측 전체로 대체하면 로컬 부하는 줄지만 외부 보존 한도에 종속되므로 자체 Prometheus/Grafana를 택한다.

UptimeRobot 공식 무료 플랜은 50개·5분 간격, Healthchecks.io Hobbyist는 20개 작업·작업당 로그 100개다. 이 저장 이력을 장기 운영 증거로 삼지 않는다. [UptimeRobot][UPTIME], [Healthchecks][HEALTH] 웹·수집·알림·백업을 각각 검사하고 원시 이메일·본문은 외부 감시로 보내지 않는다.

## 8. ADR 후보 요약

| ADR | 선택지 | 결정안·트레이드오프 |
|---|---|---|
| Java | 21 / 25 | 21. 요구 기능에 충분하며 실행·CI·이미지를 하나로 통일. 25 고유 기능은 사용하지 않음 |
| 앱 경계 | 단일 앱 / 별도 수집·발송 프로세스 | 기능별 패키지의 단일 앱. 배포 단순성 대신 DB 조율·실행 격리 필요 |
| 동시 실행 | 메모리 잠금 / DB 임대·선점 | job_lease+fence, 발송 SKIP LOCKED. 추가 서비스 대신 SQL·중단 시험 책임 |
| 무중단 | 앱 재시작 / 두 색상 전환 | 동일 호스트 blue/green+Caddy reload. 일시 자원 증가, VM 장애 고가용성은 아님 |
| 관측 | 직접 Prometheus/Grafana / 외부 무료 전체 | 직접 운영+외부 생존 감시. 운영 책임 증가 대신 지표·보존 통제 |
| 이메일 | Brevo / Resend | 위 조건부 Brevo. 한도·EU 저장·파기 지연·국외 고지 비용을 함께 판단 |
| 비밀 저장 | 애플리케이션 암호화 / 별도 키 관리 서비스 | JDK AES-GCM+외부 주입 키, HMAC 중복 판별. 키 복구·교체는 직접 책임 |
| 관리 토큰 | 원문 보관 / 재생성 가능한 PRF+해시 | 목적별 HMAC 토큰과 해시. 메일 재발급 가능, 키·세대 관리 필요 |
| 요청 제한 | 인스턴스 메모리 / DB 카운터 | DB 조건부 증가. 두 앱 합산 가능, 만료 정리와 DB 비용 필요 |
| 일정·파기 설정 | 전부 기준선까지 미정 / 초기 설정 후 검증 | §5 정책으로 먼저 경계를 시험. 성능·가동률 목표와 구분 |
| 백업 | 단일 사본 / 암호화한 두 위치 | pg_dump+restic, OCI와 사용자 SFTP 후보. 외부 위치 확보·키 복구 필요 |
| 파기 복원 | 복원 시 구독 전부 폐기 / 독립 파기 대장 | 대장 적용 후 재개. 구현·저장 호출이 늘지만 살아 있는 구독의 복구 유지 |

다음 검토에서 확인할 것은 PRD-A5의 오류 분류, 실제 발송 계정의 보관·파기·국외 처리 조건, 외부 백업 위치, 실제 서버 자원과 원천의 미확정 파라미터다. 다른 에이전트 안·실서버·실제 발송은 확인하지 않았으며, 처리량·가동률·복원 시간을 달성했다고 주장하지 않는다.

[DNS]: https://hc.apache.org/httpcomponents-client-5.6.x/current/httpclient5/apidocs/org/apache/hc/client5/http/DnsResolver.html
[S3]: https://docs.oracle.com/en-us/iaas/Content/Object/Tasks/s3compatibleapi_topic-Amazon_S3_Compatibility_API_Support.htm
[RESTIC]: https://restic.readthedocs.io/en/stable/030_preparing_a_new_repo.html
[FORGET]: https://restic.readthedocs.io/en/stable/060_forget.html
[DUMP]: https://www.postgresql.org/docs/17/app-pgdump.html
[CADDY]: https://caddyserver.com/docs/api#post-load
[CSRF]: https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html
[PGLOCK]: https://www.postgresql.org/docs/17/sql-select.html#SQL-FOR-UPDATE-SHARE
[JCA]: https://docs.oracle.com/javase/8/docs/technotes/guides/security/crypto/CryptoSpec.html
[DISCORD]: https://docs.discord.com/developers/resources/webhook
[BREVOAPI]: https://developers.brevo.com/reference/send-transac-email
[BREVOEVENT]: https://developers.brevo.com/docs/transactional-webhooks
[BREVOAUTH]: https://developers.brevo.com/docs/secured-webhooks
[BREVOFREE]: https://help.brevo.com/hc/en-us/articles/208580669-FAQs-What-are-the-limits-of-the-Free-plan
[BREVOLOC]: https://help.brevo.com/hc/en-us/articles/360001005510-Data-storage-location
[BREVOLOG]: https://help.brevo.com/hc/en-us/articles/360021533839-Manage-your-transactional-logs-and-email-previews
[RESENDPRICE]: https://resend.com/pricing
[RESENDSEC]: https://resend.com/security
[RESENDIDEM]: https://resend.com/docs/dashboard/emails/idempotency-keys
[UPTIME]: https://uptimerobot.com/pricing/
[HEALTH]: https://healthchecks.io/pricing/

