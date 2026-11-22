# PR #32 최종 차분 검수 · Sol 2라운드

- 검수일: 2026-11-20 KST · 비작성자 GPT-6.1 Sol(high), root Codex 요청.
- 고정 대상: **`dee0f6c5d4c1112738debcab1af4d1e5f15ff88a`**.
- 비교: 최초 검수 대상 **`717fb914510564bd86247ed932976c98049e1090`** 이후 전체 차분. 중간 시험 보완은 **`4f4d1414d2ee78e808e9ab27db48aeda7edf97d3`**.
- 자기 검수 worktree에서 git show/diff로 읽었다. 작성자에게 입력하거나 제품 source·시험·설정·브랜치를 편집하지 않았다.

**판정: 최종 코드·시험·문서 차분 수용. 추가 수정 요청 없음.** 최초 P3의 for 상태 단정은 해소됐고, CI에서 드러난 inode guard의 false failure도 시험의 구별력을 유지한 채 보완됐다. 최종 dee CI와 이후 result-only tip은 root가 별도 확인한다. 실제 운영 업그레이드 실행 승인을 뜻하지 않는다.

## inode guard 수정

717의 새 회귀는 tar 전후 inode **숫자**가 반드시 달라야 한다고 검사했다. directory mount에서는 교체 전 개별 파일 inode를 잡는 참조가 없어 파일시스템이 해제된 번호를 새 파일에 재사용할 수 있다. 따라서 번호가 같다는 사실만으로 “tar가 교체하지 않았다”라고 판정할 수 없었다.

4f4d141은 tar 전에 **`ln "$cfg" "$WORK/prometheus.yml.pre-sync"`**로 교체 전 파일을 붙잡는다. pin 경로는 archive의 compose/deploy 대상 밖이고 같은 임시 작업 디렉터리 안에 있다. tar 뒤 **`[[ ! "$cfg" -ef "$WORK/prometheus.yml.pre-sync" ]]`**로 두 경로가 같은 파일인지 확인한다.

- 옛 inode가 하드링크에 남아 있으므로 새 파일에 그 inode 번호를 즉시 재사용할 수 없다.
- `-ef`는 파일의 device/inode 정체성을 비교한다. tar가 실제로 기존 파일을 그대로 덮어썼다면 여전히 같은 파일이어서 guard가 실패하므로, 검사를 단순히 삭제하거나 무조건 통과시킨 것이 아니다.
- 이후의 **새 scrape job 추가→SIGHUP→실행 중 Prometheus status/config 확인→같은 컨테이너 ID 확인**은 그대로다. 옛 단일 파일 bind라면 pin된 옛 파일을 계속 읽어 새 job이 나타나지 않는 실패 조건도 유지된다.
- pin은 시험의 임시 WORK 아래에만 생기며 제품 mount·runtime privilege에 추가되는 것이 아니다. 기존 GNU tar·실제 Docker bind 회귀 범위에 맞는 보완이다.

## 최초 P3와 runtime 보존

`monitoring.md`는 “for 대기가 모두 처음부터”라는 문장을 제거했다. 짧은 평가/scrape 공백, 보존된 데이터와 복원 조건에 따른 for 상태 복원 또는 재대기, 재생성 뒤 `/api/v1/alerts` 확인으로 바꿨다. 고정 v3.16.0 RestoreForState와 기본 outage tolerance 1h/grace 10m 근거도 붙였다. 최초 검수에서 확인한 공식 source와 일치한다.

717→dee 사이 **compose.monitoring.yml, deploy.yml, deploy.sh, compose.prod.yml 4파일은 바이트 동일**임을 직접 확인했다. 따라서 최초 검수의 directory mount·prometheus_data 유지·경로/ro 권한·Grafana directory mount·네트워크·포트·flags·state 태그·overlay 판정은 그대로 유지한다. 기존 설치의 한 번 `deploy.sh monitoring up -d prometheus` 재생성 및 이후 SIGHUP 안내도 유지된다.

## 실패·실행 근거의 구분

**717의 실제 CI 성공으로 기록하지 않는다.** root가 직접 확인해 전달한 Monitoring **44714412767 compose-integration 실패**는 `tar did not replace prometheus.yml, so this step would prove nothing`이었다. 최초 [검수](pr-32-sol.md)는 source상 회귀 구조를 검토했고 이 실제 CI를 실행/조회한 결과가 아니었다. 그때의 구조 판정과 이후 발견된 시험 guard 결함을 구분한다.

이번에 고정 HEAD에 보존된 작성자 결과 두 파일을 읽었다.

- `claude-monitoring-bind-fix-result.md`는 첫 CI 실패와 inode 번호 재사용 원인을 **명시적으로 보존**한다. source의 B1 문제와 새 시험 guard의 결함을 섞지 않는다.
- 작성자는 로컬 dind(Compose 2.33.0, GNU tar1.35)에서 수정본 통과·단일 파일 mount 변이의 status/config 실패를 실행했고, 하드링크 보완 뒤에도 같은 구별력을 확인했다고 보고했다.
- 작성자는 기존 설치 up -d 업그레이드에서 컨테이너 ID 변경·directory mount 전환·같은 prometheus_data 및 이전 sample 보존을 확인했다고 보고했다.
- **4f4d141** CI 44715793111/Monitoring 44715793145의 7잡 success는 작성자 보고다. 이전 실패를 이 성공으로 덮지 않으며, 그것을 **dee의 최종 CI 결과로 대신하지 않는다**.
- `claude-pr32-review-fixes-result.md`는 for 문장 정정과 공식 source 확인, 재시작 실험 미실행을 구분한다. 첫 결과의 같은 문장도 정정 표시를 남겼고 실패 기록을 삭제하지 않았다.

위 dind·변이·업그레이드·원격 CI는 **내가 이번 세션에서 재실행/조회한 것이 아니다**. root가 이미 읽은 실행 근거와 작성자 보고로 인용한다. 이번 추가 차분만으로 같은 전체 시험을 다시 반복할 필요는 발견하지 못했다.

## 직접 확인 범위

- 717..dee 전체 4파일 차분 및 두 작성자 결과 원문 검토.
- 위 runtime 4파일의 바이트 동일성 확인.
- 고정 dee의 compose-integration.sh **bash -n 통과**.
- `git diff --check 717..dee` **exit 0**.

전체 Docker·Gradle 시험, 실제 inode 재사용·업그레이드·for 재시작 실험은 실행하지 않았다. 실제 운영 서버·env·키·provider에 접근/변경하지 않았다. 이 검수 기록만 root와 자기 검수 worktree에 작성했다. 최종 CI 직접 확인과 결과 파일만 추가한 후속 HEAD 분리는 root 담당이다.
