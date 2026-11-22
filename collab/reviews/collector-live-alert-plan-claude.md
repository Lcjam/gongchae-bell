# 수집 경보 훈련 준비안 독립 검수 (Claude)

2026-11-20 root 배정 `collab/2026-11-19-resume/claude-live-alert-plan-review-request.md`에 따른 비작성자 검수다. 대상은 `collab/2026-11-19-resume/agy-live-collector-alert-plan.md`(agy, 2026-11-19)이고 대조 source는 `origin/main` `c4465f5`다. 이 main의 `deploy/monitoring/`, `compose.monitoring.yml`, `compose.prod.yml`, `deploy/deploy.sh`, `deploy.yml`은 운영 적용판 `32e71e4`와 차분이 없다. 운영 SSH·env·키·Discord·DB에는 접근하지 않았다. 앱·설정·시험도 바꾸지 않았다. 재현은 로컬 Docker로만 했다.

## 판정

**이대로는 실행하지 않는다. 차단 4건을 고치면 실행할 수 있다.** 핵심 방향은 맞다. 원래 `expr`·`for`를 그대로 두고, 고정 지표 exporter로 실제 Prometheus → Grafana `ops-discord` 경로를 통과시키는 방식이다. 다만 아래 B1은 준비안 그대로면 drill 대상이 Prometheus에 아예 반영되지 않는 결함이다. B2는 훈련 중 실제 critical 경보를 가리는 결함이다.

## 차단 (최소 수정 요구 포함)

### B1. `prometheus.yml` in-place 수정이 운영 Prometheus에 닿지 않는다

- **근거**: `compose.monitoring.yml`은 `prometheus.yml`을 단일 파일로 bind mount한다. `deploy.yml`의 동기화는 `tar -xf - -C /opt/gongchae-bell`이다. 서버와 같은 GNU tar 1.35로 재현한 결과는 다음과 같다(`ubuntu:24.04 --privileged`, docker와 같은 커널 `mount --bind`).
  - 컨테이너 기동 시 host inode `72515`, mount `72515`
  - tar 동기화(같은 내용) 뒤 host `72520`, mount `72515`
  - 그 뒤 host 파일을 `cat >`로 고치면 host에는 drill-exporter가 보이지만 mount 쪽은 옛 내용 그대로다
- **운영 상태(기록상)**: Prometheus 컨테이너는 09:52:55Z에 기동했다(`claude-runtime-result.md`). 그 뒤 10:22Z(`517eb6b`)와 10:35Z(`32e71e4`) dispatch가 둘 다 이 tar 동기화를 거쳤다(`claude-g4-result.md`, `claude-main32-deploy-result.md`). 따라서 지금 운영 Prometheus는 옛 inode를 보고 있을 가능성이 높다(추정, 실행 전 확인 필요). 내용이 같아 지금 기능 문제는 없다. 하지만 준비안 Step 1의 `cat … > prometheus.yml` + SIGHUP은 drill 대상을 추가하지 못하고, 실패표의 "mv 사용 여부 점검"으로는 원인을 찾지 못한다.
- **규칙 디렉터리는 문제없다**: 같은 재현에서 디렉터리 bind mount는 tar 동기화 뒤에도 새 파일 내용과 추가 파일을 봤다. 2라운드 `GongchaeDrill`이 성공한 이유다.
- **최소 수정**: Step 0에서 컨테이너와 host의 `prometheus.yml` inode·sha256을 비교한다(`deploy/deploy.sh monitoring exec -T prometheus stat -c %i /etc/prometheus/prometheus.yml`, 같은 명령의 `sha256sum`, host `stat -c %i`·`sha256sum`).
  - 다르면 drill 전에 한 번 `deploy/deploy.sh monitoring up -d --force-recreate prometheus`로 mount를 현재 파일에 다시 묶는다. 볼륨 `prometheus_data`는 유지된다. 그 뒤로는 계획대로 in-place 수정 + SIGHUP을 쓴다.
  - 재생성하는 몇 초 동안 Grafana 전달 규칙(`execErrState: Error`)이 평가에 실패하면 `DatasourceError`가 운영 Discord로 갈 수 있다. 훈련 기록에 같이 남긴다.
  - 반영 확인은 targets뿐 아니라 `/api/v1/status/config`에 drill 대상이 보이는지로 한다.

### B2. drill 대상을 `gongchae-app` job에 넣으면 실제 앱 장애 경보가 가려진다

- **근거**: `GongchaeAppMetricsDown`은 `max(up{job="gongchae-app"}) < 1 or absent(up{job="gongchae-app"})`(`for: 5m`)다. 준비안의 sed는 drill-exporter를 이 job의 targets에 붙인다. `prom/prometheus:v3.16.0`에서 실제 `rules/gongchae.yml`로 `promtool test rules`를 돌렸다.
  - green·blue `up=0`, drill `up=1`(같은 job)이면 6분 시점에 `GongchaeAppMetricsDown` 경보가 **없다**(가려짐 재현).
  - drill을 `job="gongchae-drill"`로 분리하면 같은 조건에서 `GongchaeAppMetricsDown`이 발동하고, `GongchaeApiBudgetWarning`도 그대로 발동한다(`max(collector_api_attempts_today) >= max(collector_api_warning_limit)`에는 job 필터가 없음).
  - 두 테스트 모두 `SUCCESS`. 시험 파일은 아래 "재현 자료"에 있다.
