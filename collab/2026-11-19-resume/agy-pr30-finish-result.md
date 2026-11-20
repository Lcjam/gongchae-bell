# PR30 남은 통합·문서 마무리 결과 (#16·#17) · agy

요청: `collab/2026-11-19-resume/agy-pr30-finish-request.md` · 작성권 인계: `collab/2026-11-19-resume/pr30-writer-handoff.md`

## 1. 작성권 · 브랜치 · 커밋 · PR · CI

| 항목 | 값 |
|---|---|
| worktree / 브랜치 | `C:/Users/lcj/orca/workspaces/my-project/issue-16-calendar-retention` / `fix/#16-calendar-retention` |
| 원작성자 (source·시험·ADR) | primary Claude — 코드 커밋 `ea9d0ca3bee5e433858a388d92272a0cafad763d`, 원 결과 [`claude-calendar-retention-result.md`](claude-calendar-retention-result.md)(무변경 보존) |
| 후속 단독 작성자 (통합·운영문서) | 추가 agy2 |
| `origin/main` 병합 (PR #27 포함) | `c4465f5f3029550168337408f77e7ee6960e18d8` → 병합 커밋 `7f5be67` (무충돌 `ort`) |
| 운영 문서·원 결과 커밋 SHA | `22507a515a892bee9bbce78fb29b9bd821edb5b5` (`docs(ops): align backup-restore doc with ADR-0017 wall-clock retention (#16 #17)`) |
| 최종 head | 이 결과 파일(`collab/2026-11-19-resume/agy-pr30-finish-result.md`)만 추가한 문서 후속 커밋 (코드·시험·`backup-restore.md` 무변경) |
| Draft PR | [#30](https://github.com/Lcjam/gongchae-bell/pull/30) (`Refs #16 #17`) |
| CI (`ea9d0ca`, 기존) | CI [44510882353](https://github.com/Lcjam/gongchae-bell/actions/runs/44510882353) 4잡 `success` · Monitoring [44510882605](https://github.com/Lcjam/gongchae-bell/actions/runs/44510882605) 3잡 `success` |
| CI (`22507a5`, 통합·문서 갱신) | CI [44712541558](https://github.com/Lcjam/gongchae-bell/actions/runs/44712541558) (`test`, `deploy-verify`, `backup-verify`, `secrets` 4잡 `success`) · Monitoring [44712541547](https://github.com/Lcjam/gongchae-bell/actions/runs/44712541547) (`rules`, `compose-integration`, `alert-e2e` 3잡 `success`) |

## 2. 수행 내역

1. **최신 `main`(`c4465f5`, PR #27) 통합 (`7f5be67`)**:
   - PR #27의 격리 복원 절차, `ubuntu`(UID `1001`)·컨테이너(`1000:1000`) 권한 분리, 사고별 분기(M1 전용/호스트 생존 A/VM 영구 소실 B), 전환 직전 운영 앱 중지 후 최신 호스트 대장 재적용(§7.1 6.2), `sudo rm -rf` 정리, `docs/ops/alerts.md` 변경을 충돌 없이 그대로 보존했다.
2. **`docs/ops/backup-restore.md`를 ADR-0017 및 실제 `backup.sh`·`expire-select.py`에 정합화 (`22507a5`)**:
   - **근거·§1**: `[ADR-0017](../adr/0017-backup-wall-clock-retention.md)` 추가, `backup.sh run`의 덤프 전 만료→`prune --max-unused 0`→재조회 0건 검증 순서 및 `backup-meta.txt`의 `retention=wall-clock-14d` 반영.
   - **§2 보관 주기 및 한계**: 기존 `--keep-daily 14`·`--keep-within`(최신 snapshot 기준)을 새 동작처럼 남기지 않고 폐기·대체 사실을 명시했다. 실행 시각(`RUN_NOW_EPOCH`) 기준 14일 초과(`> 1,209,600`초) 명시적 전체 ID `restic forget`·최후 사본 예외 없음(0개 허용)·매 실행 `restic prune --max-unused 0`·재조회 0건 확인(`RETENTION_VERIFY_FAIL`)·신뢰 불가 목록 거부(`RETENTION_SELECT_FAIL`)·타 태그 보존을 실제 소스와 맞췄다.
   - **실제 물리 잔류 한계 구분**: "최대 14일 고정 삭제"를 약속하지 않고 ① 실행 간격에 따른 실제 최대 잔류(14일 + 실행 간격 약 15일), ② `forget`(목록 제거)과 `prune`(pack 물리 삭제) 구분 및 자격/네트워크/잠금/prune 오류 시 지연 잔류, ③ OCI 밖 PC/NAS 오프라인 기간 잔류 및 복귀 후 동일 `expire-select.py`+명시 ID `forget`+`prune --max-unused 0` 정리, ④ 장기 실패 시 0개 허용을 분리해 적었다.
   - **§3·§4·§5·§6**: OCI 한도 표의 `keep-daily` 문구 교체, env 표에서 `BACKUP_KEEP_DAILY` 제거 및 기존 `backup.env` 마이그레이션/거부(`BACKUP_KEEP_DAILY is no longer supported`) 안내 추가, 호스트 필수 도구 `python3` 명시, §6 실패 경보에 만료 단계 5종(`/fail` 중단)과 사전 점검 실패(`BACKUP_KEEP_DAILY` 잔존·`python3` 누락 등 미수신 경보 감지) 반영.
   - **§8 `#17` 이관 범위**: pull 쪽 만료 기준을 구 `--group-by host,tags`에서 `expire-select.py` + 명시 ID `forget` + `prune --max-unused 0`(오래된 OCI 사본 재보관 방지·오프라인 한계 반영)으로 맞추고, `erasure.log` 정리 주기·허용 복원 최소 시점·연속 커버리지·PC pull 복구 절차·처리방침 고지 정합성을 남은 항목으로 유지했다.
3. **원작성자 보고서 보존 (`22507a5`)**:
   - `collab/2026-11-19-resume/claude-calendar-retention-result.md`(SHA-256 `e3f0de1fdd14ae29…`)를 root 원본과 바이트 동일하게 커밋 보존했다.

## 3. 실제 확인 범위 및 미접근 경계

- **agy 직접 확인**: `git merge origin/main` 무충돌 통합, `git diff c4465f5 -- docs/ops/backup-restore.md docs/ops/alerts.md`로 PR #27 복원·권한·사고별 분기·M1·6.2 보존 확인, `backup.sh`·`expire-select.py`·`backup.env.example`·`ADR-0017` 대조, `claude-calendar-retention-result.md` 바이트 일치, 상대 링크 검사(`broken_links=0`), 민감값 패턴 점검(`secrets=0`), `git diff --check` 통과, 원격 CI `44712541558`(4잡)·Monitoring `44712541547`(3잡) 전부 `success` 직접 조회.
- **미실행·미접근**: 앱 코드·백업 스크립트·테스트를 수정하지 않았으므로 로컬 전체 Gradle/Docker 시험은 반복하지 않았으며(원격 CI `backup-verify`·`test`·`deploy-verify`·`Monitoring`으로 확인), PR #29(`docs/#19-nov19-review-records`)와 타 작성자 브랜치는 건드리지 않았다. 실제 OCI·운영 서버·`.env`·키·PC 저장소·provider·실제 백업 타이머 설치/적용에는 접근하지 않았다.
