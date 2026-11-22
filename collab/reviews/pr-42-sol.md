# PR42 독립 검수 · Sol

2026-11-22. **파일 도구 단계는 수용한다. P1·P2 차단 사항은 찾지 못했고, 아래 P3 두 건을 남긴다.** 이 판정은 운영 init/mark 허가나 #17 전체 완료 판정이 아니다. root의 새 PR42 판정은 읽지 않고 결론을 냈다.

- 고정 source: **`e4685764a8cc14262f7d6af4da631917b0c9398b`**
- 비교 base: **`33d9f5a93b0beefb53d29b4d9da82e1a67c492a9`**
- 범위: [고정 9파일 diff](https://github.com/Lcjam/gongchae-bell/compare/33d9f5a93b0beefb53d29b4d9da82e1a67c492a9...e4685764a8cc14262f7d6af4da631917b0c9398b) 전체(798 additions, 9 deletions).
- 기준: root `CLAUDE.md`, recovery-barrier `final.md`, `claude-journal-tool-result.md`, 세 `02-*-review.md`. 후자의 감독·보존점 반례와 최종 단계 분리를 구별해서 대조했다.

## P3 지적

### 1. CREATE_NEW가 실패해도 기존 임시 파일을 지우는 finally

**[소스 직접 확인 + 작은 제어흐름 재현]** [Tool:224–244](https://github.com/Lcjam/gongchae-bell/blob/e4685764a8cc14262f7d6af4da631917b0c9398b/src/main/java/kr/gongchae/bell/alerts/ErasureJournalTool.java#L224)의 `kept=false`와 `finally { if (!kept) Files.deleteIfExists(temp); }`는 try-resource의 `FileChannel.open(CREATE_NEW)`가 실패해도 실행된다.

구체적 반례는 무작위로 고른 `.L.tool-<suffix>`가 이미 있는 경우다. open은 `FileAlreadyExistsException`으로 거부하지만, finally가 이 호출이 만들지 않은 기존 파일을 지운다. 남은 orphan 임시 파일을 자동 삭제하지 않고 보고한다는 방침과 어긋나고, 충돌 상대가 실행 중이면 상대의 게시도 실패시킬 수 있다. 정상 대장 L 자체를 삭제하는 경로는 아니며, SecureRandom의 64비트 이름이 우연히 충돌할 확률은 작으므로 P3로 분류한다.

**최소 수정:** 생성 성공 뒤에만 별도 `created=true`를 세워 정리하거나, 생성 성공이 확인되는 범위 안으로 cleanup을 옮긴다. 실패한 CREATE_NEW로 기존 이름의 파일을 삭제하지 않는다. 최소 회귀는 이미 존재하는 temp를 두고 생성 실패 뒤 바이트가 그대로인지 확인하는 것이다. 이름 선택을 제어하는 작은 seam이면 충분하며 별도 상태 원장은 필요 없다.

**실행 증거의 범위:** ignored `build/pr42-review/TempCleanupProbe.java`에서 동일한 CREATE_NEW/kept/finally 제어흐름을 Windows Java 17 source-file mode로 실행했다. 결과는 `CREATE_NEW refused; preexisting temp retained=false`였다. POSIX 속성 인자는 제외한 Java 제어흐름 재현이며, 실제 Tool/JDK21/Linux 충돌 시험을 실행했다고 세지 않는다. probe가 만든 가짜 파일과 임시 디렉터리만 사용했고 실행 후 지웠다.

### 2. root 실행이 항상 OWNER_MISMATCH라는 문구는 구현보다 넓다

**[소스 직접 확인, 실제 root 재실행 없음]** [alerts.md:73](https://github.com/Lcjam/gongchae-bell/blob/e4685764a8cc14262f7d6af4da631917b0c9398b/docs/ops/alerts.md#L73)는 “root로 실행하면 `ERASURE_TOOL_OWNER_MISMATCH`로 거부한다”고 쓴다. 실제 검사는 caller UID가 아니라 파일/디렉터리의 uid/gid와 새 temp의 uid/gid 비교다.

따라서 정상 UID1000 소유 디렉터리와 한 marker짜리 정상 L에 root가 접근하면 `init`의 ALREADY, `mark`의 NOOP, `verify`는 새 temp를 만들지 않고 계약 확인과 force를 마칠 수 있다. CI smoke가 입증한 것은 **tail이 일반 해지 줄인 root mark의 새 temp 생성/게시 거부**다. 이 결과를 모든 명령/전이의 root 거부로 확대할 수 없다.

**최소 수정:** 문구를 새 파일을 게시하는 경로의 소유자 불일치 거부로 좁힌다. caller 자체를 거부하는 새 정책/기능은 이번 계약에 필요 없다. 이 차이는 L의 owner/gid 보존을 깨지 않으므로 비차단이다.

## 계약과 구현·시험 대조

| 항목 | 직접 확인한 구현/원본 증거와 판단 |
|---|---|
| 첫 인자와 기존 기동 | main은 첫 인자가 정확히 `erasure-journal`일 때만 분기하고 System.exit로 끝낸다. 기존 `SpringApplication.run`은 그대로다. 실제 JAR의 class-loading smoke가 Tool 적재와 SpringApplication/context·Hikari·Flyway·PostgreSQL·logback·Tomcat 미적재를 검사한다. 다른 위치의 인자를 도구 명령으로 오인하지 않는다. |
| 공통 strict parser/marker | `ErasureJournal.readAll`은 기존 positional read loop의 추출이고 parser·coverage·subscription ID 추출은 바뀌지 않았다. newMarker는 기존 reserved tag, version8, variant2와 74 random bits를 조합한다. 임의 UUID/시각 입력은 없다. mark는 기존 ID 충돌도 재선택한다. 10,000 marker 검사와 기존 Journal 9시험이 있다. |
| missing/empty/기존 marker | init은 missing에만 새 파일을 link로 게시한다. 기존 한 marker는 ALREADY, empty/nonmarker/multiline/corrupt는 거부한다. mark는 missing/empty를 거부하고 marker tail만 NOOP다. 기존 empty를 marker로 조용히 승격하지 않는다. |
| ENOENT/EACCES | stat은 NoSuchFileException만 missing으로 바꾸며 AccessDeniedException은 고정 오류+exit4다. Linux smoke의 other UID init/check와 group reader mark/verify가 실제 ACCESS_DENIED였다. reader check는 성공한다. |
| inode/OS lock | mutation/verify는 NOFOLLOW open과 tryLock으로 기존 inode를 잠근다. 같은 JVM 겹침은 LOCK_BUSY, 다른 프로세스 null도 같은 경로다. init 경쟁은 CREATE_NEW temp+no-replace link로 수렴한다. 같은 JVM lock/20×6 동시 init 시험은 있으나 cross-process 실행 증거는 없다. |
| prefix/게시/권한 | next는 원문 전체 bytes에 marker 한 줄을 붙인다. tmp write loop→readback/strict→uid/gid/mode→file.force→link 또는 ATOMIC_MOVE→dir sync→새 경로 reread다. mark는 0600/0640을 보존하며 size tripwire가 있다. uid/gid는 디렉터리와 맞춰 검사한다. setgid 상속·불일치 거부, prefix cmp, inode 교체 시험이 있다. P3 temp cleanup 예외는 위에 별도로 적었다. |
| NOOP/ALREADY/verify | 성공 코드를 내기 전에 공통 confirm이 권한 확인+file.force+dir sync를 한다. dir sync 실패 뒤 이미 게시된 mark/init은 UNCONFIRMED4이고, 재시도 NOOP/ALREADY/verify도 sync가 계속 실패하면 성공으로 바꾸지 않는다. sync 복구 후 재시도 성공을 시험한다. check는 read-only 문법 검사이며 durability 완료를 주장하지 않는다. |
| 후속 검증/공개 출력 | 게시 후 다시 경로로 열어 strict bytes와 attrs를 비교한다. 실패는 UNCONFIRMED다. 경로/UUID/원문/환경/예외 원문 대신 고정 코드와 counts만 출력한다. stdout 패턴과 stderr 공백 검사가 실제 JAR smoke에 있다. |
| 기존 복원 DELETE 게이트 | `ErasureReapplyCommand.java`, `ErasureJournalTest.java`는 base 대비 동일함을 git diff로 확인했다. 기존 coverage/parser와 M1 skip 3조건 문구를 제거하지 않았다. CI 전체 check도 성공이다. 새로운 primitive를 restore DELETE 우회로 연결하지 않았다. |
| 운영 문서/초기 M1 | runbook/alerts는 수동 empty 생성 예시를 제거하고 감독 작업 미구현 및 그 병합·검수 전 운영 init/mark 금지를 명시한다. 유효 M1/B 없음·B=[] 포기를 파일 도구가 판정/승인하지 않는다. 그 선행조건과 empty repo 새 현황 확인·정상 만료 대기는 작성자 결과서/최종 합의의 다음 게이트에 남아 있다. CHANGELOG도 Unreleased 파일 도구와 후속 미완료를 구분한다. |

## CI 귀속과 직접 확보한 원본

**[GitHub 원본 직접 조회]** 두 run은 모두 `pull_request`, attempt1, metadata의 head_sha가 고정 `e4685764...`다. 7개 job이 전부 success다.

| run | job | 완료 UTC |
|---|---|---|
| [CI 45028240737](https://github.com/Lcjam/gongchae-bell/actions/runs/45028240737) | secrets, deploy-verify, test, backup-verify: 4/4 success | 2026-11-22 13:37:47 |
| [Monitoring 45028240700](https://github.com/Lcjam/gongchae-bell/actions/runs/45028240700) | compose-integration, rules, alert-e2e: 3/3 success | 2026-11-22 13:42:26 |

PR CI가 실제 checkout한 것은 head 자체가 아니라 merge ref **`9ee8b54ea862d51036363bfe40543d543b8d81ce`**다. test 로그와 [merge commit 원본](https://github.com/Lcjam/gongchae-bell/commit/9ee8b54ea862d51036363bfe40543d543b8d81ce)에서 부모가 정확히 base `33d9f5a...`와 head `e4685764...`임을 확인했다. 다른 tip이나 이전 CI를 재사용하지 않았다.

- CI test 로그: `ubuntu-24.04-arm`, Linux-ARM64, `check bootJar` 성공. 실제 JAR smoke의 명령 기대값 **23줄**, 별도의 1,277-class 미기동 확인 줄, `ALL_JOURNAL_TOOL_SMOKE_PASSED`를 직접 확인했다. root mark OWNER_MISMATCH 및 group/other ACCESS_DENIED도 이 원본에 있다.
- `test-results` artifact **14756562069**를 내려받아 HTML을 읽었다. metadata head도 고정 SHA이며 digest는 `sha256:2b8ff72758cec6cc98761e00f55d046cf4b50515a2eec320930b988e417c9d9f`다(이 값은 API 기록값이며 ZIP digest를 별도 계산한 것은 아니다).
- 해당 artifact의 ToolTest **12 tests / 0 failures / 0 skipped**, JournalTest **9 / 0 / 0**를 직접 확인했다. 소스의 setgid 시험도 skip 없는 12개에 포함된다. 이는 ARM CI 실행 결과를 읽은 것이며 내가 로컬 Linux에서 21시험을 다시 실행한 결과가 아니다.
- 로컬 사본은 ignored `build/pr42-ci.log`, `build/pr42-review/test-results/`에만 두었다. GitHub connector로 run/job/artifact 원본을 조회했고, CLI는 원본 로그·artifact 다운로드와 checkout SHA 확인에 사용했다.

**[작성자 보고에 의존]** 로컬 Linux JDK21 x86_64·UID1500/보조G2000에서 21 JUnit 통과(0 skip), 로컬 실제 JAR smoke, 정상 Spring 경로의 가짜 DB 기동 실패 관측은 작성자 보고다. 이를 독립 재실행으로 표시하지 않는다. root 13:24의 실제 서버 dir0750/1000:1000, ubuntu EACCES/privileged L ENOENT 관측도 제공된 운영 맥락이며 내가 서버에서 확인한 값이 아니다.

자료 위치 주의: 검수 계약 `final.md`와 작성자 결과서는 root의 로컬 collab 자료로 읽었다. 고정 head의 `git ls-tree`에는 recovery-barrier 폴더가 없다. 따라서 alerts.md의 로컬 합의 링크를 고정 GitHub tree 안에 포함된 증거라고 세지 않는다.

## 미실행 한계와 다음 게이트

**file.force 실패, atomic rename 자체 실패, ENOSPC, 전원 손실 뒤 durability, cross-process lock 거부는 독립 실행하지 않았다.** 작성자도 앞의 fault들과 cross-process 실행 부재를 명시했다. 실제 파일 도구 소스는 force/게시 실패를 비정상 종료로 전파하고 게시 후 실패를 UNCONFIRMED로 남기며, dir sync 실패·재시도와 temp 생성 EACCES를 Linux 시험으로 덮었다. 따라서 미실행 fault 전부를 증명했다고 보지는 않지만, 이 단계의 소스 병합을 차단할 구체적인 L 손실 반례로 판단하지 않았다. 전원 손실 보장은 미확인 상태다.

반대로 **old-inode writer 경쟁은 실제로 남는다.** 도구 A가 옛 inode를 잠그고 새 inode로 rename한 뒤, 옛 inode에서 기다리던 writer B가 그 잠금을 얻어 쓰면 추가 줄은 새 경로에서 사라진다. 같은 길이 overwrite는 size tripwire로도 잡지 못한다. 이는 파일 도구가 해결했다고 주장하지 않고 명시적으로 운영 writer 정지를 전제로 하므로 이번 단계 비차단이다. 감독 없이 운영 실행한다면 차단 사유다.

감독 PR에서는 deploy→backup 양 lock, 앱 양색 실제 stop/DB client0·prepared0, 앱 stop 전 durable guard, SIGKILL 후 orphan helper identity/실제 종료, 실패 조회와 empty 구분, live tar의 잠금 전 게시·monitoring 우회, UID1000:G와 setgid 설치, backup.sh EACCES 구분을 실제 fixture로 닫아야 한다. 안전 검증→M1 false resume→독립 정상 backup→동일 snapshot meta/B 확인→M2 gate 순서를 유지하고 child backup 우회 토큰으로 대신하지 않는다.

보존 inventory/compact·PC offline 정책·정비 cadence·dump 밖 NEW 중복 가능성 대 추가 누락 선택도 이번 PR이 해결한 것으로 세지 않는다. 유효 M1 및 PC 단독 B를 임의로 폐기하거나, 기존 확정 14일 초과 전량삭제·0개 허용을 14일 이내 포기로 확대하면 안 된다.

새 Docker/Gradle/공개 HTTP 부하·브라우저·SSH·DB·실대장·provider 실행은 없었다. 기존 checkout/branch·제품 source/test/config·사용자 원본·다른 agent 문서는 변경하지 않았으며 commit/push/merge도 하지 않았다. 검수서와 ignored probe/원본 다운로드만 만들었다.
