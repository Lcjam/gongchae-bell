# 설계 1라운드 독립안 (agy)

> **문서 상태**: 1라운드 독립 설계안  
> **기준 문서**: `CLAUDE.md`, `docs/prd.md` (v1.0 확정), `collab/03-feasibility.md`  
> **설계 원칙**: 결정 위주의 간결함, 최소 구성 원칙(새 의존성/컨테이너마다 필요 이유 명시), 수치 실측 전제.

---

## 1. 아키텍처

### (1) 컨테이너 구성 (OCI Ampere A1 2 OCPU / 12GB RAM 가정)
*최소 구성 원칙에 따라 별도 Redis나 메시지 큐 없이 RDB 중심으로 설계함.*

```
                 [Internet / Client]
                          │
                          ▼
             [caddy (Reverse Proxy, SSL)] (80/443)
                          │ (upstream 전환)
         ┌────────────────┴────────────────┐
         ▼ (Port 8081)                     ▼ (Port 8082)
   [app-blue (Spring Boot)]          [app-green (Spring Boot)]
         │                                 │
         └────────────────┬────────────────┘
                          ▼
            [postgres (PostgreSQL 17)] (5432, 내부망)
                          ▲
                          │ (Scrape / Actuator)
           [prometheus] ──┴── [grafana] (3000, 내부망)
```

| 컨테이너 | 역할 | 메모리 제한 (예산) | 왜 필요한가 (필요 이유) |
|---|---|---|---|
| `caddy` | 리버스 프록시, Let's Encrypt 자동 HTTPS, Blue/Green 무중단 라우팅 | 128MB | 도메인 SSL 인증서 자동 갱신 및 배포 중 다운타임 없는 upstream 교체를 위해 |
| `app-blue` / `app-green` | Spring Boot 앱 (WAS, 스케줄러, 웹 서빙, 알림 발송) | 각 1.5GB (JVM Max Heap 768MB) | 수집, 서빙, 알림의 핵심 비즈니스 로직을 무중단 배포하기 위해 |
| `postgres` | 메인 RDB (PostgreSQL 17) | 2.0GB (`shared_buffers=512MB`) | 3년치 공고, 구독 정보, 아웃박스 발송 큐의 영속 저장을 위해 |
| `prometheus` | 지표 수집 및 시계열 저장 | 512MB | Spring Boot Actuator 지표 및 수집/발송 지표 모니터링을 위해 |
| `grafana` | 모니터링 대시보드 시각화 (내부망/SSH 터널 접근) | 512MB | HTTP p95, 오류율, JVM 메모리, 수집/발송 상태 시각화를 위해 |
| **합계** | **평시 약 4.6GB / 배포 순간 약 6.1GB (12GB 중 여유 6GB 유지로 OOM 원천 방지)** |

### (2) 앱 내부 패키지 구조 (단일 모듈, 도메인 중심 계층형)
```
com.example.jobalio
├── collect           // 수집 도메인
│   ├── client        // 공공데이터 API RestClient (재시도, 로깅, 호출 카운터 연동)
│   ├── scheduler     // @Scheduled 정기 수집기 (1시간 주기, ShedLock 선점)
│   ├── service       // 완결성 검사, 멱등 upsert, 해시 변경 감지, 상태 판정 로직
│   └── entity        // JobPosting, JobDetail, PostingChange, CollectRun, ApiCallLog
├── alert             // 알림 도메인
│   ├── subscriber    // 이메일/디스코드 구독 신청, 더블옵트인, 관리/해지 토큰 처리
│   ├── sender        // Discord Webhook Sender (429 retry-after), Email Sender (Resend API)
│   ├── worker        // Outbox 발송 폴러 및 묶음 발송 스케줄러 (조건부 상태 선점)
│   └── entity        // Subscription, OutboxAlert, RateLimitBucket
├── web               // 웹 화면 도메인 (Thymeleaf MVC)
│   ├── controller    // JobController (/jobs), HomeController (/), InstitutionController, AlertController
│   └── dto           // 필터 DTO, 화면 렌더링 DTO
└── global            // 공통 인프라
    ├── config        // Security, Caddy 헤더, RestClient, ShedLock 설정
    ├── crypto        // AES-256-GCM JPA AttributeConverter (웹훅 URL 암호화)
    ├── ratelimit     // IP/이메일 기반 요청 제한 인터셉터 (Caffeine + Bucket4j)
    └── exception     // 전역 예외 처리
```

