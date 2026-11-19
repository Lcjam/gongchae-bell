# PR #22 (`#16` 백업) 1차 검수 수정 결과 (`agy`)

- **작성자**: `agy`
- **대상 브랜치 / Worktree**: `ops/#16-backup` (`C:/Users/lcj/orca/workspaces/my-project/issue-16-backup`)
- **기준 HEAD (수정 전)**: `c9225fa60107679b69395a8fed33a7ca6aa1b8bf`
- **코드·테스트·운영문서 수정 커밋 SHA**: `8501539adf5c949b1c68d03c6c4d8ed726e6aec5` (`ops: fix restic forget grouping, log redaction loop, and env parsing (Refs #16)`)
- **병합 기준 `origin/main` SHA**: `be30f6999db4ccd31e590dd7de054c85c049dee4` (PR #20 반영)
- **`main` 통합 머지 커밋 SHA / CI Run**: `5a0049406ff4a1d6fd1c66f44be922ebcca2226a` / [CI Run #44282076712](https://github.com/Lcjam/gongchae-bell/actions/runs/44282076712) (`secrets` / `test` / `backup-verify` / `deploy-verify` 전 항목 `success`)
- **PR**: `https://github.com/Lcjam/gongchae-bell/pull/22` (Draft 유지, `main` 병합 및 `#16` close 미수행)
- **이전 회차 CI 기록**:
  - `8501539a`: [CI Run #44275512026](https://github.com/Lcjam/gongchae-bell/actions/runs/44275512026) (`secrets` / `test` / `backup-verify` `success`)
  - `0760ff0f`: [CI Run #44278207418](https://github.com/Lcjam/gongchae-bell/actions/runs/44278207418) (`secrets` / `test` / `backup-verify` `success`)
- **최종 HEAD 안내**: 이 문서 갱신 커밋이 `ops/#16-backup` 및 PR #22의 최종 HEAD가 되며, 푸시 후 4개 잡(`test`·`deploy-verify`·`backup-verify`·`secrets`) CI 통과 및 `mergeable=MERGEABLE` 상태를 확인해 PR #22 본문에 최종 SHA·CI를 갱신합니다.

---

## 1. 리뷰 지적사항별 수정 내역

### 1) B1 (P1): `restic forget` 경로별 그룹 분리 및 랜덤 스테이징 경로 안정화
- **수정 파일**: [deploy/backup/backup.sh](file:///C:/Users/lcj/orca/workspaces/my-project/issue-16-backup/deploy/backup/backup.sh), [docs/ops/backup-restore.md](file:///C:/Users/lcj/orca/workspaces/my-project/issue-16-backup/docs/ops/backup-restore.md)
- **내용**:
  - `run_backup()`의 `restic forget` 호출에 `--group-by host,tags`를 추가해(`--tag gongchae-bell --group-by host,tags --keep-daily "$BACKUP_KEEP_DAILY" --prune`), 서로 다른 `mktemp` 경로를 가진 기존 snapshot과 신규 snapshot이 모두 같은 논리 그룹(`host` + `tags`)으로 묶여 `keep-daily` 정리 대상이 되도록 수정했습니다.
  - `flock` 배타 잠금 획득 이후 `prepare_stage_dir()`를 호출해 스테이징 디렉터리를 고정 경로 `${BACKUP_STAGE_ROOT%/}/gongchae-backup-stage`로 정규화했습니다. 기존 잔존 디렉터리는 정리하고, `stat_mode_uid()`로 심볼릭 링크 거부·소유자 일치·`0700` 권한을 강제합니다.
  - ADR-0012의 초기값 `--keep-daily 14` 정책 자체는 임의 변경하지 않았습니다.

### 2) P2 (root Codex): `redact_stream()` 무한 반복 및 고정 문자열 마스킹 수정
- **수정 파일**: [deploy/backup/backup.sh](file:///C:/Users/lcj/orca/workspaces/my-project/issue-16-backup/deploy/backup/backup.sh)
- **내용**:
  - 기존 `while ((idx = index(out, secret)) > 0)` 루프는 `RESTIC_PASSWORD=R` 등 `[REDACTED]`의 부분 문자열이 비밀번호일 때 치환 결과 `[REDACTED]`를 처음부터 다시 검색해 무한 루프에 빠졌습니다.
  - `redact_stream()`을 `ENVIRON` 기반 변수 전달(`awk -v` 이스케이프 해석 제거)과 단일 패스·최장 일치 우선(`mask_all()`) 스캔으로 교체해 이미 치환된 `[REDACTED]` 구간을 재검색하지 않고, 역슬래시(`\n`, `\\`)·정규식 메타문자(`$`, `&`, `/`, `[`, `*`, `+`)·`[REDACTED]` 포함 합성값·접두사 중복 비밀번호에서도 정확히 1회 치환 후 종료하도록 수정했습니다.

### 3) B2 (Claude): `compose.env`의 `ALERTS_ENABLED` 따옴표·대소문자 파싱
- **수정 파일**: [deploy/backup/backup.sh](file:///C:/Users/lcj/orca/workspaces/my-project/issue-16-backup/deploy/backup/backup.sh), [docs/ops/backup-restore.md](file:///C:/Users/lcj/orca/workspaces/my-project/issue-16-backup/docs/ops/backup-restore.md)
- **내용**:
  - `alerts_enabled_in_compose_env()`가 공백, `export ` 접두사, 인라인 `#` 주석, 작은/큰따옴표(`"true"`, `'true'`), 대소문자(`TRUE`, `True`)를 정규화해 `ALERTS_ENABLED`를 판별하도록 보완했습니다.
  - 운영 문서에 M2 전환 시 명시적 안전장치로 `REQUIRE_ERASURE_LOG=true` 설정을 권장하도록 추가했습니다.

### 4) B3 (Claude) & Docker 그룹 점검 안내
- **수정 파일**: [docs/ops/backup-restore.md](file:///C:/Users/lcj/orca/workspaces/my-project/issue-16-backup/docs/ops/backup-restore.md)
- **내용**:
  - §5 사전 점검 및 설치 절차에 `id -nG ubuntu | grep -qw docker`로 `ubuntu` 사용자의 `docker` 그룹 소속 및 Compose 접근 권한을 확인하는 단계를 추가했습니다.
  - §6 모니터링 연동에 `backup.env` 로드·검증 이전 단계 실패 시 `HEALTHCHECKS_BACKUP_URL`이 로드되지 않아 `/fail` ping을 보낼 수 없으므로 Healthchecks 미수신(grace/period 만료) 알림과 `journalctl -u gongchae-backup.service`로 감지·조치함을 명시했습니다.
  - §8 `#17` 이관 범위에 `--group-by host,tags` 기준으로 실서버 격리 복원 리허설을 검증하도록 반영했습니다.

### 5) `origin/main` (`be30f699`) 통합 및 CI·Runbook 연동 (`5a004940`)
- **수정 파일**: [.github/workflows/ci.yml](file:///C:/Users/lcj/orca/workspaces/my-project/issue-16-backup/.github/workflows/ci.yml), [docs/runbook.md](file:///C:/Users/lcj/orca/workspaces/my-project/issue-16-backup/docs/runbook.md)
- **내용**:
  - `origin/main`(`be30f6999db4ccd31e590dd7de054c85c049dee4`)을 `ops/#16-backup`으로 머지하고 `.github/workflows/ci.yml` 충돌을 해소해 `test`, `deploy-verify`, `backup-verify`, `secrets` 4개 잡이 모두 유지되도록 정리했습니다.
  - `docs/runbook.md` §7 `#16 백업` 항목에 실제 운영 문서 링크 `[백업과 복구 절차](ops/backup-restore.md)`를 연결했습니다.
  - `8501539a`의 `deploy/backup/`, `deploy/tests/backup-*`, `docs/ops/backup-restore.md` 수정 의미는 머지 과정에서 100% 보존됐습니다.

---

## 2. 추가 회귀 테스트 및 검증 결과

### 1) 단위·대역 회귀 (`ubuntu:24.04` 컨테이너 내 실제 `flock` / `awk` / `timeout` 실행)
- **수정 파일**: [deploy/tests/backup-test.sh](file:///C:/Users/lcj/orca/workspaces/my-project/issue-16-backup/deploy/tests/backup-test.sh)
- **추가 검증 항목**:
  - `forget --tag gongchae-bell --group-by host,tags --keep-daily 14 --prune` 호출 인자 확인
  - `[5/11]` `erasure.log` 부재 시 `ALERTS_ENABLED=true`, `"true"`, `'true'`, `TRUE`, `"True" # inline comment` 모두 fail-closed 및 `/fail` 전송 검증
  - `[9/11]` `timeout 5` 상한 하에서 `RESTIC_PASSWORD=R` / `AWS_ACCESS_KEY_ID=E` / `AWS_SECRET_ACCESS_KEY=D` (`[REDACTED]` 구성 문자), `pre[REDACTED]post`, 역슬래시/정규식 메타문자(`p@ss\nw&rd$1/\[x]*+`, `key\\slash`, `amp&1\0`), 접두사 중복(`KEY1` / `KEY1_LONGER`)의 무한 루프 미발생·완전 마스킹 검증
- **실행 명령 및 결과**:
  - `docker run --rm -v "C:/Users/lcj/orca/workspaces/my-project/issue-16-backup:/src:ro" ubuntu:24.04 bash -lc 'mkdir -p /work && cp -a /src/. /work/ && cd /work && bash -n deploy/backup/backup.sh deploy/tests/backup-test.sh deploy/tests/backup-integration-test.sh && bash deploy/tests/backup-test.sh'`
  - 결과: exit `0`, `ALL_BACKUP_UNIT_TESTS_PASSED`

### 2) 통합 회귀 (일회용 `postgres:17` + 실제 `restic/restic:latest`)
- **수정 파일**: [deploy/tests/backup-integration-test.sh](file:///C:/Users/lcj/orca/workspaces/my-project/issue-16-backup/deploy/tests/backup-integration-test.sh)
- **추가 검증 항목**:
  - 서로 다른 임시 경로(`legacy-mktemp-1`, `legacy-mktemp-2`)와 날짜(`2026-11-15`, `2026-11-16`)로 기존 snapshot 2개를 시딩
  - 고정 스테이징 경로(`gongchae-backup-stage`)로 3일차 백업 실행: `BACKUP_KEEP_DAILY=2`에서 `SNAPSHOT_RETENTION_SUMMARY count=2`로 1건 정리 확인
  - 이어서 `BACKUP_KEEP_DAILY=1` 재실행: 다른 경로의 잔여 legacy snapshot까지 `count=1`로 축소(`restic snapshots --tag gongchae-bell --json` 잔여 `1`건) 확인 후 `pg_restore` 5행 복원 확인
- **실행 명령 및 결과**:
  - `& 'C:\Program Files\Git\bin\bash.exe' -lc 'cd /c/Users/lcj/orca/workspaces/my-project/issue-16-backup && bash deploy/tests/backup-integration-test.sh'`
  - 결과: exit `0`, `BACKUP_INTEGRATION_TEST_PASSED`

### 3) Gradle / Gitleaks / GitHub Actions ARM64 CI
- `./gradlew --no-daemon check bootJar` (`8501539a`): exit `0`, `BUILD SUCCESSFUL`
- `./gradlew --no-daemon check bootJar` (`main` 머지 `5a004940` 후): exit `0`, `BUILD SUCCESSFUL in 1m 46s`
- `gitleaks detect --source C:/Users/lcj/orca/workspaces/my-project/issue-16-backup --verbose`: exit `0`, `71 commits scanned`, `no leaks found`
- `gitleaks dir C:/Users/lcj/orca/workspaces/my-project/issue-16-backup --verbose`: exit `0`, `no leaks found`
- ARM64 CI (`8501539adf5c949b1c68d03c6c4d8ed726e6aec5`): [Run #44275512026](https://github.com/Lcjam/gongchae-bell/actions/runs/44275512026) — `secrets`, `test`, `backup-verify` 전 Job `success`
- ARM64 CI (`main` 머지 `5a0049406ff4a1d6fd1c66f44be922ebcca2226a`): [Run #44282076712](https://github.com/Lcjam/gongchae-bell/actions/runs/44282076712) — `secrets`, `test`, `backup-verify`, `deploy-verify` 4개 Job 전 항목 `success`

---

## 3. 실제 환경 후속 범위 (`#16` 잔여 및 `#17` 연계)

- OCI VM(`ubuntu`)에 `/opt/gongchae-bell/deploy/backup/backup.sh` 실행 권한과 `/opt/gongchae-bell/backup` (`0750`, `ubuntu:ubuntu`), `/opt/gongchae-bell/backup/backup.env` (`0600`, `ubuntu:ubuntu`), `systemd` 유닛(`/etc/systemd/system/gongchae-backup.{service,timer}`, `User=ubuntu`, `Group=ubuntu`)을 설치·적용하고, `id -nG ubuntu | grep -qw docker`·실제 OCI Object Storage 버킷 초기화·1회 수동 실행·자동 타이머 실행 확인
- Healthchecks.io 체크 등록(`HEALTHCHECKS_BACKUP_URL`) 및 미수신/실패(`/fail`) 알림 수신 확인
- OCI 외부 사본(NAS/PC `restic copy` pull) 및 실제 운영 키·스키마 기준 격리 VM/컨테이너 복원·해지 대장 재적용 리허설(`#17`)
- `keep-daily 14`와 달력 14일/해지대장/OCI 밖 사본 간 개인정보처리방침·문서 고지 정합성 정리(`#17`과 함께 root 진행)
