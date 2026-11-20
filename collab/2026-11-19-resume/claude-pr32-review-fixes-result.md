# PR32 2라운드 문서 정정·결과 보존 (#14·#15) · Claude

2026-11-20 root 배정 `collab/2026-11-19-resume/claude-pr32-review-fixes-request.md`의 후속이다. 1라운드 결과는 [`claude-monitoring-bind-fix-result.md`](claude-monitoring-bind-fix-result.md)(검증 head `4f4d141`, CI 7잡 success)에 있다. source와 회귀 시험은 바꾸지 않았다.

## 정정 (Sol P3, `collab/reviews/pr-32-sol.md`)
- 고친 문장: `docs/ops/monitoring.md` 업그레이드 항목의 "진행 중이던 규칙의 `for` 대기도 처음부터 다시 센다"를 다음 뜻으로 바꿨다. "재생성 몇 초 동안 평가·scrape 공백이 생기고, `for` 상태는 보존 데이터와 복원 조건에 따라 이어지거나 다시 대기하므로 재생성 뒤 `/api/v1/alerts`로 실제 경보 상태를 확인한다." 근거 링크와 기본 플래그 값도 문장에 넣었다. 1라운드 결과 파일의 같은 문장도 정정 표시와 함께 고쳤다. 실행 기록과 실패 기록(첫 CI 실패 원인 등)은 그대로 두었다.
- 고정 공식 source 직접 확인(Sol 인용 링크, `v3.16.0` 태그의 raw 파일):
  - `cmd/prometheus/main.go` 619~623행: `rules.alert.for-outage-tolerance` 기본 `1h`, `rules.alert.for-grace-period` 기본 `10m`.
  - `rules/group.go` 755행 `RestoreForState`: 재시작 시각에서 outage tolerance 이내의 `ALERTS_FOR_STATE`를 읽어 `ActiveAt`을 되살린다. 경우는 셋이다.
    - 이미 firing이던 경보(`timeRemainingPending <= 0`)는 `ActiveAt`을 유지한다.
    - `for`가 grace period보다 짧은 pending 경보는 처음부터 다시 대기한다.
    - 나머지는 grace period 뒤 또는 정지 시간만큼 미뤄 이어진다.
  - 이 프로젝트에서 `for`가 grace보다 짧은 규칙(`5m`·`1m`)은 다시 대기하고, `10m`·`15m` 규칙은 이어질 수 있다. 처음 결과처럼 "모두 처음부터"라는 단정은 틀렸다.
- 새 Prometheus 플래그, runtime이나 시험 변경, 관련 없는 모니터링 개선은 넣지 않았다. 재시작 실험은 하지 않았다(Sol도 source 근거만 확인).

## 결과 저장소 보존
1라운드 결과는 CI head를 지키느라 커밋하지 않고 두었다. 이번에 아래 두 파일을 함께 PR32에 커밋했다.
- `collab/2026-11-19-resume/claude-monitoring-bind-fix-result.md`(2라운드 정정 표시 포함)
- 이 파일

root 사본도 같은 내용이다.

## 검증 범위
- 로컬 전체 시험은 다시 돌리지 않았다(문서만 바뀜).
- 바뀐 파일에서 비밀값 형식(웹훅 토큰, Resend 키, 개인키, 사용자 이메일)을 검색했고 0건이었다.
- 새로 넣은 외부 링크(`v3.16.0/rules/group.go#L755`)와 결과 파일 사이 상대 링크가 실제로 있는지 확인했다.
- 최종 CI는 아래 "최종 HEAD·CI"에 적는다.

## 최종 HEAD·CI
(push 뒤 확인해 worktree와 root 사본에 적는다.)
