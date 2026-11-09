# 설계 1라운드 · claude 독립 설계안

> codex와 agy의 01은 보지 않았다. 원칙: **PRD v1.0 범위 안에서 컨테이너와 의존성을 최소로 한다.** 새 구성 요소마다 "왜"를 적는다.

## 1. 아키텍처

**A1 1대의 Docker Compose 구성**
| 컨테이너 | 왜 필요한가 |
|---|---|
| `caddy` | HTTPS 자동 발급, blue/green 전환, 접근 로그 |
| `app-blue` / `app-green` | 무중단 배포. 평소에는 한쪽만 떠 있다 |
| `postgres` (17) | 데이터 원본 |
| `prometheus` | 지표 수집 (PRD 감시·경보 요구) |
| `grafana` | 대시보드와 경보 → 운영 디스코드. 외부에 열지 않고 SSH 터널로만 접속 |
| `node-exporter` | 호스트 메모리, 디스크, CPU (PRD 자원 실측과 디스크 경보) |

**넣지 않는 것**: Loki(로그는 Docker json 로그 + 로테이션으로 시작), Redis, 별도 워커 앱, Alertmanager(Grafana 경보로 충분), postgres-exporter(필요해지면 추가).

**앱 구조**: Spring Boot 단일 앱, 기능별 패키지
`collect`(API 클라이언트, 적재, 정기 수집, 상세) · `posting`(공고, 기관 조회) · `web`(Thymeleaf 컨트롤러) · `alert`(구독, 매칭, 발송) · `ops`(호출 기록, 잠금, 지표)

