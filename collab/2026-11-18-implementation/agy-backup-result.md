# #16 백업 구현 1라운드 결과 · agy

- **상태**: **백업 스크립트·systemd 유닛·테스트·CI·운영 문서 준비 완료 (Draft PR #22 제출, 실서버 OCI/Healthchecks/복원 리허설 미실시)**
- **작업 브랜치 / 워크트리**: `ops/#16-backup` (`C:/Users/lcj/orca/workspaces/my-project/issue-16-backup`)
- **기반 `main` SHA**: `000dce555e9eb69ef3184dd5f80aa1d499a1f602`
- **코드·문서 커밋 SHA**: `f0605c3b3934e8310452ddab94ac17c12c62f2c4` (`ops: add daily pg_dump/erasure restic backup, systemd units, CI verification, and backup-restore doc (Refs #16)`)
- **ARM64 CI 실행 (`f0605c3b`)**: [GitHub Actions Run #44227670423](https://github.com/Lcjam/gongchae-bell/actions/runs/44227670423) (`test` ✓ 1m42s · `backup-verify` ✓ 50s · `secrets`/Gitleaks ✓ 6s)
- **Draft PR**: [#22](https://github.com/Lcjam/gongchae-bell/pull/22) (`DRAFT`, `Refs #16`, `main` 미머지, `#16` 미닫음)

---

## 1. 구현 산출물 요약

| 파일 | 역할 및 핵심 내용 |
|---|---|
| `deploy/backup/backup.sh` | 호스트 일일 백업 스크립트 (`flock` 배타 잠금 `exit 20`, `backup.env` `0600`/`0400` 권한·소유자 검사, 앱 `.env` 미로드 분리). 운영 Compose `postgres` 컨테이너 내부에서 `pg_dump -Fc` + `pg_restore --list` TOC 검증 수행(`compose.prod.yml`의 `APP_BLUE_TAG`/`APP_GREEN_TAG` 보간 호환, 실행 중 DB 볼륨 파일 복사 미사용). ADR-0013 호스트 `erasure.log` 포함(`ALERTS_ENABLED=true` 시 누락·디렉터리 fail-closed, M1 비활성 시 부재 허용). `restic backup` → `restic forget --keep-daily 14 --prune` → `restic snapshots` 요약 로그(`SNAPSHOT_RETENTION_SUMMARY`). `RESTIC_PASSWORD`·AWS 키·`HEALTHCHECKS_BACKUP_URL`(경로 토큰 포함) 고정 문자열 마스킹. 실패 시 성공 heartbeat 미전송 및 `/fail` POST. |
| `deploy/backup/backup.env.example` | `CLAUDE.md` 규칙에 따라 키 이름만 기재 (`RESTIC_REPOSITORY`, `RESTIC_PASSWORD`, `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_DEFAULT_REGION`, `HEALTHCHECKS_BACKUP_URL`, 선택 오버라이드). `DB_PASSWORD`·앱 비밀값과 분리. |
| `deploy/backup/systemd/gongchae-backup.service` | `User=ubuntu`, `Group=ubuntu`, `Type=oneshot`, `WorkingDirectory=/opt/gongchae-bell`, `PrivateTmp=yes`, `NoNewPrivileges=yes`. |
| `deploy/backup/systemd/gongchae-backup.timer` | `OnCalendar=*-*-* 03:00:00 Asia/Seoul`, `Persistent=true` (서버 UTC 기준 `18:00:00 UTC` 상당). |
| `deploy/tests/backup-test.sh` | 모의 `docker`/`restic`/`curl` + 실제 `flock` 기반 11개 단위/대역 시나리오 (`0644`/`0640`/symlink 거부, 정상 경로 및 `compose.env` 비밀값 미전달, `pg_dump`/TOC 실패 시 `/fail`, `erasure.log` M1/M2/빈 파일/디렉터리 경계, `restic backup`/`forget --prune`/heartbeat 실패, 특수문자 비밀값·Healthchecks UUID 마스킹, `flock` 동시 실행 `exit 20`, `init` 하위 명령). |
| `deploy/tests/backup-integration-test.sh` | 일회용 `postgres:17` 2대(원본·복원 대상) + 합성 `erasure.log` + 실제 `restic` 로컬 저장소로 `backup.sh init` → `backup.sh run` → `restic restore` → 별도 DB `pg_restore` → 행 수(5건)·`erasure.log` 바이트 일치까지 검증. |
| `.github/workflows/ci.yml` | ARM64 `backup-verify` 잡 추가 (`restic` 설치, `bash -n`, `backup-test.sh`, `backup-integration-test.sh`). |
| `docs/ops/backup-restore.md` | 백업 방식·대상, `keep-daily 14` 의미와 달력 14일 삭제 보장 제외 고지, OCI Always Free 공식 한도 표, 키 분리·마운트·systemd 설치 절차, 실패 경보, 격리 복구 및 해지 대장 재적용(`--alerts.erasure-reapply=true`) 절차, 미실시 복원 리허설 기록 표, `#17` 이관 범위 정리. |

---

## 2. 검증 결과 (`f0605c3b` 기준)

| 검증 항목 | 환경 | 결과 | 근거 |
|---|---|---|---|
| Bash 구문 검사 | Linux `ubuntu:24.04` / ARM64 CI | **통과 (`exit 0`)** | `bash -n deploy/backup/backup.sh deploy/tests/backup-test.sh deploy/tests/backup-integration-test.sh` |
| 백업 단위·대역 회귀 (`backup-test.sh`) | 로컬 Linux `ubuntu:24.04`(실제 `flock`) + ARM64 CI | **통과 (`ALL_BACKUP_UNIT_TESTS_PASSED`)** | 11개 시나리오 전체 통과 (`deploy/tests/backup-test.sh`) |
| 격리 백업·복원 통합 시험 (`backup-integration-test.sh`) | 로컬 Docker(`postgres:17` + `restic/restic:latest`) + ARM64 CI(네이티브 ARM64 `restic` + `postgres:17`) | **통과 (`BACKUP_INTEGRATION_TEST_PASSED`)** | `pg_dump -Fc` → `restic` 암호화 → `restic restore` → 별도 DB `pg_restore` 5행 복원 및 `erasure.log` 바이트 일치 |
| Gradle + Spring 전체 빌드/테스트 | 로컬 + ARM64 CI (`./gradlew --no-daemon check bootJar`) | **통과 (`BUILD SUCCESSFUL`)** | [Run #44227670423 / test](https://github.com/Lcjam/gongchae-bell/actions/runs/44227670423) |
| 비밀값 스캔 | 로컬 `gitleaks` + CI (`gitleaks-action@v3.0.0`) | **통과 (no leaks)** | [Run #44227670423 / secrets](https://github.com/Lcjam/gongchae-bell/actions/runs/44227670423) |

---

## 3. OCI Always Free 한도 출처 (외부 사실 교차 검증 대기)

- **확인 출처** (2026-11-18 확인, `CLAUDE.md` 외부 사실 규칙에 따라 다른 에이전트 출처 검증 후 결정 확정):
  - [Oracle Cloud Infrastructure · Always Free Resources](https://docs.oracle.com/en-us/iaas/Content/FreeTier/freetier_topic-Always_Free_Resources.htm)
    - **Always Free 전용 계정**: Standard · Infrequent Access · Archive 합산 총 **20 GB**
    - **Paid / Free Trial 활성 계정**: Standard **10 GB** + Infrequent Access **10 GB** + Archive **10 GB**
    - **API 요청**: 월 **50,000회** (Object Storage API requests/month)
  - [Oracle Cloud Infrastructure · Amazon S3 Compatibility API](https://docs.oracle.com/iaas/Content/Object/Tasks/s3compatibleapi.htm)
- **유료 기능 비활성화**: Standard 티어 버킷 1개만 사용하며 Object Versioning · Auto-Tiering · Replication · Retention Rules 등 유료·추가 기능을 켜는 설정을 넣지 않았다.

---

## 4. `#14` 머지 후 `docs/runbook.md` 링크 삽입 위치 안내

- `#14` PR #20의 `docs/runbook.md`에는 `## 7. 후속 연동 인터페이스 (#15 관측 · #16 백업)` 섹션이 이미 존재하므로, `#14`와 `#16`이 모두 `main`에 머지된 뒤 해당 섹션의 `#16 백업 (ADR-0012 / ADR-0013)` 항목 아래에 `[백업과 복구 절차](ops/backup-restore.md)` 링크를 추가하면 충돌 없이 연결된다.

---

## 5. 실서버 미검증 항목 (후속 운영 적용 및 `#17`)

1. OCI 실서버(`/opt/gongchae-bell/backup/backup.env`, `systemd` 타이머 설치) 및 실제 OCI Object Storage 버킷 `restic init` / `backup` / `forget --prune` snapshot 크기·데이터 시점·권한 실측.
2. 실제 Healthchecks.io ping / `/fail` 수신 확인.
3. `#17` 범위: 사용자 NAS·PC `restic copy` pull 스케줄·접근 제한·별도 heartbeat, 실운영 스키마·키가 포함된 격리 PostgreSQL 복원 및 `ERASURE_REAPPLY_COMPLETE` / fail-closed 리허설, 실측 기반 RTO/RPO 기록.
