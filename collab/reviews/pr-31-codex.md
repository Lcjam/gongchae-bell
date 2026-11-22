# PR31 검수 · Codex

2026-11-20, 비작성자 root Codex. 대상 HEAD `4367f94a11f88ed00a950675e0a84b0c56e074e9`, 코드 `94f816e8784933cfa97b9f5c5083c446e90728ac`, 기준 main c4465f5. 판정: **수용**.

성공 지표의 기록 자격을 SQL UPDATE 결과에서 외곽 SENT 트랜잭션의 실제 커밋 완료로 옮겼다. `dispatchClaim`의 TransactionTemplate 안에서 REQUIRES_NEW 예약이 끝난 다음 등록하므로 콜백은 외곽 A의 동기화다. HTTP attempt 카운터는 실제 공급자 결과를 계속 세며 두 성공 타이머와 duplicate-risk만 afterCommit에서 기록한다. 끝점 finishedAt은 기존 HTTP 결과 후 시각을 유지한다. NEW 목록은 immutable copy로 캡처한다.

실제 호출자인 AlertWorker는 dispatch 예외를 로그만 남기고 즉시 다시 보내지 않는다. 커밋 뒤 지표 기록 예외가 나더라도 이미 SENT인 행은 PENDING/RETRY 선점이나 SENDING 회수 대상이 아니므로 다시 전송되는 경로가 없다. 동기화 밖 즉시 기록 분기는 현재 private 메서드의 유일한 호출 경로에서는 실행되지 않는다. 이번 diff는 R 예약·부모 잠금·404 TX3·해지와 UNCERTAIN/item 보존을 변경하지 않는다.

새 회귀는 worker 정상 진입점과 격리 PG deferred constraint trigger를 사용한다. HTTP_5XX 이후 2차 HTTP SUCCESS와 R(attempt 2) 영속화는 허용하고 NEW.status=SENT의 외곽 COMMIT에서만 실패시킨다. HTTP 실행 1건·DB SENDING·미확정 attempt·item 보존·attempt SUCCESS +1과 성공 지표 0을 함께 단언한다. 이후 회수·정상 커밋에서 세 성공 지표가 정확히 +1이 된다. 원본의 즉시 기록은 0 단언을 통과할 수 없으므로 구현을 단순 복제한 시험이 아니다. trigger는 finally에서 제거한다.

직접 확인: 실제 source/test/docs 전체 diff, dispatch의 실제 호출자·예외 처리와 미터 기록 코드, 최종 원격 PR HEAD·CI. 작성자 원본 실패 실행과 169개 시험 통과는 결과 보고 인용이며 로컬 전체 시험을 다시 실행하지 않았다. 최종 CI [44513729882](https://github.com/Lcjam/gongchae-bell/actions/runs/44513729882) 4잡과 Monitoring [44513729831](https://github.com/Lcjam/gongchae-bell/actions/runs/44513729831) 3잡이 모두 COMPLETED/SUCCESS인 것을 직접 조회했다. Claude도 `pr-31-claude.md`에서 독립 수용했다.

DB commit 성공 직후 프로세스가 죽으면 아직 scrape되지 않은 인메모리 표본이 사라질 수 있다. 기존 Prometheus 수집의 한계이며 DB 기반 게이지와 의미가 다르다. 실제 provider·서버·키·env에는 접근하지 않았다. 운영 발송 활성화나 이슈 #7 전체 완료의 증거로 쓰지 않는다.
