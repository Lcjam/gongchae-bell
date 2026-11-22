# PR #35 비작성자 검수 수정 결과 (`agy`)

- **작성자**: `agy` (기존 PR #35 단독 작성자 역할 재개, 새 에이전트 미사용)
- **요청 근거**: `collab/2026-11-19-resume/nov22-agy-pr35-fixes-request.md`, `collab/reviews/pr-35-codex.md`, `collab/reviews/pr-35-claude.md`, `collab/2026-11-20-performance/final.md`
- **작업 위치 / 브랜치**: `C:/Users/lcj/orca/workspaces/my-project/issue-18-performance-baseline` / `ops/#18-performance-baseline`
- **보존된 이전 PR #35 HEAD SHA**: `1dbd52e096a40fb65ee51eab6ffb776eae7a6383` (히스토리 보존, rebase/amend 없음)
- **기준 `origin/main` SHA**: `eb903017c4fb256a8ece665da795d7a94f6aeb25`
- **수정 소스 커밋 SHA**: `0ff1412a54bd48ad95167d326f405c0cc05f8dbe` (`fix(perf): enforce warmup safety/duration caps and track dynamic container restarts (#18)`)
- **최종 브랜치 HEAD**: 이 결과 문서를 `ops/#18-performance-baseline`에 기록하는 후속 `docs:` 커밋(소스·시험·운영 문서 변경 없음, PR #35 본문에 최종 HEAD SHA 병기)
- **Draft PR**: https://github.com/Lcjam/gongchae-bell/pull/35 (`Refs #18`, base `main`)

---

## 1. 검수 지적별 최소 수정 내역

### 1.1 워밍업 안전 중단·시간 상한·`C=4` barrier 정리 (`deploy/perf/perf-probe.py`, `docs/ops/performance-baseline.md`)
- **공통 안전 판정 적용 (Codex R1 / Claude P2)**:
  - `main()` 내부 `record_result()`로 `warmup`과 `measure` 모든 부하 요청에 동일한 `cum_5xx >= 3`, `consecutive_timeouts >= 2`, `rolling_window`(30초, 최소 10건) 오류율 `> 2%` 안전 중단(`ABORTED_SAFETY`, exit `3`)을 적용했다.
  - `abort_reason`에 `(phase=warmup)` / `(phase=measure)`를 함께 남겨 어느 단계에서 중단됐는지 즉시 식별할 수 있다.
  - **phase 원자료·성능 분모 분리 유지**: `requests.csv`에는 `phase=warmup`/`measure`를 모두 기록하되, `overall`·`by_group`·`by_case` 성능 집계 분모는 계속 `measure_rows`만 사용한다(`summary.json`·`summary.md`에 `warmup_requests`, `warmup_errors`를 별도 표시).
- **임계 이후 dispatch 중단, `C=4` 진행 중 요청 정직 기록, barrier 교착 방지**:
  - 워밍업 각 요청 dispatch 직전 `state_lock` 아래에서 `stop_event`, `--stop-file`, `load_deadline_mono`를 확인해 임계 도달 이후 새 dispatch를 즉시 중단한다. 이미 진행 중이던 `C=4` 요청은 완료 후 `requests.csv`에 정직하게 기록된다(동시성 `C=4`, 5xx 임계 `3`이면 임계 도달 시점에 최대 `C-1=3`건의 진행 중 요청이 완료되어 총 3~6건이 기록되고 이후 추가 dispatch 없음).
  - `trigger_stop()`과 워밍업 조기 종료 경로에서 `warmup_barrier.abort()`를 호출해, 한 워커가 먼저 워밍업을 끝내고 barrier에 대기 중인 상태에서 다른 워커가 안전 중단·시간 상한으로 종료해도 `BrokenBarrierError`로 즉시 해제되도록 했다. 중단 시 `requests.csv`, `summary.json`, `summary.md`, `conditions.md` 4종을 모두 보존하고 `ABORTED_SAFETY`(exit `3`)를 숨기지 않는다.
- **워밍업 실효적 시간 상한 및 시간·타임아웃 의미 명확화 (새 CLI 옵션·추상 계층 없음)**:
  - `load_deadline_mono = load_start_mono + duration_sec`를 부하 시작(워밍업 진입) 시점에 설정해 `--duration`(`S1=180s`, `S2=120s`)이 **워밍업 + 측정을 합친 총 부하 시간 상한**으로 동작하게 했다. 워밍업 중에도 상한 도달 시 새 dispatch를 중단해 `S1`/`S2` 제한 부하가 무한히 연장되지 않는다.
  - `summary.json`/`summary.md`/`conditions.md`와 CLI `--help`, `docs/ops/performance-baseline.md`에 총 부하 시간(`load_elapsed_sec`), 워밍업 시간(`warmup_elapsed_sec`), 측정 시간(`measure_elapsed_sec`)의 의미를 명확히 표기했다.

### 1.2 신규·재생성 컨테이너 `RestartCount` 탐지 (`deploy/perf/sample-resources.sh`, `docs/ops/performance-baseline.md`)
- **baseline 이후 신규 컨테이너 0→1 탐지 (Codex R2)**:
  - `docker inspect` 포맷을 `{{.Id}}|{{.Name}}|{{.RestartCount}}`로 확장하고 `(container_name, container_id)` 단위로 `BASELINE_CONTAINER_ID`·`BASELINE_RESTARTS`를 추적한다.
  - baseline 이후 처음 관측된 신규 컨테이너(예: `blue/green` 배포 대상 신규 앱)의 첫 관측 `container_id`와 `count`를 기준선으로 저장해 이후 `0→1` 증가를 탐지한다. 첫 폴링 전에 이미 재시작해 `count > 0`으로 첫 관측된 경우에도 즉시 `WARN_CONTAINER_RESTART ... origin=new_container`를 기록한다.
- **같은 이름·새 `container_id` 재생성 구별 (Codex R2 / Claude 비차단 #3)**:
  - 같은 이름의 컨테이너가 새 `container_id`로 재생성되면(예: 기존 컨테이너 `RestartCount=3` → 새 ID `0`) ID 변화를 감지해 기준선을 새 컨테이너의 count(`0`)로 재설정하고, 이후 새 컨테이너의 `0→1` 재시작을 누락 없이 경고한다(첫 관측 시 이미 `>0`이면 `origin=recreated_container` 경고). 동일 ID에서 카운터가 감소한 방어적 상황도 기준선을 재조정한다.
  - `containers.csv`에 `container_id`(12자 단축 ID) 열을 추가하고 `sampler-meta.txt`에 `restart_note`를 남겨 `blue/green` 중첩 관측 시 ID 전환을 추적할 수 있게 했다. 기존 정적 초기 앱의 `0→1` 재시작 탐지 회귀를 그대로 유지한다.

### 1.3 Windows `Ctrl+C`(`SIGINT`) 및 소켓 `--timeout` 의미 고지 (Claude 비차단 관찰)
- **Windows `SIGINT` 독립 검증 및 최소 보완**:
  - Windows Python `3.12.10`에서 단독 실험(`threading.Thread.join()` vs `join(timeout=0.1)` + 0.2초 뒤 `signal.raise_signal(signal.SIGINT)`)을 수행한 결과, timeout 없는 `t.join()`은 워커 종료까지 `1.500s` 동안 `SIGINT` 처리기 호출이 지연됐고 `join(timeout)` 반복은 `0.203s`에 즉시 처리기가 실행됨을 직접 확인했다.
  - 이에 따라 `perf-probe.py`의 스레드 대기 루프를 `while t.is_alive(): t.join(timeout=0.2)`로 최소 보완하고, `perf-test.py`에 `signal.raise_signal(signal.SIGINT)`로 `STOPPED_BY_SIGNAL` 및 원자료 보존을 검증하는 회귀 시험을 추가했다.
  - 다만 대화형 Windows 콘솔의 실제 `Ctrl+C` 키 입력 자체는 헤드리스 실행 환경에서 미검증이므로, CLI `--help`와 `docs/ops/performance-baseline.md`에 **특히 Windows 환경에서는 `--stop-file`을 기본 수동 중단 수단으로 권장**한다고 명시했다.
- **소켓 `--timeout` 실제 의미 고지**:
  - `http.client`의 `--timeout`이 **소켓 연결·읽기 1회 대기 상한**이며 응답 전체 경과 상한이 아님(조금씩 흘러나오는 응답은 `--timeout`을 초과할 수 있고 `timeout_truncated_at_ms`는 소켓 대기 상한 절단 표기)을 CLI `--help`, `summary.md`, `conditions.md`, `docs/ops/performance-baseline.md`에 정확히 고지했다.
- 비차단 관찰을 빌미로 범위를 확대하거나 새 상시 서비스·새 CLI 옵션을 추가하지 않았다.

---

## 2. 직접 실행한 검증 및 공식 CI 결과

### 2.1 로컬 직접 검증 (합성 HTTP·`/proc`·`docker` 대역, 실제 사이트/VM 미접근)
- **Windows Python `3.12.10` 독립 `SIGINT` 프로브**:
  - `t.join()`(`use_timeout=False`): `handler_fired_after=1.500s`
  - `t.join(timeout=0.1)`(`use_timeout=True`): `handler_fired_after=0.203s`
- **Windows Python (`python -B deploy/tests/perf-test.py -v`)**: **12/12 통과** (`Ran 12 tests in 7.063s — OK`)
  - 기존 8건 회귀 통과 + 신규 4건 통과:
    1. `test_warmup_safety_abort_5xx_timeout_and_rolling_error_rate` (워밍업 5xx 3건·연속 timeout 2회·최근 오류율 302 중단, phase 분리 및 원자료 4종 보존)
    2. `test_c4_warmup_abort_does_not_deadlock_barrier_and_records_inflight` (`C=4` 동시 in-flight 정직 기록·임계 이후 추가 dispatch 차단·대기 워커 barrier 비교착 해제)
    3. `test_warmup_duration_cap_prevents_unbounded_extension` (느린 200 워밍업이 `--duration` 상한에서 중단되어 무한 연장 방지)
    4. `test_sigint_stops_cleanly_promptly_and_preserves_raw_outputs` (`SIGINT` 즉시 중단·`STOPPED_BY_SIGNAL`·원자료 보존)
- **Linux Docker (`python:3.12-slim`)**:
  - `bash -n deploy/deploy.sh deploy/g4-probe.sh deploy/tests/deploy-test.sh deploy/tests/caddy-integration-test.sh deploy/perf/sample-resources.sh deploy/tests/sample-resources-test.sh`: 통과
  - `python3 -m py_compile deploy/perf/perf-probe.py deploy/tests/perf-test.py`: 통과
  - `python3 -B deploy/tests/perf-test.py -v`: **12/12 통과** (`Ran 12 tests in 14.563s — OK`)
  - `./deploy/tests/sample-resources-test.sh`: **6/6 통과** (`ALL_SAMPLE_RESOURCES_TESTS_PASSED`)
    - 기존 1~4단계(정적 초기 앱 `0→1` 재시작 회귀, overrun tick skip, 2 vCPU 정규화, cgroup/RSS 분리, 덮어쓰기 거부, `--stop-file`) + 신규 5단계(baseline 이후 신규 앱 `0→1` 및 첫 관측 `>0` 탐지) + 신규 6단계(동일 이름 새 `container_id` 재생성 `3 → new_id 0 → 1` 탐지 및 `containers.csv` `container_id` 기록)
  - `./deploy/tests/deploy-test.sh`: **17/17 통과** (`ALL_DEPLOY_TESTS_PASSED`)
- **위생 검증**: 전 수정 파일 LF/UTF-8(no BOM) 확인(`LF_UTF8_NOBOM_OK`), `git diff --check` 통과, `__pycache__` 정리.

### 2.2 GitHub Actions 공식 CI (`ops/#18-performance-baseline`, 소스 커밋 `0ff1412a54bd48ad95167d326f405c0cc05f8dbe`)
- **CI** (Run [`44968425259`](https://github.com/Lcjam/gongchae-bell/actions/runs/44968425259)) — **`success` (4/4 잡 성공)**:
  - `test` (`137042593416`, `2m23s`): `pass`
  - `deploy-verify` (`137042593489`, `1m34s`, `perf-test.py` 12건 + `sample-resources-test.sh` 6단계 + `deploy-test.sh` 17단계 포함): `pass`
  - `backup-verify` (`137042593258`, `56s`): `pass`
  - `secrets` (`137042593431`, `7s`, gitleaks): `pass`
- **Monitoring** (Run [`44968425305`](https://github.com/Lcjam/gongchae-bell/actions/runs/44968425305)) — **`success` (3/3 잡 성공)**:
  - `rules` (`137042593813`, `11s`): `pass`
  - `compose-integration` (`137042593510`, `43s`): `pass`
  - `alert-e2e` (`137042593874`, `6m25s`): `pass`

---

## 3. 미실행 범위 및 남은 한계

- **미실행(금지 범위 준수)**:
  - 실제 공개 사이트 부하 실측(`S1`/`S2`), `COLLECTOR_ENABLED=true` 수집 구간 실측, 실제 배포 `blue/green` JVM 중첩 실측, 실패 유도 `G4_FAIL → abort` 훈련
  - 실제 OCI VM·SSH·계정·`.env`·키·외부 API·Discord·Resend·PC 설치 작업·타 작성자 브랜치·원 리뷰(`collab/reviews/pr-35-*.md`) 및 과거 실측 기록 수정 없음
- **남은 기술적 한계**:
  - `SIGINT`는 Windows Python `3.12.10`에서 `signal.raise_signal(signal.SIGINT)`로 검증했으며, 대화형 Windows 콘솔의 실제 `Ctrl+C` 키 입력(터미널 에뮬레이터별 `CTRL_C_EVENT` 전달 차이 포함)은 직접 검증하지 않았다(문서에서 `--stop-file`을 기본 수단으로 권장).
  - `--timeout`은 `http.client` 소켓 연결·읽기 1회 상한이므로, 바이트를 천천히 흘려보내는 응답의 전체 경과 시간을 강제 절단하지는 못한다(문서·요약에 명시).
  - 합성 대역 테스트는 실제 OCI ARM64 커널 `/proc`, Docker cgroup v2, Caddy TLS, PostgreSQL 17 쿼리 계획을 대체하지 않으며, #18 실측 완료를 주장하지 않는다.
