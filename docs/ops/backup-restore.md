# 백업과 복구 · 공채벨 (#16)

> 근거: [PRD §6·§7](../prd.md), [아키텍처](../design/architecture.md), [ADR-0006](../adr/0006-observability.md), [ADR-0012](../adr/0012-backup-restic-pull.md), [ADR-0013](../adr/0013-erasure-journal-host-file.md), [ADR-0014](../adr/0014-initial-settings.md)
> 본 문서는 **#16(OCI 호스트 자동 백업 소스 준비)** 범위의 운영·복구 절차를 정리한다. 실제 OCI 서버 실행·OCI 밖 NAS/PC `restic copy` pull·격리 복원 리허설 검증은 **#17**에서 수행하며, 그 전까지 백업·복구 전체를 완료로 표시하지 않는다. 실측되지 않은 수치(RTO/RPO, 실제 snapshot 크기·소요 시간)는 설정값 또는 미측정으로만 적는다.

---

## 1. 백업 방식 및 대상

- **실행 주체·시각**: OCI 호스트 `ubuntu`(UID/GID `1000:1000`) systemd timer `gongchae-backup.timer`가 매일 `03:00 KST`(`OnCalendar=*-*-* 03:00:00 Asia/Seoul`, UTC 기준 `18:00:00 UTC`, `Persistent=true`)에 `gongchae-backup.service` → `deploy/backup/backup.sh run`을 실행한다.
- **동시 실행 방지**: `flock` 배타 잠금(`/opt/gongchae-bell/backup/backup.lock`)으로 중복 실행을 막는다(`LOCK_HELD`, exit `20`).
- **백업 대상 (고정 스테이징 디렉터리 `${BACKUP_STAGE_ROOT}/gongchae-backup-stage`, `0700`, `flock` 배타 보호)**:
  1. `gongchae-bell.dump`: 실행 중인 운영 Compose `postgres` 컨테이너 내부에서 `pg_dump -Fc`로 추출한 논리 덤프. 생성 직후 비어 있지 않음과 `pg_restore --list` TOC 검증을 통과해야 한다. (**실행 중인 PostgreSQL 데이터 볼륨 `postgres_data` 파일 직접 복사는 사용하지 않는다.**)
  2. `erasure.log`: ADR-0013 호스트 해지 대장(`/var/lib/gongchae-bell/erasure/erasure.log`).
     - `REQUIRE_ERASURE_LOG=auto`(기본값)일 때, `/opt/gongchae-bell/.env`에 `ALERTS_ENABLED=true`(따옴표·대소문자 포함)가 설정되어 있으면 파일이 반드시 정규 파일로 존재해야 하며 없으면 실패 종료한다. M2(`ALERTS_ENABLED=true`) 전환 시 `backup.env`에 `REQUIRE_ERASURE_LOG=true`를 함께 명시하는 것을 권장한다.
     - M1(`ALERTS_ENABLED=false`) 단계에서는 파일이 아직 없으면 `ERASURE_LOG_ABSENT_M1_ONLY`만 남기고 DB 덤프를 계속 백업하며, 빈 파일이라도 미리 생성되어 있으면 함께 백업한다.
     - 경로에 파일 대신 디렉터리가 있으면 즉시 실패한다.
  3. `backup-meta.txt`: 백업 UTC 시각, 덤프 바이트·SHA-256, TOC 항목 수, 해지 대장 포함 여부·바이트·줄 수(비밀값·UUID 미포함).
- **암호화·전송**: 고정 스테이징 경로에서 `restic backup --tag gongchae-bell --tag daily`로 클라이언트 측 암호화 후 OCI Object Storage S3 호환 버킷에 업로드한다.

---

## 2. 보관 주기 및 한계 (`--keep-daily 14 --prune`)

- 백업 성공 직후 `restic forget --tag gongchae-bell --group-by host,tags --keep-daily 14 --prune`을 실행한다(ADR-0012, ADR-0014 초기 설정).
- `--group-by host,tags`로 묶기 때문에 기존 무작위 임시 경로 snapshot을 포함한 동일 `(host, tags)`(`gongchae-bell,daily`) 논리 묶음 전체에 보관 개수가 일관되게 적용된다.
- 실행 뒤 `restic snapshots --tag gongchae-bell --compact`로 남은 snapshot 수, 가장 오래된 시점(`oldest_utc`), 최신 snapshot ID·시점(`latest_id`, `latest_utc`)을 로그(`SNAPSHOT_RETENTION_SUMMARY`)에 남긴다.
- **보관 한계**: `--keep-daily 14`는 **백업이 성공한 서로 다른 날짜 기준 최대 14개 일일 snapshot**을 남기는 규칙이다. 백업이 실패해 건너뛴 날이 있으면 더 과거 snapshot이 남을 수 있으므로, 이 설정만으로 "모든 사본이 달력상 14일 뒤 무조건 삭제된다"고 보장하지 않는다. 달력 기준 사본 파기·해지 대장·OCI 밖 사본 고지 정합성은 #17에서 함께 확정한다.

