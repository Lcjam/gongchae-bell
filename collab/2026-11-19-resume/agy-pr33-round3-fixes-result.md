# PR #33 3차 검수(Codex · Sol) 수정 결과 (`agy`)

- **작성자 / 일자**: `agy` (단독 작성, 추가 에이전트 미생성) / 2026-11-22 KST
- **근거 문서**: `collab/reviews/pr-33-codex-round3.md`, `collab/reviews/pr-33-sol-round3.md`, `collab/2026-11-19-resume/agy-pr33-round2-fixes-result.md` (기존 R1·R2 리뷰/요청/결과/원안 및 과거 실패 기록 무수정 보존)
- **작업 위치 / 브랜치**: `C:/Users/lcj/orca/workspaces/my-project/issue-15-collector-drill` / `ops/#15-collector-drill`
- **기준 `origin/main` SHA**: `eb903017c4fb256a8ece665da795d7a94f6aeb25`
- **직전 검수 기준 HEAD SHA**: `152745dee26952e649803b2ba47d959f522076f1` (기존 7/7 CI 성공은 유지하되 본 3차 결함 해결 근거로 사용하지 않음)
- **이번 3차 수정 소스 커밋 SHA (3건)**:
  1. `a1604d6628082112ccd6e96dd3457beb3167670e` — `fix: propagate baseline check errors, enforce TSDB tri-state, and harden PID fixture (Refs #15)`
  2. `76307c25171eefd9cbf64fed77a0fcf40c224a59` — `fix: rename alerts_doc variable to avoid metric regex collision (Refs #15)` (`a1604d6`의 CI `test` `#45027229617/job/137222779450` `MonitoringContractIntegrationTest` 정규식 충돌 수정)
  3. `0370ced21ae5f1b2481f0c17f6e9ff8910840eb7` — `fix: write drill meta and owner files atomically to avoid worker read race (Refs #15)` (`76307c2`의 `compose-integration` `#45028568503/job/137226880648`에서 발견된 `write_meta_files` 비원자적 truncate 레이스 수정)
