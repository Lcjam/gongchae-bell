# PR42 수정본 재검수 · root Codex · 2라운드

**판정: 수용.** 고정 head `2cbd52ef7d7cc17d3139fe95dd0bb0d0dc15e17c`, 이전 `e4685764a8cc14262f7d6af4da631917b0c9398b` 이후 1커밋이다. 4파일 delta(source·JUnit·JAR smoke·alerts 문서)와 수정 결과서를 읽었다. 이 판정은 파일 도구의 단계별 수용이며 감독/coverage/compact나 운영 실행 완료가 아니다. 독립 Sol R2의 결과는 별도로 확인한 뒤 병합한다.

## 이전 지적 해소

- **P2 temp 삭제:** CREATE_NEW를 cleanup try 블록 밖에서 먼저 연다. 이름이 이미 있으면 TEMP_EXISTS(4)이며 cleanup에 들어가지 않는다. 실제 생성 성공 뒤에만 실패 cleanup이 적용된다. deterministic suffix는 package 생성자의 시험 seam이며 운영은 같은 SecureRandom 64비트다. 새 시험은 mark/init의 외부 파일 내용·inode와 L 불변, 다른 이름 성공 뒤 외부 파일 보존을 검사한다. GROUP_MISMATCH 때 자기 temp가 제거되는 단언도 있다.
- **P2 링크:** alerts.md는 실제 tree에 있는 ADR0013을 가리킨다. Javadoc과 PR 본문도 운영 계약이 있는 alerts/ADR로 좁혔다. root가 ADR의 고정 Git blob 존재를 직접 확인했다. 미커밋 collab 설계 chain을 제품 문서에 복사하지 않았다.
- **P3 잠금:** 게시 후 reread가 journal.readLatest의 blocking lock 대신 공통 underLock/tryLock 1회를 사용한다. mark는 교체 전 inode 잠금을 가진 채 새 inode를 읽으며, afterPublish가 잠금 실패도 UNCONFIRMED(4)로 바꾼다. init도 같은 reread를 쓴다. 출판 뒤 실패를 '파일 불변 거부'라고 오보고하지 않는다.
- **P3 root 문구:** 실행자 UID 검사라는 주장을 제거하고 새 임시 파일의 owner/group 검사라고 정확히 쓴다. root verify smoke는 실제 이 동작을 확인한다. UID1000 감독 게이트는 다음 단계로 남긴다.

별도 process LockHolder의 준비 신호 뒤 lock을 점유하는 새 두 시험은 단순 같은 JVM의 overlapping lock 검사와 다르다. 최초 mark/verify/init의 LOCK_BUSY 및 rename 뒤 reread의 MARK_UNCONFIRMED, 잠금 해제 뒤 NOOP를 확인한다. 작성자는 컨테이너 사본에서 옛 temp 삭제와 blocking lock을 각각 되돌리면 새 시험이 실패함을 보고했다. root가 그 역패치를 직접 실행했다고 쓰지 않는다.

## 직접 확인한 검증

2026-11-22 14:17 UTC 전후: 고정 2cbd의 7check가 모두 COMPLETED/SUCCESS, MERGEABLE/CLEAN이었다. [CI45031042409](https://github.com/Lcjam/gongchae-bell/actions/runs/45031042409), [Monitoring45031042429](https://github.com/Lcjam/gongchae-bell/actions/runs/45031042429).

root는 [test job137234484473](https://github.com/Lcjam/gongchae-bell/actions/runs/45031042409/job/137234484473)의 원본 로그에서 check/bootJar 성공, 실제 JAR 명령24줄(추가 root verify 포함), 1279개 적재 클래스의 no-Spring 검사, ALL_JOURNAL_TOOL_SMOKE_PASSED를 직접 확인했다. source 생성자 caller는 public run과 시험 helper 둘뿐이며 같이 갱신됐다. git diff --check도 통과했다.

191test/0fail/0skip 및 Tool15/Journal9, 로컬Linux24JUnit·24smoke는 여기서는 작성자 결과 인용이다. root는 이전 e468 artifact188/12와 새 결과를 혼동하지 않는다. Sol 독립 R2가 새 CI와 artifact를 별도로 검수한다. root는 전체 Gradle/Docker나 실제 운영 CLI를 중복 실행하지 않았다.

## 남은 운영 범위

기존 journal parser/coverage/reapply DELETE 게이트·일반 Spring 경로는 이번 delta에서 바뀌지 않았다. 옛 inode writer 경쟁은 감독의 실제 writer 정지 없이는 남는다. file-force/rename/ENOSPC 실패 및 전원 손실 durability는 미주입이다. 이 한계를 숨기지 않고 감독/권한/배포·backup·monitoring gate, 전수 보존점 coverage/compact, 실제 초기화·설치·복원은 후속으로 둔다. 운영 init/mark는 아직 실행하지 않는다. #17 전체 완료나 M2 활성 근거로 쓰지 않는다.

후속: Sol R2 완료 msg_809ac8cae4eb를 수신하고 전체 `pr-42-sol-round2.md`를 읽었다. 추가 지적 없이 수용이며, Sol은 고정 2cbd의 artifact14757817141 HTML에서 전체191/Tool15/Journal9 무실패·무skip과 새 세 시험 성공을 직접 확인했다. root의 artifact 직접 조회로 바꿔 쓰지 않는다. 두 검수 수용과 고정 tip CI를 조건으로 정상 guarded merge를 진행한다.
