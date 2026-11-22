# 검수 main `e0208ff` 운영 배포·제한 성능 실측 결과 (#14·#18·#19) · Claude

2026-11-22 root Codex dispatch `msg_bb2df64e4d89`를 수행한 기록이다. 기록 worktree는 `nov22-runtime` / `docs/#14-nov22-runtime`(main `e0208ff` 기반)이다. 시각은 UTC이고 KST는 +9시간이다. token, 계정, 전체 env, PII는 적지 않았다.

## 요약

| 항목 | 결과 |
|---|---|
| target | `e0208ffd16c39b511cc0065dc8ee40e5c8b4f9c1`(PR39 병합, PR34·35·36·37 포함). 작업하는 동안 main은 바뀌지 않았다 |
| target CI | CI `45001067523`(test·deploy-verify·backup-verify·secrets), Monitoring `45001067454`(rules·compose-integration·alert-e2e) **7잡 success** |
| 발행 이미지 | workflow_run Deploy `45001298566`(`RUN_SHA=e0208ff`) Build & Push success, 배포 잡 skipped. digest `sha256:4ec3df24e590f5b79db45312b58f3108212daf425d8101a64411c4dfce769e1a`, 익명 manifest HEAD 200 |
| 배포 | 지정 명령 그대로 단독 `workflow_dispatch` Deploy [`45001647801`](https://github.com/Lcjam/gongchae-bell/actions/runs/45001647801)를 요청했고 허용됐다. `bluegreen-e0208ffd16c3-45001647801-1`, blue `4cc6e4e` → **green `e0208ff`**, `DEPLOY_OK` |
| 운영 이미지 | dispatch가 같은 태그를 다시 빌드했다. **실제 운영 digest는 `sha256:5f80098b29b51fe6e5f759241a66e6532a645b8690a81af417355d4cb2d264f9`**(컨테이너 image ID와 일치)다 |
| 외부 G4 | **28요청, 실패 0**, 5xx·연결/TLS·시간 초과·기타 0, p95 934ms, max 945ms(GitHub 러너 AWS us-east-1) |
| 공개 CSS | 공개 `/css/app.css` sha256 `87C7622B17511F4DFB025CAD83545EE27793F48AEFB365A3462CB4E1DA4A254A`로 **기대값과 같다** |
| 제한 실측 | 운영자 PC에서 **S1 200/200, S2 1,500/1,500 성공, 오류 0**. 수치는 아래. **Windows 시계 해상도 15.6ms 한계가 있다** |
| 호스트 자원 | 배포·측정 두 구간 모두 경고 0, 재시작 0. 두 앱 컨테이너가 함께 관측된 구간에서 호스트 CPU의 5초 평균 최댓값은 98.5%(08:13:30.133~35.133Z), MemAvailable 최소 10,036MiB |
| 관찰 | 로컬 참고 프로브(G4 아님) 115요청 중 **1건이 TLS 연결 오류(curl rc=35)**였다. 기록된 UTC 08:13:36Z는 두 앱 컨테이너가 함께 관측된 기동 구간 안이다. 그 시각을 포함하는 5초 구간(08:13:35.133~40.133Z)의 평균 CPU는 65.1%, 직전 5초 평균은 98.5%다. 오류 순간의 CPU, 클라이언트와 서버의 시계 차이, 오류 원인은 미확인이다. CI G4 28/28과 S1·S2 오류 0과는 별개 관측이다 |
| 바꾸지 않은 것 | `AUTO_DEPLOY_ENABLED`(미설정), 권한, collector·alerts=false, DB, 모니터링 컨테이너, 실제 수집·발송, PR33 훈련, 백업 |

## 1. 실행 전 gate와 기준선
- `4cc..e020`의 배포 관련 파일 변경: `compose.prod.yml`, `compose.monitoring.yml`, `deploy.sh`, `g4-probe.sh`, Caddy, `deploy.yml`, Flyway, `application.yml`, `deploy/monitoring` **0**.
- 앱 변경: `app.css`·`layout.html`(PR39), `ErasureJournal`·`ErasureReapplyCommand`(PR34). 서버 `deploy/`에는 `deploy/perf/`(PR35)가 추가된다.
- 앞서 끝난 workflow_run Deploy 두 개는 다른 SHA였다: `45001200739`(`RUN_SHA` b6c9467, PR36)와 `45001249975`(`RUN_SHA` e90b093, PR35). 이 둘은 target 증거로 쓰지 않았다.
- 기준선(08:09:34Z, 읽기 전용): blue `4cc6e4e`(image ID `76df7ffc…`, 재시작 0), prev green `32e71e4`, upstream blue, pending none, `DEPLOY_OK`.
  - 컨테이너 5개, 남은 drill 컨테이너 없음.
  - `COLLECTOR_ENABLED=false`, `ALERTS_ENABLED=false`
  - DB: 공고 36,879(지문 md5 `c92183c2eea0644cac25ccae5a1430da`), `api_attempt` 37, `collect_run` 40, 구독·발송 0, `api_budget` `2026-11-19=37`, Flyway 5
  - Prometheus: 디렉터리 mount, 규칙 9개 health ok, 경보 0. 디스크 `/` 18%

## 2. 배포 + host sampler
- **sampler**: e020의 `deploy/perf/sample-resources.sh`(git sha256 앞 16 `98a3d0032d0f69ac`)를 서버 `~/gongchae-ops/perf-e020/`에 따로 두고 실행했다. 운영 `deploy/`는 건드리지 않았다.
  - 5초 간격, PID `1134020`, 08:11:14Z 시작, stop-file로 08:14:42Z 종료
  - 42표본, 건너뜀 0, 경고 0

| UTC | 사건 |
|---|---|
| 08:11:38 | dispatch run 생성 |
| 08:11:44~08:12:48 | Build & Push(Azure westus3). `INPUT_SHA=e0208ff…`. 같은 태그 재빌드, digest `4ec3df24…`에서 `5f80098b…`로 바뀜 |
| 08:12:53 | 배포 잡(AWS us-east-1, `ip-10-0-138-209`) |
| 08:13:03 | `mode=bluegreen` |
| 08:13:06 | 외부 G4 프로브 시작 |
| 08:13:19 | `Starting bluegreen switch … active=blue(4cc6e4e) -> target=green(e0208ff)` |
| 08:13:41 | `Internal readiness and key routes passed for app-green`, Caddy 설정 reload(로그 08:13:41.96) |
| 08:13:42 | `SWITCH_OK … active_kept=blue serving=green` |
| 08:13:57 | `G4_PASS … total=28 ok=28 failures=0 p95_ms=934 max_ms=945` |
| 08:13:59~08:14:00 | finalize: blue 정지·제거 |
| 08:14:00 | `DEPLOY_OK color=green sha=sha-e0208ff… prev_color=blue prev_sha=sha-4cc6e4e…` |

`g4-probe-report` 원자료:

```json
{"deploy_id":"bluegreen-e0208ffd16c3-45001647801-1","execution_context":"github-actions-external","runner_host":"ip-10-0-138-209","base_url":"https://gongchae.enjoylcjworld.com","started_at":"2026-11-22T08:13:06Z","finished_at":"2026-11-22T08:13:57Z","interval_sec":1,"connect_timeout_sec":3,"max_time_sec":5,"total_requests":28,"ok_requests":28,"failures":0,"http_5xx":0,"connect_tls_errors":0,"timeouts":0,"other_non_200":0,"p95_latency_ms":934,"max_latency_ms":945}
```

**두 JVM 동시 실행 구간**(sampler 원자료 `collab/2026-11-22-perf/sampler-deploy/`): app-blue와 app-green이 함께 잡힌 표본은 08:13:30~08:13:55Z의 6개다.

| 5초 표본 끝 | 호스트 CPU busy(2 vCPU 전체) | load1 | MemAvailable |
|---|---|---|---|
| 08:13:25 | 11.5% | 0.40 | 10,420MiB |
| 08:13:30 | 51.5% | 0.60 | 10,307MiB |
| 08:13:35 | **98.5%** | 0.80 | 10,114MiB |
| 08:13:40 | 65.1% | 0.73 | 10,052MiB |
| 08:13:45 | 44.1% | 0.67 | **10,036MiB**(최소) |
| 08:13:50 | 10.7% | 0.62 | 10,066MiB |

- 컨테이너 최대값(`docker stats` 한 번 표본, CPU는 코어 기준 원문):
  - app-green CPU 193.08%(2 vCPU 기준 약 96.5%, JVM 기동), cgroup 메모리 378.5MiB
  - app-blue 462.1MiB, postgres 184.0MiB, grafana 121.1MiB, prometheus 41.5MiB, caddy 16.4MiB
- 디스크 18%, RestartCount 증가 0, 경고 0.
- cgroup 메모리는 RSS가 아니다(도구 주석과 같은 의미). 표본 간격이 5초이므로 순간 최댓값은 놓쳤을 수 있다.

## 3. 배포 뒤 검증 (08:14:59~08:15Z)
- `deploy.sh status`: `active_color=green`, `active_sha=sha-e0208ff…`, `active_deploy_id=bluegreen-e0208ffd16c3-45001647801-1`, prev blue `4cc6e4e`, upstream green, pending none, `DEPLOY_OK`.
- app-green image ID `sha256:5f80098b…`, 재시작 0. 앱 로그는 Flyway `Current version … 5` / `Schema "public" is up to date`이고 ALERT·HEARTBEAT·COLLECTION·ERASURE·WARN·ERROR 줄 0.
- DB는 기준선과 같다(공고 36,879, 지문 `c92183c2…`, `api_attempt` 37, `collect_run` 40, 구독·발송 0, `api_budget` 그대로, Flyway 5). 플래그도 둘 다 false 그대로다.
- 모니터링: Prometheus는 재생성하지 않았다(2026-11-20T10:36:00Z 기동 그대로, 디렉터리 mount). 대상은 app-green up·app-blue down, 규칙 9개 health ok, 경보 0. Grafana도 기동 그대로다.
- 서버 `deploy/perf/` 동기화를 확인했다(`sample-resources.sh` hash가 실행본과 같음).
- 공개 화면(내 PC):
  - `/`, `/jobs`, `/jobs/306769`, `/institutions/C0066`, `/robots.txt`, `/sitemap.xml`, `/privacy`, `/subscriptions/new` 200. `/actuator/health` 404.
  - `/jobs`에 `site-header`·`본문 바로가기`·`site-footer`가 각 1개 있다.
  - **알림 비활성 조건**: `site-nav-cta` 0, `/subscriptions/new?` 0, `이 조건으로 알림 받기 (준비 중)` 1. 지연 배너 0(현재 지연 아님).
  - `POST /subscriptions/email`은 503(알림 꺼짐 계약).
  - 보안 헤더 5종(HSTS, CSP, X-Frame-Options, X-Content-Type-Options, Referrer-Policy)이 있다.
- 포트: 서버 리스너는 80·443(IPv4/6)과 `127.0.0.1:3000`이다. 외부에서 3000·5432·8081·9090은 시간 초과.
- **로컬 참고 프로브**(내 PC, `/`·`/jobs` 1초 간격, G4를 대신하지 않음): 08:11:32~08:14:13Z 115요청 중 비정상 1건이다. 나머지는 p95 301ms, max 380ms.
  - 그 1건은 `08:13:36Z GET / → curl rc=35(SSL connect error), 0.11초`다.
  - 시점: 기록된 UTC로는 두 앱 컨테이너가 함께 관측된 기동 구간(sampler 표본 28~33, 08:13:30.133~08:13:55.133Z) 안이고, Caddy upstream reload(08:13:41.96) 전이다.
  - 그 시각을 포함하는 5초 구간 08:13:35.133~40.133Z의 호스트 CPU 평균은 65.1%(65.12563%)다. 직전 5초 구간 08:13:30.133~35.133Z의 평균은 98.5%(98.49699%)다. 둘 다 `/proc/stat` 누적값 차이로 낸 5초 평균이라 오류 순간의 CPU가 아니다. 내 PC와 서버의 시계 차이(offset)도 재지 않았다. 따라서 CPU 포화를 이 오류의 원인으로 보지 않는다.
  - 그 구간 Caddy 로그에는 200 응답 34건뿐이고 대응하는 오류 줄은 없다. 같은 구간 CI G4(us-east-1)는 실패 0이다.
  - 클라이언트(Windows schannel·가정망) 쪽인지 서버 TLS 처리 쪽인지는 **확인하지 못했다**. 이 1건을 무중단 실패로 단정하지 않는다. 다만 성공으로 지우지도 않는다.

## 4. 제한 성능 실측 (PR35 도구, 운영자 PC = OCI VM 밖)
- 도구는 main `e0208ff`의 `deploy/perf/perf-probe.py`(워밍업 안전 중단 수정 포함)다. 클라이언트는 한국 가정망 Windows Python 3.12다.
  - 모든 요청에 `X-Gongchae-Internal: 1`, `gongchae-probe/perf-18`을 붙였다. 재시도 없음, 본문 미저장, stop-file 준비.
- **manifest**(`collab/2026-11-22-perf/urls.tsv`): 공개 `/jobs` HTML의 GET 결과로 실존과 적용을 확인한 17개다.
  - home 1, list 1
  - filter 5: `career=newcomer` 422건, `region=R3018&ncs=R600014&status=open` 5건, `q=채용&status=all` 31,227건, `status=all&page=10`, `sort=begin` 462건
  - detail 5: sn 306769·306805·306740·306783·306791
  - institution 5: C0066·C0059·C1055·C0210·C0306
- preflight-only(08:15~08:16Z 사이, 정확한 시각은 기록하지 않음): `PREFLIGHT_OK` 17/17, RTT min 47 / median 63 / max 234ms. 첫 요청 뒤 연결 재사용.
- host sampler(측정 구간): PID `1139210`, 08:16:52~08:18:28Z, 19표본, 건너뜀 0, **경고 0**, 재시작 0. 원자료는 `collab/2026-11-22-perf/sampler-perf/`다.

| 프로필 | 실행(UTC) | 상태 | 워밍업 | 측정 n / 성공 / 오류 | 측정 시간 | 전체 p50 / p95 / p99 / max(ms) | 연결 재사용 |
|---|---|---|---|---|---|---|---|
| S1 (C=1, 화면군당 40) | 08:16:55~08:17:11 | COMPLETED | 25 | 200 / 200 / 0 | 13.52s | 62 / 94 / 281 / 296 | 200/200, 재연결 0 |
| S2 (C=4, 상한 1,500) | 08:17:38~08:18:08 | COMPLETED(요청 상한 도달) | 100 | 1,500 / 1,500 / 0 | 27.09s | 62 / 125 / 344 / 437 | 1,500/1,500, 재연결 0 |

화면군별 성공 p95(nearest-rank, ms):

| 화면군 | S1 n=40 p50 / p95 / max | S2 n=300 p50 / p95 / max |
|---|---|---|
| home | 47 / 63 / 63 | 47 / 63 / 94 |
| list | 62 / 63 / 63 | 62 / 79 / 125 |
| filter | 63 / 281 / 296 | 78 / 344 / 437 |
| detail | 47 / 63 / 63 | 47 / 63 / 79 |
| institution | 63 / 79 / 79 | 63 / 79 / 110 |

- 케이스 중 p95가 가장 큰 것은 S2 `filter/keyword_all`(`q=채용&status=all`, n=60) p95 391ms·max 437ms, 다음이 `filter/deep_page_all`(n=60) p95 156ms다. S1 케이스는 n=8이라 p95를 내지 않았다(n·max만).
- 측정 구간 호스트(5초 표본):
  - CPU 범위는 **각 프로필의 측정 단계에 완전히 들어가는 5초 구간만** 셌다. 측정 시작 = `finished_utc − measure_elapsed_sec`이고, 워밍업과 경계에 걸친 구간은 뺐다.
    - S1(측정 08:16:57.418~08:17:10.938Z): 두 구간 25.1%·25.0%
    - S2(측정 08:17:41.166~08:18:08.256Z): 다섯 구간 56.7~72.9%(56.7·69.0·72.9·72.3·68.4)
    - 처음 기록의 "S1 24~25%"는 측정 끝을 넘는 경계 구간(08:17:08.056~13.056, 24.4%)까지 넣은 값이라 고쳤다.
  - load1 최대 0.89, MemAvailable 최소 10,490MiB, 디스크 18%는 측정용 sampler **전체 창**(08:16:53~08:18:23Z, 19표본) 기준이다.
  - 컨테이너 최대: postgres CPU 원문 89.9%(코어 기준), app-green 55.0%·cgroup 426.2MiB, caddy 11.0%
- **부하 총량**: preflight 17 + S1(preflight 17 + 225) + S2(preflight 17 + 1,600)로 약 1,900요청이다. 부하 시간은 S1 약 16초 + S2 약 30초 + preflight로 15분 상한 안이다. 배포 중 로컬 참고 프로브 115요청은 1초 간격 관측이라 별도로 센다.
- **측정의 한계(중요)**:
  1. **Windows 시계 해상도.** 도구는 지연을 `time.monotonic()` 차이로 잰다. S1 측정값 200개는 13가지 값(46·47·62·63·78·…)으로 양자화됐다. 위 p50·p95·max는 **15.625ms 해상도로 양자화된 값**이다. 이 해상도는 오차 범위를 검증한 정확도가 아니다.
     - **측정 당시 근거**(S1과 S2 사이, 같은 PC·같은 `python`). 파일로 저장하지 않았고, 작성자 세션 기록의 터미널 출력 원문을 옮긴다:
       `monotonic GetTickCount64() 0.015625` / `perf_counter QueryPerformanceCounter() 1e-07` / `distinct elapsed values: 13`
     - **사후 조회**(2026-11-22T08:37:29Z, 같은 PC, 측정 당시 출력이 아님): `python 3.12.10`, `platform Windows-11-10.0.26300-SP0`, monotonic·perf_counter 출력은 위와 같다.
     - 도구를 `perf_counter`로 바꿀지는 root 배정 대상이고 이번에는 하지 않았다.
  2. closed-loop 응답 시간이다. 포화 도착률이나 처리량이 아니다.
  3. 한 장소(한국 가정망)에서 1회씩 잰 값이다.
  4. `COLLECTOR_ENABLED=false` 상태다. **수집 중 성능이 아니다.**
  5. G4 수치(GitHub 러너, 배포 중)와 섞지 않는다.
  6. 정책, SLO, 목표값은 정하지 않았다.

## 5. 정리
- 서버의 sampler 두 개는 stop-file로 정상 종료했다. 남은 sampler 프로세스는 없다. 원자료를 내려받은 뒤 **내가 만든 `~/gongchae-ops/perf-e020/`만 지웠다**. 이전 `backfill-initial-*` 기록은 그대로다.
- 원자료 중 Windows에서 쓴 8개(S1·S2의 `requests.csv`·`summary.json`·`summary.md`·`conditions.md`)는 CRLF였다. 저장소 `.gitattributes`(`* text=auto eol=lf`)에 따라 커밋 때 줄끝만 LF로 바뀌었고 내용은 같다.
- 로컬 프로브도 stop-file로 멈췄다. 운영 `.env`, 키, 계정, 다른 브랜치, 사용자 frontend 원본은 건드리지 않았다.
- (2라운드 보존) 배포 중 로컬 참고 프로브 원자료 115행을 `collab/2026-11-22-perf/local-reference-probe.csv`로 보존했다(열: UTC, 경로, HTTP 코드, curl `time_total`, curl rc). 내부 경로·비밀값·개인정보는 없다.
- standalone preflight-only 실행은 요약값(`PREFLIGHT_OK`, 17/17, RTT 47/63/234ms, 첫 요청 뒤 재사용)만 출력하도록 파이프로 걸러 원문 JSON을 **저장하지 않았다(미보존)**. S1·S2 각 실행의 preflight 결과는 각 `summary.json`에 있다.

## 직접 확인 / 미확인 / 남은 것
- **직접 확인**: 위의 CI·이미지·배포 run·G4 아티팩트, 서버 상태·DB·로그·모니터링, 공개 화면·CSS hash·헤더·포트, sampler 원자료, S1·S2 원자료(`collab/2026-11-22-perf/S1`·`S2`의 `requests.csv`·`summary.json`·`summary.md`·`conditions.md`).
- **미확인**:
  - 08:13:36Z TLS 오류 1건의 원인
  - 브라우저 렌더 화면(이번에는 HTML·CSS hash로 확인)
  - Discord 채널
  - dispatch가 덮어쓴 이전 digest `4ec3df24…` 이미지가 GHCR에 남아 있는지
- **남은 게이트**: `AUTO_DEPLOY_ENABLED` 미설정 유지, 수집 활성화와 수집 중 실측, 실패 G4 훈련, PR33 수집 경보 훈련, 백업 설치, 성능 측정 도구의 시계 해상도 개선 여부, dispatch 같은 태그 재빌드 문제.

## 기록 PR
- draft PR https://github.com/Lcjam/gongchae-bell/pull/40
  - 첫 제출 head `76f8408`(이 결과, 원자료, CHANGELOG): CI `45002373441`(test·deploy-verify·backup-verify·secrets)과 Monitoring `45002373419`(rules·compose-integration·alert-e2e) 7잡 모두 success
  - 2라운드 검수 수정(Sol P2·P3, 원자료 숫자 불변)과 최종 head·CI는 [`claude-pr40-review-fixes-result.md`](claude-pr40-review-fixes-result.md)에 있다
