# PR #38 독립 재검수 (3라운드, 설치 전 보완) · claude

2026-11-22 · 비작성자(PR38 작성은 agy) · 요청: root Codex 오케스트레이션 `task_3aa5073c2d14`

- **고정 대상:**
  - 최종 head `359e85c401ce0598a23c041ffddb46380de48f03`. 결과서 `agy-pr38-install-gates-result.md` 하나만 더한 tip이다.
  - code `c174e4e4039aab3d5b08e87d3ccd09b9bf4131b3`
  - 비교 기준 `d4ee65d4e116a8c6390e0ac12e80dbed3ef6d949`
  - 그 사이 커밋은 `b6d695a`(시험 설정 게이트, 8.3 거부), `1ec00d7`(시험 임시 경로, `icacls /T`), `c174e4e`(`GetFullPath` 전 원시 경로 8.3 거부), `359e85c`(결과서)다.
  - `c174..359` 차이는 결과서 1파일이다. `pc-pull.ps1` blob `f296c778…`과 `pc-pull-test.ps1` blob `4fd8d56b…`는 두 커밋에서 같다(`git rev-parse <sha>:<path>`).
- **읽은 것:**
  - root `CLAUDE.md`, 내 [`pr-38-claude-round2.md`](pr-38-claude-round2.md), `nov22-agy-pr38-install-gates-request.md`, `agy-pr38-install-gates-result.md`.
  - `d4ee..359` 차이 5파일 전체.
  - c174의 `pc-pull.ps1`: `Assert-SafeLocalPath`, `Load-Config`, `Assert-ValidSourceConfig`, `Invoke-CmdRun`의 시각 선택, main.
  - c174의 `pc-pull-test.ps1`: helper, 1g·1h, `-TestHarness`가 없는 호출 전부, cleanup.
  - `pc-pull.env.example`, `pc-pull-backup.md` §1과 작업 등록 절, `ci.yml`의 pc-pull 단계.
- **읽지 않은 것:** root의 PR38 3라운드 판정(독립 결론 전).
- **바꾸지 않은 것:**
  - source·시험·설정, 작성자 branch, 기존 `pr23-claude-review` checkout, 계정·env·키·SSH·provider, 실제 PC·ACL·작업 스케줄러.
  - commit·push·merge도 하지 않았다.

## 판정: 수용. merge를 막는 지적 없음

- round2의 P3-1(8.3 별칭)과 P3-2(시험용 시간·원본 설정)는 모두 해소됐다(§1).
- 새로 nit 셋을 남긴다(§2). 셋 다 실제 설치를 막지 않는다.
- **최종 CI:** dispatch 시점에는 359의 8잡이 진행 중이었다. 내가 원본을 조회했을 때는 8잡 모두 success로 끝나 있었다. c174 결과(작성자 보고)와 359 결과(내 사후 조회)를 §3에서 구분해 적는다.
- 이 판정은 source·시험·CI 증거에 대한 것이다. 실제 PC 설치, ACL 적용, 작업 스케줄러 등록, OCI 연동을 승인하는 것은 아니다.

## 1. round2 지적별 확인

### P3-2. 시험용 시간·원본 설정 → **해소**

| 확인 항목 | 근거 (c174 `pc-pull.ps1`/`pc-pull-test.ps1`) | 결과 |
|---|---|---|
| 운영 env에서 세 키 거부 | `Load-Config:631`–`:637` | 아래 1 |
| env 파일 안의 표지 거부 | `:627`–`:629` | 아래 2 |
| 거부 시점 | main `:1278`–`:1281`, `:1295` | 아래 3 |
| 운영 기본값 | `:647`, `:777`–`:790`, `:1163`, `:1181`, `:1253`–`:1258`, `:1011`–`:1014` | 아래 4 |
| 자식 시험에만 표지 | 시험 `:203`–`:208` | 아래 5 |
| 1g의 판별력 | 시험 `:568`–`:651` | 아래 6 |

1. 세 키 중 하나라도 비어 있지 않고 프로세스 `PC_PULL_TEST_HARNESS_MODE`가 정확히 `"1"`이 아니면 `CONFIG_INVALID`로 throw한다. 경고만 남기고 계속하는 분기는 없다.
2. env 파일에 표지가 있으면(비어 있지 않으면) 프로세스 표지가 있어도 거부한다. 이 검사가 프로세스 표지 판정보다 먼저 돈다.
   - `$kv`는 `@{}`라 키 대소문자를 무시한다. `pc_pull_test_harness_mode=1`도 같은 키로 걸린다.
   - 실제 사용처(`:647`, `:778`, `:785`)도 같은 `$kv`와 `$isTestHarness`를 쓴다. 그래서 "거부 판정은 통과했는데 값은 적용되는" 어긋남이 생길 수 없다.
