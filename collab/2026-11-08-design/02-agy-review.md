# 설계 2라운드 · agy(Antigravity) 교차 비판 보고서

> **검토 대상**: `collab/2026-11-08-design/`의 `01-claude.md`, `01-codex.md`, `01-agy.md`  
> **기준**: `CLAUDE.md`, `docs/prd.md` (v1.0 확정), 최소 구성 원칙(새 의존성·컨테이너 최소화)

---

## 1. 사실 오류 · 근거 없는 수치 · 미검증 외부 주장 지적

### (1) claude 01의 OCI Email 및 Caddy 배포 전제 오류
- **OCI Email Delivery 우선 제안 오류**: claude는 1순위로 OCI Email Delivery를 언급했으나, OCI Always Free는 서비스 한도표(`max-emails-day`)상 24시간 발송량이 `0`으로 잠겨 있어 서포트 티켓 승인 없이 발송이 불가능합니다 (공식: OCI Email Delivery Service Limits). MVP 기본 발송기로 둘 수 없습니다.
- **Caddy 능동 헬스체크 기반 Blue/Green의 위험성**: Caddyfile에 두 upstream을 상시 두고 `health_uri`와 `lb_try_duration`에만 의존하는 방식은, 컨테이너 기동 중 미묘한 타이밍에 502/503이 발생할 수 있습니다. Caddy 커뮤니티에서도 지적되었듯, `caddy reload`(또는 admin API)로 upstream 포트를 명시적으로 스위칭하는 것이 안전합니다.
- **Java 25 LTS 표기**: Java 25는 2025년 9월 출시된 LTS이지만, Spring Boot 4.1.x 환경 및 ARM64 JRE 베이스 이미지의 검증 안정성을 감안할 때 현 시점에서는 Java 21 LTS가 가장 검증된 표준입니다.

### (2) codex 01의 과도한 아키텍처 비대화 및 미확인 전제
- **AWS S3 SDK 도입**: OCI Object Storage S3 호환 API를 쓰기 위해 무거운 `AWS SDK v2` 의존성을 앱에 추가하는 것은 최소 구성 원칙에 위배됩니다. 앱 런타임이 아닌 호스트 백업 CLI(`rclone` 또는 OCI CLI)로 충분히 처리 가능합니다.
- **`erasure_journal` (동기 파기 대장)의 오버엔지니어링**: 구독 해지 시마다 OCI Object Storage에 동기적으로 파기 대장을 쓰고 확인한 뒤 DB를 삭제하는 방식은, 단순 해지 API 호출 하나에 외부 클라우드 스토리지 가용성을 결합해 장애 지점을 늘립니다. PRD FR-A6에 명시된 대로 "DB 즉시 파기 + 14일 백업 보관 후 자동 소멸 고지"로 충분합니다.
- **Brevo 웹훅 콜백 수신 엔드포인트 누락 판단**: PRD v1.0에서 비동기 반송 웹훅 수신기는 MVP 제외 항목임에도 `/callbacks/brevo`를 설계에 포함시켰습니다.

### (3) agy 01의 불필요한 의존성 및 미검증 주장
- **ShedLock 및 Bucket4j 의존성 추가**: PostgreSQL 17이 이미 있으므로 Advisory Lock이나 DB 조건부 갱신으로 충분한데 외부 라이브러리(ShedLock, Bucket4j, Caffeine)를 불필요하게 추가했습니다.
- **Cloudflare R2 결제수단 미검증**: R2 무료 티어(10GB)는 사실이나, Cloudflare 계정 등록 시 신용카드 등록이 필수입니다. 사용자의 결제수단 등록 여부를 확인하지 않고 확정안으로 제시한 것은 미검증 주장입니다.

---

## 2. ADR별 세 안의 결정 비교 표

| 주제 | claude | codex | agy | 권고안 및 이유 |
|---|---|---|---|---|
| **Java 버전** | Java 25 | Java 21 | Java 21 | **Java 21 LTS 권고**. Spring Boot 4.1, Virtual Threads, ARM64 환경에서 가장 안정적이고 호환성 검증됨. |
| **동시 실행 방지** | Postgres Advisory Lock + `SKIP LOCKED` | DB `job_lease` 펜스 + `SKIP LOCKED` | ShedLock (JDBC) + DB 조건부 선점 | **claude/codex 방식(DB 네이티브 락) 권고**. ShedLock 라이브러리 없이 `pg_try_advisory_xact_lock`과 `FOR UPDATE SKIP LOCKED`로 충분. |
| **무중단 배포** | Caddy 두 upstream 등록 + 능동 헬스체크 | Caddy 설정 reload (API) | Caddyfile upstream 포트 변경 + `caddy reload` | **agy/codex 방식(명시적 upstream 교체) 권고**. 헬스체크 기반 묵시적 스위칭은 배포 중 일시 502 오류 유발 위험이 큼. |
| **관측 스택** | Prometheus + Grafana + node-exporter | Prometheus + Grafana (내부망) | Prometheus + Grafana (내부망) | **codex/agy 방식(node-exporter 제외) 권고**. 호스트 자원은 cgroup/docker stats로 충분하며 12GB 메모리 절약. |
| **이메일 서비스** | OCI Email(1순위) / Brevo(2순위) | Brevo Transactional API | Resend API | **Resend 1순위, Brevo 2순위 (표준 인터페이스 뒤 격리) 권고**. OCI Email은 한도 0으로 탈락. Resend는 로고 강제가 없고 API가 깔끔함(일 100통). |
| **웹훅 URL 암호화** | JDK AES-256-GCM + HMAC-SHA256 | JDK AES-256-GCM + HMAC | JPA Converter AES-256-GCM + SHA-256 | **세 안 일치**. JDK 내장 AES-256-GCM으로 암호화하고 조회용 HMAC/해시 분리. |
| **요청 제한** | 앱 메모리 고정 윈도 + DB | DB `rate_limit_bucket` 테이블 | 인메모리 Caffeine + Bucket4j | **codex의 DB 기반 카운터 권고**. Blue/Green 배포 중 두 앱 간 카운터 일관성을 유지하면서 추가 라이브러리 없음. |
| **백업 도구** | `pg_dump` + age 암호화 + `rclone` | `pg_dump` + `restic` 암호화 | `pg_dump -Fc` 압축 바이너리 | **claude 방식(`rclone` + 암호화) 권고**. rclone은 OCI Object Storage 및 다양한 외부 스토리지를 단일 CLI로 지원. |

