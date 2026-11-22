# PR #33 2차 재검수(Codex · Sol) 수정 결과 (`agy`)

- **작성자 / 일자**: `agy` (단독 인계, 추가 에이전트 미생성) / 2026-11-22 KST
- **근거 문서**: `collab/reviews/pr-33-codex-round2.md`, `collab/reviews/pr-33-sol-round2.md`, `collab/2026-11-19-resume/nov22-agy-pr33-round2-fixes-request.md` (기존 R1 리뷰/요청/결과/원안 무수정 보존)
- **작업 위치 / 브랜치**: `C:/Users/lcj/orca/workspaces/my-project/issue-15-collector-drill` / `ops/#15-collector-drill`
- **기준 `origin/main` SHA**: `eb903017c4fb256a8ece665da795d7a94f6aeb25`
- **보존된 직전 PR #33 HEAD SHA**: `7a2df3178f6a046b0b83a3d0cc6a4476fc9e3a2e` (R1 수정 소스 `3cb5eab1b67a59dc9bf57eaa9a2dc8a338be500a`)
- **이번 R2 수정 소스 커밋 SHA**:
  - `55eae4e61a4c3c350e600a448f7e54e430cabb8e` (`fix: bound kill grace, fence orphaned workers after SIGKILL, and flush TSDB on restore (Refs #15)`)
  - `784337e0a110d3a75223c4d1fd6c4e71ea742c72` (`test: extend unrelated PID probe lifetime beyond dual restore eval window (Refs #15)` — `fa662fe`의 `compose-integration` `#45003677085/job/137150449931`에서 Case 6c의 두 차례 `restore`(각 ~30s post-SIGHUP 평가 대기) 합산 ~61초가 `sleep 60` 무관 PID probe 수명을 자연 만료시킨 테스트 결함을 `sleep 300` + `cleanup()` 정리로 수정)
