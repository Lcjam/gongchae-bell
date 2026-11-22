# #17 교차 검토 · claude

2026-11-19 · 2라운드 · 대상: [`01-codex.md`](01-codex.md), [`01-agy.md`](01-agy.md), 내 [`01-claude.md`](01-claude.md)

- `01-claude.md`를 다 쓴 뒤에 두 안을 읽었다. `02-agy-review.md`는 이 검토가 독립적이도록 읽지 않았다.
- source·설정·계정·env·키·OCI·PC는 바꾸거나 조회하지 않았다. 새로 확인한 외부 사실은 §1 끝의 restic 소스 한 건이다(2026-11-19 열람).

## 1. 합의된 것

- 첫 OCI 밖 사본은 현재 PC에 둔다. pull은 바깥으로 나가는 HTTPS만 쓰고, 집 쪽 포트는 열지 않는다. OCI와 다른 restic 비밀번호를 쓰고, Healthchecks에는 별도 check를 둔다. 새 서비스·VM·유료 자원은 필요 없다.
- `keep-daily 14`는 PRD의 달력 14일이 아니다. `keep-within(-daily)`도 최신 snapshot 기준이다(agy와 내가 각각 공식 문서로 확인). 장기 실패 때 "달력 기준 삭제"와 "최신 1개 생존" 중 하나를 고르는 일은 사용자 결정이다.
- 덤프 시각은 `dump_started_utc`와 아카이브 생성 시각으로 판정한다. mtime과 업로드 시각은 쓰지 않는다. 묶음 대장은 최신 대장의 바이트 prefix여야 한다(agy와 내가 같은 `cmp` 검사를 따로 제안했다). 허용 복원 최소시점은 대장 커버리지 시작 이상이어야 한다.
- 복원은 격리 PG에서 발송을 끈 채 한다. 키 검증, 재적용, 실측 기록 뒤에 RTO/RPO를 정한다. VM 소실 때의 유실 한계는 숨기지 않는다.
- 온라인 정리를 하려면 교체되지 않는 고정 잠금 파일, 완전 쓰기, fsync, 원자 rename, 디렉터리 fsync가 필요하다. 유효한 줄만 건져 덮어쓰지 않는다.

## 2. 반례와 사실 오류

