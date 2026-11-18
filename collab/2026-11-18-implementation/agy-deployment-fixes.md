# PR #20 배포 코드 2라운드 수정 결과 · agy

- **상태**: **4건 검수 결함 수정 및 로컬·ARM64 CI 검증 완료 (Draft PR #20 갱신, 실서버 환경 게이트 미실행)**
- **작업 브랜치 / 워크트리**: `ops/#14-bluegreen-deploy` (`C:/Users/lcj/orca/workspaces/my-project/issue-14-deployment`)
- **검수 1라운드 기준 SHA**: `ede7ce1cd7fc4d0db384c378e81ed4579585b54b`
- **코드·테스트·Runbook 수정 커밋 SHA**: `2cecc9f78f2cc94ff715aab6a3d4051c8e388aa6` (`fix: harden deploy recovery, G4 enforcement, stop/rm checks, and Caddy error log redaction (Refs #14)`)
- **ARM64 CI 실행 (`2cecc9f7`)**: [GitHub Actions Run #44225358776](https://github.com/Lcjam/gongchae-bell/actions/runs/44225358776) (`test` ✓ 1m35s · `deploy-verify` ✓ 1m21s · `secrets`/Gitleaks ✓ 9s)
- **PR**: [#20](https://github.com/Lcjam/gongchae-bell/pull/20) (`DRAFT`, `main` 미머지, `#14` 미닫음)

---

## 1. 결함별 수정 내역

| # | 결함 | 수정 파일 | 구현 요약 |
|---|---|---|---|
| 1 | `finalize` 중간 중단 후 `abort` 시 `active_*`/`prev_*` 미복구 (P1) | `deploy/deploy.sh`, `deploy/tests/deploy-test.sh` | `write_pending_state`에 `pending_from_deploy_id`, `pending_prev_color`, `pending_prev_sha`, `pending_prev_deploy_id`, `pending_target_prev_tag`를 저장하고 `pending_deploy_id`를 마지막에 기록하도록 변경했다. `finalize_pending_internal`은 `resolve_pending_from_deploy_id`로 이전 deploy ID를 읽어 `active_deploy_id` 덮어쓰기 후 재시도해도 `prev_deploy_id`가 유지되게 했다. `abort_pending_internal`은 `active_*`, `prev_*`, 양쪽 색 태그까지 이전 상태로 복원한다. `clear_pending_state`는 `pending_deploy_id`를 먼저 삭제하고, 이후 중단으로 남은 잔여 `pending_*` 파일은 `assert_no_pending_and_consistent_state`와 `cmd_status`에서 안전하게 정리·무시한다. |
| 2 | Caddy 기본/error logger 토큰·Referer 노출 (P1) | `deploy/caddy/Caddyfile`, `deploy/tests/caddy-integration-test.sh` | 공통 `(redacted_log_format)` 스니펫을 분리하고 전역 옵션 `{ log { import redacted_log_format } }`와 사이트 `log` 양쪽에 적용했다. 이로써 `http.log.access.log0`뿐 아니라 502 등 upstream 연결 실패 시 `http.log.error.log0`를 기록하는 `default` 로거에서도 `t` 쿼리, `Referer`, `Authorization`, `Cookie`가 제거된다. |
| 3 | CLI `--phase full` 및 사후 `rollback`의 외부 G4 생략 (P2) | `deploy/deploy.sh`, `docs/runbook.md`, `deploy/tests/deploy-test.sh` | `require_g4_hook`를 추가해 `cmd_bluegreen(..., "full", ...)`과 `pending_deploy_id`가 없는 사후 `cmd_rollback("full", ...)`에서 전환(`switch_to_target`) 전에 실행 가능한 `G4_HOOK` 존재를 필수로 검증한다. 누락 시 컨테이너·Caddy를 건드리지 않고 fail-closed로 종료한다. `pending_deploy_id`가 남아 있는 중단 복구 `rollback`과 최초 `bootstrap`(또는 미부트스트랩 상태 단축 실행)은 `G4_HOOK` 없이 계속 동작한다. `docs/runbook.md`의 수동 롤백·단축 명령 안내를 실제 동작에 맞췄다. |
| 4 | `finalize`에서 구 앱 `stop`/`rm` 실패 무시 (P2) | `deploy/deploy.sh`, `deploy/tests/deploy-test.sh` | `stop_and_remove_color`에서 `|| true`를 제거하고, `compose ps -a -q`로 컨테이너 미존재 시 멱등 반환(`0`), 존재 시 `stop -t 40` 후 `ps --status running -q` 검증, `rm -f` 후 `ps -a -q` 검증으로 실제 종료·제거를 확인한다. `finalize_pending_internal`과 `abort_pending_internal`에서 `stop_and_remove_color`가 실패하면 `last_deploy_result`에 `FINALIZE_STOP_FAILED`/`ABORT_STOP_FAILED`를 기록하고 `pending_*`와 `active_*`를 보존한 채 비정상 종료해 해결 후 재시도할 수 있게 했다. |
| - | `#6` 최신 계약 반영 (Runbook 해지 대장 사전 준비) | `docs/runbook.md` | `#6`(`ErasureJournal`) 계약에 맞춰 `ALERTS_ENABLED=true` 전환 전 빈 `/var/lib/gongchae-bell/erasure/erasure.log` 파일(`1000:1000`, `0600`) 사전 생성 필요성과 미존재 시 `ALERTS_ERASURE_LOG_MISSING` 기동 실패 동작을 Runbook §1.3에 명시했다. |

---

## 2. 회귀 및 통합 검증 결과 (`2cecc9f7` 기준)

임시 테스트 격리는 `deploy/tests/deploy-test.sh` 내부의 `TMP_ROOT` 임시 디렉터리와 `$CASE_DIR/failbin/{mv,rm}` 경로 주입 및 모의 `docker`/`curl` 대역으로만 수행했으며, 원본 프로덕션 소스는 변이하지 않았다.

| 검증 항목 | 실행 환경 | 결과 | 확인 범위 |
|---|---|---|---|
| Bash 구문 검사 | Windows Git Bash / Linux `ubuntu:24.04` / ARM64 CI | **통과 (`exit 0`)** | `deploy/deploy.sh`, `deploy/g4-probe.sh`, `deploy/tests/deploy-test.sh`, `deploy/tests/caddy-integration-test.sh` |
| 배포·복구 단위/대역 회귀 (`deploy-test.sh`, 15개 시나리오) | 로컬 Linux `ubuntu:24.04` 컨테이너(실제 `flock`/`mv`/`rm`) + 원격 ARM64 CI | **통과 (`ALL_DEPLOY_TESTS_PASSED`)** | 기존 12개 + `[13/15]` `active_color`/`active_deploy_id`/정리 중단 후 `abort`·`finalize` 재시도·중복 실행·후속 배포, `[14/15]` `full`/`rollback`의 `G4_HOOK` 필수(누락·실패·성공)·`bootstrap`·pending `rollback` 보존, `[15/15]` silent running·`stop` 실패·`rm` 실패·둘 다 실패·이미 제거된 멱등성·재시도 |
| 실제 Caddy 통합·502 오류 로그 시험 (`caddy-integration-test.sh`) | 로컬 Docker `caddy:2-alpine` + 원격 ARM64 CI | **통과 (`CADDY_INTEGRATION_TEST_PASSED`)** | 기존 보안 헤더·`/subscriptions*` 헤더·access 로그 마스킹·위조 `XFF` 차단·`reload` 전환 + 활성 `app-green` 중지 후 실제 `502` 응답에서 `http.log.error` 발생 확인 및 stdout/stderr 전체에서 `ERROR_SECRET_TOKEN`·`ERROR_REF_TOKEN`·`evil.error.example`·`Authorization`·`Cookie` 미노출(`keep=1` 유지) |
| Compose 설정 검증 | 로컬 Docker Compose + 원격 ARM64 CI | **통과 (`exit 0`)** | `compose.prod.yml --profile blue --profile green config -q` 및 `--env-file deploy/.env.prod.example` |
| Gradle + Spring 통합 테스트 | 로컬 (`./gradlew --no-daemon check bootJar`) + 원격 ARM64 CI | **통과 (`BUILD SUCCESSFUL`)** | `DeploymentConfigIntegrationTest` 포함 전체 테스트 및 `bootJar` |
| ARM64 이미지 빌드 | 원격 ARM64 CI (`ubuntu-24.04-arm`) | **통과 (`exit 0`)** | `docker build --platform linux/arm64` (push 없음) |
| 비밀값 스캔 | 로컬 `gitleaks` + 원격 CI (`gitleaks-action@v3.0.0`) | **통과 (no leaks)** | PR 커밋 범위 전체 스캔 통과 (`curl-auth-header` 오탐 방지를 위해 테스트 헤더를 변수로 전달) |

---

## 3. 남은 후속 환경 검증 (M3 실서버 게이트)

1. OCI 실서버 최초 준비(`/opt/gongchae-bell/.env`, `erasure` 디렉터리 및 M2 활성화 시 빈 `erasure.log` 생성), `bootstrap` 실행, 공개 HTTPS(`https://gongchae.enjoylcjworld.com`) 확인.
2. 실제 `main` 머지 자동 배포(`bluegreen`)의 외부 G4 실패 0건 실측(`g4-probe-report`) 및 실제 실패 유도 롤백(`G4_FAIL` / `rollback`) G4 연속성 실측.
3. 호스트 외부 포트 차단(`5432`/`8081` 비공개) 및 자원(`docker stats`·`df`) 실측, M2 실제 Resend·Discord 발송/해지 관측.
