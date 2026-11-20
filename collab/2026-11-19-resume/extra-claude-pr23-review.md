# 추가 Claude의 PR23 독립 검수

보낸 에이전트 root Codex, PR23 수정 후 독립 검수 라운드다. 사용자가 Claude·agy 추가 세션을 각각 최대 두 개 더 열어도 된다고 승인했다. 기존 Claude는 문서 검수·실제 배포 준비, 기존 agy는 PR21 재검수 뒤 PR23 운영 설정·NEW 지연 수정 담당이다. 이번 Claude 세션은 별도 검수자다.

먼저 CLAUDE.md를 읽는다. 앱 코드·시험·구현 설정은 읽기 전용으로 검수하고 다른 작성자 브랜치를 수정하지 않는다. 검수 worktree는 launch 결과의 별도 checkout이며 base는 PR23 source다. root와 기존 issue-7-alert-delivery에는 작성자가 있으므로 소스를 고치지 않는다.

## 대상과 원본

오늘 확인한 PR23 최종 head는 `b50d3d044f2502a6c8568ad4b15284c589758add`다. 최초 code `1b7c23cde830424a0bff9f9219379af985dbc063`, 부모 잠금 수정 code `83dcf9cf900d58741d1890eea1cd22cb594b706d`, 기준 main `ed3f28f38623afeccd41006548a9e4f54ed0e301`이다. 최종 CI test·deploy-verify·backup-verify·secrets 전 잡 success와 MERGEABLE을 root가 오늘 직접 조회했다.

PRD FR-A4·A5·A8/G7, #7 이슈, #6 계약, root `collab/2026-11-15-m2-readiness/12-claude-revised-contract.md`·13·14, `collab/2026-11-17-m2-readiness/03-codex-alert-eligibility-decision.md`와 구현 source를 먼저 읽는다. 현재 부모 잠금 수정의 실제 source·정상 진입점 회귀를 독립적으로 대조한다. 첫 결론을 쓴 뒤 root `pr-23-codex.md`, `pr-23-sol.md` 및 작성자 결과·수정 결과와 교차 확인한다.

R 전 child DML·잠금 금지, 부모→자식 순서, claim/시도 펜싱, unknown 뒤 동일 full hash/typed target, R 뒤 현재 D-3 자격과 예약 보존, 생성/해지 경합, item 반환, 다음 창/23시간/최대3, 404 TX3·RFC8058·라벨/로그 경계를 본다. 전역 부모 잠금이나 새 회차 재선점 정책을 발명하지 않는다. 구체적 의심만 작고 격리된 대역/PG 진단으로 확인한다. 전체 빌드·무거운 병렬 Docker 시험을 반복할 필요는 없다.

기존 agy에게 아직 남은 두 P2의 수정도 이번 재개에 배정했다. 세 worker env의 실제 Compose 전달 누락과 이메일 NEW 수집→발송 지연의 묶음 전 대기 누락이다. 해당 수정은 작성자가 진행하므로 이 세션이 구현하지 않는다. 현재 불변 head의 판단과 후속 수정이 필요한 범위를 구분해 결과에 남긴다. root가 최종 새 head를 전달하면 그 차분을 후속 검수한다.

## 결과와 경계

root `collab/reviews/pr-23-claude.md`에 불변 SHA·판정·문제 위치/등급/재현·확인 범위·직접 실행과 보고의 구분을 적는다. 검수 worktree에 같은 파일을 남길 수 있지만 별도 PR이나 main 병합은 하지 않는다. root의 기존 보호 파일·다른 검수 기록을 보존한다.

실제 env·키·API/provider·개발 DB·서버·계정·OCI·DNS·결제·실제 외부 발송 접근/변경은 없다. 진단은 자기 ignored .tools 밑에서만 하고 source를 고치지 않는다. Windows helper는 Hidden, 한 번 대기는 60초 이내다. 끝나면 `작성 완료: <root 결과 경로>` 한 줄만 남긴다.
