# 수집 운영 경보 실제 규칙(`예산 500 경고` · `연속 미완료`) 훈련 2차 계획서 · agy

- **작성자 / 일자**: agy / 2026-11-20
- **문서 위치 / 관계**: 기존 원안([`agy-live-collector-alert-plan.md`](agy-live-collector-alert-plan.md))과 비작성자 독립 검수([`collector-live-alert-plan-claude.md`](../reviews/collector-live-alert-plan-claude.md))를 보존한 상태에서 **차단 4건(B1~B4)과 비차단 운영 상태 갱신을 모두 반영한 2차 실행 계획**이다.
- **실행 하네스**: [`deploy/monitoring/collector-alert-drill.sh`](../../deploy/monitoring/collector-alert-drill.sh) (단독 실행·사전 점검·원복 겸용)
- **검증 회귀**:
  - [`deploy/monitoring/prometheus/tests/gongchae.test.yml`](../../deploy/monitoring/prometheus/tests/gongchae.test.yml) (B2 비가림·원본 `for: 10m` `promtool test rules`)
  - [`deploy/monitoring/tests/collector-alert-drill-test.sh`](../../deploy/monitoring/tests/collector-alert-drill-test.sh) (격리 Docker 통합 시험)
- **실행 범위**: 본 브랜치(`ops/#15-collector-drill`)는 계획·하네스·로컬/CI 격리 대역 검증까지이며, **실제 운영 서버 SSH·DB·env·키·Discord·Resend·공개 앱에는 접근/변경하지 않았다.**

---

## 1. 최신 운영 기준선 및 불변 제약 (2026-11-20 기준)

- **최신 기록상 운영 상태** (`claude-main32-deploy-result.md`, `origin/main` `eb903017`):
  - 활성 색 **`green`** (`sha-32e71e4…`, `app-green:8081` up / `app-blue` down), 이전 색 `blue` (`836a3c0`), 컨테이너 5개(`grafana`, `prometheus`, `caddy`, `app-green`, `postgres`), Flyway V5, `posting` 36,879건, `api_attempt` 37건, `COLLECTOR_ENABLED=false`, `ALERTS_ENABLED=false`.
  - **고정값 가정 금지 (B3·비차단 1 반영)**: 실제 실행 시 `app-blue`나 `api_budget(오늘)=37`을 가정하지 않는다. 하네스가 실행 직전 `prometheus` targets의 실제 활성 `gongchae-app` 대상과 DB 스냅샷(`posting` 건수+md5, `collect_run`, `api_attempt`, `api_budget` 전 일자)을 직접 읽어 **실행 전후 동일성**으로 판정한다.
- **보존할 실제 규칙 (`deploy/monitoring/prometheus/rules/gongchae.yml`)**:
  - `GongchaeApiBudgetWarning`: `expr: max(collector_api_attempts_today) >= max(collector_api_warning_limit)`, `for` 없음, `severity: warning`
  - `GongchaeRegularCollectionFailing`: `expr: max(collector_regular_incomplete_consecutive) >= 2`, **`for: 10m`**, `severity: critical`
  - **임계값(`500`, `2`)·`expr`·`for: 10m`·스크랩/평가 주기(`30s`)를 절대 줄이거나 바꾸지 않는다.**
- **훈련 중 배포 금지 및 동기화 경계 (비차단 3 · PR33 R3 반영)**:
  - `deploy.yml`의 `tar` 동기화는 `deploy.sh`가 `deploy.lock`을 잡기 **전에** 실행되므로, 하네스 잠금만으로 외부 `tar` 동기화를 막았다고 간주하지 않는다. 따라서 훈련 창 동안 자동/수동 배포를 금지하며, 설령 외부 배포나 운영자 수정이 끼어들더라도 원복 직전 **소유 해시 검증(`EXPECTED_*_SHA256` 또는 `DRILL_*_SHA256` 일치 시에만 쓰기)**으로 새로운 정상 설정을 덮어쓰지 않고 보존한 채 `UNEXPECTED_EXTERNAL_CHANGE`로 중단한다.

---

## 2. 검수 차단 4건(B1~B4) 해결 설계

