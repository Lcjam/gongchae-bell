# Prometheus 설정 bind mount 배포 회귀 수정 결과 (#14·#15) · Claude

2026-11-20 root 배정 `collab/2026-11-19-resume/claude-monitoring-bind-fix-request.md`를 수행했다. worktree `issue-14-monitoring-bind`, branch `fix/#14-monitoring-bind`, 기준 main `eb903017c4fb256a8ece665da795d7a94f6aeb25`다. PR은 https://github.com/Lcjam/gongchae-bell/pull/32 이고 **최종 head는 `4f4d1414d2ee78e808e9ab27db48aeda7edf97d3`**다(`717fb91` 수정 + `4f4d141` 시험 보완).

## 원인과 수정
- 원인(검수 B1): 배포 동기화 `tar -xf`는 기존 파일을 지우고 새 inode로 만든다. 그런데 Prometheus는 `prometheus.yml`만 단일 파일로 mount해 옛 inode를 잡고 있었다. 그래서 동기화나 그 뒤의 in-place 수정을 SIGHUP으로 반영하지 못했다.
- `compose.monitoring.yml`: Prometheus mount 두 줄(`prometheus.yml` 단일 파일, `rules/` 디렉터리)을 `./deploy/monitoring/prometheus:/etc/prometheus:ro` 한 줄로 바꿨다. `--config.file=/etc/prometheus/prometheus.yml`과 `rule_files: /etc/prometheus/rules/*.yml`은 그대로 맞는다. `prometheus_data` 볼륨은 바꾸지 않았다.
- Grafana: mount 두 개(`provisioning`, `dashboards`)가 모두 디렉터리라 같은 원인이 없다. 바꾸지 않았다.
- `docs/ops/monitoring.md` 설치 5번에 세 가지를 보탰다.
  - `up -d`는 파일 내용 변경만으로는 컨테이너를 다시 만들지 않으므로 반영 수단이 아니다.
  - 디렉터리 mount의 이유와 재현 근거.
  - 기존 설치는 동기화 뒤 한 번 `deploy/deploy.sh monitoring up -d prometheus`로 다시 만든다. 볼륨은 유지되고 몇 초 동안 평가·scrape 공백과 `DatasourceError`가 생길 수 있다. `for` 상태는 보존 데이터와 복원 조건에 따라 이어지거나 다시 대기하므로 재생성 뒤 실제 경보 상태를 확인한다. 확인 명령도 적었다. *(2라운드 정정: 처음 결과에는 "`for` 대기가 처음부터 다시 시작된다"고 썼다. Sol P3 지적대로 v3.16.0 `RestoreForState`에 맞지 않아 고쳤다. [`claude-pr32-review-fixes-result.md`](claude-pr32-review-fixes-result.md))*
  - 시험 표의 `compose-integration.sh`에 ④를 추가했다.
- `.github/workflows/deploy.yml`: 주석만 고쳤다. "`up -d prometheus grafana`로 반영"이라던 오안내를 SIGHUP과 `restart grafana`로 바꿨다. 동작 변경은 없다.

## 회귀 (`deploy/monitoring/tests/compose-integration.sh` 4단계)
실행 중 Prometheus가 `gongchae-app` 설정을 읽었는지 `/api/v1/status/config`로 확인한다. 그다음 순서는 다음과 같다.
1. 동기화 전 파일에 하드링크를 걸어 옛 파일을 붙잡는다. 단일 파일 mount가 하던 일을 흉내 낸다.
2. `deploy.yml`과 같은 모양으로 `tar --exclude='deploy/state' -cf - … | tar -xf - -C`를 실행한다.
3. `-ef`로 파일이 실제로 교체됐는지 확인한다.
4. in-place로 scrape job `it-reload-probe`를 추가하고 `deploy.sh monitoring kill -s SIGHUP prometheus`를 보낸다.
5. `/api/v1/status/config`에 새 job이 보이고 컨테이너 ID가 그대로인지 확인한다.

privileged mount 재현은 시험에 넣지 않았다. 실제 Docker bind mount만 쓴다.

## 검증
- **로컬 dind**(`docker:27-dind`, dockerd 27.5.1, Compose v2.33.0, GNU tar 1.35, 실제 Linux bind mount):
  - 수정본은 1~4단계를 모두 통과했다(`COMPOSE_INTEGRATION_TEST_PASSED`).
  - 단일 파일 mount로 되돌린 판은 4단계에서 `COMPOSE_IT_FAIL Prometheus still runs the pre-sync prometheus.yml after SIGHUP (mount pinned to the old inode)`로 실패했다(구별력).
  - 위 두 결과는 하드링크 보완 뒤에도 같았다.
- **업그레이드 절차(같은 dind)**: 옛 mount로 띄운 Prometheus(70초 scrape)에 새 파일을 동기화하고 `deploy.sh monitoring up -d prometheus`를 실행했다.
  - 컨테이너가 `Recreate`되어 ID가 `1c0e33befc6b`에서 `b474cbc7fd65`로 바뀌었다.
  - mount는 `prometheus.yml`·`rules` 두 개에서 `deploy/monitoring/prometheus->/etc/prometheus` 하나로 바뀌었다.
  - 같은 볼륨 `prometheus_data`가 유지됐고, 재생성 전의 sample도 남았다(`count_over_time(up[1h])`가 2에서 2·3).
- **CI**: 첫 head `717fb91`에서 `compose-integration`이 실패했다. 내 시험 결함이었다. 러너의 ext4가 tar가 방금 비운 inode 번호를 새 파일에 그대로 다시 줘서, inode 번호 비교가 "교체 안 됨"으로 오판했다. 실제 운영에서는 단일 파일 mount가 옛 inode를 붙잡아 재사용이 일어나지 않으므로 B1 판단에는 영향이 없다. 하드링크로 옛 파일을 붙잡고 `-ef`로 비교하도록 고쳤다.
  - 최종 `4f4d141`: CI `44715793111`(test·deploy-verify·backup-verify·secrets)과 Monitoring `44715793145`(rules·compose-integration·alert-e2e) **7잡 전부 success**. CI 로그에서 4단계 뒤 `COMPOSE_INTEGRATION_TEST_PASSED`를 확인했다.

## 한계·남은 것
- **운영 서버 적용은 하지 않았다.** 적용할 때는 다음을 차례로 한다.
  1. 이 PR이 병합된 main을 배포한다(동기화).
  2. 한 번 `deploy/deploy.sh monitoring up -d prometheus`를 실행한다.
  3. 컨테이너 안 `/etc/prometheus`가 디렉터리 mount인지, `status/config`가 host 파일과 같은지 본다.
  4. Grafana `DatasourceError`가 1회 갈 수 있다는 점을 기록한다.
- 지금 운영 Prometheus는 단일 파일 mount 그대로다. 그 앞에서 `prometheus.yml`을 고치는 경보 훈련(검수 B1)은 위 적용 뒤에 해야 한다.
- 단일 파일 mount 실패는 dind와 기존 검수 재현으로만 확인했다. CI에는 수정본 통과만 있다. 옛 mount 실패 run은 CI에 남기지 않았다.
