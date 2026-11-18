# 운영 Runbook · 공채벨

> 근거: [PRD v1.0](prd.md), [아키텍처](design/architecture.md), [ADR-0005](adr/0005-blue-green-caddy.md), [ADR-0010](adr/0010-rate-limit-and-budget.md), [ADR-0012](adr/0012-backup-restic-pull.md), [ADR-0013](adr/0013-erasure-journal-host-file.md), [ADR-0014](adr/0014-initial-settings.md)
> 이 문서는 **문서만 보고 처음부터 서버를 설치·재배포·롤백·점검**할 수 있게 작성한다. 실측되지 않은 수치는 추측하지 않고, 실제 서버 실행 결과는 후속 운영 기록에 남긴다.

## 1. 서버 초기 준비 (OCI Ampere A1 · Ubuntu 24.04 ARM64)

### 1.1 방화벽 및 네트워크 경계
- 외부 공개 포트는 Caddy의 **`80/tcp`**, **`443/tcp`** (+ 관리용 SSH `22/tcp`, 가능하면 관리자 소스 IP로 제한)만 연다.
- PostgreSQL(`5432`), Spring 관리 포트(`8081`), 추후 추가할 Grafana(`3000`) 포트는 OCI Security List 및 호스트 방화벽(`ufw`/iptables)에서 외부 공개하지 않는다.
- `compose.prod.yml`에서 `postgres`는 `internal: true`인 `backend` 네트워크에만 붙고, `app-blue`/`app-green`은 `8080`·`8081` 호스트 포트를 게시하지 않는다.

### 1.2 필수 도구 설치 확인
호스트(`ubuntu` 사용자)에서 다음 도구가 동작해야 한다.
```bash
docker --version
docker compose version
flock --version
curl --version
```

### 1.3 호스트 디렉터리 및 파일 권한
컨테이너 런타임(`Dockerfile`)은 비루트 UID/GID `1000:1000`(`ubuntu`)으로 실행된다.

| 경로 | 소유/권한 | 용도 |
|---|---|---|
| `/opt/gongchae-bell/` | `ubuntu:ubuntu` `0750` | `compose.prod.yml`과 `deploy/` 배치 루트 |
| `/opt/gongchae-bell/.env` | `ubuntu:ubuntu` `0600` | 운영 환경변수 (Git 밖, Secrets/운영자만 입력) |
| `/opt/gongchae-bell/deploy/deploy.sh` | `ubuntu:ubuntu` `0750` | `flock` 기반 배포·롤백 스크립트 |
| `/opt/gongchae-bell/deploy/g4-probe.sh` | `ubuntu:ubuntu` `0750` | 외부 G4 프로브 스크립트 |
| `/opt/gongchae-bell/deploy/caddy/Caddyfile` | `ubuntu:ubuntu` `0644` | Caddy 메인 설정 (`ro` 마운트) |
| `/opt/gongchae-bell/deploy/state/` | `ubuntu:ubuntu` `0750` | `active_color`, `active_sha`, `active_deploy_id`, `prev_*`, `pending_*`, `deploy.lock`, `caddy/upstream.caddy` |
| `/var/lib/gongchae-bell/erasure/` | `1000:1000` `0750` | `erasure.log` 호스트 디렉터리 (`postgres_data` 볼륨과 분리, ADR-0013) |

최초 1회 준비 명령:
```bash
sudo mkdir -p /opt/gongchae-bell/deploy/state/caddy /var/lib/gongchae-bell/erasure
sudo chown -R ubuntu:ubuntu /opt/gongchae-bell
sudo chown -R 1000:1000 /var/lib/gongchae-bell/erasure
sudo chmod 0750 /opt/gongchae-bell /opt/gongchae-bell/deploy/state /var/lib/gongchae-bell/erasure
```
- M2 알림(`ALERTS_ENABLED=true`)을 처음 켜기 전에는 운영자가 빈 해지 대장 파일을 미리 생성해야 한다(`sudo install -o 1000 -g 1000 -m 600 /dev/null /var/lib/gongchae-bell/erasure/erasure.log && sync`). 앱은 이 파일을 자동 생성하지 않으며, `ALERTS_ENABLED=true` 상태에서 파일이 없으면 `ALERTS_ERASURE_LOG_MISSING`으로 시작하지 않는다. (`ALERTS_ENABLED=false` 상태에서는 디렉터리 준비만으로 기동된다.)

