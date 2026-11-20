# 백업 실행 시각 기준 14일 만료 결과 (#16·#17) · Claude

2026-11-19 root 배정 `claude-calendar-retention-request.md`를 수행했다. worktree `issue-16-calendar-retention`, branch `fix/#16-calendar-retention`이고 기준은 main `fd10654`, commit `ea9d0ca`다. draft PR은 https://github.com/Lcjam/gongchae-bell/pull/30 이다.

## 구현
- `deploy/backup/expire-select.py`(새 파일): `restic snapshots --tag gongchae-bell --json`을 읽어 `지금 - 시각 > 14일`인 snapshot의 전체 ID를 오래된 순으로 출력한다. 목록을 믿을 수 없으면 exit 2로 끝나며 아무것도 고르지 않는다. 해당하는 경우는 JSON·배열 아님, 64자리 hex 아닌 ID, 시간대 없는 시각, 태그 불일치, 5분을 넘는 미래 시각이다.
- `deploy/backup/backup.sh run` 순서: 시각을 1회 읽음 → 저장소 확인 → 명시 ID forget → `prune --max-unused 0`(매 실행) → 같은 시각 기준 재조회로 만료 0건 확인 → 덤프·대장·업로드 → heartbeat.
  - 만료 단계 실패(`RESTIC_SNAPSHOTS_FAIL`, `RETENTION_SELECT_FAIL`, `RESTIC_FORGET_FAIL`, `RESTIC_PRUNE_FAIL`, `RETENTION_VERIFY_FAIL`)는 덤프 전에 멈추고 `/fail`을 보낸다.
  - `BACKUP_KEEP_DAILY`는 없앴고, 설정에 남아 있으면 거부한다. `PYTHON_BIN`(기본 `python3`)은 기존 `*_BIN`과 같은 방식이다.
- ADR-0017을 새로 썼고 ADR-0012 보관 문구와 ADR 색인을 고쳤다.

## 시험 (로컬 실측, CI는 PR30에서 확인 대기)
- `backup-test.sh` 12/12 통과(Docker `python:3.12-slim`, Linux). 확인 범위:
  - 경계: 14일-1초와 정각 14일은 유지, 14일+1초는 만료
  - 전부 만료(0개), 빈 목록·null, +09:00 시간대와 나노초, 5분 이내 미래 시각 허용
  - 거부: 잘못된 입력 9종, 나쁜 항목이 하나라도 섞인 목록 전체
  - 실행 흐름: forget → prune → 덤프 → 업로드 순서, 덤프·업로드 실패일에도 만료가 먼저 됨
  - 만료 단계 실패 5종이 각각 덤프 없이 `/fail`로 끝남, prune 실패 다음 실행의 재prune
  - 기존 redaction·flock·init·대장 계약 유지
- 변이 4개가 모두 시험에 걸렸다: 경계 `>`→`>=`, 만료를 덤프 뒤로 이동, prune `--max-unused` 기본값, 재조회 검증 제거.
- `backup-integration-test.sh` 통과(실제 restic 컨테이너, postgres:17).
  - 저장소 1: 20일·14일+1시간 사본은 forget됐다(`forgotten=2`). 그 고유 blob은 prune 뒤 `restic cat blob`이 실패해 물리적으로 지워졌음을 확인했다(prune `to delete: 4 blobs / 682 B`). 14일-1시간 사본과 30일 된 `other-app` 태그 사본은 남았다. 복원 행 수 5도 맞았다.
  - 저장소 2: 사본이 모두 14일을 넘었고 덤프가 실패했다. gongchae-bell 사본은 0개, `remaining: 0 blobs`였다.
- Windows 로컬에서는 시험 하네스용 Python 경로 래퍼(scratchpad, 저장소 밖)를 썼다. CI(ubuntu arm)에는 필요 없다.

## 남은 것
- PR30 CI 결과 확인(제출 직후 아직 보지 못함).
- PR27 병합 뒤 `docs/ops/backup-restore.md` §2(보관 한계)와 env 표의 `BACKUP_KEEP_DAILY` 행을 갱신하는 일. 지금은 PR27과 겹치지 않게 건드리지 않았다.
- PC pull·OCI·운영 서버 적용, 백업 서비스 ubuntu(1001)의 대장 읽기 권한 게이트는 범위 밖이다.
- 실제 최대 보관은 "14일 + 실행 간격"(약 15일)이다. 만료가 실패한 날은 그날 백업도 하지 않는다(ADR-0017에 기록).