- **최종 PR #33 HEAD**: 본 결과 문서 갱신 커밋(`784337e0a110d3a75223c4d1fd6c4e71ea742c72` 직후 1커밋, 코드/스크립트/테스트 추가 변경 없음). 최종 HEAD SHA와 최종 HEAD 기준 7잡 CI 결과는 Draft PR #33 본문에 함께 기록.
- **Draft PR**: [#33](https://github.com/Lcjam/gongchae-bell/pull/33) (Draft 유지, `#15` 미종료)

---

## 1. 항목별 수정 내역

### 1) P1 R2 / Sol P1-1 — `run_bounded` 강제 KILL 상한, 신호 마스킹 상속 차단, `recreate` 경로 상한
- **`run_bounded` (`deploy/monitoring/collector-alert-drill.sh`)**:
  - 기존 `timeout "$secs" "$@"`는 `SIGTERM`을 무시하는 자식이나 `restore_and_verify`의 `trap '' INT TERM HUP`(`SIG_IGN`) 상속 시 무기한 대기할 수 있었다.
  - 서브셸에서 `trap - INT TERM HUP`으로 신호 처리를 기본값(`SIG_DFL`)으로 복원하고 배포 루트 잠금 FD(`9>&-`)를 닫은 뒤 `exec timeout -k "$CMD_KILL_AFTER_SEC" "$secs" "$@"`(기본 `CMD_TIMEOUT_SEC=20`, `CMD_KILL_AFTER_SEC=5`)로 실행하도록 수정했다.
  - GNU `timeout`은 기본 모드(`--foreground` 미사용)에서 자식을 별도 프로세스 그룹으로 실행하므로, `SIGTERM`을 무시하는 자식·후손까지 유예 시간 후 `SIGKILL`로 프로세스 그룹 전체를 강제 종료한다.
- **`--allow-recreate-prometheus` 재생성 경로**:
  - 기존 무제한 `bash -c "$RECREATE_CMD"` 호출을 `run_bounded "$RECREATE_TIMEOUT_SEC" bash -c "$RECREATE_CMD"`(기본 `90s`, 실패 시 `RECREATE_PROMETHEUS_FAILED`)로 감쌌다.
- **`docker ps` 타임아웃 오판 방지 (`exporter_container_exists`)**:
  - 기존 `if run_bounded ... docker ps -a | grep -Fxq ...`는 `docker ps`가 타임아웃돼도 `grep`이 1을 반환해 exporter가 없는 것으로 오판할 수 있었다. `0`(존재) / `1`(정상 조회·부재) / `2`(오류·타임아웃) 3상태로 분리해 `preflight` 및 `restore_and_verify`(`RESTORED` no-op 포함)에서 오류를 절대 은폐하지 않게 했다.
- **`ACTION=run` 종료 시 중복 `restore_and_verify` 방지**:
  - `RESTORE_ATTEMPTED=1` 및 `set +e` 경계로 `restore_and_verify`가 실패했을 때 `EXIT` trap에서 중복 복구를 재실행하지 않고 정확한 실패 상태(`RESTORE_FAILED`)와 비정상 종료 코드를 반환하게 정리했다.

### 2) P1 R3 / Sol P1-2 — 감독자 `SIGKILL` 이후 수동 `restore` 워커 펜싱 및 시도별 격리 계약
- **시도별 격리 경로 & 메타데이터 계약 (`write_meta_files`, `prepare_drill_files`, `load_meta_for_restore`)**:
  - 각 실행은 고유 `RUN_ID`와 시도별 작업 디렉터리 `ATTEMPT_DIR="${WORK_DIR}/runs/${RUN_ID}"`(내부 `run_id` 파일 포함)를 사용한다. 같은 기본 `--work-dir`로 새 실행을 시작해도 새 실행은 `${WORK_DIR}/runs/${NEW_RUN_ID}`만 사용하므로 이전 워커가 새 실행의 `metrics.prom`에 접근할 수 없다.
  - `meta.env`와 `deploy/state/drill-runtime.owner`에 `ATTEMPT_DIR`, `SUPERVISOR_PID`, `SUPERVISOR_START_TIME`, `WORKER_PID`, `WORKER_START_TIME`, `WORKER_LOCK_FILE`(`${STATE_DIR}/worker-${RUN_ID}.lock`), `WORKER_REVOKED_FILE`(`${STATE_DIR}/worker-${RUN_ID}.revoked`)을 기록한다.
- **커널 워커 lease lock & `/proc` 정체성 검증 (`fence_and_terminate_worker`, `is_verified_worker_pid`, `is_worker_lease_free`)**:
  - Linux(`flock` 지원 환경)에서 감독자는 워커 spawn 직전에 FD 8로 `worker-${RUN_ID}.lock` 배타 `flock`을 잡아 워커에만 상속시키고 즉시 자기 FD 8을 닫는다(워커 시작 레이스 창 0).
  - 감독자 정상 중단뿐 아니라 **수동 `ACTION=restore`도** `restore_and_verify` 초입에서 `fence_and_terminate_worker`를 실행한다.
  - 신호 송신 전 반드시 `/proc/<pid>/cmdline`의 정확한 `_worker` + `--run-id <RUN_ID>` 인자와 `/proc/<pid>/stat`의 부팅 기준 시작 시각(`starttime`, field 22)을 검증해 일치하는 워커 및 검증된 워커의 후손(`list_descendant_pids`)만 `TERM`→5초 후 `KILL`로 종료한다. **PID가 무관한 프로세스에 재사용된 경우 절대 신호를 보내지 않는다.**
  - 검증된 워커가 여전히 살아 있거나 커널 lease(`worker-${RUN_ID}.lock` 또는 `/proc` 스캔)가 해제되지 않으면 **`DRILL_RESTORE_FAILED UNVERIFIED_WORKER_ALIVE`** + `STATE=RESTORE_FAILED` + 비정상 종료로 fail-closed 처리해 새 실행의 자원 재사용을 차단한다.
- **워커 쓰기 시점 다중 펜스 (`verify_worker_attempt_active`, `write_metrics`)**:
  - `_worker`가 `write_metrics`를 호출하거나 장기 대기 루프를 돌 때마다 ① `WORKER_REVOKED_FILE` 부재, ② `${ATTEMPT_DIR}/revoked` 부재, ③ `${ATTEMPT_DIR}/run_id == $RUN_ID`, ④ `OWNER_FILE`의 `RUN_ID == $RUN_ID && STATE == ARMED`, ⑤ 감독자 PID+`starttime` 생존을 모두 검증한다.
- **물리적 한계 보존 (`agy-live-collector-alert-plan-round2.md`)**:
  - 호스트 전원 단절, Docker 데몬 불능, 감독자 자체 `SIGKILL` 시 자동 복구 완료를 보장하지 않는다는 한계는 그대로 유지하며, 그 이후 수동 `restore`가 살아 있는 이전 워커를 안전하게 차단·종료하거나 미확인 시 fail-closed함을 구분해 명시했다.

### 3) P2 R4 — CI `44720253183/136233865613` (`False alerts fired at 0/0 drill baseline`) 실제 원인 규명 및 수정
- **실제 원인 (Prometheus v3.16.0 직접 재현 확인)**:
  - `SIGHUP`으로 `prometheus.yml`에서 `job_name: gongchae-drill` 전체 scrape job을 제거할 때 Prometheus는 `StaleNaN`을 기록하지 않는다. 따라서 직전 Case 3(`SIGTERM` 중단)에서 스크랩된 `collector_api_attempts_today{job="gongchae-drill"}=500`, `collector_regular_incomplete_consecutive{job="gongchae-drill"}=2` 시계열이 5분 lookback delta 동안 TSDB에 그대로 살아 남는다.
  - 동시에 `rules/gongchae.yml`의 `summary`에서 `[훈련]`을 제거하고 `SIGHUP`하면 `gongchae` rule group이 재적재되며 다음 `evaluation_interval(30s)` 전까지 `/api/v1/alerts`가 일시적으로 `[]`을 반환한다. 기존 `restore_and_verify`는 이 일시적 `[]`만 보고 `DRILL_RESTORE_VERIFIED_OK`를 선언했고, 직후 평가 주기(및 Case 4 시작 직후)에 남아 있던 `500/2` 시계열이 다시 평가돼 `GongchaeApiBudgetWarning(firing)`·`GongchaeRegularCollectionFailing(pending)`가 발동했다. (실제 운영에서도 중단 복구 후 `[훈련]` 없는 실경보가 최대 5분 발동할 수 있는 결함이었다.)
- **수정 (`restore_and_verify`, `check_prom_clean_baseline`, `run_worker_phase`)**:
  - `restore_and_verify`는 워커를 차단·종료한 직후(Prometheus 조회·exporter 제거·`SIGHUP` 전) **무조건 `${ATTEMPT_DIR}/metrics.prom`을 `0/0`으로 먼저 덮어쓰고**(스크랩 TOCTOU 레이스 차단), `job="gongchae-drill"`에 `> 0` 샘플이 있으면 Prometheus가 `0/0`을 스크랩해 TSDB에 반영(`DRILL_TSDB_FLUSHED_ZERO`)할 때까지 대기한 뒤 config/rules 원복과 exporter 제거·`SIGHUP`을 수행한다.
  - `SIGHUP` 이후 `prom_rule_group_last_eval`(`/api/v1/rules?type=alert`)로 `gongchae` rule group이 실제로 새 평가를 완료할 때까지 대기(`wait_for_prom_rule_eval_after`)하고, `prom_has_nonzero_drill_series`로 TSDB에 비영 `gongchae-drill` 시계열이 없음을 명시 검증한 뒤 경보 `0`건을 확인한다.
  - `check_prom_clean_baseline`과 `run_worker_phase`도 각각 `LINGERING_DRILL_SERIES_NONZERO` 차단 및 `wait_for_drill_zero_baseline`(0/0 스크랩 완료)을 명시 검증한다.
  - 원래 `expr`, `for: 10m`, `scrape_interval/evaluation_interval: 30s`, `promtool` 회귀, `RESTORED` no-op, 외부 변경 보존, `export`/runtime `false`, 필수 원본 SHA-256 검증은 그대로 유지했다.

---

## 2. 직접 실행 검증 결과 · CI · 미실행 및 한계

### 로컬 직접 실행 (Windows 11 + Docker Desktop 29.9.2 + Git Bash)
- `bash -n deploy/monitoring/collector-alert-drill.sh deploy/monitoring/tests/collector-alert-drill-test.sh` 및 `git diff --check`: 통과 (`exit 0`).
- `promtool check config /etc/prometheus/prometheus.yml` (`SUCCESS`, 9 rules) 및 `promtool test rules /etc/prometheus/tests/gongchae.test.yml` (`SUCCESS`).
- `./gradlew.bat test --rerun --tests kr.gongchae.bell.ops.MonitoringContractIntegrationTest`: `BUILD SUCCESSFUL in 44s`.
- `gitleaks dir --verbose deploy/monitoring collab/2026-11-19-resume/agy-live-collector-alert-plan-round2.md`: `no leaks found`.
- **`bash deploy/monitoring/tests/collector-alert-drill-test.sh`**: 전체 통과 (`COLLECTOR_ALERT_DRILL_TEST_PASSED`)
  - Case 1: 실제 Flyway `V1..V5` 적용 `postgres:17`, 필수 SHA 누락/불일치, `export COLLECTOR_ENABLED=true`, `.env` `false` vs 런타임 앱 `true` 차단, 정상 preflight.
  - Case 1b: `--allow-recreate-prometheus`에서 `TERM` 무시 자식+후손(`sleep 60`)을 `6s` 만에 강제 종료(`RECREATE_PROMETHEUS_FAILED`, 후손 잔존 0).
  - Case 2: 디렉터리 bind mount 호환 (`MOUNT_OK:directory`).
  - Case 3: 실제 firing + `for: 10m` pending, 다른 `--backup-dir` 동시 `run #2` 차단(`DRILL_LOCK_BUSY`), 동시 `restore` 직렬화(`DRILL_RESTORE_ALREADY_RESTORED_NOOP`), 복구 중 2차 `SIGTERM` 무시, `DRILL_TSDB_FLUSHED_ZERO`, `rules/drill-keep.yml` 보존.
  - Case 4: Case 3 직후 깨끗한 `0/0` 기준선(`DRILL_BASELINE_UP`)에서 180s 워커 정체 시 40s 마감 독립 복구(`exit 124`, `116s < 180s`).
  - Case 5: `restore_and_verify` 신호 마스킹 상태에서 `TERM` 무시 `docker kill` 자식+후손(`sleep 120`)을 `30s` 내 종료, 후손 잔존 0, `RESTORE_FAILED` 기록(절대 `RESTORED` 미기록).
  - Case 6: 감독자만 `SIGKILL` → 워커 생존 확인 → `preflight` 차단(`PREVIOUS_DRILL_NOT_RESTORED`) → 수동 `restore`가 생존 워커 종료 및 `ATTEMPT_DIR` 정리(`DRILL_RESTORE_VERIFIED_OK`) → 동일 `--work-dir` 새 `run` 중 구 `RUN_ID` 지연 writer 거부 및 새 `metrics.prom` 해시 불변 → 무관 프로세스 PID 재사용 시 미종료 확인.
  - Case 7: `STATE=RESTORED` no-op 신규 설정 보존, `STATE=ARMED` 외부 변경 보존(`UNEXPECTED_EXTERNAL_CHANGE`), 실제 `posting.last_seen_at` 변경 탐지(`DRILL_RESTORE_FAILED`) 및 원복 후 멱등 재복구.

### 원격 ARM64 Linux CI 검증 (최종 소스·테스트 커밋 `784337e0a110d3a75223c4d1fd6c4e71ea742c72` 기준, 7/7 `SUCCESS`)
- **CI** ([`#45004482797`](https://github.com/Lcjam/gongchae-bell/actions/runs/45004482797)): `test` (`2m15s`), `deploy-verify` (`1m40s`), `backup-verify` (`1m2s`), `secrets` (`9s`) — **4/4 `pass`**.
- **Monitoring** ([`#45004482840`](https://github.com/Lcjam/gongchae-bell/actions/runs/45004482840)): `rules` (`10s`), `compose-integration` ([`job/137152905837`](https://github.com/Lcjam/gongchae-bell/actions/runs/45004482840/job/137152905837), `9m20s`), `alert-e2e` (`6m30s`) — **3/3 `pass`**.
- *(참고: 직전 코드 커밋 `55eae4e61a4c3c350e600a448f7e54e430cabb8e`도 `CI #45002682356` 4/4 `pass` · `Monitoring #45002682325` 3/3 `pass`였으며, 중간 결과 커밋 `fa662fe07fe7423b8b900d5aadbb4fb36741f7d8`에서 `compose-integration` `#45003677085/job/137150449931`이 Case 6c `sleep 60` 자연 만료로 실패한 원인을 확인해 `784337e`로 수정 후 7/7 통과를 재검증했다.)*
- `ubuntu-24.04-arm` 실실행 확인 항목: Linux `tar` stale single-file inode 탐지(`MOUNT_STALE_SINGLE_FILE`), bounded recreate `3s`, Case 3 TSDB `0/0` flush, Case 4 독립 마감 복구 `89s < 180s`, Case 5 신호 마스킹 하 `TERM` 무시 자식+후손 종료 `31s` + `RESTORE_FAILED`, Case 6 Linux `flock` 기반 미확인 lease fail-closed(`UNVERIFIED_WORKER_ALIVE`) + 감독자 `SIGKILL` 후 수동 복구 워커 종료 + PID 재사용 보호 + 동일 `--work-dir` 격리, `COLLECTOR_ALERT_DRILL_TEST_PASSED`.

### 미실행 및 보존된 한계
- 실제 운영 SSH · OCI · 계정 · `.env` · 비밀키 · 운영 DB · 공개 앱 · 외부 공공 API · 운영 Discord · Resend에는 전혀 접근/변경하지 않았다.
- 실제 10분 실운전 및 Grafana→운영 Discord 채널 수신 검증은 본 PR의 재검수·병합 이후 별도 배정에서 수행한다.
- 호스트 전원 단절, Docker 데몬 불능, 감독자 자체 `SIGKILL` 시 자동 복구 완료를 보장할 수 없다는 물리적 한계는 그대로 유지한다.
