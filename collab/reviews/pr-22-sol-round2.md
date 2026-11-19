# PR22 백업 독립 2차 검수

2026-11-18 Codex 검수 담당이 구현에 참여하지 않고 수행했다. CLAUDE.md의 구현 1명·리뷰 2명 규칙에 따른 별도 검수 기록이다.

## 대상과 판정

- 최종 불변 HEAD는 `8c5da2c247f232865c2ed20dfdbb6554ce83eeb4`다. `C:/Users/lcj/orca/workspaces/my-project/issue-16-backup`의 HEAD 일치와 clean 상태를 직접 확인했다.
- 1차 검수 기준은 `c9225fa60107679b69395a8fed33a7ca6aa1b8bf`, 핵심 수정은 `8501539adf5c949b1c68d03c6c4d8ed726e6aec5`, main 통합은 `5a0049406ff4a1d6fd1c66f44be922ebcca2226a`다. 최종 HEAD에서 백업 소스·시험의 수정 의미가 유지됐다. 마지막 커밋의 변경은 결과 기록 문서다.
- **판정은 소스 준비 범위 승인이다.** 이번 검수에서 머지를 막는 P0·P1·P2 결함이나 수정으로 유입된 회귀를 발견하지 못했다. 실서버 백업·복구 완료 판정은 후속 운영 검증까지 남는다.

## 기존 결함의 해소 근거

### P1 보관 정리 묶음

`deploy/backup/backup.sh:496-498`은 `forget --tag gongchae-bell --group-by host,tags --keep-daily "$BACKUP_KEEP_DAILY" --prune`을 호출한다. 경로가 다른 기존 snapshot도 동일 host·tags 묶음의 정책 적용 대상에 들어간다. 신규 스테이징 경로는 잠금 획득 후 `${BACKUP_STAGE_ROOT%/}/gongchae-backup-stage`로 고정된다. `prepare_stage_dir`의 호출보다 `acquire_lock`이 앞서고, 정상 실행 경로에서 잠금 FD를 유지한다.

검수자가 네트워크가 차단된 일회용 `restic/restic:latest` 컨테이너에서 합성 파일과 컨테이너 내부 로컬 저장소로 독립 재현했다. 실행 바이너리는 `restic 0.19.1`, `linux/amd64`였다. 서로 다른 경로·날짜의 동일 host·tags snapshot 3개를 만든 뒤 제출 명령의 grouping으로 `keep-daily=2`에서 2개, `keep-daily=1`에서 1개가 남았다. 최신 합성 파일의 복원 내용까지 일치했다. 종료 코드는 0이고 `RESTIC_DISTINCT_PATH_RETENTION_3_TO_2_TO_1_AND_RESTORE_PASSED`를 확인했다.

제출 통합 시험도 legacy 경로 2개를 시딩한 뒤 실제 `backup.sh` 실행으로 3→2→1 정리, 최신 덤프와 해지 대장 복원, PostgreSQL 5행 확인을 수행하도록 구성돼 있다. 이 전체 PostgreSQL 통합 시험은 이번 검수자가 재실행하지 않았다.

### P2 마스킹 무한 반복

`backup.sh:36-98`은 합성 비밀값을 `ENVIRON`으로 전달하고 입력의 미처리 부분만 전진하며 치환한다. 같은 위치에 여러 후보가 있으면 긴 값을 택한다. 처리 결과에 들어간 `[REDACTED]`를 다시 입력으로 검색하는 경로가 사라졌다. `awk -v`의 역슬래시 해석에 의존하던 값 전달도 제거됐다.

최종 소스의 단위·대역 시험을 독립 실행해 `[REDACTED]`의 구성 문자, 마커를 포함한 값, 역슬래시와 정규식 문자, 접두사가 겹치는 값에 대한 5초 상한 회귀 검증이 통과한 것을 직접 확인했다. env 로더는 `source`·`eval` 없이 `read -r`과 `printf -v`로 값을 적재하고, restic 인수는 따옴표와 배열로 전달한다. 앱 Compose env를 백업 프로세스 환경으로 적재하는 경로도 없다.

### ALERTS_ENABLED와 운영 문서

- `backup.sh:286-305`의 parser는 작은·큰따옴표, 대소문자, CRLF 제거, `export` 접두사, 주석, 마지막 일치 값 처리를 포함한다. 단위 시험이 다루는 `true`, `"true"`, `'true'`, `TRUE`, `"True" # inline comment` 각각에서 대장 부재가 실패하고 `/fail`이 기록되는 것을 직접 확인했다.
- `docs/ops/backup-restore.md`는 M2 전환 시 `REQUIRE_ERASURE_LOG=true` 권고, 설정·도구 점검의 초기 실패를 미수신 경보로 감지하는 절차, `ubuntu` Docker 그룹 점검을 포함한다.
- 문서의 실행 파일·예시 파일·systemd 유닛 원본 경로가 저장소의 `deploy/backup/` 경로 및 `ExecStart=/opt/gongchae-bell/deploy/backup/backup.sh`와 일치한다. 백업 스크립트와 두 시험은 Git에서 `100755`다. runbook의 백업 문서 링크도 연결돼 있다.