### (3) CI/CD 및 Blue/Green 무중단 배포 구체 방식
1. **GitHub Actions**: `main` 푸시 시 테스트 통과 ➡️ `ubuntu-24.04-arm` 러너에서 ARM64 이미지 빌드 ➡️ GHCR에 커밋 SHA 태그 푸시.
2. **배포 스크립트 (SSH 실행)**:
   - 현재 활성 포트(예: 8081 Blue) 확인 ➡️ 비활성 컨테이너(8082 Green)를 새 이미지로 기동.
   - Actuator readiness 헬스체크 (`curl -f http://localhost:8082/actuator/health/readiness`) 5초 간격 최대 60초 폴링.
   - 통과 시: Caddyfile의 upstream을 8082로 변경 후 `caddy reload` 실행 (다운타임 0초 전환).
   - 전환 후: 기존 Blue 컨테이너에 SIGTERM 전송 (`server.shutdown=graceful`, 대기 20초) ➡️ 이전 컨테이너 정지.
   - 실패 시: Green 컨테이너 즉시 중지 및 롤백, 디스코드 배포 실패 알림 발송.

### (4) 관측 스택
- Spring Boot Actuator (`/actuator/prometheus`)를 Prometheus가 15초 주기로 스크랩 ➡️ Grafana 대시보드 표출.
- 외부 감시: Healthchecks.io (정기 수집/발송 완료 heartbeat ping), UptimeRobot (외부 HTTPS 5분 주기 생존 확인).

### (5) 백업 흐름
- 매일 새벽 4시 Linux Crontab:
  1. `pg_dump -Fc`로 백업 생성 (`jobdb_YYYYMMDD.dump`).
  2. OCI CLI로 OCI Object Storage(`jobdb-backup` 버킷)에 업로드.
  3. 사본 1개를 OCI 밖 외부 저장소(Cloudflare R2)로 전송.
  4. 14일 경과 백업본 자동 삭제.

---

## 2. 데이터 모델

### (1) 테이블 명세