---

## 3. OCI Object Storage 무료 한도 및 유료 기능 방지

| 항목 | 공식 문서 한도 | 확인 출처 및 일자 | 운영 원칙 |
|---|---|---|---|
| **저장 용량** | **Always Free 전용 계정**: Standard · Infrequent Access · Archive 합산 **총 20 GB**<br>**Paid / Free Trial 활성 계정**: Standard 10 GB · Infrequent Access 10 GB · Archive 10 GB | [OCI Always Free Resources](https://docs.oracle.com/en-us/iaas/Content/FreeTier/freetier_topic-Always_Free_Resources.htm) (2026-11-18 확인, 다른 에이전트 출처 교차 검증 대기) | Standard 티어 전용 버킷 1개만 사용. `keep-daily 14 --prune`으로 보관량을 통제하고 실제 버킷 사용량을 운영 관측에서 확인 |
| **API 요청** | 월 **50,000회** (Object Storage API requests/month) | 위 동일 출처 (2026-11-18 확인) | 일 1회 `restic backup` + `forget --prune` + #17 `restic copy` 수준으로 무료 범위 내 유지 |
| **S3 호환 접속** | `s3:https://<namespace>.compat.objectstorage.<region>.oraclecloud.com/<bucket>` | [OCI S3 Compatibility API](https://docs.oracle.com/iaas/Content/Object/Tasks/s3compatibleapi.htm) | Customer Secret Key 사용. **Object Versioning · Auto-Tiering · Replication · Retention Rules 등 유료/추가 기능은 켜지 않는다** |

---

## 4. 키 보관 및 환경변수 분리 원칙

- **DB/앱 env와 백업 env 분리**:
  - `/opt/gongchae-bell/.env`: 앱·DB(`DB_USER`, `DB_PASSWORD` 등). `backup.sh`는 이 파일을 `source`하지 않으며, `docker compose --env-file`로만 참조하고 `pg_dump`는 `postgres` 컨테이너 내부의 `POSTGRES_USER`/`POSTGRES_DB`/`POSTGRES_PASSWORD`로 실행한다.
  - `/opt/gongchae-bell/backup/backup.env`: 백업 전용(`0600` 또는 `0400`, `ubuntu:ubuntu` 소유, 심볼릭 링크 금지). `backup.sh`가 시작 시 권한과 소유자를 검사(`ENV_PERM_FAIL`)해 group/other 권한이 열려 있으면 즉시 중단한다.
- **저장소 키 분리 보관 (ADR-0012)**:
  - `RESTIC_PASSWORD`와 `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY`는 DB·백업 저장소 안에 두지 않고 서버 `/opt/gongchae-bell/backup/backup.env`와 **사용자의 비밀번호 관리자**에 별도 보관한다. `RESTIC_PASSWORD`를 잃어버리면 어떤 백업 snapshot도 복호화할 수 없다.
- **로그 마스킹**: `backup.sh`는 `RESTIC_PASSWORD`, `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `HEALTHCHECKS_BACKUP_URL`(경로 토큰 포함), URL 내 userinfo를 `[REDACTED]`로 치환해 표준 출력·표준 오류와 실패 ping 본문에 남기지 않는다.
- **예시 파일**: 저장소의 `deploy/backup/backup.env.example`에는 키 이름만 두고 실제 값은 넣지 않는다.

| 키 (`backup.env`) | 필수 | 기본값 (`backup.sh`) | 설명 | 비밀 여부 |
|---|---|---|---|---|
| `RESTIC_REPOSITORY` | 필수 | 없음 | `s3:https://<ns>.compat.objectstorage.<region>.oraclecloud.com/<bucket>` | 아니오 |
| `RESTIC_PASSWORD` | 필수 | 없음 | restic 저장소 암호화 비밀번호 (분실 시 복구 불가) | **비밀** |
| `AWS_ACCESS_KEY_ID` | S3 시 필수 | 없음 | OCI Customer Secret Key Access Key | **비밀** |
| `AWS_SECRET_ACCESS_KEY` | S3 시 필수 | 없음 | OCI Customer Secret Key Secret | **비밀** |
| `AWS_DEFAULT_REGION` | S3 시 권장 | 빈 문자열 | OCI 리전 (예: `ap-tokyo-1`) | 아니오 |
| `HEALTHCHECKS_BACKUP_URL` | 선택(운영 권장) | 빈 문자열 | Healthchecks.io ping URL (실패 시 `/fail` 자동 호출) | **비밀(토큰 URL)** |
| `COMPOSE_FILE` | 선택 | `/opt/gongchae-bell/compose.prod.yml` | 운영 Compose 파일 경로 | 아니오 |
| `COMPOSE_ENV_FILE` | 선택 | `/opt/gongchae-bell/.env` | Compose env 경로(`ALERTS_ENABLED` 읽기·보간용) | 아니오 |
| `DEPLOY_STATE_DIR` | 선택 | `/opt/gongchae-bell/deploy/state` | `blue_tag`/`green_tag` 조회 디렉터리 | 아니오 |
| `ERASURE_LOG_PATH` | 선택 | `/var/lib/gongchae-bell/erasure/erasure.log` | ADR-0013 호스트 해지 대장 경로 | 아니오 |
| `REQUIRE_ERASURE_LOG` | 선택 | `auto` | `auto` / `true` / `false` (`auto`는 `ALERTS_ENABLED=true` 시 필수) | 아니오 |
| `BACKUP_KEEP_DAILY` | 선택 | `14` | `restic forget --keep-daily` 보관 개수 | 아니오 |

---

## 5. 설치 및 운영 절차 (systemd)

1. 호스트 디렉터리와 `ubuntu` 사용자의 Docker 실행 권한을 확인·준비한다(소유 `ubuntu:ubuntu`, `0750`):
   ```bash
   id -nG ubuntu | grep -qw docker   # 미포함 시 sudo usermod -aG docker ubuntu
   sudo mkdir -p /opt/gongchae-bell/backup
   sudo chown -R ubuntu:ubuntu /opt/gongchae-bell/backup
   sudo chmod 0750 /opt/gongchae-bell/backup
   ```
2. `deploy/backup/backup.env.example`을 `/opt/gongchae-bell/backup/backup.env`로 복사해 값을 채우고 권한을 잠근다:
   ```bash
   cp /opt/gongchae-bell/deploy/backup/backup.env.example /opt/gongchae-bell/backup/backup.env
   chmod 0600 /opt/gongchae-bell/backup/backup.env
   ```
3. 최초 1회 restic 저장소를 초기화한다(기존 저장소가 있으면 건너뛴다):
   ```bash
   /opt/gongchae-bell/deploy/backup/backup.sh init --env-file /opt/gongchae-bell/backup/backup.env
   ```
4. systemd 유닛을 설치하고 `03:00 Asia/Seoul` 일정과 타이머를 활성화한다:
   ```bash
   sudo cp /opt/gongchae-bell/deploy/backup/systemd/gongchae-backup.service /etc/systemd/system/
   sudo cp /opt/gongchae-bell/deploy/backup/systemd/gongchae-backup.timer /etc/systemd/system/
   sudo systemctl daemon-reload
   systemd-analyze calendar "*-*-* 03:00:00 Asia/Seoul"
   sudo systemctl enable --now gongchae-backup.timer
   systemctl list-timers gongchae-backup.timer
   ```
5. 수동 실행 및 로그 점검:
   ```bash
   sudo systemctl start gongchae-backup.service
   journalctl -u gongchae-backup.service -n 100 --no-pager
   /opt/gongchae-bell/deploy/backup/backup.sh snapshots --env-file /opt/gongchae-bell/backup/backup.env
   ```

---

## 6. 실패 경보 (Healthchecks.io, ADR-0006)

- `pg_dump`, `pg_restore --list`, `erasure.log` 검증, `restic backup`, `restic forget --prune` 중 어느 하나라도 실패하면 **성공 ping을 보내지 않고** `${HEALTHCHECKS_BACKUP_URL}/fail`로 실패 요약을 POST한 뒤 비정상 종료(`exit != 0`)한다.
- `backup.env` 권한 오류(`ENV_PERM_FAIL`), 누락된 필수 키, 도구(`docker`/`restic`/`curl`/`flock`) 누락 등 `backup.env` 적재 완료 전 실패는 `HEALTHCHECKS_BACKUP_URL`을 읽기 전에 중단되므로 `/fail`을 보내지 않으며, Healthchecks.io의 **일정 유예 초과(미수신) 경보**와 `systemd` 실패 상태(`journalctl -u gongchae-backup.service`)로 감지된다.
- 백업 자체가 성공했더라도 성공 ping 전송이 실패하면 비정상 종료해 systemd 장애 기록으로 남긴다.
- 설정된 주기(일 1회 + 유예 시간) 안에 성공 ping이 오지 않거나 `/fail`을 수신하면 Healthchecks.io가 운영 채널로 경보를 보낸다.

---

## 7. 복구 절차 (ADR-0012 · ADR-0013 · fail-closed)

> **주의**: 기존 운영 DB에 직접 덮어쓰지 않고 **발송·수집을 중지한 격리 환경**에서 검증한 뒤 전환한다.

1. **사전 준비**: 대상 호스트에서 앱 발송/수집(`COLLECTOR_ENABLED=false`, `ALERTS_ENABLED=false`)을 멈춘 상태를 확인한다. 비밀번호 관리자의 `RESTIC_PASSWORD`와 OCI 자격증명을 준비한다.
2. **snapshot 선택 및 추출**:
   ```bash
   mkdir -p /tmp/gongchae-restore && chmod 0700 /tmp/gongchae-restore
   set -a && source /opt/gongchae-bell/backup/backup.env && set +a
   restic snapshots --tag gongchae-bell --compact
   restic restore <SNAPSHOT_ID> --target /tmp/gongchae-restore
   ```
3. **덤프 및 메타 확인**: `/tmp/gongchae-restore/backup-meta.txt`의 `dump_sha256`과 `gongchae-bell.dump`의 `sha256sum`이 일치하는지 확인하고 `pg_restore --list`로 구조를 점검한다.
4. **격리 PostgreSQL 복원**:
   ```bash
   docker compose -f /opt/gongchae-bell/compose.prod.yml --env-file /opt/gongchae-bell/.env \
     exec -T postgres pg_restore -U "$DB_USER" -d "$DB_NAME" --clean --if-exists --no-owner --exit-on-error < /tmp/gongchae-restore/gongchae-bell.dump
   ```
5. **해지 대장 검증 및 재적용 (`ALERTS_ENABLED=false` 상태 유지, ADR-0013)**:
   - 백업에서 복원한 `erasure.log` 경로를 `ALERTS_ERASURE_LOG`로 지정해 재적용 명령을 실행한다(없거나 손상된 대장이면 fail-closed로 0건 삭제 후 비정상 종료한다):
     ```bash
     ALERTS_ENABLED=false ALERTS_ERASURE_LOG=/tmp/gongchae-restore/erasure.log \
       java -jar gongchae-bell.jar --spring.main.web-application-type=none --alerts.erasure-reapply=true
     ```
   - `ERASURE_REAPPLY_COMPLETE deleted=N` 및 `exit 0`을 확인하고, 복원된 대장을 호스트 `/var/lib/gongchae-bell/erasure/erasure.log`(`1000:1000`, `0600`)로 배치한 뒤에만 알림(`ALERTS_ENABLED=true`)을 재개한다.
6. **임시 복원 디렉터리 정리**: `rm -rf /tmp/gongchae-restore`.
7. **한계 고지 (ADR-0013)**: VM 전체가 영구 소실된 경우 마지막 일일 백업 이후(최대 약 24시간)에 발생한 `erasure.log` 기록은 백업에 남아 있지 않으므로, 그 사이 해지한 구독자에게 복원 후 다시 발송될 수 있다.

---

## 8. 복원 리허설 기록 및 #17 이관 범위

### 복원 리허설 기록 (실서버 미실시)

| 일시 (KST) | 대상 환경 | 복원한 snapshot ID / 데이터 시점 | 소요 시간 | 대장 재적용(`deleted=N`) | 결과 및 비고 |
|---|---|---|---|---|---|
| *미실시 (#17 예정)* | 격리 검증 DB (실서버 리허설 전) | *미실시* | *미측정* | *미실시* | 본 브랜치에서는 일회용 `postgres:17` + 로컬 `restic` 통합 시험(`backup-integration-test.sh`)으로 메커니즘만 검증 |

### #17로 넘기는 범위

- 사용자 NAS·PC에서 외부 포트 개방 없이 `restic copy` pull 스케줄 실행 및 사본 접근 제한·별도 Healthchecks.io heartbeat 검증 (pull 쪽 `forget` 시에도 `--group-by host,tags` 묶음 기준을 명시·일치시킨다)
- 실운영 스키마·키가 포함된 격리 PostgreSQL 복원 리허설, `ERASURE_REAPPLY_COMPLETE` 및 missing/손상 대장 fail-closed 검증, 실측 기반 RTO/RPO 설정
- `erasure.log` 정리 주기, 허용 복원 최소 시점, 연속 커버리지, 보관·prune 실패 경계 및 처리방침 사본 파기 고지 정합성 확정
