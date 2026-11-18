# #14 배포 자동화 계약 · agy

> 2026-11-17 (2026-11-18 재개 갱신) · 작성: agy · 검수: codex (root) · 브랜치: `ops/#14-bluegreen-deploy`
> 기반 `origin/main`: `000dce555e9eb69ef3184dd5f80aa1d499a1f602` · 참조 이슈: [#14](https://github.com/Lcjam/gongchae-bell/issues/14)
> 관련 문서: [PRD G4·§6·§7](../../docs/prd.md), [아키텍처](../../docs/design/architecture.md), [ADR-0005](../../docs/adr/0005-blue-green-caddy.md), [ADR-0006](../../docs/adr/0006-observability.md), [ADR-0010](../../docs/adr/0010-rate-limit-and-budget.md), [ADR-0012](../../docs/adr/0012-backup-restic-pull.md), [ADR-0013](../../docs/adr/0013-erasure-journal-host-file.md), [ADR-0014](../../docs/adr/0014-initial-settings.md), [ADR-0015](../../docs/adr/0015-no-spring-security.md)

## 1. 시작 범위와 비변경 경계

### 이번 이슈(#14) 포함 범위
1. **ARM64 멀티스테이지 컨테이너 이미지**: `Dockerfile`, `.dockerignore` (`eclipse-temurin:21-jdk-noble` 빌드 → `eclipse-temurin:21-jre-noble` 실행, UID 1000 비루트 실행, `-Xmx512m` 기본 힙, `.env`/`.tools`/키/덤프/`deploy` 등 민감·로컬 자료 제외).
2. **운영 Compose (`compose.prod.yml`)**: `postgres:17`, `app-blue`/`app-green` (`profiles` 분리, 개별 이미지 태그, `stop_grace_period: 40s`), `caddy:2-alpine`. 공개 포트는 `80`/`443`만, `postgres`는 `internal: true` 백엔드 망 격리, `erasure.log` 호스트 디렉터리(`/var/lib/gongchae-bell/erasure`) 분리 마운트. 선택 환경변수의 기본값 설명은 Compose와 `docs/runbook.md`에 둔다.
3. **Caddy 설정 (`deploy/caddy/Caddyfile`)**: 명시 upstream 파일(`runtime/upstream.caddy`) 디렉터리 마운트 기반 atomic 전환·`caddy validate`·`caddy reload`, 보안 헤더(HSTS, `X-Content-Type-Options`, `X-Frame-Options`, `Content-Security-Policy`, `?Referrer-Policy`), `/subscriptions*` `no-referrer`/`no-store`/`noindex`, 접근 로그 `t` 쿼리·`Referer`·`Authorization`·`Cookie` 제거, 프록시 IP 단일 홉(`X-Forwarded-For` 덮어쓰기) 경계.
4. **배포·롤백 스크립트 (`deploy/deploy.sh`) & VM 밖 G4 프로브 (`deploy/g4-probe.sh`)**:
   - ADR-0005 순서 엄수: `비활성 색 기동 → 내부 readiness/핵심 화면(/, /jobs, /robots.txt) → Caddy validate/reload → 전환 직후 sanity 확인(구 색 실행 유지) → VM 밖 G4 외부 시험 판정 → [G4 성공] 구 색 graceful stop(40s) + 활성 상태 커밋(finalize) / [G4 실패] 실행 중인 구 색 upstream으로 즉시 reload 복귀 + 새 색 중지(abort)`.
   - `flock` 상호 배제, `deploy_id`로 CI·외부 프로브·서버 상태(`pending_*`, `active_deploy_id`, `prev_deploy_id`)·중단 재실행·롤백을 동일 배포 단위로 식별, `active_color` vs `upstream.caddy` 교차 검증(불일치·잔류 `pending_deploy_id` 시 fail-closed).
   - 초기 `bootstrap` 모드와 정상 `bluegreen` 무중단 rollout·사후 `rollback` 분리.
   - `g4-probe.sh`는 GitHub Actions 러너(VM 밖)에서 배포 전·중·후 연속 요청을 실행하고 runbook 고정 기준(5xx/연결·TLS/타임아웃/non-200 실패 0) 미달 시 같은 `deploy_id`로 즉시 `abort` 트리거.
5. **CI/CD 워크플로 & env 스키마 & Runbook/CHANGELOG**:
   - `.github/workflows/ci.yml`: 기존 Gradle/Gitleaks + `deploy-verify`(구문/Compose/Caddy/대역 테스트/ARM64 이미지 빌드 검증).
   - `.github/workflows/deploy.yml`: `CI` 성공 뒤(`workflow_run` on `main`) 또는 `workflow_dispatch`에서 SHA 태그 GHCR push → 동시 배포 차단(`concurrency`, `cancel-in-progress: false`) → 최소 권한 + Secrets 이름만 참조 → `AUTO_DEPLOY_ENABLED == 'true'` 게이트 → `deploy_id` 기반 `switch` / 외부 G4 / `finalize` or `abort`.
   - `deploy/.env.prod.example`: 저장소 규칙(`CLAUDE.md`)에 따라 **키 이름만** 두고 기본값 설명은 `compose.prod.yml`과 `docs/runbook.md`에 둔다. M1 + PR #13(`08cfdac3`/`7fa3f385`)의 `ALERTS_*` / `RESEND_API_KEY` / `PRIVACY_*` 키 스키마를 수용한다(기본 비활성).
   - `docs/runbook.md`, `CHANGELOG.md`.

### 비변경·제외 범위 (금지)
- root 작업트리(`C:/Users/lcj/orca/projects/my-project`) 소스·`scripts/build-review-html.py`·untracked 문서 수정 금지(종료 시 `agy-deployment-result.md` 사본 한 개만 복사).
- `#6`(`issue-6-subscriptions`, Claude 수정 중) 파일/브랜치/설정/DB 간섭 금지, `#7` 착수 금지.
- `collector` / `ingestion` / `catalog` 비즈니스 로직 및 Flyway 마이그레이션 수정 금지.
- Prometheus/Grafana 컨테이너(#15), restic 백업 cron/pull(#16/#17)은 본 브랜치 범위 밖(Compose 내부 `8081` 및 `#16` 백업 인터페이스만 열어 둔다).
- 실제 OCI SSH·DNS·Secrets 생성/읽기·실서버 배포·실메일/외부 API 호출·`main` 머지 금지.

## 2. 최소 인터페이스 계약

### 2-1. 메인 앱 config 최소 변경 (`src/main/resources/application.yml`)
- 추가: `server.forward-headers-strategy: ${SERVER_FORWARD_HEADERS_STRATEGY:native}` (`1`줄 추가 + 주석).
- **근거**: ADR-0010의 요청 제한 키(클라이언트 IP)와 `Origin`/스킴 인식이 Caddy 프록시 뒤에서 동작하려면 Spring Boot/Tomcat `RemoteIpValve`가 필요하다. `origin/main`에는 아직 없으며 PR #13과 동일한 키/기본값(`native`)으로 맞춰 충돌 가능성을 최소화한다. Tomcat 기본 `internalProxies`(RFC1918·루프백)가 Docker 내부망 프록시만 신뢰하고 외부 IP의 위조 `X-Forwarded-*` 헤더는 거부한다.

### 2-2. 호스트 파일·디렉터리 배치 (서버 기준, root 미접근·사전 설계)
| 경로 | 소유/권한 | 용도 |
|---|---|---|
| `/opt/gongchae-bell/compose.prod.yml` | `ubuntu:ubuntu` | 운영 Compose 정의 |
| `/opt/gongchae-bell/.env` | `ubuntu:ubuntu` `0600` | 운영 환경변수(Secrets 주입, git 밖) |
| `/opt/gongchae-bell/deploy/deploy.sh` | `ubuntu:ubuntu` `0750` | 배포·롤백 스크립트 |
| `/opt/gongchae-bell/deploy/caddy/Caddyfile` | `ubuntu:ubuntu` `0644` | Caddy 메인 설정(`ro` 마운트) |
| `/opt/gongchae-bell/deploy/state/` | `ubuntu:ubuntu` `0750` | `active_color`, `active_sha`, `active_deploy_id`, `prev_*`, `pending_*`, `blue_tag`, `green_tag`, `last_deploy_*`, `deploy.lock`, `caddy/upstream.caddy` |
| `/var/lib/gongchae-bell/erasure/` | `1000:1000` `0750` | `erasure.log` 호스트 디렉터리(ADR-0013, `postgres_data` 볼륨과 분리) |

### 2-3. `deploy.sh` 상태·실행 인터페이스 (ADR-0005 일치)
- 호출:
  - `deploy.sh deploy <image_tag> [--mode auto|bootstrap|bluegreen] [--phase full|switch] [--deploy-id ID]` (`deploy.sh <image_tag>` 축약 허용)
  - `deploy.sh bootstrap <image_tag> [--deploy-id ID]`
  - `deploy.sh finalize --deploy-id ID`
  - `deploy.sh abort --deploy-id ID [--if-pending]`
  - `deploy.sh rollback [--phase full|switch] [--deploy-id ID]`
  - `deploy.sh status`
- 불변식(fail-closed):
  1. 모든 쓰기 동작(`bootstrap`, `deploy`, `finalize`, `abort`, `rollback`)은 `flock` 배타 잠금 안에서 실행한다.
  2. `bluegreen` 시작 전 `pending_deploy_id`가 비어 있고 `state/active_color`와 `caddy/upstream.caddy`가 가리키는 색이 반드시 일치해야 한다. 불일치하거나 중단된 `pending_deploy_id`가 남아 있으면 기존 컨테이너를 건드리지 않은 채 실패 종료한다.
  3. `bootstrap`은 `active_color`와 `pending_deploy_id`가 없을 때만 허용한다. `bluegreen`은 `active_color`가 있을 때만 허용한다(초기 설치와 무중단 G4 게이트 분리).
  4. `--phase switch`는 `upstream.caddy` 전환과 로컬 sanity 검증 후 `pending_*` 상태만 기록하고 **구 색을 정지하지 않으며 `active_color`를 바꾸지 않는다**.
  5. 외부 G4 통과 후 `finalize --deploy-id <id>`가 `pending_deploy_id` 일치를 검증한 뒤 구 색을 `stop -t 40`으로 정지하고 `active_color`/`active_sha`/`active_deploy_id`/`prev_*`를 atomic `mv`로 커밋한다.
  6. 외부 G4 실패(또는 전환 중 SSH 중단) 시 `abort --deploy-id <id>`가 동일 `deploy_id`를 검증한 뒤 아직 실행 중인 구 색 헬스를 확인해 즉시 구 색 upstream으로 `caddy reload` 복귀하고 새 색을 정지한다(`--if-pending`은 해당 `deploy_id`가 실제로 pending일 때만 정리, 타 배포 ID 불일치 시 거부).

### 2-4. G4 외부 프로브 고정 계약 (`deploy/g4-probe.sh`, `docs/runbook.md` 동기화)
- 실행 위치: **OCI VM 밖** (GitHub Actions 러너 또는 별도 외부 호스트).
- 대상: `https://gongchae.enjoylcjworld.com/` 및 `/jobs` (번갈아 요청).
- 간격/타임아웃: `1s` 간격, `--connect-timeout 3 --max-time 5`.
- 측정 구간: 배포 전 기준선(`10s`) → SSH `switch` 실행 중 전체 → 전환 후 안정화(`15s`, `min_requests=15`).
- 실패 기준(G4): **0건** (`HTTP 5xx`, TCP/DNS 연결 실패, TLS 오류, 타임아웃, 또는 기대 경로 HTTP `!= 200`). 리포트 JSON과 로그에 `deploy_id`를 남긴다.

### 2-5. #15·#16·M2 후속 연동 인터페이스
- #15(관측): `app-blue`/`app-green`은 `MANAGEMENT_ADDRESS=0.0.0.0`으로 `edge` 내부망 `:8081`(`/actuator/health/readiness`, `/actuator/prometheus`)을 노출하되 호스트 포트는 열지 않는다.
- #16(백업): 일일 논리 백업 대상은 실행 중 `postgres` 컨테이너에서 추출한 `pg_dump -Fc` 결과물과 `${ERASURE_HOST_DIR}/erasure.log`(해지 대장)이며 이를 함께 `restic`으로 백업한다. **실행 중 PostgreSQL 데이터 볼륨(`postgres_data`)의 파일 직접 복사는 일일 논리 백업 인터페이스로 사용하지 않는다.**
- M2(#6/#7): `compose.prod.yml`이 PR #13의 모든 `ALERTS_*`/`RESEND_*`/`PRIVACY_*` 키를 기본 비활성(`ALERTS_ENABLED=false`) 상태로 전달한다.

## 3. ADR/아키텍처 충돌 검토 결과
- **ADR-0005 및 `architecture.md`와 완전히 일치하도록 정렬 완료.**
  - `deploy.yml`(외부 러너)과 `deploy.sh`(서버)를 `deploy_id`로 연결된 2단계(`switch` → VM 밖 G4 → `finalize` 또는 `abort`)로 구성해, 외부 G4가 성공하기 전까지 이전 앱을 실행 상태로 유지하고 실패 시 콜드 스타트 없이 즉시 이전 upstream으로 복귀한다.
