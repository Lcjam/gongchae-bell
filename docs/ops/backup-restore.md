# 백업과 복구 · 공채벨 (#16)

> 근거: [PRD §6·§7](../prd.md), [아키텍처](../design/architecture.md), [ADR-0006](../adr/0006-observability.md), [ADR-0012](../adr/0012-backup-restic-pull.md), [ADR-0013](../adr/0013-erasure-journal-host-file.md), [ADR-0014](../adr/0014-initial-settings.md)
> 본 문서는 **#16(OCI 호스트 자동 백업 소스 준비)** 범위의 운영·복구 절차를 정리한다. 실제 OCI 서버 실행·OCI 밖 NAS/PC `restic copy` pull·격리 복원 리허설 검증은 **#17**에서 수행하며, 그 전까지 백업·복구 전체를 완료로 표시하지 않는다. 실측되지 않은 수치(RTO/RPO, 실제 snapshot 크기·소요 시간)는 설정값 또는 미측정으로만 적는다.

---

## 1. 백업 방식 및 대상

- **실행 주체·시각**: OCI 호스트 `ubuntu`(UID/GID `1000:1000`) systemd timer `gongchae-backup.timer`가 매일 `03:00 KST`(`OnCalendar=*-*-* 03:00:00 Asia/Seoul`, UTC 기준 `18:00:00 UTC`, `Persistent=true`)에 `gongchae-backup.service` → `deploy/backup/backup.sh run`을 실행한다.
- **동시 실행 방지**: `flock` 배타 잠금(`/opt/gongchae-bell/backup/backup.lock`)으로 중복 실행을 막는다(`LOCK_HELD`, exit `20`).
- **백업 대상 및 순서 불변식 (고정 스테이징 디렉터리 `${BACKUP_STAGE_ROOT}/gongchae-backup-stage`, `0700`, `flock` 배타 보호)**:
  1. `gongchae-bell.dump`: 실행 중인 운영 Compose `postgres` 컨테이너 내부에서 `pg_dump -Fc`로 추출한 논리 덤프. 생성 직후 비어 있지 않음과 `pg_restore --list` TOC 검증을 통과해야 한다. (**실행 중인 PostgreSQL 데이터 볼륨 `postgres_data` 파일 직접 복사는 사용하지 않는다.**)
  2. `erasure.log`: ADR-0013 호스트 해지 대장(`/var/lib/gongchae-bell/erasure/erasure.log`). **반드시 `pg_dump`가 끝난 뒤**에 복사해 스테이징한다(`stage_postgres_dump` → `stage_erasure_log`). 이 순서를 지켜야 백업 snapshot에 담긴 `erasure.log`가 적어도 해당 덤프 스냅샷 시점 이후(복사 직전)까지의 해지를 포함한다.
     - `REQUIRE_ERASURE_LOG=auto`(기본값)일 때, `/opt/gongchae-bell/.env`에 `ALERTS_ENABLED=true`(따옴표·대소문자 포함)가 설정되어 있으면 파일이 반드시 정규 파일로 존재해야 하며 없으면 실패 종료한다. M2(`ALERTS_ENABLED=true`) 전환 시 `backup.env`에 `REQUIRE_ERASURE_LOG=true`를 함께 명시하는 것을 권장한다.
     - **M1 전용(`ALERTS_ENABLED=false`) vs M2 이후 구분**: 처음부터 `ALERTS_ENABLED=false`로만 운영해 아직 대장 파일을 만들지 않은 M1 백업에서는 `ERASURE_LOG_ABSENT_M1_ONLY`와 `backup-meta.txt`의 `erasure_included=false`를 남기고 DB 덤프만 백업한다(빈 파일이라도 미리 만들어 두었으면 함께 백업). 반면 M2를 한 번이라도 켠 뒤(`erasure_included=true` 또는 `ALERTS_ENABLED=true`)에는 대장이 필수이며, missing·손상 대장을 빈 대장으로 대체하지 않는다.
     - 경로에 파일 대신 디렉터리나 심볼릭 링크가 있으면 즉시 실패한다.
  3. `backup-meta.txt`: 백업 UTC 시각, 덤프 바이트·SHA-256, TOC 항목 수, 해지 대장 포함 여부(`erasure_included=true|false`)·바이트·줄 수(비밀값·UUID 미포함).
- **암호화·전송**: `backup.sh`는 스테이징 디렉터리 안에서 상대 경로(`gongchae-bell.dump`, `backup-meta.txt`, 존재 시 `erasure.log`)로 `restic backup --tag gongchae-bell --tag daily`를 실행하므로, `restic restore` 대상 디렉터리 루트에 위 파일들이 그대로 풀린다.

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

