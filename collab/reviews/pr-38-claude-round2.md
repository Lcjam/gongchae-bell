# PR #38 독립 재검수 (2라운드) · claude

2026-11-22 · 비작성자(PR38 작성은 agy) · 요청: root Codex 오케스트레이션 `msg_c5c11298101f`

- **고정 대상:**
  - 최종 head `d4ee65d4e116a8c6390e0ac12e80dbed3ef6d949`
  - 수정 source `96d54b175d217f01fcd4d3252cd1866db5decb35`
  - 비교 기준(이전) `6b9b7f80dcc5b1a686da87737e02136afba5c812`
  - `pc-pull.ps1` blob `bf6b834495e13a3c35499471b21f9d1764eb80db`. scratchpad 사본과 `git hash-object`로 대조해 일치했다.
- **읽은 것:**
  - root `CLAUDE.md`, 이전 지적 [`pr-38-codex.md`](pr-38-codex.md)·[`pr-38-sol.md`](pr-38-sol.md), 작성자 결과 `agy-pc-pull-result.md`·`agy-pr38-review-fixes-result.md`.
  - 고정 head의 `pc-pull.ps1` 전체, `pc-pull-test.ps1`의 1·6번 사례와 helper, `ci.yml`, `pc-pull-backup.md`, `pc-pull.env.example`.
  - `6b9b7f8..d4ee65d`의 차이(5파일). 이 범위에서 `expire-select.py`와 `backup-test.sh`는 바뀌지 않았다.
- **읽지 않은 것:** `pr-38-codex-round2.md`(독립 판정 전).
- **바꾸지 않은 것:** source·시험·설정, 작성자 branch, 기존 checkout, 계정·env·키·SSH·provider, PC 설치, 정기 작업. commit·push·merge도 하지 않았다.

## 판정: 수용. merge를 막는 지적 없음

- 이전 지적 5건(Codex R1·R2, Sol P1·P2·P2)은 모두 해소됐다(§1).
- 새로 P3 세 건과 nit 하나를 남긴다(§2). 셋 다 운영자가 직접 이상한 설정을 넣어야 드러난다.
  - P3-2는 이번 수정 이전(`f8b3171`)부터 있던 문제다.
  - 실제 PC 설치 전에 처리할지는 root가 판단한다.
- 이 판정은 source와 시험 증거에 대한 것이다. 실제 PC 설치, OCI 연동, 정기 실행을 승인하는 것은 아니다.

## 1. 이전 지적별 확인

| 이전 지적 | 수정 | 확인 근거 | 판정 |
|---|---|---|---|
| Codex R1 / Sol P1: 조상 junction, 보조 경로 미검사 | 아래 표 | 아래 표 | **해소** |
| Sol P2: PS7 ACL API | 아래 표 | 아래 표 | **해소** |
| Sol P2: case 6가 copy 단계의 lock을 증명하지 못함 | 아래 표 | 아래 표 | **해소** (nit은 §2) |
| Codex R2: 14일 초과 사본 예외 복원 문구 | 아래 표 | 아래 표 | **해소** |

**Codex R1 / Sol P1 — 경로 보호**

- **수정:**
  - `Assert-SafeLocalPath`(`:378`)가 leaf부터 드라이브 루트까지 모든 **기존** 조상을 순회하며 reparse point를 거부한다. leaf가 아직 없어도 마찬가지다.
  - 드라이브 조회는 fail-closed다: DriveInfo 예외, 준비 안 된 볼륨, Network, Fixed/Removable 외 유형, 비-NTFS를 모두 거부한다.
  - 적용 역할: env, PC 비밀번호, OCI 비밀번호, lock, cache, log, selector.
  - 중첩 검사 `Assert-NoPathOverlap`을 더했다.
  - `PC_ALLOW_CUSTOM_PATHS`는 제거했고, 설정하면 거부한다(`:604`).
- **실행 순서(코드 확인):**
  1. env 경로 검증 → regular 파일 확인 → 읽기 → ACL.
  2. PC 비밀번호: 경로 검증 → 읽기.
  3. lock·cache·log·selector 검증 → 중첩 검사 → 그 뒤에야 `LogFilePath` 활성화(`:729`).
  4. lock 획득 → 디렉터리 생성.
  5. OCI 비밀번호 경로는 로컬 선만료 **뒤** `Assert-ValidSourceConfig`(`:955`)에서 검증하고 읽는다.
