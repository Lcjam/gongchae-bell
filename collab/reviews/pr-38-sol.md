# PR #38 독립 검수 — Sol

- 검수자: GPT-6.1 Sol high, 비작성자. 2026-11-22 KST.
- 고정 대상: `e807a342cea1d3094a4e717af56dc2e2a3d847d0` (구현 `f8b3171`, 시험 보완 `e807a34`).
- 실제 branch 기준/merge-base: `7cb464a7b4a51300ee1d1fe3d78f9478c4623cad`. **이 기준과의 9파일 차분**을 검수했다. 이후 main 6c9와의 단순 양쪽 diff에서 보이는 다른 PR 변경을 PR38의 삭제/회귀로 세지 않았다.
- **판정: 수정 요청. P1 경로 보호 1건, P2 지원 버전 1건, P2 시험 증거 간극 1건.** 14일·0개 정책의 선택/정리 순서는 반영됐다. 실제 PC 설치·OCI 운영 승인은 이번 검수와 별개다.
- root 리뷰는 첫 독립판정 전에 읽지 않았고 이후에도 읽지 않았다. 작성자 결과/최종 CI는 제출 전이라는 요청서 전제로 source만 검수했다.

## P1. 경로 보호가 lexical destination에 한정돼 junction과 다른 역할 경로를 통과시킨다

위치: `pc-pull.ps1:342–423`, `:452–453`, `:536–551`, `:666–694`.

`Assert-SafeLocalDestination`은 `GetFullPath` 뒤 **마지막 경로 자체만** reparse point로 검사한다(368행). 중간 디렉터리의 junction/symlink를 따라 도착하는 위치는 검사하지 않고, OneDrive/Git 검사는 입력의 문자 경로를 기준으로 한다. 따라서 `C:\local-alias\repo`에서 `local-alias`가 동기화 폴더를 가리키면 최종 repo가 일반 디렉터리라는 이유로 통과한다. 원격/다른 볼륨으로 이어지는 조상 경로 역시 lexical C:의 DriveInfo만으로 보장할 수 없다.

또한 보호 함수는 PcRepo에만 호출된다. env·password 파일은 regular-file/ACL 검사만 하고, cache/log/lock은 Normalize-LocalPath만 적용하거나 기본값을 사용한다. 예를 들어 `PC_CACHE_DIR`를 UNC로 설정하면 첫 로컬 `cat config` 전 `Ensure-Directory`부터 네트워크 경로에 접근한다. 따라서 설정된 cache가 원격인 경우 “로컬 정리를 네트워크보다 먼저” 한다는 설명도 성립하지 않는다. plaintext env/password가 동기화/Git 경로에 놓이는 것은 NTFS ACL만으로 클라우드 업로드나 commit을 막을 수 없다.

**직접 Windows probe 2건**(고정 source의 실제 함수들을 추출, synthetic 파일/폴더만 사용):

1. 임시 `SyntheticOneDrive\repo`를 만들고 자식 프로세스의 OneDrive 변수만 그 경로로 설정했다. 실제 junction `local-alias → SyntheticOneDrive`를 만든 후 `Assert-SafeLocalDestination(alias\repo, false)`는 **허용**, 직접 `SyntheticOneDrive\repo`는 `DEST_SYNC_DIR_FORBIDDEN`으로 **거부**했다. `PC_ALLOW_CUSTOM_PATHS` 우회를 사용하지 않았다.
2. 임시 동기화 폴더에 가짜 env를 놓고 그 파일의 ACL을 현재 사용자만 허용하도록 제한했다. `PC_CHECK_ACL`을 생략해 **기본 true**인 상태에서 실제 `Load-Config`가 동기화 경로의 env·cache·log·lock을 모두 허용했다(`DEFAULT_ACL_ENABLED=True`, `SYNC_ENV_CACHE_LOG_LOCK_ACCEPTED=True`). 실제 비밀값·실제 OneDrive·작업 등록·실운영 ACL은 사용하지 않았다.