```sql
-- 1. 공고 본체
CREATE TABLE job_posting (
    sn BIGINT PRIMARY KEY,                  -- recrutPblntSn
    title VARCHAR(300) NOT NULL,            -- recrutPbancTtl
    inst_cd VARCHAR(50) NOT NULL,           -- pblntInstCd
    inst_nm VARCHAR(150) NOT NULL,          -- instNm
    ncs_cd_lst VARCHAR(100),                -- R600020 등
    ncs_cd_nm_lst VARCHAR(200),
    hire_type_lst VARCHAR(50),              -- R1010 등
    recrut_se VARCHAR(20) NOT NULL,         -- R2010(신입), R2030(신입+경력)
    work_rgn_lst VARCHAR(100),
    recrut_nope INT,                        -- 모집인원 (null 가능)
    pbanc_bgng_ymd DATE NOT NULL,           -- 공고 시작일
    pbanc_end_ymd DATE NOT NULL,            -- 공고 마감일
    status VARCHAR(20) NOT NULL,            -- ONGOING, CLOSED, CHECK_REQUIRED
    original_src_url VARCHAR(1000),
    is_main_page_url BOOLEAN DEFAULT FALSE, -- 기관 첫 화면 여부
    content_hash CHAR(64) NOT NULL,         -- SHA-256 변경 감지 해시
    first_seen_at TIMESTAMPTZ NOT NULL,
    last_seen_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_job_filter ON job_posting (status, pbanc_end_ymd, pbanc_bgng_ymd DESC);
CREATE INDEX idx_job_inst ON job_posting (inst_cd, pbanc_bgng_ymd DESC);
CREATE INDEX idx_job_recrut_se ON job_posting (recrut_se);

-- 2. 신규 공고 상세 (1:1 선택적 적재)
CREATE TABLE job_detail (
    sn BIGINT PRIMARY KEY REFERENCES job_posting(sn) ON DELETE CASCADE,
    qualification TEXT,
    preference TEXT,
    steps_json JSONB,
    files_json JSONB,
    collected_at TIMESTAMPTZ NOT NULL
);

-- 3. 공고 변경 관측 이력
CREATE TABLE posting_change (
    id BIGSERIAL PRIMARY KEY,
    sn BIGINT NOT NULL REFERENCES job_posting(sn) ON DELETE CASCADE,
    changed_field VARCHAR(50) NOT NULL,
    before_val TEXT,
    after_val TEXT,
    observed_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_change_sn ON posting_change (sn, observed_at DESC);

-- 4. 수집 실행 이력
CREATE TABLE collect_run (
    id BIGSERIAL PRIMARY KEY,
    start_at TIMESTAMPTZ NOT NULL,
    end_at TIMESTAMPTZ,
    result VARCHAR(20) NOT NULL,            -- SUCCESS, PARTIAL, FAIL
    total_count INT,
    received_count INT,
    new_count INT,
    updated_count INT,
    closed_count INT,
    error_message TEXT
);

-- 5. 일일 API 호출량 카운터
CREATE TABLE api_call_log (
    call_date DATE PRIMARY KEY,             -- KST 기준 날짜
    call_count INT NOT NULL DEFAULT 0,
    last_call_at TIMESTAMPTZ
);

-- 6. 알림 구독 (이메일 & 디스코드)
CREATE TABLE subscription (
    id BIGSERIAL PRIMARY KEY,
    channel_type VARCHAR(20) NOT NULL,      -- EMAIL, DISCORD
    email VARCHAR(255),                     -- EMAIL일 때
    webhook_url_encrypted TEXT,             -- DISCORD일 때 (AES-256-GCM)
    webhook_url_hash CHAR(64),              -- DISCORD 중복 체크용 SHA-256
    filter_criteria_json JSONB NOT NULL,    -- 필터 조건
    notify_types VARCHAR(50) NOT NULL,      -- NEW, DEADLINE, ALL
    status VARCHAR(20) NOT NULL,            -- UNVERIFIED, ACTIVE, UNSUBSCRIBED, DISABLED
    manage_token_hash CHAR(64) NOT NULL UNIQUE, -- SHA-256
    verify_token_hash CHAR(64),             -- SHA-256 (더블옵트인 확인용)
    token_expired_at TIMESTAMPTZ,
    fail_count INT NOT NULL DEFAULT 0,
    terms_version VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    verified_at TIMESTAMPTZ
);
CREATE INDEX idx_sub_active ON subscription (status, channel_type);
CREATE INDEX idx_sub_webhook_hash ON subscription (webhook_url_hash);

-- 7. 알림 발송 아웃박스 (Outbox)
CREATE TABLE outbox_alert (
    id BIGSERIAL PRIMARY KEY,
    subscription_id BIGINT NOT NULL REFERENCES subscription(id) ON DELETE CASCADE,
    job_sn BIGINT NOT NULL REFERENCES job_posting(sn),
    alert_type VARCHAR(20) NOT NULL,        -- NEW_JOB, DEADLINE_D3
    status VARCHAR(20) NOT NULL,            -- PENDING, SENDING, SENT, FAILED
    retry_count INT NOT NULL DEFAULT 0,
    scheduled_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    sent_at TIMESTAMPTZ,
    CONSTRAINT uk_outbox_dedup UNIQUE (subscription_id, job_sn, alert_type)
);
CREATE INDEX idx_outbox_poll ON outbox_alert (status, scheduled_at);

-- 8. ShedLock 테이블 (동시 실행 방지)
CREATE TABLE shedlock (
    name VARCHAR(64) PRIMARY KEY,
    lock_until TIMESTAMPTZ NOT NULL,
    locked_at TIMESTAMPTZ NOT NULL,
    locked_by VARCHAR(255) NOT NULL
);
```

### (2) 상태 전이 모델
1. **공고 상태 (`job_posting.status`)**:
   - `ONGOING` ➡️ `CLOSED`: 마감일(KST) 경과 또는 완전 수집 시 `ongoingYn=N` 확인.
   - `ONGOING` ➡️ `CHECK_REQUIRED`: 완전 수집에서 마감일 전 공고가 누락된 경우.
   - `CHECK_REQUIRED` ➡️ `ONGOING`: 차기 수집에서 다시 목록에 포함된 경우.
   - `CHECK_REQUIRED` ➡️ `CLOSED`: 마감일 경과 시.
