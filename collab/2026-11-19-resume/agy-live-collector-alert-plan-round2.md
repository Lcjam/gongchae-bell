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
- **훈련 중 배포 금지 (비차단 3 반영)**:
  - `deploy.yml`의 `tar` 동기화가 훈련 중 실행되면 host 파일과 Prometheus bind mount가 어긋나므로, 훈련 창 동안 자동 배포(`AUTO_DEPLOY_ENABLED`) 및 수동 `workflow_dispatch` 배포를 실행하지 않는다.

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

### B3. 컨테이너 env 기반 DB 스냅샷 & 일자 무관 전후 동일성 검증
- **문제**: `docker compose -f compose.prod.yml --env-file .env exec postgres`는 빈 `APP_BLUE_TAG`/`APP_GREEN_TAG` 보간 실패로 깨지고 셸에 `$DB_USER`도 없다. 또한 `api_budget = 37`은 `2026-11-19` 행이므로 날짜가 바뀌면 오늘 행이 `0`이 되어 판정이 깨진다.
- **해결**:
  - `.env`는 절대 `source`하지 않고, `COLLECTOR_ENABLED`·`ALERTS_ENABLED` 2개 값만 읽기 전용 파서로 추출해 `false`임을 확인한다(비밀값 출력 0).
  - Postgres는 실행 중인 컨테이너 내부 env로 직접 조회한다:
    ```bash
    docker exec "$PG_CONTAINER" sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1 -qtAc "..."'
    ```
  - 전후 비교 대상 4종(`diff -u db_before.txt db_after.txt` == 빈 출력):
    1. `posting` 총 건수 및 `sn:status:updated_at` 정렬 md5 지문
    2. `collect_run` 총 건수
    3. `api_attempt` 총 건수 및 `max(id)`
    4. `api_budget` 전체 `day=count` 목록
  - 추가로 실행 중인 서비스 컨테이너 목록(`이름|이미지|ID`)과 `.env` 플래그도 전후 `diff`로 보존을 확인한다.

### B4. SSH 단절·중간 실패·시간 상한 자동 원복 및 비은폐 실패 판정
- **훈련 표식 (1안 채택 + 안전장치)**:
  - `rules/gongchae.yml`의 `GongchaeApiBudgetWarning`과 `GongchaeRegularCollectionFailing` 2개 규칙의 **`summary:` label에만** `"[훈련] "` 접두어를 붙인다(YAML 문법 안전을 위해 큰따옴표로 감쌈).
  - Python 검증기가 **정확히 2줄만 변경**되고 모든 `expr:`·`for:` 줄(특히 `for: 10m`)이 바이트 단위로 동일함을 확인한 뒤 `promtool check config`를 통과해야만 적용한다.
- **SSH 분리 실행 · 워치독 · `EXIT` 트랩**:
  - 실제 실행은 SSH 세션과 분리해 기동한다(`nohup setsid bash ... >~/gongchae-ops/collector-drill.log 2>&1 < /dev/null &`).
  - 스크립트 내부에 `trap on_signal INT TERM HUP` + `trap on_exit EXIT`와 **25분(`1500s`) 하드 마감 워치독**(시간 초과 시 `SIGTERM` 전송 → `restore_and_verify` 자동 실행) + exporter 컨테이너 자체 `timeout 1560`의 2중 방어를 둔다.
  - 이전 실행의 `${BACKUP_DIR}/state`가 `ARMED` 또는 `RESTORE_FAILED`로 남아 있으면 새 `preflight`/`run`을 **즉시 거부**해 원본 백업이 오염되는 사고를 원천 차단한다.
- **원복 오류 비은폐 + Git `main` blob 해시 대조**:
  - `restore_and_verify`는 ① in-place 파일 원복 + `rules/drill*.yml` 제거, ② `drill-exporter` 제거 확인, ③ `SIGHUP` 후 `/api/v1/status/config`·`/api/v1/targets`에서 `gongchae-drill` 0건 & 활성 `gongchae-app` `up` 확인, ④ `/api/v1/alerts` firing/pending `0`건 확인, ⑤ `orig.sha256` **및** `--expected-prom-sha256`/`--expected-rules-sha256`(Git `main` blob SHA-256) **및** Prometheus 컨테이너 내부 SHA-256 일치, ⑥ DB/플래그/컨테이너 전후 동일성을 모두 검사한다.
  - 이 중 **하나라도 실패하면 `DRILL_RESTORE_FAILED`를 stderr에 출력하고 `state=RESTORE_FAILED`와 함께 비정상 종료(`exit != 0`)**한다. 원복 오류를 절대 성공으로 숨기지 않는다.

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
