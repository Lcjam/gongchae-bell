# #17 새 반례와 작은 안전 경로 교차 검토

보낸 에이전트 root Codex, #17 종합 전 한정 교차 검토다. PR27 검수 완료를 받았고 세 P2 묶음을 작성자 agy에게 이미 돌려보냈다. 그 브랜치는 수정하지 않는다.

자기 #17 01·02와 root `02-codex-review.md`, agy `03-agy-counterexamples.md`를 읽는다. root는 journal 시각이 commit 전에 포착되고 완료 상한이 보이지 않는 반례를 들었으며 agy가 source 순서로 성립을 확인했다. 이 새 반례만 검수하고 기존 m=1h 같은 시간 여유를 안전 증명으로 쓸 수 있는지 정정한다.

특히 agy 대안1의 "정지 뒤 새 dump 또는 D_lo>stop_time 기준"과 "T_w_new가 stop_time보다 과거"를 혼동하지 않는다. 과거 timestamp cutoff로 줄을 지우면서 옛 dump도 허용하면 지연 tx 반례가 남을 수 있다. 앱/백업 완전 정지 barrier 이후의 새 검증 dump만 허용하는 더 작은 경로, bundle 대장+최신 대장 결합으로 지킬 수 있는 범위, 과거 복원점 유실이라는 실제 tradeoff를 각각 분석한다. 충분하지 않은 조건을 충분하다고 쓰지 않는다. 동작을 구현하거나 새 숫자·보유정책을 확정하지 않는다.

복원된 과거 작업의 UNCERTAIN+item 보존도 source와 맞게 대조한다. 첫 보충 수집을 ALERTS_ENABLED=false로 한다고 이후 EMAIL_DIGEST NEW가 자동 배제되는지 실제 `from_backfill`·`first_seen_at`·notification_item 조건으로 반례를 따진다. 현재 regular ingest는 alerts flag가 꺼졌다고 from_backfill=true가 되지 않으므로 flag 한 번 끈 것만으로 익일 이메일 중복까지 막았다고 주장하지 않는다.

결과 `collab/2026-11-19-recovery/03-claude-counterexamples.md`를 자기 worktree와 root에 남긴다. source·시험·구현 설정·실제 서버·계정·env·키/provider는 변경/접근하지 않는다. 기존 3자 원문은 보존한다. 긴 전체 빌드는 필요 없다. 완료 시 `작성 완료: <root 결과 경로>` 한 줄만 남긴다.
