# #17 Windows PC Pull · 14일 보유기간 Source 구현 결과 (`agy`)

- **작성일**: 2026-11-22
- **요청 근거**: `collab/2026-11-19-resume/agy-pc-pull-request.md` (root Codex 1라운드 배정, 2026-11-22 인계 완료)
- **정책/설계 근거**: `CLAUDE.md`, `collab/2026-11-19-recovery/04-user-retention-decision.md`, `collab/2026-11-19-recovery/08-final.md`, `collab/2026-11-19-resume/agy-backup-operational-prep.md`, `collab/2026-11-19-resume/agy-oci-backup-preflight-result.md`, `docs/adr/0017-backup-wall-clock-retention.md`

---

## 1. Worktree · 브랜치 · 커밋 · PR

| 항목 | 값 |
|---|---|
| Orca worktree | `C:/Users/lcj/orca/workspaces/my-project/issue-17-pc-pull` (단독 작성) |
| 브랜치 | `ops/#17-pc-pull` |
| 기준 `main` | `7cb464a7b4a51300ee1d1fe3d78f9478c4623cad` (PR #30 병합 커밋; 최신 `origin/main` `6c9d1c71a8490b455659a2a886ffc46c71200b70`과 `git merge-tree --write-tree` 충돌 없음 확인) |
| 소스·시험·CI·문서 커밋 | `f8b317167b255e43e5fef499cbb19c898183109c` (`feat(ops): add Windows PC restic pull and 14-day wall-clock retention (Refs #17)`) |
| CI 결정성 보완 커밋 (최종 소스 SHA) | `e807a342cea1d3094a4e717af56dc2e2a3d847d0` (`test(ops): isolate PC cache and remove all source packs in copy-race case (Refs #17)`) |
| 최종 HEAD | 이 결과 파일만 추가하는 문서 커밋(`docs(ops): record #17 Windows PC pull implementation result (Refs #17)` — 아래 §4의 소스 SHA `e807a34` 기준 8/8 CI 통과 후 문서 커밋 최종 HEAD CI도 통과 확인 예정) |
| Draft PR | [#38](https://github.com/Lcjam/gongchae-bell/pull/38) (`draft`, `base=main`, `head=ops/#17-pc-pull`, `MERGEABLE`, `Refs #17` — 이슈 #17 미종료) |

---

## 2. 구현 요약 (파일별)

| 파일 | 변경 내용 |
|---|---|
| `deploy/backup/expire-select.py` | 기존 CLI/출력 형식(`expired` 기본 모드)을 100% 유지하면서 선택 옵션 `--mode expired\|kept` 추가. 서버 `backup.sh`와 Windows PC `pc-pull.ps1`이 동일한 14일 wall-clock 엄격 검증(JSON 배열·64-hex ID·`gongchae-bell` 태그·timezone 포함 시각·미래 300초 초과 거부)을 재사용하도록 단일화. |
| `deploy/tests/backup-test.sh` | 기존 서버 회귀 테스트를 보존하고 `expire-select.py --mode kept` 경계값(정확히 14일/14일-1초 포함, 14일+1초 제외), 오염 항목 포함 시 전체 거부, 잘못된 `--mode` 거부 검증 추가. |
| `deploy/backup/pc/pc-pull.ps1` | Docker Desktop 비의존 네이티브 Windows PowerShell(`5.1+`/`7+`) 실행기(`init` / `run` / `snapshots`).<br>• 기본 경로 `%LOCALAPPDATA%\gongchae-bell\` (`backup-replica`, `backup-env`, `runtime`, `cache`, `logs`).<br>• 로컬 대상 안전 가드: 원격 백엔드(`s3:` 등), UNC 경로(`\\server\share`), reparse point/심볼릭 링크, 네트워크 드라이브, 비-NTFS 볼륨, OneDrive, Google Drive(경로·VolumeLabel), Git 체크아웃(`.git` 상위 탐색) 거부.<br>• Windows Well-Known SID(`S-1-5-18`, `S-1-5-32-544`, `S-1-3-0`, 현재 사용자 SID) 기반 NTFS ACL 검증(`ENV_ACL_FAIL`), `BACKUP_KEEP_DAILY` 거부, 로컬/OCI 비밀번호 동일 사용 거부, URL 내 basic-auth 자격 내장 거부.<br>• 단일 실행 OS 배타 잠금(`FileShare.None` → `LOCK_HELD`, exit `20`).<br>• 엄격한 역할 분리: 로컬 `cat config`/`snapshots`/`forget`/`prune` 호출에서 모든 `AWS_*`·`RESTIC_FROM_*`·주변 환경변수를 제거하고 `-r <LOCAL_DST>`만 전달.<br>• **네트워크/OCI 자격 확인 전 로컬 선만료**: `OCI_RESTIC_PASSWORD_FILE` 읽기·ACL 검증을 소스 단계로 지연해 원본 장애·비밀번호 파일 누락/오류일에도 로컬 `>14일` 명시 64-hex ID `forget` → `prune --max-unused 0` → `expire=0` 재검증을 먼저 완료(0개 잔류 정상 허용; 로컬 비밀번호/권한 오류는 `LOCAL_REPO_AUTH_FAIL` / `LOCAL_REPO_UNAVAILABLE` 명시 실패).<br>• **읽기 전용 원본 복사**: `restic --no-lock snapshots --tag gongchae-bell --json` → `expire-select.py --mode kept`로 `<=14일` 유효 full ID만 선별 → `restic --no-lock --cache-dir <CACHE> -r <LOCAL_DST> copy --from-repo <OCI_SRC> <ids...>` 실행(원본 잠금 쓰기 없음, 대상 append 잠금 유지, 원본 `snapshot.time` 보존, 멱등 재실행, `COPY_MAX_RETRIES` `0..3` 제한 재시도).<br>• **후정리 및 정직한 알림**: `copy` 성공/실패와 무관하게 전송 종료 시점 UTC 기준 로컬 `forget` + `prune --max-unused 0` + `expire=0` 재검증을 항상 수행(전송 중 경계 초과 사본·부분 실패 고아 pack 정리). 모든 단계 성공 시에만 Healthchecks 성공 핑 전송, 실패 시 마스킹된 `/fail` 전송 및 `LOCAL_RETENTION_OK_BUT_SOURCE_FAILED` 분리 기록.<br>• 출력·로그·`/fail` 본문에서 비밀번호·키·Healthchecks 토큰/URL·URL 자격 자동 `[REDACTED]` 처리. |
| `deploy/backup/pc/pc-pull.env.example` | 빈 비밀값 설정 키 예시(로컬/OCI 비밀번호 파일 분리, 읽기 전용 IAM 주체 전용 키 명시, PC pull 전용 `HEALTHCHECKS_PC_PULL_URL`). |
| `deploy/tests/pc-pull-test.ps1` | 실제 암호화 `restic 0.19.1` 로컬 저장소와 로컬 루프백 HTTP 모의 Healthchecks 서버를 사용하는 10개 시나리오 격리 통합 테스트. |
| `.github/workflows/ci.yml` | `pc-pull-verify` (`windows-latest`) 잡 추가: 공식 `restic_0.19.1_windows_amd64.zip` 다운로드 + SHA-256(`da948ad707ed690426473aaba2046cd61f8f90f6f0e7dab6be0d5796531de67d`) 검증 후 `deploy/tests/pc-pull-test.ps1` 실행. |
| `docs/ops/pc-pull-backup.md` | 체크아웃/Orca 세션 수명과 분리된 `%LOCALAPPDATA%\gongchae-bell\runtime\` 배치, 읽기 전용 IAM 주체 분리, NTFS ACL(`/inheritance:r` + 사용자/`SYSTEM`/`Administrators`), 비승격 로그온 기반 작업 스케줄러(`StartWhenAvailable` + 로그온 복구), 무인 로그오프 불가·PC 오프라인 삭제 지연·미수신 한계, PC 사본 복구 절차 문서화. |
| `docs/adr/0017-backup-wall-clock-retention.md`, `docs/ops/backup-restore.md` | Windows PC pull 구현체(`pc-pull.ps1`, `--mode kept`, `pc-pull-backup.md`) 반영 및 미실행 운영 적용 범위 유지. |

---

## 3. 직접 실행한 검증 결과

### 3.1 네이티브 Windows 11 PC 격리 시험 (`deploy/tests/pc-pull-test.ps1`)
- **실행 환경**: Windows 11 + `powershell.exe` (5.1) + Python 3.12 (`C:\Users\lcj\AppData\Local\Programs\Python\Python312\python.exe`) + 임시 폴더 내 공식 포터블 `restic 0.19.1` (`restic_0.19.1_windows_amd64.zip`, 공식 `v0.19.1` `SHA256SUMS` 대조 및 SHA-256 `da948ad707ed690426473aaba2046cd61f8f90f6f0e7dab6be0d5796531de67d` 일치 확인; 전역 미설치, 시험 종료 즉시 임시 폴더 삭제 확인, `GLOBAL_RESTIC=False`).
- **결과**: 최종 소스(`e807a34`) 기준 **10/10 통과** (`ALL_PC_PULL_TESTS_PASSED`, `PC_PULL_TEST_EXIT=0`):
  1. 가드레일: `s3:` 원격 대상(`DEST_MUST_BE_LOCAL`), UNC 대상(`DEST_MUST_BE_LOCAL`), Git 체크아웃(`DEST_GIT_REPO_FORBIDDEN`), `BACKUP_KEEP_DAILY` 거부, 광범위 Windows ACL(`Users:R` → `ENV_ACL_FAIL`) 거부.
  2. 초기화: NTFS 읽기 전용 원본에서 `init --from-repo --copy-chunker-params --no-lock` 성공 및 멱등 재실행(`PC_RESTIC_REPO_ALREADY_INITIALIZED`).
  3. 14일 경계·원본 보존·읽기 전용 복사·멱등성: 원본 20일/14일+1초 미복사, 정확히 14일/14일-1초만 원본 `snapshot.time` 그대로 복사, 로컬 18일 초과 사본 선만료 삭제, 로컬 30일 `foreign-tag` 유지, 원본 `locks` 0개 유지, 재실행 중복 없음, 성공 heartbeat 수신.
  4. 전부 만료 `0`개 허용: 로컬·원본 모두 14일 초과 시 로컬 `gongchae-bell` 0개 + `PC_PULL_OK` 정상 종료, `foreign-tag` 보존.
  5. 원본 장애 시 로컬 선만료 유지 및 로컬 인증 실패 구분: 원본 경로/비밀번호 오류, `COPY_MAX_RETRIES=1`/`delay=0` 재시도(`attempt 1/2`→`2/2`), `OCI_RESTIC_PASSWORD_FILE` 누락 시에도 로컬 선만료·`prune` 완료 후 `LOCAL_RETENTION_OK_BUT_SOURCE_FAILED` + `/fail` 기록; PC/OCI 동일 비밀번호 거부; 로컬 오비밀번호 `LOCAL_REPO_AUTH_FAIL` 명시 실패.
  6. 대상 잠금 보존: `dst/locks` 읽기 전용 시 `--no-lock` 전달에도 대상 잠금 실패로 중단.
  7. 복사 부분 실패 / OCI `prune` 경합: 독립 PC 캐시 + 원본 data pack 전체 제거 시 재시도 후 `SOURCE_COPY_FAIL`, 미완성 스냅샷 `0`개, `POST_RETENTION_SUMMARY` (`prune --max-unused 0`) 실행, `/fail` 전송.
  8. 전송 중 14일 경계 초과: 사전 시각 기준 복사 허용(`<=14일`) 후 사후 시각(`+2초`)에서 `POST_RETENTION`이 즉시 `forget` + `prune`해 0개 잔류.
  9. 단일 실행 잠금: OS `FileShare.None` 선점 상태에서 `LOCK_HELD` (exit `20`).
  10. 비밀값 redaction: 정규식 메타문자 포함 로컬/OCI 비밀번호·키·Healthchecks 토큰이 stdout, stderr, `pc-pull.log`, `/fail` heartbeat 본문 모두에서 `[REDACTED]`로 마스킹됨 확인.

### 3.2 기타 로컬 정적·단위 검증
- `expire-select.py` 기본 / `--mode expired` / `--mode kept` Python 3.12 직접 스모크 검증 통과 (`RC=0`).
- `bash -n deploy/backup/backup.sh deploy/tests/backup-test.sh deploy/tests/backup-integration-test.sh` 구문 검증 통과 (`RC=0`).
- `gitleaks dir --no-banner --redact` 변경/신규 전체 파일(`deploy/backup`, `deploy/tests/pc-pull-test.ps1`, `deploy/tests/backup-test.sh`, `docs/ops/pc-pull-backup.md`, `docs/ops/backup-restore.md`, `docs/adr/0017-backup-wall-clock-retention.md`, `.github/workflows/ci.yml`) 누출 `0`건 (`RC=0`).
- 문서 상대 링크 검증(`docs/ops/pc-pull-backup.md`, `docs/ops/backup-restore.md`, `docs/adr/0017-backup-wall-clock-retention.md`): `checked=18 missing=0`.
- `git merge-tree --write-tree HEAD origin/main`: `RC=0` (최신 `main` `6c9d1c7`과 충돌 없음).
- 참고: Windows Git Bash에는 `flock`이 없고 로컬 Docker Desktop 데몬이 미기동 상태여서 Linux용 `backup-test.sh` / `backup-integration-test.sh` 전체 실행은 아래 원격 Ubuntu CI `backup-verify` 잡에서 직접 검증됨.

---

## 4. 원격 CI 검증 결과 (PR #38, 소스 최종 SHA `e807a342cea1d3094a4e717af56dc2e2a3d847d0`)

- 초기 소스 커밋 `f8b3171`의 `pc-pull-verify`에서 Case 7이 공유 캐시 + 단일 pack 선택 순서에 따라 간헐 성공하던 비결정성을 원격 `windows-latest`가 포착했고, `e807a34`에서 독립 PC 캐시와 전체 data pack 제거로 수정한 뒤 **8/8 전체 통과** 확인:

| 워크플로 | 잡 | 러너/내용 | 결과 | 소요 | 링크 |
|---|---|---|---|---|---|
| CI | `pc-pull-verify` | `windows-latest`, `restic 0.19.1` SHA-256 검증 + `pc-pull-test.ps1` 10/10 | `SUCCESS` | 2m22s | [job 137042327514](https://github.com/Lcjam/gongchae-bell/actions/runs/44968339684/job/137042327514) |
| CI | `backup-verify` | `ubuntu-latest`, `backup-test.sh` (`--mode kept` 포함) + `backup-integration-test.sh` (PG17+restic) | `SUCCESS` | 1m02s | [job 137042327704](https://github.com/Lcjam/gongchae-bell/actions/runs/44968339684/job/137042327704) |
| CI | `test` | Gradle 테스트·검증 | `SUCCESS` | 2m28s | [job 137042327644](https://github.com/Lcjam/gongchae-bell/actions/runs/44968339684/job/137042327644) |
| CI | `deploy-verify` | 배포 검증 | `SUCCESS` | 1m30s | [job 137042327707](https://github.com/Lcjam/gongchae-bell/actions/runs/44968339684/job/137042327707) |
| CI | `secrets` | gitleaks 스캔 | `SUCCESS` | 10s | [job 137042327680](https://github.com/Lcjam/gongchae-bell/actions/runs/44968339684/job/137042327680) |
| Monitoring | `rules` | Prometheus/Alertmanager 규칙 검증 | `SUCCESS` | 9s | [job 137042327588](https://github.com/Lcjam/gongchae-bell/actions/runs/44968339662/job/137042327588) |
| Monitoring | `compose-integration` | 모니터링 Compose 통합 | `SUCCESS` | 36s | [job 137042327818](https://github.com/Lcjam/gongchae-bell/actions/runs/44968339662/job/137042327818) |
| Monitoring | `alert-e2e` | 알림 E2E | `SUCCESS` | 6m10s | [job 137042327756](https://github.com/Lcjam/gongchae-bell/actions/runs/44968339662/job/137042327756) |

- PR 상태: `OPEN`, `isDraft=true`, `mergeable=MERGEABLE`.

---

## 5. 미실행 범위 및 남은 한계 (정직한 경계)

- **미실행 (이번 소스/격리 검증 라운드 범위 밖)**:
  - 실제 OCI Object Storage 버킷, 전용 읽기 전용 IAM 사용자·그룹·정책, Customer Secret Key, OCI Budget/알림 생성.
  - 실제 서버 SSH 접속, `backup.sh` 실운영 실행, 실제 OCI S3 엔드포인트 대상 `restic copy --no-lock` 연동 시험.
  - 사용자 Windows 11 PC에 영구 `restic` 전역 설치, `%LOCALAPPDATA%\gongchae-bell\` 실운영 디렉터리 생성·NTFS ACL 적용·비밀번호 파일 배치·작업 스케줄러 등록.
  - 실제 Healthchecks.io PC pull 체크 생성 및 실운영 성공/`/fail`/미수신 경보 수신 검증, 실제 Discord/Resend 발송.
  - 실운영 스키마·키 기반 PC 로컬 사본 복원 리허설 및 `ERASURE_COVERAGE_VERIFIED` + `ERASURE_REAPPLY_COMPLETE` 통합 확인.
- **운영상 구조적 한계 (문서 반영 완료)**:
  - 비승격 사용자 작업 스케줄러(`-LogonType Interactive`)는 사용자가 Windows에 로그온한 상태에서만 실행되며, PC 전원 꺼짐·절전·로그오프 기간 동안에는 로컬 `forget`/`prune`이 실행되지 않아 14일 초과 사본이 물리적으로 지연 잔류할 수 있다(상시 가동 NAS로 보장하지 않음; 다음 로그온/`StartWhenAvailable` 복귀 실행의 선만료 단계에서 우선 정리).
  - OCI 서버 백업(`prune`)과 동시 실행 경합이나 네트워크 장애로 `copy`가 실패하면 제한 재시도 후 실패 종료(`/fail`)하며, 로컬 선만료·후정리만 완료된 상태는 동기화 성공으로 간주하지 않는다.
  - PC 장기 오프라인 시 Healthchecks.io 미수신 경보가 발생하며, 경보 미해소 상태는 보유기간 준수로 집계하지 않는다.
