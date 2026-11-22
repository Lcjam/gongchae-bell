# Windows PC Pull 백업 · 보유기간 운영 절차 (#17)

> 근거: [PRD §6·§7](../prd.md), [ADR-0006](../adr/0006-observability.md), [ADR-0012](../adr/0012-backup-restic-pull.md), [ADR-0017](../adr/0017-backup-wall-clock-retention.md), [서버 백업·복구 절차](backup-restore.md), 사용자 결정(`collab/2026-11-19-recovery/04-user-retention-decision.md`)
> 본 문서는 **OCI 밖 첫 관리 사본 대상(Windows 11 PC 로컬 NTFS)**의 `restic` pull·보유기간 자동화 소스와 설치·복구 절차를 정리한다. 실제 OCI 버킷·IAM 키 생성, PC 영구 설치·ACL 적용·작업 스케줄러 등록, 실운영 복원 리허설은 별도 운영 적용 라운드에서 수행하며, 그 전까지 외부 사본 운영을 완료로 표시하지 않는다.

---

## 1. 구성 원칙 및 경로 배치 (체크아웃 · 동기화 폴더 분리)

- **Docker Desktop 비의존 · PowerShell 5.1 / 7 지원**: 네이티브 Windows `restic`(`0.19.1` 기준), `Python 3`, Windows PowerShell 5.1 또는 PowerShell 7(`pwsh`, 표준 `Get-Acl` 기반)만 사용한다.
- **공통 만료 판정기 재사용**: 서버 `deploy/backup/backup.sh`와 동일한 `deploy/backup/expire-select.py`를 그대로 사용한다(로컬 만료는 기본 `expired` 모드, 읽기 전용 원본 필터링은 `--mode kept` 모드). 별도 파서를 두지 않아 서버·PC 간 14일 경계 판정 드리프트를 막는다.
- **개발 검증 배치와 실운영 배치의 구분 (체크아웃 · Orca 세션 수명 분리)**:
  - 개발·CI 격리 검증에서는 저장소 체크아웃의 `deploy/backup/pc/pc-pull.ps1`과 `deploy/backup/expire-select.py`를 직접 실행할 수 있다(`EXPIRE_SELECT_PATH`만 체크아웃 위치를 허용하며, 이 경우에도 원격/UNC·조상 junction·동기화 폴더·저장소 내부 중첩은 차단한다).
  - 실제 운영에서는 작업 스케줄러가 Git 저장소나 Orca worktree 안의 스크립트를 직접 가리키지 않도록 `pc-pull.ps1`과 `expire-select.py`를 `%LOCALAPPDATA%\gongchae-bell\runtime\`으로 복사해 실행한다.
- **전 역할 경로 보호 및 중첩 금지 (변경·네트워크 접근 전 차단)**:
  - `pc-pull.ps1`은 `PC_RESTIC_REPOSITORY`뿐 아니라 `PC_PULL_ENV_FILE`, 비밀번호 파일(`PC_RESTIC_PASSWORD_FILE`·`OCI_RESTIC_PASSWORD_FILE`), `PC_CACHE_DIR`, `PC_LOG_DIR`, `PC_LOCK_FILE` 전체에 공통 로컬 NTFS 경로 검증을 적용한다(경로 우회 옵션 없음).
  - 원격 백엔드(`s3:` 등), UNC 경로(`\\server\share`), 네트워크 드라이브, 비-NTFS 볼륨, 준비되지 않은 드라이브(조회 실패 포함 fail-closed), 대상 자체 및 **모든 기존 조상 경로의 junction/symlink/reparse point**(아직 생성되지 않은 하위 leaf 포함), OneDrive, Google Drive(`G:` 등), Git 체크아웃(`.git` 상위 탐색)을 파일·디렉터리 생성이나 네트워크 접근 **전에** 즉시 거부한다.
  - 로컬 저장소(`PC_RESTIC_REPOSITORY`)와 env·비밀번호·잠금·캐시·로그·selector 경로는 서로 중첩(동일/상하위 포함)될 수 없다(`PATH_OVERLAP_FORBIDDEN`). `PC_CHECK_ACL=false`는 운영 우회 수단으로 사용하지 않는다.

| 역할 | 권장 로컬 NTFS 경로 (기본값) | 비고 |
|---|---|---|
| 로컬 암호화 저장소 | `%LOCALAPPDATA%\gongchae-bell\backup-replica` | `PC_RESTIC_REPOSITORY` (OCI 원본과 별도 비밀번호) |
| 설정 · 비밀 파일 | `%LOCALAPPDATA%\gongchae-bell\backup-env\` | `pc-pull.env`, `pc-repo.pass`, `oci-repo.pass` |
| 런타임 스크립트 · 잠금 | `%LOCALAPPDATA%\gongchae-bell\runtime\` | `pc-pull.ps1`, `expire-select.py`, `pc-pull.lock` |
| restic 캐시 | `%LOCALAPPDATA%\gongchae-bell\cache` | `PC_CACHE_DIR` |
| 실행 로그 (마스킹) | `%LOCALAPPDATA%\gongchae-bell\logs\pc-pull.log` | 비밀값·토큰 자동 `[REDACTED]` |

---

## 2. 실행 순서 · 역할 분리 · 보유기간 불변식 (ADR-0017)

1. **단일 실행 잠금**: `pc-pull.ps1`은 시작 시 `%LOCALAPPDATA%\gongchae-bell\runtime\pc-pull.lock`에 OS 배타 파일 잠금(`FileShare.None`)을 잡는다. 이미 실행 중이면 `LOCK_HELD`로 즉시 종료한다(exit `20`).
2. **역할 분리(원격 삭제 원천 차단)**:
   - 로컬 `cat config`·`snapshots`·`forget`·`prune` 하위 프로세스 환경에서는 `AWS_*`와 `RESTIC_FROM_*` 변수를 모두 제거하고 로컬 경로(`PC_RESTIC_REPOSITORY`)만 전달한다.
   - 원본 조회·복사는 읽기 전용 OCI 주체와 `--no-lock`만 사용한다. `restic 0.19.1`에서 `restic --no-lock -r <LOCAL_DST> copy --from-repo <OCI_SRC> <ids...>`는 **원본(`OCI_SRC`) 잠금만 생략**하고 **대상(`LOCAL_DST`) append 잠금은 유지**한다.
3. **로컬 선(先)만료 → `prune` → 재조회 (네트워크 작업 전 무조건 실행)**:
   - OCI 연결·인증·`copy` 확인 **전에** 로컬 저장소에서 현재 UTC 시각 기준 `now - snapshot.time > 14일(1,209,600초)`인 `gongchae-bell` 전체 64-hex ID를 `expire-select.py`로 골라 `restic forget <ids...>` → `restic prune --max-unused 0` → 재조회 `expire=0` 확인을 완료한다.
   - 최신·최후 사본 예외는 두지 않는다. 조건에 해당하면 로컬 `gongchae-bell` 사본이 **`0`개 남는 것도 정상 결과**다(다른 태그 사본은 건드리지 않는다).
   - OCI 연결·비밀번호·`copy`가 실패하는 날에도 로컬의 14일 초과 사본 정리는 이미 끝나 있다. 반대로 로컬 비밀번호 오류(`LOCAL_REPO_AUTH_FAIL`)나 로컬 저장소 권한 오류는 즉시 명시 실패로 중단한다.
4. **읽기 전용 원본 검증 및 유효 사본만 복사**:
   - `restic --no-lock snapshots --tag gongchae-bell --json` 결과를 `expire-select.py --mode kept`로 전수 검증해 **14일 이내(`now - time <= 14일`) 전체 64-hex ID만** 선별한다(이미 만료된 OCI 사본을 복사해 로컬에 재보관하지 않는다).
   - 선별된 ID만 `restic --no-lock -r <LOCAL_DST> copy --from-repo <OCI_SRC> <ids...>`로 복사한다. 원본 `snapshot.time`이 그대로 보존되며, 이미 복사된 스냅샷은 `restic copy`의 `original` 비교로 멱등 건너뜀 처리된다.
   - OCI `prune` 경합이나 일시 네트워크 오류로 `copy`가 실패하면 성공으로 숨기지 않는다. 설정된 제한 횟수(`COPY_MAX_RETRIES`, 기본 `1`, `0..3`)만큼 원본 목록을 다시 읽고 재시도하며, 끝까지 실패하면 실패 상태를 유지한 채 5단계 후정리로 넘어간다(`restic copy`는 트리 복사 완료 뒤에만 스냅샷을 저장하므로 미완성 스냅샷은 남지 않는다).
5. **로컬 후(後)정리 및 최종 검증 (성공·실패 무관 항상 실행)**:
   - 복사 종료 시점 UTC 시각 기준으로 다시 `expire-select.py` → `forget`(전송 도중 14일 경계를 넘은 사본 정리) → `prune --max-unused 0`(직전 `copy` 중단 시 남은 고아 pack 포함 물리 정리) → `expire=0` 재검증을 수행한다.
6. **외부 Heartbeat (Healthchecks.io, 서버 백업과 별도 체크)**:
   - 선만료·원본 동기화·후정리 재검증이 **모두** 성공했을 때만 성공 핑(`PC_PULL_OK ...`)을 보낸다.
   - 어느 단계든 실패하면 `/fail` 핑(마스킹된 사유 포함)을 보내고 0이 아닌 코드로 종료한다. 로컬 정리가 끝났더라도 원본 동기화가 실패했으면 `LOCAL_RETENTION_OK_BUT_SOURCE_FAILED`로 분리 기록하고 성공 핑을 보내지 않는다.

---

## 3. 비밀값 분리 및 Windows NTFS ACL 잠금 (검토용 설치 절차)

### 3.1 IAM · 비밀값 분리 원칙
- **OCI 읽기 전용 주체 분리**: `OCI_AWS_ACCESS_KEY_ID` / `OCI_AWS_SECRET_ACCESS_KEY`는 반드시 **전용 비관리자 읽기 전용 IAM 사용자·그룹**(`inspect buckets` + `read objects`, 전용 버킷 1개 한정, `manage objects`·`locks/*` 쓰기 없음)에 발급한 Customer Secret Key를 사용한다. **기존 테넌시 관리자 사용자의 키를 재사용하거나 "읽기 전용 키"로 부르지 않는다.**
- **비밀번호 분리**: 로컬 PC 저장소 비밀번호(`pc-repo.pass`)와 OCI 원본 저장소 비밀번호(`oci-repo.pass`)는 서로 다른 값으로 분리해 별도 파일과 사용자의 비밀번호 관리자에 보관한다.
- **노출 금지**: 비밀값은 공개 Git 저장소, PowerShell 명령 인자, 작업 스케줄러 인자, 표준 출력/오류, `pc-pull.log`, Healthchecks `/fail` 본문에 넣지 않는다.

### 3.2 설치 · ACL 제한 · 최초 초기화 명령 (운영 적용 라운드에서 실행)

> 주의: Windows 11의 `%LOCALAPPDATA%`에는 `CodexSandboxUsers` 등 추가 그룹 권한이 상속될 수 있다. 반드시 `/inheritance:r`로 상속을 끊고 현재 사용자·`SYSTEM`·`Administrators`만 남긴다. `pc-pull.ps1`은 기본값(`PC_CHECK_ACL=true`)으로 env·비밀번호 파일의 ACL을 검사해 허용 외 주체가 있으면 `ENV_ACL_FAIL`로 즉시 중단한다.

```powershell
# 1) 네이티브 restic 0.19.1 설치 (Docker Desktop 불필요; 공식 v0.19.1 SHA-256 검증 권장)
winget install --id restic.restic --version 0.19.1 --exact
restic version
python --version

# 2) Git 체크아웃·동기화 폴더 바깥 런타임/저장소 디렉터리 생성
$base = Join-Path $env:LOCALAPPDATA "gongchae-bell"
$dirs = @(
  $base,
  (Join-Path $base "backup-replica"),
  (Join-Path $base "backup-env"),
  (Join-Path $base "runtime"),
  (Join-Path $base "cache"),
  (Join-Path $base "logs")
)
foreach ($d in $dirs) { New-Item -ItemType Directory -Path $d -Force | Out-Null }

# 3) NTFS 상속 제거 및 현재 사용자 · SYSTEM · Administrators 전용 제한
$me = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name
foreach ($d in $dirs) {
  icacls.exe $d /inheritance:r /grant:r "${me}:(OI)(CI)F" /grant:r "SYSTEM:(OI)(CI)F" /grant:r "Administrators:(OI)(CI)F"
}
icacls.exe (Join-Path $base "backup-env")

# 4) 런타임 스크립트 복사 (저장소 체크아웃과 분리)
Copy-Item ".\deploy\backup\pc\pc-pull.ps1" (Join-Path $base "runtime\pc-pull.ps1")
Copy-Item ".\deploy\backup\expire-select.py" (Join-Path $base "runtime\expire-select.py")

# 5) 설정 파일과 별도 비밀번호 파일 준비 (비밀값은 로컬에서만 수동 입력, 커밋 금지)
Copy-Item ".\deploy\backup\pc\pc-pull.env.example" (Join-Path $base "backup-env\pc-pull.env")
# backup-env\pc-repo.pass (로컬 PC 저장소 전용 새 비밀번호)
# backup-env\oci-repo.pass (OCI 원본 저장소 비밀번호)
# backup-env\pc-pull.env 에 PC_RESTIC_PASSWORD_FILE, OCI_RESTIC_PASSWORD_FILE, OCI_RESTIC_REPOSITORY,
# 읽기 전용 IAM 주체의 OCI_AWS_ACCESS_KEY_ID / OCI_AWS_SECRET_ACCESS_KEY / OCI_AWS_DEFAULT_REGION,
# PC pull 전용 HEALTHCHECKS_PC_PULL_URL 만 채운다.
foreach ($f in @("pc-pull.env", "pc-repo.pass", "oci-repo.pass")) {
  $p = Join-Path $base "backup-env\$f"
  if (Test-Path $p) {
    icacls.exe $p /inheritance:r /grant:r "${me}:F" /grant:r "SYSTEM:F" /grant:r "Administrators:F"
  }
}

# 6) 최초 1회 로컬 저장소 초기화 (원본 chunker 파라미터 복사, --no-lock 읽기 전용)
powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $base "runtime\pc-pull.ps1") -Command init

# 7) 수동 1회 pull·만료 실행 및 스냅샷 확인
powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $base "runtime\pc-pull.ps1") -Command run
powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $base "runtime\pc-pull.ps1") -Command snapshots
```

---

## 4. Windows 작업 스케줄러 등록 절차 및 정직한 운영 한계

### 4.1 비승격 사용자(로그온 기반) 작업 등록 예시 (운영 적용 라운드에서 실행)
- OCI 서버 백업(`03:00 KST`)과 겹칠 확률을 줄이기 위해 일일 실행 시각은 `04:30 KST` 이후로 두고, PC가 꺼져 있거나 절전 중 놓친 실행을 다음 기동·로그온에서 보충하도록 `StartWhenAvailable`과 로그온 트리거를 함께 둔다.
- 작업 인자에는 **비밀값을 절대 넣지 않고** 런타임 스크립트 경로(`%LOCALAPPDATA%\gongchae-bell\runtime\pc-pull.ps1`)와 `-Command run`만 전달한다.

```powershell
$runtimeScript = Join-Path $env:LOCALAPPDATA "gongchae-bell\runtime\pc-pull.ps1"
$action = New-ScheduledTaskAction `
  -Execute "powershell.exe" `
  -Argument "-NoProfile -NonInteractive -WindowStyle Hidden -ExecutionPolicy Bypass -File `"$runtimeScript`" -Command run"

$me = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name
$dailyTrigger = New-ScheduledTaskTrigger -Daily -At "04:30"
$logonTrigger = New-ScheduledTaskTrigger -AtLogOn -User $me

$settings = New-ScheduledTaskSettingsSet `
  -StartWhenAvailable `
  -AllowStartIfOnBatteries `
  -DontStopIfGoingOnBatteries `
  -MultipleInstances IgnoreNew `
  -ExecutionTimeLimit (New-TimeSpan -Hours 1)

$principal = New-ScheduledTaskPrincipal `
  -UserId $me `
  -LogonType Interactive `
  -RunLevel Limited

Register-ScheduledTask `
  -TaskName "GongchaeBell-PcBackupPull" `
  -Action $action `
  -Trigger @($dailyTrigger, $logonTrigger) `
  -Settings $settings `
  -Principal $principal `
  -Description "Gongchae-Bell off-OCI restic pull and 14-day wall-clock retention (#17)"
```

### 4.2 한계와 고지 (상시 가동 NAS처럼 보장하지 않음)
- **무인 로그오프 실행 불가(비승격 등록 기준)**: `-LogonType Interactive` 작업은 사용자가 Windows에 로그온해 있을 때만 실행된다. 로그오프 상태나 전원 꺼짐·절전 중에는 자동 실행되지 않는다(무인 로그오프 실행이 필요하면 별도 1회 관리자 상승 등록이 필요하다).
- **PC 오프라인 동안 물리 삭제 지연**: PC가 꺼져 있거나 로그오프된 기간 동안에는 로컬 `forget`·`prune`이 실행되지 않으므로 14일이 지난 사본이 로컬 디스크에 물리적으로 남을 수 있다. 다음 로그온 또는 `StartWhenAvailable` 복귀 실행의 1단계(선만료)에서 만료 사본을 우선 삭제·`prune --max-unused 0` 한다.
- **오류·미수신 감지**: Healthchecks.io PC pull 전용 체크의 주기+유예 기간 안에 성공 핑이 오지 않거나 `/fail`을 수신하면 경보로 감지한다. PC가 장기간 꺼져 있으면 미수신 경보가 발생하며, 그 기간은 삭제 완료로 간주하지 않는다.

---

## 5. 장애 대응 및 PC 사본에서의 복구 절차

- **로그 점검**: `%LOCALAPPDATA%\gongchae-bell\logs\pc-pull.log`에서 최근 실행의 `PRE_RETENTION_SUMMARY`, `SOURCE_COPY_OK`/`SOURCE_COPY_FAIL`, `POST_RETENTION_SUMMARY`, `PC_PULL_OK`를 확인한다.
- **`LOCK_HELD` (exit `20`)**: 이전 실행이 진행 중이다. 중복 실행하지 않고 완료를 기다린다.
- **`SOURCE_COPY_FAIL` (OCI `prune` 경합 또는 네트워크 오류)**:
  - 미완성 스냅샷은 로컬에 저장되지 않으며, 5단계 `POST_RETENTION`의 `prune --max-unused 0`이 미참조 pack을 정리한다.
  - OCI 서버 백업 실행 중(`03:00 KST` 전후 또는 `Persistent=true` 지연 실행)과 겹쳤다면 몇 분 뒤 `pc-pull.ps1 -Command run`을 수동 재실행해 `PC_PULL_OK`를 확인한다.
- **`LOCAL_REPO_AUTH_FAIL` / `ENV_ACL_FAIL`**: 로컬 `pc-repo.pass` 또는 NTFS ACL을 복구하기 전까지 로컬 정리가 수행되지 않으므로 즉시 수정 후 수동 실행한다.
- **OCI 소실 시 PC 로컬 사본으로 복원**:
  1. 먼저 `pc-pull.ps1 -Command run`을 실행해 로컬 선만료(`expire-select.py` → 명시 ID `forget` → `prune --max-unused 0` → 재조회)를 수행한다. OCI 소실로 원본 단계는 실패(`LOCAL_RETENTION_OK_BUT_SOURCE_FAILED`, 0이 아닌 종료 코드)하지만, `%LOCALAPPDATA%\gongchae-bell\logs\pc-pull.log`에서 `PRE_RETENTION_SUMMARY ... expire=0`과 `POST_RETENTION_SUMMARY ... expire=0`이 모두 기록됐는지 확인해 14일 초과 사본이 물리 정리됐음을 검증한다(로컬 정리 자체가 실패하면 복원으로 진행하지 않는다).
  2. 로컬 만료 검증이 끝난 뒤 `pc-pull.ps1 -Command snapshots`로 남아 있는 유효(`14일 이내`) 최신 `gongchae-bell` snapshot ID를 확인한다. 남은 `gongchae-bell` 스냅샷이 `0`개이면 사용자 결정(`collab/2026-11-19-recovery/04-user-retention-decision.md`, 14일 보유기간 우선·0개 허용)에 따라 복원 가능한 유효 사본이 없는 상태로 처리하며 만료된 사본을 예외 복원하지 않는다.
  3. 유효한 `gongchae-bell` 스냅샷이 있으면 `PC_RESTIC_REPOSITORY`와 `pc-repo.pass`로 임시 접근제한 폴더에 `restic restore <SNAPSHOT_ID> --target <WORK_DIR>`로 `gongchae-bell.dump`, `backup-meta.txt`, (M2 이후) `erasure.log`를 추출한다.
  4. 이후 DB 복원·SHA-256 검증·격리 PostgreSQL 17 검증·해지 대장 재적용 및 발송 재개 게이트는 [서버 백업·복구 절차](backup-restore.md) §7을 그대로 따른다. 작업 완료 후 임시 복원 폴더를 삭제한다.
