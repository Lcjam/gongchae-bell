# 수집 경보 훈련안 B1~B4 수정 및 실행 하네스 결과 (`agy`)

- **작성자**: `agy` (단독 작성자)
- **요청 및 검수 근거**: `collab/2026-11-19-resume/agy-live-alert-fix-request.md`, 원안 `collab/2026-11-19-resume/agy-live-collector-alert-plan.md`(보존), 독립 검수 `collab/reviews/collector-live-alert-plan-claude.md`(보존)
- **작업 위치 / 브랜치**: `C:/Users/lcj/orca/workspaces/my-project/issue-15-collector-drill` / `ops/#15-collector-drill`
- **기준 `origin/main` SHA**: `eb903017c4fb256a8ece665da795d7a94f6aeb25` (`Merge pull request #31 from Lcjam/fix/#7-metric-commit`)
- **구현 커밋 SHA**: `38e0b65c5c6e689df76ada7478c1ef0dcd5521d4` + `6aa5ae60fc9410d0294157d3d52a5f4853488def` (`MonitoringContractIntegrationTest` 지표 정규식 충돌 회피를 위해 `collector-alert-drill.sh` 지역 변수명 정리)
- **Draft PR**: [#33](https://github.com/Lcjam/gongchae-bell/pull/33) (`ops/#15-collector-drill`)

---

## 1. 반영한 산출물 및 B1~B4 해결 요약

1. **[`collab/2026-11-19-resume/agy-live-collector-alert-plan-round2.md`](agy-live-collector-alert-plan-round2.md)** (원안·검수 파일 무수정 보존, 2차 계획 별도 작성)
   - 최신 운영 기록(`claude-main32-deploy-result.md`: 활성 `green` `sha-32e71e4`, `posting` 36,879, `api_attempt` 37, 수집/알림 `false`)으로 사실 갱신하되, 고정 색(`app-blue`)·고정 일자(`37`) 가정 없이 매 실행 전후 실측 비교로 전환했습니다.
   - 훈련 중 배포 금지, Prometheus TSDB 1년 보존에 남는 스파이크의 시각 기록, `fix/#14-monitoring-bind` 영구 mount 변경 미복제 경계를 명시했습니다.
2. **[`deploy/monitoring/collector-alert-drill.sh`](../../deploy/monitoring/collector-alert-drill.sh)** (`preflight` / `run` / `restore`)
   - **B1 (실제 mount/inode 사전 검사)**: 실행 직전 `docker inspect` `Mounts`로 `single-file` vs `directory` bind mount를 자동 판별합니다. 현재 단일 파일 mount에서는 host/container `sha256sum`과 Linux `stat -c %i` inode를 비교해 `tar` 동기화로 끊긴 mount를 `MOUNT_STALE_SINGLE_FILE`로 fail-closed 차단하며, `--allow-recreate-prometheus` 명시 시에만 재생성 후 재검증합니다. `fix/#14-monitoring-bind` 적용 후 디렉터리 mount에서도 `MOUNT_OK:directory`로 그대로 동작합니다. `SIGHUP` 반영·원복 확인 시 `/api/v1/targets?state=active`와 `/api/v1/status/config`를 함께 검사합니다.
   - **B2 (`gongchae-drill` job 분리)**: `job_name: gongchae-app`은 1바이트도 바꾸지 않고 별도 `job_name: gongchae-drill`을 추가해 훈련 중 실제 앱이 내려가도 `GongchaeAppMetricsDown`이 가려지지 않게 했습니다.
   - **B3 (컨테이너 env DB 스냅샷 & 일자 무관 비교)**: `.env`를 `source`하지 않고 `COLLECTOR_ENABLED`·`ALERTS_ENABLED`만 파싱하며, Postgres는 `docker exec "$PG_CONTAINER" sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" ...'`로 `posting`(건수+md5)·`collect_run`·`api_attempt`(건수+max id)·`api_budget`(전 일자) 전후 동일성을 비교합니다.
   - **B4 (SSH 단절·중간 실패·시간 상한 자동 원복 및 비은폐 판정)**: `nohup setsid` 분리 실행, `trap on_signal INT TERM HUP` + `trap on_exit EXIT`, 25분(`1500s`) 하드 워치독, exporter `timeout 1560`, `[훈련] ` label-only 변경(정확히 2줄만 수정·모든 `expr:`/`for: 10m` 바이트 동일 검증 + `promtool check config`), `orig.sha256` + `--expected-*-sha256`(Git `main` blob) + 컨테이너 내부 SHA-256 일치 검증을 넣었습니다. 복원 항목 중 하나라도 실패하면 `DRILL_RESTORE_FAILED` + `state=RESTORE_FAILED` + 비정상 종료로 실패를 숨기지 않으며, 복구 전 다음 `preflight`/`run`을 차단합니다.
3. **[`deploy/monitoring/prometheus/tests/gongchae.test.yml`](../../deploy/monitoring/prometheus/tests/gongchae.test.yml)**
   - B2 회귀 케이스 추가: `job="gongchae-drill"`이 `up=1`(`500`, `2`)인 상태에서 `job="gongchae-app"`(`app-blue`/`app-green`)이 모두 `up=0`으로 내려가도 5분 뒤 `GongchaeAppMetricsDown`이 정상 발동하며, 원본 `GongchaeApiBudgetWarning`(즉시)·`GongchaeRegularCollectionFailing`(원본 `for: 10m` 준수: 6m·10m 미발동 → 11m 발동)과 해제까지 검증합니다.
4. **[`deploy/monitoring/tests/collector-alert-drill-test.sh`](../../deploy/monitoring/tests/collector-alert-drill-test.sh)** 및 **[`.github/workflows/monitoring.yml`](../../.github/workflows/monitoring.yml)**
   - 격리 Docker 스택(`postgres:17` + `caddy:2-alpine` stand-in `app-green` + `prom/prometheus:v3.16.0`, 원본 `prometheus.yml`·`rules/gongchae.yml` 무수정)에서 preflight fail-closed(플래그·Git SHA·Linux `tar` stale inode), 디렉터리 mount 호환, 실제 `GongchaeApiBudgetWarning` `firing` + `GongchaeRegularCollectionFailing` `pending` 진입 뒤 중간 `SIGTERM` 자동 원복, 원복 실패 비은폐 및 멱등 재복구를 검증하고 `Monitoring / compose-integration` CI에 연결했습니다.

---

## 2. 로컬 격리 검증 결과 및 한계

- **로컬 실행 검증**:
  - `promtool check config prometheus.yml` (`SUCCESS: 1 rule files found`) 및 `promtool test rules gongchae.test.yml` (`SUCCESS`, 신규 B2 비가림·`for: 10m` 회귀 포함).
  - `bash -n deploy/monitoring/collector-alert-drill.sh deploy/monitoring/tests/collector-alert-drill-test.sh` 통과.
  - `deploy/monitoring/tests/collector-alert-drill-test.sh` 로컬 Docker 실행 통과 (`COLLECTOR_ALERT_DRILL_TEST_PASSED`):
    - `COLLECTOR_ENABLED=true`·Git SHA 불일치 fail-closed 확인
    - 단일 파일 mount(`MOUNT_OK:single-file`) 및 디렉터리 mount(`MOUNT_OK:directory`) preflight 통과
    - 원본 `prometheus.yml`/`rules/gongchae.yml`(`for: 10m`, `30s` scrape/eval)로 `DRILL_BUDGET_WARNING_FIRING` + `DRILL_COLLECTION_FAILING_PENDING` 확인 뒤 중간 `SIGTERM` 주입 → `DRILL_RESTORE_VERIFIED_OK`, host/container SHA-256 일치, `drill-exporter` 제거, `gongchae-drill` config/target 제거, alerts `0`, DB/플래그/컨테이너 일치 확인
    - 의도적 SHA 불일치 원복 시 `DRILL_RESTORE_FAILED` + 비정상 종료 + `state=RESTORE_FAILED` + 후속 `preflight` 차단(`PREVIOUS_DRILL_NOT_RESTORED`) + 정상 SHA 멱등 재복구(`DRILL_RESTORE_VERIFIED_OK`) 확인
  - `MonitoringContractIntegrationTest` 로컬 통과 (`BUILD SUCCESSFUL in 36s`, `deploy/monitoring/collector-alert-drill.sh`의 매칭 지표가 `collector_api_attempts_today`·`collector_regular_incomplete_consecutive` 2건뿐임을 확인).
  - `gitleaks dir --verbose` (`no leaks found`).
- **원격 CI 및 한계**:
  - Windows 로컬 Docker Desktop에서는 `uname -s`가 Linux가 아니므로 Linux `tar` stale-inode 분기(`MOUNT_STALE_SINGLE_FILE`)는 원격 GitHub Actions `ubuntu-24.04-arm` (`Monitoring / compose-integration`)에서 실실행으로 검증합니다(PR 본문에 최종 CI run URL 기록).
  - `collector-alert-drill-test.sh`는 CI 실행 시간을 지키기 위해 `GongchaeRegularCollectionFailing` `pending` 진입 뒤 `SIGTERM` 중단·원복을 검증하며, `for: 10m` 전체 경과 평가는 `promtool test rules gongchae.test.yml`로 검증하고 실제 10분 실운전+Grafana→Discord 수신은 검수 후 별도 운영 실행 라운드에서 수행합니다.
  - 운영 SSH·계정·`.env`·키·실제 Discord/Resend·운영 DB·공개 앱·`fix/#7-metric-commit`·`fix/#14-monitoring-bind`는 접근/변경하지 않았습니다.
