# 모니터링과 경보 (#15)

> 상태 (2026-11-18): 설정, 앱 계측, 시험이 저장소에 있다. **운영 서버 설치, 실제 운영 디스코드·UptimeRobot·Healthchecks.io 계정 연결과 수신 확인은 아직 하지 않았다.** 아래 "실제 수신 확인"을 기록하기 전에는 #15를 닫지 않는다. 결정 근거는 [ADR-0006](../adr/0006-observability.md), 초기값은 [ADR-0014](../adr/0014-initial-settings.md)다.

## 무엇을 무엇으로 보나

| 대상 | 방법 | 상태 |
|---|---|---|
| 웹 생존 | UptimeRobot 외부 HTTP(5분) | 계정 설정 전 |
| 정기 수집 | Healthchecks.io heartbeat(앱이 보냄) + Prometheus 경보 | 앱 쪽 구현, 기본 꺼짐 |
| 이메일 묶음 | Healthchecks.io heartbeat (`HEARTBEAT_DIGEST_URL`) | #7 구현 병합, 계정 연결 전 |
| 백업, NAS·PC 가져오기 | Healthchecks.io heartbeat(호스트 스크립트가 보냄) | 백업 #16 구현(계정 연결 전), NAS·PC #17 |
| HTTP p95·오류율, 화면 요청·원문 이동, 마지막 완전 수집, 연속 미완료, API 일 예산 | Prometheus → Grafana 대시보드, Prometheus 규칙 → Grafana → 운영 디스코드 | 구현, 대역 시험 통과 |
| 발송 적체·실패·결과 불명확·지연(M2) | Prometheus → Grafana 대시보드, Prometheus 규칙 → Grafana → 운영 디스코드 | #7·#15 통합 구현, 대역 시험 통과 (실서버 연결 전) |

## 구성