---

## 3. PRD v1.0 범위 초과 및 최소 구성 원칙 위반 항목 (제거 권고)

1. **codex의 AWS SDK v2 및 `erasure_journal` (동기 파기 대장)**:
   - *제거 이유*: 해지 시 외부 S3 API를 동기 호출하는 것은 과도한 복잡성이며 가용성 저하 요인. PRD 범위(14일 백업 보관 후 소멸)를 초과함.
2. **codex의 `/callbacks/brevo` (외부 웹훅 수신 파이프라인)**:
   - *제거 이유*: PRD FR-A5 및 알림 리뷰 final에서 "비동기 반송 처리는 MVP 제외"로 확정됨. 불필요한 CSRF 예외 및 외부 공격 표면 증가.
3. **claude의 `node-exporter` 컨테이너**:
   - *제거 이유*: 단일 노드 12GB 환경에서 컨테이너 개수를 최소화해야 함. cgroup 및 docker stats로 충분.
4. **agy의 `ShedLock` 및 `Bucket4j/Caffeine` 라이브러리**:
   - *제거 이유*: PostgreSQL 네이티브 락 및 DB 카운터로 100% 대체 가능하므로 의존성 제거.

---

## 4. 빠지면 PRD 수용 기준을 충족 못 하는 설계 누락

1. **Caddy 접근 로그에서 토큰 파라미터(`?token=`, `?t=`) 마스킹 (claude 지적)**:
   - PRD FR-A6: "토큰은 앱·프록시·HTTP 클라이언트 로그에서 가린다." Caddyfile `log` 디렉티브에서 쿼리스트링 제거 설정이 필수.
2. **Discord 웹훅 발송 시 리다이렉트 추적 금지 및 사설 IP 차단 (codex/agy 지적)**:
   - PRD FR-A2: SSRF 방지. HTTP Client 설정에서 `followRedirects(false)` 및 DnsResolver 검증 로직 필수.
3. **일일 묶음 이메일 발송 스케줄러 명세 (FR-A4)**:
   - 새 공고와 마감 임박 D-3 공고를 하나의 이메일로 번들링하여 매일 아침(예: KST 08:00) 발송하는 배치 트리거 명시 필요.

---

## 5. agy 01 초안에서 철회 및 수정할 점

1. **ShedLock 의존성 철회 ➡️ PostgreSQL Native 락 채택**:
   - `pg_try_advisory_xact_lock`과 `FOR UPDATE SKIP LOCKED`를 직접 사용하여 외부 의존성 배제.
2. **Bucket4j/Caffeine 의존성 철회 ➡️ DB 카운터 기반 요청 제한 채택**:
   - `rate_limit_bucket` 테이블 기반으로 Blue/Green 배포 중에도 일관된 요청 제한 구현.
3. **Cloudflare R2 확정안 철회 ➡️ 백업 위치 선택지로 변경**:
   - 신용카드 등록이 필요 없는 옵션(사용자 SFTP/로컬 전송)을 우선 고려할 수 있도록 사용자 선택지로 이관.

---

## 6. 사용자 결정이 꼭 필요한 항목 목록 (기술 선택 제외)

> *아키텍처, Java 버전, 락 방식, Caddy 설정 등 기술적 선택은 에이전트 간 합의로 종결하며, 사용자에게는 인프라 및 외부 계정 관련 의사결정만 요청합니다.*

1. **이메일 발송 서비스 계정 및 도메인 DNS 권한**:
   - 발송 서비스로 **Resend (일 100통 무료, 깔끔한 텍스트)** 또는 **Brevo (일 300통 무료, 하단 로고 포함)** 중 어느 서비스에 가입하여 API 키를 발급하고, 보유 도메인 DNS에 SPF/DKIM TXT 레코드를 설정할 수 있습니까?
2. **OCI 밖 2차 백업 사본 저장 위치**:
   - OCI 재해 대비 2차 사본을 **Cloudflare R2 (무료 10GB, 신용카드 등록 필요)**에 둘 것인지, 아니면 **개인 PC/로컬 서버로의 SFTP 전송** 방식을 선호합니까?
3. **운영 알림용 디스코드 채널 웹훅**:
   - 시스템 장애, 배포 실패, API 호출 한도 경보를 수신할 본인 소유의 Discord 채널 웹훅 URL을 준비할 수 있습니까?