최소 수정은 공통 경로 검증을 모든 보호 역할에 적용하고, 조상의 reparse point를 거부하거나 실제 도착 경로/볼륨까지 확인하는 것이다. 존재하지 않는 대상도 가장 가까운 기존 조상부터 검사하고 접근/드라이브 조회 실패를 로컬 안전으로 간주하지 않는다. runtime/selector·env/password·repo·cache/log/lock의 허용 배치와 중첩 금지를 명확히 한다. 안전한 기본 경로만 시험하고 optional override를 무검증으로 두지 않는다. 합성 junction 및 sync/UNC 보조 경로가 mutation 전 거부되는 회귀가 필요하다.

## P2. PowerShell 7+ 지원 표기와 ACL API가 맞지 않는다

위치: `pc-pull.ps1:5`, `:307–320`, `:488–494`; CI `:86–89`.

스크립트는 Windows PowerShell 5.1+와 PowerShell 7+를 지원한다고 적지만 기본 ACL 검사가 `[System.IO.File]::GetAccessControl($Path)`를 호출한다. 이 static API는 .NET Framework 쪽 API이고, 현대 .NET에는 FileInfo를 받는 FileSystemAclExtensions/Get-Acl 경로가 필요하다. 따라서 pwsh 7에서 기본 ACL 검사를 수행하면 local pre-expiry 이전 Load-Config에서 실패한다. CI는 명시적으로 `powershell.exe`만 실행하므로 이 지원 범위를 검증하지 않는다.

