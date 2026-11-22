# PR #38 설치 전 보완 (P3-1 · P3-2) 결과 (`agy`)

- **작성일**: 2026-11-22
- **요청 근거**: root Codex 오케스트레이션 지시 (`msg_5e71ccedca02`, PR38 설치 전 보완)
- **검수 원문**: `collab/reviews/pr-38-claude-round2.md` (P3-1 8.3 단축 이름, P3-2 시험용 시간/원본 설정 분리), `collab/reviews/pr-38-codex-round2.md`
- **이전 결과(보존)**: `collab/2026-11-19-resume/agy-pc-pull-result.md`, `collab/2026-11-19-resume/agy-pr38-review-fixes-result.md`

---

## 1. Worktree · 브랜치 · 커밋 · PR

| 항목 | 값 |
|---|---|
| Orca worktree | `C:/Users/lcj/orca/workspaces/my-project/issue-17-pc-pull` (단독 작성) |
| 브랜치 | `ops/#17-pc-pull` |
| 직전 기준선 (보존) | `d4ee65d4e116a8c6390e0ac12e80dbed3ef6d949` |
| 보완 소스·시험·문서 커밋 | `b6d695a2f78f250907d830411c1c20187dfe4f5f` (`fix(ops): gate test-only overrides and reject 8.3 short-name paths in PC pull (Refs #17)`) |
| 시험 경로·ACL 전파 보완 | `1ec00d7b207459240f63be04ebc857b5c86c48ec` (`test(ops): use LOCALAPPDATA Temp long path and icacls /T for RO tree ACLs (Refs #17)`) |
| PS 5.1/7 8.3 일관 거부 보완 (최종 소스 SHA) | `c174e4e4039aab3d5b08e87d3ccd09b9bf4131b3` (`fix(ops): reject 8.3 short-name segments before GetFullPath for PS 5.1 and PS 7 parity (Refs #17)`) |
| 최종 HEAD | 이 결과 파일만 추가하는 문서 커밋(`docs(ops): record PR #38 pre-install gates result (Refs #17)` — 아래 §4 소스 SHA `c174e4e` 기준 8/8 CI 성공 확인 후 문서 커밋 최종 HEAD 8/8 CI 통과까지 확인) |
| Draft PR | [#38](https://github.com/Lcjam/gongchae-bell/pull/38) (`draft`, `base=main`, `head=ops/#17-pc-pull`, `MERGEABLE`, `Refs #17` — 이슈 #17 미종료) |

---

## 2. 보완 내용

| 항목 | 수정 내용 |
|---|---|
| **1. 시험용 시간·원본 설정 분리 (`P3-2`)** | • `deploy/backup/pc/pc-pull.ps1` `Load-Config`: `PC_NOW_EPOCH_OVERRIDE`, `PC_POST_NOW_EPOCH_OVERRIDE`, `PC_ALLOW_LOCAL_SOURCE` 세 키를 **프로세스 환경변수 전용 표지** `$env:PC_PULL_TEST_HARNESS_MODE -eq "1"` 뒤로 격리. 표지가 없는 일반 운영 실행에서는 세 키 중 하나라도 비어 있지 않으면 경고 후 계속하지 않고 즉시 `CONFIG_INVALID`로 거부.<br>• `.env` 파일 안에 `PC_PULL_TEST_HARNESS_MODE`를 직접 적어 우회하려는 시도도 `CONFIG_INVALID`로 거부.<br>• 운영 기본 실행은 항상 실제 현재 UTC 시각(`Get-UtcEpochNow`)과 `s3:https://` 원본만 허용.<br>• `deploy/backup/pc/pc-pull.env.example`: `PC_CHECK_ACL=` 바로 위에 기본값(`true`) 유지 및 `PC_CHECK_ACL=false`를 운영 우회 수단으로 쓰지 말라는 경고 주석 명시.<br>• `deploy/tests/pc-pull-test.ps1`: `Invoke-PcPull`에 `-TestHarness` 스위치를 두고 해당 자식 프로세스에만 `PC_PULL_TEST_HARNESS_MODE=1`을 전달(미지정 호출에서는 명시 제거). Case `1g`에서 실제 초기화 저장소 + 유효 스냅샷을 상대로 미래 `PC_NOW_EPOCH_OVERRIDE`, `PC_POST_NOW_EPOCH_OVERRIDE`, `PC_ALLOW_LOCAL_SOURCE=true`, env 내부 `PC_PULL_TEST_HARNESS_MODE=1`이 모두 `CONFIG_INVALID`로 거부되고 유효 스냅샷 `1`건 보존·성공 heartbeat 부재를 검증. |
| **2. 8.3 단축 이름 경로 거부 (`P3-1`) + `subst` 한계 문서화** | • `deploy/backup/pc/pc-pull.ps1` `Assert-SafeLocalPath`: Windows 환경에서 **`GetFullPath` 호출 전 원시 확장 경로**와 **정규화 경로** 양쪽의 구성요소에서 8.3 단축 이름 패턴(`~[0-9]+(\.[^.]+)?$`, 예: `ONEDRI~1`, `GOOGLE~1`)을 fail-closed 거부(`DEST_SHORT_NAME_FORBIDDEN` / `PATH_SHORT_NAME_FORBIDDEN`). `.NET`(PS 7)의 `GetFullPath`가 기존 8.3 별칭을 긴 이름으로 자동 확장하기 전에 차단해 PS 5.1과 PS 7 모두 동일 오류 코드로 일관 차단하며, 저장소뿐 아니라 env·비밀번호·cache·log·lock·selector 전 역할에 적용.<br>• `deploy/tests/pc-pull-test.ps1` Case `1h`: 합성 `OneDrive - Synthetic Co`에 대해 긴 이름(`DEST_SYNC_DIR_FORBIDDEN`), 실제 OS가 생성한 8.3 단축 경로(8.3 활성 볼륨에서 repo `DEST_SHORT_NAME_FORBIDDEN` + log `PATH_SHORT_NAME_FORBIDDEN`, 미생성 확인), 결정적 8.3 문자열(`ONEDRI~1`·`GOOGLE~1`), 일반 긴 정상 경로(`normal-long-folder-name\standalone-replica`, `-TestHarness` 없이 운영 모드 `init -StandaloneInit` 성공)를 검증.<br>• `docs/ops/pc-pull-backup.md` §1: 8.3 단축 이름 거부, 시험 전용 설정 및 표지 거부, `subst` 가상 드라이브 미지원·미검증 한계를 명시. |

- 기존 불변식(로컬 선만료 → 읽기 전용 source copy → 성공/실패 후 POST 정리 → heartbeat, 14일 초과 전량 삭제/0개 허용, 조상 junction/UNC/NTFS/ACL/경로 중첩 금지/키 분리, Case 6a·6b)은 그대로 유지했다.

---

## 3. 직접 실행한 검증 결과

### 3.1 로컬 네이티브 Windows 11 검증 (`deploy/tests/pc-pull-test.ps1`)
- **실행 환경**: Windows 11 + `powershell.exe` `5.1.26100.9818 (Desktop)` + Python 3.12 + 임시 폴더 공식 `restic 0.19.1` (`restic_0.19.1_windows_amd64.zip`, SHA-256 `da948ad707ed690426473aaba2046cd61f8f90f6f0e7dab6be0d5796531de67d` 검증; 전역 미설치·종료 즉시 삭제).
- **결과 (`c174e4e`)**: **10/10 전체 통과** (`ALL_PC_PULL_TESTS_PASSED`, `PC_PULL_TEST_EXIT=0`, 기본 `PC_CHECK_ACL=true`, 실제 C: 볼륨 8.3 OS 별칭 활성 확인 및 차단 통과).
- **정적 검증**: `gitleaks dir --no-banner --redact` 변경 4파일 누출 `0`건 (`RC=0`), `git merge-tree --write-tree HEAD origin/main` `RC=0`.
- **참고**: 로컬 PC에는 `pwsh` 미설치(`PWSH=NOT_INSTALLED`)여서 추가 설치하지 않았고, PowerShell 7 검증은 아래 원격 `windows-latest` CI에서 직접 확인했다.

---

## 4. 원격 CI 검증 결과 (PR #38, 최종 소스 SHA `c174e4e4039aab3d5b08e87d3ccd09b9bf4131b3`)

- **8/8 전체 통과 (`SUCCESS`)**, `pc-pull-verify` 원본 로그에서 **Windows PowerShell `5.1.26100.33822 (Desktop)`**과 **PowerShell `7.6.10 (Core)` (`pwsh`)** 양쪽 모두 `(8.3 short-name active on volume: testing real OS alias)` + `ALL_PC_PULL_TESTS_PASSED` 확인:

| 워크플로 | 잡 | 러너/내용 | 결과 | 소요 | 링크 |
|---|---|---|---|---|---|
| CI | `pc-pull-verify` | `windows-latest`, `restic 0.19.1` SHA-256 검증 + PS `5.1.26100.33822` 10/10(실 8.3 포함) + `pwsh` `7.6.10` 10/10(실 8.3 포함) | `SUCCESS` | 5m58s | [job 137229404106](https://github.com/Lcjam/gongchae-bell/actions/runs/45029394813/job/137229404106) |
| CI | `backup-verify` | `ubuntu-latest`, `backup-test.sh` + `backup-integration-test.sh` (PG17+restic) | `SUCCESS` | 1m05s | [job 137229403745](https://github.com/Lcjam/gongchae-bell/actions/runs/45029394813/job/137229403745) |
| CI | `test` | Gradle 테스트·검증 | `SUCCESS` | 2m19s | [job 137229403971](https://github.com/Lcjam/gongchae-bell/actions/runs/45029394813/job/137229403971) |
| CI | `deploy-verify` | 배포 검증 | `SUCCESS` | 1m32s | [job 137229404089](https://github.com/Lcjam/gongchae-bell/actions/runs/45029394813/job/137229404089) |
| CI | `secrets` | gitleaks 스캔 | `SUCCESS` | 10s | [job 137229403953](https://github.com/Lcjam/gongchae-bell/actions/runs/45029394813/job/137229403953) |
| Monitoring | `rules` | Prometheus/Alertmanager 규칙 검증 | `SUCCESS` | 9s | [job 137229404081](https://github.com/Lcjam/gongchae-bell/actions/runs/45029394800/job/137229404081) |
| Monitoring | `compose-integration` | 모니터링 Compose 통합 | `SUCCESS` | 40s | [job 137229404103](https://github.com/Lcjam/gongchae-bell/actions/runs/45029394800/job/137229404103) |
| Monitoring | `alert-e2e` | 알림 E2E | `SUCCESS` | 6m48s | [job 137229403998](https://github.com/Lcjam/gongchae-bell/actions/runs/45029394800/job/137229403998) |

- PR 상태: `OPEN`, `isDraft=true`, `mergeable=MERGEABLE`.

---

## 5. 미실행 범위 및 남은 한계

- **미실행**: 실제 PC 영구 설치·실 ACL 적용·작업 스케줄러 등록, 실제 OCI/S3/IAM/버킷/계정/env/키/provider 변경, 운영 SSH 접속, `subst` 드라이브 실재현(시스템 드라이브 문자 미생성; 문서 한계로 명시), 실운영 복원 리허설.
- **남은 한계**:
  - Windows `subst` 가상 드라이브는 미지원·미검증이며 운영 경로로 사용하지 않는다.
  - 대화형 로그온 기반 작업 스케줄러의 PC 오프라인 삭제 지연, 강제 종료 시 후정리 미보장, `InRun` 이전 실패 `/fail` 미전송, `HEALTHCHECKS_PC_PULL_URL` 미설정 시 `HEARTBEAT_SKIPPED` 동작은 기존과 동일하다.
