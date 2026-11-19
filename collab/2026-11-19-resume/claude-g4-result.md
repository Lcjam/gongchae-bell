# 외부 G4 blue/green·롤백 실측 (#14) · Claude

2026-11-19 root Codex 3라운드 배정 `collab/2026-11-19-resume/claude-g4-narrow-request.md`를 수행한 기록이다. 앞 기록은 [`claude-runtime-result.md`](claude-runtime-result.md), [`claude-runtime-round2.md`](claude-runtime-round2.md)다. 시각은 UTC이고 괄호 안은 KST다.

## 요약

| 시험 | Deploy run | deploy_id | 외부 G4 (GitHub 러너) | 결과 |
|---|---|---|---|---|
| blue/green `836a3c0` → `517eb6b` | [`44504907458`](https://github.com/Lcjam/gongchae-bell/actions/runs/44504907458) | `bluegreen-517eb6b11b06-44504907458-1` | **45회, 실패 0**, p95 588ms, max 633ms | `DEPLOY_OK` |
| 롤백 `517eb6b` → `836a3c0` | [`44505579286`](https://github.com/Lcjam/gongchae-bell/actions/runs/44505579286) | `rollback-32e71e4bb56c-44505579286-1` | **24회, 실패 0**, p95 1,194ms, max 1,407ms | `ROLLBACK_OK` |

- 두 호출 모두 **지정된 단독 명령 그대로** 한 번씩 요청했고 허용됐다. 권한 규칙·모드는 바꾸지 않았다.
- `AUTO_DEPLOY_ENABLED`는 바꾸지 않았다(Variables에는 `SITE_DOMAIN`만 있음). 자동 배포 스위치 변경의 거부는 해소되지 않았다. 이번에 허용된 것은 일회성 `workflow_dispatch` 두 건뿐이다.
- 운영은 롤백 뒤 blue `836a3c0`이다. `COLLECTOR_ENABLED=false`, `ALERTS_ENABLED=false` 그대로다.

## 실행 전 확인 (10:21:22Z, 19:21:22)

- main은 PR23 병합 `48f6878cc57230bedd1693003b0e82eb9c8ae287`이었다. `.github/workflows/deploy.yml`, `deploy/deploy.sh`, `deploy/g4-probe.sh`가 `517eb6b`와 같았다(`git diff` 0줄). 배포 잡은 대상 SHA로 checkout하므로 main에서 바뀐 `compose.prod.yml`(PR23)은 쓰이지 않는다.
- PR23 병합의 workflow_run Deploy `44504564676`은 Build & Push success, 배포 잡 skipped였다(자동 배포 꺼짐).
- 서버 상태:
  - `active_color=blue`, `active_sha=sha-836a3c0…`, `active_deploy_id=bootstrap-836a3c0af062-manual-20261119`, `prev=none`, `pending=none`
  - 서버 `compose.prod.yml` sha256 `f2619f2c…`, `deploy.sh` `5b38284b…`
- `sha-517eb6b…` 태그의 실행 전 digest는 `sha256:c750129c022a0a2967e51ae67de1d0cd04442e7550c9b10c2c4c6924eb7b2305`였다.

## 1. blue/green 배포 G4

요청 명령(10:21:31Z, 19:21:31 KST, 허용됨):

```text
gh workflow run Deploy -R Lcjam/gongchae-bell --ref main -f action=deploy -f image_sha=517eb6b11b06a15aebeb7c0d3c94662eacd62ea7
```

| UTC | 사건 |
|---|---|
| 10:21:37~10:22:37 | Build & Push. `INPUT_SHA=517eb6b…`. **같은 태그를 다시 빌드해 덮어씀**: 새 digest `sha256:8fbb95543b7bd532083f7839bdb341f701ddeceafdbc877454d9bab0622445ef` |
| 10:22:43 | 배포 잡 시작. 러너 `ip-10-0-137-45`(AWS us-west-2, OCI 밖). checkout `ref: 517eb6b…` |
| 10:22:54 | 원격 `deploy.sh status`가 `active_color=blue`이므로 `mode=bluegreen` |
| 10:22:57 | 외부 G4 프로브 시작(기준선 10초) |
| 10:23:08 | `Starting bluegreen switch … active=blue(836a3c0) -> target=green(517eb6b)` |
| 10:23:48 | `Internal readiness and key routes passed for app-green` |
| 10:23:49 | `SWITCH_OK … active_kept=blue serving=green` |
| 10:24:04 | `G4_PASS … total=45 ok=45 failures=0 p95_ms=588 max_ms=633` |
| 10:24:06~10:24:07 | finalize: 구 색 blue 정지·제거(grace 40초 중 실제 0.6초) |
| 10:24:07 | `DEPLOY_OK color=green sha=sha-517eb6b… prev_color=blue prev_sha=sha-836a3c0…` |

`g4-probe-report` 아티팩트:

```json
{"deploy_id":"bluegreen-517eb6b11b06-44504907458-1","execution_context":"github-actions-external","runner_host":"ip-10-0-137-45","base_url":"https://gongchae.enjoylcjworld.com","started_at":"2026-11-19T10:22:57Z","finished_at":"2026-11-19T10:24:04Z","interval_sec":1,"connect_timeout_sec":3,"max_time_sec":5,"total_requests":45,"ok_requests":45,"failures":0,"http_5xx":0,"connect_tls_errors":0,"timeouts":0,"other_non_200":0,"p95_latency_ms":588,"max_latency_ms":633}
```

배포 뒤 서버(10:25:22Z):
- `active_color=green`, `active_sha=sha-517eb6b…`, `active_deploy_id=bluegreen-517eb6b11b06-44504907458-1`, `prev_color=blue`, `prev_sha=sha-836a3c0…`, `pending=none`, `last_deploy_result=DEPLOY_OK`
- `upstream.caddy`는 `reverse_proxy app-green:8080`이다.
- 실행 중 이미지 RepoDigest는 `@sha256:8fbb9554…`로 새 빌드와 같다.
- 서버 `compose.prod.yml`·`deploy.sh` 해시는 변함없다(517eb6b와 836a3c0의 배포 정의가 같음).
- Prometheus 대상은 green up·blue down이고 공개 `/`, `/jobs`, `/robots.txt`는 200이다.

## 2. 롤백 G4

요청 명령(10:25:48Z, 19:25:48 KST, 배포 성공을 확인한 뒤 별도 요청, 허용됨):

```text
gh workflow run Deploy -R Lcjam/gongchae-bell --ref main -f action=rollback
```

- 요청 직전 main이 PR26 병합 `32e71e4bb56c9137cb12f9b49088a66d949f988a`로 움직였다. 그래서 이 run의 workflow 파일과 러너 checkout은 `32e71e4`다.
  - `deploy.yml`·`deploy.sh`·`g4-probe.sh`가 `517eb6b`와 같음을 확인했다.
  - 롤백 모드는 서버 파일 동기화를 하지 않는다(`deploy.yml`). 그래서 서버에서 돈 것은 기존 `deploy.sh`(`5b38284b…`)다.
  - Build & Push는 설계대로 skipped였다.

| UTC | 사건 |
|---|---|
| 10:25:53 | 배포 잡 시작. 러너 `runnervmeozm8`(Region centralus, OCI 밖) |
| 10:25:58 | 원격 상태 `last_deploy_result=DEPLOY_OK`, `mode=rollback`. G4 프로브 시작 |
| 10:26:10 | `Starting rollback switch … active=green(517eb6b) -> target=blue(836a3c0)`. app-blue 새로 기동 |
| 10:26:24 | blue 내부 readiness·핵심 경로 통과(기동 뒤 13초), `SWITCH_OK … serving=blue` |
| 10:26:41 | `G4_PASS … total=24 ok=24 failures=0 p95_ms=1194 max_ms=1407` |
| 10:26:43~10:26:44 | finalize: green 정지, `ROLLBACK_OK color=blue sha=sha-836a3c0… stopped_color=green` |

`g4-probe-report` 아티팩트:

```json
{"deploy_id":"rollback-32e71e4bb56c-44505579286-1","execution_context":"github-actions-external","runner_host":"runnervmeozm8","base_url":"https://gongchae.enjoylcjworld.com","started_at":"2026-11-19T10:25:58Z","finished_at":"2026-11-19T10:26:41Z","interval_sec":1,"connect_timeout_sec":3,"max_time_sec":5,"total_requests":24,"ok_requests":24,"failures":0,"http_5xx":0,"connect_tls_errors":0,"timeouts":0,"other_non_200":0,"p95_latency_ms":1194,"max_latency_ms":1407}
```

롤백 뒤 서버(10:27:34Z):
- `active_color=blue`, `active_sha=sha-836a3c0…`, `active_deploy_id=rollback-32e71e4bb56c-44505579286-1`, `prev_color=green`, `prev_sha=sha-517eb6b…`, `pending=none`, `last_deploy_result=ROLLBACK_OK`
- upstream은 `app-blue:8080`이고 실행 이미지는 `sha256:17ffb078…`(bootstrap 때와 같은 836a3c0 이미지)다.
- Prometheus는 blue up·green down, 경보 0이다. 공개 `/`, `/jobs`, `/robots.txt`는 200이다.

## 참고: 독립 교차 측정 (내 PC, 한국 가정망)

CI G4를 대신하지 않는 참고값이다. 같은 공개 URL을 `/`·`/jobs` 번갈아 1초 간격으로 조회했고 `curl --connect-timeout 3 --max-time 5`, 읽기 전용이다.

| 구간 | 요청 | 비200·curl 오류 | p95 | max | 전환 구간 안 |
|---|---|---|---|---|---|
| 배포 10:22:09~10:25:29Z | 144 | 0 | 296ms | 527ms | 10:23:08~10:24:08 44회, 실패 0 |
| 롤백 10:25:44~10:27:01Z | 56 | 0 | 410ms | 478ms | 10:26:10~10:26:45 25회, 실패 0 |

## 관찰과 root 확인 요청

1. **롤백 deploy_id의 SHA가 롤백 대상이 아니다.** `deploy.yml`은 롤백에서 `TARGET_SHA=github.sha`를 쓴다. 그래서 id가 workflow를 실행한 main HEAD(`32e71e4`)로 지어졌다. 실제 대상은 `836a3c0`이다. 상태 파일의 `active_sha`는 맞다. 기록을 읽는 사람이 헷갈릴 수 있어 워크플로 수정 여부를 root가 정한다(앱·워크플로 source는 바꾸지 않았다).
2. **dispatch는 이미지를 다시 빌드한다.** `sha-517eb6b` 태그 digest가 `c750129c…`에서 `8fbb9554…`로 바뀌었다. 같은 커밋 태그가 가리키는 이미지가 바뀌므로 G4 기록에는 digest를 함께 적는다. 런북에 이 동작을 적을지는 root가 정한다.
3. 롤백 G4의 p95(1,194ms)는 배포 G4(588ms)보다 높다. 러너 지역이 다르고(centralus, us-west-2), blue가 막 기동한 JVM이었다. 각 1회 측정이라 원인을 가르지 않았다. 같은 시각 한국 측정 p95는 410ms였다.
4. 롤백 run 동안 PR26 병합의 workflow_run Deploy `44505638505`가 시작됐다. 자동 배포가 꺼져 있어 배포 잡은 skipped였다(아래 "자동 run 확인").

## 자동 run 확인

PR26 병합 `32e71e4`의 workflow_run Deploy `44505638505`은 Build & Push success(10:27:57Z), 배포 잡 **skipped**(10:27:58Z)였다. 10:28:56Z 서버는 롤백 직후와 같았다. `active_color=blue`, `active_sha=sha-836a3c0…`, `pending=none`, `ROLLBACK_OK`이고 실행 중인 앱은 `app-blue`(`sha-836a3c0…`) 하나다. 미검수 상태의 main이 자동으로 운영에 나가지 않았다.

## 미검증·범위 밖

- `AUTO_DEPLOY_ENABLED` 변경: 하지 않았다. 거부 기록은 그대로다.
- 실패 유도 롤백(`G4_FAIL` → abort 경로): 하지 않았다. 이번 롤백은 사후 롤백(`action=rollback`) 경로다.
- 최신 main `32e71e4`(PR23 알림 워커·PR26) 운영 배포, 정기 수집 활성화, 실제 사용자 메일: 범위 밖
