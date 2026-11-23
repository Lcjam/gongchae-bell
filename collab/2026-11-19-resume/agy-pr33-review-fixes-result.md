# PR #33 비작성자 2검수(Codex · Sol) 수정 결과 (`agy`)

- **작성자**: `agy` (단독 작성자)
- **요청 및 검수 근거**: `collab/2026-11-19-resume/agy-pr33-review-fixes-request.md`, `collab/reviews/pr-33-codex.md`, `collab/reviews/pr-33-sol.md` (원안·기존 보고 및 타 작성자 브랜치 무수정 보존)
- **작업 위치 / 브랜치**: `C:/Users/lcj/orca/workspaces/my-project/issue-15-collector-drill` / `ops/#15-collector-drill`
- **기준 `origin/main` SHA**: `eb903017c4fb256a8ece665da795d7a94f6aeb25`
- **기존 PR #33 변수명 보완 SHA (보존)**: `6aa5ae60fc9410d0294157d3d52a5f4853488def` (`1a339a606a156d8b064589b2a3a3dc6e14b1c883`까지)
- **이번 R1~R5 수정 커밋 SHA**: `3cb5eab1b67a59dc9bf57eaa9a2dc8a338be500a` (`fix: harden collector drill schema, locking, supervisor handoff, and runtime flags (Refs #15)`)
- **Draft PR**: [#33](https://github.com/Lcjam/gongchae-bell/pull/33) (Draft 유지, `#15` 미종료)

---

## 1. 항목별 수정 내역 (R1~R5)

1. **R1 — 실제 Flyway 스키마 정합 (`collector-alert-drill.sh` `capture_db_snapshot`, `collector-alert-drill-test.sh`)**
   - `posting`에 없는 `updated_at` 조회를 제거하고, 실제 `V1__catalog_and_ingestion.sql`·`V3__collector_runtime_records.sql` 컬럼(`posting.sn`, `status`, `content_hash`, `first_seen_at`, `last_seen_at`, `last_changed_at`, `detail_status`, `detail_collected_at`, `collect_run` 건수+`max(id)`, `api_attempt` 건수+`max(id)`, `api_budget` 전 일자)으로 지문 SQL을 교체했습니다.
   - 시험의 가짜 `CREATE TABLE posting (... updated_at)`를 제거하고, 저장소의 실제 tracked Flyway 마이그레이션 `src/main/resources/db/migration/V1..V5*.sql`을 순서대로 적용한 격리 `postgres:17`에서 DB 기준선 및 `posting.last_seen_at` 변경 탐지(`DB baseline changed during drill` → `DRILL_RESTORE_FAILED`)를 검증했습니다.

2. **R2 — 독립 감독자(`Supervisor`)·워커 분리, 명령 상한, 반복 신호 마스킹 (`collector-alert-drill.sh`)**
   - `run`(감독자)이 배포 루트 잠금과 설정 변경/원복 소유권을 독점하고, 잠금 FD(`9>&-`)를 닫은 자식 워커(`_worker`)만 지표 주입·폴링 대기를 수행하도록 분리했습니다.
   - 모든 외부 `docker`/`psql`/`wget` 호출에 명시적 실행 상한(`timeout 20`, `wget -T 10`)을 적용했습니다.
   - 25분 마감(`MAX_DURATION_SEC`)·워커 종료·외부 신호(`INT`/`TERM`/`HUP`) 도달 시 감독자가 즉시 추가 신호를 마스킹(`trap '' INT TERM HUP`)한 뒤 `${WORK_DIR}/revoked` 마커 기록 → 워커 및 하위 프로세스를 `TERM` → 5초 후 `KILL` + `wait`로 완전 종료시키고(늦게 깨어난 재변경 차단), 그 뒤에 직접 `restore_and_verify`를 실행합니다.
   - `RESTORE_DONE=1`은 모든 복구·검증이 `rrc=0`으로 완료된 뒤에만 기록합니다.
   - **한계 명시**: Docker 데몬 불능·호스트 전원 단절·감독자 자체 `SIGKILL` 시 완료를 약속하지 않으며, 디스크 `STATE=ARMED`/`RESTORE_FAILED`로 남아 다음 `preflight`/`run`이 fail-closed로 차단되고 수동 `restore`로 안전 복구합니다.

3. **R3 — 배포 루트 배타 잠금, 소유 해시 사전 검증, 이미 복구된 상태 No-Op (`collector-alert-drill.sh`, `agy-live-collector-alert-plan-round2.md`)**
   - 배포 루트 공통 잠금 `${ROOT_DIR}/deploy/state/drill-runtime.lock`(Linux 커널 `flock`, Windows 로컬 개발 환경 원자적 lockdir 폴백)과 `${ROOT_DIR}/deploy/state/drill-runtime.owner`로 동일 루트의 `preflight`·`run`·`restore`를 직렬화했습니다(`--backup-dir`가 달라도 동시 `run`은 `DRILL_LOCK_BUSY`로 차단, `run` 중 `restore`는 `flock -w`로 직렬화).
   - `STATE=RESTORED`에서 과거 `restore`를 재실행하면 파일을 덮어쓰지 않고 읽기 전용 검증 후 `DRILL_RESTORE_ALREADY_RESTORED_NOOP`으로 종료합니다.
   - `STATE=ARMED`/`RESTORE_FAILED` 원복 시에도 현재 파일 해시가 이번 실행의 `EXPECTED_*_SHA256` 또는 `DRILL_*_SHA256`이 아니면 쓰기 전에 즉시 중단(`UNEXPECTED_EXTERNAL_CHANGE`)해 외부 정상 변경을 보존합니다.
   - `deploy.yml`의 `tar` 동기화가 `deploy.sh`의 `deploy.lock` 획득 전에 실행되므로 잠금만으로 외부 `tar` 동기화를 막지 못함을 계획서에 명시하고, 위 소유 해시 사전 감지로 덮어쓰기를 방지합니다.

4. **R4 — `.env` `export` 구문 + 실행 중 앱 컨테이너 allowlist 런타임 플래그 검증 (`collector-alert-drill.sh`)**
   - `.env` 파서가 `export COLLECTOR_ENABLED=true`와 따옴표·잘못된 확장 구문(`${`, `$(`, backtick)을 정확히 처리/거부하도록 고쳤고, `verify_all_flags_false`가 `||` 문맥에서도 Python 실패 코드를 누락하지 않도록 명시적 `|| return 1`을 추가했습니다.
   - 실행 중인 모든 `app-blue`/`app-green` 컨테이너(또는 `--app-containers`)의 `.Config.Env`에서 **`COLLECTOR_ENABLED`·`ALERTS_ENABLED` 2개 키만 allowlist로 메모리 판독**(비밀값·전체 env 미출력)해 파일과 런타임 모두 `false`임을 사전·사후 검증합니다.

5. **R5 — 필수 Git 원본 해시 강제 및 소유 자원 한정 정리 (`collector-alert-drill.sh`)**
   - `preflight`·`run`에서 64자리 hex `--expected-prom-sha256`·`--expected-rules-sha256` 둘 다 필수로 요구하며 누락/형식 오류/불일치 시 mutation 전 거부합니다(`EXPECTED_SHA_REQUIRED`). `run`이 `meta.env`에 저장한 동일 기준을 `restore`에서 재검증합니다.
   - `rm -f "${RULES_DIR}"/drill*.yml` glob 삭제를 제거해 본 실행이 만든 `${EXPORTER_NAME}`과 `${WORK_DIR}`만 정리하고 기존 운영자 규칙(`rules/drill-keep.yml`)을 보존합니다.

---

## 2. 직접 실행 검증 결과 및 미실행/한계

- **로컬 직접 실행 검증**:
  - `bash -n deploy/monitoring/collector-alert-drill.sh deploy/monitoring/tests/collector-alert-drill-test.sh` 통과.
  - `promtool check config prometheus.yml` (`SUCCESS`) 및 `promtool test rules gongchae.test.yml` (`SUCCESS`, B2 `GongchaeAppMetricsDown` 비가림 + 원본 `for: 10m` 회귀 유지).
  - `gradle test --rerun --tests kr.gongchae.bell.ops.MonitoringContractIntegrationTest`: `BUILD SUCCESSFUL in 22s` (지표 정규식 충돌 0건).
  - `gitleaks dir --verbose deploy/monitoring collab/2026-11-19-resume/agy-live-collector-alert-plan-round2.md`: `no leaks found`.
  - **`deploy/monitoring/tests/collector-alert-drill-test.sh`** 전체 통과 (`COLLECTOR_ALERT_DRILL_TEST_PASSED`):
    - 실제 tracked Flyway `V1..V5` 적용 `postgres:17`에서 기준선 통과 + `posting.last_seen_at` 변경 시 `DB baseline changed during drill`·`DRILL_RESTORE_FAILED` 탐지 및 원복 후 멱등 재복구 확인.
    - `--expected-*-sha256` 누락(`EXPECTED_SHA_REQUIRED`), `export COLLECTOR_ENABLED=true`(`ENV_FILE_FLAG_NOT_FALSE`), `.env` `false` vs 실행 앱 `true`(`RUNTIME_FLAG_NOT_FALSE`), 단일 파일/디렉터리 mount preflight 확인.
    - 실제 `GongchaeApiBudgetWarning` `firing` + `GongchaeRegularCollectionFailing` `pending`(원본 `for: 10m`) 중 다른 `--backup-dir` 동시 `run #2` 차단(`DRILL_LOCK_BUSY`), 동시 `restore` 직렬화(`DRILL_LOCK_BUSY` 및 종료 후 `DRILL_RESTORE_ALREADY_RESTORED_NOOP`), 1차 `SIGTERM` + **복구 중 2차 `SIGTERM` 무시 완료**(`DRILL_RESTORE_VERIFIED_OK`), 기존 `rules/drill-keep.yml` 바이트 동일 보존 확인.
    - 워커가 전경 `sleep 120`에서 정체돼도 감독자가 40s 마감(`DRILL_SUPERVISOR_DEADLINE_REACHED`)에서 독립 종료·인계·원복 완료(`exit 124`, 총 `55s < 120s`).
    - `STATE=RESTORED` 뒤 신규 정상 `prometheus.yml` 변경에 과거 `restore` 재실행 시 `DRILL_RESTORE_ALREADY_RESTORED_NOOP`(신규 설정 보존), `STATE=ARMED` 중 외부 `prometheus.yml` 변경 시 `UNEXPECTED_EXTERNAL_CHANGE`(덮어쓰기 없이 실패 종료) 확인.
- **미실행 및 한계**:
  - 실제 운영 SSH·계정·`.env`·키·운영 DB·실제 Discord/Resend·공개 앱에는 접근/변경하지 않았습니다.
  - Linux `flock` 및 `tar` stale-inode 분기(`MOUNT_STALE_SINGLE_FILE`)는 원격 GitHub Actions `ubuntu-24.04-arm` (`Monitoring / compose-integration`)에서 실실행되며 최종 CI run URL은 PR #33 본문에 기록합니다.
  - 실제 10분 실운전 + Grafana→운영 Discord 채널 수신 확인은 본 PR 검수·병합 뒤 별도 운영 실행 배정에서 수행합니다.