### 1.4 GitHub Secrets / Variables / GHCR 설정
1. Repository / `production` Environment **Secrets** (값은 GitHub Secrets에만 저장, 로그·문서·코드 출력 금지):
   - `DEPLOY_HOST`: OCI VM 공인 IP 또는 SSH 호스트
   - `DEPLOY_USER`: `ubuntu`
   - `DEPLOY_SSH_KEY`: 배포 전용 SSH 개인키
   - `DEPLOY_KNOWN_HOSTS`: `ssh-keyscan -H <DEPLOY_HOST>` 결과
2. Repository **Variables**:
   - `SITE_DOMAIN`: `gongchae.enjoylcjworld.com`
   - `AUTO_DEPLOY_ENABLED`: 준비가 끝난 뒤 `true`로 설정 (미설정/`false`이면 `main` push 자동 배포는 건너뛰고 `workflow_dispatch` 수동 실행만 허용)
3. GHCR 이미지 풀:
   - 공개 저장소(PRD U3)에 맞춰 GHCR 패키지(`ghcr.io/<owner>/gongchae-bell`)가 public이거나, 비공개면 서버에서 `docker login ghcr.io`를 먼저 수행한다.

---

## 2. 환경변수 목록 (`/opt/gongchae-bell/.env`)

`deploy/.env.prod.example`에는 저장소 규칙에 따라 **키 이름만** 둔다. `/opt/gongchae-bell/.env`를 만들 때 복사한 뒤 실제 값을 채우고 `chmod 600`으로 잠근다. 아래 표의 기본값은 `compose.prod.yml` 또는 `application.yml`에 정의된 값이다.

| 키 | 필수 | 기본값 (`compose.prod.yml` / 앱) | 설명 | 비밀 여부 |
|---|---|---|---|---|
| `APP_IMAGE` | 필수 | 없음 | GHCR 이미지 이름 (예: `ghcr.io/lcjam/gongchae-bell`) | 아니오 |
| `APP_BLUE_TAG` | `deploy.sh` 관리 | 비어 있으면 상태 파일 또는 `unset` | `app-blue` 이미지 태그 (`sha-<commit>`) | 아니오 |
| `APP_GREEN_TAG` | `deploy.sh` 관리 | 비어 있으면 상태 파일 또는 `unset` | `app-green` 이미지 태그 (`sha-<commit>`) | 아니오 |
| `SITE_DOMAIN` | 선택 | `gongchae.enjoylcjworld.com` | Caddy TLS 발급 및 서빙 도메인 | 아니오 |
| `APP_BASE_URL` | 선택 | `https://gongchae.enjoylcjworld.com` | `sitemap.xml`·메일 링크 등에 쓰는 공개 URL | 아니오 |
| `JAVA_TOOL_OPTIONS` | 선택 | `-Xms256m -Xmx512m -XX:+ExitOnOutOfMemoryError` | JVM 힙·OOM 옵션 (ADR-0014 시작값 512MiB) | 아니오 |
| `DB_NAME` | 선택 | `gongchae` | PostgreSQL 데이터베이스 이름 | 아니오 |
| `DB_USER` | 필수 | 없음 | PostgreSQL 사용자명 | 비밀 권장 |
| `DB_PASSWORD` | 필수 | 없음 | PostgreSQL 비밀번호 | **비밀** |
| `ERASURE_HOST_DIR` | 선택 | `/var/lib/gongchae-bell/erasure` | 호스트 `erasure.log` 디렉터리 경로 | 아니오 |
| `CADDY_RUNTIME_DIR` | 선택 | `./deploy/state/caddy` | `upstream.caddy` 런타임 디렉터리 | 아니오 |
| `COLLECTOR_ENABLED` | 선택 | `false` | 정기 수집 스케줄러 활성화 여부 | 아니오 |
| `COLLECTOR_SERVICE_KEY` | 수집 시 필수 | 빈 문자열 | 공공데이터포털 일반 인증키 | **비밀** |
| `COLLECTOR_WARNING_LIMIT` | 선택 | `500` | 일 호출 경고 임계값 (ADR-0014) | 아니오 |
| `COLLECTOR_STOP_LIMIT` | 선택 | `900` | 일 호출 자동 중단 임계값 (ADR-0014) | 아니오 |
| `ALERTS_ENABLED` | 선택 | `false` | M2 알림 활성화 여부 (M2 전까지 `false`) | 아니오 |
| `ALERTS_TOKEN_KEY` | M2 시 필수 | 빈 문자열 | 구독 토큰 암호화 키 | **비밀** |
| `ALERTS_HMAC_KEY` | M2 시 필수 | 빈 문자열 | 레이트 리밋/토큰 HMAC 키 | **비밀** |
| `ALERTS_WEBHOOK_KEY` | M2 시 필수 | 빈 문자열 | 웹훅 URL 암호화 키 | **비밀** |
| `ALERTS_WEBHOOK_KEY_ID` | M2 시 필수 | 빈 문자열 | 웹훅 암호화 키 식별자 | 아니오 |
| `ALERTS_ERASURE_LOG` | 선택 | `/var/lib/gongchae-bell/erasure/erasure.log` | 해지 대장 파일 경로 | 아니오 |
| `RESEND_API_KEY` | M2 시 필수 | 빈 문자열 | Resend API 키 | **비밀** |
| `ALERTS_MAIL_FROM` | M2 시 필수 | 빈 문자열 | 발신 이메일 주소 | 아니오 |
| `PRIVACY_OFFICER` | M2 시 필수 | 빈 문자열 | 개인정보 보호책임자명 | 아니오 |
| `PRIVACY_CONTACT` | M2 시 필수 | 빈 문자열 | 개인정보 담당 연락처 | 아니오 |
| `PRIVACY_POLICY_VERSION` | M2 시 필수 | 빈 문자열 | 처리방침 버전 | 아니오 |
| `PRIVACY_EFFECTIVE_DATE` | M2 시 필수 | 빈 문자열 | 처리방침 시행일 | 아니오 |
| `PRIVACY_HOSTING_LOCATION` | M2 시 필수 | 빈 문자열 | 호스팅 위치 | 아니오 |
| `PRIVACY_OVERSEAS_BASIS` | M2 시 필수 | 빈 문자열 | 국외 이전 근거 (Resend 미국 저장) | 아니오 |
| `PRIVACY_RESEND_RETENTION` | M2 시 필수 | 빈 문자열 | 수탁자 보관 기간 | 아니오 |

