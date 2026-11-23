# [훈련 — 실제 장애 아님] #15 실제 운영 수집 경보 훈련 실행 기록 (`agy`)

> **구분: 계획된 운영 경보 훈련 (실제 장애 아님)** — 승인된 운영 호스트(`ubuntu@158.179.179.104`)에서 병합된 고정 `main` (`f3f55a90aa0c544c3d9d2438cd3e74b20742959a`)의 `deploy/monitoring/collector-alert-drill.sh`로 `GongchaeApiBudgetWarning`(임계값 `500`) 및 `GongchaeRegularCollectionFailing`(임계값 `2`, 원본 **`for: 10m`**)의 발동·Grafana 전달·해제·자동 원복을 1회 실측했다.

- **작성자 / 일시**: `agy` (단독 실행, 추가 에이전트 미생성) / 2026-11-23 (UTC `06:35:56Z` ~ `06:54:37Z`, KST `15:35:56` ~ `15:54:37`)
- **작업 브랜치 / 기준 SHA**: `ops/#15-live-drill-20261123` / 고정 `main` **`f3f55a90aa0c544c3d9d2438cd3e74b20742959a`** (PR #33 `bf5eb6a` 병합)
- **정제 근거 파일**: [`evidence-sanitized.txt`](evidence-sanitized.txt) (비밀값·웹훅 URL·자격증명 0건, 원격 raw log는 호스트 `~/gongchae-ops/collector-drill-20261123.log` mode `0600` 및 백업 디렉터리 mode `0700`으로 접근 제한 유지)
- **관련 이슈**: `#15` (미종료, `docs/incidents` 최종 회고는 3자 검토 후 별도 종합)

---

## 1. 사전 기준선 확인 및 스크립트 단독 배치 (`06:35:56Z` ~ `06:36:59Z`)

1. **운영 기준선 (직접 읽기 전용 확인, `06:35:56Z`)**:
   - **배포 상태**: `active_color=green`, `active_sha=sha-e0208ffd16c39b511cc0065dc8ee40e5c8b4f9c1`, `last_deploy_result=DEPLOY_OK`, `pending_deploy_id=none`. 기존 `drill-runtime.lock`/`owner`·`/tmp/gongchae-collector-drill`·이전 수집 훈련 백업 흔적 없음.
   - **컨테이너 (5개, 전원 `restarts=0`, 업그레이드/재시작 0건)**:
     - `gongchae-bell-prod-app-green-1` (`sha-e0208ffd16c39b511cc0065dc8ee40e5c8b4f9c1`, `269f29fc1df7`, started `2026-11-22T08:13:28Z`)
     - `gongchae-bell-prod-caddy-1` (`caddy:2-alpine`, `81ed0a65fdf8`, started `2026-11-19T09:50:10Z`)
     - `gongchae-bell-prod-grafana-1` (`grafana/grafana-oss:13.0.2`, `1e197abbea04`, started `2026-11-20T10:36:13Z`)
     - `gongchae-bell-prod-postgres-1` (`postgres:17`, `d383491b6bcb`, started `2026-11-19T09:45:35Z`)
     - `gongchae-bell-prod-prometheus-1` (`prom/prometheus:v3.16.0`, `c4e45ead8892`, started `2026-11-20T10:36:00Z`)
   - **Mount 및 Git `main` (`f3f55a9`) SHA-256 일치**:
     - Prometheus mount: **디렉터리 bind mount** (`/opt/gongchae-bell/deploy/monitoring/prometheus` → `/etc/prometheus`, `rw=false`, PR #32 기적용으로 Prometheus 재생성 불필요).
     - `prometheus.yml` (host = container = `f3f55a9` Git blob): `b7127f953236d414cefe54592d7fe8e09545052d15cc2c47f26f4dec6a48ea22` (inode `309446` 일치).
     - `rules/gongchae.yml` (host = container = `f3f55a9` Git blob): `7043847527f6472776296e9ab2881b50cd736b1c2971ee9e8bb8d4305ea3b4eb` (`rules/` 디렉터리 내 단일 파일, 원래 9개 규칙 확인).
   - **플래그 (allowlist 2키만 확인, 비밀값 미출력)**: `.env` mode `0600` (`COLLECTOR_ENABLED=false`, `ALERTS_ENABLED=false`, `OPS_DISCORD_WEBHOOK_URL` 설정 여부 `True`), `app-blue` 미실행, `app-green` 런타임 `COLLECTOR_ENABLED=false`, `ALERTS_ENABLED=false`.
   - **Prometheus / Grafana / DB 기준선**:
     - Prometheus: `gongchae-app` `app-green:8081` `up` (`app-blue:8081` `down`), 활성 경보 `0`건(`[]`), `gongchae-drill` TSDB 시계열 없음(`[]`).
     - Grafana: `/api/health` `database=ok`, `version=13.0.2`, `grafana_alerting_notification_latency_seconds_count=0` (훈련 전 Discord 전달 카운터 `0`).
     - PostgreSQL 17 (컨테이너 env 조회): `posting_count=36879`, `posting_md5=5df42f2e45d716487504e6b28d6bf26e`, `collect_run=40:40`, `api_attempt=37:37`, `api_budget=2026-11-19=37`.
2. **단일 스크립트 배치 및 `preflight` (`06:36:59Z`)**:
   - `git show f3f55a90aa0c544c3d9d2438cd3e74b20742959a:deploy/monitoring/collector-alert-drill.sh` 1파일만 `/opt/gongchae-bell/deploy/monitoring/collector-alert-drill.sh`로 배치 (`.env`/compose/deploy/rules tar 덮어쓰기·앱 배포 없음).
   - 배치 SHA-256 일치 확인: `91bd2b48bede0dde0d2231979083e6fc7d080fd53655fef6be65a35ebed38c2d`, `bash -n` 통과.
   - `preflight` 실행(`--expected-prom-sha256`, `--expected-rules-sha256` 모두 고정 `f3f55a9` blob 전달): `MOUNT_OK:directory`, `PROM_BASELINE_OK active_app=app-green:8081`, **`DRILL_PREFLIGHT_OK`**.

---

## 2. 실제 훈련 타임라인 (UTC 실측, `RUN_ID=20261123T063734Z-1482951-23079`)

- 실행 방식: `nohup setsid bash /opt/gongchae-bell/deploy/monitoring/collector-alert-drill.sh run --expected-prom-sha256 b7127f95... --expected-rules-sha256 70438475... > ~/gongchae-ops/collector-drill-20261123.log 2>&1 < /dev/null &` (SSH 단절 독립, 테스트 hook/stub/단축 옵션 0건, 원래 `500`/`2` 및 `for: 10m` 그대로 사용).

| 시각 (UTC) | 경과 (주입 기준) | 관측 사건 및 상태 |
|---|---|---|
| `06:37:33Z` | `-5s` | 감독자 분리 기동 (`PID 1482951`, 이후 `PPID=1`, `Ss` 세션 리더 유지 확인) |
| `06:37:35Z` | `-3s` | `promtool check config/rules` 통과, `gongchae-drill-exporter` 기동(`gongchae-bell-prod_backend` 내부망, 호스트 포트 없음), `job_name: gongchae-drill` 및 `[훈련]` summary 2줄 적용 + `SIGHUP` |
| `06:37:38Z` | `0s` | **`DRILL_BASELINE_UP`** (`gongchae-drill` `up`, `0/0` 스크랩, 오경보 `0`건) → **`DRILL_INJECTED budget=500 failing=2`** |
| `06:38:21.422Z` (`06:38:24Z` 로그) | `+43s` (`+46s`) | Prometheus에서 **`GongchaeApiBudgetWarning` `firing`** (`[훈련] 공공데이터 API 일 호출 경고 한도 도달`, 값 `500`) 및 **`GongchaeRegularCollectionFailing` `pending`** (`[훈련] 정기 수집 2회 연속 미완료 (실패 기록)`, 값 `2`) 동시 확인. `gongchae-app`(`app-green:8081`) `up` 유지 |
| `06:38:30Z` ~ `06:40:38Z` | `+52s` ~ `+180s` | Checkpoint 1 관측: Grafana `ngalert.sender.router`가 `count=1` 전달, `grafana_alerting_notifications_total{integration="discord",org="1"}` `0 → 1`, 지연 `0.444s`, 실패 `0`건(`pending` 경보 조기 전달 없음) |
| `06:42:40Z` (`06:43:40Z` 확인) | `+302s` (`5m 02s`) | **`DRILL_COLLECTION_FAILING_STILL_PENDING_AT_5M`** — Checkpoint 2에서 `GongchaeRegularCollectionFailing` `pending` 유지 및 Grafana Discord 카운터 `1` 유지 확인 |
| `06:48:25Z` | `+647s` (`10m 47s`) | **`DRILL_COLLECTION_FAILING_FIRING elapsed_sec=647`** — 원본 `for: 10m`(600초) 전 구간 경과 후 발동 |
| `06:48:30Z` ~ `06:50:15Z` | `+652s` ~ `+757s` | Checkpoint 3 관측: Prometheus 양 경보 모두 `firing`, Grafana `ngalert.sender.router` `count=2`, `grafana_alerting_notifications_total{integration="discord",org="1"}` `1 → 2`, 2차 지연 `0.303s`, 실패 `0`건 |
| `06:49:56Z` | `+738s` | 90초 전달 대기 후 **`DRILL_RECOVERY_INJECTED budget=0 failing=0`** |
| `06:50:21Z` | `+763s` (`+25s` 복구) | **`DRILL_ALERTS_RESOLVED_IN_PROMETHEUS`** → Grafana `[RESOLVED]` 전달 90초 대기 |
| `06:51:52Z` | `+854s` | `DRILL_WORKER_COMPLETE` → 감독자 `restore_and_verify` 시작 |
| `06:52:24Z` | `+886s` (`14m 50s` 총소요) | **`DRILL_RESTORE_VERIFIED_OK (prom_written=1 rules_written=1)`** → **`DRILL_RUN_COMPLETE`** |
| `06:54:37Z` | 사후 점검 | Checkpoint 4 독립 사후 검증 완료: Grafana `notifications_total{integration="discord",org="1"}=4`(발동 2 + 해제 2, 누적 지연 `1.375s`, 실패 `0`), `state="alerting"=0`, `state="normal"=2`, `active=0` |

---

## 3. 사후 원복 및 전후 불변 검증 (`06:54:37Z` 독립 점검)

| 검증 항목 | 결과 | 근거 (실측) |
|---|---|---|
| 감독자·워커 종료 및 상태 기록 | **PASS** | `PID 1482951` 정상 종료, `meta.env`·`state`·`drill-runtime.owner` 3파일 모두 `STATE=RESTORED`(`WORKER_PID=` 공백), 임시 파일·`/tmp/gongchae-collector-drill`·`worker-*.lock/revoked` 0건 |
| `prometheus.yml` / `rules/gongchae.yml` | **PASS** | 호스트·컨테이너 SHA-256 모두 `f3f55a9` 원본 blob과 일치 (`b7127f95...`, `70438475...`), `rules/` 내 `gongchae.yml` 1파일만 존재 |
| Prometheus config·targets·rules·alerts·TSDB | **PASS** | `/api/v1/status/config` 내 `gongchae-drill` 0건, active targets `gongchae-app`(`app-green:8081` `up`)만 존재, `alerts=[]`, `gongchae` 9규칙 post-reload 평가 완료(`lastEvaluation=2026-11-23T06:54:21.423Z`), `job="gongchae-drill"` TSDB 양 지표 모두 `0` |
| 임시 exporter 제거 및 상시 컨테이너 불변 | **PASS** | `gongchae-drill-exporter` 0건, `CONTAINERS_DIFF_EMPTY_OK`, 기존 5컨테이너 ID·이미지·`StartedAt` 동일, 전원 `restarts=0` |
| PostgreSQL 17 불변 | **PASS** | `DB_DIFF_EMPTY_OK` (`posting_count=36879`, `posting_md5=5df42f2e45d716487504e6b28d6bf26e`, `collect_run=40:40`, `api_attempt=37:37`, `api_budget=2026-11-19=37`) |
| `.env` 및 앱 런타임 플래그 불변 | **PASS** | `FLAGS_DIFF_EMPTY_OK`, `.env` mode `0600`, 파일·`app-green` 런타임 모두 `COLLECTOR_ENABLED=false`, `ALERTS_ENABLED=false` |
| Grafana 알림 전달 카운터 (HTTP/내부 메트릭) | **PASS** | `grafana_alerting_notifications_total{integration="discord",org="1"}` `0 → 1 → 2 → 4` (`notification_requests_total=4`, `latency_count=4`, `latency_sum=1.375s`, 실패 카운터 미증가/로그 오류 0건) |

---

## 4. 직접 관측 · 보고 인용 · 미실행 및 확인 대기 구분

- **직접 실행·직접 관측**: 위 사전 기준선 확인, `f3f55a9` `collector-alert-drill.sh` 단독 배치 및 해시 확인, `preflight`, 분리 `run` 1회 전 과정(0/0 기준선 → 500/2 주입 → `GongchaeApiBudgetWarning` 발동 → 5분 pending 유지 → `for: 10m` 647초 발동 → 0/0 복구 → 자동 원복), Grafana `/metrics` Discord 전달 카운터 `0 → 1 → 2 → 4`(오류 0), 사후 독립 원복/해시/TSDB/DB/플래그/컨테이너 검증.
- **보고 인용 (소급 기준선으로 미사용)**: 요청문에 기록된 `06:31:20Z` root 읽기 전용 점검과 PR #33 1~5차 검수 결과(`pr-33-codex-round5.md`, `pr-33-sol-round5.md`, `agy-pr33-round4-fixes-result.md`)는 배경으로만 참조했으며, 본 훈련의 모든 전후 기준선은 `06:35:56Z` 및 `06:37:34Z`에 직접 새로 수집했다.
- **미실행 및 확인 대기**:
  - **Discord 채널 UI 실제 수신 확인: 확인 대기 (사람 확인 필요)** — Grafana 내부 메트릭(`grafana_alerting_notifications_total{integration="discord"}=4`, `0` errors)으로 Discord 웹훅 HTTP 전달 4건(발동 2건 + 해제 2건) 성공은 관측했으나, 실제 운영 Discord 채널 화면에서 `[훈련]` 메시지 4건이 정상 표시됐는지는 에이전트가 직접 볼 수 없으므로 추측하지 않고 사용자 확인 대기로 남긴다.
  - Resend 이메일, 고객 구독, 실제 공공데이터포털 API 호출, `COLLECTOR_ENABLED`/`ALERTS_ENABLED` 활성화, Grafana/앱 보안 수정판 업그레이드, 상시 컨테이너 재시작/볼륨 삭제/권한 변경은 일절 수행하지 않았다.
  - `docs/incidents` 최종 포스트모템은 3자 검토 후 종합 단계로 남겨 두었다.
  - 지표 이력 한계(사전 계획 명시): Prometheus 1년 TSDB 보존으로 훈련 창(`06:37:38Z` ~ `06:50:21Z`)의 `job="gongchae-drill"` `500`/`2` 샘플이 이력에 남으며, job 라벨 및 본 훈련 창 기록으로 실제 수집 지표와 구별한다.