2. **구독 상태 (`subscription.status`)**:
   - `UNVERIFIED` ➡️ `ACTIVE`: 더블옵트인 확인 링크(POST) 통과 (디스코드는 등록 시 테스트 성공 즉시 `ACTIVE`).
   - `UNVERIFIED` ➡️ `영구 삭제`: 24시간 미확인 시 자동 삭제.
   - `ACTIVE` ➡️ `UNSUBSCRIBED`: 해지 요청(POST) 수신 시 즉시 이메일/발송데이터 영구 삭제.
   - `ACTIVE` ➡️ `DISABLED`: Discord 404 즉시, 또는 이메일 5xx 연속 3회 실패 시.
3. **발송 작업 상태 (`outbox_alert.status`)**:
   - `PENDING` ➡️ `SENDING`: 워커가 `UPDATE ... SET status='SENDING' WHERE id = :id AND status='PENDING'`으로 원자적 선점.
   - `SENDING` ➡️ `SENT`: 발송 성공 시.
   - `SENDING` ➡️ `PENDING`: 429 레이트리밋 수신 시 `retry_after` 적용 후 지연 대기.
   - `SENDING` ➡️ `FAILED`: 3회 재시도 초과 실패 시.

### (3) 개인정보 파기 규칙 반영 방식
- **미확인 구독 파기**: 스케줄러가 매시간 `WHERE status = 'UNVERIFIED' AND created_at < NOW() - INTERVAL '24 HOURS'` 레코드 영구 DELETE.
- **해지 시 즉시 파기**: 해지 POST 트랜잭션에서 `subscription` 레코드 및 관련 `outbox_alert` 즉시 DELETE.
- **발송 이력 파기**: `SENT` 완료 후 14일 경과 레코드 자동 DELETE.

---

## 3. 라우트 설계

### (1) 사용자 화면 URL 및 POST 엔드포인트
| 경로 | 메서드 | 역할 및 동작 |
|---|---|---|
| `/` | `GET` | 홈 화면 (마감 임박 D-3, 오늘 시작 공고, 바로가기) |
| `/jobs` | `GET` | 공고 목록 및 검색 (`ncs`, `rgn`, `hire`, `se`, `status`, `q`, `sort`, `page`) |
| `/jobs/{sn}` | `GET` | 공고 상세 화면 |
| `/institutions/{pblntInstCd}` | `GET` | 기관별 3년 공고 이력, 시기 패턴 표, 직무 분포 표 |
| `/privacy` | `GET` | 개인정보처리방침 (법 제30조 고지) |
| `/alerts/subscribe/email` | `POST` | 이메일 구독 신청 ➡️ 더블옵트인 안내 화면 |
| `/alerts/subscribe/discord` | `POST` | 디스코드 구독 신청 ➡️ 테스트 메시지 발송 후 관리 링크 안내 화면 |
| `/alerts/verify` | `GET` | 이메일 확인 링크 ➡️ [구독 확인 완료] 버튼 폼 렌더링 (GET 상태 변경 방지) |
| `/alerts/verify` | `POST` | 구독 확인 완료 처리 ➡️ `ACTIVE` 전환 |
| `/alerts/manage/{token}` | `GET` | 구독 관리 화면 (현재 조건 조회, 수정 폼, 해지 버튼) |
| `/alerts/manage/{token}/update` | `POST` | 구독 조건 수정 폼 제출 |
| `/alerts/manage/{token}/unsubscribe` | `POST` | 구독 해지 처리 (즉시 파기) |
| `/alerts/unsubscribe/one-click` | `POST` | RFC 8058 원클릭 해지 엔드포인트 (`List-Unsubscribe-Post`) |

### (2) 운영용 엔드포인트 및 공개 여부
- `/actuator/health/readiness`, `/actuator/health/liveness`: Caddy에서 외부 차단 (localhost 전용).
- `/actuator/prometheus`: Caddy에서 외부 차단 (Prometheus 내부 도커 네트워크 전용).
- 그 외 Actuator 엔드포인트: 노출 설정 제외 (`management.endpoints.web.exposure.include=health,prometheus`).

