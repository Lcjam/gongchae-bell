# #14 배포 자동화 소스 작업 결과 · agy

> 2026-11-18 · 작성: agy · 조율/검수: codex (root) · 브랜치: `ops/#14-bluegreen-deploy`
> 기반 `origin/main`: `000dce555e9eb69ef3184dd5f80aa1d499a1f602` · Draft PR: [#20](https://github.com/Lcjam/gongchae-bell/pull/20) (`Refs #14`)

## 1. 커밋 및 PR 정보

- **Draft PR**: [#20](https://github.com/Lcjam/gongchae-bell/pull/20) (`ops/#14-bluegreen-deploy` -> `main`, `isDraft: true`, `Refs #14`)
- **코드·계약·문서 커밋 SHA**:
  - `930c3e64edd6b8eefecfc8ba9dd20880a0130cff` — `ops: add ARM64 image and Caddy blue/green deploy automation (Refs #14)`
  - `a970b27c50b526489285afb9b2e0636eaf69853a` — `ops: track key-only deploy/.env.prod.example in gitignore (Refs #14)`
- **결과 문서 커밋**: 본 파일(`agy-deployment-result.md`)은 `a970b27c50b526489285afb9b2e0636eaf69853a` 직후 후속 `docs:` 커밋으로 기록·푸시함.

## 2. 구현 및 계약 정렬 내역

1. **ADR-0005 순서 및 `deploy_id` 상관관계 정렬 (`deploy/deploy.sh`, `deploy/g4-probe.sh`, `.github/workflows/deploy.yml`, `agy-deployment-contract.md`)**:
   - `deploy.sh`를 2단계(`--phase switch` -> 외부 G4 -> `finalize` 또는 `abort`)로 구현했다.
   - `switch`는 비활성 색 기동 → 내부 readiness(`/actuator/health/readiness`) 및 `/`, `/jobs`, `/robots.txt` 확인 → `caddy validate` → `pending_*` 상태 기록 후 `upstream.caddy` 전환/`caddy reload` → 로컬 sanity 확인까지 수행하되, **외부 G4 판정이 끝날 때까지 구 색을 정지하지 않고 `active_color`도 유지**한다.
   - `deploy.yml`은 `deploy_id` (`${mode}-${TARGET_SHA:0:12}-${GITHUB_RUN_ID}-${GITHUB_RUN_ATTEMPT}`)를 생성해 외부 `g4-probe.sh`, SSH `switch`, `finalize`, `abort --if-pending` 전체에 전달한다.
   - 외부 G4 통과 시 `finalize --deploy-id <id>`가 ID 일치를 검증한 뒤 구 색을 `stop -t 40`으로 정지하고 `active_color`/`active_sha`/`active_deploy_id`/`prev_*`를 커밋한다.
   - 외부 G4 실패(또는 `switch` 중 중단) 시 `abort --deploy-id <id>`가 실행 중인 구 색 헬스를 확인해 콜드 스타트 없이 즉시 구 색 `upstream.caddy`로 `caddy reload` 복귀한 뒤 실패한 새 색을 정지한다.
   - `pending_deploy_id`가 남아 있거나 `active_color`와 `upstream.caddy`가 불일치하면 새 배포는 `flock` 안에서 fail-closed로 거부되며, `deploy.sh status`에 `pending_deploy_id`·`active_deploy_id`가 노출된다.
2. **#16 백업 인터페이스 정렬 (`agy-deployment-contract.md`, `compose.prod.yml`, `docs/runbook.md`)**:
   - #16 일일 논리 백업 대상을 실행 중 `postgres` 컨테이너의 `pg_dump -Fc` 결과와 `${ERASURE_HOST_DIR}/erasure.log`(해지 대장)로 명시했다.
   - 실행 중 PostgreSQL 데이터 볼륨(`postgres_data`)의 파일 직접 복사는 일일 논리 백업 인터페이스로 사용하지 않음을 계약·Compose 주석·runbook에 함께 고정했다.
3. **키 이름 전용 환경변수 예시 (`deploy/.env.prod.example`, `.gitignore`)**:
   - 저장소 규칙(`CLAUDE.md`)에 따라 `deploy/.env.prod.example`에는 키 이름(`KEY=`)만 두고, 기본값 설명은 `compose.prod.yml`과 `docs/runbook.md`에 기재했다. `.gitignore`에 `!deploy/.env.prod.example`을 추가해 Git 추적 누락을 방지했다.
4. **이미지·Compose·Caddy·앱 최소 설정·문서**:
   - `Dockerfile` (`eclipse-temurin:21-jdk-noble` → `eclipse-temurin:21-jre-noble`, UID `1000:1000` 비루트, `-Xmx512m`) 및 `.dockerignore` (`.env*`, `.tools`, 키/덤프/`deploy` 제외).
   - `compose.prod.yml` (`postgres:17` internal `backend` 망, `app-blue`/`app-green` `profiles` 분리·`stop_grace_period: 40s`, `caddy:2-alpine` 80/443 전용 공개, `erasure` 호스트 디렉터리 분리, M2 PR #13 호환 env 키).
   - `deploy/caddy/Caddyfile` 및 `upstream.{blue,green}.caddy` (보안 헤더, `/subscriptions*` `no-referrer`/`no-store`/`noindex`, `t` 쿼리·`Referer`·`Authorization`·`Cookie` 로그 마스킹, edge 프록시 IP 경계).
   - `src/main/resources/application.yml` (`server.forward-headers-strategy: ${SERVER_FORWARD_HEADERS_STRATEGY:native}` 1줄 추가) 및 `DeploymentConfigIntegrationTest`.
   - `docs/runbook.md` (초기 준비, 권한, env 표, `bootstrap` vs `bluegreen` 측정 분리, 고정 G4 규격, 실패/중단 복구 절차, #15/#16 연동 및 ADR-0013 24시간 한계) 및 `CHANGELOG.md`.

## 3. 검증 결과

### 원격 CI (`a970b27c50b526489285afb9b2e0636eaf69853a`)
- **CI Run**: [44219016067](https://github.com/Lcjam/gongchae-bell/actions/runs/44219016067) (`conclusion: success`)
  - `test` (`ubuntu-24.04-arm`, 1m27s, [job](https://github.com/Lcjam/gongchae-bell/actions/runs/44219016067/job/134515946685)): `./gradlew --no-daemon check bootJar` 통과 (Testcontainers PostgreSQL 17, `DeploymentConfigIntegrationTest`의 `graceful` 종료/`native` 프록시 헤더/관리 포트 readiness·prometheus 노출 및 앱 포트 미노출 포함).
  - `deploy-verify` (`ubuntu-24.04-arm`, 1m15s, [job](https://github.com/Lcjam/gongchae-bell/actions/runs/44219016067/job/134515947072)):
    1. `bash -n` 구문 검증 및 `./deploy/tests/deploy-test.sh` 통과 (모의 `docker`/`curl` + 실제 `flock` 기반 12개 시나리오: `bootstrap` vs `bluegreen` 분리, 2단계 `switch`→`finalize`, `deploy_id` 불일치 거부, 외부 G4 실패 `abort` 시 구 색 미재시작 즉시 복귀, 중단된 `pending_deploy_id` fail-closed 및 수동 `rollback` 복구, pre-switch/validate/reload/post-switch 실패, 상태 불일치 fail-closed, 2단계 사후 `rollback`, `G4_HOOK` 실패 abort, `flock` 동시성 exit 20, `g4-probe.sh` pass/5xx/timeout/TLS/404 및 `deploy_id` JSON 리포트).
    2. `docker compose -f compose.prod.yml --profile blue --profile green config -q` 및 `--env-file deploy/.env.prod.example` 포함 검증 통과.
    3. `./deploy/tests/caddy-integration-test.sh` 통과 (격리 Docker 브리지 네트워크의 실제 `caddy:2-alpine` 컨테이너로 보안 헤더, `/subscriptions*` 헤더, `t`/`Referer`/`Authorization`/`Cookie` 로그 마스킹, 스푸핑된 `X-Forwarded-For` 차단, `upstream.caddy` 교체 + `caddy validate` + `caddy reload` 무중단 blue→green 전환 확인).
    4. `docker build --platform linux/arm64 -t gongchae-bell:ci-... .` ARM64 이미지 빌드 통과.
  - `secrets` (`ubuntu-latest`, 8s, [job](https://github.com/Lcjam/gongchae-bell/actions/runs/44219016067/job/134515947158)): `gitleaks/gitleaks-action@v3.0.0` 통과.

### 로컬 검증 (Windows 작업트리)
- `bash -n deploy/deploy.sh deploy/g4-probe.sh deploy/tests/deploy-test.sh deploy/tests/caddy-integration-test.sh` 통과, `file` LF 줄바꿈 확인.
- `.\gradlew.bat --no-daemon testClasses bootJar` 통과.
- `docker compose -f compose.prod.yml --profile blue --profile green config -q` 및 `--env-file deploy/.env.prod.example` 검증 통과.
- `gitleaks dir .` 및 `gitleaks detect --source .` (`57 commits`, `no leaks found`) 통과.
- *(환경 제약)* 로컬 Windows 호스트에는 `flock`이 없고 Docker Desktop 데몬이 미실행 상태여서 `deploy-test.sh`·`caddy-integration-test.sh`·Testcontainers 통합 실행은 위 원격 Linux ARM64 CI(`44219016067`)에서 수행했다.

## 4. 범위 경계 및 남은 운영 실행 조건

- **건드리지 않은 범위**: `issue-6-subscriptions`, `collector`/`ingestion`/`catalog` 로직 및 Flyway 마이그레이션, root 작업트리 소스·untracked 문서·`scripts/build-review-html.py` (root에는 본 결과 파일 사본 1개만 복사).
- **미실행·후속 운영 단계 조건** (본 소스 준비 범위 밖, 실제 서버 적용 시 별도 기록 필요):
  1. OCI VM 최초 준비(`/opt/gongchae-bell/.env`, `/var/lib/gongchae-bell/erasure`, GitHub Secrets/Variables) 및 `bootstrap` 실행(초기 설치로 G4 무중단 측정 대상과 분리).
  2. `gongchae.enjoylcjworld.com` 실 HTTPS에서 `main` 머지 자동 배포(`bluegreen`)의 G4 외부 프로브 0건 실측 및 실패 유도 롤백 실측 기록.
  3. 외부 포트 차단(`5432`, `8081` 비공개) 및 `docker stats`/`df` 자원 실측, 최초 3년 적재 후 `COLLECTOR_ENABLED=true` 전환.
  4. 구현자 외 리뷰 2건 및 신규 스키마에서의 이전 앱 호환 검증 기록(#14 완료 기준).