### B1. 실제 bind mount 유형 및 stale inode 사전 검사 (현재/미래 mount 모두 호환)
- **문제**: 현재 `compose.monitoring.yml`은 `prometheus.yml`을 단일 파일 bind mount로 붙인다. `deploy.yml`의 `tar -xf` 동기화 이후에는 host 파일의 inode가 바뀌어, 컨테이너 안 Prometheus가 옛 inode를 계속 보게 된다(이 상태에서는 `cat > prometheus.yml` + `SIGHUP`이 반영되지 않는다). 한편 별도 브랜치(`fix/#14-monitoring-bind`, primary Claude)가 영구 디렉터리 mount 전환을 진행 중이며, 본 브랜치는 그 변경을 복제하지 않는다.
- **해결 (`collector-alert-drill.sh` `inspect_prom_mounts`)**:
  1. `docker inspect`의 `Mounts`를 실행 직전 검사해 `/etc/prometheus/prometheus.yml`(**`single-file`**) vs `/etc/prometheus`(**`directory`**) mount를 자동 판별한다.
  2. **현재 `single-file` mount**: host와 Prometheus 컨테이너 내부의 `sha256sum` **및** `stat -c %i` inode를 비교한다.
     - 불일치 시 `preflight`와 기본 `run`은 즉시 **`MOUNT_STALE_SINGLE_FILE`로 fail-closed 중단**한다.
     - 운영자가 명시적으로 `--allow-recreate-prometheus` 옵션을 주었을 때만 `deploy/deploy.sh monitoring up -d --force-recreate prometheus`로 mount를 현재 파일에 다시 묶은 뒤(`prometheus_data` 볼륨 보존) `/-/ready`와 inode·SHA-256 일치를 재검증하고 진행한다.
     - *유의*: 재생성 수 초 동안 Grafana 전달 규칙(`execErrState: Error`)이 평가에 걸리면 `DatasourceError`가 1회 전송될 수 있으므로 실행 로그와 `monitoring.md` 기록에 함께 남긴다.
  3. **신규 `directory` mount (`fix/#14-monitoring-bind` 적용 후)**: `MOUNT_OK:directory`로 판별하며 host/container SHA-256 일치만 확인하고 재생성 없이 진행한다.
  4. 두 경우 모두 `cat ... >` **in-place 쓰기 + `SIGHUP`**을 사용하며, 반영·원복 판정은 `/api/v1/targets?state=active`뿐 아니라 **`/api/v1/status/config`**까지 함께 대조한다.

### B2. 별도 `job_name: gongchae-drill` 분리 (`GongchaeAppMetricsDown` 비가림)
- **문제**: `drill-exporter:8081`을 기존 `gongchae-app` job의 targets에 추가하면, 훈련 중 실제 `app-blue`/`app-green`이 모두 내려가도 `max(up{job="gongchae-app"})`가 `1`로 유지되어 critical 경보 `GongchaeAppMetricsDown`이 가려진다.
- **해결**:
  - 기존 `job_name: gongchae-app` 블록은 1바이트도 수정하지 않는다.
  - `prometheus.yml` 끝에 별도 job을 덧붙인다:
    ```yaml
      - job_name: gongchae-drill
        metrics_path: /actuator/prometheus
        static_configs:
          - targets: ["drill-exporter:8081"]
    ```
  - 두 수집 경보(`GongchaeApiBudgetWarning`, `GongchaeRegularCollectionFailing`)는 job 필터 없이 `max(...)`를 집계하므로 정상 발동하고, `GongchaeAppMetricsDown`·`GongchaeHttp5xxHigh`·M2 `job="gongchae-app"` 경보와 job 필터 대시보드 패널은 전혀 가려지지 않는다.
  - **지표 이력 해석 메모 (비차단 4)**: Prometheus TSDB 보존(1년) 때문에 훈련 창의 `500`·`2` 스파이크가 job 필터 없는 수집 패널에 남는다. 별도 `job="gongchae-drill"` 라벨로 구분 가능하며, 실제 실행 후 `docs/ops/monitoring.md` 수신 표에 훈련 시작/종료 시각을 남긴다.