- **최종 PR #33 HEAD**: 본 결과 문서 추가 커밋(`0370ced21ae5f1b2481f0c17f6e9ff8910840eb7` 직후 1커밋, 코드/스크립트/테스트 추가 변경 없음). 최종 HEAD SHA 및 최종 HEAD 기준 7/7 CI 실행 링크는 Draft PR #33 본문에 함께 갱신.
- **Draft PR**: [#33](https://github.com/Lcjam/gongchae-bell/pull/33) (Draft 유지, `#15` 미종료)

---

## 1. 3차 검수 지적별 수정 및 회귀 내역

### 1) P1 — `check_prom_clean_baseline` Python 실패 명시 전파 (`deploy/monitoring/collector-alert-drill.sh`)
- **결함 원인**: `preflight_checks`가 `prom_check="$(check_prom_clean_baseline 2>&1)" || fail "$prom_check"`처럼 OR-list 문맥에서 호출하므로 함수 내부 `set -e`가 비활성화된다. 기존 Python 검증 뒤에 붙은 `prom_has_nonzero_drill_series`가 clean(`1`)이면 Python 실패(exit 1)가 덮여 함수가 `0`을 반환했다.
- **수정**:
  - `check_prom_clean_baseline`의 Python 검증 블록에 명시적 `|| return 1`을 추가하고, API `status == "success"` 및 `alerts`/`activeTargets`/`yaml` 타입 검증 + 명시적 `return 0`을 적용했다.
  - 동일 `||` / `if` 호출 문맥 점검 중 `capture_runtime_flags_snapshot`(`resolve_app_containers || return 1`)와 `assert_no_unexpected_alerts`(`prom_api_get || return 1`)에도 명시적 실패 전파를 보강했다.
  - Python 변수명 `alerts_doc`가 `MonitoringContractIntegrationTest`의 `alerts_[a-z0-9_]+` 정규식에 걸리지 않도록 `prom_alerts_doc`로 정리했다(`76307c2`).
- **회귀 검증 (`collector-alert-drill-test.sh` Case 1c)**:
  - 실제 `bash "$DRILL_SH" run "${COMMON_ARGS[@]}"` 호출 문맥(정상 clean TSDB 응답 고정)에서 ① 기존 `firing` 경보, ② 기존 `pending` 경보, ③ 건강한 `gongchae-app` target 0건, ④ Prometheus config에 기존 `gongchae-drill` job 존재, ⑤ active targets에 기존 `gongchae-drill` target 존재의 5개 경우 모두 비정상 종료 + 에러 메시지 확인 + **mutation 전 중단**(`OWNER_FILE`·`backup/state` 미생성, `prometheus.yml`/`gongchae.yml` 해시 불변, exporter 미생성)을 검증했다.

### 2) P2 — `prom_has_nonzero_drill_series` 3상태 계약 엄격화 (`0=양수 / 1=유효 0 또는 빈 vector / 2=오류`)
- **결함 원인**: `json.loads`·구조 누락·`float(...)` 예외가 Python 기본 종료 코드 `1`로 빠져 호출자가 clean(`1`)으로 오인할 수 있었다.
- **수정**:
  - `try ... except Exception: raise SystemExit(2)`로 모든 JSON 파싱/스키마/수치 예외를 오류 `2`로 변환했다.
  - `status == "success"`, `data` dict, `resultType == "vector"`, `"result"` 키 존재 및 list, 각 원소 `value` 2-element list, 유한 timestamp·유한 숫자(`math.isfinite`)를 모두 검증하고, 두 쿼리 모두 검증한 뒤에만 `0`(양수 존재) 또는 `1`(유효 빈 vector 또는 유효 0 이하 vector)을 반환한다. Bash 측에서도 `0`/`1` 외 종료 코드는 모두 `2`로 정규화한다.
- **회귀 검증 (`collector-alert-drill-test.sh` Case 1c · Case 5b)**:
  - `preflight`/`run`: 잘린 JSON `{`, `data.result` 누락(`{"status":"success","data":{}}`), 비수치 `"not-a-number"`, `"NaN"` 모두 `PROM_DRILL_SERIES_CHECK_FAILED`로 거부, 양수 `500`은 `LINGERING_DRILL_SERIES_NONZERO`로 거부, 유효 빈 vector `[]` 및 유효 `0` vector는 `DRILL_PREFLIGHT_OK` 통과 확인.
  - `restore`(Case 5b): 잘린 JSON `{`, `data.result` 누락, 비수치 `"not-a-number"` 3건 모두 실제 `bash "$DRILL_SH" restore`에서 비정상 종료 + `failed to query Prometheus TSDB for lingering gongchae-drill series` + `STATE=RESTORE_FAILED` 기록 확인 후 정상 retry로 `DRILL_RESTORE_VERIFIED_OK` 확인.

### 3) P2-2 — 무관 PID 시험 fixture 수명 및 종료 원인 구분 (`collector-alert-drill-test.sh` Case 6c)
- **과거 실패 기록 보존**: `fa662fe`의 `Monitoring #45003677085/job/137150449931` 실패(`sleep 60`이 두 차례 `restore` 합산 ~61초 중 만료돼 `restore killed unrelated process`로 기록된 로그)는 `agy-pr33-round2-fixes-result.md` 및 GitHub Actions 이력에 그대로 보존하며, 당시 로그만으로는 신호 살해와 자연 만료를 구별할 수 없었음을 구분한다.
- **수정**:
  - `784337e`의 `sleep 300`을 넘어, Case 6c의 무관 프로세스(`UNRELATED_PID`)와 Linux `flock` lease holder(`LEASE_HOLDER_PID`)를 모두 시험 전용 sentinel 파일(`$WORK/unrelated_keepalive`, `$WORK/lease_keepalive`) 기반 루프로 변경해 **본 시험이 명시적으로 sentinel을 제거할 때까지 유지**되도록 했다(`cleanup()`에서도 자기 시험 프로세스만 정리).
  - `assert_fixture_alive` 헬퍼를 추가해 생존 실패 시 `wait "$pid"` 종료 상태를 수집하고, `wait_rc > 128`(신호 종료 `signal=$((wrc-128))`)과 정상/비신호 종료(`exit_code`)를 명확히 구별한다. 해제 시에도 `wait`로 `exit 0` 정상 종료를 확인한다.

### 4) CI 중 추가 발견·수정 — `write_meta_files` 비원자적 쓰기 레이스 (`0370ced`)
- `76307c2`의 `Monitoring #45028568503/job/137226880648` Case 4에서 `_worker` 기동 직후 `verify_worker_attempt_active`가 `$OWNER_FILE`을 읽는 순간 감독자의 `write_meta_files "ARMED"`가 `>` 리다이렉션으로 파일을 0바이트로 잘라 `owner run_id= state=ARMED` 레이스가 발생했다.
- `meta.env`, `state`, `drill-runtime.owner` 3파일 모두 동일 디렉터리 임시 파일(`*.tmp.$$`)에 쓴 뒤 `mv -f`로 POSIX 원자적 교체하도록 수정해 읽기 레이스를 원천 제거했다.

---

## 2. 직접 실행 검증 · CI 이력(실패 보존 포함) · 미실행 및 한계

### 로컬 직접 실행 (Windows 11 + Git Bash + Gradle)
- `bash -n deploy/monitoring/collector-alert-drill.sh deploy/monitoring/tests/collector-alert-drill-test.sh` 및 `git diff --check`: `exit 0`.
- `gitleaks dir --verbose deploy/monitoring`: `no leaks found`.
- 로컬 함수 추출 probe(실제 `prom_has_nonzero_drill_series`, `check_prom_clean_baseline` 및 `out="$(check_prom_clean_baseline 2>&1)" || rejected=1` OR-list 호출 문맥): 19/19 통과 (`ALL_ROUND3_LOCAL_PROBES_PASSED` — TSDB 8건 + baseline 거부 9건 + 유효 empty/zero 수락 2건).
- `./gradlew.bat test --rerun --tests kr.gongchae.bell.ops.MonitoringContractIntegrationTest` (`76307c2` 기준): `BUILD SUCCESSFUL in 23s`.

### 원격 ARM64 Linux CI 이력 (실패 보존 + 최종 소스 7/7 `SUCCESS`)
- **과거 실패 기록 보존**:
  - `fa662fe` [`Monitoring #45003677085/job/137150449931`](https://github.com/Lcjam/gongchae-bell/actions/runs/45003677085/job/137150449931): Case 6c `sleep 60` 수명 부족 실패 → `784337e` 및 본 3차 sentinel fixture(`a1604d6`)로 수정.
  - `a1604d6` [`CI #45027229617/job/137222779450`](https://github.com/Lcjam/gongchae-bell/actions/runs/45027229617/job/137222779450): `MonitoringContractIntegrationTest`의 `alerts_doc` 변수명 정규식 충돌 실패(동일 SHA `Monitoring #45027229660` 3/3 `pass`) → `76307c2`로 수정.
  - `76307c2` [`Monitoring #45028568503/job/137226880648`](https://github.com/Lcjam/gongchae-bell/actions/runs/45028568503/job/137226880648): `write_meta_files` 비원자적 truncate 레이스(`owner run_id= state=ARMED`) 실패(동일 SHA `CI #45028568554` 4/4 `pass`) → `0370ced`로 수정.
- **최종 소스 커밋 `0370ced21ae5f1b2481f0c17f6e9ff8910840eb7` 원격 CI (7/7 `SUCCESS`)**:
  - **CI** ([`#45029244268`](https://github.com/Lcjam/gongchae-bell/actions/runs/45029244268)): `test` (`2m22s`), `deploy-verify` (`1m31s`), `backup-verify` (`1m4s`), `secrets` (`6s`) — **4/4 `pass`**.
  - **Monitoring** ([`#45029244278`](https://github.com/Lcjam/gongchae-bell/actions/runs/45029244278)): `rules` (`10s`), `compose-integration` ([`job/137228934802`](https://github.com/Lcjam/gongchae-bell/actions/runs/45029244278/job/137228934802), `11m22s`), `alert-e2e` (`9m4s`) — **3/3 `pass`**.
  - 실제 `ubuntu-24.04-arm` Linux에서 Case 1c(baseline 5건 0-mutation 거부 + TSDB 3상태), Case 5b(복원 중 malformed TSDB 3건 `RESTORE_FAILED`), Case 6c(sentinel keepalive fixture + `assert_fixture_alive` + Linux `flock` 미확인 lease fail-closed + PID 재사용 보호), 기존 `expr`/`for: 10m`·`0/0` flush·post-`SIGHUP` 평가·Flyway `V1..V5` DB drift·외부 파일 보존까지 전체 통과(`COLLECTOR_ALERT_DRILL_TEST_PASSED`).

### 미실행 및 보존된 한계
- 로컬 Windows에서 전체 `collector-alert-drill-test.sh`를 3차 수정 후 중복 재실행하지 않았으며, 로컬에서는 `bash -n`·`gitleaks`·19건 함수 probe·`MonitoringContractIntegrationTest`를 직접 실행하고 전체 Docker/Linux `flock`/stale-inode 통합 시험은 원격 `ubuntu-24.04-arm` CI(`compose-integration`)에서 실실행 확인했다.
- 실제 운영 SSH · OCI · 계정 · `.env` · 비밀키 · 운영 DB · 공개 앱 · 외부 공공 API · 운영 Discord · Resend에는 접근/변경하지 않았으며, 실제 10분 운영 훈련은 재검수·병합 이후 별도 배정한다.
- 호스트 전원 단절, Docker 데몬 불능, 감독자 자체 `SIGKILL` 시 자동 복구 완료 불가 한계는 그대로 유지한다.