---

## 3. 배포 절차: 최초 설치(`bootstrap`)와 상시 무중단 전환(`bluegreen`) 구분

> **측정 조건 분리 원칙**: `bootstrap`은 기존 서비스가 없는 빈 서버에 처음 `postgres`·`app-blue`·`caddy`를 올리는 설치 단계이며 **G4 무중단 배포 측정 대상이 아니다**. G4 외부 시험은 기존 서비스가 응답 중인 상태에서 새 버전으로 전환하는 `bluegreen` rollout(및 `rollback`)에 적용한다. 대역(`test-double`) 검증으로 실제 무중단 완료를 주장하지 않는다.

### 3.1 최초 설치 (`bootstrap`)
1. `/opt/gongchae-bell/`에 `compose.prod.yml`과 `deploy/`를 배치하고 `/opt/gongchae-bell/.env`를 작성한다(`COLLECTOR_ENABLED=false`, `ALERTS_ENABLED=false` 권장).
2. GitHub Actions `Deploy` workflow(`workflow_dispatch`, `action=bootstrap`) 또는 서버에서 실행한다:
   ```bash
   cd /opt/gongchae-bell
   PULL_POLICY=always ./deploy/deploy.sh bootstrap sha-<commit>
   ```
3. 동작 순서:
   - `upstream.blue.caddy` → `deploy/state/caddy/upstream.caddy` 배치
   - `postgres` 기동 및 healthcheck 대기
   - `app-blue` 기동 및 내부 readiness(`/actuator/health/readiness` = `UP`)·핵심 경로(`/`, `/jobs`, `/robots.txt` = `200`) 확인
   - `caddy` 기동 및 `caddy validate` 통과
   - `active_color=blue`, `active_sha=sha-<commit>`, `active_deploy_id` 기록