3. 게이트는 env 경로 검사(`:584`) 바로 뒤, ACL·저장소 경로·lock보다 앞에 있다.
   - main에서 `Load-Config`가 lock, `InRun`, 선만료보다 먼저 돈다. 그래서 미래 시각으로 forget이 일어날 수 없다.
   - `/fail`은 `InRun` 이후에만 보낸다(`:1295`). 이 거부에서는 성공 heartbeat도 `/fail`도 나가지 않는다. Healthchecks의 누락 알림으로만 드러난다. 이것은 기존 한계와 같다.
4. 운영 기본값:
   - `NowOverride`/`PostNowOverride`는 harness일 때만 값을 갖는다. 그래서 선만료, 시도별 시각, 후만료 모두 `Get-UtcEpochNow`를 쓴다.
   - `AllowLocalSource`도 harness일 때만 참이 될 수 있다(`:647`). 운영에서는 `s3:https://` 원본이 강제된다.
5. `Invoke-PcPull`은 상속된 표지를 먼저 지우고, `-TestHarness`일 때만 자식 환경에 `1`을 넣는다.
   - 부모 시험 프로세스, `ci.yml:89`/`:93`, 저장소 어디에도 표지를 설정하는 곳이 없다(`git grep`).
   - `ExtraEnv`가 뒤에서 덮어쓸 수는 있다. 그러나 표지를 넘기는 호출은 없다.
6. 1g는 실제로 init한 저장소에 1시간 전 snapshot을 넣고, +30일 `PC_NOW_EPOCH_OVERRIDE`를 준다.
   - 게이트가 경고로 퇴행하면 선만료가 그 snapshot을 forget한다. 그러면 `Count == 1` 단언이 깨진다. 단언에 판별력이 있다.
   - hb 로그는 `경로|본문` 형식이다(`:869`의 `…|PC_PULL_OK`). 그래서 `PC_PULL_OK` 부재 단언도 의미가 있다.
   - 다만 snapshot 수와 heartbeat 단언은 첫 하위 사례(now)에만 있다. 나머지 셋(post, local source, env 안 표지)은 거부 문구만 단언한다. post와 local source는 같은 `:631` 반복문을 타고, env 안 표지는 `:627` 분기에서 Load-Config 단계에 바로 거부된다. 그래서 결론은 같다.

- **기존 사례 영향:** `-TestHarness`가 없는 호출(1a–1f, 1g, 1h, 10a)의 env에는 세 키가 없다. 그래서 기존 경로 시험이 새 게이트 때문에 엉뚱한 이유로 통과하지 않는다. 2–10은 `-TestHarness`만 더했고 단언은 그대로다.
- **env 예시:** `PC_CHECK_ACL=` 바로 위에 "운영 우회로 쓰지 말라"는 경고가 붙었다. 세 키와 표지는 예시에 없다.
- **새 운영 우회 설정:** 만들지 않았다. 유일한 새 스위치는 프로세스 환경 표지다. 이것은 root가 요청한 설계이며 env 파일로는 켤 수 없다. 남는 위험은 §2 N2에 적었다.

### P3-1. 8.3 단축 이름 → **해소**

- **수정 위치:**
  - 원시 검사 `:392`–`:399`: `ExpandEnvironmentVariables` 뒤, `GetFullPath` 앞.
  - 정규화 검사 `:420`–`:427`: 드라이브 루트를 뺀 나머지 구성요소.
  - 둘 다 `~[0-9]+(\.[^.]+)?$`이고 `-match`라 대소문자를 무시한다.
- **적용 역할:** repo(`:657`), env(`:584`), PC 비밀번호(`:667`), lock(`:693`), cache(`:700`), log(`:707`), selector(`:739`), OCI 비밀번호(`Assert-ValidSourceConfig:995`). 모두 `Assert-SafeLocalPath`를 지난다.
- **두 검사의 역할:**
  - 실제로 막는 것은 원시 검사다.
  - 정규화 검사는 보조선이다. 상대 경로가 단축 CWD로 풀리는 경우, 또는 `Normalize-LocalPath`(`:345`)가 환경변수를 한 번 더 펼치는 경우를 잡는다. 시험은 원시 쪽만 친다. 문제는 아니다.
