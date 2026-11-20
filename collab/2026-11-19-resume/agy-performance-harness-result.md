# #18 성능·자원 최소 2파일 측정 도구 구현 결과 (`agy`)

- **작성자**: `agy` (단독 작성자)
- **요청 근거**: `collab/2026-11-19-resume/agy-performance-harness-request.md`, `collab/2026-11-20-performance/final.md`
- **작업 위치 / 브랜치**: `C:/Users/lcj/orca/workspaces/my-project/issue-18-performance-baseline` / `ops/#18-performance-baseline`
- **기준 `origin/main` SHA**: `eb903017c4fb256a8ece665da795d7a94f6aeb25` (`Merge pull request #31 from Lcjam/fix/#7-metric-commit`)
- **소스 커밋 SHA**: `1ec43115797faf0f921243d510415937a3e12fb6` (`ops(perf): add minimal performance probe and host resource sampler (#18)`)
- **Draft PR**: https://github.com/Lcjam/gongchae-bell/pull/35 (`Refs #18`, base `main`)

---

## 1. 구현 산출물 및 실행 위치 분리 계약

외부 부하 도구 의존성, 새 GitHub Actions 워크플로, 상시 쿼터 서비스 없이 합의된 최소 2파일 도구와 예시 매니페스트·문서·로컬 대역 시험을 구현했다.

1. **[`deploy/perf/perf-probe.py`](../../deploy/perf/perf-probe.py)** (`100755`, Python 3 표준 라이브러리 전용)
   - **실행 위치**: **OCI VM 바깥 고정 클라이언트**(예: 운영자 PC)에서 공개 HTTPS(또는 단위 시험용 로컬 HTTP/HTTPS 대역)를 대상으로 실행. SSH·VM·DB·`.env` 접근 없음.
   - **고정 입력·사전 검증(Preflight)**:
     - 3열 TSV(`group`, `case_id`, `path`) 매니페스트를 검증하며, `JobSearchCriteria.java`의 대소문자 구별 소문자 허용값(`status`: `open|check|closed|all`, `sort`: `deadline|begin`, `career`: `newcomer|newonly|career`)만 통과시킨다. 대문자 `ALL`/`OPEN`, 잘못된 `sort=start`, 금지 경로(`/jobs/{sn}/source`, `/subscriptions/*`), POST·리다이렉트 추적을 차단한다.
     - 측정 전 모든 URL을 1회 조회해 HTTP `200`, 화면군별 핵심 마커(`home`·`detail`·`institution`의 `<h1>`/섹션 ID, `/jobs`의 `#jobs-filter-panel`·`검색 결과 N건`), 필터별 `<option selected>`·`#filter-q` 값(`result_count > 0`)·`<li class="page-item active">` 실제 페이지 번호(클램핑 감지)를 확인한다. `sort=begin`처럼 기본 `/jobs`와 결과 건수가 같을 수 있는 필터도 `<option value="begin" selected>` 및 필터 패널 표시로 정확히 판별한다.
   - **내부 헤더·keep-alive·집계**:
     - 모든 요청에 `X-Gongchae-Internal: 1`과 `User-Agent: gongchae-probe/perf-18`을 고정 전송해 `CatalogMetrics.java`가 `traffic="internal"`로 분류하도록 한다(`traffic="user"` 오염 방지).
     - 워커당 영구 `http.client.HTTPConnection`/`HTTPSConnection` 1개를 재사용하는 **closed-loop keep-alive** 방식이며, 원자료 CSV에 `reused_conn`(`0|1`)과 `reconnect_count`를 기록하고 응답 본문·PII·비밀값은 저장하지 않는다. 한글 쿼리(`q=채용`)는 HTTP/1.1 전송 전 UTF-8 퍼센트 인코딩한다.
     - 설정 제안 프로파일: `S1`(`C=1`, 화면군당 `40`건 또는 `180초`), `S2`(`C=4`, `120초` 또는 `1,500`건), 워밍업 1라운드 제외, 결정적 라운드로빈 순환, 상한 도달 시 즉시 종료(강제 분모 채움 금지).
     - `measure` 전체 요청을 `n_total`로 삼고 비200(3xx 포함)·타임아웃(기본 10초)·연결/TLS 오류를 분리 집계한다. nearest-rank(`ceil(0.95 * n)`)로 성공 표본 `p95_ok`(`n_ok`)를 기본 보고하고 오류 1건 이상이면 전체 표본 `p95_all`(타임아웃 상한 절단 표기)을 병기하며 `n < 20`인 화면군·케이스는 p95를 산출하지 않고 `n`과 `max`만 기록한다.
   - **안전 중단 및 원자료 보존**:
     - 직전 `30초` 창 오류율 `> 2%`(최소 창 표본 10건), 누적 HTTP `5xx >= 3`, 연속 타임아웃 `>= 2` 시 즉시 중단(`ABORTED_SAFETY`, exit `3`), `--stop-file`·`SIGINT`·`SIGTERM` 시 정상 중단(`STOPPED_BY_*`). 중단 시에도 수집된 원자료(`requests.csv`, `summary.json`, `summary.md`, `conditions.md`)를 모두 저장하고 상태를 숨기지 않는다.
     - `--out-dir`에 기존 결과 파일이 있으면 덮어쓰지 않고 즉시 실패(`OUTPUT_EXISTS`)한다.