## 7. 격리 복원 리허설 및 장애 복구 절차 (ADR-0012 · ADR-0013 · fail-closed)

> **핵심 원칙**:
> 1. **운영 DB 직접 덮어쓰기 금지**: 리허설과 전환 전 검증은 **절대 운영 Compose(`compose.prod.yml`)의 `postgres` 컨테이너나 `postgres_data` 볼륨을 대상으로 실행하지 않는다.** 고유한 컨테이너·볼륨·DB·계정과 외부 통신이 차단된 Docker `--internal` 네트워크를 쓰는 **전용 일회용 `postgres:17`**에서만 복원한다. (`--clean --if-exists`가 필요할 때도 격리 일회용 DB를 재사용할 때만 적용하며 운영 `postgres`에는 쓰지 않는다.)
> 2. **호스트 생존 시 살아 있는 최신 호스트 대장 사용**: DB 손상·실수 삭제·잘못된 마이그레이션 등 호스트가 살아 있는 사고나 리허설에서는 백업 시점 이후 해지까지 포함한 **살아 있는 호스트 대장(`/var/lib/gongchae-bell/erasure/erasure.log`)**을 재적용에 사용한다. 백업 안의 과거 `erasure.log`로 재적용하거나 살아 있는 호스트 대장을 백업 파일로 덮어쓰면 백업 이후 해지한 구독자가 부활하고 호스트 대장에서도 영구 유실된다. **VM·호스트 영구 소실(또는 호스트 대장 자체 소실) 시에만** 사용 가능한 백업(OCI 또는 NAS/PC 사본) 중 **가장 최신** 정상 사본을 택한다.
> 3. **운영 `.env` 통째 `source` 금지 및 외부 부작용 차단**: 복원·재적용 명령은 `/opt/gongchae-bell/.env`를 `source`하지 않는다(운영 `DB_*`·`ALERTS_ENABLED=true`·`COLLECTOR_ENABLED=true`·`HEARTBEAT_*`·`RESEND_API_KEY`가 격리 컨테이너에 섞이는 사고 방지). `DB_URL`·`DB_USER`·`DB_PASSWORD`는 격리 PG로 명시하고, `ALERTS_ENABLED=false`, `COLLECTOR_ENABLED=false`, `HEARTBEAT_COLLECTOR_URL=`(빈 값)으로 고정하며, 외부 망이 차단된 `--internal` 네트워크 안에서 실행한다.
> 4. **missing·손상 대장 대체 금지**: `ErasureReapplyCommand`는 파일 누락(`ERASURE_LOG_MISSING`)이나 손상(`ERASURE_LOG_TORN_LAST_LINE`, `ERASURE_LOG_BAD_LINE_<N>`) 시 0건 삭제 후 실패 종료한다. 원인 확인 전에 빈 파일로 대체해 통과시키지 않는다. 단, 처음부터 `ALERTS_ENABLED=false`였던 M1 전용 백업(`backup-meta.txt`의 `erasure_included=false`이며 M2를 켠 적이 없는 경우)은 재적용할 대장이 없을 수 있으므로 M2 이후 대장 필수 조건과 구분한다.

### 7.1 격리 복원 및 해지 대장 재적용 단계 (리허설·전환 전 검증 공통)

1. **사전 준비 및 격리 식별자 고정**:
   - 리허설 중에는 운영 앱·DB를 건드리지 않는다. 실제 장애 복구 시에는 먼저 운영 앱의 수집·발송을 멈춘 뒤 진행한다.
   - 비밀번호 관리자에서 `RESTIC_PASSWORD`와 OCI 자격증명을 확인한다. `backup.sh`와 별개로 restic 자격증명만 `/opt/gongchae-bell/backup/backup.env`에서 읽고, 운영 `/opt/gongchae-bell/.env`는 읽지 않는다.
   - 제한된 권한(`0700`)의 임시 작업 디렉터리와 고유 격리 자원 이름을 만든다:
     ```bash
     RESTORE_WORK="$(mktemp -d /tmp/gongchae-restore.XXXXXX)"
     chmod 0700 "$RESTORE_WORK"
     RESTORE_NET="gongchae-restore-net-$$"
     RESTORE_PG="gongchae-restore-pg-$$"
     RESTORE_VOL="gongchae-restore-vol-$$"
     RESTORE_DB="gongchae_restore"
     RESTORE_USER="gongchae_restore"
     RESTORE_PASS="$(openssl rand -hex 24)"
     ```