- **영향**: 훈련 중(최대 25분, 정리에 실패하면 exporter 종료 전까지) 앱이 실제로 내려가도 critical 경보가 오지 않는다. "훈련 경보와 실제 경보를 섞지 않는다"는 요구에도 어긋난다.
- **최소 수정**: 기존 job의 targets를 고치지 말고 별도 scrape job을 덧붙인다.
  ```yaml
  - job_name: gongchae-drill
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ["drill-exporter:8081"]
  ```
  수집 경보 두 개(`RegularCollectionFailing`, `ApiBudgetWarning`)는 job 필터가 없으므로 그대로 발동한다. `AppMetricsDown`·`Http5xxHigh`·대시보드의 job 필터 패널은 영향을 받지 않는다. 붙인 뒤 `grep -q 'job_name: gongchae-drill'`로 실제 반영을 확인한다. 준비안 sed는 패턴이 맞지 않아도 조용히 통과한다.

### B3. Step 0·4의 DB 확인 명령이 실패하고, "API 37" 판정은 날짜가 바뀌면 틀린다

- **근거 1**: `docker compose -f compose.prod.yml --env-file .env exec …`는 실패한다.
  - `compose.prod.yml`의 앱 서비스는 `${APP_BLUE_TAG:?…}`·`${APP_GREEN_TAG:?…}`를 요구한다. 운영 `.env`에는 이 키가 없고 `deploy.sh`가 상태 파일에서 넣어 준다. `docs/ops/monitoring.md` 설치 절에도 직접 compose 호출은 `required variable APP_BLUE_TAG is missing`로 실패한다고 적혀 있다.
  - 셸에 `$DB_USER`도 없다. `.env`를 source하지 않았기 때문이다.
- **근거 2**: Step 0·4는 오늘 KST 날짜의 `api_budget`을 37로 기대한다. 37은 2026-11-19 행이다. 2026-11-20 KST부터는 오늘 행이 없어 0이 나오므로 "불변"을 판정할 수 없다. 앱 게이지 `collector_api_attempts_today`도 오늘 0이다.
- **최소 수정**:
  - 컨테이너 env로 조회해 비밀값을 셸에 올리지 않는다: `docker exec gongchae-bell-prod-postgres-1 sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -qtAc "…"'`.
  - 판정은 고정값이 아니라 **실행 전후 같음**으로 한다. 비교 대상은 `select count(*), max(id) from api_attempt`, `select day, count from api_budget order by day`, `select count(*) from posting`(+ 2라운드의 posting 지문 md5), `select count(*) from collect_run`이다.

### B4. SSH 단절·중간 실패 때 운영 설정이 훈련 상태로 남는다

- **근거**: `timeout 1500`은 exporter만 끝낸다. 지표가 사라져 경보는 풀리지만, `prometheus.yml`의 drill 대상과 1안의 `[훈련]` 요약은 그대로 남는다. 1안이면 이후 **실제** 예산·연속 미완료 경보가 `[훈련]`으로 표시된다. Step 4는 사람이 재접속해야만 돈다.
- **최소 수정**: 훈련 전체를 한 스크립트로 묶어 SSH와 분리해 실행한다(`nohup setsid bash drill.sh >~/gongchae-ops/drill.log 2>&1 &` 등). 스크립트 안에는 다음이 들어가야 한다.
  - `trap restore EXIT`
  - 정해진 마감 시각에 restore를 부르는 감시. restore는 멱등이어야 한다: in-place 원복 → `rules/drill*.yml` 삭제 → `docker rm -f drill-exporter` → SIGHUP → 해시 확인.
  - 해시 확인은 백업 사본뿐 아니라 **Git main blob**과도 비교한다(`git show <main>:deploy/monitoring/prometheus/rules/gongchae.yml | sha256sum`).
  - 사람은 로그와 API로 관측만 한다.

## 비차단

1. **사실 갱신**: 준비안 §1의 blue `836a3c0`·`app-blue`·"API 37"은 2026-11-19 관측이다. 최신 기록(`claude-main32-deploy-result.md`)은 다음과 같다.
   - green `sha-32e71e4…`(`app-green:8081` up, `app-blue` down), prev blue `836a3c0`, 컨테이너 5개, Flyway V5, 공고 36,879, `api_attempt` 37, 수집·알림 false
   - 지금 main은 `c4465f5`(문서만 추가)이고 모니터링 source 차분은 없다.
   - 실행 직전 `deploy.sh status`·targets·alerts·DB를 다시 읽어 기준선을 새로 잡는다. "app-blue의 37/0" 같은 문구는 "활성 색 앱의 오늘 값"으로 바꾼다.
