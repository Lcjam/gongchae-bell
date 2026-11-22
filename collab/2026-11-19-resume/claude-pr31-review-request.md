# PR31 독립 검수 재개

보낸 에이전트 root Codex, 비작성자 검수 1라운드. PR28·PR29 검수 완료를 확인했다. 두 PR은 다시 검수하지 않는다.

root `CLAUDE.md`, `collab/reviews/pr-23-codex-metric-followup.md`, `collab/2026-11-19-resume/agy-delivery-metric-commit-request.md`와 `agy-delivery-metric-commit-result.md`를 읽는다. 실제 PR31의 고정 HEAD `4367f94a11f88ed00a950675e0a84b0c56e074e9`(코드 `94f816e8784933cfa97b9f5c5083c446e90728ac`)를 검수한다.

핵심은 worker 진입점에서 HTTP 성공과 외곽 SENT 트랜잭션 커밋을 구별하는지, 지연 두 타이머·duplicateRisk가 afterCommit 뒤 정확히 한 번 기록되는지, 실제 HTTP attempt 카운터는 시도 결과로 유지되는지다. deferred constraint trigger 회귀가 원본 실패/수정 성공을 실제로 구별하는지와 REQUIRES_NEW 예약·부모/notification 잠금·404 TX3·UNCERTAIN/item 보존 회귀를 확인한다. afterCommit 예외가 DB 성공을 잘못 재처리하게 하는 경로가 있는지도 실제 호출자로 판단한다. 추정만으로 결함을 단정하지 않는다.

현재 자기 `pr23-claude-review` 검수 worktree에서 git show 등으로 불변 SHA를 읽는다. 작성자 `issue-7-metric-commit`이나 앱·테스트·설정은 수정하지 않는다. 기존 검수 파일도 보존한다. 원격 CI는 root가 최종 확인하며 새 실패/간극이 없으면 전체 시험을 반복하지 않는다. 서버·env·키·실제 provider·새 에이전트 접근은 없다.

결과 `collab/reviews/pr-31-claude.md`를 자기 worktree와 root에 쓴다. 수용/수정 요청, 심각도·근거 경로, 직접 확인과 작성자 인용·미실행을 구별한다. 끝나면 `작성 완료: <root 결과 경로>` 한 줄만 남긴다.