### B3. 컨테이너 env 기반 DB 스냅샷(실제 Flyway 스키마) & allowlist 런타임 플래그 검증 (R1 · R4 반영)
- **문제**: `docker compose -f compose.prod.yml --env-file .env exec postgres`는 빈 `APP_BLUE_TAG`/`APP_GREEN_TAG` 보간 실패로 깨지고 셸에 `$DB_USER`도 없다. `api_budget = 37`은 `2026-11-19` 행이므로 날짜가 바뀌면 판정이 깨진다. 또한 `posting`에는 `updated_at` 컬럼이 없고(`first_seen_at`/`last_seen_at`/`last_changed_at` 보유), `.env`의 `export KEY=true`나 파일(`false`)과 실제 실행 중 앱 컨테이너(`true`)의 불일치도 차단해야 한다.
- **해결**:
  - `.env`는 절대 `source`하지 않고, `export ` 접두어·따옴표·잘못된 구문을 검증하는 전용 파서로 `COLLECTOR_ENABLED`·`ALERTS_ENABLED`만 읽는다(비밀값 출력 0).
  - 실행 중인 모든 `app-blue`/`app-green` 컨테이너에서 **allowlist 2개 키(`COLLECTOR_ENABLED`, `ALERTS_ENABLED`)만** 메모리로 읽어 파일과 런타임 모두 `false`임을 사전·사후 검증한다(불일치·누락·`true`면 즉시 중단).
  - Postgres는 실행 중인 컨테이너 내부 env로 직접 조회한다:
    ```bash
    docker exec "$PG_CONTAINER" sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1 -qtAc "..."'
    ```
  - 전후 비교 대상 4종(`diff -u db_before.txt db_after.txt` == 빈 출력, 실제 Flyway `V1`·`V3` 컬럼만 사용):
    1. `posting` 총 건수 및 `sn:status:content_hash:first_seen_at:last_seen_at:last_changed_at:detail_status:detail_collected_at` 정렬 md5 지문
    2. `collect_run` 총 건수 및 `max(id)`
    3. `api_attempt` 총 건수 및 `max(id)`
    4. `api_budget` 전체 `day=count` 목록
  - 추가로 실행 중인 서비스 컨테이너 목록(`이름|이미지|ID`)도 전후 `diff`로 보존을 확인한다.

### B4. 배포 루트 배타 잠금 · 독립 감독자 원복 · 소유 자원 한정 정리 · 필수 Git 해시 (R2 · R3 · R5 반영)
- **필수 Git 원본 해시 (R5)**:
  - `preflight`와 `run`은 `--expected-prom-sha256`·`--expected-rules-sha256`(64자리 소문자 hex) 둘 다 **필수**이며, 누락·형식 오류·현장 파일 불일치 시 즉시 중단한다. `run`은 해당 기준과 스테이징된 `DRILL_*_SHA256`을 `meta.env`에 저장하고 `restore`도 동일 기준으로 검증한다.
- **배포 루트 공통 배타 잠금 & 소유권 보호 (R3)**:
  - `${ROOT_DIR}/deploy/state/drill-runtime.lock`(`flock`, Windows 개발 환경은 원자적 lockdir 폴백)과 `drill-runtime.owner`로 동일 배포 루트의 `preflight`·`run`·`restore`를 직렬화한다(`--backup-dir`가 달라도 동시 `run`은 `DRILL_LOCK_BUSY`로 차단).
  - `restore` 시 `STATE=RESTORED`이면 설정 파일을 다시 쓰지 않고 읽기 전용 검증 후 `DRILL_RESTORE_ALREADY_RESTORED_NOOP`으로 종료한다.
  - `STATE=ARMED`/`RESTORE_FAILED` 원복 시에도 현재 파일 해시가 **이번 실행의 원본 또는 훈련 해시**가 아니면 절대 덮어쓰지 않고 `UNEXPECTED_EXTERNAL_CHANGE`로 중단해 외부 변경을 보존한다.
  - 본 하네스는 `rules/`에 새 파일을 만들지 않으므로 **`rules/drill*.yml` glob 삭제를 하지 않으며**, `meta.env`에 기록된 소유 `EXPORTER_NAME`과 `WORK_DIR`만 정리한다.
- **훈련 표식 (1안 채택 + 안전장치)**:
  - `rules/gongchae.yml`의 `GongchaeApiBudgetWarning`과 `GongchaeRegularCollectionFailing` 2개 규칙의 **`summary:` label에만** `"[훈련] "` 접두어를 붙인다(큰따옴표 감싸기 + 정확히 2줄 변경 + 모든 `expr:`/`for: 10m` 바이트 동일 검증 + `promtool check config`).