- **시험:**
  - 1e: 기존 leaf와 미생성 deep leaf 모두 `DEST_REPARSE_POINT_FORBIDDEN`, target 아래에 디렉터리가 생기지 않음.
  - 1f-1–7: UNC cache면 lock 미생성, OneDrive의 log/env/password, Git의 lock, 중첩, 미마운트 드라이브.
  - 5: OneDrive의 OCI 비밀번호 → 선만료 뒤 실패.
- **내 probe(§3):**
  - A: 살아 있는 junction 조상 → 거부.
  - B: target이 사라진 dangling junction 조상 → 거부.
  - C: 링크 객체 자체에 RA(read attributes)를 deny한 junction → 거부.

**Sol P2 — PS7 ACL**

- **수정:**
  - `Assert-RestrictedWindowsAcl`이 `Get-Acl -LiteralPath`를 쓴다(`:307`).
  - 시험 helper는 `icacls`로 바뀌었다.
  - `ci.yml`에 `pwsh` 단계가 추가됐다.
- **확인:**
  - `Invoke-PcPull`(시험 `:173`)은 `(Get-Process -Id $PID).Path`, 즉 같은 runtime으로 `pc-pull.ps1`을 실행한다. 그래서 pwsh 단계에서는 1d의 기본 ACL 검사(`PC_CHECK_ACL` 생략 = true)도 pwsh에서 돈다.
  - 원본 job 로그 137149798492에 `Using PowerShell: 5.1.26100.33822 (Desktop)` 뒤 1–10번과 `ALL_PC_PULL_TESTS_PASSED`가 있다. 이어서 `Using PowerShell: 7.6.10 (Core)` 뒤 1–10번과 `ALL_PC_PULL_TESTS_PASSED`가 있다.

**Sol P2 — case 6**

- **수정:**
  - 6a는 다음을 단언한다: `LOCAL_REPO_UNAVAILABLE`, `SOURCE_COPY_START` 미도달, `/fail` 전송, `PC_PULL_OK` 부재.
  - 6b는 copy 호출 때만 shim이 `dst6b/locks`에 쓰기 거부를 걸고 실제 restic 0.19.1에 위임한다. 단언 항목:
    - `PRE_RETENTION_SUMMARY … expire=0` 통과, `SOURCE_COPY_START` 도달.
    - lock 오류, `SOURCE_COPY_FAIL` 또는 `LOCAL_DEST_LOCKED`.
    - `POST_RETENTION_SUMMARY` 실행, 복사된 snapshot 0개, `/fail` 전송, OK 부재.
- **확인:**
  - 6a는 사실 선만료보다 앞의 준비 확인(`cat config`, `:999`)에서 실패한다. 제목에는 "pre-retention"이라 적혀 있지만 단언(`LOCAL_REPO_UNAVAILABLE`)과는 맞는다.
  - 6b는 "선만료 통과 → 실제 copy 단계"를 6a와 분리해 보여 준다.
  - destination이 lock을 생략하는 회귀가 생기면 6b의 copy가 성공해 시험이 실패한다. 즉 판별력이 있다.

**Codex R2 — 14일 초과 사본**

- **수정:** `pc-pull-backup.md` §5가 이렇게 바뀌었다.
  1. `run` 실행.
  2. 로그의 `PRE`·`POST_RETENTION_SUMMARY … expire=0` 확인.
  3. `snapshots`로 14일 이내 사본 확인.
  4. 남은 사본이 0개면 "복원 가능한 유효 사본 없음"으로 처리하고 만료 사본을 예외 복원하지 않는다.
- **확인:** 04(14일 초과 전량 삭제, 0개 허용)와 맞다. 새 예외나 재질문은 없다.

**변경 없는 run 계약 (회귀 없음 확인):**
1. `Load-Config` → lock.
2. `cat config` → 선만료(명시 ID forget → `prune --max-unused 0` → 재조회 검증).
3. source 설정과 비밀번호 → `--no-lock` snapshots → `--mode kept` → copy(`-r` 로컬, `--from-repo` 원본). 이 구간은 `try`로 감싸 오류를 잡는다.
4. 후만료는 항상 실행.
5. source 실패면 `LOCAL_RETENTION_OK_BUT_SOURCE_FAILED`와 `/fail`. 모두 성공했을 때만 성공 heartbeat(`:1128`–`:1242`).

## 2. 새 지적

### P3-1. 8.3 단축 이름으로 OneDrive/Google Drive 문자열 검사를 우회할 수 있다