2. **[`deploy/perf/sample-resources.sh`](../../deploy/perf/sample-resources.sh)** (`100755`, Bash, 읽기 전용)
   - **실행 위치**: **Linux OCI VM 내부**(`ubuntu` 계정). 컨테이너 재시작, DB·`.env`·배포 상태 변경 없음.
   - 5초 monotonic 스케줄(`scheduled_utc`와 `actual_utc` 병기)로 호스트 `/proc/stat`·`/proc/meminfo`·`/proc/loadavg`·`df -B1` 및 `docker stats --no-stream`·`RestartCount`를 수집하며, 구간 전후 `docker system df`와 선택 볼륨 `du -sb`를 기록한다.
   - `docker stats` 지연 등으로 주기를 초과하면 밀린 표본을 몰아 찍지 않고 건너뛴 횟수(`skipped_since_prev`, `total_skipped_ticks`)만 기록한다.
   - `CPUPerc` 원문(코어당 100%, 2 vCPU에서 최대 200%)과 `--host-vcpu`(기본 `2`)로 나눈 정규화 값을 함께 남기며, `mem_usage_cgroup_raw`가 프로세스 RSS가 아닌 Docker cgroup 표시값임을 명시하고 호스트 `mem_available_mib`를 주 지표로 기록한다.
   - 경고 임계값(`MemAvailable < 1,536 MiB`, `/` > 80%, `RestartCount` 증가)을 `warnings.log`와 CSV `warnings` 열에 기록하고, 기존 출력 디렉터리 덮어쓰기 금지(`OUTPUT_EXISTS`) 및 `--stop-file`·`SIGINT`/`SIGTERM` 정상 종료를 지원한다.
3. **[`deploy/perf/urls.example.tsv`](../../deploy/perf/urls.example.tsv)**: 5개 화면군(`home`, `list`, `filter`, `detail` 5건, `institution` 5건) 예시 매니페스트(실제 실행 전 대상 환경의 실존 코드·`sn`·기관으로 사본 작성 필요).
4. **[`docs/ops/performance-baseline.md`](../../docs/ops/performance-baseline.md)**: 실행 위치 분리 계약, preflight·헤더·집계·안전 중단 규칙, 설정·안전 상한 제안(목표/실측 아님 명시), 미실측·미완료 범위 문서화.
5. **로컬 대역 시험 및 기존 CI 연결**:
   - [`deploy/tests/perf-test.py`](../../deploy/tests/perf-test.py) (`100755`): 합성 HTTP 서버 대역으로 헤더·keep-alive 연결 재사용·그룹별 상한·nearest-rank math·`n<20` p95 생략·비200/타임아웃 분모·대문자 enum/무효 코드/페이지 클램핑/빈 `q`/금지 경로 거부·`sort=begin` 동건수 허용·`urls.example.tsv` 파싱·3종 안전 중단(5xx/타임아웃/30s 오류율)·`--stop-file`·원자료 덮어쓰기 거부를 검증(8개 테스트, 장시간 sleep 및 외부 네트워크 호출 없음).
   - [`deploy/tests/sample-resources-test.sh`](../../deploy/tests/sample-resources-test.sh) (`100755`): 합성 `/proc`·`docker`·`df`·`du` 대역으로 `bash -n`, monotonic 예약·초과 지연 tick 건너뜀·2 vCPU CPU 정규화·cgroup/호스트 메모리 분리·3종 경고(`WARN_MEM_AVAILABLE_LOW`, `WARN_DISK_USAGE_HIGH`, `WARN_CONTAINER_RESTART`)·덮어쓰기 거부·`--stop-file` 정상 종료를 검증(4단계, 실제 VM 측정 아님).
   - [`.github/workflows/ci.yml`](../../.github/workflows/ci.yml): 기존 `deploy-verify` 잡에 `bash -n`, `python3 -m py_compile`, `python3 deploy/tests/perf-test.py`, `./deploy/tests/sample-resources-test.sh`를 연결.

