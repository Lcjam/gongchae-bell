# PR #38 두 비작성자 검수 수정 결과 (`agy`)

- **작성일**: 2026-11-22
- **요청 근거**: root Codex 오케스트레이션 지시 (`msg_2e9e5844a6ed`, 2026-11-22 수정 1라운드)
- **검수 원문**: `collab/reviews/pr-38-codex.md` (P2 R1/R2), `collab/reviews/pr-38-sol.md` (P1 경로 보호, P2 PS7 ACL, P2 Case 6)
- **이전 결과(보존)**: `collab/2026-11-19-resume/agy-pc-pull-result.md` (초기 구현 `f8b3171`, `e807a34`, 결과 커밋 `6b9b7f8`)

---

## 1. Worktree · 브랜치 · 커밋 · PR

| 항목 | 값 |
|---|---|
| Orca worktree | `C:/Users/lcj/orca/workspaces/my-project/issue-17-pc-pull` (단독 작성) |
| 브랜치 | `ops/#17-pc-pull` |
| 기존 기준선 (보존) | `6b9b7f80dcc5b1a686da87737e02136afba5c812` (`f8b3171` + `e807a34` + `agy-pc-pull-result.md`) |
| 검수 수정 소스·시험·CI·문서 커밋 | `96d54b175d217f01fcd4d3252cd1866db5decb35` (`fix(ops): harden PC pull path guards, PS7 Get-Acl, copy-lock test, and recovery policy (Refs #17)`) |
| 최종 HEAD | 이 결과 파일만 추가하는 문서 커밋(`docs(ops): record PR #38 review fixes result (Refs #17)` — 아래 §4 소스 SHA `96d54b1` 기준 8/8 CI 성공 확인 후 문서 커밋 최종 HEAD 8/8 CI 통과까지 확인) |
| Draft PR | [#38](https://github.com/Lcjam/gongchae-bell/pull/38) (`draft`, `base=main`, `head=ops/#17-pc-pull`, `MERGEABLE`, `Refs #17` — 이슈 #17 미종료) |

---

## 2. 검수 항목별 수정 대응표

| 검수 항목 | 근거 | 수정 내용 (파일·함수) |
|---|---|---|
| **1. 경로 보호 (P1/P2)** | `pr-38-codex.md` R1, `pr-38-sol.md` P1 | `deploy/backup/pc/pc-pull.ps1`:<br>• 단일 `Assert-SafeLocalDestination`을 공통 `Assert-SafeLocalPath` + `Assert-NoPathOverlap` + `Assert-SafeBinarySpec`으로 교체해 `PC_RESTIC_REPOSITORY`뿐 아니라 `PC_PULL_ENV_FILE`, `PC_RESTIC_PASSWORD_FILE`, `OCI_RESTIC_PASSWORD_FILE`, `PC_LOCK_FILE`, `PC_CACHE_DIR`, `PC_LOG_DIR`, `EXPIRE_SELECT_PATH` 전체에 일관 적용.<br>• leaf(미생성 leaf 포함)에서 드라이브 루트까지 **모든 기존 조상 경로**를 순회하며 `ReparsePoint`(junction/symlink)를 거부(`DEST_REPARSE_POINT_FORBIDDEN` / `PATH_REPARSE_POINT_FORBIDDEN`)하고, 경로 조회·속성 조회 오류도 안전으로 넘기지 않고 fail-closed(`*_ACCESS_UNVERIFIED`) 처리.<br>• `System.IO.DriveInfo` 예외·비준비 볼륨·네트워크 드라이브·비-NTFS·비지원 드라이브 타입을 모두 fail-closed(`*_DRIVE_UNAVAILABLE` / `*_NETWORK_DRIVE_FORBIDDEN` / `*_NON_NTFS_FORBIDDEN`) 거부(기존 catch 무시 제거).<br>• `PC_ALLOW_CUSTOM_PATHS` 우회 옵션을 제거하고 설정 시 `CONFIG_INVALID`로 거부.<br>• `PC_PULL_ENV_FILE`은 파일 읽기 전, `PC_LOG_DIR`은 `$script:LogFilePath` 활성화 전, 보조 경로 전체는 `Acquire-PcLock`·디렉터리 생성·`restic` 호출 전에 검증해 mutation 및 네트워크 cache 접근을 선차단. `OCI_RESTIC_PASSWORD_FILE`은 로컬 선만료 뒤 `Assert-ValidSourceConfig`에서 읽기 전 경로·중첩 검증을 수행해 원본 자격 경로 오류에도 로컬 선만료가 건너뛰어지지 않음.<br>• `EXPIRE_SELECT_PATH`만 개발/CI 체크아웃 실행을 위해 `-AllowGitCheckout`을 허용하되 UNC·조상 junction·동기화 폴더·저장소 중첩은 차단하며, 실운영 배치는 `%LOCALAPPDATA%\gongchae-bell\runtime\` 분리로 문서화.<br>• 저장소(`PC_RESTIC_REPOSITORY`)와 env·비밀번호·잠금·캐시·로그·selector 간 중첩(상하위 포함) 금지(`PATH_OVERLAP_FORBIDDEN`). |
| **2. PowerShell 5.1 / 7 ACL 호환 (P2)** | `pr-38-sol.md` P2 | `deploy/backup/pc/pc-pull.ps1`: `Assert-RestrictedWindowsAcl`의 .NET Framework 전용 `[System.IO.File]::GetAccessControl($Path)`를 Windows PowerShell 5.1과 PowerShell 7+ 양쪽에서 동일하게 동작하는 표준 `Get-Acl -LiteralPath $Path` + Well-Known SID 비교로 교체.<br>`deploy/tests/pc-pull-test.ps1`: 테스트 헬퍼(`Set-ReadOnlyTreeAcl`/`Restore-WritableTreeAcl`)의 `.NET Framework` `Directory.GetAccessControl`/`SetAccessControl`도 `icacls.exe` + `*<SID>` `(WD,AD,DE,DC)`로 교체하고 `PC_CHECK_ACL` 기본값(`true`) 유지.<br>`.github/workflows/ci.yml`: `pc-pull-verify` (`windows-latest`)에서 기존 `powershell.exe` (5.1) 전체 10개 시나리오를 유지하고, 같은 `pc-pull-test.ps1` 전체를 `pwsh` (PowerShell 7)로도 추가 실행. |
| **3. destination copy lock 시험 (P2)** | `pr-38-sol.md` P2 | `deploy/tests/pc-pull-test.ps1` Case 6:<br>• **6a (기존 pre-copy 잠금 회귀 유지 및 단언 강화)**: `dst6/locks` 읽기 전용 시 단순 non-zero가 아니라 `unable to create lock in backend` + `LOCAL_REPO_UNAVAILABLE` + `SOURCE_COPY_START` 미도달 + `/fail` 핑 및 `PC_PULL_OK` 부재 검증.<br>• **6b (실제 copy 단계 destination append-lock 검증 추가)**: `dst6b`를 쓰기 가능 상태로 두어 `PRE_RETENTION_SUMMARY`가 정상 통과한 뒤, `pc-pull.ps1`이 `SOURCE_COPY_START`로 진입해 실제 `restic --no-lock ... -r <dst6b> copy --from-repo <src2> <ids...>`를 호출하는 순간 투명 포워딩 shim이 `dst6b/locks`에 쓰기 거부 ACL을 걸고 실제 `restic 0.19.1` 바이너리에 위임한 뒤 종료 시 복원. `PRE_RETENTION_SUMMARY` 통과, `SOURCE_COPY_START` 도달, 실제 `restic 0.19.1`의 `unable to create lock in backend` 오류, `SOURCE_COPY_FAIL`/`LOCAL_DEST_LOCKED`, `POST_RETENTION_SUMMARY` 실행, 복사 스냅샷 `0`개, `/fail` 전송 및 `PC_PULL_OK` 부재를 모두 단언. |
| **4. 복구 문서 14일 예외 삭제 (P2)** | `pr-38-codex.md` R2 | `docs/ops/pc-pull-backup.md` §5: `14일 초과 미정리 사본이 남아 있다면 먼저 만료 정리 또는 복원 허용 여부 확인` 문구를 제거. OCI 소실 시에도 먼저 `pc-pull.ps1 -Command run`으로 로컬 선만료·`prune --max-unused 0`을 수행해 로그의 `PRE_RETENTION_SUMMARY ... expire=0`과 `POST_RETENTION_SUMMARY ... expire=0`을 검증(원본 단계 실패 `LOCAL_RETENTION_OK_BUT_SOURCE_FAILED`와 로컬 정리 성공 구별)한 뒤, `snapshots`로 남은 유효(`14일 이내`) 사본만 복원하고 `0`개이면 사용자 결정(`04-user-retention-decision.md`)에 따라 만료 사본을 예외 복원하지 않도록 통일. §1에도 전 역할 경로 보호·개발/운영 배치 구분·중첩 금지·`PC_CHECK_ACL=false` 운영 우회 금지를 명시. |

---

## 3. 직접 실행한 검증 결과

### 3.1 로컬 네이티브 Windows 11 검증 (`deploy/tests/pc-pull-test.ps1`)
- **실행 환경**: Windows 11 + `powershell.exe` `5.1.26100.9818 (Desktop)` + Python 3.12 + 임시 폴더 공식 `restic 0.19.1` (`restic_0.19.1_windows_amd64.zip`, SHA-256 `da948ad707ed690426473aaba2046cd61f8f90f6f0e7dab6be0d5796531de67d` 검증; 전역 미설치·종료 즉시 삭제).
- **결과 (`96d54b1`)**: **10/10 전체 통과** (`ALL_PC_PULL_TESTS_PASSED`, `PC_PULL_TEST_EXIT=0`, `PC_ALLOW_CUSTOM_PATHS` 전면 제거 + `PC_CHECK_ACL` 기본 `true`):
  - 신규 회귀 포함: `PC_ALLOW_CUSTOM_PATHS` 거부, 실제 Windows NTFS 조상 junction(`alias-od -> SyntheticOneDrive`)의 기존 leaf 및 미생성 deep leaf 거부 + 하위 디렉터리 미생성 확인, UNC `PC_CACHE_DIR` 선차단(잠금 파일 미생성), OneDrive `PC_LOG_DIR`/`PC_PULL_ENV_FILE`/`PC_RESTIC_PASSWORD_FILE` 선차단(로그 미생성), Git 체크아웃 `PC_LOCK_FILE` 선차단(잠금 파일 미생성), repo-cache 중첩(`PATH_OVERLAP_FORBIDDEN`) 거부, 미마운트 드라이브 문자 fail-closed(`DEST_DRIVE_UNAVAILABLE`), OneDrive `OCI_RESTIC_PASSWORD_FILE` 시 로컬 선만료 완료 후 `PATH_SYNC_DIR_FORBIDDEN` + `LOCAL_RETENTION_OK_BUT_SOURCE_FAILED`, Case 6a(사전 잠금 거부) + Case 6b(실제 `copy` 단계 `SOURCE_COPY_START` 도달 후 destination append-lock 거부 및 `/fail` 검증).
- **참고**: 로컬 PC에는 `pwsh` (PowerShell 7)가 미설치(`PWSH=NOT_INSTALLED`)여서 추가 설치하지 않았으며, PowerShell 7 검증은 아래 원격 `windows-latest` CI `pc-pull-verify`에서 직접 실행해 통과를 확인했다.

### 3.2 로컬 정적 검증
- `gitleaks dir --no-banner --redact` (`deploy/backup/pc/pc-pull.ps1`, `deploy/tests/pc-pull-test.ps1`, `docs/ops/pc-pull-backup.md`, `.github/workflows/ci.yml`): 누출 `0`건 (`RC=0`).
- `docs/ops/pc-pull-backup.md` 상대 마크다운 링크 검증: `checked=6 missing=0`.
- `git merge-tree --write-tree HEAD origin/main`: `RC=0` (충돌 없음).

---

## 4. 원격 CI 검증 결과 (PR #38, 검수 수정 소스 SHA `96d54b175d217f01fcd4d3252cd1866db5decb35`)

- **8/8 전체 통과 (`SUCCESS`)**, 특히 `pc-pull-verify`에서 **Windows PowerShell `5.1.26100.33822 (Desktop)`**과 **PowerShell `7.6.10 (Core)` (`pwsh`)** 양쪽 모두 `PC_CHECK_ACL` 기본값(`true`)·경로 우회 없음 상태로 10/10 `ALL_PC_PULL_TESTS_PASSED` 확인:

| 워크플로 | 잡 | 러너/내용 | 결과 | 소요 | 링크 |
|---|---|---|---|---|---|
| CI | `pc-pull-verify` | `windows-latest`, `restic 0.19.1` SHA-256 검증 + PS `5.1.26100.33822` 10/10 + `pwsh` `7.6.10` 10/10 | `SUCCESS` | 5m30s | [job 137147728262](https://github.com/Lcjam/gongchae-bell/actions/runs/45002782781/job/137147728262) |
| CI | `backup-verify` | `ubuntu-latest`, `backup-test.sh` + `backup-integration-test.sh` (PG17+restic) | `SUCCESS` | 59s | [job 137147728006](https://github.com/Lcjam/gongchae-bell/actions/runs/45002782781/job/137147728006) |
| CI | `test` | Gradle 테스트·검증 | `SUCCESS` | 2m24s | [job 137147728244](https://github.com/Lcjam/gongchae-bell/actions/runs/45002782781/job/137147728244) |
| CI | `deploy-verify` | 배포 검증 | `SUCCESS` | 1m31s | [job 137147728279](https://github.com/Lcjam/gongchae-bell/actions/runs/45002782781/job/137147728279) |
| CI | `secrets` | gitleaks 스캔 | `SUCCESS` | 6s | [job 137147728192](https://github.com/Lcjam/gongchae-bell/actions/runs/45002782781/job/137147728192) |
| Monitoring | `rules` | Prometheus/Alertmanager 규칙 검증 | `SUCCESS` | 11s | [job 137147727869](https://github.com/Lcjam/gongchae-bell/actions/runs/45002782777/job/137147727869) |
| Monitoring | `compose-integration` | 모니터링 Compose 통합 | `SUCCESS` | 40s | [job 137147728194](https://github.com/Lcjam/gongchae-bell/actions/runs/45002782777/job/137147728194) |
| Monitoring | `alert-e2e` | 알림 E2E | `SUCCESS` | 7m04s | [job 137147728014](https://github.com/Lcjam/gongchae-bell/actions/runs/45002782777/job/137147728014) |

- PR 상태: `OPEN`, `isDraft=true`, `mergeable=MERGEABLE`.

---

## 5. 미실행 범위 및 남은 한계

- **미실행 (이번 검수 수정 라운드 범위 밖)**:
  - 실제 OCI 버킷·전용 읽기 전용 IAM 사용자/그룹/정책·Customer Secret Key·OCI Budget 생성 및 실 S3 엔드포인트 연동.
  - 실제 서버 SSH 접속, 운영 DB·env·키/provider(Resend/Discord/Healthchecks) 변경.
  - 사용자 PC 영구 `restic`/`pwsh` 설치, `%LOCALAPPDATA%\gongchae-bell\` 실운영 디렉터리/ACL 적용, 작업 스케줄러 등록, 실운영 복원 리허설.
- **남은 구조적 한계**:
  - Windows 비승격 로그온 기반 작업 스케줄러(`-LogonType Interactive`)는 PC 전원 꺼짐·절전·로그오프 기간 동안 로컬 `forget`/`prune`을 실행하지 못하므로 오프라인 기간만큼 삭제가 지연될 수 있다(다음 로그온/`StartWhenAvailable` 복귀 시 선만료로 정리; 상시 가동 NAS로 보장하지 않음).
  - 프로세스 강제 종료·전원 단절·작업 스케줄러 강제 종료 시에는 `POST_RETENTION` 후정리 완료를 보장할 수 없으며, 다음 실행의 `PRE_RETENTION`(`prune --max-unused 0`)과 Healthchecks 미수신 경보로 복구·감지한다.
  - 초기 `Load-Config`/`Acquire-PcLock` 단계 실패는 `InRun` 진입 이전이므로 `/fail` heartbeat가 전송되지 않으며(운영에서는 Healthchecks 미수신 경보로 감지), `HEALTHCHECKS_PC_PULL_URL`이 비어 있으면 `HEARTBEAT_SKIPPED`로 동작하므로 실제 무응답 탐지는 별도 체크 URL 설치가 필요하다.
