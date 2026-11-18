# #15 모니터링 구현 결과 · claude

> 2026-11-18 · 요청: root Codex 구현 1라운드(요청 파일은 root 작업트리 `collab/2026-11-18-implementation/claude-monitoring-request.md`) · 작성자: claude(단독)
> 브랜치 `ops/#15-monitoring` · 워크트리 `C:/Users/lcj/orca/workspaces/my-project/issue-15-monitoring` · 기반 main `000dce555e9eb69ef3184dd5f80aa1d499a1f602`

- **상태**: 소스·설정·문서·시험 완료, **Draft PR #21**. 운영 서버 설치, 실제 Discord·UptimeRobot·Healthchecks.io 계정 연결과 수신 확인은 **하지 않았다**. #15는 닫지 않는다
- **code SHA**: `f8725494935c541db5abb6c88413e73bf674ad5d` (`feat(ops): add monitoring stack, alert forwarding and heartbeat (#15)`)
- **head**: 이 결과 파일과 `docs/ops/monitoring.md`의 CI 실행 기록 한 줄을 더한 docs 커밋(코드 변경 없음). SHA는 PR #21 head로 확인한다
- **PR**: [#21](https://github.com/Lcjam/gongchae-bell/pull/21) (draft, base `main`, `Refs #15`)
- **CI (`f872549`)**: 모두 통과
  - CI [run 44227246669](https://github.com/Lcjam/gongchae-bell/actions/runs/44227246669): `test`(`check bootJar`, ubuntu-24.04-arm) 1m32s, `secrets`(gitleaks) 9s
  - Monitoring [run 44227246661](https://github.com/Lcjam/gongchae-bell/actions/runs/44227246661): `rules`(promtool) 14s, `alert-e2e`(ARM64 격리 스택) 4m25s

## 1. 구현 범위

| 파일 | 내용 |
|---|---|
| `compose.monitoring.yml` (새) | `compose.prod.yml` 위 overlay. `prometheus:v3.16.0`(호스트 포트 없음, 보존 1년, `backend`+`monitoring` 망), `grafana-oss:13.0.2`(`127.0.0.1:3000`만, `monitoring` 망만, 외부 원격 수집·업데이트 확인 끔, 관리자 비밀번호 필수). 앱 설정과 `deploy.sh`는 건드리지 않는다. 두 이미지는 arm64 manifest를 확인했다 |
| `deploy/monitoring/prometheus/` | scrape 30초(`app-blue:8081`, `app-green:8081` 정적), 규칙 6개, `promtool` 규칙 시험 |
| `deploy/monitoring/grafana/` | 데이터소스, 대시보드 `공채벨 운영`(19개 쿼리), 전달 규칙 1개 + 연락처 `ops-discord` + 알림 정책 |
| `deploy/monitoring/tests/` | `alert-e2e.sh`, 시험용 base compose, 디스코드 대역·쿼리 검사 `stub.py` |
| `deploy/monitoring/.env.example`, `.env.example` | 키 이름만: `GRAFANA_ADMIN_PASSWORD`, `OPS_DISCORD_WEBHOOK_URL`, `HEARTBEAT_COLLECTOR_URL` |
| `.github/workflows/monitoring.yml` (새) | `rules`, `alert-e2e` 잡. #14의 `ci.yml` 수정과 겹치지 않게 별도 파일로 뒀다 |
| `src/main/java/.../ops/Heartbeat.java` (새) | 성공 시에만 보내는 Healthchecks ping. 빈 URL이면 꺼짐, GET 1회, 5초 제한, 리다이렉트 안 따름, 재시도 없음, 실패는 URL 없이 `HEARTBEAT_FAILED collector <사유>` 로그만 남기고 예외를 던지지 않음, 잘못된 URL은 `INVALID_HEARTBEAT_URL`로 기동 실패 |
| `RegularCollector`, `CollectorConfiguration` | 정기 회차가 `COMPLETE`로 커밋된 뒤, 선점한 앱만 상세 sweep 전에 ping. 실패 처리, 예산, 회차 의미는 그대로다 |
| `CollectorMetrics` | `collector_api_warning_limit`, `collector_api_stop_limit` 게이지(설정값). 규칙이 하드코딩한 500/900이 아니라 실제 설정과 비교하게 했다 |
| `application.yml` | `http.server.requests` 히스토그램 버킷(5ms~10s), `heartbeat.collector-url: ${HEARTBEAT_COLLECTOR_URL:}` |
| 문서 | `docs/ops/monitoring.md`(새), ADR-0006 구현 메모, `architecture.md`(Grafana 루프백 포트, 경보 흐름), `collector.md`(한도 게이지, 링크), `implementation-status.md`(운영 기반 행) |

**경보 규칙 (초기값, `promtool`로 발동·복구 시험)**: `GongchaeAppMetricsDown`(두 색 scrape 실패 5분), `GongchaeRegularCollectionFailing`(`max(incomplete_consecutive) >= 2`, 10분), `GongchaeRegularCollectionStale`(완전 수집 이력이 있고 2시간 15분 무완료, 1분), `GongchaeApiBudgetWarning`/`Stopped`(오늘 예약 ≥ 설정 한도), `GongchaeHttp5xxHigh`(10분 5% 초과이면서 5건 이상, 5분). p95 경보는 PRD §6대로 기준선 뒤로 미뤘다.

**blue/green**: DB 게이지는 두 색이 같은 값이라 `max`, HTTP·화면 카운터는 색별 `rate`/`increase` 후 `sum`. 앱 종료 직전 마지막 scrape 뒤 증가분(최대 30초) 손실과 `increase()` 근사를 문서에 적었다.

## 2. 시험

| 시험 | 결과 |
|---|---|
| 로컬 `./gradlew --no-daemon check bootJar` | 101 tests, 실패 0 |
| `HeartbeatTest` (4) | 꺼짐이면 요청 0, 성공 GET 1회, 500·302·연결 실패는 예외 없이 URL 없는 로그, 잘못된 URL 3종은 URL 없는 오류 |
| `RuntimeDatabaseTest` (18, 기존 테스트에 단언 추가) | 두 앱이 같은 슬롯을 동시에 실행해도 ping 1회, 실패·미완료 회차는 ping 없음, 같은 슬롯 재실행은 ping 없음 |
| `MonitoringContractIntegrationTest` (1) | `deploy/monitoring` 아래 파일이 읽는 모든 앱 지표 이름이 실제 `/actuator/prometheus`에 있음, `/jobs` 히스토그램 버킷, 한도 게이지 500·900, 검색어·원래 경로(`/jobs/31337`)·`keyword`가 scrape에 없음 |
| `promtool test rules` | 6개 규칙의 발동·비발동·복구, 두 색 중복 시 경보 1개, 수집 전 0이면 침묵, 낮춘 한도, 낮은 트래픽의 오류 1건. 임계값을 바꾸면(8100→9000, ≥2→≥3, 5%→50%) 실패하는 것도 확인 |
| `alert-e2e.sh` 로컬 (2026-11-18 11:45:34~11:50:29 KST, Docker Desktop amd64) | 웹훅 없이 Grafana 기동, scrape, 대시보드 쿼리 19개 성공, `api_budget` 950 삽입 → 경고·차단 발동 메시지 131초, 삭제 → 해제 메시지 120초 |
| `alert-e2e.sh` CI (12:00:56~12:04:12 KST, ARM64) | 같은 항목 통과. 발동 99초, 해제 62초 |
| gitleaks | CI 통과. 변경 파일만 `gitleaks dir`로 따로 돌려도 no leaks |

실제 Discord, UptimeRobot, Healthchecks.io, 공공데이터 API에는 아무 요청도 보내지 않았다. 대역은 Docker 망 안의 HTTP 서버다.

## 3. root 판단 요청 (설계 쟁점)

ADR-0006의 결정(Prometheus+Grafana 내부 전용, Grafana 경보 → 운영 디스코드, Alertmanager 없음)은 바꾸지 않았다. 다만 구현에서 정한 세 가지가 문서 표현과 닿는다. ADR-0006에 구현 메모로 적었고, 다르게 원하면 알려 달라.

1. **임계값을 Prometheus 규칙에 두고 Grafana는 전달만 한다.** Grafana 관리 규칙에 임계값을 두면 발동·복구를 오프라인으로 시험하기 어렵다. 대가: Grafana가 `alertname`을 덮어써서 `prom_alert` 라벨로 옮겼고, 지연이 Prometheus 평가 + Grafana 평가만큼 늘어난다(대역 시험 99~131초).
2. **Grafana는 호스트 루프백 `127.0.0.1:3000`에 연다.** SSH 터널 접속에 필요하다. architecture의 "호스트 포트는 80, 443뿐"을 "외부에 여는 포트는 80, 443뿐, Grafana만 루프백"으로 고쳤다. 대안은 포트 없이 컨테이너 IP로 터널링하는 것이지만 IP가 바뀐다.
3. **"전송 기본 꺼짐"을 닫힌 로컬 포트로 구현했다.** Grafana 13.0.2는 빈 디스코드 URL로는 시작하지 않는다(`could not find webhook url property in settings`, 재시작 반복을 실측). 그래서 URL이 없으면 compose가 `http://127.0.0.1:9/alerts-off`를 넣는다. 경보는 평가되고 전송은 컨테이너 안에서 실패한다. 대안은 웹훅을 필수로 만드는 것인데, 그러면 경보 채널 없이 대시보드만 먼저 띄울 수 없다.

## 4. 통합 대기와 후속 작업

**#14(PR #20)와 합칠 때** (이 PR은 #14 파일을 고치지 않았다):
- `compose.prod.yml` 앱 공통 환경에 `HEARTBEAT_COLLECTOR_URL: ${HEARTBEAT_COLLECTOR_URL:-}`, `deploy/.env.prod.example`에 키 이름. 이것 전에는 운영 앱에 heartbeat URL이 닿지 않는다(꺼짐과 같다).
- `docs/runbook.md`에 `docs/ops/monitoring.md` "설치" 링크, `CHANGELOG.md`에 항목(CHANGELOG는 #14가 처음 만드는 파일이라 add/add 충돌을 피하려고 만들지 않았다).
- 실제 `compose.prod.yml`과 `-f compose.monitoring.yml` 병합 확인, `deploy.sh` 배포 뒤 monitoring 컨테이너가 남는지(`--remove-orphans` 없음 확인), 서버 파일 배치 방법. 지금은 같은 망 구성을 흉내 낸 시험용 base 위에서만 검증했다.
- `application.yml`은 #14(`server.forward-headers-strategy`)와 #6(`alerts:`)도 고친다. 서로 다른 블록이라 자동 병합을 예상하지만 확인하지 않았다.

**M2 (#6 → #7)**: 발송 적체·실패·불확실 지표와 묶음 heartbeat는 **만들지 않았다**. 근거 스키마(V5 `notification`)는 미머지 PR #13에만 있고 발송기(#7)는 구현 전이다. `docs/ops/monitoring.md` "M2 발송 지표 통합 계약"에 지표 이름(안), V5 기준 정의, 경보(안), 라벨 제한, `digestHeartbeat` 연결 방법을 적었다. 선행 순서: #6 PR #13과 예산 수정 머지 → #7 발송기 → #7이 지표·heartbeat 구현 → 규칙·`promtool` 시험·대시보드 패널. 그때까지 대시보드는 "아직 지표 없음" 문구만 보이고 0으로 채우지 않는다. `RegularCollector` 생성자 인자를 `collectorHeartbeat`로 이름 붙여 두 번째 `Heartbeat` 빈이 생겨도 주입이 모호하지 않게 했다.

**#16, #17**: 백업·NAS pull heartbeat는 호스트 스크립트 몫이다. 체크 일정·유예(안)와 `curl` 형태만 문서에 두었다.

## 5. 실제 환경 미검증 (후속 실행, root 배정 후)

- 운영 서버 설치, 외부에서 `:3000`·`:9090`·`:8081` 접근 차단 확인.
- Grafana → 운영 디스코드 실제 수신(훈련 규칙 `GongchaeDrill` 절차), Healthchecks `gongchae-collector` 실제 ping과 `/fail` 수신·복구, UptimeRobot 임시 404 모니터 수신·복구. 각 발동·수신·복구 시각을 `docs/ops/monitoring.md` 표에 기록. 웹 장애와 배치 미완료는 따로 발동한다.
- UptimeRobot·Healthchecks.io의 현재 무료 범위와 디스코드 알림 가능 여부(ADR-0006 조사값만 있고 다시 확인하지 않았다).
- Prometheus·Grafana 메모리와 1년 보존 디스크 실측.
- 정기 수집을 실제로 켠 상태의 경보 관측(지금은 수집 전이라 수집 경보가 침묵하는 설계).

## 6. 그 밖에

- 커밋 타입: 사용자 환경의 pro-workflow 커밋 훅이 `ops:` 타입을 거부해서(허용: feat, fix, refactor, test, docs, chore, perf, ci, style, build, revert) `feat(ops):`로 커밋했다. 프로젝트 규칙의 `ops:`와 다르다.
- 다른 워크트리·브랜치(#14, #6)는 `git show`로 읽기만 했다. root 작업트리에는 이 결과 사본 하나만 복사한다.
- 로컬에서 받은 Docker 이미지: `prom/prometheus:v3.16.0`·`latest`, `grafana/grafana-oss:13.0.2`·`latest`, `eclipse-temurin:21-jre`, `python:3.13-alpine`, `zricethezav/gitleaks:latest`. 시험 스택은 끝날 때마다 볼륨까지 지웠다.