---

## 2. 검증 결과 (로컬 + GitHub Actions CI/Monitoring)

### 2.1 로컬 검증 (모두 합성 대역, 실제 VM·공개 사이트 미접근)
- **Windows Python (`python deploy/tests/perf-test.py -v`)**: **8/8 통과** (`Ran 8 tests in 4.629s — OK`).
- **Linux Docker (`python:3.12-slim`)**:
  - `bash -n deploy/deploy.sh deploy/g4-probe.sh deploy/tests/deploy-test.sh deploy/tests/caddy-integration-test.sh deploy/perf/sample-resources.sh deploy/tests/sample-resources-test.sh`: 통과
  - `python3 -m py_compile deploy/perf/perf-probe.py deploy/tests/perf-test.py`: 통과
  - `python3 deploy/tests/perf-test.py -v`: **8/8 통과** (`Ran 8 tests in 11.401s — OK`)
  - `./deploy/tests/sample-resources-test.sh`: **4/4 통과** (`ALL_SAMPLE_RESOURCES_TESTS_PASSED`)
  - `./deploy/tests/deploy-test.sh`: **17/17 통과** (`ALL_DEPLOY_TESTS_PASSED`)
- **위생 검증**: `git diff --check` 통과, 전 파일 LF/UTF-8(no BOM) 확인, `docs/ops/performance-baseline.md` 내부 상대 링크 5/5 정상 확인.

### 2.2 GitHub Actions (`ops/#18-performance-baseline`, 소스 커밋 `1ec43115797faf0f921243d510415937a3e12fb6`)
- **CI** (Run [`44717918689`](https://github.com/Lcjam/gongchae-bell/actions/runs/44717918689)) — **`success` (4/4 잡 성공)**:
  - `test` (`136225917601`): `success`
  - `deploy-verify` (`136225918028`, 신규 `perf-test.py` + `sample-resources-test.sh` 실행 포함): `success`
  - `backup-verify` (`136225918140`): `success`
  - `secrets` (`136225917823`, gitleaks): `success`
- **Monitoring** (Run [`44717918997`](https://github.com/Lcjam/gongchae-bell/actions/runs/44717918997)) — **`success` (3/3 잡 성공)**:
  - `rules` (`136225919940`): `success`
  - `compose-integration` (`136225919710`): `success`
  - `alert-e2e` (`136225920087`): `success`

> 참고: 이 결과 문서를 브랜치에 기록하는 후속 `docs:` 커밋은 소스 파일(`deploy/`, `.github/`, `docs/ops/`)을 변경하지 않습니다.

---

## 3. 한계 및 미완료 범위 (이번 작업 비범위)

- **미실측/미실시 항목** (문서 `docs/ops/performance-baseline.md`에도 동일하게 미완료로 표기):
  1. 실제 운영 공개 사이트 대상 평상시 무부하·제한 부하(`S1`·`S2`) 실측
  2. `COLLECTOR_ENABLED=true` 전환 및 선행 경보 게이트 통과 후 정상 `:07` 슬롯 수집 중 구간 실측
  3. 다음 정상 승인 배포에 `sample-resources.sh`를 붙인 `blue/green` JVM 중첩 자원 실측
  4. 실패 유도 `G4_FAIL → abort` 훈련 및 포스트모템(`docs/incidents/`)
  5. 실측 근거 기반 초기 성능 목표·메모리 한도 수치 확정
- **비접근 원칙 준수**: 실제 서버·SSH·계정·`.env`·비밀값·외부 API/Discord/Resend·`workflow_dispatch`·PR #30(`issue-16-calendar-retention`) 및 타 작성자 브랜치(32/33/17)에는 일절 접근하거나 변경하지 않았다.
- **합성 대역 검증 한계**: `perf-test.py`와 `sample-resources-test.sh`는 로컬 HTTP 대역 및 합성 `/proc`·`docker` 출력 기반 검증이며, 실제 OCI ARM64 VM의 커널 `/proc`, Docker cgroup v2, Caddy TLS, PostgreSQL 17 쿼리 계획을 대체하지 않는다.
