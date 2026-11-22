# 복구 대장 정책 최종 반례 확인 재개

보낸 에이전트 root Codex, #17 교차 검토 후속이다. 이전 `agy-live-collector-alert-plan.md`는 제출 완료이므로 다시 작성하지 않는다. Orca 재시작 뒤 이 패널의 PTY가 없음을 확인했고 기존 `issue-17-recovery-design` worktree에서 복구한다.

root `CLAUDE.md`, `collab/2026-11-19-recovery/`의 01/02/03 검토와 `04-user-retention-decision.md`, `05-claude-barrier-refinement.md`, 실제 main의 ADR/backup-restore/journal parser·restore CLI를 읽는다. 사용자 04는 실행 시점 14일 초과 전부 삭제·0개 허용이며 재확인 질문은 하지 않는다.

Claude 05의 barrier marker/prefix 검증안을 실제 현재 parser·복원 명령과 비교해 조율자가 구현 가능한 최소 최종안을 결정할 수 있게 남은 반례와 필요한 변경 범위를 좁힌다. 특히 **SUB=0만으로 M2 대장 누락/손상을 면제하지 않는다**. 기존 PR27의 M1 예외는 SUB=0 + erasure_included=false + M2 미개시 근거가 함께 있는 경우뿐이다. 거대한 새 서비스나 일시정지 없는 복잡한 압축은 추가하지 않는다. 앱 양색·백업 완전 정지와 DB 세션0 확인, dump와 대장 일관성, 최신 대장 재적용, sender/item 보존의 현실적인 절차를 확인한다.

현재는 설계/검수만으로 앱·테스트·설정·다른 브랜치·서버·키·env·실제 provider·새 agent를 변경/접근하지 않는다. 과거 제안을 모두 다시 쓰지 말고 수용/철회/남은 blocker를 근거와 함께 `collab/2026-11-19-recovery/06-agy-final-check.md` root에 쓴다. 끝나면 `작성 완료: <root 결과 경로>` 한 줄만 남긴다.
