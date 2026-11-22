# PR #32 독립 검수 · Sol

- 일자: 2026-11-20 KST · 비작성자 GPT-6.1 Sol(high), root Codex 요청.
- 고정 대상: **`717fb914510564bd86247ed932976c98049e1090`**, 기준 **`eb903017c4fb256a8ece665da795d7a94f6aeb25`**.
- 근거: `claude-monitoring-bind-fix-request.md`, `collector-live-alert-plan-claude.md` B1, 고정 SHA의 4파일 diff 및 관련 Compose·deploy·monitoring 소스. 자기 검수 worktree에서 `git show`로 읽었으며 작성자에게 입력하지 않았다.

**판정: source 변경 수용, 병합 차단 결함 없음. 비차단 P3 문서 정정 1건.** 최종 CI와 author의 실행 결과·result-only tip 확인은 root 담당이며, 이 판정은 실제 운영 적용 완료를 뜻하지 않는다.

## P3 · 재생성 후 모든 규칙의 for 대기가 처음부터라는 단정은 부정확하다

위치: `docs/ops/monitoring.md:58`의 “진행 중이던 규칙의 `for` 대기도 처음부터 다시 센다.”

고정 이미지 Prometheus **v3.16.0**은 저장소의 `ALERTS_FOR_STATE`를 읽어 이전 `ActiveAt`을 복원하는 `RestoreForState()`를 갖는다. 기본 outage tolerance는 1시간, grace period는 10분이다. 짧은 pending 규칙은 다시 대기할 수 있지만 모든 규칙이 무조건 처음부터 시작하지는 않는다. 이 프로젝트의 `GongchaeAlertsBacklog`는 `for: 15m`이므로, 보존된 TSDB와 직전 상태에 따라 15분 전부를 다시 기다리는 대신 복원된 대기 시간으로 처리될 수 있다. 이미 firing이었던 상태도 별도 복원 분기다. [v3.16.0 RestoreForState 소스](https://github.com/prometheus/prometheus/blob/v3.16.0/rules/group.go#L703), [v3.16.0 기본 플래그](https://github.com/prometheus/prometheus/blob/v3.16.0/cmd/prometheus/main.go#L566)

영향은 운영자가 재생성 뒤 일정 시간 동안 경보가 오지 않을 것이라고 잘못 예상할 수 있다는 점이다. 최소 정정은 **“평가·scrape에 잠깐 공백이 생길 수 있다. for 상태는 보존 데이터와 복원 조건에 따라 이어지거나 다시 대기하므로 재생성 뒤 실제 alerts 상태를 확인한다.”** 정도다. mount 수정이나 새 플래그·새 시험을 추가할 필요는 없다. 이 동작은 고정 버전 공식 source로 확인했으며 재시작 실험은 수행하지 않았다.

## B1 해결과 보존 범위

| 검수 항목 | 판단 |
|---|---|
| inode 문제의 실제 해결 | 기존 단일 파일 `/etc/prometheus/prometheus.yml`과 rules 하위 mount를 **`./deploy/monitoring/prometheus:/etc/prometheus:ro` 하나**로 대체한다. tar가 prometheus.yml 파일 inode를 바꿔도 컨테이너는 상위 디렉터리에서 새 경로를 조회하므로 이후 in-place 편집과 SIGHUP이 새 파일에 닿는다. 배포가 디렉터리 자체를 통째 rename하는 새 동작을 추가하지 않았다. |
| 경로 | `--config.file=/etc/prometheus/prometheus.yml`과 rule_files `/etc/prometheus/rules/*.yml`은 그대로이며 새 mount 아래 두 경로가 모두 존재한다. compose.prod.yml이 있는 `/opt/gongchae-bell` 기준 상대 경로라 실제 host source는 `/opt/gongchae-bell/deploy/monitoring/prometheus`다. |
| 소유·권한 | bind는 계속 ro이며 runtime privilege/capability를 추가하지 않는다. config와 rule은 git mode 100644, 디렉터리 아래의 기존 파일들이다. 신규 chown/secret 배치나 쓰기 가능 config mount는 없다. 실제 호스트의 디렉터리 검색·읽기 권한은 운영 적용 시 별도 확인할 조건이며 실측한 것으로 쓰지 않는다. |
| 데이터 | `prometheus_data:/prometheus`, storage path `/prometheus`, retention 1y가 바뀌지 않는다. 데이터 볼륨을 삭제·초기화하는 제품 명령은 없다. |
| 네트워크·포트 | Prometheus는 backend+monitoring, host port 없음. Grafana는 monitoring만, 127.0.0.1:3000만 publish. 기존 값이 모두 보존됐다. |
| flags·태그·overlay | 이미지·command·앱 flags·두 색 tags·heartbeat·deploy.sh monitoring 분기는 그대로다. deploy.yml의 실행 코드는 같고 reload/restart 안내 주석만 바뀌었다. |
| Grafana 동일 원인 여부 | provisioning과 dashboards는 처음부터 **디렉터리 bind**이며 단일 provisioning 파일 bind는 없다. grafana_data는 named volume이다. 같은 tar 파일 교체로 파일 inode가 바뀌어도 이 디렉터리 mount는 새 파일을 본다. Grafana mount 변경을 추가하지 않은 판단이 적절하다. |

## 기존 설치의 재생성 안내

문서는 새 compose 파일 동기화 뒤 **`deploy/deploy.sh monitoring up -d prometheus`**를 한 번 실행하도록 안내한다. `--force-recreate`를 직접 쓰지는 않지만, 이 경우에는 service mount 정의가 변경되므로 일반 `up -d`도 해당 컨테이너를 재생성하고 mounted volume을 보존한다. 따라서 force-recreate 플래그가 없다는 이유만으로 결함으로 보지 않는다. 파일 내용만 바뀐 뒤에는 up/restart만으로 mount 정의가 갱신되는 것처럼 안내하지 않고 Prometheus SIGHUP·Grafana restart를 구분한다. [Docker Compose up 공식 문서](https://docs.docker.com/reference/cli/docker/compose/up/)

운영 명령은 계속 `deploy.sh monitoring`을 거쳐 실제 state 태그를 적재하며 raw Compose의 빈 APP_BLUE_TAG/GREEN_TAG 보간 문제를 되살리지 않는다. `prometheus_data` 유지, 짧은 조회 공백의 DatasourceError 가능성, status/config 확인도 적혀 있다. 이미 기동한 단일 파일 mount에 **SIGHUP만 보내면 이번 mount 구조 변경까지 적용된다**는 오안내는 남아 있지 않다.

## 회귀 시험의 구별력

`compose-integration.sh`의 새 4단계는 실행 중인 실제 Prometheus의 `/api/v1/status/config`에 기존 `gongchae-app` job이 로드됐는지 먼저 확인한다. 그 뒤 제품 deploy.yml과 같은 **tar 생성·추출 모양**으로 고유 작업 디렉터리에 재배포하고 다음을 검사한다.

1. host prometheus.yml inode가 실제로 달라졌음을 assert한다. inode 교체가 없으면 “증명한 것이 없다”며 실패한다.
2. 새 host 파일에 `it-reload-probe` scrape job을 추가하고 SIGHUP을 보낸다.
3. 실행 중 Prometheus의 status/config에서 새 job을 확인한다. host 파일 내용만 grep하는 시험이 아니다.
4. monitoring 컨테이너 ID가 기존과 같아 reload를 위해 컨테이너를 재생성하지 않았음을 확인한다.

따라서 옛 단일 파일 bind에서는 새 host inode에 붙은 job이 mounted 옛 inode에 없으므로 마지막 config 검사가 실패하는 구조다. 이전 B1 보고의 privileged bind 재현을 제품 runtime에 넣지 않았으며 새 회귀도 privileged/capability를 요구하지 않는다. 기존 1~3단계의 태그 보간·포트/네트워크·heartbeat·앱 배포 중 monitoring 유지 검증은 삭제되지 않았다.

이 회귀는 config reload를 입증하며 probe의 scrape 건강도나 규칙 재시작 상태까지 시험한다고 확대하지 않는다. **이번 검수 세션에서 실제 Docker 정상/구설정 변이 회귀를 실행한 것은 아니다.** source상 구별력을 확인했으며 실행 결과는 author 보고와 root의 최종 CI 확인으로 분리한다.

## 직접 수행 및 미실행 범위

- 4파일 diff 검토 및 `git diff --check eb903017..717fb91` **exit 0**.
- 고정 SHA의 `compose-integration.sh`를 raw blob으로 Bash에 넣어 **`bash -n` 통과** 확인(실행은 안 함).
- compose.prod.yml, deploy.sh, prometheus.yml, gongchae rules, Grafana 대시보드·provisioning 4파일, Monitoring workflow의 **9파일이 기준 SHA와 바이트 동일**함을 확인했다.
- Compose 재생성 의미와 Prometheus for 복원은 위 공식 문서·고정 버전 source로 교차 확인했다.

전체 시험·Docker 회귀·구설정 변이·실제 운영 업그레이드는 실행하지 않았다. author 결과 파일은 검수 중 아직 root에 없었으므로 읽은 것으로 쓰지 않는다. CI를 조회하거나 success로 선결론 내리지 않았다. 실제 서버·env·키·provider·운영 데이터에 접근/변경하지 않았으며 앱·시험·설정·작성자 브랜치도 수정하지 않았다. 이 결과 파일만 root와 자기 검수 worktree에 기록했다.