근거는 Microsoft 공식 [File.GetAccessControl](https://learn.microsoft.com/en-us/dotnet/api/system.io.file.getaccesscontrol?view=netframework-4.8.1), [FileSystemAclExtensions.GetAccessControl](https://learn.microsoft.com/en-us/dotnet/api/system.io.filesystemaclextensions.getaccesscontrol?view=net-7.0), [PowerShell 7 Get-Acl](https://learn.microsoft.com/en-us/powershell/module/microsoft.powershell.security/get-acl?view=powershell-7.4) API 대조다. **로컬 pwsh 7 실행은 하지 않았다.** 사용 가능한 native Windows PowerShell에서만 probe했다.

최소 수정은 5.1/7 양쪽 지원 API로 맞추고 기본 ACL 경로의 두 runtime 시험을 추가하거나, 이번 지원을 5.1로 정확히 좁히고 다른 runtime을 사전 명시 오류로 거부하는 것이다. `PC_CHECK_ACL=false`를 해결책으로 안내하지 않는다.

## P2. case 6은 copy의 destination lock을 검증하기 전에 실패한다

위치: `deploy/tests/pc-pull-test.ps1:675–703`; 실제 흐름 `pc-pull.ps1:981–982`, `:897`.

case 6은 dst/locks를 읽기 전용으로 만든 다음 전체 `run`을 호출하고 **nonzero 하나만** 검사한다. 그런데 run은 copy보다 먼저 로컬 pre-retention의 `prune`을 수행하고 이 작업도 destination lock을 써야 한다. 따라서 시험은 pre-prune에서 이미 실패할 수 있으며, `SOURCE_COPY_START`에 도달했거나 copy의 append-lock이 거부됐는지 확인하지 않는다. copy에서 destination lock을 잘못 생략하는 회귀가 있어도 같은 pre-prune 실패로 이 시험을 통과할 수 있다.

이는 **시험의 제어 흐름 분석**이며 실제 restic으로 case 6을 재실행한 결과가 아니다. 반대로 현재 copy 구현의 방향/잠금 계약 자체는 공식 [restic v0.19.1 cmd_copy.go](https://github.com/restic/restic/blob/v0.19.1/cmd/restic/cmd_copy.go)를 직접 읽어 확인했다. `--from-repo`의 options swap 뒤 source는 `openWithReadLock(...NoLock...)`, destination은 `openWithAppendLock(...false...)`다.

최소 보완은 pre-expiry를 통과한 뒤 실제 copy 단계에서만 destination lock 거부를 유발하거나, 현재 copy wrapper를 실제 작은 restic repo로 한정 호출해 목적지 lock 거부를 확인하는 것이다. 단순 nonzero 대신 해당 단계 도달·잠금 오류·성공 heartbeat 부재를 확인한다. 전체 회귀 반복보다 이 증거 간극을 닫는 시험이 필요하다.

## 반영된 계약과 확인 범위

- `Invoke-CmdRun`은 로컬 준비/선만료 뒤에 source password 파일·source 설정을 검사한다. source 조회/선택/copy의 예외와 실패를 잡고 후만료를 시도한다. pre/post 모두 명시 full ID forget → 매번 `prune --max-unused 0` → 같은 cutoff로 전체 재조회/selector 검증이다. 최후 사본 예외를 추가하지 않았다. source 실패가 남으면 local retention 성공을 별도 기록하고 성공 heartbeat를 보내지 않는다.
- copy의 제한 재시도는 source 목록을 새로 읽는다. destination은 로컬 `-r`, source는 `--from-repo`이며 password/key는 ProcessStartInfo의 환경변수로 전달한다. source 쪽 forget/prune 호출은 없다. official cmd_copy.go에서 Time을 바꾸지 않는 저장과 Original 기반 중복 검사를 확인했다. 실제 IAM의 읽기 전용성은 설치 전 별도 운영 검증 사항이다.
- selector는 기존 expired 기본 CLI를 보존하고 kept 출력만 추가했다. **직접 한정 검증 5/5**: 기존 expired의 14d+1초 선택, kept의 정각/14d−1초 보존, 정상 항목 뒤 잘못된 시각·미래301초·짧은 ID 각각에서 exit2와 stdout 없음. 잘못된 전체 입력에서 일부 ID를 먼저 내보내지 않았다.
- 9파일의 script·공통 selector·env example·시험·CI·ADR/runbook 차분을 읽었다. 시험 source에는 실제 작은 암호화 저장소·foreign tag·RO source·동일 시각/멱등 copy·source 장애 후 정리·경계 전후 정리·redaction·loopback heartbeat 시나리오가 있다. **그 전체 시험이 여기서 통과했다고 주장하지 않는다.** e807의 cache 분리/전체 source pack 제거는 하나의 copy 실패 시나리오 보완이지 모든 중간 성공/실패 조합의 검증은 아니다.
- default 경로는 LOCALAPPDATA 아래며 runtime 스크립트 복사/Interactive Task Scheduler·로그온 회복·PC 오프라인 삭제 지연 한계가 문서에 있다. 다만 문서의 “항상 후정리”는 제어가 반환되는 오류 처리 경로의 의미다. 프로세스 강제 종료/전원 단절/Task Scheduler 강제 종료 때 finally나 후정리 완료를 보장하는 것으로 해석하지 않는다.
- 성공 핑은 정상 제어 흐름에서 pre/source/post 완료 뒤에만 전송한다. heartbeat URL이 비어 있으면 `HEARTBEAT_SKIPPED`로 성공 반환하는 것은 현 코드의 선택이므로 실제 운영의 미수신 경보는 별도 URL/체크 설치를 끝내야 성립한다. 초기 config/lock 단계 실패는 InRun 이전이라 `/fail`이 전송되지 않는 범위도 있다. 무응답 탐지를 운영 완료로 추정하지 않는다.
- 직접 probe는 임시 디렉터리의 실제 함수/selector만 사용했다. 최초 probe는 로컬 실행 정책 때문에 스크립트 로드가 거부됐고, 이후 요청서/시험과 같은 **해당 자식 프로세스에만** `-ExecutionPolicy Bypass`를 사용했다. 전역 정책을 바꾸지 않았다. 임시 junction은 비재귀 삭제했고 cleanup 대상은 검증한 임시 루트로 한정했다.
- 실제 restic binary/전체 시험·최종 CI·Task Scheduler 등록·영구 PC ACL/설치·실제 OCI/계정/env/키/SSH/provider·외부 API/Discord/Resend는 실행/접근하지 않았다. root/작성자의 결과와 후속 tip·CI 최종 대조는 root 담당이다. 기존 검수 checkout 및 제품/작성자 branch, 다른 Sol frontend 작업은 변경하지 않았다.