2. **snapshot 선택 및 추출 (`backup.sh` 상대 경로 아카이브 구조)**:
   - `backup.sh`는 스테이징 디렉터리 내부 기준 상대 경로로 백업하므로, `restic restore` 결과물(`gongchae-bell.dump`, `backup-meta.txt`, 포함 시 `erasure.log`)이 `"$RESTORE_WORK"` 바로 아래에 풀린다.
     ```bash
     set -a && source /opt/gongchae-bell/backup/backup.env && set +a
     restic snapshots --tag gongchae-bell --compact
     restic restore <SNAPSHOT_ID> --target "$RESTORE_WORK"
     ls -la "$RESTORE_WORK"
     ```
3. **덤프 무결성 및 TOC 확인**:
   ```bash
   cat "$RESTORE_WORK/backup-meta.txt"
   expected_sha="$(awk -F= '$1=="dump_sha256"{print $2}' "$RESTORE_WORK/backup-meta.txt")"
   actual_sha="$(sha256sum "$RESTORE_WORK/gongchae-bell.dump" | awk '{print $1}')"
   [[ -n "$expected_sha" && "$expected_sha" == "$actual_sha" ]] || { echo "DUMP_SHA_MISMATCH" >&2; exit 1; }
   docker run --rm --network none -i postgres:17 pg_restore --list < "$RESTORE_WORK/gongchae-bell.dump" | head -n 40
   ```
4. **전용 일회용 PostgreSQL 17 기동 및 덤프 복원 (운영 Compose `postgres` 금지)**:
   ```bash
   docker network create --internal "$RESTORE_NET"
   docker volume create "$RESTORE_VOL"
   docker run -d --name "$RESTORE_PG" \
     --network "$RESTORE_NET" \
     -v "$RESTORE_VOL:/var/lib/postgresql/data" \
     -e POSTGRES_DB="$RESTORE_DB" \
     -e POSTGRES_USER="$RESTORE_USER" \
     -e POSTGRES_PASSWORD="$RESTORE_PASS" \
     postgres:17
   until docker exec "$RESTORE_PG" pg_isready -U "$RESTORE_USER" -d "$RESTORE_DB"; do sleep 1; done

   # 새 격리 DB이므로 --clean 불필요 (동일 격리 DB 재실행 시에만 --clean --if-exists 허용, 운영 postgres 절대 금지)
   docker exec -i "$RESTORE_PG" \
     pg_restore -U "$RESTORE_USER" -d "$RESTORE_DB" --no-owner --exit-on-error < "$RESTORE_WORK/gongchae-bell.dump"
   docker exec "$RESTORE_PG" psql -U "$RESTORE_USER" -d "$RESTORE_DB" -c '\dt'
   ```
