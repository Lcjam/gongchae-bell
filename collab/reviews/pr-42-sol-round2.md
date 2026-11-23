# PR42 수정본 독립 재검수 · Sol

2026-11-22. **고정 수정본을 수용한다. 이전 Sol P3 두 건은 해소됐고, 이번 delta에서 추가 P1·P2·P3 지적은 없다.** 게시 후 reread의 잠금 대기와 미커밋 계약 참조도 수정 요청에 맞게 처리됐다. 파일 도구 단계의 수용이며 운영 init/mark 허가나 감독·보존점·compact 완료 판정은 아니다. root의 새 판정은 읽지 않았다.

- 고정 head: **`2cbd52ef7d7cc17d3139fe95dd0bb0d0dc15e17c`**
- 이전 source/직접 부모: **`e4685764a8cc14262f7d6af4da631917b0c9398b`**. 그 위 **1커밋**, 4파일 134 additions / 23 deletions임을 git으로 확인했다.
- [검수한 고정 delta](https://github.com/Lcjam/gongchae-bell/compare/e4685764a8cc14262f7d6af4da631917b0c9398b...2cbd52ef7d7cc17d3139fe95dd0bb0d0dc15e17c): Tool, ToolTest, 실제 JAR smoke, alerts 운영 문서.
- 읽은 자료: root와 동일 hash의 `CLAUDE.md`, 자기 `pr-42-sol.md`, `collab/2026-11-19-resume/nov22-claude-pr42-fixes-request.md`, 작성자 `claude-pr42-review-fixes-result.md`. 기존 전체 계약을 다시 설계하지 않고 수정과 영향 경로만 검수했다.

## 수정별 독립 판단

| 대상 | 직접 확인과 판단 |
|---|---|
| 외부 기존 temp 보존 | `FileChannel.open(CREATE_NEW)`가 cleanup try/finally **밖**에 있다. 이미 존재하면 `TEMP_EXISTS`(exit4)를 내고 정리 구간에 들어가지 않는다. 다른 open 실패도 그 구간에 들어가지 않는다. 기존 파일을 지우던 원인이 제거됐다. Supplier는 package-private 생성자의 시험 seam이며 운영은 기존 SecureRandom 64비트 suffix를 유지한다. 외부 seed/운영 옵션은 추가하지 않았다. |
| 자기 temp cleanup | 생성 성공 후의 write/parse/attrs/force 실패에는 기존 finally cleanup이 유지된다. init link·mark move 실패 정리도 생성에 성공한 temp만 받는다. 새 충돌 시험은 mark의 외부 파일 bytes+inode, init의 외부 bytes와 missing L을 확인한다. 정상 mark 후에도 외부 temp를 남기고 verify `temps=1`로 보고한다. setgid/GROUP_MISMATCH 시험은 고정 `mine` 이름의 **자기 생성 temp가 제거됐음**을 명시적으로 확인한다. |
| 게시 후 reread 잠금 | init/mark 두 호출 모두 공통 새 `reread(file,bytes,attrs)`를 쓴다. 이 메서드는 blocking `journal.readLatest()` 대신 기존 `underLock`→한 번의 `tryLock`을 사용하고 strict bytes/attrs를 비교한다. 다른 프로세스 점유 시 Stop3가 바깥 `afterPublish`에서 해당 `INIT_UNCONFIRMED`/`MARK_UNCONFIRMED` exit4로 변환된다. 이미 게시한 것을 초기 LOCK_BUSY/파일 불변으로 잘못 보고하지 않는다. |
| 별도 process 시험의 판별력 | `LockHolder`는 ProcessBuilder로 실행한 **별도 JVM**이고 OS lock 취득 뒤 `LOCKED`를 출력한다. 시작 전 시험은 mark/verify/init 각각 20초 내 LOCK_BUSY3, bytes 불변과 잠금 해제 후 DONE을 확인한다. 게시 후 시험은 rename 뒤 sync hook에서 **새 경로의 파일**을 child가 잠근 것을 확인한 뒤 reread에 진입한다. 20초 내 MARK_UNCONFIRMED4, marker 게시 사실, 해제 후 NOOP 성공을 확인하므로 구버전 blocking reread와 구별된다. init에도 같은 공통 reread가 적용되지만 게시 후 경쟁을 init으로 따로 실행한 시험은 아니다. |
| 문서·Javadoc·PR 본문 | alerts는 계약 참조를 실제 고정 tree에 있는 `docs/adr/0013-erasure-journal-host-file.md`로 바꿨고 Javadoc은 ops 계약/ADR을 가리킨다. PR 본문의 Markdown target도 ops 절과 ADR 경로이며 collab 참조는 0개였다. 고정 tree에서 두 대상 파일과 ops 절을 확인했고 src/docs의 해당 recovery-barrier collab 참조도 0개다. PR 본문은 Git 객체가 아니라 조회 시점의 가변 metadata임을 구별한다. |
| root 주장 범위 | 새 temp의 owner/gid가 맞지 않는 **게시 경로**의 거부로 좁혔다. 실행자 자체를 거부하는 검사가 아니며 root verify/NOOP/ALREADY는 해당 거부에 걸리지 않는다고 명시한다. 새 smoke는 tail이 일반 줄인 root mark OWNER_MISMATCH3 뒤 root verify OK0을 확인한다. 기존 smoke의 파일 상태 비교도 유지된다. UID1000 실행과 실제 설치는 감독 게이트로 남았다. |

**기존 동작 유지:** delta는 raw bytes 복사/marker 생성·공통 strict parser/coverage·restore DELETE 게이트를 바꾸지 않았다. main의 첫 인자 분기, 정상 Spring 경로, NOOP/ALREADY의 file+dir force, 게시 전 force→원자 게시→dir force 순서와 고정 출력 처리는 유지된다. `TEMP_EXISTS`4가 새 명시 오류이며, 기존 명령/성공 상태를 제거하지 않았다. `GongchaeBellApplication`, `ErasureJournal`, `ErasureReapplyCommand`, CI 설정, runbook, CHANGELOG가 이전 source와 동일함을 git diff exit0으로 확인했다.

## 고정 tip의 GitHub 원본 증거

**[직접 조회]** connector의 commit/run/job/artifact metadata와 GitHub CLI 원본 로그·artifact를 확인했다. 이전 head의 CI를 재사용하지 않았다.

| run | 정확한 head_sha / 결과 | 완료 UTC |
|---|---|---|
| [CI 45031042409](https://github.com/Lcjam/gongchae-bell/actions/runs/45031042409) | `2cbd52ef...`, PR attempt1. test·deploy-verify·backup-verify·secrets **4/4 success** | 2026-11-22 14:09:53 |
| [Monitoring 45031042429](https://github.com/Lcjam/gongchae-bell/actions/runs/45031042429) | `2cbd52ef...`, PR attempt1. rules·compose-integration·alert-e2e **3/3 success** | 2026-11-22 14:13:15 |

실제 test checkout은 PR merge ref **`6827667cf8050898ecddab21ed6a9e56af278339`**다. 로그의 checkout SHA와 [commit 원본](https://github.com/Lcjam/gongchae-bell/commit/6827667cf8050898ecddab21ed6a9e56af278339)에서 부모가 base `33d9f5a93b0beefb53d29b4d9da82e1a67c492a9`와 고정 head `2cbd52ef...`임을 확인했다. 조회 당시 PR metadata의 head/base도 같다.

**직접 읽은 실행 결과:**

- test job **137234484473**, `ubuntu-24.04-arm`/Linux-ARM64. `check bootJar`의 BUILD SUCCESSFUL과 실제 JAR smoke 명령 결과 **24줄**, `ALL_JOURNAL_TOOL_SMOKE_PASSED`를 확인했다.
- class-loading 검사: 1,279 classes, Spring context/Hikari/Flyway/PostgreSQL/logback/Tomcat 미적재 확인 줄. root verify OK0이 추가됐으며 나머지 정상 전이·손상/빈 파일 거부·역할별 EACCES 결과도 유지됐다. smoke는 DB/앱 env/network 없는 runtime 기반 컨테이너 경로이며 실제 운영 설치 시험은 아니다.
- `test-results` artifact **14757817141**, metadata head는 고정 SHA다. digest `sha256:29c1bd207be93fd3eb921f1554336d3929b4b7e6746f884be1d8812ceffcd774`는 API/업로드 로그 값이며 ZIP digest를 별도 재계산하지는 않았다.
- 내려받은 HTML: 전체 **191 tests / 0 failures / 0 skipped**, Tool **15 / 0 / 0**, Journal **9 / 0 / 0**. 새 충돌·별도 process 초기 lock·게시 후 lock 시험 모두 success다. Tool HTML의 각 실행 시간은 0.006s / 0.064s / 0.078s로 기록돼 있다. 한 JVM의 OverlappingFileLockException 시험만 통과한 것을 cross-process 증거로 바꿔 세지 않았다.
- 원본 사본은 ignored `build/pr42-review/round2-ci-test.log`, `round2-test-results/`에만 두었다. 위 수치는 **CI 실행 원본을 직접 읽은 결과**이며 내가 Linux 시험을 재실행했다는 뜻이 아니다.

## 작성자 보고와 미실행의 구분

**[작성자 보고 인용]** 로컬 Linux JDK21/x86_64·비root UID1500/보조G2000의 Tool15+Journal9=24 JUnit 무skip 성공, 로컬 24명령 smoke, 복사본에 옛 동작을 역패치했을 때의 두 실패는 작성자 결과서의 보고다. 보고된 실패는 기존 temp 삭제 시 `NoSuchFileException`, blocking lock 복원 시 20,000ms timeout이다. 나는 역패치 복사본/실패 로그를 별도 수집하거나 재실행하지 않았다. 소스상 각 회귀 시험의 판별력을 확인하고, 최종 ARM CI에서 그 시험들이 실제 실행·성공했음을 독립 확인했다.

**[이번 검수 미실행]** 새 Gradle/Docker/JAR/실CLI 시험, 로컬 probe 추가 실행, SSH·운영 대장·실DB·OCI/PC·계정/env/키/provider·공공 API·부하·브라우저 시험은 하지 않았다. 불필요한 전체 시험 반복 없이 delta와 원본 증거로 검수했다. 일반 Spring 경로는 source 불변과 CI 근거로 보았으며 새 기동 관측은 없다.

file.force 실패·rename 자체 실패·ENOSPC·전원 손실 뒤 durability는 여전히 미실행이다. 게시 후 실패는 기존 dir sync 실패 시험과 이번 reread lock 점유 시험으로 확인한 범위다. 이전 검수의 cross-process 미실행 한계는 **작성자 로컬/ARM CI 시험 증거로 보완됐지만**, 내가 별도 process를 직접 실행한 것은 아니다.

old-inode 대기 writer의 줄 손실과 같은 길이 overwrite의 tripwire 한계, 실제 writer 정지/DB0·durable guard·orphan helper·권한 설치·tar/monitoring 우회·보존 inventory·M1 전환·compact는 그대로 다음 게이트다. 해당 감독 PR 검수 전 운영 init/mark 금지 문구도 유지된다. 이번 수정으로 이 범위를 완료했다고 보지 않는다.

기존 frontend checkout은 `feat/#19-frontend-refresh` / `2c58aa1d0d916597ed63c750c4e2a7941499158b` 그대로이며 tracked diff는 0이다. source/test/config·다른 branch·사용자 디자인·다른 agent 파일은 변경하지 않았다. 검수서와 ignored 원본 다운로드만 만들었고 commit/push/merge·새 agent·메뉴 대리 승인은 없었다.
