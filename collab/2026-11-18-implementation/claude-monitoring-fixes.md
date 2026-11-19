# PR21 모니터링 수정과 #14 실제 통합 결과 · claude

2026-11-18. 요청은 root Codex의 "Claude 다음 구현·수정 배정" 2번이다. 근거 리뷰는 root `collab/reviews/pr-21-codex.md`(P2 p95), `pr-21-agy.md`(P2 태그 보간, P3 첫 수집 확인, 쟁점 의견)다. 단독 작성자 Claude, 브랜치 `ops/#15-monitoring`, worktree `issue-15-monitoring`.

## SHA

| 항목 | 값 |
|---|---|
| 시작 head | `e0642d1de21799650f069fef7a864e8a94b94473` |
| main(#14 `be30f69`) 병합 | `247ed4b` (충돌 없음) |
| 코드·문서 커밋 | `24ac28b` + `8fcc85e` (아래 "커밋 기록") |
| main(#6 PR13 `ed3f28f`) 병합 | `c36c557` (`.env.example`, `docs/implementation-status.md` 충돌 해소) |
| main(#16 PR22 `52c172c`) 병합 | `010930d`. runbook 충돌은 #15 모니터링 줄과 #16 백업 문서 링크를 모두 유지. 병합된 #16 동작(성공 ping, 실패 `/fail`, 재시도 없음)에 맞춰 모니터링 문서의 백업 heartbeat 행과 구현 현황을 고침. 로컬 `check bootJar` 154 tests, 0 failures |
| CI (`c36c557`) | CI [44283945810](https://github.com/Lcjam/gongchae-bell/actions/runs/44283945810) test(`check bootJar`, ARM64)·deploy-verify(`ALL_DEPLOY_TESTS_PASSED` 16개, Caddy 통합, ARM64 이미지)·secrets 성공. Monitoring [44283945764](https://github.com/Lcjam/gongchae-bell/actions/runs/44283945764) rules(promtool `SUCCESS`)·compose-integration(ARM64, `COMPOSE_INTEGRATION_TEST_PASSED`, 39초)·alert-e2e(발동 104초, 해제 57초) 성공 |
| 시험 보정 | `4d2e4e8`의 Monitoring [44285072148](https://github.com/Lcjam/gongchae-bell/actions/runs/44285072148) `compose-integration`이 1단계에서 실패했다. 두 태그가 모두 비면 Compose가 `APP_BLUE_TAG`와 `APP_GREEN_TAG` 중 어느 쪽을 먼저 보고할지 정해져 있지 않은데(앞 실행은 BLUE, 이번은 GREEN) 시험이 BLUE만 받았다. 둘 다 받도록 고치고 로컬에서 다시 통과했다. 같은 head의 CI [44285072147](https://github.com/Lcjam/gongchae-bell/actions/runs/44285072147)는 test·backup-verify·deploy-verify·secrets 성공 |
| 최종 head | 이 파일을 담은 다음 커밋(시험 보정 포함). PR #21 head로 확인 |
| PR | [#21](https://github.com/Lcjam/gongchae-bell/pull/21) draft 유지, `Refs #15` |

## 1. HTTP p95 히스토그램 상한 (root P2)

- `application.yml`의 `http.server.requests` `maximum-expected-value`를 10s에서 **30s**로 올렸다. 지원하는 가장 긴 요청은 25초 예산의 알림 신청·관리 링크 재발송 POST이고 종료 유예가 30초다.
- 실제 버킷(로컬 scrape): 10초 위로 `10.02, 11.45, 12.88, 14.32, 15.75, 17.18, 22.91, 28.63, 30.0`초와 `+Inf`. 10s 설정에서는 10초 위 유한 버킷이 `10.0` 하나뿐이었다.
- 검증
  - `MonitoringContractIntegrationTest.theP95BucketsHoldTheLongestSupportedRequest`: Spring이 쓰는 같은 미터(이름·태그 키)에 합성 20초 요청 20건을 기록하고 실제 `/actuator/prometheus`를 읽는다. 마지막 유한 버킷 ≥ 25초, `+Inf` 20건, Prometheus 방식 p95가 17초 초과·상한 이하. 설정을 10s로 되돌린 변이에서 `last finite bucket covers the 25 s budget`(10.0 < 25.0)로 실패하고 원복했다.
  - `gongchae.test.yml`에 `promql_expr_test`를 더했다. 대시보드 p95 패널의 쿼리를 **그대로** 실제 버킷 경계 입력에 평가해 20초 요청은 `22.62016109195`, 30초를 넘는 요청은 `30`을 확인한다. 기대값을 22.7로 바꾼 변이에서 `got 2.262016109195E+01`로 실패하고 원복했다. 위 Java 시험이 패널 쿼리와 버킷 경계가 promtool 파일에 그대로 있는지 확인해 둘이 어긋나지 않게 묶었다.
- 해석(문서 "p95 읽는 법"): 값이 30이면 "30초 이상"이다. 예산을 넘긴 요청(GC·OS 정지, DB 불응)이 있었다는 신호이며 그 이상의 길이는 이 지표로 알 수 없다. 30초 안의 값도 버킷 사이 보간값이다.

## 2. `APP_BLUE_TAG`/`APP_GREEN_TAG` 빈 값 보간 (agy P2)

- 원인: `compose.prod.yml`은 두 태그를 필수로 요구하고, #14 계약에서 태그는 `.env`가 아니라 `deploy/state/{blue,green}_tag`에 있다. 그래서 문서의 `docker compose -f compose.prod.yml -f compose.monitoring.yml ...`은 서버에서 실패한다.
- 수정: `deploy.sh monitoring <docker compose 인자>` 하위 명령. 기존 `compose()`(같은 `-f compose.prod.yml`, 같은 `--env-file`)에 `-f compose.monitoring.yml`을 더하고, 다른 compose 호출과 같은 `export_color_tags`로 태그를 읽는다(배포 전이면 자리표시 `unset`). 앱·Caddy·배포 상태를 건드리지 않으므로 배포 잠금은 잡지 않는다. 인자 없음·overlay 파일 없음은 docker 호출 전에 실패한다.
- **이미지 태그 단축형 함정**: `deploy.sh`는 모르는 첫 인자를 이미지 태그로 보고 배포한다(`*)` 분기). 새 분기는 그 앞에 있다. 분기를 지운 변이에서 `deploy.sh monitoring config -q`가 **태그 `monitoring`으로 bootstrap을 실행**했고 `deploy-test.sh` [16/16]이 이를 잡았다.
- `deploy-test.sh` [16/16](모의 docker, 실제 `flock`): 같은 프로젝트·`.env`에 overlay를 얹음, 배포 전 `unset`, bootstrap 뒤 `blue=sha-111`, 배포 잠금을 쥔 상태에서도 실행, 앱 명령 없음, 배포 상태 무변경, 거절 시 docker 호출 0. 모의 docker는 overlay 호출 때 받은 태그 값을 기록하도록 한 줄 늘렸다. 전체 16개 시나리오 `ALL_DEPLOY_TESTS_PASSED`(로컬 `ubuntu:24.04` 컨테이너).

## 3. 배치·설치·배포 유지·heartbeat 전달 (실제 #14 경로)

- `compose.prod.yml` 앱 공통 환경에 `HEARTBEAT_COLLECTOR_URL: ${HEARTBEAT_COLLECTOR_URL:-}`. `deploy/.env.prod.example`에 `HEARTBEAT_COLLECTOR_URL`, `GRAFANA_ADMIN_PASSWORD`, `OPS_DISCORD_WEBHOOK_URL` 키 이름(따로 있던 `deploy/monitoring/.env.example`은 지웠다).
- `deploy.yml` 동기화 대상에 `compose.monitoring.yml`(`deploy/monitoring/`은 `deploy/`에 이미 포함). 동기화만으로 실행 중인 컨테이너는 바뀌지 않고 운영자가 `deploy.sh monitoring up -d prometheus grafana`로 적용한다고 주석과 문서에 적었다.
- 새 `deploy/monitoring/tests/compose-integration.sh`와 CI 잡 `Monitoring / compose-integration`. 실제 Docker Compose, 실제 `compose.prod.yml`·overlay·`deploy.sh`, 합성 값과 대체 이미지(alpine), 격리 프로젝트명, 끝나면 `down -v`.
  1. `.env` 규약(빈 태그)으로 직접 compose → `APP_BLUE_TAG` 보간 실패(agy 재현과 같음)
  2. `deploy.sh monitoring config` → 두 색 앱에 heartbeat URL, Prometheus 포트 없음·`backend`+`monitoring`, Grafana `monitoring`만·`127.0.0.1` 포트만, 공개 포트는 Caddy와 Grafana 루프백뿐
  3. `deploy.sh monitoring up -d prometheus grafana` → 두 컨테이너 실행. 앱 컨테이너(`run --rm --no-deps app-blue env`)가 실제로 heartbeat URL을 받음. `deploy.sh bootstrap`이 app-blue를 만들고(준비 확인 로그), 대체 이미지라 준비 실패로 `BOOTSTRAP_APP_FAIL`, app-blue를 멈추고 지움. 그동안 두 모니터링 컨테이너가 **같은 ID로 계속 실행**. `deploy.sh monitoring restart grafana` 뒤에도 같은 ID
  - 로컬 결과: Docker Desktop(Windows, linux/amd64 엔진, Compose v2.33.0)에서 `docker:27-cli` 컨테이너로 실행해 `COMPOSE_INTEGRATION_TEST_PASSED`, 남은 컨테이너 0. 로컬 공유 경로가 `noexec` tmpfs라 시험은 `deploy.sh`를 `bash`로 호출한다(shebang과 같음). ARM64 결과는 아래 CI

## 4. 첫 정기 수집 확인 절차 (agy P3)

`docs/ops/monitoring.md` 설치 7번에 넣었다. 수집이 꺼진 초기에는 "마지막 완전 수집 경과"가 `No data`이고 수집 경보 둘이 울리지 않는 것이 의도된 상태다. `COLLECTOR_ENABLED=true` 배포 뒤 첫 매시 7분 슬롯이 지나면 패널이 숫자로 바뀌고 `max(collector_last_complete_epoch) > 0`이 되는지 본다. 이때부터 `Stale` 경보가 동작한다. 두 슬롯이 지나도 `No data`면 미완료 회차 패널과 `COLLECTION_FAILED` 로그를 본다. 첫 성공 전 경보 공백을 사람이 메우는 단계로 적었고, 운영 중 수집 실패 감지(`Failing`, `Stale`)의 의미는 바꾸지 않았다. `collector_enabled` 게이지 같은 새 지표는 만들지 않았다.

## 설계 설명 (세 에이전트 의견 수렴 반영)

- Prometheus 규칙 평가 → Grafana 전달, Grafana `127.0.0.1:3000` 루프백 바인딩은 그대로다.
- 웹훅 미설정 상태를 운영자가 보는 그대로 문서에 적었다. 경보는 평가돼 Grafana 목록에 발동으로 보이고, 발동 중이면 Grafana 로그에 `127.0.0.1:9` 로컬 전송 실패가 반복되며 밖으로는 아무것도 나가지 않는다. 웹훅을 넣은 뒤에는 `deploy.sh monitoring up -d grafana`로 다시 만들고(환경값은 `restart`로 바뀌지 않음) 훈련 경보로 발동·수신·복구를 확인해야 전환 완료다.
- 평가·전달 지연은 기존 문서의 대역 측정 그대로다. 로컬 Docker Desktop 1회 발동 131초·해제 120초, ARM64 CI 1회 발동 99초·해제 62초(시험 DB·디스코드 대역, 운영 측정 아님).

## 커밋 기록

- 사용자 환경의 pro-workflow 커밋 훅이 `ops:` 타입을 거부한다(허용 목록에 없음). root가 수용한 형식에 맞춰 `fix(ops):`로 커밋했다.
- 첫 시도가 훅에 막히면서 같은 명령의 `git add`도 실행되지 않았다. 그래서 `24ac28b`에는 미리 스테이징된 두 변경(새 `compose-integration.sh`, `deploy/monitoring/.env.example` 삭제)만 들어갔고 나머지는 `8fcc85e`에 있다. 이미 push한 커밋이라 다시 쓰지 않고 새 커밋 메시지에 이 사실을 적었다. 두 커밋을 합친 내용이 이 문서의 수정 전부다.

## 검증

| 시험 | 결과 |
|---|---|
| 로컬 `./gradlew check bootJar` (PR13 병합 뒤) | **154 tests, 0 failures** |
| `MonitoringContractIntegrationTest` | 2건 통과, 10s 변이 실패 확인 |
| `promtool test rules gongchae.test.yml` (로컬 `prom/prometheus:v3.16.0`) | SUCCESS, 기대값 변이 실패 확인 |
| `deploy-test.sh` 16개 시나리오 | `ALL_DEPLOY_TESTS_PASSED`, `monitoring` 분기 제거 변이에서 bootstrap 실행으로 실패 확인 |
| `compose-integration.sh` | 로컬 `COMPOSE_INTEGRATION_TEST_PASSED` |
| CI (`c36c557`) | CI [44283945810](https://github.com/Lcjam/gongchae-bell/actions/runs/44283945810) test(`check bootJar`, ARM64)·deploy-verify(`ALL_DEPLOY_TESTS_PASSED` 16개, Caddy 통합, ARM64 이미지)·secrets 성공. Monitoring [44283945764](https://github.com/Lcjam/gongchae-bell/actions/runs/44283945764) rules(promtool `SUCCESS`)·compose-integration(ARM64, `COMPOSE_INTEGRATION_TEST_PASSED`, 39초)·alert-e2e(발동 104초, 해제 57초) 성공 |

실제 Discord·UptimeRobot·Healthchecks.io·OCI·SSH·DNS·계정·`.env` 실제 값·키·개인정보·개발 DB 접근은 0회다. 다른 작성자의 worktree는 읽지 않았다.

## 실제 환경 후속 (#15를 닫지 않음)

- 서버 설치(`deploy.sh monitoring up -d prometheus grafana`), 외부 `:3000`·`:9090`·`:8081` 차단과 `ss -ltnp` 확인
- 운영 디스코드 훈련 경보 발동·수신·복구, Healthchecks `gongchae-collector` ping과 `/fail`, UptimeRobot 임시 404 모니터 수신·복구 기록
- `COLLECTOR_ENABLED=true` 뒤 첫 정기 수집 패널 전환 확인(설치 7번)
- Prometheus·Grafana 메모리와 1년 보존 디스크 실측, UptimeRobot·Healthchecks.io 현재 무료 범위 재확인
- M2 발송 지표와 묶음 heartbeat는 #7. 백업 heartbeat는 #16이 main에 병합됐고 Healthchecks 계정 연결은 후속

## 3라운드: 공유 임시 upstream 경합과 cp·mv 실패 (2026-11-18)

요청은 root `collab/2026-11-18-implementation/claude-monitoring-concurrency-request.md`, 근거는 `collab/reviews/pr-21-codex-round2.md`, `pr-21-sol-round2.md`(P2)다. 검수자의 `.tools/pr21-round2/` 진단은 읽지 않았다. 아래 시험은 이 브랜치의 운영 코드 경로를 직접 실행한다.

| 항목 | 값 |
|---|---|
| 시작 head | `bee2be2` (빈 태그 단언 수정. root가 compose-integration success를 확인했고 다시 만들지 않았다) |
| 코드·시험·문서 커밋 | `25c9fbb` |
| CI (`25c9fbb`) | CI [44287466328](https://github.com/Lcjam/gongchae-bell/actions/runs/44287466328) test(`check bootJar`, ARM64)·deploy-verify(`[17/17]` 포함 `ALL_DEPLOY_TESTS_PASSED`, Caddy 통합, ARM64 이미지)·backup-verify·secrets 성공. Monitoring [44287466430](https://github.com/Lcjam/gongchae-bell/actions/runs/44287466430) rules·compose-integration(`COMPOSE_INTEGRATION_TEST_PASSED`)·alert-e2e(발동 93초, 해제 57초) 성공 |
| 최종 head | 이 파일을 담은 다음 커밋(문서만). PR #21 head로 확인 |

### 원인

- `main`이 하위 명령 분기·잠금 획득 전에 거는 EXIT trap이 공유 경로 `upstream.caddy.next`를 **모든 종료에서** 지웠다. 배포 잠금을 쥔 switch가 검증과 이동 사이에 있을 때 잠금 없이 도는 `monitoring`(2라운드에서 추가), `status`, 도움말, 잠금을 얻지 못하고 끝나는 실행이 그 파일을 지울 수 있었다.
- `atomic_reload_upstream`, `stage_and_validate_upstream`, `rollback_reload_from_prev`는 `if ! ...` 문맥에서 불려 errexit가 꺼진다. 사라진 파일의 `mv` 실패가 가려지고 구 설정의 reload 성공이 `SWITCH_OK`로 보고됐다. 같은 문맥의 `rollback_reload_from_prev`는 `.prev` 이동이 실패하면 새 색 설정을 "성공적으로" 다시 읽고 호출자가 그 새 색을 멈추는 더 나쁜 경로가 있어 함께 고쳤다.

### 수정 (`deploy/deploy.sh`)

- `cleanup_on_exit`: `upstream.caddy.next`는 그 파일을 준비한 실행(`stage_and_validate_upstream`, 배포 잠금 아래)만 지운다(`OWNS_STAGED_UPSTREAM`). 잠금을 새로 잡지 않으며 `monitoring`·`status`의 동작과 모니터링 설치·exec·restart 경로는 그대로다. 실행별 `*.tmp.$$` 정리는 그대로다.
- `stage_and_validate_upstream`의 복사, `atomic_reload_upstream`의 `.prev` 보관과 `.next` 이동을 각각 확인한다. 실패하면 `CADDY_STAGE_FAIL`로 `upstream.caddy`를 바꾸지 않은 채 실패를 돌려준다. 호출자의 기존 처리대로 pending을 정리하고 새 색을 지우며 구 색이 계속 서빙한다.
- reload 실패 뒤 `.prev` 원복 이동이 실패하면 파일이 새 색을 가리키므로 `CADDY_RELOAD_REVERT_FAIL`로 즉시 끝내고 새 색과 pending을 남긴다(abort로 복구). `rollback_reload_from_prev`의 `.prev` 이동 실패는 `ROLLBACK_RELOAD_FAILED`로 돌려줘 기존처럼 두 색을 남긴다.
- finalize의 `upstream == pending` 색 비교 guard, 정지·제거·복원 실패 처리, 원자 전환 순서는 바꾸지 않았다.
- `compose.monitoring.yml` 머리말 예시를 `deploy/deploy.sh monitoring up -d prometheus grafana`로 맞췄다. runbook §5 실패 표에 `CADDY_STAGE_FAIL`, `CADDY_RELOAD_REVERT_FAIL`, `.prev` 복귀 실패를 넣고 §7·§8과 모니터링 문서를 고쳤다.

### 회귀 시험 `deploy-test.sh` [17/17] (모의 docker·curl, 실제 `flock`, 전체 CLI)

모의 docker에 Caddy 검증 시점 훅(`MOCK_VALIDATE_HOOK`)을 더했다. 실제 switch가 배포 잠금을 쥔 채 검증과 이동 사이에 있을 때 다른 `deploy.sh` 프로세스를 실행한다. `failbin` 모의 `mv`에는 원본 경로로 실제로 아무것도 옮기지 않고 실패하는 주입(`INJECT_MV_FAIL_FROM`)을 더했다.

| 사례 | 확인 |
|---|---|
| 17a | 다른 프로세스가 배포 잠금을 쥐고 `upstream.caddy.next`를 둔 상태에서 `monitoring config`, `status`, `--help`, 잠금 실패로 끝나는 `finalize`(LOCK_HELD) 뒤에도 파일 내용이 그대로 |
| 17b | 검수자 재현과 같은 끼어들기: 실제 `deploy --phase switch`의 검증과 이동 사이에 `monitoring config`와 `status`를 실행해도 `SWITCH_OK`, `upstream.caddy`가 실제로 green, pending green, finalize 성공 |
| 17c | 이동 직전에 준비 파일이 사라지면 switch가 실패(`CADDY_STAGE_FAIL ... could not be moved`), `SWITCH_OK` 없음, upstream·active 무변경, pending 없음, 구 색 실행·새 색 제거, 다음 배포 정상 |
| 17d | 전환 뒤 확인 실패 + `.prev` 복귀 이동 실패 → `ROLLBACK_RELOAD_FAILED ... could not restore`, `POST_SWITCH_ROLLED_BACK` 없음, 두 색 실행, pending 유지, abort가 blue로 복구 |
| 17e | upstream이 pending 색과 다른 상태(예전 거짓 성공이 남기던 상태)에서 finalize가 `STATE_MISMATCH`로 거부, 두 색·pending 유지, abort 복구 |

변이 확인(Ubuntu 24.04 컨테이너에서 복사본만 바꿈, 원본 무변경):

| 변이 | 결과 |
|---|---|
| M1 공유 trap 복원(모든 종료가 `.next` 삭제) | 17a 실패: `expected=[staged by another deploy] actual=[]` |
| M2 `.next` 이동 확인 제거 | 17c 실패: switch가 성공으로 끝남 |
| M3 `.prev` 복귀 이동 확인 제거 | 17d 실패: `ROLLBACK_RELOAD_FAILED could not restore` 없음 |
| M1+M2(원래 코드 모양) + 17a 생략 | 17b 실패: 로그에 `SWITCH_OK deploy_id=dep-2 ... serving=green`인데 `upstream.caddy`에 `app-green:8080` 없음. 검수자가 재현한 거짓 성공과 같다 |

### 검증

| 시험 | 결과 |
|---|---|
| `deploy-test.sh` 17개 시나리오 (로컬 `ubuntu:24.04`) | `ALL_DEPLOY_TESTS_PASSED` |
| `compose-integration.sh` (로컬, 수정된 `deploy.sh`) | `COMPOSE_INTEGRATION_TEST_PASSED` |
| 로컬 `./gradlew check bootJar` | 154 tests, 0 failures (Java 변경 없음) |
| CI (`25c9fbb`) | CI [44287466328](https://github.com/Lcjam/gongchae-bell/actions/runs/44287466328) test(`check bootJar`, ARM64)·deploy-verify(`[17/17]` 포함 `ALL_DEPLOY_TESTS_PASSED`, Caddy 통합, ARM64 이미지)·backup-verify·secrets 성공. Monitoring [44287466430](https://github.com/Lcjam/gongchae-bell/actions/runs/44287466430) rules·compose-integration(`COMPOSE_INTEGRATION_TEST_PASSED`)·alert-e2e(발동 93초, 해제 57초) 성공 |

30초 p95·합성 20초 Timer·promtool 쿼리, 실제 Compose 결합·모니터링 컨테이너 유지·heartbeat 전달 시험은 그대로 두었다. Gradle·실제 Compose 시험은 로컬에서 다시 통과했고 promtool은 CI `rules`로 확인한다. 실제 서버·SSH·OCI·DNS·계정·`.env` 실제 값·키·provider·개발 DB와 다른 작성자 브랜치에는 접근하지 않았다.