2. **훈련 표시 1안 권장**: `rules/gongchae.yml` 머리 주석대로 `summary`는 annotation이 아니라 **label**이다. Grafana 전달식 `label_replace(ALERTS{alertstate="firing"}, …)`가 이 label을 그대로 실어 보내므로 `[훈련]`이 Discord 메시지에 보인다(2라운드 `GongchaeDrill`도 label 요약으로 전송 성공). 1안을 B4의 자동 원복·Git 해시 확인과 함께 쓴다. 2안의 `GongchaeDrill` 감싸기 메시지는 선택이다.
3. **훈련 중 배포 금지**: dispatch의 tar 동기화가 끼면 host 파일이 원본으로 돌아가고 mount가 다시 어긋난다(B1). 현재 `AUTO_DEPLOY_ENABLED`는 미설정이다. 훈련 창에는 수동 dispatch도 하지 않는다고 적는다.
4. **지표 이력**: Prometheus 보존은 1년이다. 훈련 창의 500·2 값이 수집 패널(`max`, job 필터 없음)에 남는다. admin API는 꺼져 있어 삭제할 수 없다. 별도 job(B2)으로 구분되므로 `monitoring.md` 수신 표에 훈련 창 시각을 남겨 해석할 수 있게 한다.
5. **시간 예산**: 주입부터 `RegularCollectionFailing` 발동까지는 `for 10m` + 평가 30초 + Grafana 30초 + group_wait 30초로 약 11~12분이다. 해제는 2라운드 실측 76초다. 합계 약 16분으로 exporter 상한 25분 안에 든다. 준비안의 관측 시점(5분에 pending 유지, 10분30초~12분에 firing)도 맞다. 실측 값으로 바꿔 기록한다.
6. **이미 있던 #15 문서 결함(이번 계획 밖)**: `monitoring.md` 설치 5번은 "규칙·scrape 설정을 바꾼 뒤 SIGHUP"이라고 쓴다. 하지만 배포 동기화가 한 번이라도 지나간 뒤에는 B1 때문에 `prometheus.yml` 변경이 SIGHUP으로 반영되지 않는다. 해결안은 둘 중 하나다: `up -d --force-recreate prometheus`로 안내를 바꾸거나, `prometheus.yml`도 디렉터리로 mount하도록 source를 바꾼다. 둘 다 별도 배정 대상이다.

## 맞다고 확인한 것

- 두 규칙의 `expr`·`for`·`max` 집계를 바꾸지 않는다. exporter는 `collector_api_attempts_today`·`collector_regular_incomplete_consecutive` 두 개만 낸다.
  - 한도 `collector_api_warning_limit=500`과 `collector_last_complete_epoch=0`은 활성 앱 값이 그대로 쓰인다. 그래서 `ApiBudgetStopped`(900)와 `RegularCollectionStale`(epoch 0)은 조용하다.
  - Grafana는 `alertstate="firing"`만 전달하므로 `for: 10m` 대기 중 pending은 보내지 않는다.
- exporter는 `backend`(`internal: true`) 망에만 붙고 호스트 포트가 없다. 서버에 이미 있는 `caddy:2-alpine`을 쓰며 새 상시 서비스나 유료 자원이 없다. `Content-Type` 헤더와 끝 개행이 필요하다는 agy의 로컬 재현은 Prometheus v3 text 파서 동작과 맞다(이번에 다시 재현하지는 않았다).
- 규칙 디렉터리 bind mount는 tar 동기화 뒤에도 파일 변경·추가를 본다(재현함). 그래서 rule 파일 쪽 원복은 SIGHUP으로 충분하다.
- 시작 조건(`COLLECTOR_ENABLED=true`이거나 기존 firing이 있으면 중단)과 promtool 사전 검사는 적절하다.

## 재현 자료 (로컬, 운영 미접근)

- tar·bind mount: `docker run --rm --privileged ubuntu:24.04`에서 `mount --bind` 단일 파일과 디렉터리에 `tar -cf - deploy | tar -xf - -C host`를 돌렸다. 결과는 B1과 같다(단일 파일 inode 72515 → host 72520, mount 72515 유지. 디렉터리는 새 내용을 봄).
- 규칙 가림: `origin/main`의 `rules/gongchae.yml` + 시험 파일 `mask.test.yml`(2개 케이스)을 `prom/prometheus:v3.16.0`의 `promtool test rules`로 돌렸고 `SUCCESS`였다. 시험 파일은 Claude scratchpad `drill-review/`에 있고 저장소에는 넣지 않았다. 필요하면 root가 실행 배정 때 `deploy/monitoring/prometheus/tests/`의 회귀로 옮길지 정한다.

## 실행 전에 남는 조건

B1~B4 반영본의 재검수, 운영 실행 배정, 2라운드 `GongchaeDrill` 메시지의 사람 화면 확인 여부(root가 사용자에게 질문 대기 중).
