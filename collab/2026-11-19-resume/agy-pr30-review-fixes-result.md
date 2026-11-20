# PR30 비작성자 검수 후속 보완 결과 (#16·#17) · agy

요청: `collab/2026-11-19-resume/agy-pr30-review-fixes-request.md` · 근거 리뷰: `collab/reviews/pr-30-sol.md`, `collab/reviews/pr-30-codex.md`, 사용자 결정 `collab/2026-11-19-recovery/04-user-retention-decision.md`

## 1. 브랜치 · 커밋 · PR · CI

| 항목 | 값 |
|---|---|
| worktree / 브랜치 | `C:/Users/lcj/orca/workspaces/my-project/issue-16-calendar-retention` / `fix/#16-calendar-retention` |
| 직전 HEAD (보존) | `29ac2aa67b6f32aa5a4d78f942664ca9c515c11e` (원작성자 `ea9d0ca`, PR #27 병합 `7f5be67`, `backup-restore.md` 1차 정합화 `22507a5`, `claude-calendar-retention-result.md`·`agy-pr30-finish-result.md` 모두 무변경 보존) |
| 보완 커밋 SHA | `e93a8c5872e13778da14dd0268653dc9f9a70992` (`fix(ops): clarify PC pull expiry order and retention failure logs (#16 #17)`) |
| 최종 head | 이 결과 파일(`collab/2026-11-19-resume/agy-pr30-review-fixes-result.md`)만 추가한 문서 후속 커밋 (코드·시험·ADR·`backup-restore.md` 무변경) |
| Draft PR | [#30](https://github.com/Lcjam/gongchae-bell/pull/30) (`Refs #16 #17`, `MERGEABLE`, 최신 `main` PR #28·#29·#31과 파일 충돌 없어 불필요한 추가 병합 미실행) |
| CI (`e93a8c5`) | CI [44713822749](https://github.com/Lcjam/gongchae-bell/actions/runs/44713822749) (`backup-verify`, `deploy-verify`, `test`, `secrets` 4잡 `success`) · Monitoring [44713821575](https://github.com/Lcjam/gongchae-bell/actions/runs/44713821575) (`rules`, `compose-integration`, `alert-e2e` 3잡 `success`) |

## 2. 보완 내역 (`e93a8c5`, 3 files, +6/-6)

1. **D2 — PC pull 만료 순서 정정 (`docs/adr/0017-backup-wall-clock-retention.md`, `docs/ops/backup-restore.md` §2·§8)**:
   - 기존 "OCI에서 `restic copy`로 가져온 뒤 로컬 만료" 서술을 **네트워크 작업(`restic copy`) 전에 로컬 저장소에서 `expire-select.py` + 명시 ID `forget` + `prune --max-unused 0` + 재조회 검증을 먼저 수행**하도록 고쳤다. 이로써 OCI 연결 또는 copy가 실패한 날에도 로컬의 14일 초과 사본 정리가 건너뛰어지지 않는다.
   - 이어서 원본 snapshot 시각을 보존하는 `restic copy` 뒤에도 같은 만료 재검사·정리를 수행해 14일 초과 OCI 사본이 로컬에 재보관되지 않도록 명시했고, 어느 단계든 실패하면 보유기간 준수로 세지 않는 원칙을 유지했다. (PC 코드 구현이나 저장소 구축은 추가하지 않음.)
2. **Sol P3 O1 — 만료 실패 로그 표현 정정 (`deploy/backup/backup.sh` 399·403·418행)**:
   - `select_expired`가 forget/prune 뒤 재조회에도 재사용되므로, `RESTIC_SNAPSHOTS_FAIL`과 `RETENTION_SELECT_FAIL` 메시지의 `nothing was deleted`를 `retention state unverified`로 교체해 삭제 후 재조회 실패 시에도 거짓 정보를 내지 않도록 했다.
   - `RESTIC_FORGET_FAIL` 메시지를 `forget did not complete; snapshot deletion state unverified and retention not met`으로 고쳐 부분 삭제 가능성을 정확히 표현했다.
   - 오류 코드(`RESTIC_SNAPSHOTS_FAIL`, `RETENTION_SELECT_FAIL`, `RESTIC_FORGET_FAIL`)와 종료 동작·정책은 변경하지 않았고 새 상태기계나 테스트는 추가하지 않았다.

## 3. 확인 범위 및 미접근 경계

- **직접 실행한 한정 검증 (`e93a8c5`)**:
  - `git diff --check` 통과.
  - Docker `python:3.12-slim` 컨테이너에서 `bash -n deploy/backup/backup.sh deploy/tests/backup-test.sh deploy/tests/backup-integration-test.sh` 및 `bash deploy/tests/backup-test.sh` 실행 → **`ALL_BACKUP_UNIT_TESTS_PASSED` (12/12)**.
  - 원격 `CI` `44713822749`(`backup-verify` 실제 restic+postgres 통합 시험 포함 4잡) 및 `Monitoring` `44713821575`(3잡) 전부 `success`, PR #30 `MERGEABLE` 직접 조회.
- **미실행·미접근**: 재조회 실패 주입 테스트는 새로 추가·실행하지 않았다. 원작성자 커밋 `ea9d0ca`, `claude-calendar-retention-result.md`, `agy-pr30-finish-result.md`, PR #27 병합 내용 및 타 브랜치(PR #29 포함)는 모두 보존했다. 실제 서버·PC·계정·키·provider·새 agent에는 접근하지 않았다.