4. 최초 적재(FR-C1)와 정기 수집 활성화는 [수집 실행과 점검](ops/collector.md#운영-순서와-남은-게이트)을 따라 `COLLECTOR_ENABLED=false` 상태에서 3년 최초 적재(`START_SCAN`)를 마친 뒤 `COLLECTOR_ENABLED=true`로 전환한다.

### 3.2 상시 무중단 배포 (`bluegreen`, ADR-0005 순서)
`CI` 워크플로가 `main`에서 성공하면 `.github/workflows/deploy.yml`이 ARM64 이미지를 `ghcr.io/<owner>/gongchae-bell:sha-<commit>`으로 푸시하고(`workflow_run`), `AUTO_DEPLOY_ENABLED == 'true'`일 때 아래 순서로 배포한다. 같은 `deploy_id`(`<mode>-<short_sha>-<run_id>-<attempt>`)가 CI·외부 프로브·서버 상태·`flock` 전체를 연결한다.

1. **사전 기준선 측정 (VM 밖 러너)**: `deploy/g4-probe.sh`를 시작하고 `10s` 동안 기준선을 수집한다.
2. **Switch 단계 (`deploy.sh deploy <tag> --mode bluegreen --phase switch --deploy-id <id>`, `flock` 잠금)**:
   - `pending_deploy_id`가 없으며 `active_color`와 `upstream.caddy`가 일치하는지 교차 검증한다(불일치 시 **fail-closed**, 기존 컨테이너 무변경).
   - 비활성 색(`app-green` 또는 `app-blue`)을 기동하고 내부 `:8081/actuator/health/readiness`와 `:8080`의 `/`, `/jobs`, `/robots.txt` (`200`)를 확인한다. (실패 시 새 색만 정지)
   - `upstream.caddy.next`를 생성해 `caddy validate`를 통과한 뒤 `pending_*` 상태를 기록하고 `upstream.caddy`를 원자적 교체 후 `caddy reload`한다. (validate/reload 실패 시 원복 후 새 색 정지)
   - 로컬 Caddy 경유 sanity 확인 후 **구 색을 실행 상태로 유지한 채** 반환한다.
3. **외부 G4 판정 (VM 밖 러너)**: `g4-probe.sh`가 전환 완료 후 `15s` 창을 추가로 측정한다.
4. **결과 반영 (`flock` 잠금)**:
   - **G4 성공 (`failures == 0`)**: `deploy.sh finalize --deploy-id <id>` 호출 → 구 색에 `SIGTERM` (`stop -t 40`, ADR-0014 30s graceful shutdown + 10s 여유) → `active_color`, `active_sha`, `active_deploy_id`, `prev_*` 커밋 및 `pending_*` 정리.
   - **G4 실패 (`failures > 0`)**: `deploy.sh abort --deploy-id <id>` 호출 → 아직 실행 중인 구 색 헬스 확인 → 즉시 구 색으로 `caddy reload` 복귀(콜드 스타트 없음) → 새 색 `stop -t 40` 정지 → `pending_*` 정리 (`active_color` 유지).

---

## 4. PRD G4 고정 외부 시험 계약

`deploy/g4-probe.sh`와 `.github/workflows/deploy.yml`은 아래 고정 값을 사용한다.

| 항목 | 고정 값 |
|---|---|
| 실행 위치 | **OCI VM 밖** (GitHub Actions 러너 또는 별도 외부 호스트) |
| 대상 URL | `https://gongchae.enjoylcjworld.com/` 및 `/jobs` (번갈아 요청) |
| 요청 간격 | `1s` (`--interval 1`) |
| 타임아웃 | `--connect-timeout 3 --max-time 5` |
| 측정 구간 | 전환 전 기준선 `10s` → SSH `switch` 실행 중 전체 → 전환 후 안정화 `15s` (`--min-requests 15`) |
| 실패 기준 | `HTTP 5xx`, DNS/TCP 연결 실패, TLS 오류, 타임아웃(`curl` exit 28), 또는 HTTP `!= 200` 합계 **0건** |
| 리포트 필드 | `deploy_id`, `execution_context`, `runner_host`, `base_url`, `started_at`, `finished_at`, `total_requests`, `ok_requests`, `failures`, `http_5xx`, `connect_tls_errors`, `timeouts`, `other_non_200`, `p95_latency_ms`, `max_latency_ms` |

---

## 5. 실패 유형별 동작과 롤백·중단 복구

| 실패 시점 | 자동 동작 | 서버 상태 | 수동 조치 |
|---|---|---|---|
| **기동 전 실패** (`PRE_SWITCH_FAIL`) | 새 색 `stop -t 40` 후 종료 | 기존 색·Caddy·`active_color` 무변경 | 새 버전 로그 확인 후 재시도 |
| **Caddy 검증/리로드 실패** (`CADDY_VALIDATE_FAIL`, `CADDY_RELOAD_FAIL`) | `upstream.caddy` 원복, 새 색 중지, `pending_*` 정리 | 기존 색 계속 서빙 | `Caddyfile`·런타임 마운트 점검 |
| **전환 직후/외부 G4 실패** (`POST_SWITCH_FAIL`, `G4_FAIL`) | 실행 중인 구 색으로 즉시 `caddy reload` 복귀 후 새 색 중지 | 기존 색·`active_color` 유지 (콜드 스타트 없음) | Actions 아티팩트 `g4-probe-report` 및 앱 로그 확인 |
| **중단된 배포·종료 실패 잔류** (`PENDING_DEPLOY_EXISTS`, `STATE_MISMATCH`, `FINALIZE_STOP_FAILED`) | 새 배포 **fail-closed** 거부 (`finalize` 종료 실패 시에도 `pending_*` 유지) | 실행 중 컨테이너·기존 `active_color` 보호 | 아래 `중단 복구` 절차로 `finalize` 재시도 또는 `abort` 수행 |
| **사후 롤백** (`rollback`) | `prev_color`/`prev_sha` 기동 → 외부 G4 검증 → 현재 색 `stop -t 40` | 이전 정상 버전으로 복귀 | `workflow_dispatch(action=rollback)` 또는 아래 2단계/G4_HOOK 절차 |

### 중단된 배포 및 사후 롤백 수동 명령
```bash
cd /opt/gongchae-bell
./deploy/deploy.sh status
```
- `pending_deploy_id`가 남아 있는 중단 상태(`switch` 중단, `finalize` 중간 중단, `FINALIZE_STOP_FAILED` 포함)라면:
  - 기존 활성 색(`active_color`, `prev_*` 포함)으로 되돌리려면(`pending_deploy_id`가 있을 때는 `G4_HOOK` 없이 즉시 복구):
    ```bash
    ./deploy/deploy.sh abort --deploy-id <pending_deploy_id>
    # 또는 deploy_id 지정 없이 수동 긴급 복구:
    ./deploy/deploy.sh rollback
    ```
  - 외부 G4와 실제 응답이 새 색(`pending_color`)에서 정상임을 확인했고 구 색 종료·커밋을 완료하거나 재시도하려면:
    ```bash
    ./deploy/deploy.sh finalize --deploy-id <pending_deploy_id>
    ```
- 이미 커밋된 정상 배포(`pending_deploy_id=none`)를 이전 버전(`prev_color`/`prev_sha`)으로 되돌리려면:
  - 권장(외부 G4 포함): GitHub Actions `Deploy` 워크플로에서 `workflow_dispatch` → `action: rollback` 실행.
  - 서버 CLI 단독 실행 시 `deploy.sh rollback`·`deploy.sh deploy <tag> --phase full`·단축 `deploy.sh <tag>` 모두 외부 판정 수단(`G4_HOOK`)이 필수이며, `G4_HOOK`가 없으면 전환 전에 fail-closed로 거부된다. `G4_HOOK` 없이 수동 2단계로 복구하려면 아래 순서를 따른다:
    ```bash
    ./deploy/deploy.sh rollback --phase switch --deploy-id manual-rb-$(date -u +%Y%m%dT%H%M%SZ)
    # VM 밖 호스트에서 ./deploy/g4-probe.sh --base-url https://gongchae.enjoylcjworld.com --duration 15 --min-requests 15 실행
    # 통과 시:
    ./deploy/deploy.sh finalize --deploy-id <위에서 지정한 ID>
    # 실패 시:
    ./deploy/deploy.sh abort --deploy-id <위에서 지정한 ID>
    ```

---

## 6. 서버 접속과 점검 방법

### 6.1 상태 및 컨테이너 확인
```bash
ssh ubuntu@<DEPLOY_HOST>
cd /opt/gongchae-bell
./deploy/deploy.sh status
docker compose -f compose.prod.yml --profile blue --profile green ps
```

### 6.2 내부 관리 포트·프록시·로그 점검
- 내부 readiness / Prometheus 확인 (호스트 포트 미공개, 컨테이너 내부 확인):
  ```bash
  docker compose -f compose.prod.yml --profile blue exec -T app-blue \
    curl -fsS http://127.0.0.1:8081/actuator/health/readiness
  ```
- Caddy 보안 헤더·토큰 경로 헤더·로그 마스킹 확인:
  - `/subscriptions*` 응답은 `Referrer-Policy: no-referrer`, `Cache-Control: no-store`, `X-Robots-Tag: noindex`가 포함되어야 한다.
  - Caddy JSON 로그(접근 로그 `stdout` 및 기본/오류 로그 `stderr`)는 `t` 쿼리 파라미터, `Referer`, `Authorization`, `Cookie`를 제거한다:
    ```bash
    docker compose -f compose.prod.yml logs --tail=100 caddy
    ```
- 외부 비공개 포트 점검 (VM 밖에서 실행):
  - `80`, `443` 외 `5432`(PostgreSQL), `8081`(Actuator), `3000`(Grafana) 연결이 거부/타임아웃되는지 확인한다.

### 6.3 프록시 IP 신뢰 경계 (ADR-0010)
- Caddy는 edge에서 `trusted_proxies`를 열지 않으므로 클라이언트가 보낸 `X-Forwarded-For`를 신뢰하지 않고 실제 접속 IP(`remote_host`)를 upstream으로 전달한다.
- 앱(`application.yml`)은 `server.forward-headers-strategy: ${SERVER_FORWARD_HEADERS_STRATEGY:native}`를 사용하며, Tomcat 기본 `internalProxies`(Docker 내부 사설 대역/루프백)에서 온 프록시 헤더만 해석한다.

---

## 7. 후속 연동 인터페이스 (#15 관측 · #16 백업)

- **#15 관측**: `app-blue`/`app-green`은 `MANAGEMENT_ADDRESS=0.0.0.0`으로 Docker 내부망 `:8081`(`/actuator/health/readiness`, `/actuator/prometheus`)만 노출한다. 호스트 포트는 열지 않는다.
- **#16 백업 ([백업과 복구 절차](ops/backup-restore.md), ADR-0012 / ADR-0013)**:
  - 일일 논리 백업 대상은 **실행 중인 `postgres` 컨테이너에서 추출한 `pg_dump -Fc` 결과물**과 **호스트의 `${ERASURE_HOST_DIR}/erasure.log`(해지 대장)**이다. 이를 `restic`으로 암호화해 OCI Object Storage에 보관한다.
  - **실행 중인 PostgreSQL 데이터 볼륨(`postgres_data`) 파일 직접 복사는 일일 논리 백업 인터페이스로 사용하지 않는다.**
  - **한계 고지 (ADR-0013)**: VM 전체가 영구 소실되면 마지막 일일 백업 이후(최대 약 24시간) `erasure.log`도 함께 사라져 그 사이 해지한 구독자에게 복원 후 다시 발송될 수 있다. 이 한계는 개인정보처리방침과 복원 절차에서 함께 고지한다.

---

## 8. 현재 검증 범위와 남은 운영 실행 조건

- **소스·구문·대역 검증 (본 브랜치 `ops/#14-bluegreen-deploy`)**:
  - `deploy/tests/deploy-test.sh` (모의 `docker`/`curl` + 실제 `flock` 기반 15개 시나리오), `deploy/tests/caddy-integration-test.sh` (실제 `caddy:2-alpine` 기반 헤더·access/502 error 로그 마스킹·IP 경계·reload 전환), `docker compose -f compose.prod.yml --profile blue --profile green config -q`, `Dockerfile` ARM64/컨테이너 빌드, `./gradlew check bootJar`(`DeploymentConfigIntegrationTest` 포함), Gitleaks 스캔으로 검증한다.
- **남은 실서버 운영 실행 조건 (후속 실행 단계에서 기록)**:
  1. OCI VM 최초 준비 및 `bootstrap` 실행, HTTPS(`https://gongchae.enjoylcjworld.com`) 확인
  2. 실제 `main` 머지 자동 배포(`bluegreen`)의 G4 외부 시험 0건 실측 기록 (CI `g4-probe-report`)
  3. 실제 실패 유도 롤백(`G4_FAIL` 또는 `rollback`)의 외부 G4 연속성 실측 기록
  4. 외부 포트 차단(`5432`/`8081` 비공개) 및 자원(`docker stats`·`df`) 실측 기록
  5. 최초 3년 적재 완료 후 `COLLECTOR_ENABLED=true` 전환 (`docs/ops/collector.md`)
