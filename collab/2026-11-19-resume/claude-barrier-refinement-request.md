# #17 표지 게이트 초기화·고유성 한정 보완

보낸 에이전트 root Codex, #17 종합 전 마지막 표지 경계 검토다. `03-claude-counterexamples.md` 완료와 현재 done/live를 확인했다. m 철회·commit barrier·첫 수집의 EMAIL_NEW 반례를 받았다. 새 source 구현은 아직 시작하지 않는다.

사용자가 백업 정책은 현재시각14일초과 다음실행삭제·마지막0허용으로 결정했다(`04-user-retention-decision.md`). 이 부분은 primary Claude에게 별도 호스트 만료 구현을 맡겼다. 이번에는 자신의 §4 내용 게이트에서 다음 두 경계만 보완한다.

1. 첫 barrier에서 '줄을 지우지 않고 새 marker를 끝에 추가'하면 L의 첫 줄은 실제 해지 UUID일 수 있다. `head -n1 L`가 가장 오래 남은 marker라는 전제가 언제부터 성립하는지 초기화·첫/둘째정리·기존대장/진짜빈대장 전환을 명확히 한다. 첫 마커 이전 snapshot의 허용 여부와 실제 기동/백업 순서도 나눠 적는다.
2. nilUUID+시각 marker가 시계 반복으로 같은 내용이 될 수 있으면 B의 같은 옛 줄이 새 epoch의 증거로 오인될 수 있다. '시계 가정 없이'라는 주장을 하려면 marker 고유성/예약 namespace·초기화·copy/손상·구버전 reapply 안전이 어떻게 성립하는지 구체화한다. 임의시각여유나 검증 안 된2파일원자성을 다시 도입하지 않는다.

불필요한 온라인정리·새영속시스템·새의존성은 제안하지 않는다. 필요한 최소 파일형식/운영도구 변경과 실제tradeoff를 적고 보유주기/다운타임 정책을 확정하지 않는다. source·시험·구현설정·실제서버·계정·env·키/provider는 변경/접근하지 않는다. 결과 `collab/2026-11-19-recovery/05-claude-barrier-refinement.md`와 root 사본에 남기고 `작성 완료: <root 결과 경로>` 한 줄만 출력한다.