| 파일 | 내용 |
|---|---|
| [`compose.monitoring.yml`](../../compose.monitoring.yml) | `prometheus`, `grafana`. `compose.prod.yml`(#14) 위에 얹는 overlay다. 같은 프로젝트로만 쓴다 |
| `deploy/monitoring/prometheus/prometheus.yml` | scrape 30초, 규칙 평가 30초, 대상 `app-blue:8081`·`app-green:8081` |
| `deploy/monitoring/prometheus/rules/gongchae.yml` | 경보 규칙 9개 (수집·웹·예산 6개 + M2 발송 3개, 아래 표) |
| `deploy/monitoring/prometheus/tests/gongchae.test.yml` | 규칙 발동·복구 시험 (`promtool test rules`) |
| `deploy/monitoring/grafana/provisioning/` | 데이터소스, 대시보드 등록, 경보 전달 규칙·연락처·알림 정책 |
| `deploy/monitoring/grafana/dashboards/gongchae.json` | 대시보드 `공채벨 운영` (uid `gongchae-ops`) |
| `deploy/monitoring/tests/alert-e2e.sh` | 격리 스택 대역 시험 (아래 "시험") |

**공개하지 않는다.** Prometheus는 호스트 포트가 없다. Grafana는 호스트 루프백 `127.0.0.1:3000`에만 열어 SSH 터널로 들어간다. 앱 관리 포트 `:8081`은 #14 설정대로 Docker 내부망에만 있다. 망은 셋이다:
- `backend`(#14, internal): Prometheus → 앱 `:8081`
- `monitoring`(이 overlay, 외부 송신 가능): Grafana ↔ Prometheus, Grafana → 디스코드
- Grafana는 `backend`에 없으므로 앱과 postgres에 닿지 않는다

**경보 흐름.** Prometheus가 규칙을 평가하고, Grafana 규칙 하나(`GongchaePrometheusAlert`)가 `ALERTS{alertstate="firing"}`을 30초마다 읽어 경보마다 운영 디스코드로 보낸다. 경보가 풀리면 해제 메시지도 보낸다. Alertmanager는 쓰지 않는다(ADR-0006). 임계값을 Prometheus 규칙에 둔 이유는 `promtool`로 CI에서 발동·복구를 시험할 수 있어서다. Grafana는 `alertname`을 자기 규칙 이름으로 덮어쓰므로 Prometheus 경보 이름은 `prom_alert` 라벨로 옮긴다. 알림 정책은 `prom_alert`별로 묶는다. 처음 대기 30초, 묶음 간격 1분, 계속 발동 중이면 4시간마다 다시 보낸다. Prometheus를 조회하지 못하면 Grafana 자체 경보(`DatasourceError`)가 같은 채널로 간다. VM 전체나 Docker가 멈추면 Grafana도 멈추므로, 그때는 외부 감시(UptimeRobot, Healthchecks.io)만 울린다. 그래서 둘을 따로 둔다.

> **주의 (기존 `grafana_data` 볼륨이 있는 설치에서 Grafana 이미지가 바뀐 경우):** `deploy.yml`이 새 `compose.monitoring.yml`을 서버에 동기화한 뒤 평소의 `deploy/deploy.sh monitoring up -d grafana`(웹훅 활성화 포함)나 `deploy/deploy.sh monitoring up -d prometheus grafana`를 실행해도 새 Grafana 이미지로 컨테이너가 재생성되며 기존 `grafana_data`(`grafana.db`) 마이그레이션이 시작된다. [Grafana OSS 보안 갱신 기록](../security/grafana-update-2026-11-23.md#52-실제-운영-rollout-전-별도-운영-gate-이번-pr-미실행)의 **Gate 1(Grafana 중지 후 `grafana_data` 사전 백업)과 Gate 2(외부 egress 차단 네트워크 격리 리허설)**를 마치기 전에는 기존 `grafana_data` 볼륨을 대상으로 `grafana`를 `pull`·`up`·재생성하지 않는다.

**전송은 기본 꺼짐.** `OPS_DISCORD_WEBHOOK_URL`이 비어 있으면 compose가 `http://127.0.0.1:9/alerts-off`(Grafana 컨테이너 자신의 닫힌 포트)를 넣는다. Grafana는 웹훅 URL이 비어 있으면 시작하지 않기 때문이다(13.0.2에서 확인: `could not find webhook url property in settings`). 이 상태로 대시보드와 Prometheus를 먼저 띄울 수 있다. 운영자가 보는 실제 동작은 이렇다.
- 경보는 평가되고 Grafana 경보 목록에 발동으로 보인다. 받는 곳은 없다.
- 발동 중인 경보가 있으면 Grafana 로그에 로컬 전송 실패(`connection refused`, 대상 `127.0.0.1:9`)가 반복해 남는다. 밖으로는 아무것도 나가지 않는다.
- 웹훅을 넣었다고 끝난 것이 아니다. `.env`에 값을 넣고 `deploy.sh monitoring up -d grafana`로 다시 만든 뒤(환경값은 `restart`로 바뀌지 않는다), 아래 "실제 수신 확인" 1번 훈련으로 발동·수신·복구를 확인해야 전환 완료다.

앱 heartbeat도 `HEARTBEAT_COLLECTOR_URL`이 비어 있으면 아무것도 보내지 않는다.

## 설치 (운영 서버, 후속 실행)

#14는 main에 병합됐다(`be30f69`). 서버 적용은 아직 하지 않았다. 아래 명령은 모두 `/opt/gongchae-bell`에서 실행한다.

**모니터링 명령은 `deploy/deploy.sh monitoring <docker compose 인자>`로 실행한다.** `compose.prod.yml`은 `APP_BLUE_TAG`·`APP_GREEN_TAG`를 필수로 요구하는데, #14 계약에서 이 값은 `.env`가 아니라 `deploy/state/{blue,green}_tag`에 있다(`.env`에서는 빈 값). 그래서 `docker compose -f compose.prod.yml -f compose.monitoring.yml ...`을 직접 치면 `required variable APP_BLUE_TAG is missing a value`로 실패한다(agy가 재현, `compose-integration.sh` 1단계). `deploy.sh monitoring`은 다른 `deploy.sh` compose 호출과 같은 방법으로 태그 상태를 읽고(배포 전이면 자리표시 `unset`), 같은 `.env`와 같은 프로젝트에 `-f compose.monitoring.yml`을 더해 나머지 인자를 그대로 compose에 넘긴다. 앱·Caddy·배포 상태를 건드리지 않으므로 배포 잠금은 잡지 않는다. 진행 중인 배포가 준비한 Caddy 임시 파일(`upstream.caddy.next`)도 지우지 않는다(그 파일은 준비한 실행만 정리한다. PR21 2차 검수 P2, `deploy-test.sh` [17/17]).

1. 파일 배치: `main` 배포 워크플로(`deploy.yml`)가 `compose.prod.yml`, `compose.monitoring.yml`, `deploy/`(→ `deploy/monitoring/` 포함)를 서버에 동기화한다. 동기화만으로 실행 중인 Prometheus·Grafana가 바뀌지는 않는다(아래 5번).
2. `/opt/gongchae-bell/.env`(0600)에 키를 넣는다. 키 이름은 [`deploy/.env.prod.example`](../../deploy/.env.prod.example)에 있다.
   - `GRAFANA_ADMIN_PASSWORD`: 필수. 비밀번호 관리자에서 만든다. Grafana는 이 값을 **처음 기동할 때만** 쓴다. 나중에 바꾸려면 `deploy/deploy.sh monitoring exec grafana grafana cli admin reset-admin-password <새 값>`을 쓴다.
   - `OPS_DISCORD_WEBHOOK_URL`: 운영 디스코드 채널 웹훅. 비워 두면 전송 꺼짐(위 설명).
   - `HEARTBEAT_COLLECTOR_URL`: 앱 설정이다. `compose.prod.yml`이 두 색 앱 환경으로 넘긴다. 값을 바꾸면 다음 앱 배포부터 적용된다.
3. 기동: `deploy/deploy.sh monitoring up -d prometheus grafana`
4. 접속: 내 PC에서 `ssh -L 3000:127.0.0.1:3000 <서버>` → `http://localhost:3000` (계정 `admin`). Prometheus 화면은 Grafana Explore로 본다. 대상 상태는 `deploy/deploy.sh monitoring exec prometheus wget -qO- localhost:9090/api/v1/targets`.
5. 설정을 바꾼 뒤: 규칙·scrape 설정은 `deploy/deploy.sh monitoring kill -s SIGHUP prometheus`, Grafana 프로비저닝은 `deploy/deploy.sh monitoring restart grafana`, `.env` 값(웹훅·비밀번호)은 `deploy/deploy.sh monitoring up -d grafana`(다시 만듦).
   - 파일 내용만 바뀌면 `up -d`는 컨테이너를 다시 만들지 않으므로 반영 수단이 아니다. 위 SIGHUP·restart를 쓴다.
   - Prometheus는 `deploy/monitoring/prometheus` **디렉터리**를 `/etc/prometheus`로 mount한다(`prometheus.yml`과 `rules/`). 배포 동기화의 `tar -xf`는 기존 파일을 지우고 새 inode로 만든다. 디렉터리 mount는 그 뒤에도 새 파일을 본다. 예전처럼 `prometheus.yml` 한 파일만 mount하면 컨테이너가 옛 inode를 계속 잡고 있어서, 동기화나 그 뒤의 in-place 수정이 SIGHUP으로 반영되지 않았다(2026-11-20 GNU tar 1.35로 재현, `compose-integration.sh` 4단계). Grafana mount는 처음부터 모두 디렉터리라 같은 문제가 없다.
   - **업그레이드(단일 파일 mount로 이미 띄운 Prometheus):** 새 `compose.monitoring.yml`이 동기화된 뒤 한 번 `deploy/deploy.sh monitoring up -d prometheus`를 실행한다. compose가 mount 정의 변경을 보고 Prometheus 컨테이너만 다시 만든다. `prometheus_data` 볼륨(1년 보존 데이터)은 그대로다. 재생성 몇 초 동안 평가·scrape에 공백이 생긴다. 그사이 Grafana 전달 규칙이 Prometheus를 읽지 못하면 운영 디스코드로 `DatasourceError`가 갈 수 있다. 진행 중이던 경보의 `for` 상태는 Prometheus가 보존된 데이터에서 되살리는데, 복원 조건에 따라 이어지기도 하고 다시 대기하기도 한다([v3.16.0 `RestoreForState`](https://github.com/prometheus/prometheus/blob/v3.16.0/rules/group.go#L755): 기본 `--rules.alert.for-outage-tolerance=1h`, `--rules.alert.for-grace-period=10m`). 그래서 재생성 뒤 `/api/v1/alerts`로 실제 경보 상태를 확인한다. 확인: `deploy/deploy.sh monitoring exec -T prometheus wget -qO- localhost:9090/api/v1/status/config`가 서버의 `deploy/monitoring/prometheus/prometheus.yml`과 같은 내용이어야 한다.
6. 외부 비공개 확인: 서버 밖에서 `curl -m 5 http://<공개 IP>:3000/`, `:9090`, `:8081`이 모두 연결 실패여야 한다. 서버에서 `ss -ltnp`로 3000이 `127.0.0.1`에만 열린 것을 본다. 결과를 아래 "실제 수신 확인" 기록에 같이 남긴다.
7. **첫 정기 수집 확인.** 설치 시점에 수집이 꺼져 있으면(`COLLECTOR_ENABLED=false`, 최초 적재 전) 대시보드의 "마지막 완전 수집 경과"는 `No data`이고 수집 경보 둘은 울리지 않는다. 의도된 초기 상태다. [수집 운영](collector.md#운영-순서와-남은-게이트) 절차대로 `COLLECTOR_ENABLED=true`로 앱을 배포한 뒤 첫 매시 7분 슬롯이 지나고 1분쯤(scrape 30초) 뒤에 이 패널이 `No data`에서 분 단위 숫자로 바뀌고, Explore에서 `max(collector_last_complete_epoch) > 0`인지 본다. 이때부터 `GongchaeRegularCollectionStale`이 동작한다. 두 슬롯이 지나도 `No data`이면 첫 회차가 완료되지 못한 것이다. "미완료 회차" 패널, 앱 로그의 `COLLECTION_FAILED`, [수집 운영](collector.md#설정과-정기-수집)을 확인한다. 이 확인은 첫 성공 전에는 경보가 없는 구간을 사람이 메우는 단계이며 결과(시각, 패널 값)를 "실제 수신 확인" 표 아래에 적는다. heartbeat를 켰다면 같은 회차의 ping이 Healthchecks 체크에 찍히는지도 함께 본다.

**배포와의 관계.** #14의 `deploy.sh`는 `compose.prod.yml` 하나로 앱만 다룬다(`up -d --no-deps app-<색>`, `stop`, `rm`). 그때 compose가 `Found orphan containers (prometheus, grafana)` 경고를 내지만 지우지는 않는다. `compose-integration.sh`가 실제 Docker Compose에서 `deploy.sh`가 app-blue를 만들고 멈추고 지우는 동안 두 컨테이너가 같은 ID로 계속 실행되는 것을 확인한다. `--remove-orphans`나 `compose.prod.yml`만으로 `down`을 하면 두 컨테이너가 지워진다(볼륨은 `-v` 없이는 남는다).

## 지표

이름은 Prometheus 노출 이름이다. 라벨은 모두 고정된 값이다. 경로는 템플릿(`/jobs/{sn}`)이고 쿼리, 원래 경로, IP, User-Agent, 이메일, 웹훅, 토큰, 구독 ID는 라벨에 없다(`MonitoringContractIntegrationTest`가 실제 scrape로 확인).

| 지표 | 집계 시점과 분모 | blue/green |
|---|---|---|
| `http_server_requests_seconds_{bucket,count,sum}{uri,method,status,outcome,exception}` | 요청 처리가 끝날 때 앱 메모리에서 센다. 재시작하면 0부터. 버킷 5ms~30s(아래 "p95 읽는 법"). 패널과 규칙은 `uri=~"/actuator.*"`를 뺀다 | 색마다 `rate` 후 `sum`. 한 요청은 한 색만 처리한다 |
| `gongchae_page_views_total{page,traffic}` | 화면 컨트롤러가 그 화면을 그리기로 했을 때(없는 공고 404는 세지 않음). `traffic=user`만 방문으로 본다. `bot`, `internal`(UptimeRobot·Healthchecks·G4 프로브)은 따로 센다. 고유 방문자가 아니라 요청 수다 | 색마다 `increase` 후 `sum` |
| `gongchae_source_redirects_total{generic,traffic}` | 원문으로 302를 보낼 때. `generic=true`는 기관 공통 채용 페이지 | 위와 같다 |
| `collector_*` (아래) | scrape할 때마다 DB를 읽는다. 재시작해도 값이 이어진다 | 두 색이 같은 값을 낸다. **`max`로만 읽는다** |
| `collector_api_warning_limit`, `collector_api_stop_limit` | 설정값(`COLLECTOR_WARNING_LIMIT`, `COLLECTOR_STOP_LIMIT`). 규칙은 이 값과 비교한다 | `max` |
| `alerts_notification_*`, `alerts_subscriptions_*`, `alerts_send_total_used`, `alerts_delivery_delay_max_seconds` (아래) | scrape할 때마다 DB를 읽는다([알림 운영](alerts.md#발송-기록비동기-워커지표-7)). 재시작해도 값이 이어진다 | 두 색이 같은 값을 낸다. `max` 또는 **`max by (kind)` 뒤 `sum`**으로 중복 제거 |
| `alerts_delivery_attempts_total`, `alerts_delivery_duplicate_risk_total`, `alerts_delivery_*_latency_seconds_*` | 발송 시도·완료 시 처리한 앱 메모리에서 센다. 첫 발송 전에는 노출되지 않는다 | 색마다 `increase`/`rate` 후 `sum` (`max`는 `max by (kind)`) |
| `up{job="gongchae-app",instance}` | Prometheus가 scrape할 때. 꺼진 색은 0 | 둘 중 하나라도 1이면 정상 |

`collector_*` 게이지의 뜻은 [수집 운영](collector.md#운영-지표)에 있다. 경보에 쓰는 것은 `collector_regular_incomplete_consecutive`(최근 완전 수집 뒤의 정기 회차 수, 진행 중 포함), `collector_last_complete_epoch`(마지막 정기 완전 수집의 종료 시각, 없으면 0), `collector_api_attempts_today`(KST 오늘 예약 수)다.

**p95 읽는 법.** 패널은 `histogram_quantile(0.95, …)`로 버킷 사이를 직선 보간한다. 버킷 상한은 30초다. 앱이 지원하는 가장 긴 요청이 25초 예산의 알림 신청·관리 링크 재발송 POST이고, 종료 유예가 30초라서다. 마지막 유한 버킷(30초)을 넘는 요청이 p95를 차지하면 값은 30으로 고정된다. 이것은 "30초 이상"이라는 뜻이며 예산을 넘긴 요청(GC·OS 정지, DB 불응)이 있었다는 신호다. 그 이상의 길이는 이 지표로 알 수 없다. 이전 상한 10초에서는 정상 범위의 10~25초 요청도 10으로 고정됐다(root P2). 30초 안의 값도 버킷 경계 사이의 보간값이다(예: 20초 요청만 있으면 17.18~22.91초 버킷 안에서 22.62). 시험: `MonitoringContractIntegrationTest`가 실제 scrape의 버킷 경계를, `gongchae.test.yml`이 패널 쿼리 그대로의 결과를 확인한다. M2 발송 지연 타이머(`alerts_delivery_latency_seconds`, `alerts_delivery_collection_latency_seconds`)는 `percentiles-histogram`을 켜지 않았으므로 `count`·`sum`·`max`만 노출되며, 대시보드는 평균(`rate(sum)/rate(count)`)과 최근 `max`만 표시하고 존재하지 않는 `p95` 쿼리는 쓰지 않는다(G7 분포 측정이 필요해지면 후속 PR에서 히스토그램 도입 여부를 판단한다).

**집계의 한계.** 카운터는 앱이 멈출 때 마지막 scrape 뒤의 증가분(최대 30초)을 잃는다. `increase()`는 구간 끝을 보간하므로 정수로 떨어지지 않는 근사다. 방문 수를 기록에 쓸 때는 이 조건을 같이 적는다. 보존 기간은 1년이다(ADR-0014).

**대시보드 `공채벨 운영`.** 웹(p95, 5xx 비율, 상태별 요청률, 화면 요청·원문 이동 기간 합, 색별 scrape), 정기 수집(마지막 완전 수집 경과, 미완료 회차, 24시간 선점·완료, 미종료·상세 포기), API 일 예산(오늘·한도, 종류별·재시도·결과 없음), 경보와 발송(firing 경보, 적체·회수 대상, 실패·불명·중복 위험, 발송 성공, 구독·발송 상한, 시도 결과·누적 중복 위험, 작업 생성→발송 지연, NEW 수집→발송 지연)이 있다. 완전 수집 기록이 없으면 경과 패널은 `No data`다.

## 경보 규칙 (초기값)

모두 초기 설정이며 목표값이 아니다. 운영하며 바꾸면 이 표와 규칙 시험을 함께 고친다. HTTP p95에는 경보를 두지 않는다. PRD §6대로 기준선을 실측한 뒤 정한다.

| 경보 | 조건 | 지속 | 등급 | 복구 |
|---|---|---|---|---|
| `GongchaeAppMetricsDown` | 두 색 모두 scrape 실패, 또는 대상이 하나도 없음 | 5분 | critical | 한 색이라도 응답 |
| `GongchaeRegularCollectionFailing` | `max(collector_regular_incomplete_consecutive) >= 2`: 실패·미완료로 끝난 정기 회차가 2번 이어짐 | 10분 (진행 중 회차가 잠깐 2를 만드는 것을 거른다) | critical | 다음 완전 수집(값이 0) |
| `GongchaeRegularCollectionStale` | 마지막 완전 수집이 있었고(0 아님) 그 뒤 8100초(2시간 15분)가 지남: 매시 7분 슬롯 두 번이 기록 없이 지나감(앱 정지, 스케줄러 꺼짐) | 1분 | critical | 다음 완전 수집 |
| `GongchaeApiBudgetWarning` | 오늘 예약 수 ≥ 경고 한도(500) | 없음 | warning | KST 자정에 0으로 돌아가면 |
| `GongchaeApiBudgetStopped` | 오늘 예약 수 ≥ 차단 한도(900): 정기 수집도 더 예약하지 못한다 | 없음 | critical | KST 자정 |
| `GongchaeHttp5xxHigh` | 10분 동안 5xx 비율 > 5%이고 5xx가 5건 이상 (actuator 제외) | 5분 | warning | 조건이 풀리면 |
| `GongchaeAlertsBacklog` | `max(alerts_notification_backlog) > 0`: 보낼 때가 5분 넘게 지난 대기 작업 있음 (대시보드에서 `sending_stuck`과 함께 본다) | 15분 | warning | 적체 0 |
| `GongchaeAlertsFailedHigh` | `sum(max by (kind) (alerts_notification_failed_last1h)) >= 5`: 최근 1시간 `FAILED` 5건 이상 (색별 `max` 선중복 제거) | 없음 | warning | 1시간 창이 5 미만으로 내려가면 |
| `GongchaeAlertsUncertain` | `sum(max by (kind) (alerts_notification_uncertain_last24h)) > 0`: 최근 24시간 `UNCERTAIN` 발생(공급자 처리 가능, 재발송 금지) | 없음 | warning | 24시간 창이 0이 되면 |

수집을 켜기 전(`collector_last_complete_epoch`=0)에는 수집 경보 둘이 울리지 않는다. 일부러 수집을 끈 동안에는 Grafana에서 `prom_alert=GongchaeRegularCollectionStale` silence를 건다. 상세·최초 적재는 차단 한도−72(828)에서 먼저 멈추며 이는 경보가 아니라 설계된 동작이다([수집 운영](collector.md#호출-예산과-시도)).

**지연.** scrape 30초 + 규칙 평가 30초 + 규칙의 지속 시간 + Grafana 평가 30초 + 처음 대기 30초다. 대역 시험 두 번에서 지속 시간이 없는 경보가 조건 발생 뒤 131초·99초에 웹훅 대역에 닿았고, 해제는 조건이 풀린 뒤 120초·62초에 닿았다(아래 시험 기록, 운영 측정 아님).

## 외부 감시 (계정 설정은 후속)

무료 범위는 ADR-0006의 조사값이다(UptimeRobot 무료 50개·5분, Healthchecks.io 무료 20개). 계정을 만들 때 그 시점의 무료 범위와 쓸 수 있는 알림 채널(디스코드 연동 가능 여부 포함)을 다시 확인하고 여기에 적는다. 아직 확인하지 않았다.

**UptimeRobot.** HTTP(s) 모니터 하나, `https://<도메인>/`, 5분 간격. 앱은 User-Agent의 `UptimeRobot`으로 이 요청을 `traffic=internal`로 따로 센다. 경보는 운영자 채널로 받는다.

**Healthchecks.io.** 체크 이름, 일정, 유예는 초기값이다. ping URL에는 체크의 비밀 ID가 들어 있으므로 env·Secrets에만 두고 로그에 남기지 않는다.

| 체크 | 보내는 쪽 | 일정 (Asia/Seoul) | 유예 | 상태 |
|---|---|---|---|---|
| `gongchae-collector` | 앱 `RegularCollector`, `HEARTBEAT_COLLECTOR_URL` | cron `7 * * * *` | 70분: 한 슬롯을 놓치면 다음 슬롯까지 기다리고, 두 슬롯을 놓치면 울린다 | 구현, 기본 꺼짐 |
| `gongchae-digest` | 앱 묶음 생성(#7), `HEARTBEAT_DIGEST_URL` | cron `0 8 * * *` | #7 완료 뒤(당일 08:00+만회 고려해 Healthchecks 설정 시 확정) | 앱 구현 완료(#7), 계정 연결 전 |
| `gongchae-backup` | 호스트 `deploy/backup/backup.sh`(#16), `HEALTHCHECKS_BACKUP_URL`. 성공 뒤 ping, 실패 시 `/fail` | cron `0 3 * * *` | 2시간(안) | 구현(#16 병합), 계정 연결 전 |
| `gongchae-nas-pull` | NAS·PC의 `restic copy`(#17) 성공 뒤 | #17의 실행 주기 | "며칠"(ADR-0012) → 1일 주기 + 2일 유예(안) | 미구현 |

앱 heartbeat의 동작:
- 정기 회차를 **선점한 앱만**, 회차가 `COMPLETE`로 커밋된 뒤 **한 번** 보낸다. 실패·미완료 회차, 선점하지 못한 앱(다른 색, 재시작 뒤 같은 슬롯)은 보내지 않는다. 상세 sweep의 성패는 신호에 들어가지 않는다(상세보다 먼저 보낸다).
- `GET` 한 번, 연결·응답 5초 제한, 리다이렉트는 따르지 않고, 재시도하지 않는다. 실패는 `HEARTBEAT_FAILED collector <HTTP_상태|예외 이름>`으로만 로그에 남기고 수집 결과를 바꾸지 않는다. 놓친 ping은 Healthchecks의 유예가 흡수하고, 이어지면 Healthchecks가 울린다.
- URL은 http·https만 받는다. 형식이 틀리면 `INVALID_HEARTBEAT_URL`로 시작하지 않으며 오류에 URL을 넣지 않는다.

호스트 백업(#16)은 덤프 검증·restic 백업·보관 정리가 모두 성공한 뒤에만 성공 ping을, 그 외에는 `/fail`을 보낸다(`curl -fsS --connect-timeout 5 --max-time 10 -X POST`, 재시도 없음, 로그에서 URL 마스킹). 설정 파일을 읽기 전의 실패는 `/fail` 없이 미수신 경보로 드러난다. 상세는 [백업과 복구](backup-restore.md). #17의 NAS·PC pull도 같은 방식으로 붙인다.

## M2 발송 지표 통합 계약 (구현·통합 완료)

#6(`ed3f28f`)·#7(`48f6878`)이 `AlertsMetrics`와 `digestHeartbeat`를 구현했고, #15 후속으로 Prometheus 규칙(`gongchae.yml`)·`promtool` 시험(`gongchae.test.yml`)·Grafana 패널(`gongchae.json`)·대역 E2E(`alert-e2e.sh`)를 연결했다. 상세 지표 정의와 분모·단위는 [알림 운영](alerts.md#발송-기록비동기-워커지표-7)에 있다.

| 지표 | 뜻 (`notification` · `AlertsMetrics`) | 경보 · 패널 |
|---|---|---|
| `alerts_notification_backlog` | `status IN ('PENDING','RETRY') AND next_attempt_at <= now() - 5분` | > 0이 15분 지속 → `GongchaeAlertsBacklog` (warning) |
| `alerts_notification_sending_stuck` | `status='SENDING' AND claimed_at < now() - 5분`: 회수 대상 | `GongchaeAlertsBacklog` 패널에 함께 표시 |
| `alerts_notification_failed_last1h{kind}` | 지난 1시간 `FAILED` 작업 수 (6종 `kind`) | `sum(max by (kind)(...)) >= 5` → `GongchaeAlertsFailedHigh` |
| `alerts_notification_uncertain_last24h{kind}` | 지난 24시간 `UNCERTAIN` 작업 수 (6종 `kind`) | `sum(max by (kind)(...)) > 0` → `GongchaeAlertsUncertain` (warning, 재발송 금지) |
| `alerts_notification_sent_last24h{kind}`, `alerts_notification_duplicate_risk_last24h{kind}` | 최근 24시간 `SENT` 및 `UNKNOWN` 경유 후 `SENT` 건수 | 대시보드 패널 (`max by (kind)`) |
| `alerts_subscriptions_active{channel}`, `alerts_subscriptions_disabled{channel}`, `alerts_send_total_used{provider}` | 채널별 구독 수, 일/시간 발송 상한 사용량 | 대시보드 패널 (`max by (...)`) |
| `alerts_delivery_attempts_total{kind,outcome}`, `alerts_delivery_duplicate_risk_total{kind}` | 발송 시도·중복 위험 누적 카운터 | 대시보드 기간 증가분 (`sum by (...) (increase(...))`) |
| `alerts_delivery_delay_max_seconds` | 미완료 비동기 작업(`PENDING`/`RETRY`/`SENDING`) 최장 경과 초 | 대시보드 패널 (`max(...)`) |
| `alerts_delivery_latency_seconds{kind}` (`count`/`sum`/`max`) | `SENT` 비동기 작업 1건의 생성→완료 지연 (`08:00` 묶음 이후만) | 대시보드 평균·`max` (`sum by (kind)(rate(sum))/rate(count)`) |
| `alerts_delivery_collection_latency_seconds{kind}` (`count`/`sum`/`max`) | `SENT` `NEW` 항목 1건의 수집(`first_seen_at`)→완료 지연 (`DISCORD_NEW`·`EMAIL_DIGEST`만, `08:00` 전 대기 포함) | 대시보드 평균·`max` (G7, `p95` 히스토그램 미도입) |

- DB 기반 게이지는 두 색이 같은 값을 내므로 `max` 또는 `max by (kind)` 뒤 `sum`으로 중복 제거한다. 라벨은 고정 집합(`kind` 6종, `channel` 2종, `provider` 2종, `outcome`)만 사용하며 구독 ID, 수신 주소, 웹훅, `last_error` 원문은 라벨에 넣지 않는다.
- 묶음 heartbeat: `AlertsConfiguration`의 `@Bean digestHeartbeat`(`heartbeat.digest-url` ← `HEARTBEAT_DIGEST_URL`)가 `collect_run(DIGEST, 날짜)` 선점·완료 뒤 1회 `ping()`한다. `RegularCollector`의 `collectorHeartbeat`와 이름이 분리되어 있다.

## #14 통합 (2026-11-18)

#14가 main에 병합된 뒤(`be30f69`) 이 브랜치에 받아 합쳤다.
- `compose.prod.yml`의 앱 공통 환경에 `HEARTBEAT_COLLECTOR_URL: ${HEARTBEAT_COLLECTOR_URL:-}`, `deploy/.env.prod.example`에 `HEARTBEAT_COLLECTOR_URL`·`GRAFANA_ADMIN_PASSWORD`·`OPS_DISCORD_WEBHOOK_URL` 키 이름(따로 있던 `deploy/monitoring/.env.example`은 지웠다)
- `deploy.sh monitoring` 하위 명령(위 "설치"), `deploy.yml` 동기화 대상에 `compose.monitoring.yml`
- `docs/runbook.md` §7에 이 문서 링크, `CHANGELOG.md`에 항목
- 검증: `compose-integration.sh`가 실제 `compose.prod.yml` + overlay + `deploy.sh`로 합성 값만 써서 위 내용을 확인한다(아래 "시험"). 경보 대역 시험(`alert-e2e.sh`)은 그대로 망 구성을 흉내 낸 시험용 base(`compose.e2e.yml`)에서 실제 앱 jar로 돈다. 운영 서버 적용은 하지 않았다.

## 시험

| 시험 | 무엇을 | 어디서 |
|---|---|---|
| `HeartbeatTest` | 꺼짐이면 요청 없음, 성공 GET 1회, 500·302·연결 실패는 예외 없이 URL 없는 로그, 잘못된 URL은 URL 없는 오류 | `./gradlew check` |
| `RuntimeDatabaseTest` | 두 앱이 같은 슬롯을 동시에 돌려도 ping 1회, 실패 회차는 ping 없음, 같은 슬롯 재실행은 ping 없음 | `./gradlew check` (Testcontainers) |
| `MonitoringContractIntegrationTest` | 규칙·대시보드·시험 파일이 읽는 모든 앱 지표(M2 `alerts_*` 포함)가 실제 `/actuator/prometheus`에 있음, `/jobs` 히스토그램 버킷, M2 타이머 버킷 미참조(`count`/`sum`/`max`만), 한도 게이지 500·900, 검색어·원래 경로가 scrape에 없음. 실제 `http.server.requests` 미터에 합성 20초 요청을 기록해 scrape의 마지막 유한 버킷이 25초 이상이고 p95가 10초에 고정되지 않음, 대시보드 p95 쿼리와 버킷 경계가 promtool 시험에 그대로 있음 | `./gradlew check` |
| `gongchae.test.yml` | 규칙 9개(수집·웹·예산 6개 + M2 3개)의 0/미노출·발동·비발동·복구, 두 색 `max`/`max by (kind)` 중복 제거(경보 1개 및 `4 < 5` 비발동), 수집 전 0, 한도 설정을 낮춘 경우, 낮은 트래픽의 오류 1건, 대시보드 p95 및 M2 지연 평균 쿼리 | CI `Monitoring / rules` |
| `compose-integration.sh` | 실제 Docker Compose, 실제 `compose.prod.yml`·overlay·`deploy.sh`, 합성 값과 대체 이미지. ① `.env` 규약(빈 태그)으로 직접 compose하면 보간 실패 ② `deploy.sh monitoring config`가 두 파일을 합치고 두 색 앱에 heartbeat URL, Grafana는 `127.0.0.1` 포트만, Prometheus는 포트 없음, 공개 포트는 Caddy와 Grafana 루프백뿐 ③ `deploy.sh monitoring up`으로 두 컨테이너 기동, 앱 컨테이너에 heartbeat URL 전달, `deploy.sh bootstrap`이 app-blue를 만들고 준비 실패로 멈추고 지우는 동안 두 컨테이너가 같은 ID로 실행, `deploy.sh monitoring restart grafana` ④ `deploy.yml`과 같은 모양의 `tar` 동기화로 `prometheus.yml`이 새 inode가 된 뒤 in-place로 scrape job을 더하고 SIGHUP하면 실행 중인 Prometheus 설정(`/api/v1/status/config`)에 보이고 컨테이너는 그대로(단일 파일 mount로 되돌리면 실패) | CI `Monitoring / compose-integration` |
| `deploy-test.sh` [16/17] | `deploy.sh monitoring`: 태그를 `deploy/state`에서 읽음(배포 전 `unset`), 같은 프로젝트·`.env`, 배포 잠금을 쥔 상태에서도 실행, 앱·배포 상태 무변경, 인자 없음·overlay 없음은 docker 호출 전에 실패. 이 분기가 없으면 `monitoring`이 이미지 태그로 읽혀 bootstrap이 실행되는 것을 변이로 확인 | CI `CI / deploy-verify` |
| `deploy-test.sh` [17/17] | 다른 실행이 배포 잠금과 `upstream.caddy.next`를 쥔 동안 `monitoring`·`status`·도움말·잠금 실패 종료가 그 파일을 지우지 않음. 실제 switch의 검증과 이동 사이에 `monitoring config`·`status`를 끼워도 전환이 green으로 완료. 이동할 파일이 사라지면 `SWITCH_OK` 없이 실패하고 구 색 유지·새 색 제거·pending 없음. 복귀 중 `.prev` 이동 실패는 `ROLLBACK_RELOAD_FAILED`로 두 색과 pending을 남기고 abort로 복구. finalize 색 비교 guard 유지. 세 수정을 각각 되돌린 변이와 원래 코드 모양(공유 정리 + 확인 없는 이동)에서 실패를 확인 | CI `CI / deploy-verify` |
| `alert-e2e.sh` | 실제 앱 jar + 새 DB + 이 저장소의 Prometheus·Grafana 설정 + 디스코드 대역: 웹훅 없이 Grafana 기동, scrape, 대시보드 쿼리 전부 오류 없음, `api_budget` 950 경고·차단 발동/해제, M2 `FAILED` 4건 비발동·5건 발동/해제, `UNCERTAIN` 1건 발동/해제, `backlog`/`sending_stuck`/`delay_max` scrape 및 PII 미노출 | CI `Monitoring / alert-e2e` |

### 대역 시험 기록

| 일시 (KST) | 조건 | 결과 |
|---|---|---|
| 2026-11-18 11:45:34~11:50:29 | 로컬 Docker Desktop(Windows, linux/amd64 엔진), `alert-e2e.sh` 1회, 시험 DB, 디스코드 대역 | 웹훅 없이 Grafana 기동 5초, 앱 scrape 23초, 대시보드 쿼리 19개 성공, 발동 메시지 2건(경고·차단)이 삽입 뒤 131초에, 해제 메시지 2건이 삭제 뒤 120초에 도착 |
| 2026-11-18 12:00:56~12:04:12 | GitHub Actions `ubuntu-24.04-arm`(ARM64), [run 44227246661](https://github.com/Lcjam/gongchae-bell/actions/runs/44227246661), 커밋 `f872549`, 같은 스크립트 | Grafana 기동 5초, scrape 26초, 대시보드 쿼리 19개 성공, 발동 99초, 해제 62초. 메시지 4건(발동 2, 해제 2) |
| 2026-11-18 19:17:21~19:21:35 | GitHub Actions `ubuntu-24.04-arm`(ARM64), [run 44283945764](https://github.com/Lcjam/gongchae-bell/actions/runs/44283945764), 커밋 `c36c557`(#14·#6 병합, 버킷 30초), 같은 스크립트 | Grafana 기동 6초, scrape 26초, 발동 104초, 해제 57초. 메시지 4건(발동 2, 해제 2) |
| 2026-11-19 19:40:10~19:48:46 | 로컬 Docker Desktop(Windows, linux/amd64 엔진), `alert-e2e.sh` (M2 통합 후), 시험 DB, 디스코드 대역 | Grafana 기동 6초, scrape 12초, 대시보드 쿼리 35개 성공, `api_budget` 발동 121초/해제 121초, `FAILED` 4건 비발동·5건 발동 98초/해제 116초, `UNCERTAIN` 발동/해제, `backlog`/`sending_stuck`/`delay_max` scrape 및 PII 미노출 |

규칙의 임계값을 바꾸면(8100→9000, ≥2→≥3, 5%→50%) `promtool test rules`가 실패하는 것도 확인했다. 운영 서버의 Prometheus·Grafana 메모리와 1년 보존 디스크 사용량은 아직 재지 않았다. 설치 뒤 `docker stats`와 볼륨 `du -sh`로 재서 기록한다.

## 실제 수신 확인 (후속, #15 완료 조건)

실제 API 한도를 쓰거나 운영 DB 값을 바꿔서 시험하지 않는다(`api_budget`을 운영 DB에 넣으면 그날 수집이 실제로 멈춘다). 아래처럼 각 채널을 따로 발동하고, 발동 시각, 수신 시각, 복구 시각, 채널, 측정 조건을 표에 남긴다.

1. **Grafana → 운영 디스코드**: 규칙 디렉터리에 훈련 규칙을 잠시 넣는다.
   ```yaml
   # deploy/monitoring/prometheus/rules/drill.yml (훈련 뒤 지운다)
   groups:
     - name: drill
       rules:
         - alert: GongchaeDrill
           expr: vector(1)
           labels: {severity: warning, summary: "경보 수신 훈련"}
   ```
   `kill -s SIGHUP prometheus` → 디스코드 발동 메시지 수신 → 파일 삭제와 SIGHUP → 해제 메시지 수신.
2. **배치 미완료 (Healthchecks)**: `HEARTBEAT_COLLECTOR_URL`을 넣고 앱을 재배포한 뒤 다음 정기 회차의 ping이 체크에 찍히는지 본다. 운영자 PC에서 `<ping URL>/fail`을 한 번 보내 체크를 down으로 만들고 수신을 확인한다. 다음 정기 회차의 성공 ping으로 복구되는 것을 확인한다. 수집은 건드리지 않는다.
3. **웹 장애 (UptimeRobot)**: 없는 경로(`https://<도메인>/uptime-drill-404`)를 보는 임시 모니터를 만들어 down 경보를 받고, 그 모니터를 지워 복구를 확인한다. 실제 앱 정지는 장애 훈련(포스트모템, "훈련" 표시)에서 따로 한다.
4. 백업·NAS 가져오기와 발송 경보는 #16, #17, #7이 생긴 뒤 같은 방식으로 기록한다.

| 일시 | 채널 | 발동 방법 | 발동 → 수신 | 복구 → 수신 | 조건 |
|---|---|---|---|---|---|
| (미실시) | | | | | |