| # | 대상 | 주장 | 반례·근거 | 결과와 제안 |
|---|---|---|---|---|
| X1 | agy §2.1·§5-5 | pull 전용 **읽기 전용** Customer Secret Key로 `restic copy` | restic `cmd_copy.go`는 원본을 `openWithReadLock(ctx, gopts, gopts.NoLock, …)`로 연다. 이 읽기 잠금은 백엔드에 잠금 객체(`restic.LockFile`)를 만들고, 갱신하고, 지운다([cmd_copy.go](https://raw.githubusercontent.com/restic/restic/master/cmd/restic/cmd_copy.go), [lock.go](https://raw.githubusercontent.com/restic/restic/master/internal/repository/lock.go)). OCI `read objects`는 GET·LIST만 되고 새 객체 생성은 `manage`다([OCI 정책 참조](https://docs.oracle.com/en-us/iaas/Content/Identity/Reference/objectstoragepolicyreference.htm)) | 그대로면 copy가 시작부터 잠금 생성에 실패한다. 성립 조건은 `--no-lock`이 원본에 전달되는 것이다(`--from-repo` 옵션 교환 뒤 전달 여부는 확인하지 못함). 리허설에서 a(read+`--no-lock`) → b(잠금 경로만 `manage`) → c(버킷 `manage`) 순서로 확인한다(01-claude §3.1) |
| X2 | agy §2.3-3·§2.4③ | 정리 때 `erasure.meta`(`coverage_since_utc`)를 대장과 "함께 원자 교체" | rename은 파일 하나씩만 원자적이다. ① 대장 rename 뒤 메타 rename 전에 crash하면, 메타가 옛 커버리지를 말해 오래된 덤프가 게이트를 통과하고 해지가 살아난다(불안전). 메타를 먼저 바꾸면 이 crash는 과잉 거부로 안전하다. ② 하지만 `backup.sh`는 Java 잠금 없이 `cp`로 복사한다. 셸 `flock(1)`은 flock(2)이고 JDK는 fcntl 레코드 잠금이라 Linux 로컬 파일시스템에서 서로를 막지 못한다([flock(2)](https://man7.org/linux/man-pages/man2/flock.2.html), [UnixFileDispatcherImpl.c](https://github.com/openjdk/jdk/blob/master/src/java.base/unix/native/libnio/ch/UnixFileDispatcherImpl.c)). 그래서 snapshot 하나에 "새 대장 + 옛 메타"가 담길 수 있다 | 워터마크를 **대장 첫 줄의 nil UUID 줄**로 넣는다(01-claude §3.5). 그러면 파일 하나라 rename 한 번이 원자적이다. 기존 `readStrict`·재적용 명령이 받아 0행을 삭제하므로 이전 버전과 호환된다. 별도 메타를 고집하면 "메타 먼저"와 "백업이 같은 fcntl 잠금 아래 두 파일 복사"가 둘 다 필요하다 |
| X3 | agy §2.3-4·§2.6-2, codex "대장 정리" | 정리 전에 "모든 허용 복원점이 제외", "OCI+NAS/PC+수동 사본 0건 확인" | 꺼진 PC와 수동 사본은 조회로 0건을 증명할 수 없다. 이것을 안전 조건으로 두면 정리를 영원히 못 하거나, 증명할 수 없는 선언에 기대게 된다. agy 자신의 재개 조건 `coverage_since ≤ T_dump`(§2.3-2·§2.6-1)가 이미 그보다 오래된 덤프를 막는다. **해지 부활 안전은 게이트가 맡고**, 사본 삭제 증거는 보유 고지가 맞는지 확인하는 용도로만 쓴다 | 두 증거를 나눈다(codex의 "각각 증거를 둔다"와 같은 방향). 정리 조건: `T_w_new ≤ 지금 − P − m`. 정리 뒤 남은 오래된 사본은 복원이 거부될 뿐이다 |
| X4 | 두 안 모두 | 현재 runbook을 고칠 대상으로 지목하지 않음 | main `docs/ops/backup-restore.md` §7은 ① 4단계에서 운영 Compose의 postgres에 `pg_restore --clean`을 실행한다. ② 5단계에서 백업 안의 오래된 대장으로 재적용한 뒤 **그 파일로 호스트 대장을 교체**한다. 호스트가 살아 있는 사고에서 백업 뒤 해지가 살아나고, 호스트 대장에서도 영구히 사라진다(01-claude R1·R2) | agy §2.5의 원칙(운영 DB 무복원, 최신 대장)과 같은 결론이다. 다만 이것은 **이미 main에 있는 결함**이다. #17 첫 단계에서 source 없이 문서부터 고친다. "호스트 대장을 더 오래된 사본으로 덮지 않는다"를 명시한다 |
| X5 | codex "제안" | PC restic 저장소를 "프로젝트의 ignored 백업 경로"에 둔다 | 그 경로는 저장소 작업 트리 안이다. `git clean -xdf`나 worktree 정리로 유일한 OCI 밖 사본이 지워질 수 있다. 세 에이전트가 같은 트리에서 넓은 파일 권한으로 일한다. 프로젝트 폴더가 동기화 대상이면 통제하지 못하는 사본이 생긴다 | 저장소 밖의 동기화하지 않는 로컬 경로(`%LOCALAPPDATA%\…`, agy·나 동일)와 사용자 전용 ACL을 쓴다 |
| X6 | codex "대장 정리" | 서로 다른 언어의 잠금이 같은 파일을 잠그는지 혼합 프로세스 시험으로 확인 | 기본 경우는 이미 공식 문서로 답이 있다. Linux 로컬 파일시스템에서 `flock(1)`/flock(2)와 Java `FileChannel.lock()`(fcntl)은 상호작용하지 않는다(위 X2 출처). NFS·CIFS에서는 상호작용한다. 또 POSIX 레코드 잠금은 프로세스가 그 파일의 아무 fd나 닫으면 풀린다([fcntl_locking(2)](https://man7.org/linux/man-pages/man2/fcntl_locking.2.html)) | 셸 정리 스크립트는 기각한다. 시험은 정리기를 Java로 만들 때, 그리고 `flock --fcntl`(OFD) 변형을 쓸 때만 한다. OFD와 기존 레코드 잠금의 상호작용은 이번에 확인하지 않았다 |
| X7 | agy §2.4 | 고정 잠금 파일 규약(append·readStrict·compact가 모두 `erasure.lock`) | ① agy §2.4 머리말이 설명한 "옛 inode에 쓰고 유실" 실패가 **배포 중 옛 버전 앱**에서 다시 생긴다. 옛 앱은 `erasure.lock`을 모르고 대장 inode만 잠근다. ② 같은 JVM에서 잠금을 쥔 동안 `erasure.lock`을 다른 채널로 열고 닫으면 잠금이 풀린다(X6) | 두 색이 모두 새 버전일 때만 온라인 정리를 허용하는 배포 게이트가 필요하다(codex도 혼합 배포 구간을 지적했다). 첫 단계는 앱을 멈추고 하는 정리로 버전 문제를 피한다(agy A안, 01-claude §3.5) |
| X8 | agy §2.1·§3 | 04:00 pull, 충돌하면 "pull 실패" | 반대 방향이 빠졌다. PC copy가 원본 읽기 잠금을 쥐는 동안 서버의 `forget --prune`(배타)은 실패한다. 작업 스케줄러의 놓친 실행 보충으로 PC가 03:00~03:30 사이에 켜지면 겹친다. PC가 copy 도중 죽으면 다른 호스트의 잠금이라 프로세스 생존 검사를 못 한다. 그러면 시각으로만 stale을 판정한다(문서상 30분, 소스상 5분마다 갱신). 잠금을 얻을 때 stale 잠금을 자동으로 지우는지는 확인하지 못했다 | 시작 시각이 02:50~03:40이면 기다리는 가드를 둔다(서비스 `TimeoutStartSec=30min`, agy 확인). runbook에 서버 `RESTIC_PRUNE_FAIL`이 나면 잠금 확인과 `restic unlock` 단계를 넣는다 |
| X9 | agy §1.2 | "pg_dump는 실행 시작 시점 스냅샷"(문서 인용), FAQ의 copy 잠금 설명 | pg_dump 문서는 "일관된 백업"만 말하고 시작 시점이라고 하지 않는다. 생성 시각이 스냅샷보다 앞선다는 근거는 소스 순서다(`CreateArchive` → `ConnectDatabase` → `setup_connection`). `--list` 헤더는 문서에 예시로만 있다. 인용한 FAQ(`075_faq.html`)는 내 조회에서 404였다 | 결론(하한으로 쓴다)은 같다. 근거를 소스로 바꾸고, 헤더 형식은 리허설에서 확인한다. copy 잠금 근거는 X1의 소스로 대체한다 |
| X10 | agy §2.2-3 | 수동 사본 "USB·클라우드 복사 금지" | 이번 배정은 요청하지 않은 금지 규칙을 필수로 만들지 말라고 했다. 금지만으로는 지켜졌는지 검증할 수도 없다 | 수동 사본은 만든 시각과 삭제 시각을 기록하는 것으로 바꾼다. 안전은 X3의 게이트가 맡는다 |
| X11 | agy §2.4② (경미) | 시각 `< cutoff` 줄을 걸러 낸다(여유 시간 포함) | 시계 역행이 여유 시간보다 작으면 안전하다. 앞부분만 지우는 규칙(처음으로 cutoff 이상인 줄에서 멈춤)은 역행 크기를 가정하지 않는다 | 앞부분 규칙을 권한다. 반대 의견은 아니다 |

## 3. 자기 철회와 보완

1. **사실 보완(F8 해소):** `restic copy`는 원본 snapshot 구조체를 저장할 때 `Parent`와 `Original`만 바꾼다. 시각과 호스트는 그대로다([cmd_copy.go](https://raw.githubusercontent.com/restic/restic/master/cmd/restic/cmd_copy.go)). 그래서 PC의 `keep-within-daily`는 원래 백업 시각으로 동작한다. 01-claude §1 F8과 §5의 "확인 필요"에서 이 부분을 뺀다.
2. **정정(PC 보관 상한):** 01-claude §3.2는 PC 상한을 "대략 14일+1.5일"로 적었다. 이것은 pull이 계속 성공할 때만 맞다. PC는 켜져 있는데 copy가 며칠 실패하면, 단계 ①의 forget이 오래된 최신 snapshot을 기준으로 돈다. 그래서 상한은 **14일 + 마지막 성공 pull 이후 일수**다. 한계 고지 2에 이 경우를 더한다.
3. **정정(F6 해석):** 01-claude는 copy의 원본 잠금 종류를 "문서에 없음"으로 두었다. 소스로 확인하니 **읽기(비배타) 잠금이고 저장소에 객체로 만든다.** 01-claude §3.1의 a 선택지는 `--no-lock`이 원본에 전달될 때만 성립한다는 조건부 판단으로 유지한다.
4. **채택(agy):**
   - pg_dump를 `TZ=UTC`로 실행해 생성 시각 헤더를 모호하지 않게 한다. `docker compose exec -e TZ=UTC`처럼 backup.sh의 작은 변경이다.
   - 비밀값 공생존 조건을 넣는다. 두 restic 비밀번호와 앱의 웹훅·토큰·HMAC 키를 VM과 백업 밖의 비밀번호 관리자에서 함께 꺼낼 수 있어야 한다.
   - 첫 리허설은 **PC 사본에서** 복원한다. 그래야 OCI 밖 사본이 실제로 쓸 수 있다는 근거가 된다.
5. **채택(codex):** 사본의 물리 삭제 증거와 복원 허용 판정을 따로 둔다. 혼합 버전 배포 구간을 따로 검증한다(X7).
6. **유지:** 워터마크를 대장 안에 두는 안, 앞부분만 지우는 정리, 하한 시각 판정, 앱 정지 정리를 첫 단계로 하는 안은 반례를 찾지 못해 그대로 둔다. 다만 nil UUID 줄을 현재 main의 재적용 명령으로 읽는 시험은 아직 하지 않았다(코드 읽기로만 판단).

## 4. 최소 구현 경로 (세 안을 합친 제안)

| 단계 | 내용 | source |
|---|---|---|
| A | runbook 결함 X4를 고친다(격리 일회용 PG, 살아 있는 대장 우선, 호스트 대장을 오래된 사본으로 덮지 않음). 덤프 → 대장 복사 순서를 불변식으로 적는다. PC 저장소를 저장소 밖에 만들고(`init --from-repo --copy-chunker-params`), 작업 스케줄러(로컬 forget 먼저, 02:50~03:40 가드), Healthchecks pull check를 설정한다. X1의 자격 a→b→c를 확인한다. PC 사본으로 첫 격리 복원 리허설을 하고 실측을 기록한다 | 없음 |
| B | `backup.sh`: `dump_started_utc`, `TZ=UTC`, 보관 옵션은 사용자 결정에 따른다. 복원 검증 스크립트: sha, 하한 시각, `cmp` prefix, 사후 0건 | 셸과 테스트 |
| C | 워터마크를 아는 판독기, 앱을 멈추고 실행하는 정리 명령, 키 검증 명령 | Java와 테스트 |
| D (선택) | 고정 잠금 파일, 온라인 정리, 두 색 버전 게이트 | Java, 배포 |

A로 #17의 "실제 OCI 밖 사본, 자동 pull 근거, 리허설 기록, 운영 DB 무덮어쓰기"를 채운다. 공개 보유기간을 실제 절차와 맞추는 일은 B·C 뒤에 한다.

## 5. 남은 선택 (사용자 결정)

| 쟁점 | codex | agy | claude |
|---|---|---|---|
| 워터마크 위치 | 언급 없음 | 별도 `erasure.meta` | 대장 첫 줄 nil UUID(X2) |
| 정리의 안전 조건 | 모든 허용 복원점 제외와 연속성 검증 | 사본 조회로 0건 확인 | 게이트(`T_w + m`)가 안전을 맡고 사본 삭제는 고지 증거(X3) |
| 정리 방식 | 자동이면 고정 잠금과 버전 검증 | A: 정지 수동 / B: 온라인 | 정지 정리 먼저, 온라인은 버전 게이트 뒤 |
| PC 저장소 위치 | 프로젝트 ignored 경로 | 저장소 밖 로컬 | 저장소 밖 로컬(X5) |
| 수동 사본 | 운영 절차가 무심코 쓰지 않게 | 금지 | 기록하고 게이트로 보호(X10) |
| 장기 실패 때 보관 | 실제 잔류 설명 | 사용자 결정 | 사용자 결정. 나는 restic 옵션과 한계 고지를 권한다 |
| PC 자격 범위 | 언급 없음 | 읽기 전용 | 리허설로 a→b→c 중 최저선(X1) |
| 값 `P`·`m`·유예·정리 주기 | 미정 | 미정 | 제안값만(21일·1시간·2일·월 1회) |
| 복원 때 중복 발송(백업 시점의 PENDING 작업, 슬롯) | 언급 없음 | 언급 없음 | #17에서 다룰지 별도 이슈로 둘지 |
