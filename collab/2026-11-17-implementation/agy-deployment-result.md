# #14 배포 자동화 소스·문서 준비 결과 · agy

- **상태**: **소스·문서·CI/대역 검증 완료 (Draft PR #20 제출, 실서버 배포·실 HTTPS G4·M2 알림 미검증)**
- **작업 브랜치 / 워크트리**: `ops/#14-bluegreen-deploy` (`C:/Users/lcj/orca/workspaces/my-project/issue-14-deployment`)
- **기반 `origin/main` SHA**: `000dce555e9eb69ef3184dd5f80aa1d499a1f602`
- **구현·문서 커밋 SHA**:
  - `930c3e64edd6b8eefecfc8ba9dd20880a0130cff` (`ops: add ARM64 image and Caddy blue/green deploy automation (Refs #14)`)
  - `a970b27c50b526489285afb9b2e0636eaf69853a` (`ops: track key-only deploy/.env.prod.example in gitignore (Refs #14)`)
- **계약 문서**: [agy-deployment-contract.md](agy-deployment-contract.md)
- **Draft PR**: [#20](https://github.com/Lcjam/gongchae-bell/pull/20) (`DRAFT`, `Refs #14`, 환경·실측 게이트 미완료 명시, 이슈 `#14` 미닫음)
- **CI 실행 (`a970b27c`)**: [GitHub Actions Run #44219016067](https://github.com/Lcjam/gongchae-bell/actions/runs/44219016067) (`test` ✓ 1m27s · `deploy-verify` ✓ 1m15s · `secrets`/Gitleaks ✓ 8s)

---

## 1. 구현 산출물 요약

| 파일 | 역할 및 핵심 내용 |
|---|---|
| `Dockerfile` | `eclipse-temurin:21-jdk-noble` 빌드 → `eclipse-temurin:21-jre-noble` 실행 ARM64 멀티스테이지 이미지. UID/GID `1000:1000` 비루트 실행, `-Xms256m -Xmx512m -XX:+ExitOnOutOfMemoryError` (ADR-0014), 내부 readiness 점검용 `curl` 포함. |
| `.dockerignore` | `.git`, `.env`, `.env.*`, `.tools`, `.gradle`, `build`, `deploy`, `collab`, `docs`, `fixtures`, `review`, `scripts`, `*.log`, `*.pem`, `*.key`, `*.p12`, `*.jks`, `*.crt`, `*.dump`, `*.sql`, `*.sql.gz`, `*.db`, `*.sqlite*`, `*.bak`, `*.tar*`, `*.zip` 등 민감 파일·빌드 메타 제외. |
| `.gitignore` | `.env.*` 제외 규칙 아래 `!deploy/.env.prod.example` 명시 허용 및 `deploy/state/` 런타임 상태 무시. |
| `compose.prod.yml` | `postgres:17`(`backend` `internal: true` 망 격리), `app-blue`/`app-green`(`profiles` 분리, 개별 태그 `APP_BLUE_TAG`/`APP_GREEN_TAG`, `stop_grace_period: 40s`, `MANAGEMENT_ADDRESS=0.0.0.0` 내부망 전용, `ERASURE_HOST_DIR` 분리 마운트, M1/M2 기본 `false`), `caddy:2-alpine`(공개 포트 `80`/`443`만 게시). |
| `deploy/caddy/Caddyfile` · `upstream.{blue,green}.caddy` | `gongchae.enjoylcjworld.com` TLS + 보안 헤더(`Strict-Transport-Security`, `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Content-Security-Policy`, `?Referrer-Policy`), `/subscriptions*` 전용 헤더(`no-referrer`, `no-store`, `noindex`), JSON 접근 로그에서 `t` 쿼리·`Referer`·`Authorization`·`Cookie` 제거, edge `trusted_proxies` 미개방으로 클라이언트 위조 `X-Forwarded-For` 차단. |
| `deploy/deploy.sh` | `flock` 상호 배제, `bootstrap` vs `bluegreen` 분리, `deploy_id` 상관관계 기반 ADR-0005 2단계 전환(`--phase switch` → 외부 G4 판정 → `finalize` 구 색 `stop -t 40` 또는 `abort` 즉시 복귀), `active_color` vs `upstream.caddy` 불일치·잔류 `pending_deploy_id` 시 fail-closed. |
| `deploy/g4-probe.sh` | OCI VM 밖 실행용 G4 연속 프로브(`/`·`/jobs` 번갈아 요청, `1s` 간격, `--connect-timeout 3 --max-time 5`, `deploy_id` 포함 JSON 리포트, `5xx`/연결·TLS/타임아웃/non-200 `> 0` 시 실패 종료). |
| `deploy/.env.prod.example` | 저장소 규칙(`CLAUDE.md`)에 따라 **키 이름만** 기재. M1 기본 키 + PR #13(`08cfdac3`)의 `ALERTS_*`/`RESEND_API_KEY`/`PRIVACY_*` 스키마 호환(실제 운영에서 M2 전까지 기본 비활성). |
| `src/main/resources/application.yml` | 최소 변경 `server.forward-headers-strategy: ${SERVER_FORWARD_HEADERS_STRATEGY:native}` 1항목 추가(ADR-0010 프록시 뒤 클라이언트 IP 해석, PR #13과 동일 키). collector/sender/구독 로직 변경 없음. |
| `src/test/java/kr/gongchae/bell/DeploymentConfigIntegrationTest.java` | `graceful` shutdown·`30s` phase timeout·`native` forward headers·`collector.enabled=false` 기본값 및 Tomcat `RemoteIpValve`의 단일 홉 신뢰/좌측 위조 `X-Forwarded-For` 차단, `:8081` 관리 포트 격리 검증. |
| `.github/workflows/ci.yml` | `deploy-verify` 잡(`ubuntu-24.04-arm`) 추가: `bash -n`, `deploy-test.sh`, `compose.prod.yml` 검증, `caddy-integration-test.sh`, ARM64 `docker build` (push 없음). |
| `.github/workflows/deploy.yml` | `CI`(`main`) 성공 후 `workflow_run` 또는 `workflow_dispatch`에서 SHA 태그 ARM64 GHCR 푸시, `concurrency: production-deploy`(`cancel-in-progress: false`) 동시 배포 직렬화, 최소 권한(`permissions: {}` 루트), Secrets 이름만 참조, `AUTO_DEPLOY_ENABLED == 'true'` 게이트, VM 밖 `g4-probe.sh` + 2단계 `switch`→`finalize`/`abort` 자동 롤백. |
| `docs/runbook.md` · `CHANGELOG.md` | G4 고정 규격(`1s` 간격, `10s` 전 + 전환 중 + `15s` 후, 실패 0), 최초 `bootstrap`과 `bluegreen` 분리, 실패 유형별 복구/`abort`/`finalize`/`rollback`, `#15`(내부 `:8081`)·`#16`(`pg_dump -Fc` + `erasure.log`, 실행 중 `postgres_data` 파일 복사 금지) 인터페이스, 미완료 실측 범위 명시. |

---

## 2. 검증 결과 (로컬 + 원격 ARM64 CI)

| 구분 | 항목 | 결과 | 근거 / 조건 |
|---|---|---|---|
| 구문 검사 (로컬 Git Bash) | `bash -n deploy/deploy.sh deploy/g4-probe.sh deploy/tests/deploy-test.sh deploy/tests/caddy-integration-test.sh` | **통과 (`exit 0`)** | 2026-11-18 재개 후 실행 |
| 원격 CI Gradle + 통합 테스트 | `./gradlew --no-daemon check bootJar` (`ubuntu-24.04-arm`, `DeploymentConfigIntegrationTest` 포함) | **통과 (1m27s)** | [Run #44219016067 / test](https://github.com/Lcjam/gongchae-bell/actions/runs/44219016067) |
| 원격 CI `deploy.sh`·G4 대역 테스트 | `./deploy/tests/deploy-test.sh` (모의 `docker`/`curl` + 실제 `flock` 12개 시나리오: `bootstrap` 분리, 2단계 `switch`/`finalize`/`abort`, `deploy_id` 불일치 거부, 중단 상태 fail-closed, `pre-switch`/validate/reload/post-switch 실패, 사후 `rollback`, `G4_HOOK` 실패, `flock` `exit 20`, G4 5xx/TIMEOUT/TLS_ERR/404) | **통과 (`ALL_DEPLOY_TESTS_PASSED`)** | [Run #44219016067 / deploy-verify](https://github.com/Lcjam/gongchae-bell/actions/runs/44219016067) |
| 원격 CI Compose 검증 | `docker compose -f compose.prod.yml --profile blue --profile green config -q` + `--env-file deploy/.env.prod.example` | **통과 (`exit 0`)** | [Run #44219016067 / deploy-verify](https://github.com/Lcjam/gongchae-bell/actions/runs/44219016067) |
| 원격 CI 실제 Caddy 통합 시험 | `./deploy/tests/caddy-integration-test.sh` (`caddy:2-alpine` 2대 upstream + 실제 `Caddyfile`: 보안 헤더, `/subscriptions*` `no-referrer`/`no-store`/`noindex`, `?t=`/`Referer`/`Authorization`/`Cookie` 로그 마스킹, 위조 `X-Forwarded-For: 9.9.9.9` 차단, atomic `upstream.caddy` 교체 + `caddy validate` + `caddy reload` 전환) | **통과 (`CADDY_INTEGRATION_TEST_PASSED`)** | [Run #44219016067 / deploy-verify](https://github.com/Lcjam/gongchae-bell/actions/runs/44219016067) |
| 원격 CI ARM64 이미지 빌드 | `docker build --platform linux/arm64 -t gongchae-bell:ci-... .` | **통과** | [Run #44219016067 / deploy-verify](https://github.com/Lcjam/gongchae-bell/actions/runs/44219016067) |
| 원격 CI 비밀값 스캔 | `gitleaks/gitleaks-action@v3.0.0` | **통과 (8s)** | [Run #44219016067 / secrets](https://github.com/Lcjam/gongchae-bell/actions/runs/44219016067) |

---

## 3. ADR 충돌 검토 및 비변경 준수

- **기존 ADR 충돌 없음**: ADR-0005의 "전환 → G4 외부 시험 → 실패 시 구 색 복귀 → 성공 시 구 색 SIGTERM + 활성 색 기록" 순서를 지키기 위해 `deploy.yml`(외부 러너)과 `deploy.sh`(서버 `flock`) 사이에 `deploy_id` 기반 2단계(`switch` → 외부 `g4-probe.sh` → `finalize` 또는 `abort`) 인터페이스를 구현했다.
- **비변경 준수**: `collector`/`ingestion`/`catalog` 비즈니스 로직 및 Flyway 마이그레이션 무변경, `#6`(`issue-6-subscriptions`) 작업/브랜치/설정/DB 미접촉, `#7` 미착수, 실제 SSH·OCI·DNS·Secrets·유료 자원·실메일/API 미접촉, `main` 머지 미수행, root 보호 파일(`scripts/build-review-html.py` 및 기존 untracked 기록) 무변경.

---

## 4. 미검증 항목 및 후속 게이트 (중요)

1. **실서버 `bootstrap` 및 공개 HTTPS(`https://gongchae.enjoylcjworld.com`) 운영 성공 여부는 미검증**이다. (GitHub Secrets `DEPLOY_*` 등록, 서버 `/opt/gongchae-bell/.env` 작성, 실제 OCI `bootstrap` 실행 필요)
2. **실제 `main` 자동 배포 G4 0건 및 실패 유도 롤백 실측은 미검증**이다. (M2 알림 구현·검증과 root 검수 후 별도 실운영 배정 시 수행하며, 이번 대역 시험으로 실제 무중단을 주장하지 않는다)
3. **외부 포트 차단 실측(`5432`/`8081` 비공개)·운영 자원 실측(`docker stats`·`df`)·M2 실발송·#15 관측·#16/#17 백업/복원 리허설**은 후속 M2/M3 단계 과제로 남는다.