- **독립 감독자(`Supervisor`) · 워커 분리 · 강제 상한(`timeout -k`) · 수동 복구 워커 펜싱 (R2 · R3 2차 보완)**:
  - 실제 실행은 SSH 세션과 분리해 기동한다(`nohup setsid bash ... >~/gongchae-ops/collector-drill.log 2>&1 < /dev/null &`).
  - **감독자(`run`)**가 배포 루트 잠금과 설정 변경/원복 소유권을 독점하고, 잠금 FD(`9>&-`)를 닫은 **자식 워커(`_worker`)**만 시도별 격리 디렉터리(`${WORK_DIR}/runs/${RUN_ID}`)에 지표를 주입한다.
  - 모든 외부 `docker`/`psql`/`wget` 명령 및 opt-in `--allow-recreate-prometheus` 재생성 명령은 `run_bounded`를 거친다. `run_bounded`는 복구 중 신호 마스킹(`trap '' INT TERM HUP`)이 자식에게 상속되지 않도록 서브셸에서 `INT`/`TERM`/`HUP`을 기본값으로 복원한 뒤 GNU `timeout -k <grace> <secs>`로 실행해, `SIGTERM`을 무시하는 자식·후손도 제한 시간 내 `SIGKILL`로 강제 종료한다.
  - 워커 정체·비정상 종료·25분(`1500s`) 마감 도달·신호(`INT`/`TERM`/`HUP`) 수신 시 감독자는 즉시 추가 신호를 마스킹한 뒤 `RUN_ID`별 취소 마커 기록 → 프로세스 정체성(`/proc/<pid>/cmdline`의 `_worker --run-id <RUN_ID>` + `/proc/<pid>/stat` 시작 시각 `starttime`)이 검증된 워커만 `TERM`→5초 후 `KILL`로 종료하고 전용 워커 lease lock(`worker-${RUN_ID}.lock`, Linux `flock`) 해제를 확인한 뒤 `restore_and_verify`를 수행한다.
  - **감독자 `SIGKILL` 이후 수동 `restore` 계약**: `meta.env`에 `RUN_ID`·`ATTEMPT_DIR`·`WORKER_PID`·`WORKER_START_TIME`·`WORKER_LOCK_FILE`·`WORKER_REVOKED_FILE`을 기록한다. 수동 `restore`는 동일 정체성 검증으로 살아 있는 이전 워커만 안전 종료하며(PID 재사용으로 무관 프로세스를 절대 종료하지 않음), 워커 lease가 여전히 점유돼 종료를 확인할 수 없으면 `UNVERIFIED_WORKER_ALIVE`로 fail-closed(`STATE=RESTORE_FAILED`)해 새 실행의 자원 재사용을 차단한다. 워커의 `write_metrics` 역시 시도 디렉터리 `run_id`·취소 마커·`OWNER_FILE` 소유 상태·감독자 생존을 검증하므로 이전 워커가 같은 기본 `--work-dir`의 새 실행 지표를 덮어쓸 수 없다.
  - **TSDB 0/0 flush 및 SIGHUP 이후 재평가 검증**: `restore_and_verify`는 exporter와 `gongchae-drill` job을 제거하기 전에 살아 있는 exporter로 `0/0`을 기록하고 Prometheus가 `0/0`을 스크랩해 TSDB에 반영한 뒤 config/rules를 원복·`SIGHUP`하며, `SIGHUP` 이후 `gongchae` rule group의 실제 새 평가 완료(`lastEvaluation` 갱신)와 `job="gongchae-drill"` 비영(non-zero) 시계열 부재 및 경보 `0`건을 확인한 뒤에만 `RESTORED`/`RESTORE_DONE=1`을 기록한다.
  - **물리적 한계 구분 (보존)**: Docker 데몬 불능, 호스트 전원 단절, 감독자 자체에 대한 `SIGKILL` 시 자동 복구 완료를 보장하지 않으며, 디스크에 `STATE=ARMED`/`RESTORE_FAILED`가 남아 다음 `preflight`/`run`이 fail-closed로 차단되고 수동 `restore`로 이전 워커를 차단·종료한 뒤 안전 복구한다.
- **원복 오류 비은폐**:
  - `restore_and_verify`의 검증 항목(호스트/컨테이너/Git SHA-256 일치, exporter 제거, Prometheus config/targets/alerts 정리, 활성 `gongchae-app` `up`, DB·파일+런타임 플래그·컨테이너 일치) 중 하나라도 실패하면 `DRILL_RESTORE_FAILED` + `STATE=RESTORE_FAILED` + 비정상 종료(`exit != 0`)를 반환한다.

---

## 3. 실제 운영 실행 절차 (별도 승인 후 실행 — 이번 라운드 미실행)

### Step 0. Git `main` 원본 SHA-256 확보 (로컬/CI에서 확인)
```bash
PROM_SHA="$(git show origin/main:deploy/monitoring/prometheus/prometheus.yml | sha256sum | awk '{print $1}')"
RULES_SHA="$(git show origin/main:deploy/monitoring/prometheus/rules/gongchae.yml | sha256sum | awk '{print $1}')"
echo "PROM_SHA=$PROM_SHA RULES_SHA=$RULES_SHA"
```