## 직접 수행한 격리 검증

진단 코드는 root의 ignored `.tools/pr22-round2/probe.sh`, `.tools/pr22-round2/restic-probe.sh`에 두었다. 기존 로컬 이미지를 `--pull never --network none`으로 실행했다. Ubuntu에는 대상 백업·시험 디렉터리를 읽기 전용으로 마운트했고 Docker 소켓을 제공하지 않았다. 시험용 파일과 저장소는 종료 시 폐기되는 컨테이너 안에 생성했다.

1. 최종 소스의 `bash -n`과 `deploy/tests/backup-test.sh` 전체 11개 구간이 exit 0 및 `ALL_BACKUP_UNIT_TESTS_PASSED`로 통과했다. 실제 `flock`, `awk`, `timeout`을 사용하고 Docker·restic·curl은 합성 대역으로 실행했다.
2. 성공, 덤프 실패, 빈 TOC, restic 백업 실패, prune 실패, 성공 heartbeat 실패, 필수 해지 대장 부재의 7개 경로를 별도로 실행했다. 기대한 종료 성공·실패와 각 실행 뒤 고정 스테이징 디렉터리 제거를 확인했고 `SUPPLEMENTAL_CLEANUP_PROBES_PASSED`를 얻었다.
3. 잔존 합성 파일이 있는 동일 소유자 경로를 준비하면 기존 내용이 제거되고 새 디렉터리가 `0700`으로 만들어졌다. 심볼릭 링크는 거부되고 링크 대상 sentinel은 유지됐다. 다른 UID 소유의 잔존 경로도 거부되고 소유자가 유지됐다. `SUPPLEMENTAL_STAGE_PROBES_PASSED`를 얻었다.
4. 실제 restic의 다중 경로 보관 정리·복원 격리 시험이 통과했다.

최초 Docker 이미지 조회는 sandbox 접근 제한으로 실패했고, 승인 검토를 거친 제한된 로컬 이미지 조회와 위 격리 실행으로 검증을 마쳤다. 구현 소스·시험·설정·브랜치·main은 수정하지 않았다.

## 보고로 확인한 범위

- root 조율자가 최종 HEAD의 CI `44282472364`에서 `test`, `deploy-verify`, `backup-verify`, `secrets` 전 잡 success를 직접 조회했다고 전달했다. 이 검수자는 해당 CI를 별도로 조회하지 않았다. [최종 CI](https://github.com/Lcjam/gongchae-bell/actions/runs/44282472364)
- agy 수정 결과에는 실제 일회용 PostgreSQL 17와 restic 통합 시험의 3→2→1 정리 및 5행 복원 성공이 기록돼 있다. 이번 검수자는 소스·시험 구조와 독립적인 실제 restic 축소 실험으로 교차 검토했다. [작성자 수정 결과](../2026-11-18-implementation/agy-backup-fixes.md)
- restic 정책과 OCI 한도의 외부 사실은 기존 두 검수와 root의 공식 문서 교차 검증을 참조했다. 이번 라운드에서 새 외부 사실을 추가 확정하지 않았다. [Claude 1차 검수](pr-22-claude.md) · [Codex 1차 검수](pr-22-codex.md)

## 남은 실서버 검증

- 실제 Ubuntu 호스트의 restic·Docker/Compose·flock·curl 가용성, `ubuntu` 실행 권한, 배치 경로·실행 권한·env 권한, systemd의 03시 일정과 수동·예약 실행을 확인해야 한다.
- 실제 OCI 저장소 초기화·업로드·prune와 사용량, Healthchecks 성공·실패·미수신 경보를 확인해야 한다.
- #17에서 OCI 밖 사본, 운영 스키마·키를 사용하는 격리 PostgreSQL 복원과 해지 대장 재적용, 실측 복원 기록을 남겨야 한다.
- 채택된 `keep-daily 14`와 달력 기준 14일, 해지 대장·외부 사본의 고지 정합성 결정은 #17 범위로 유지한다.

이 검수에서는 실제 provider/API, 실제 `.env`·키·개발 DB·서버·OCI·DNS·결제에 접근하지 않았다. 전체 PostgreSQL 통합 시험과 최종 CI는 위의 작성자·조율자 보고로 구분했다.