**무중단 배포 (Caddy)**
- Caddy upstream에 `app-blue:8080`과 `app-green:8080`을 둘 다 등록하고, 능동 헬스체크(`health_uri /actuator/health/readiness`, 관리 포트로)와 `lb_try_duration`(연결 실패 시 다른 쪽으로 재시도)을 켠다.
- `deploy.sh`: 새 색 실행 → readiness 대기(제한 시간) → 실패하면 새 색 중지(**롤백**) / 성공하면 옛 색에 SIGTERM(graceful shutdown) → 종료 확인.
- 커뮤니티에 "배포 직후 첫 요청 실패" 사례가 있다 ([caddy.community](https://caddy.community/t/blue-green-deployment-without-downtime/23511)). G4 외부 시험으로 반드시 확인하고, 실패하면 Caddy admin API로 upstream을 명시적으로 바꾸는 방식으로 전환한다.
- Flyway는 새 색이 시작할 때 실행한다. 확장 → 축소(expand/contract) 순서로만 스키마를 바꾼다.

**CI/CD (GitHub Actions, 공개 저장소)**
- PR: `./gradlew test` (Testcontainers)
- `main` 머지: `ubuntu-24.04-arm`에서 bootJar 빌드 → 이미지 → GHCR(공개) → SSH로 `deploy.sh <sha>` 실행 → G4 외부 시험 스크립트 실행 결과를 Actions 로그에 남긴다

**백업**: 호스트 cron → `pg_dump`(postgres 컨테이너 안에서 실행) → gzip → **age로 암호화**(이메일이 들어 있으므로) → rclone으로 OCI Object Storage + OCI 밖 1곳 → Healthchecks.io 핑. 보관 14일.

**외부 감시**: Healthchecks.io(정기 수집, 이메일 묶음 발송, 백업 heartbeat) + 외부 HTTP 감시(UptimeRobot 무료 플랜. 개인 비상업 용도).

## 2. 데이터 모델 (PostgreSQL)
| 테이블 | 핵심 컬럼 | 제약·인덱스 |
|---|---|---|
| `institution` | `code`(PK, `pblntInstCd`), `name` | |
| `posting` | `sn`(PK), `inst_code`, `title`, `bgng_date`, `end_date`, `recrut_se`, `recrut_nope`(숫자로 해석되면 int, 아니면 null + 원문), 자격·우대·전형 텍스트, `src_url`, `src_url_generic`(bool), `quality_flags`, `content_hash`, `status`(OPEN/CLOSED/CHECK), `from_backfill`(bool), `first_seen_at`, `last_seen_at`, `last_changed_at`, `detail_status`(NONE/DONE/FAILED), `steps`/`files`(jsonb) | `(status, end_date)`, `(inst_code, bgng_date)`, `bgng_date` |
| `posting_code` | `sn`, `type`(NCS/REGION/HIRE/EDU), `code` | PK(`sn`, `type`, `code`), 인덱스(`type`, `code`, `sn`). 다중값 필터를 표준 JPA로 처리하기 위해 배열 대신 조인 테이블 |
| `code_name` | `type`, `code`, `name` | PK(`type`, `code`). 응답의 `*NmLst`에서 채운다 (코드표 PDF 파싱 안 함) |
| `posting_change` | `id`, `sn`, `observed_at`, `changed_fields`(jsonb) | |
| `collect_run` | `id`, `kind`(BACKFILL/REGULAR/DETAIL/DIGEST), `started_at`, `finished_at`, `result`(COMPLETE/INCOMPLETE/FAILED), 건수들, `error` | |
| `api_call` | `id`, `called_at`, `endpoint`, `http_status`, `result_code` | `called_at` (일 합계 조회. 90일 뒤 삭제) |
| `subscription` | `id`, `channel`(EMAIL/DISCORD), `email`, `webhook_enc`(bytea), `webhook_hash`, `conditions`(jsonb), `kinds`, `status`(PENDING/ACTIVE/DISABLED), `confirm_token_hash`, `confirm_expires_at`, `confirm_sent_count`, `manage_token_hash`, `consent_version`, `consented_at`, `confirmed_at`, `disabled_reason`, `fail_count` | `email`, `webhook_hash`, 토큰 해시 유니크 |
| `notification` | `id`, `subscription_id`(FK **ON DELETE CASCADE**), `kind`(NEW/DEADLINE/DIGEST), `status`(PENDING/SENDING/SENT/FAILED/CANCELLED), `attempts`, `next_attempt_at`, `claimed_at`, `sent_at`, `last_error` | `(status, next_attempt_at)` |
| `notification_item` | `notification_id`, `subscription_id`, `sn`, `kind` | **유니크(`subscription_id`, `sn`, `kind`)** |

**상태 전이**
- 공고: `OPEN → CLOSED`(마감일 경과 또는 `ongoingYn=N`), `OPEN → CHECK`(완전 수집에서 빠짐) → 다시 나타나면 `OPEN`
- 구독: `PENDING → ACTIVE`(확인 POST) · `ACTIVE → DISABLED`(영구 실패) · 해지 = **행 삭제**(발송 기록까지 CASCADE로 파기) · `PENDING` 만료 = 삭제
- 발송: `PENDING → SENDING`(선점) → `SENT` / `FAILED`(재시도 소진) · 오래 멈춘 `SENDING` → `PENDING`(중복 가능성이 생기므로 지표로 센다) · 구독 조건이 바뀌어 대상이 아니게 되면 `CANCELLED`

**파기**: 해지와 미확인 만료는 즉시 행 삭제. 백업에는 14일 남는다(처리방침에 적는다). 운영 지표(발송 수, 중복 수)는 Prometheus 집계값으로만 남긴다.

## 3. 라우트
| 경로 | 설명 |
|---|---|
| `GET /`, `/jobs`, `/jobs/{sn}`, `/institutions/{code}`, `/privacy`, `/robots.txt`, (`/sitemap.xml` Should) | 공개 화면 |
| `GET /subscribe?{필터}` | 현재 필터를 채운 구독 폼 (이메일 / 디스코드 탭) |
| `POST /subscriptions/email`, `POST /subscriptions/discord` | 신청 (디스코드는 테스트 메시지 성공 시 바로 ACTIVE) |
| `GET /subscriptions/confirm?t=` → `POST /subscriptions/confirm` | 확인 화면 → 확인 |
| `GET /subscriptions/manage?t=` → `POST .../manage`(조건 수정), `POST .../unsubscribe` | 관리 |
| `POST /subscriptions/manage-link` | 이메일로 관리 링크 다시 받기 |
| `POST /subscriptions/discord/recover` | 웹훅 URL을 다시 입력해서 관리 |
| `POST /unsubscribe/one-click?t=` | RFC 8058 원클릭 해지 |
| 관리 포트 `:8081/actuator/*` | 공개 안 함. Prometheus만 내부망에서 접근 |

토큰이 쿼리 문자열에 있으므로 **Caddy 접근 로그에서 `t` 파라미터를 지우고**(Caddy 로그 필터), Spring도 쿼리를 로그에 남기지 않는다.

**외부 API**: `list` 정기(매시 7분, `ongoingYn=Y`, `numOfRows=1000`) 하루 24회 · `list` 최초 적재(공시 시작일 기준 월 단위, 약 36회, 1회만) · `detail` 새 진행 공고만(하루 수십 회 예상)

## 4. 와이어프레임 (텍스트)
- **공통**: 상단 메뉴(공고, 알림 받기) · 하단(마지막 수집 시각, 개인정보처리방침, 데이터 출처: 공공데이터포털)
- **홈**: 마감 임박 카드(D-3) · 오늘 시작 공고 목록 · 바로가기 버튼 2개 · 알림 구독 안내
- **목록**: 왼쪽(모바일은 접힘) 필터 폼 · 결과 표(기관, 제목, 직무, 지역, 고용형태, 구분, 마감일, D-day, 상태 배지) · 페이지네이션 · "이 조건으로 알림 받기" 버튼
- **상세**: 제목 + 기관 링크 · 핵심 정보 표 · 자격, 우대, 전형 · 전형 단계/첨부(또는 "미수집") · 원문 버튼(+ 잡알리오 링크) · 품질 경고
- **기관**: 기관명, 기준 기간(3년) · 요약(공고 수, 모집 예정 인원 합계, 합산 불가 건수) · 연도×월 공고 수 표 · NCS 분포 표 · 공고 이력 표 · 잡알리오 기관비교 링크
- **구독 폼**: 조건 · 알림 종류 · 이메일 또는 웹훅 URL · 고지문 + 동의 + 만 14세 확인
- **확인, 관리, 해지 화면**: 현재 조건 + 버튼 하나

## 5. ADR 후보와 결정안
| # | 주제 | 결정안 | 트레이드오프 |
|---|---|---|---|
| 1 | 언어와 빌드 | **Java 25(LTS) + Spring Boot 4.1 + Gradle** | 로컬 Java 17은 Gradle toolchain이 해결한다 |
| 2 | 앱 구조 | 단일 앱, 기능별 패키지 | 수집과 웹이 같은 프로세스라 수집 부하가 웹에 영향을 줄 수 있다. 규모상 감수 |
| 3 | 동시 실행 방지 | 스케줄은 **PostgreSQL advisory lock**(`pg_try_advisory_xact_lock`), 발송 선점은 **`FOR UPDATE SKIP LOCKED`**. 라이브러리(ShedLock) 없이 | 직접 짠 SQL이라 테스트로 보장해야 한다 |
| 4 | 무중단 배포 | Caddy 두 upstream + 헬스체크 + `lb_try_duration` + graceful shutdown | 첫 요청 실패 사례가 있다 → G4로 검증하고, 실패하면 admin API 전환 |
| 5 | 관측 | Prometheus + Grafana 직접 운영(내부 전용) + node-exporter. 외부는 Healthchecks.io + UptimeRobot | 메모리를 쓴다(실측 필요). 대신 외부 계정 의존과 데이터 반출이 없다 |
| 6 | 이메일 발송 | **SMTP 표준(`spring-boot-starter-mail`)으로 구현해서 서비스는 설정으로 바꿀 수 있게 한다.** 후보: **OCI Email Delivery**(Always Free 월 3,000건이라고 문서에 있지만 한도표는 0으로 충돌. 콘솔 확인 필요. 홈 리전이 한국이면 국외 이전 없음) / **Brevo**(하루 300통, 제3자 출처, EU 업체 → 국외 이전 고지) / Resend(월 3,000통에 하루 100통, 제3자 출처, 미국). 1순위 OCI, 안 되면 Brevo | 공식 가격 페이지 확인 전까지 한도는 미확인. 비동기 반송은 MVP 밖 |
| 7 | 웹훅 암호화 | JDK `javax.crypto` **AES-256-GCM** + 조회용 **HMAC-SHA256** 해시. 키는 환경변수 | 키를 잃으면 디스코드 구독 전부를 재등록해야 한다 → 키 백업 절차를 runbook에 둔다 |
| 8 | 요청 제한 | IP 기준은 앱 메모리의 고정 윈도(인스턴스별), 대상 기준은 DB 값(`confirm_sent_count` 등)으로 | 배포 중에는 인스턴스가 둘이라 IP 한도가 잠깐 2배가 된다. 감수 |
| 9 | 초기 설정값 | 확인 링크 24시간, 미확인은 만료 즉시 삭제, 외부 호출 재시도 3회(지수 백오프), 이메일 즉시 실패 3회 연속이면 비활성, API 경고 500/중단 900, 수집 매시 7분, 이메일 묶음 매일 08:00, 디스코드 마감 임박 09:00, 백업 03:00 보관 14일, IP 신청 제한 10분당 5회 | 전부 운영하면서 조정하는 초기값이다 |
| 10 | 백업 위치 | OCI Object Storage + OCI 밖 1곳(**사용자 결정**): Cloudflare R2(10GB 무료, 결제수단 등록 필요 여부 미확인) / Backblaze B2(10GB 무료) | 백업은 age로 암호화한다. 키는 서버와 사용자 PC에 따로 보관 |
| 11 | 다중값 코드 | `posting_code` 조인 테이블 | 배열 + GIN보다 행이 많지만 표준 JPA로 질의할 수 있다 |

출처: [Brevo·Resend 비교(제3자)](https://www.courier.com/integrations/compare/brevo-vs-resend), [Caddy reverse_proxy](https://caddyserver.com/docs/caddyfile/directives/reverse_proxy), [Cloudflare R2 가격](https://developers.cloudflare.com/r2/pricing/index.md), [Backblaze B2 가격](https://www.backblaze.com/cloud-storage/pricing), OCI Always Free 문서(03-feasibility)