### Step 1. 서버 사전 점검 (`preflight`, 읽기 전용)
```bash
cd /opt/gongchae-bell
bash deploy/monitoring/collector-alert-drill.sh preflight \
  --expected-prom-sha256 "$PROM_SHA" \
  --expected-rules-sha256 "$RULES_SHA"
```
- **정상 (`MOUNT_OK:single-file` 또는 `MOUNT_OK:directory`, `DRILL_PREFLIGHT_OK`)**: Step 2로 진행.
- **`MOUNT_STALE_SINGLE_FILE` 출력 시**: `tar` 동기화로 단일 파일 bind mount가 어긋난 상태다. `fix/#14-monitoring-bind`가 아직 반영 전이라면 Step 2에 `--allow-recreate-prometheus`를 명시하거나, 먼저 `deploy/deploy.sh monitoring up -d --force-recreate prometheus` 후 `preflight`를 다시 통과시킨다.
- **`COLLECTOR_ENABLED=true`, 기존 `firing` 존재, `EXPECTED_GIT_SHA_MISMATCH`, `PREVIOUS_DRILL_NOT_RESTORED` 등**: 즉시 중단하고 원인을 확인한다.

### Step 2. SSH 분리 실행 (`run`) 및 관측
```bash
mkdir -p ~/gongchae-ops && chmod 0700 ~/gongchae-ops
nohup setsid bash /opt/gongchae-bell/deploy/monitoring/collector-alert-drill.sh run \
  --expected-prom-sha256 "$PROM_SHA" \
  --expected-rules-sha256 "$RULES_SHA" \
  > ~/gongchae-ops/collector-drill.log 2>&1 < /dev/null &
echo "DRILL_PID=$!"
tail -f ~/gongchae-ops/collector-drill.log
```
*(단일 파일 mount가 stale이고 재생성을 명시 허용할 때만 `--allow-recreate-prometheus` 추가)*

- **예상 단계별 진행 (설정 기준 추정 창 — 실제 시각은 실행 시 실측)**:
  1. `DRILL_BASELINE_UP`: `gongchae-drill` `up=1`, `0`/`0`에서 오경보 `0`건.
  2. `DRILL_INJECTED` (`500` / `2`):
     - `t + ~30~90s`: `DRILL_BUDGET_WARNING_FIRING` (`[훈련] 공공데이터 API 일 호출 경고 한도 도달`) 및 `DRILL_COLLECTION_FAILING_PENDING`.
     - `t + >= 300s (5m)`: `DRILL_COLLECTION_FAILING_STILL_PENDING_AT_5M` (`for: 10m` 조기 발동 안 함 확인).
     - `t + >= 600s (~10m30s~12m)`: `DRILL_COLLECTION_FAILING_FIRING` (`[훈련] 정기 수집 2회 연속 미완료 (실패 기록)`).
     - `90s` 대기(Grafana `ops-discord` 전송 확인).
  3. `DRILL_RECOVERY_INJECTED` (`0` / `0`) → `DRILL_ALERTS_RESOLVED_IN_PROMETHEUS` → `90s` 대기(`[RESOLVED]` 전송 확인).
  4. `DRILL_RESTORE_VERIFIED_OK` → `DRILL_RUN_COMPLETE` (총 약 `15~17분`, 워치독 `25분` 이내).
- **사람/Grafana 관측**: 실행 중·완료 후 운영 Discord 채널에서 두 경보의 `[훈련]` 발동·해제 메시지와 Grafana receiver 전송 오류 없음을 확인한다.

### Step 3. 비상 수동 원복 (`restore`, 멱등)
만약 장애·재부팅·원복 실패(`RESTORE_FAILED`) 후 수동 재확인이 필요하면 아래를 실행한다:
```bash
cd /opt/gongchae-bell
bash deploy/monitoring/collector-alert-drill.sh restore \
  --expected-prom-sha256 "$PROM_SHA" \
  --expected-rules-sha256 "$RULES_SHA"
```

---

## 4. 남은 실행 조건 (실제 운영 적용 전)

1. 본 PR(`ops/#15-collector-drill`)에 대한 root Codex + 비작성자 검수 및 CI 통과.
2. 기존 `GongchaeDrill` Discord 채널 메시지의 사람 화면 확인 여부 확정.
3. 별도 운영 실행 배정 승인 후 실제 VM에서 `preflight` → `run` 수행 및 `docs/ops/monitoring.md`·`docs/ops/collector.md`에 실측 시각(발동·수신·해제, 훈련 창 시각, SHA-256/DB 불변 근거) 기록.