- **위치:** `Assert-SafeLocalPath`의 OneDrive 비교(`:489`–`:501`)와 Google Drive 정규식. 둘 다 `GetFullPath` 결과 문자열을 비교한다. 그런데 `GetFullPath`는 8.3 단축 이름을 긴 이름으로 바꾸지 않는다.
- **[직접 probe D]** 8.3 이름 생성이 켜진 이 PC의 C: 볼륨에서 확인했다.
  - 합성 폴더 `OneDrive - Synthetic Co`를 만들고, 자식 프로세스의 `OneDrive` 변수만 그 경로로 설정했다.
  - 긴 이름 `…\OneDrive - Synthetic Co\replica`는 `DEST_SYNC_DIR_FORBIDDEN`으로 거부됐다.
  - 같은 폴더의 단축 이름 `…\ONEDRI~1\replica`는 **ACCEPTED**였다.
  - Google Drive 정규식(`google\s*drive`)도 `GOOGLE~1`에는 같은 방식으로 맞지 않는다. 이것은 코드로 판단한 것이고 따로 실행하지는 않았다.
- **실패 순서:**
  1. 운영자가 env에 단축 경로를 적는다. 또는 단축 형태로 확장되는 변수를 쓴다.
  2. 동기화 폴더 안의 저장소, 로그, 비밀번호 파일이 허용된다.
- **정도:** reparse가 아닌 lexical 별칭이고, 운영자가 그런 경로를 써야 한다. 그래서 P3다.
  - [추정] `subst` 드라이브도 같은 부류다. Fixed로 보이고 OneDrive 변수와 문자열이 다르다. 시스템 드라이브 문자를 만드는 일이라 probe하지 않았다.
- **최소 보완 [제안]:** 정규화한 경로의 구성요소에 `~숫자`가 있으면 거부한다(fail-closed, 몇 줄). `subst`는 문서의 한계로 적는다.

### P3-2. 운영 env 파일이 시험용 설정을 조용히 받아들인다 (이번 수정 이전부터 있음)

- **위치:** `Load-Config`(`:616`, `:746`–`:759`), 사용처 `Invoke-CmdRun`(`:1132`, `:1150`, `:1222`).
  - `PC_NOW_EPOCH_OVERRIDE`와 `PC_POST_NOW_EPOCH_OVERRIDE`는 선만료, source `kept` 선택, 후만료의 "현재 시각"을 바꾼다.
  - `PC_ALLOW_LOCAL_SOURCE=true`는 `s3:https://` 원본 강제를 끈다.
  - 셋 다 env 예시와 문서에 없다. `f8b3171`부터 있었다.
  - 설정돼도 경고 로그가 없다.
- **[반례] 미래 시각 설정:**
  1. 운영 env에 `PC_NOW_EPOCH_OVERRIDE`가 실제 시각 + 7일로 남아 있다.
  2. 선만료가 실제로는 7–14일 된 **유효 사본**을 forget하고 prune한다.
  3. source 복사도 그만큼 줄어든다.
  4. 그런데도 `PC_PULL_OK`가 나가고 성공 heartbeat가 전송된다. 로그는 `policy=wall-clock-14d`라고 적는다.
  - 이것은 04가 허용하지 않은 "14일 이내 사본 폐기"다.
- **과거 시각 설정:** 최신 snapshot이 selector의 미래 허용치(300초, `expire-select.py:22`)를 넘으므로 대부분 선택 실패로 끝난다. 즉 fail closed에 가깝다.
- **같은 부류:** `PC_ALLOW_CUSTOM_PATHS`는 이번에 제거됐다. 서버 `backup.sh`는 `BACKUP_KEEP_DAILY`를 "정책이 돌고 있다고 오해하게 만든다"는 이유로 거부한다. 같은 기준이면 이 설정들도 운영 경로에서 받지 않아야 한다.
- **최소 보완 [제안]:**
  - 이 세 키는 시험 harness가 `ExtraEnv`로 넘기는 **프로세스 환경변수의 시험 표시**가 있을 때만 받는다. 운영 env 파일에서는 `CONFIG_INVALID`로 거부한다.
  - 최소한 설정됐을 때 `WARN NOW_OVERRIDE_ACTIVE`를 남기고 성공 heartbeat를 보내지 않는다.
- **덧붙임:** `pc-pull.env.example`은 `PC_CHECK_ACL=`을 "Optional Overrides"에 경고 없이 나열한다. 문서 §1의 "`PC_CHECK_ACL=false`는 운영 우회 수단으로 사용하지 않는다"와 어긋나므로 예시에 같은 경고를 단다.

