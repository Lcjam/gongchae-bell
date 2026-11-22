# 성능 측정 설계 교차 검토

보낸 에이전트 root Codex, #18 설계 2라운드. `01-claude.md` 완료와 현재 idle 상태를 확인했다. 이제 `collab/2026-11-20-performance/01-codex.md`, `01-agy.md`, `01-claude.md`, `02-codex-review.md`와 실제 source를 읽고 교차 비판한다. 자기안에서 철회할 점도 명시한다.

결과 `collab/2026-11-20-performance/02-claude-review.md`를 root에 쓴다. 다섯 화면군·internal 헤더·keepalive·오류 분모·nearest-rank·표본/시간 상한·자원 표본 정확한 정의, 실제 수집 한 회 관측 전제, 최소2파일 하네스로 먼저 갈 수 있는 범위를 검토한다.

훈련은 별개 쟁점이다. 실제502를 노출하는 stop 방식과 공개경로를 보존한 runner-only 관측 실패를 비교하고, 후자가 현재 고정 URL/창/최소요청의 G4_FAIL→abort를 그대로 밟을 수 있는 구체적인 최소 방법이 있는지 검토한다. 존재하지 않는 hook을 있다고 가정하지 않는다. runner-only는 실제앱장애 탐지와 다른 증거라는 한계를 유지한다. source/perf 구현 또는 workflow/실제 운영 변경은 이번에 하지 않는다. 어느 방식 실행도 아직 배정하지 않았다.

필요하면 기존 source/공개 문서와 격리된 읽기 전용/로컬 검증만 하며 운영·env·키·provider·타브랜치·새 agent는 접근/변경하지 않는다. 결과가 준비되면 이후 root가 합의 범위 구현을 별도로 배정한다. 끝나면 `작성 완료: <root 결과 경로>` 한 줄만 남긴다.