### (3) 외부 API 호출 명세
- **목록 API (`/list`)**: `apis.data.go.kr/1051000/recruitment/list`  
  - 파라미터: `ongoingYn=Y&numOfRows=1000&pageNo=1`  
  - 주기: 1시간마다 1회 (정각) ➡️ 일 24회  
- **상세 API (`/detail`)**: `apis.data.go.kr/1051000/recruitment/detail`  
  - 파라미터: `sn={sn}`  
  - 주기: 수집 회차에서 신규 발견된 공고만 1회씩 호출 ➡️ 일 약 30회  
- **호출량 통제**: 일 합계 약 54회 (개발 한도 1,000회의 5.4%). 일 500회 도달 시 경보, 900회 시 수집 자동 차단.

---

## 4. 와이어프레임 (텍스트 블록)

- **홈 (`/`)**:
  - `[상단 GNB]` 로고, 공고 목록 링크, 데이터 기준 시각 배지
  - `[바로가기 카드]` "신입 지원 가능 모아보기", "정보통신(IT) 공고 모아보기", "알림 구독하기"
  - `[마감 임박 섹션]` D-0~D-3 진행 공고 카드 (제목, 기관, D-day, 마감일)
  - `[오늘 시작 공고 섹션]` 오늘 공고 시작일인 공고 리스트
  - `[하단 푸터]` 개인정보처리방침 링크, 잡알리오 데이터 출처 표기
- **공고 목록 (`/jobs`)**:
  - `[검색/필터 영역]` 직무(NCS), 지역, 고용형태, 채용구분(신입가능/신입/경력), 진행여부, 제목 검색 인풋, [검색] 버튼
  - `[구독 액션 바]` "현재 검색 조건으로 알림 받기" 버튼
  - `[공고 테이블]` 기관명 | 제목 | 직무 | 지역 | 채용구분 | 마감일(D-day)
  - `[페이지네이션]`
- **공고 상세 (`/jobs/{sn}`)**:
  - `[헤더 카드]` 기관명, 공고 제목, 접수 기간, D-day, 모집인원, 직무/지역
  - `[상세 정보]` 자격 요건, 우대 사항, 전형 단계 타임라인(상세 수집 시), 첨부파일 다운로드 목록
  - `[액션 버튼]` "공식 원문 바로가기" (기관 첫 화면 링크인 경우 "잡알리오 공고 보기" 병기), "해당 기관 채용 이력 보기"
- **기관 상세 (`/institutions/{pblntInstCd}`)**:
  - `[기관 개요]` 기관명, 최근 3년 총 공고 수, 총 채용 예정 인원 합계
  - `[시기 패턴 표]` 연도별 × 1~12월 월별 공고 건수 매트릭스 표
  - `[직무 분포 표]` NCS 직무별 공고 건수 및 비율 표
  - `[과거 공고 이력]` 최근 3년간 등록된 공고 타임라인 리스트
- **구독 관리 (`/alerts/manage/{token}`)**:
  - `[구독 정보]` 수신 채널(이메일/디스코드), 등록일자, 알림 종류(새 공고/마감 임박)
  - `[조건 수정]` 현재 필터 조건 체크박스 수정 폼 ➡️ [수정 저장] 버튼
  - `[해지 영역]` 경고 문구 및 [알림 해지하기] POST 버튼

---

## 5. ADR 후보 및 결정안

### 1. Java 버전
- **선택지**: Java 17 vs Java 21 vs Java 25
- **결정**: **Java 21 LTS**
- **트레이드오프**: Spring Boot 4.x 안정 지원 및 Virtual Threads 활용 가능. 로컬 환경(17)을 올려야 하지만 25 대비 라이브러리 생태계가 안정적임.

### 2. 스케줄·발송 작업 동시 실행 방지 방식
- **선택지**: Redis Redisson vs ShedLock (JDBC) vs DB 상태 선점 (`UPDATE ... WHERE status='PENDING'`)
- **결정**: **ShedLock (JDBC) (스케줄러) + 조건부 상태 선점 쿼리 (발송 워커)**
- **트레이드오프**: Redis 컨테이너 추가 없이 PostgreSQL만으로 Blue/Green 배포 중 수집 스케줄 중복 실행과 발송 워커 경합을 완벽 차단.