### P3-3 (nit). 6b의 lock 오류 단언은 어느 저장소의 lock인지 특정하지 않는다

- **사실:** `src2`는 case 2에서 `Set-ReadOnlyTreeAcl`이 걸린 뒤(`:596`) 끝까지 쓰기가 거부된 상태다. 그래서 6b의 `unable to create lock in backend`라는 문자열만으로는 원본과 대상 중 어느 쪽의 lock 실패인지 구별할 수 없다.
- **현재 판별력의 출처:** "`--no-lock`이 원본에 적용된다"는 사실은 case 3에서 RO 원본 copy가 성공한 것으로 증명된다. 그래서 두 시험을 함께 놓으면 결론이 성립한다. 6b 혼자서는 성립하지 않는다.
- **보완 [제안]:** 6b에서 `LOCAL_DEST_LOCKED`(restic rc 11)를 고정해 단언하고, 오류에 `dst-copy-lock` 경로가 포함되는지도 단언한다. [추정] restic의 local backend 오류에 경로가 찍힌다는 것은 이번에 로그로 확인하지 않았다.

### 참고 (지적 아님). `*_ACCESS_UNVERIFIED`가 실제로 거의 닿지 않는 분기다

- [추정] `Test-Path`는 접근 거부를 예외로 던지지 않고 `$false`를 돌려준다. 그래서 순회 중 "존재하지만 읽을 수 없는" 조상은 없는 것으로 취급되고, 그 조상의 reparse 검사는 건너뛴다.
- [직접 probe C] 링크 객체에 RA를 deny해도, 부모 폴더의 목록 권한으로 속성을 읽을 수 있었다. `Test-Path=True`였고 결과는 거부였다. 그래서 일반 설정에서 실제로 뚫리는 경로는 만들지 못했다.
- EACCES 회귀 시험은 없다. 코드 주석의 "fail closed on inspection errors"는 `Get-Item`과 DriveInfo 단계에 대해서만 정확하다.

## 3. 직접 수행 / 보고 인용 / 미실행

**직접 수행:**
- 고정 object의 `git show`/`diff`. 위 blob을 대조했다.
- `gh run view`로 run 45003458616(CI 5잡)과 45003458589(Monitoring 3잡)를 확인했다. 둘 다 `headSha=d4ee65d4…`이고 8잡 모두 success다.
- pc-pull-verify job 137149798492의 원본 로그를 받아 두 runtime의 버전 줄과 10/10 표시를 확인했다.
- **로컬 probe:**
  - 환경: Windows PowerShell 5.1.26100.9818. 실행 정책 Bypass는 해당 자식 프로세스에만 적용했다.
  - 방법: 고정 blob에서 `Normalize-LocalPath`, `Test-HasRemoteBackendScheme`, `Assert-SafeLocalPath`만 AST로 추출했다(`Fail-Run`은 throw stub).
  - 데이터: scratchpad 임시 루트의 합성 폴더와 junction만 썼다.
  - 결과: A·B·C 거부, D 단축 이름 ACCEPTED / 긴 이름 거부.
  - 정리: junction 3개를 비재귀로 제거하고 합성 폴더를 지웠다. 남은 항목 0개(`LEFT=0`).
  - 실제 OneDrive, 비밀값, restic, 네트워크는 쓰지 않았다.

**보고 인용 (재실행 아님):**
- 작성자의 로컬 PS 5.1 10/10, gitleaks 0건, 링크 검사, merge-tree.
- Sol이 읽은 restic v0.19.1 `cmd_copy.go`의 lock 방향.
- Codex의 e807 probe.
- root가 직접 확인한 최종 CI. 나도 위에서 메타데이터와 로그를 다시 조회했다.

**미실행:**
- 전체 `pc-pull-test.ps1` 재실행.
- 로컬 pwsh 실행. 이 PC에는 pwsh가 PATH에 없다. pwsh 결과는 CI 로그로만 본다.
- `subst`·EACCES 조상의 실제 재현.
- 실제 restic copy, OCI·IAM·S3, PC 설치·ACL·작업 스케줄러, 실운영 복원.

**그대로 남는 한계 (작성자 결과와 같음):**
- 대화형 작업 스케줄러라 PC가 꺼져 있는 동안 삭제가 지연된다.
- 강제 종료 시 후만료가 보장되지 않는다.
- `InRun` 이전 실패에는 `/fail`이 없다.
- HC URL이 비어 있으면 `HEARTBEAT_SKIPPED`로 동작한다.