- **[직접 probe E]** Windows PowerShell 5.1.26100.9818. 8.3 생성이 켜진 이 PC의 C:에서, c174 blob의 경로 함수만 AST로 추출해 실행했다. 합성 `OneDrive - Synthetic Co`를 만들고, 프로세스의 `OneDrive` 변수만 그 경로로 바꿨다.

| 사례 | 입력 | 결과 |
|---|---|---|
| E1 | `…\OneDrive - Synthetic Co\r1` | `DEST_SYNC_DIR_FORBIDDEN` |
| E2 | `…\ONEDRI~1\r2` (OS가 만든 단축 leaf, 조상은 긴 이름) | `DEST_SHORT_NAME_FORBIDDEN`. round2 probe D에서 ACCEPTED였던 입력과 같은 부류 |
| E3 | `…\OneDrive - Synthetic Co.\r3` (끝 마침표) | `DEST_SYNC_DIR_FORBIDDEN`. 정규화가 마침표를 지운다 |
| E4–E6 | 끝 공백, `..`, `. ` | guard는 허용. 그러나 Win32 생성이 실패한다(아래 참고) |
| E7 | `…\normal-long-folder-name\r7` | ACCEPTED. OneDrive 밖에 생성됐다 |
| E8 | `…\backup~2024\r8` (정상 긴 이름) | `DEST_SHORT_NAME_FORBIDDEN`. 오탐(§2 N1) |

- **PS 5.1과 7의 차이:**
  - [직접] PS 5.1의 `GetFullPath`도 경로 **전체가 존재하면** 단축 이름을 펼쳤다(`…\ONEDRI~1` → `OneDrive - Synthetic Co`). leaf가 없을 때(`…\ONEDRI~1\r2`)는 그대로 뒀다.
  - PS 7의 부분 확장은 작성자 보고다. 이 PC에는 pwsh가 없다.
  - 어느 쪽이든 원시 검사가 `GetFullPath`보다 먼저 돈다. 그래서 결론이 runtime에 의존하지 않는다.
  - CI의 두 runtime 모두 실제 OS 별칭 사례에서 repo `DEST_SHORT_NAME_FORBIDDEN`, log `PATH_SHORT_NAME_FORBIDDEN`, "생성되지 않음" 단언을 통과했다(§3).
- **내 round2 정정:** "`GetFullPath`는 8.3 단축 이름을 긴 이름으로 바꾸지 않는다"는 부정확했다. leaf가 아직 없을 때(첫 init)만 그렇다. 다만 P3-1의 실패 경로, 즉 첫 init 때 동기화 폴더 안에 저장소가 생기는 경로는 그대로 유효했다.
- **유지된 검사:** 삽입한 블록 뒤의 코드는 바뀌지 않았다.
  - 조상 reparse 순회 `:429`–`:466`.
  - DriveInfo fail-closed(Network, 유형, 준비, 비-NTFS) `:472`–`:501`.
  - OneDrive `:507`, Google Drive `:521`, Git `:526`, 중첩 `:743`–`:757`, ACL `:643`.
  - 1a–1f가 두 runtime에서 통과했다.