5. **해지 대장 출처 선택 및 격리 앱 재적용 (`ALERTS_ENABLED=false`, `COLLECTOR_ENABLED=false`)**:
   - **경우 A — 호스트 생존 (리허설 또는 운영 DB 손상·실수 삭제)**:
     - **살아 있는 호스트 대장** `/var/lib/gongchae-bell/erasure/erasure.log`를 사용한다. 백업에서 풀린 `$RESTORE_WORK/erasure.log`로 호스트 파일을 덮어쓰지 않는다.
     - `ErasureJournal.readStrict()`는 OS 잠금(`FileChannel.lock()`)을 위해 `READ, WRITE` 모드로 여므로 `:ro` 마운트로는 실패하며 UID `1000:1000` 쓰기 가능(`0600`) 파일이어야 한다. 리허설 중 운영 앱이 살아 있을 때는 호스트 파일 잠금 간섭을 피하기 위해 마지막 줄 개행을 확인한 뒤 `"$RESTORE_WORK/reapply-erasure.log"`(`0600`, `1000:1000`)로 복사해 검증하고, 앱을 중지한 실제 장애 복구에서는 호스트 대장 자체를 지정한다.
   - **경우 B — VM·호스트 영구 소실 (또는 호스트 대장 유실)**:
     - 살아 있는 호스트 대장이 없을 때만 OCI 또는 NAS/PC 사본 중 **가장 최신** 정상 snapshot에서 꺼낸 `erasure.log`를 사용한다(더 오래된 덤프 snapshot과 다른 최신 snapshot의 대장이 있으면 최신 사본 선택).
   - **M1 전용 백업 예외**: `backup-meta.txt`가 `erasure_included=false`이고 M2(`ALERTS_ENABLED=true`)를 한 번도 켠 적이 없는 M1 전용 백업이라면 `erasure.log`가 없을 수 있다. 반대로 M2가 켜진 적이 있거나 `erasure_included=true`인데 대장이 없다면 빈 파일로 대체하지 말고 중단한다.
   - **재적용 실행** (운영 `.env`를 `source`하지 않고 격리 DB·차단 env를 명시하며, `--network "$RESTORE_NET"` `--internal` 망으로 외부 provider·heartbeat 통신을 원천 차단한다; `APP_IMAGE`/`TARGET_TAG`는 덤프와 스키마가 호환되는 배포 이미지 태그 지정):
     ```bash
     cp /var/lib/gongchae-bell/erasure/erasure.log "$RESTORE_WORK/reapply-erasure.log"
     chown 1000:1000 "$RESTORE_WORK" "$RESTORE_WORK/reapply-erasure.log"
     chmod 0700 "$RESTORE_WORK"
     chmod 0600 "$RESTORE_WORK/reapply-erasure.log"

     docker run --rm --network "$RESTORE_NET" \
       -u 1000:1000 \
       -v "$RESTORE_WORK:/restore:rw" \
       -e DB_URL="jdbc:postgresql://${RESTORE_PG}:5432/${RESTORE_DB}" \
       -e DB_USER="$RESTORE_USER" \
       -e DB_PASSWORD="$RESTORE_PASS" \
       -e ALERTS_ENABLED=false \
       -e COLLECTOR_ENABLED=false \
       -e HEARTBEAT_COLLECTOR_URL= \
       -e ALERTS_ERASURE_LOG=/restore/reapply-erasure.log \
       "${APP_IMAGE}:${TARGET_TAG}" \
       --spring.main.web-application-type=none \
       --alerts.erasure-reapply=true
     ```
   - 출력 로그의 **`ERASURE_REAPPLY_COMPLETE deleted=N`**과 종료 코드 **`0`**을 확인한다. `ERASURE_LOG_MISSING`·`ERASURE_LOG_TORN_LAST_LINE`·`ERASURE_LOG_BAD_LINE_<N>`이 나오면 아무 행도 삭제하지 않은 채 실패하므로 원인을 해결하기 전까지 알림을 켜지 않는다.
6. **사후 삭제 확인 및 발송 재개(`ALERTS_ENABLED=true`) 전 후속 게이트 구분**:
   - **사후 삭제 검증**: 대장에 기록된 UUID가 격리 DB의 `subscription` 테이블에 0건 남아 있는지 직접 대조한다(0건이 아니면 발송을 재개하지 않는다).
   - **발송 재개 전 후속 게이트 (재적용과 별개 단계)**:
     1. 살아 있는 호스트 대장이 `/var/lib/gongchae-bell/erasure/erasure.log`(`1000:1000`, `0600`)로 보존돼 있는지 확인(VM 소실 복구 시에만 사용 가능한 가장 최신 백업 대장을 `/var/lib/gongchae-bell/erasure/erasure.log`로 배치).
     2. 웹훅·HMAC·토큰 키(`ALERTS_WEBHOOK_KEY_ID`·`ALERTS_WEBHOOK_KEY`·`ALERTS_HMAC_KEY`·`ALERTS_TOKEN_KEY`)가 복원된 DB 구독 행과 호환되는지 확인.
     3. 리허설이 아니라 실제 장애 복구라면 검증을 통과한 격리 DB로 운영 DB를 전환한 뒤에만 `COLLECTOR_ENABLED`/`ALERTS_ENABLED=true` 재개를 검토한다. (전환 세부 절차·자동 연속성/키 검증 도구는 `#17` 후속에서 확정한다.)
7. **격리 자원 및 임시 경로 정리**:
   - 리허설·검증이 끝나면 운영 `compose.prod.yml`의 `postgres`·`postgres_data`는 건드리지 않고, 1·4단계에서 만든 격리 자원만 삭제한다:
     ```bash
     docker rm -f "$RESTORE_PG"
     docker volume rm "$RESTORE_VOL"
     docker network rm "$RESTORE_NET"
     rm -rf "$RESTORE_WORK"
     ```
8. **한계 고지 (ADR-0013)**:
   - **호스트 생존 사고**: 살아 있는 호스트 대장을 사용하므로 백업 시점 이후의 해지도 유지된다.
   - **VM·호스트 영구 소실**: 마지막 성공 백업(OCI 저장소 기준 최대 약 24시간 + 실패 일수, OCI 밖 PC pull 사본 복원 시 마지막 성공 pull 이후 기간) 사이에 기록된 `erasure.log`는 백업에 없으므로, 그 사이 해지한 구독자에게 복원 후 다시 발송될 수 있다. (재발송 메일 안 해지 링크로 재해지할 수는 있으나 유실 자체를 없애지는 못한다.)

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
