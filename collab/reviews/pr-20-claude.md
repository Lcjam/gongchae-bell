# PR20 검수 · 2라운드 · Claude

2026-11-18. root Claude가 Codex 대신 조율자로서 검수했다(Codex 사용량 한도). 대상은 PR #20 `ops/#14-bluegreen-deploy` head `24b15eb`, 코드 커밋 `2cecc9f`. 범위는 `git diff ede7ce1 24b15eb`이고 기준 결함은 `pr-20-codex.md`, `pr-20-sol.md`, 요청 `agy-deployment-fixes-request.md`의 네 항목이다.

## 판정

**수용.** 네 결함 모두 코드에서 닫혔고 회귀 시험이 각 경로를 다룬다. 아래 비차단 메모 두 건은 Codex 2차 검수 때 함께 보면 된다.

## 결함별 확인

| # | 결함 | 확인 |
|---|---|---|
| 1 | finalize 중간 중단 후 abort가 이전 active 메타데이터를 복구하지 않음 | `write_pending_state`가 전환 직전에 `pending_from_deploy_id`, `pending_prev_*`, `pending_target_prev_tag`를 쓰고 `pending_deploy_id`를 마지막에 쓴다. `abort_pending_internal`은 구 색이 멈춰 있으면(`is_app_color_healthy_now` 실패) 다시 띄우고 upstream을 되돌린 뒤, 새 색 정지·제거가 성공한 다음에 `active_*`, `prev_*`, 양쪽 색 태그를 복원하고 마지막에 pending을 지운다. finalize 재시도는 `resolve_pending_from_deploy_id`로 이전 deploy ID를 읽어 `prev_deploy_id`가 덮어써지지 않는다. 중간 중단 지점마다 pending이 남거나(재시도로 수렴) pending_deploy_id가 먼저 지워진다(고아 파일은 다음 배포의 `assert_no_pending_and_consistent_state`가 정리, `status`는 무시). `start_app_color`는 태그 상태 파일을 쓰지 않으므로 `target_prev_tag`를 앱 시작 뒤에 읽어도 이전 값이다. 시험 `[13/15]` |
| 2 | Caddy 기본/error logger에 필터 미적용 | 필터를 `(redacted_log_format)` 스니펫으로 빼서 전역 `log`(기본 logger)와 사이트 `log`에 모두 적용했다. `caddy-integration-test.sh`가 활성 앱을 멈춰 실제 502를 만들고 `http.log.error` 발생을 확인한 뒤 stdout/stderr 전체에서 합성 `t`·Referer·Authorization·Cookie 값이 없는지 본다(`keep=1`은 남음). CI 로그에 `CADDY_INTEGRATION_TEST_PASSED` |
| 3 | CLI full 배포·사후 rollback이 외부 G4 없이 finalize | `require_g4_hook`가 `cmd_bluegreen`·사후 `cmd_rollback`의 `--phase full`에서 전환 전에 실행 가능한 `G4_HOOK`을 요구한다(누락·디렉터리·실행 불가면 컨테이너와 Caddy를 건드리지 않고 실패). G4 실패는 abort 후 실패, 성공만 finalize. pending이 있는 rollback은 `require_g4_hook` 전에 abort로 빠지고, bootstrap은 영향 없다. `deploy.yml`은 원래대로 `--phase switch` → 외부 `g4-probe.sh` → finalize/abort 순서다. Runbook 명령을 맞췄다. 시험 `[14/15]` |
| 4 | finalize가 구 앱 stop/rm 오류를 삼킴 | `stop_and_remove_color`가 `|| true` 없이 `ps -a -q`(없으면 멱등 성공) → stop → `ps --status running` 확인 → rm → `ps -a -q` 확인으로 실패를 돌려준다. finalize·abort·전환 후 정리는 실패 시 `last_deploy_result`에 `FINALIZE_STOP_FAILED`/`ABORT_STOP_FAILED`/`POST_SWITCH_CLEANUP_FAILED`를 남기고 pending을 보존해 재시도할 수 있다. 실패 정리 경로(전환 전 실패 등)만 `|| true`로 원래 오류를 우선한다. 시험 `[15/15]` |

## CI

- 코드 `2cecc9f`: [44225358776](https://github.com/Lcjam/gongchae-bell/actions/runs/44225358776) test·deploy-verify(ARM64, `ALL_DEPLOY_TESTS_PASSED`, `CADDY_INTEGRATION_TEST_PASSED`)·secrets 성공
- 문서 `24b15eb`: [44225651081](https://github.com/Lcjam/gongchae-bell/actions/runs/44225651081) 성공

## 비차단 메모

1. 중간 커밋 `680025f`의 CI [44225007091](https://github.com/Lcjam/gongchae-bell/actions/runs/44225007091)가 secrets에서 실패했다(테스트용 가짜 인증 헤더를 Gitleaks가 잡은 오탐). 이후 그 커밋을 고쳐 force-push한 것으로 보인다. draft PR의 단독 작성자 브랜치라 문제는 없지만, 리뷰가 붙은 브랜치에서는 새 커밋으로 고치는 편이 검수 기록을 남긴다.
2. abort 재시작 경로에서 새 색 컨테이너의 정지·제거는 `export_color_tags`가 상태 파일의 태그(또는 `unset`)로 Compose를 보간한다. stop/rm은 서비스 이름으로 동작하므로 결과에는 영향이 없다고 판단했다. 기존 동작이며 이번 범위 밖이다.

## 확인하지 못한 것

`deploy-test.sh`와 `caddy-integration-test.sh`를 로컬에서 다시 돌리지 않았다. CI 로그의 통과 표식과 코드 읽기로 판단했다. 실서버·실제 G4·OCI·DNS는 PR 본문의 후속 환경 게이트로 남는다.