### 3. 무중단 배포 방식
- **선택지**: Nginx upstream reload vs Caddy API reload vs Docker Swarm
- **결정**: **Caddy 리버스 프록시 + Docker Compose Blue/Green (8081/8082)**
- **트레이드오프**: Caddy가 Let's Encrypt SSL 자동 갱신을 자체 내장하여 인증서 갱신 스크립트 불필요. `caddy reload`로 0초 무중단 전환.

### 4. 관측 스택
- **선택지**: Grafana Cloud 무료 플랜(외부 SaaS) vs 로컬 Prometheus+Grafana 컨테이너 직접 운영
- **결정**: **로컬 Prometheus + Grafana 경량 컨테이너 직접 운영 (메모리 각 512MB 제한)**
- **트레이드오프**: 외부 SaaS 계정 종속 없이 단일 VM 내에서 포트폴리오 관측 환경을 자립적으로 재현 가능 (메모리 예산 1GB 내 소화).

### 5. 이메일 발송 서비스
- **선택지**: Resend vs Brevo vs AWS SES
- **결정**: **Resend (API 방식)**
- **근거 및 트레이드오프**:
  - Resend: **일 100통 / 월 3,000통 무료** (*공식: `resend.com/pricing`*). 브랜딩 로고 강제 없음. 미국 소재 서버이므로 개인정보처리방침에 "국외 이전(미국)" 필수 고지.
  - Brevo: 일 300통 무료이나 메일 하단에 "Sent with Brevo" 로고 강제로 포트폴리오 완성도 저하 (*공식: `brevo.com/pricing`*).
  - AWS SES: 서울 리전 사용 가능하나 무료 티어 부재 및 샌드박스 해제 심사 불확실성으로 배제.

### 6. 웹훅 URL 암호화 방식
- **선택지**: DB 평문 저장 vs JPA AttributeConverter + AES-256-GCM
- **결정**: **JPA AttributeConverter + AES-256-GCM (SecretKey 환경변수 주입)**
- **트레이드오프**: 복호화가 가능하면서도 DB 덤프 유출 시 디스코드 웹훅 토큰 보호. 조회용으로 `webhook_url_hash`(SHA-256) 컬럼 병행.

### 7. 요청 제한 (Rate Limiting) 구현
- **선택지**: Redis + Bucket4j vs 인메모리 Caffeine Cache + Bucket4j
- **결정**: **인메모리 Caffeine Cache + Bucket4j (Spring HandlerInterceptor)**
- **트레이드오프**: Redis 추가 없이 단일 WAS 메모리 수 MB로 IP별/이메일별 요청 제한 가볍게 구현. 배포 시 카운터 초기화는 감수 가능한 수준.

### 8. 초기 설정값
- **토큰 만료**: 더블옵트인 확인 토큰 **24시간**
- **발송 재시도**: 최대 **3회** (지수 백오프: 1분, 5분, 15분)
- **연속 실패 비활성화**: 이메일 **3회 연속 실패**, 디스코드 404는 **즉시** 비활성화
- **파기 시점**: 미확인 구독 **24시간 후**, 해지 구독 **즉시 파기**, 발송 로그 **14일 후**
- **요청 제한**: IP당 구독 신청 **분당 5회**, 확인 메일 재발송 **쿨다운 1분**

### 9. 백업 방식과 OCI 밖 위치 후보
- **백업 방식**: `pg_dump -Fc` (압축 바이너리 덤프)
- **1차 저장소**: OCI Object Storage (Always Free 20GB)
- **OCI 밖 2차 사본 후보**: **Cloudflare R2**
- **근거 및 트레이드오프**: Cloudflare R2 무료 티어는 **저장 10GB, 월 100만 회 연산 무료, Egress(아웃바운드 전송료) 0원** (*공식: `cloudflare.com/developer-platform/products/r2/`*). S3 호환 API를 지원하여 별도 라이브러리 없이 OCI 밖 격리 백업 사본 저장소로 최적.
