# 검수 main `4cc6e4e` 운영 배포·모니터링 mount 업그레이드 결과 (#14·#15) · Claude

2026-11-20 root 배정 `collab/2026-11-19-resume/claude-main4cc-deploy-request.md`를 수행한 기록이다. 기록 worktree는 `nov20-runtime` / `docs/#14-nov20-runtime`(main `4cc6e4e` 기반)이다. 시각은 UTC이고 KST는 +9시간이다. 비밀값, env 전체, 이미지 키 원문, 개인정보는 적지 않았다.

## 요약

| 항목 | 결과 |
|---|---|
| target | `4cc6e4e2cca959411bf532cb9a823411ffe7f76b` (PR32 병합, PR30 포함). 이번 작업 동안 main은 그대로였다 |
| target CI | CI `44719110322`(test·deploy-verify·backup-verify·secrets), Monitoring `44719110369`(rules·compose-integration·alert-e2e) **7잡 success** |
| 배포 | 단독 `workflow_dispatch` Deploy [`44720131999`](https://github.com/Lcjam/gongchae-bell/actions/runs/44720131999), `bluegreen-4cc6e4e2cca9-44720131999-1`, green `32e71e4` → **blue `4cc6e4e`**, `DEPLOY_OK` |
| 외부 G4 | **30회, 실패 0**, 5xx·연결/TLS·시간 초과·기타 0, p95 1,020ms, max 1,102ms(GitHub 러너 Azure eastus2) |
| 모니터링 업그레이드 | Prometheus 재생성(`up -d prometheus`). mount가 단일 파일에서 **디렉터리 `/etc/prometheus`**로 바뀜. `prometheus_data` 유지, 이전 sample 이어짐. Grafana `restart`로 새 프로비저닝 반영 |
| 검증 | 규칙 9개 health ok, 대시보드 쿼리 35/35 성공, 경보 0. DB·플래그 불변, pending 없음, 공개 HTTPS 정상, 외부 비공개 포트 차단 |
| DatasourceError | Prometheus 재생성 순간 Grafana 평가 1회가 실패했지만 재시도(3회 중 1회째)에서 성공했다. 실패 상태나 전송 로그는 없다. Discord 화면은 확인하지 못했다 |
| 바꾸지 않은 것 | `AUTO_DEPLOY_ENABLED`(미설정 유지), 권한 규칙·모드, collector·alerts=false, 실제 수집·구독·발송, PR33 훈련, 백업 init·타이머·버킷·IAM, PC 설치, postgres·caddy 컨테이너 |

## 1. target 확인 (10:25~10:31Z)
- `32e71e4..4cc6e4e`의 배포 관련 차분은 다음과 같다.
  - 바뀐 것: `compose.monitoring.yml`(디렉터리 mount), 모니터링 규칙(+M2 3개 → 9개)·대시보드·시험, `deploy.yml` **주석 2줄**, 앱 소스 1파일(PR31).
  - 바뀌지 않은 것: `compose.prod.yml`, `deploy.sh`, `g4-probe.sh`, Caddy, Flyway 마이그레이션, `application.yml`.
- CI·Monitoring은 위 표와 같이 성공이다(10:25:27Z 시작). Monitoring이 끝날 때까지 같은 run을 기다렸다.
- 이미지 발행: workflow_run Deploy `44719457352`. `RUN_SHA=4cc6e4e…`, Build & Push success(10:29:02Z), 배포 잡 skipped(자동 배포 꺼짐). 발행 digest는 `sha256:b0dfc29bbcee9db95fea448262b1e0a42a7765f865b87e06900f2fe9340eadb2`이고 익명 manifest HEAD는 200이다.
- GitHub Variables는 `SITE_DOMAIN`만 있고 `AUTO_DEPLOY_ENABLED`는 없다.

## 2. 실행 전 기준선 (10:27:51Z)
- `deploy.sh status`: green `sha-32e71e4…`, `bluegreen-32e71e4bb56c-44506783359-1`, prev blue `836a3c0`, upstream green, pending none, `DEPLOY_OK`.
- 컨테이너 5개(app-green, grafana, prometheus, caddy, postgres)가 실행 중이다. `COLLECTOR_ENABLED=false`, `ALERTS_ENABLED=false`.
- **운영에서 B1 상태 확인**: Prometheus(2026-11-19T09:53:36Z 기동)의 mount가 `prometheus.yml` 단일 파일 + `rules` 디렉터리였다.
  - host `prometheus.yml`은 inode `309446`이고 컨테이너가 보는 파일은 inode `262384`로 달랐다. 내용 hash는 둘 다 `b7127f95…`로 같았다.
  - 그 사이 두 번의 배포 동기화가 inode를 바꾼 상태였다. 내용이 같아 기능 영향은 없었다.
- DB(컨테이너 env로 조회):
  - 공고 36,879, 지문 md5 `c92183c2eea0644cac25ccae5a1430da`
  - `api_attempt` 37, `collect_run` 40, `subscription`·`notification`·`notification_item` 0
  - `api_budget`는 `2026-11-19=37` 하나뿐(오늘 행 없음, 오늘 API 0). Flyway 최대 5.
- Prometheus: 규칙 6개(구판), green up·blue down, 경보 0. 인스턴스별 2일치 `up` sample 2,949(10:28Z)이고 TSDB head 시리즈는 1,568.
- Grafana: health ok. `ops-discord` 마지막 전송 2026-11-19T10:14:30Z(전날 훈련 해제), 경보 0, 대시보드 `공채벨 운영`.

## 3. blue/green 배포와 외부 G4
요청 명령(10:32:43Z run 생성, 허용됨, 지정 명령 그대로):

```text
gh workflow run Deploy -R Lcjam/gongchae-bell --ref main -f action=deploy -f image_sha=4cc6e4e2cca959411bf532cb9a823411ffe7f76b
```

| UTC | 사건 |
|---|---|
| 10:32:50~10:33:55 | Build & Push(Azure northcentralus). `INPUT_SHA=4cc6e4e…`. **같은 태그를 다시 빌드해 덮어씀**: 새 digest `sha256:76df7ffc7439088493171b0b226c2a23b0ce1ddd2f55511c51dba43fe3ec9210`. 운영 이미지는 이 digest다 |
| 10:33:59 | 배포 잡 시작(Azure eastus2, `runnervm8axu0`, OCI 밖). checkout `ref: 4cc6e4e…` |
| 10:34:04 | 원격 `last_deploy_result=DEPLOY_OK`, `mode=bluegreen` |
| 10:34:07 | 외부 G4 프로브 시작 |
| 10:34:19 | `Starting bluegreen switch … active=green(32e71e4) -> target=blue(4cc6e4e)` |
| 10:34:35 | app-blue 컨테이너 시작 |
| 10:34:45 | `Internal readiness and key routes passed for app-blue` |
| 10:34:46 | `SWITCH_OK … active_kept=green serving=blue` |
| 10:35:02 | `G4_PASS … total=30 ok=30 failures=0 p95_ms=1020 max_ms=1102` |
| 10:35:04~10:35:05 | finalize: green 정지·제거 |
| 10:35:05 | `DEPLOY_OK color=blue sha=sha-4cc6e4e… prev_color=green prev_sha=sha-32e71e4…` |

`g4-probe-report` 아티팩트 원자료:

```json
{"deploy_id":"bluegreen-4cc6e4e2cca9-44720131999-1","execution_context":"github-actions-external","runner_host":"runnervm8axu0","base_url":"https://gongchae.enjoylcjworld.com","started_at":"2026-11-20T10:34:07Z","finished_at":"2026-11-20T10:35:02Z","interval_sec":1,"connect_timeout_sec":3,"max_time_sec":5,"total_requests":30,"ok_requests":30,"failures":0,"http_5xx":0,"connect_tls_errors":0,"timeouts":0,"other_non_200":0,"p95_latency_ms":1020,"max_latency_ms":1102}
```

배포 직후(10:35:43Z):
- `active_color=blue`, `active_sha=sha-4cc6e4e…`, prev green `32e71e4`, upstream blue, pending none.
- app-blue 이미지 ID `sha256:76df7ffc…`, 재시작 0.
- 동기화된 파일 6개(`compose.monitoring.yml` `5e77f112…`, `prometheus.yml` `b7127f95…`, `rules/gongchae.yml` `70438475…`, `dashboards/gongchae.json` `09caf2a7…`, `alerting/forward.yml` `f48a5c8a…`, `compose.prod.yml` `1f2a4183…`)의 sha256 앞 16자리가 Git `4cc6e4e`와 같다.
- 참고 측정(내 PC, 한국 가정망, CI G4를 대신하지 않음): 10:32:38~10:35:21Z에 118회 조회했고 비200·curl 오류 0, p95 326ms, max 590ms다. 전환 구간(10:34:19~10:35:06)의 35회도 실패 0이다.

## 4. 모니터링 mount 업그레이드 (PR32 문서 절차)
- **Prometheus**: 10:35:59.437Z에 `deploy/deploy.sh monitoring up -d prometheus`를 실행했다.
  - 출력은 `Recreate → Recreated → Starting → Started`다. 컨테이너 ID가 `e8f620c59031`에서 `c4e45ead8892`로 바뀌었다.
  - 10:36:00.220Z에 기동했고 10:36:00.782Z에 `/-/ready`가 응답했다.
- **Grafana**: 10:36:13.563Z에 `deploy/deploy.sh monitoring restart grafana`를 실행했고 10:36:17.054Z에 health ok가 됐다. 같은 컨테이너(2026-11-19 생성)를 재시작했으며, mount(provisioning·dashboards 디렉터리 + `grafana_data`)는 원래대로다.
- **다시 만들지 않은 것**: postgres(2026-11-19T09:45:35Z 기동)와 caddy(09:50:10Z 기동)는 재시작 0회로 그대로다. app-blue는 배포가 만든 컨테이너(10:34:35Z) 그대로다. 볼륨 5개는 그대로다(`caddy_config`, `caddy_data`, `grafana_data`, `postgres_data`, `prometheus_data`).
- **DatasourceError 관측**: Grafana 로그 10:36:00.009Z에 `Failed to evaluate rule … attempt=1 max_attempts=3 next_attempt_in=981ms … lookup prometheus … dial tcp`가 있다. 재생성 순간의 이름 조회 실패다.
  - 10:35:30~10:38:30Z 로그에는 그 뒤 실패, 상태 전이, 전송 줄이 없다. 재시도에서 평가가 성공한 것으로 판단한다.
  - Grafana receiver의 `lastNotifyAttempt`는 재시작으로 메모리 값이 초기화(`0001-01-01`)돼 전송 여부의 근거로 쓸 수 없다.
  - 경보를 끄거나 숨기지 않았다. 운영 Discord 채널 화면은 확인하지 못했다.

## 5. 업그레이드 뒤 검증 (10:37:01~10:37:42Z)

| 항목 | 관측 |
|---|---|
| Prometheus mount | `bind:/opt/gongchae-bell/deploy/monitoring/prometheus -> /etc/prometheus` 디렉터리 + 볼륨 `gongchae-bell-prod_prometheus_data -> /prometheus`. healthy, 재시작 0 |
| 설정 일치 | host `prometheus.yml`와 컨테이너가 같은 inode `309446`, sha `b7127f95…`. 규칙 host·컨테이너 sha `70438475…`(Git과 같음). 적재 설정 job은 `gongchae-app` |
| 규칙 | **9개 health ok**: AlertsBacklog, AlertsFailedHigh, AlertsUncertain, ApiBudgetStopped, ApiBudgetWarning, AppMetricsDown, Http5xxHigh, RegularCollectionFailing, RegularCollectionStale |
| targets | `app-blue:8081` up(활성), `app-green:8081` down(비활성) |
| 경보 | Prometheus `alerts=[]`. Grafana 전달 규칙 `Normal (NoData)`·health ok, Grafana 경보 0 |
| 대시보드 | Grafana `공채벨 운영` version 2, 패널 25, target 35. 같은 35개 쿼리를 `$__range→1h`로 바꿔 운영 Prometheus에 직접 돌렸고 **35/35 success**(`stub.py`와 같은 판정) |
| TSDB 보존 | 2일치 `up` sample이 재생성 전 2,964·2,965(10:35:59Z)에서 재생성 뒤 2,966·2,967(10:37:02Z)로 이어졌다. 가장 오래된 sample 시각은 1795082034(2026-11-19T09:53:54Z, 최초 설치 직후)로 남아 있다. 데이터가 실제로 유지됐다는 근거이며, 볼륨 이름만 같다는 확인과는 다르다. head 시리즈 1,568 → 2,054(M2·새 앱 지표) |
| DB 불변 | 공고 36,879, 지문 `c92183c2…`, `api_attempt` 37, `collect_run` 40, 구독·발송 0, `api_budget` `2026-11-19=37`, Flyway 5. 모두 기준선과 같다. app-blue 로그는 `Schema "public" is up to date`이고 ALERT·HEARTBEAT·COLLECTION·WARN·ERROR 줄 0 |
| 상태·플래그 | blue `4cc6e4e`, pending none, `DEPLOY_OK`, `COLLECTOR_ENABLED=false`, `ALERTS_ENABLED=false` |
| 공개 | `/`, `/jobs`, `/jobs/306337`, `/robots.txt`, `/sitemap.xml`, `/privacy`, `/subscriptions/new` 200. `/actuator/health` 404. 보안 헤더 5종 있음 |
| 포트 | 서버 리스너는 80·443(IPv4/6)과 `127.0.0.1:3000`이다. 외부에서 3000·5432·8081·9090은 시간 초과 |
| 자원(1회) | app-blue 391MiB, postgres 168MiB, grafana 139MiB, prometheus 39MiB, caddy 15MiB. 디스크 `/` 7.8G 사용, 37G 여유 |

## 직접 확인과 미확인
- **직접 확인**: 위 표의 서버·API·DB·로그 값, CI·Deploy run과 G4 아티팩트, 공개 HTTPS, 외부 포트.
- **미확인**:
  - 운영 Discord 채널 화면(DatasourceError가 실제로 없었는지, 전날 훈련 메시지)
  - Grafana UI 화면 자체(API로만 확인)
  - 업그레이드 구간의 로컬 참고 측정(로컬 프로브는 10:35:21Z에 끝났다. 업그레이드는 앱 경로를 바꾸지 않았고 10:37Z 공개 화면 200으로 대신했다)
  - dispatch가 덮어쓴 이전 digest `b0dfc29b…` 이미지가 GHCR에 남아 있는지

## 남은 게이트
- 자동 배포: `AUTO_DEPLOY_ENABLED`는 미설정 그대로다. 권한 분류기가 변수 변경을 거부한 기록도 해소되지 않았다.
- 경보: 디렉터리 mount가 반영됐으므로 `prometheus.yml`을 고치는 수집 경보 훈련(PR33 계열)의 B1 전제가 충족됐다. 실제 훈련은 이번에 하지 않았다.
- 백업: restic·타이머·버킷·IAM·PC pull은 아직 설치 전이다(`agy-runtime-backup-recheck-result.md`).
- dispatch가 같은 태그를 다시 빌드하는 문제(이전 결과의 관찰)는 그대로다. 이번에도 `b0dfc29b…`에서 `76df7ffc…`로 바뀌었다.