- **`subst`:** 문서 §1은 "미지원·미검증, 운영 경로로 쓰지 않는다"라고만 적었다. 결과서 §5도 재현하지 않았다고 적었다. 소스가 해결했다고 주장하지 않는다.
- **참고: 끝 공백과 점 (E4–E6)은 우회가 아니다.**
  - [직접] 정규화가 끝 공백과 `..`을 지우지 않는다. 그래서 guard는 `OneDrive - Synthetic Co \r4` 등을 허용한다.
  - 그러나 Python 3.12 `os.makedirs`로 같은 경로를 만들면 `ERROR_PATH_NOT_FOUND`(3)으로 실패했다. Python은 `\\?\` 없이 Win32에 넘기므로 Win32 정규화를 그대로 거친다. 합성 OneDrive 안에는 아무것도 생기지 않았다.
  - [추정] restic(Go)도 짧은 경로는 같은 Win32 경로로 만든다. 긴 경로라 `\\?\`가 붙으면 공백이 든 별도 폴더가 생긴다. 어느 쪽이든 OneDrive 안은 아니다.

### P3-3 (nit, 6b) → 변경 없음, 수용

- root 지시대로 범위를 넓히지 않았다. case 3의 읽기 전용 원본 copy 성공과 함께 보면 판별력이 유지된다.

### 기존 불변식 → 유지

- 이번 diff에서 `pc-pull.ps1`이 바뀐 곳은 `Assert-SafeLocalPath`와 `Load-Config` 두 곳뿐이다. `Invoke-CmdRun`, `Invoke-LocalRetention`, `Assert-ValidSourceConfig`, `expire-select.py`는 그대로다.
- 2–10의 단언도 그대로다. 다음이 모두 유지된다:
  - 선만료 → source 검증 → 읽기 전용 copy → 성공·실패 뒤 POST 정리 → heartbeat.
  - 14일 초과 전량 삭제와 0개 허용(case 4).
  - 6a·6b.
  - 키 분리(5SamePw).
  - 복사 중 14일을 넘은 사본의 후만료 삭제(case 8).

### 시험 helper (`1ec00d7`)

- **임시 루트(`:56`–`:63`):** `%LOCALAPPDATA%\Temp\gongchae-pc-pull-test-<10hex>`를 쓴다. 그 폴더가 없을 때만 `GetTempPath()`로 돌아간다. fallback이 `RUNNER~1` 같은 단축 경로면 모든 사례가 `SHORT_NAME`으로 실패한다. 조용히 통과하지 않고 시끄럽게 실패한다.
- **`icacls /T`(`:120`–`:137`):**
  - 대상은 `$src2`, `$dst6Locks`, `$src7`, `$src8`이고 모두 TMP_ROOT 아래다.
  - icacls 실행 **전에** 복원 목록에 등록한다. 그래서 중간에 실패해도 finally에서 `/T /remove:d`가 돈다.
  - finally 순서(`:1348`–`:1364`, `} finally {`는 `:1348`): hb 서버 종료 → junction 비재귀 삭제 → ACL 복원 → TMP_ROOT 삭제. 자기 fixture 밖은 건드리지 않는다.
- **남는 점:** 복원과 삭제가 `catch {}`, `SilentlyContinue`라 실패하면 GUID 폴더가 남을 수 있다. 이전과 같다.

## 2. 새 지적 (모두 nit, 설치를 막지 않음)

### N1. 정상 이름의 `~숫자`를 오탐하고, 안내 문구가 틀린다

- [직접 E8] 실제로 이미 긴 이름인 `backup~2024`가 `DEST_SHORT_NAME_FORBIDDEN … use full long path`로 거부됐다. 그런데 "긴 경로를 쓰라"는 안내로는 해결되지 않는다.
- 안전한 쪽(fail-closed) 오류라 정도는 낮다.
- **제안:** 메시지나 문서 §1에 한 줄을 더한다. "`~숫자`로 끝나는 폴더 이름은 8.3 별칭과 구별할 수 없어 거부한다. 폴더 이름을 바꾼다."

### N2. harness 모드가 켜져 있어도 로그에 흔적이 없다

- 표지는 프로세스 환경에서만 읽는다(`:630`).
- [추정·미검증] 사용자가 `setx`로 사용자 환경변수에 표지를 넣으면 작업 스케줄러 실행에도 상속될 것이다. 그러면 env 파일의 override가 다시 조용히 적용된다. 성공 heartbeat 본문은 여전히 `policy=wall-clock-14d`다.
- 코드가 표지를 영구 설정하는 경로는 없다(`git grep`). 누군가 일부러 설정해야만 생긴다. 그래서 nit이다.
- **제안:**
  - `$isTestHarness`이고 override가 하나라도 적용되면 `TEST_HARNESS_OVERRIDES_ACTIVE now=… post_now=… allow_local_source=…`를 남긴다. 시험 단언은 바뀌지 않는다.
  - 문서에 "사용자·시스템 환경변수로 설정하지 않는다"를 한 줄 더한다.
- **같은 맥락:** `PC_CHECK_ACL=false`는 운영에서 여전히 조용히 적용된다(`:639`–`:645`). root가 정한 범위대로 예시에 경고만 더했다. 같은 WARN 한 줄을 붙이면 둘 다 로그에 남는다.

### N3. 기록 정확도

- **결과서:** §1의 "최종 HEAD" 행은 "문서 커밋 최종 HEAD 8/8 CI 통과까지 확인"이라고 적는다. 그러나 이 문장은 359 안에 있으므로 커밋 시점에는 확인할 수 없었다. 지금은 사실이 됐다(§3). root 종합에는 "c174 8/8은 작성자 확인, 359 8/8은 Claude 사후 조회"로 구분해 적는 것이 맞다.
- **코드 주석 `:393`:** "(PS 7/.NET GetFullPath expands existing 8.3 aliases)"는 불완전하다. PS 5.1도 경로 전체가 있으면 펼친다(E2). 동작에는 영향이 없다.
- **참고 (보완 불필요):** 1h의 실제 OS 별칭 사례는 `%~sI`로 단축 경로를 만든다. 이것은 조상까지 모두 줄인다([추정] CI에서는 `RUNNER~1`, `GONGCH~1`). 그래서 첫 거부가 `ONEDRI~1`이 아니라 조상에서 날 수 있다. `ONEDRI~1` 하나만의 판별은 lexical 사례와 내 E2가 맡는다.

## 3. 직접 수행 / 보고 인용 / 미실행

**직접 수행:**
- 고정 object의 `git log`/`diff`/`show`, 그리고 두 blob의 동일성 확인.
- `gh pr view 38`: OPEN, draft, MERGEABLE, head `359e85c`. `main`은 `33d9f5a`다.
- **CI, code c174:**
  - run 45029394813(CI 5잡)과 45029394800(Monitoring 3잡). `headSha=c174e4e…`이고 8잡 모두 success다.
  - pc-pull-verify job 137229404106의 원본 로그:
    - checkout `c4e1559 Merge c174e4e… into 33d9f5a…`.
    - `Using PowerShell: 5.1.26100.33822 (Desktop)`과 `7.6.10 (Core)` 각각에서 `[1/10]`–`[10/10]`, `(8.3 short-name active on volume: testing real OS alias)`, `ALL_PC_PULL_TESTS_PASSED`.
- **CI, 최종 359:**
  - run 45030119688(CI 5잡)과 45030119702(Monitoring 3잡). `headSha=359e85c…`, event `pull_request`, 8잡 모두 completed success다. 마지막 잡 alert-e2e는 14:04:17Z에 끝났다.
  - pc-pull-verify job 137231646160의 원본 로그: checkout `85a6939 Merge 359e85c… into 33d9f5a…`. 두 runtime에서 위와 같은 줄을 확인했다.
  - dispatch의 "진행 중"은 내 조회 시점에 이미 끝나 있었다. 359는 c174와 source·시험 blob이 같으므로 이 결과는 예상과 같다.
- **로컬 probe:**
  - 환경: PS 5.1.26100.9818. 고정 c174 blob에서 `Normalize-LocalPath`, `Test-HasRemoteBackendScheme`, `Assert-SafeLocalPath`만 AST로 추출했다(`Fail-Run`은 throw stub).
  - 데이터: scratchpad의 합성 폴더만 썼다. E1–E8을 돌렸고, E4–E6은 Python 3.12 `os.makedirs`로 Win32 생성 위치를 확인했다.
  - 정리: 남은 항목 0개(`LEFT=0`).
  - 실제 OneDrive, restic, 네트워크, 비밀값은 쓰지 않았다.

**보고 인용 (재실행 아님):**
- 작성자의 로컬 PS 5.1 10/10, gitleaks 0건, `merge-tree` RC=0.
- PS 7 `GetFullPath`의 부분 확장 동작(작성자 커밋 메시지와 주석).

**미실행:**
- 전체 `pc-pull-test.ps1`의 로컬 재실행. 로컬 pwsh 실행(이 PC에 없음).
- 의도적인 별칭 문법: `subst`, `fsutil setshortname`(관리자가 `~` 없는 단축 이름 지정), NTFS `::$INDEX_ALLOCATION`. [추정] 운영자 자신이 설정하는 위협 모델 밖이며, 소스가 막지 못할 수 있다.
- 목록 밖 동기화 폴더(Dropbox, iCloud 등). 이전부터 있던 범위다.
- 작업 스케줄러의 사용자 환경변수 상속(N2의 전제).
- 실제 PC 설치, ACL, 작업 스케줄러, OCI·S3·IAM, 실운영 복원.

**그대로 남는 한계 (작성자 결과와 같음):**
- 대화형 작업 스케줄러라 PC가 꺼져 있는 동안 삭제가 지연된다.
- 강제 종료 시 후만료가 보장되지 않는다.
- `InRun` 이전 실패에는 `/fail`이 없다. 이번에 더한 `CONFIG_INVALID` 거부도 여기에 해당하므로 Healthchecks 누락 알림으로만 드러난다.
- HC URL이 비어 있으면 `HEARTBEAT_SKIPPED`로 동작한다.
