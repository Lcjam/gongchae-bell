# 백업과 복구 · 공채벨 (#16)

> 근거: [PRD §6·§7](../prd.md), [아키텍처](../design/architecture.md), [ADR-0006](../adr/0006-observability.md), [ADR-0012](../adr/0012-backup-restic-pull.md), [ADR-0013](../adr/0013-erasure-journal-host-file.md), [ADR-0014](../adr/0014-initial-settings.md), [ADR-0017](../adr/0017-backup-wall-clock-retention.md)
> 본 문서는 **#16(OCI 호스트 자동 백업 소스 준비)** 범위의 운영·복구 절차를 정리한다. 실제 OCI 서버 실행·OCI 밖 NAS/PC `restic copy` pull·격리 복원 리허설 검증은 **#17**에서 수행하며, 그 전까지 백업·복구 전체를 완료로 표시하지 않는다. 실측되지 않은 수치(RTO/RPO, 실제 snapshot 크기·소요 시간)는 설정값 또는 미측정으로만 적는다.

---

## 1. 백업 방식 및 대상

- **실행 주체·시각**: OCI 호스트 `ubuntu` 계정(운영 호스트에서는 예: UID `1001`, 앱 컨테이너 기본 UID/GID `1000:1000`과 다를 수 있음)의 systemd timer `gongchae-backup.timer`가 매일 `03:00 KST`(`OnCalendar=*-*-* 03:00:00 Asia/Seoul`, UTC 기준 `18:00:00 UTC`, `Persistent=true`)에 `gongchae-backup.service` → `deploy/backup/backup.sh run`을 실행한다.
- **동시 실행 방지**: `flock` 배타 잠금(`/opt/gongchae-bell/backup/backup.lock`)으로 중복 실행을 막는다(`LOCK_HELD`, exit `20`).
- **백업 대상 및 순서 불변식 (고정 스테이징 디렉터리 `${BACKUP_STAGE_ROOT}/gongchae-backup-stage`, `0700`, `flock` 배타 보호)**:
  - `backup.sh run`은 시작 시 현재 UTC epoch(`RUN_NOW_EPOCH`)를 1회 읽고, **덤프·업로드보다 먼저** restic 저장소 확인 → 실행 시각 기준 14일 초과 만료·`prune --max-unused 0`·재조회 0건 확인(§2, [ADR-0017](../adr/0017-backup-wall-clock-retention.md))을 끝낸 뒤 아래 스테이징과 업로드로 진행한다. 당일 덤프·업로드가 실패해도 만료 사본이 남지 않게 하기 위한 순서다.
  1. `gongchae-bell.dump`: 실행 중인 운영 Compose `postgres` 컨테이너 내부에서 `pg_dump -Fc`로 추출한 논리 덤프. 생성 직후 비어 있지 않음과 `pg_restore --list` TOC 검증을 통과해야 한다. (**실행 중인 PostgreSQL 데이터 볼륨 `postgres_data` 파일 직접 복사는 사용하지 않는다.**)
  2. `erasure.log`: ADR-0013 호스트 해지 대장(`/var/lib/gongchae-bell/erasure/erasure.log`). **반드시 `pg_dump`가 끝난 뒤**에 복사해 스테이징한다(`stage_postgres_dump` → `stage_erasure_log`). 이 순서를 지켜야 백업 snapshot에 담긴 `erasure.log`가 적어도 해당 덤프 스냅샷 시점 이후(복사 직전)까지의 해지를 포함한다.
     - `REQUIRE_ERASURE_LOG=auto`(기본값)일 때, `/opt/gongchae-bell/.env`에 `ALERTS_ENABLED=true`(따옴표·대소문자 포함)가 설정되어 있으면 파일이 반드시 정규 파일로 존재해야 하며 없으면 실패 종료한다. M2(`ALERTS_ENABLED=true`) 전환 시 `backup.env`에 `REQUIRE_ERASURE_LOG=true`를 함께 명시하는 것을 권장한다.
     - **M2 전환 전 선행 게이트 (백업 서비스 읽기 권한 불일치)**: 앱 컨테이너(`USER 1000:1000`)가 쓰는 `/var/lib/gongchae-bell/erasure`(`0750` `1000:1000`) 및 `erasure.log`(`0600` `1000:1000`)는 `User=ubuntu`(호스트 UID `1001`)로 도는 `gongchae-backup.service`가 그대로 읽지 못할 수 있다. 따라서 M2(`ALERTS_ENABLED=true`)를 켜기 전 선행 작업에서 백업 서비스의 대장 읽기 권한 또는 복사 방식을 별도로 확정·검증해야 한다(이 문서 수정에서는 기존 소스·ACL 정책을 변경하지 않는다).
     - **M1 전용(`ALERTS_ENABLED=false`) vs M2 이후 구분**: 처음부터 `ALERTS_ENABLED=false`로만 운영해 아직 대장 파일을 만들지 않은 M1 백업에서는 `ERASURE_LOG_ABSENT_M1_ONLY`와 `backup-meta.txt`의 `erasure_included=false`를 남기고 DB 덤프만 백업한다(빈 파일이라도 미리 만들어 두었으면 함께 백업). 반면 M2를 한 번이라도 켠 뒤(`erasure_included=true` 또는 `ALERTS_ENABLED=true`)에는 대장이 필수이며, missing·손상 대장을 빈 대장으로 대체하지 않는다.
     - 경로에 파일 대신 디렉터리나 심볼릭 링크가 있으면 즉시 실패한다.
  3. `backup-meta.txt`: 백업 UTC 시각, 덤프 바이트·SHA-256, TOC 항목 수, 해지 대장 포함 여부(`erasure_included=true|false`)·바이트·줄 수, 보관 정책 표기(`retention=wall-clock-14d`)(비밀값·UUID 미포함).
- **암호화·전송**: `backup.sh`는 스테이징 디렉터리 안에서 상대 경로(`gongchae-bell.dump`, `backup-meta.txt`, 존재 시 `erasure.log`)로 `restic backup --tag gongchae-bell --tag daily`를 실행하므로, `restic restore` 대상 디렉터리 루트에 위 파일들이 그대로 풀린다.

---

## 2. 보관 주기 및 한계 (실행 시각 기준 14일, ADR-0017)

- **정책 전환**: 사용자 결정(`collab/2026-11-19-recovery/04-user-retention-decision.md`, [ADR-0017](../adr/0017-backup-wall-clock-retention.md))에 따라 성공한 날짜 개수나 최신 snapshot을 기준으로 삼던 옛 restic 보관 옵션(`--keep-daily`, `--keep-within`)을 폐기하고, **실행 시점의 현재 UTC 시각 기준 14일 초과 사본 전부 삭제(복원 사본 0개 허용)** 정책으로 대체했다.
- **실행 순서 (`backup.sh run` → `ensure_restic_repo` → `apply_retention`, 덤프·업로드 전)**:
  1. 실행 시작 시 현재 UTC epoch(`RUN_NOW_EPOCH`)를 1회 읽고 `restic snapshots --tag gongchae-bell --json`을 `deploy/backup/expire-select.py`로 검사해 `실행 시각 - snapshot 시각 > 14일(1,209,600초)`인 대상의 **전체 64자리 hex ID**를 오래된 순으로 선정한다.
     - 14일 미만 및 정각 14일(`== 1,209,600초`)은 유지하고, 14일 초과(`> 1,209,600초`)만 삭제한다. 최신 1개·가장 오래된 1개·마지막 성공일 예외는 두지 않으므로 조건에 따라 `gongchae-bell` snapshot이 **0개 남는 것도 정상 결과**다.
     - `gongchae-bell` 태그가 없는 다른 백업 snapshot은 조회 대상에 포함하지 않으며 삭제하지 않는다.
     - 목록을 신뢰할 수 없으면(JSON/배열 아님, 64자리 hex 아닌 ID, 시간대 없는 시각, `gongchae-bell` 태그 불일치, 실행 시각보다 5분 초과 미래 시각) 아무것도 삭제하지 않고 즉시 실패한다(`RETENTION_SELECT_FAIL`).
  2. 선정된 ID가 있으면 `restic forget <ID...>`로 명시 삭제한다(`RESTIC_FORGET_FAIL` 시 중단).
  3. **매 실행마다** `restic prune --max-unused 0`을 실행한다(`RESTIC_PRUNE_FAIL` 시 중단). restic 기본값(미사용 pack 5% 허용) 대신 `--max-unused 0`으로 삭제된 snapshot의 pack을 즉시 재패킹해 물리 정리하며, 이전 실행의 prune 실패로 남아 있던 미사용 데이터도 다음 실행에서 회수한다.
  4. 같은 `RUN_NOW_EPOCH` 기준으로 다시 목록을 받아 만료 대상이 `0`건인지 검증(`RETENTION_VERIFY_FAIL`)한 뒤, `SNAPSHOT_RETENTION_SUMMARY policy=wall-clock-14d forgotten=<N> total=<T> expire=0 keep=<K> oldest_kept_utc=<...> newest_kept_utc=<...> now_utc=<...> window_seconds=1209600`를 로그와 성공 heartbeat에 남긴다.
  5. 만료·prune·재검증이 모두 통과한 뒤에만 `pg_dump`·`erasure.log` 스테이징·`restic backup`으로 넘어간다. 따라서 당일 덤프·업로드가 실패해도 만료 사본은 이미 지워져 있으며, 반대로 만료 단계가 실패하면 덤프를 수행하지 않고 `/fail`로 중단한다.
- **실제 물리 잔류와 한계 (최대 14일 고정 삭제를 약속하지 않음)**:
  - **실행 간격에 따른 잔류**: 14일 직전 시점의 사본은 다음 실행까지 남으므로 정상 운영(일 1회 `03:00 KST`)에서도 실제 최대 잔류 기간은 **14일 + 실행 간격(약 15일)**이다.
  - **snapshot 목록 제거(`forget`)와 pack 물리 삭제(`prune`)의 구별 및 장애 시 잔류**: `RESTIC_REPO_UNAVAILABLE`(자격·네트워크 오류), 잠금 충돌, `RESTIC_FORGET_FAIL`, `RESTIC_PRUNE_FAIL`이 발생하면 snapshot 목록 제거 또는 pack 물리 삭제가 지연될 수 있다. 이 상태를 보유기간 준수·삭제 완료로 간주하지 않으며, `/fail`·미수신 경보로 감지하고 원인 복구 뒤 다음 실행에서 정리한다.
  - **OCI 밖 PC/NAS 오프라인 한계**: PC/NAS가 꺼져 있는 동안에는 로컬 정리가 실행되지 않으므로 오프라인 기간만큼 물리 사본이 더 잔류할 수 있다. Windows PC 구현체(`deploy/backup/pc/pc-pull.ps1`, 상세 설치·운영 절차는 [PC pull 운영 절차](pc-pull-backup.md))는 **네트워크 작업(`restic copy`) 전에** 로컬 저장소에서 `expire-select.py` + 명시 ID `forget` + `prune --max-unused 0` + 재조회 검증을 먼저 수행해 OCI 연결/copy 실패일에도 로컬 만료가 건너뛰어지지 않게 하고, `restic copy --no-lock`(원본 snapshot 시각 보존, `expire-select.py --mode kept`로 14일 이내 ID만 선택) 뒤에도 만료 재검사·정리를 수행해 14일 초과 사본이 재보관되지 않도록 한다. 실패 시 보유기간 준수로 세지 않는다.
  - **장기 실패 시 복원 사본 0개**: 백업이 14일을 넘겨 계속 실패하면 `gongchae-bell` 복원 사본이 0개가 될 수 있다(사용자 수용 결정).

---

## 3. OCI Object Storage 무료 한도 및 유료 기능 방지

| 항목 | 공식 문서 한도 | 확인 출처 및 일자 | 운영 원칙 |
|---|---|---|---|
| **저장 용량** | **Always Free 전용 계정**: Standard · Infrequent Access · Archive 합산 **총 20 GB**<br>**Paid / Free Trial 활성 계정**: Standard 10 GB · Infrequent Access 10 GB · Archive 10 GB | [OCI Always Free Resources](https://docs.oracle.com/en-us/iaas/Content/FreeTier/freetier_topic-Always_Free_Resources.htm) (2026-11-18 확인, 다른 에이전트 출처 교차 검증 대기) | Standard 티어 전용 버킷 1개만 사용. 실행 시각 기준 14일 만료 + `prune --max-unused 0`(ADR-0017)으로 보관량을 통제하고 실제 버킷 사용량을 운영 관측에서 확인 |
| **API 요청** | 월 **50,000회** (Object Storage API requests/month) | 위 동일 출처 (2026-11-18 확인) | 일 1회 `restic snapshots` + 명시 ID `forget` + `prune --max-unused 0` + `backup` + #17 `restic copy`. 매일 prune에 따른 실제 요청 수는 운영 적용 후 실측으로 확인 |
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

- **제거된 키 (`BACKUP_KEEP_DAILY`) 및 마이그레이션 (ADR-0017)**: 보관 기간은 실행 시각 기준 고정 14일(`14 * 86400`초)이며 조정 가능한 개수 옵션이 아니다. `deploy/backup/backup.env.example`에서 `BACKUP_KEEP_DAILY`를 제거했고, 기존 `backup.env`에 비어 있지 않은 `BACKUP_KEEP_DAILY` 값이 남아 있으면 옛 정책이 동작하는 것처럼 오인되는 것을 막기 위해 `backup.sh`가 시작 시 즉시 거부한다(`BACKUP_KEEP_DAILY is no longer supported`). 기존 호스트를 갱신할 때는 `/opt/gongchae-bell/backup/backup.env`에서 `BACKUP_KEEP_DAILY` 줄을 삭제한다.

---

## 5. 설치 및 운영 절차 (systemd)

1. 호스트 필수 도구(`docker`, `restic`, `curl`, `python3`, `flock`, `awk`, `stat`, `mktemp`, `sha256sum`; `python3`은 Ubuntu 24.04 기본 포함이며 `deploy/backup/expire-select.py` 실행에 사용)와 `ubuntu` 사용자의 Docker 실행 권한을 확인·준비한다(소유 `ubuntu:ubuntu`, `0750`):
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

- `ensure_restic_repo`(`RESTIC_REPO_UNAVAILABLE`), 만료 조회·선정·삭제·물리 정리·재검증(`RESTIC_SNAPSHOTS_FAIL`, `RETENTION_SELECT_FAIL`, `RESTIC_FORGET_FAIL`, `RESTIC_PRUNE_FAIL`, `RETENTION_VERIFY_FAIL`), `pg_dump`, `pg_restore --list`, `erasure.log` 검증, `restic backup` 중 어느 하나라도 실패하면 **성공 ping을 보내지 않고** `${HEALTHCHECKS_BACKUP_URL}/fail`로 실패 요약을 POST한 뒤 비정상 종료(`exit != 0`)한다. 만료 단계가 실패하면 덤프·업로드로 넘어가지 않고 즉시 `/fail`로 중단한다.
- `backup.env` 권한 오류(`ENV_PERM_FAIL`), 누락된 필수 키, 제거된 `BACKUP_KEEP_DAILY` 잔존, 도구(`docker`/`restic`/`curl`/`python3`/`flock`) 누락 등 `cmd_run` 진입(`backup.env` 적재·검증 완료) 전 실패는 `HEALTHCHECKS_BACKUP_URL` 실행 경로 전 중단이므로 `/fail`을 보내지 않으며, Healthchecks.io의 **일정 유예 초과(미수신) 경보**와 `systemd` 실패 상태(`journalctl -u gongchae-backup.service`)로 감지된다.
- 백업 자체가 성공했더라도 성공 ping 전송이 실패하면 비정상 종료해 systemd 장애 기록으로 남긴다.
- 설정된 주기(일 1회 + 유예 시간) 안에 성공 ping이 오지 않거나 `/fail`을 수신하면 Healthchecks.io가 운영 채널로 경보를 보낸다.

---

## 7. 격리 복원 리허설 및 장애 복구 절차 (ADR-0012 · ADR-0013 · fail-closed)

> **핵심 원칙**:
> 1. **운영 DB 직접 덮어쓰기 금지**: 리허설과 전환 전 검증은 **절대 운영 Compose(`compose.prod.yml`)의 `postgres` 컨테이너나 `postgres_data` 볼륨을 대상으로 실행하지 않는다.** 고유한 컨테이너·볼륨·DB·계정과 외부 통신이 차단된 Docker `--internal` 네트워크를 쓰는 **전용 일회용 `postgres:17`**에서만 복원한다. (`--clean --if-exists`가 필요할 때도 격리 일회용 DB를 재사용할 때만 적용하며 운영 `postgres`에는 쓰지 않는다.)
> 2. **호스트 생존 시 살아 있는 최신 호스트 대장 사용**: DB 손상·실수 삭제·잘못된 마이그레이션 등 호스트가 살아 있는 사고나 리허설에서는 백업 시점 이후 해지까지 포함한 **살아 있는 호스트 대장(`/var/lib/gongchae-bell/erasure/erasure.log`)**을 재적용에 사용한다. 백업 안의 과거 `erasure.log`로 재적용하거나 살아 있는 호스트 대장을 백업 파일로 덮어쓰면 백업 이후 해지한 구독자가 부활하고 호스트 대장에서도 영구 유실된다. **VM·호스트 영구 소실(또는 호스트 대장 자체 소실) 시에만** 사용 가능한 백업(OCI 또는 NAS/PC 사본) 중 **가장 최신** 정상 사본을 택한다.
> 3. **운영 `.env` 통째 `source` 금지 및 외부 부작용 차단**: 복원·재적용 명령은 `/opt/gongchae-bell/.env`를 `source`하지 않는다(운영 `DB_*`·`ALERTS_ENABLED=true`·`COLLECTOR_ENABLED=true`·`HEARTBEAT_*`·`RESEND_API_KEY`가 격리 컨테이너에 섞이는 사고 방지). `DB_URL`·`DB_USER`·`DB_PASSWORD`는 격리 PG로 명시하고, `ALERTS_ENABLED=false`, `COLLECTOR_ENABLED=false`, `HEARTBEAT_COLLECTOR_URL=`(빈 값)으로 고정하며, 외부 망이 차단된 `--internal` 네트워크 안에서 실행한다.
> 4. **실행 계정(`ubuntu` UID `1001`)과 앱 컨테이너(`1000:1000`) 권한 분리**: 복원·Docker·restic 명령은 호스트 `ubuntu` 계정(`docker` 그룹)으로 실행하고, **호스트 대장 읽기, 재적용 전용 하위 디렉터리(`$RESTORE_WORK/reapply`, `1000:1000`) 생성, 새 호스트 대장 배치, 최종 `$RESTORE_WORK` 삭제(`sudo rm -rf`)에만 `sudo`를 쓴다.** `$RESTORE_WORK` 전체를 `1000:1000`으로 바꾸지 말고 **해지 대장 1개만 담은 `$RESTORE_WORK/reapply`만 컨테이너에 마운트**해 덤프·메타파일(PII 포함)이 앱 컨테이너에 노출되거나 `/tmp`에 남지 않게 한다.
> 5. **missing·손상 대장 대체 금지**: `ErasureReapplyCommand`는 파일 누락(`ERASURE_LOG_MISSING`)이나 손상(`ERASURE_LOG_TORN_LAST_LINE`, `ERASURE_LOG_BAD_LINE_<N>`) 시 0건 삭제 후 실패 종료한다. 원인 확인 전에 빈 파일로 대체해 통과시키지 않는다. 단, 처음부터 `ALERTS_ENABLED=false`로만 운영해 대장 파일이 아직 없던 M1 전용 백업(`subscription` 행 수 `0` **AND** `backup-meta.txt`의 `erasure_included=false` **AND** M2 미활성 이력 확인)은 M2 이후 대장 필수 조건과 구분한다.

### 7.1 격리 복원 및 해지 대장 재적용 단계 (리허설·전환 전 검증 공통)

1. **사전 준비·이미지 태그 확인·격리 식별자 고정**:
   - **리허설 vs 실제 장애 복구 구분**: 리허설 중에는 운영 앱·DB를 건드리지 않는다. 실제 장애 복구에서도 1~5단계는 격리 DB에서 먼저 검증하되, **운영 전환 직전(6.2단계)에는 웹 해지 엔드포인트(`/subscriptions/unsubscribe*`)를 포함한 모든 운영 앱(`app-blue`, `app-green`)을 반드시 중지**해야 한다(`ALERTS_ENABLED=false`, `COLLECTOR_ENABLED=false`만으로 웹 해지 쓰기는 멈추지 않는다).
   - 비밀번호 관리자에서 `RESTIC_PASSWORD`와 OCI 자격증명을 확인한다. `backup.sh`와 별개로 restic 자격증명만 `/opt/gongchae-bell/backup/backup.env`에서 읽고, 운영 `/opt/gongchae-bell/.env`는 통째로 `source`하지 않는다.
   - 배포 이미지와 태그는 `/opt/gongchae-bell/.env`에서 `APP_IMAGE` 한 줄만 추출하고 배포 상태 파일에서 읽는다(VM 소실 등으로 호스트 파일이 없을 때는 덤프와 스키마가 호환되는 릴리스 이미지·태그를 직접 지정한다):
     ```bash
     APP_IMAGE="$(grep '^APP_IMAGE=' /opt/gongchae-bell/.env | cut -d= -f2- | tr -d "\"'")"
     ACTIVE_COLOR="$(cat /opt/gongchae-bell/deploy/state/active_color 2>/dev/null || true)"
     TARGET_TAG="$(cat "/opt/gongchae-bell/deploy/state/${ACTIVE_COLOR}_tag" 2>/dev/null || true)"
     echo "IMAGE=${APP_IMAGE}:${TARGET_TAG}"
     ```
   - `ubuntu` 소유(`0700`)의 임시 작업 디렉터리와 고유 격리 자원 이름을 만든다:
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
   - VM 소실(경우 B)에서 복원하려는 덤프 snapshot(`<DUMP_SNAPSHOT_ID>`)보다 **더 최신 정상 snapshot에 최신 `erasure.log`가 있다면** 5단계에서 별도 하위 디렉터리(`$RESTORE_WORK/latest-journal`)에 `erasure.log`만 꺼내므로 `"$RESTORE_WORK"`의 덤프·메타파일과 섞이지 않는다.
     ```bash
     set -a && source /opt/gongchae-bell/backup/backup.env && set +a
     restic snapshots --tag gongchae-bell --compact
     restic restore <DUMP_SNAPSHOT_ID> --target "$RESTORE_WORK"
     ls -la "$RESTORE_WORK"
     ```
3. **덤프 무결성 및 TOC 확인**:
   ```bash
   cat "$RESTORE_WORK/backup-meta.txt"
   expected_sha="$(awk -F= '$1=="dump_sha256"{print $2}' "$RESTORE_WORK/backup-meta.txt")"
   actual_sha="$(sha256sum "$RESTORE_WORK/gongchae-bell.dump" | awk '{print $1}')"
   [[ -n "$expected_sha" && "$expected_sha" == "$actual_sha" ]] || { echo "DUMP_SHA_MISMATCH" >&2; return 1 2>/dev/null || exit 1; }
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
5. **사고 유형·단계 판별 후 해지 대장 출처 선택 및 격리 앱 재적용 (`ALERTS_ENABLED=false`, `COLLECTOR_ENABLED=false`)**:
   - **먼저 격리 DB의 `subscription` 행 수와 `backup-meta.txt`의 `erasure_included`를 확인한다**:
     ```bash
     SUB_COUNT="$(docker exec "$RESTORE_PG" psql -U "$RESTORE_USER" -d "$RESTORE_DB" -tAc 'SELECT count(*) FROM subscription;')"
     ERASURE_INCLUDED="$(awk -F= '$1=="erasure_included"{print $2}' "$RESTORE_WORK/backup-meta.txt")"
     echo "SUB_COUNT=$SUB_COUNT ERASURE_INCLUDED=${ERASURE_INCLUDED:-unknown}"
     ```
   - **분기 M1 — M1 전용 백업 건너뛰기 (엄격 조건)**:
     - **세 조건을 모두 만족할 때만** 5·6단계 해지 대장 재적용을 건너뛴다: ① 복원된 격리 DB `SUB_COUNT == 0`, ② `ERASURE_INCLUDED == false`, ③ M2(`ALERTS_ENABLED=true`)를 한 번도 켠 적이 없는 M1 전용 운영 이력 확인(실제 호스트 `/var/lib/gongchae-bell/erasure/erasure.log`도 없음).
     - `SUB_COUNT > 0`이거나 `ERASURE_INCLUDED=true`이거나 M1 전용 여부를 확신할 수 없으면 대장 누락 시 **절대 빈 파일로 대체하지 말고 중단**한다. 또한 대장 파일이 실제로 존재한다면 `SUB_COUNT == 0`이더라도 손상(`ERASURE_LOG_TORN_LAST_LINE`, `ERASURE_LOG_BAD_LINE_<N>`) 여부를 숨기고 건너뛰지 않는다.
   - **분기 A — 호스트 생존 (리허설 또는 운영 DB 손상·실수 삭제)**:
     - **살아 있는 호스트 대장** `/var/lib/gongchae-bell/erasure/erasure.log`를 선택한다. 백업에서 풀린 `"$RESTORE_WORK/erasure.log"`로 재적용하거나 호스트 파일을 덮어쓰지 않는다.
     - 호스트 대장은 `1000:1000` `0600`이므로 `ubuntu`(UID `1001`)는 `sudo`로 확인·복사한다. 리허설 중 운영 앱이 살아 있을 때는 호스트 `FileChannel.lock()` 간섭을 피하기 위해 마지막 줄 개행을 확인한 뒤 아래 공통 재적용 명령의 사본으로 검증한다:
       ```bash
       JOURNAL_SRC="/var/lib/gongchae-bell/erasure/erasure.log"
       sudo test -f "$JOURNAL_SRC" || { echo "HOST_JOURNAL_MISSING" >&2; return 1 2>/dev/null || exit 1; }
       # 비어 있지 않다면 마지막 줄이 '\n'으로 끝나는지 확인 (개행 수가 0이면 기록 중이거나 잘린 줄일 수 있음)
       sudo tail -c 1 "$JOURNAL_SRC" | wc -l
       ```
   - **분기 B — VM·호스트 영구 소실 (또는 호스트 대장 유실)**:
     - 살아 있는 호스트 대장이 없을 때만 OCI 또는 NAS/PC 사본 중 **대장이 포함된 가장 최신 정상 snapshot** `<LATEST_JOURNAL_SNAPSHOT_ID>`를 골라 **덤프 디렉터리(`$RESTORE_WORK`)와 분리된 `"$RESTORE_WORK/latest-journal"`에 `erasure.log`만 추출**한다(덤프 snapshot 자체와 같으면 그 snapshot ID를, 더 최신 snapshot이 있으면 반드시 최신 snapshot ID를 지정).
       ```bash
       mkdir -m 0700 "$RESTORE_WORK/latest-journal"
       restic restore <LATEST_JOURNAL_SNAPSHOT_ID> --target "$RESTORE_WORK/latest-journal" --include /erasure.log
       JOURNAL_SRC="$RESTORE_WORK/latest-journal/erasure.log"
       test -f "$JOURNAL_SRC" || { echo "LATEST_JOURNAL_MISSING" >&2; return 1 2>/dev/null || exit 1; }
       ls -la "$JOURNAL_SRC"
       ```
     - 새 호스트로 실제 복구하는 경우, 영구 호스트 경로에도 `1000:1000`·`0600`으로 대장을 배치한다:
       ```bash
       sudo install -d -o 1000 -g 1000 -m 0750 /var/lib/gongchae-bell/erasure
       sudo install -o 1000 -g 1000 -m 0600 "$JOURNAL_SRC" /var/lib/gongchae-bell/erasure/erasure.log
       ```
   - **공통 격리 재적용 실행 (`reapply` 전용 하위 디렉터리만 마운트)**:
     - `ErasureJournal.readStrict()`가 `FileChannel.lock()`을 위해 `READ, WRITE` + UID `1000:1000`(`0600`)을 요구하므로, `"$RESTORE_WORK/reapply"` 하위 디렉터리만 `1000:1000`으로 만들어 **대장 파일 1개만** 컨테이너 `/restore`에 `:rw` 마운트한다(`$RESTORE_WORK`의 `gongchae-bell.dump`와 `backup-meta.txt`는 `ubuntu` 소유 그대로 유지하며 컨테이너에 마운트하지 않는다).
       ```bash
       sudo install -d -o 1000 -g 1000 -m 0700 "$RESTORE_WORK/reapply"
       sudo install -o 1000 -g 1000 -m 0600 "$JOURNAL_SRC" "$RESTORE_WORK/reapply/erasure.log"

       docker run --rm --network "$RESTORE_NET" \
         -u 1000:1000 \
         -v "$RESTORE_WORK/reapply:/restore:rw" \
         -e DB_URL="jdbc:postgresql://${RESTORE_PG}:5432/${RESTORE_DB}" \
         -e DB_USER="$RESTORE_USER" \
         -e DB_PASSWORD="$RESTORE_PASS" \
         -e ALERTS_ENABLED=false \
         -e COLLECTOR_ENABLED=false \
         -e HEARTBEAT_COLLECTOR_URL= \
         -e ALERTS_ERASURE_LOG=/restore/erasure.log \
         "${APP_IMAGE}:${TARGET_TAG}" \
         --spring.main.web-application-type=none \
         --alerts.erasure-reapply=true
       ```
     - 출력 로그의 **`ERASURE_REAPPLY_COMPLETE deleted=N`**과 종료 코드 **`0`**을 확인한다. `ERASURE_LOG_MISSING`·`ERASURE_LOG_TORN_LAST_LINE`·`ERASURE_LOG_BAD_LINE_<N>`이 나오면 아무 행도 삭제하지 않은 채 실패하므로 원인을 해결하기 전까지 진행·발송을 켜지 않는다.
6. **사후 0건 검증, 실제 전환 직전 최종 대장 재적용, 발송 재개(`ALERTS_ENABLED=true`) 게이트**:
   - **6.1 사후 삭제 확인 (리허설·전환 공통)**:
     - 선택했던 대장 `"$JOURNAL_SRC"`의 첫 열 UUID를 격리 DB의 `subscription` 테이블과 대조해 잔여 행이 **`0`건**인지 확인한다(`0`건이 아니면 중단):
       ```bash
       REMAINING_UNSUBS="$(
         sudo awk -F'\t' 'NF>=1 && $1!="" {print $1}' "$JOURNAL_SRC" | \
         docker exec -i "$RESTORE_PG" psql -U "$RESTORE_USER" -d "$RESTORE_DB" \
           -v ON_ERROR_STOP=1 -qtA \
           -c 'CREATE TEMP TABLE tmp_erased(id text);' \
           -c 'COPY tmp_erased FROM STDIN;' \
           -c 'SELECT count(*) FROM subscription s JOIN tmp_erased e ON s.id::text = e.id;'
       )"
       echo "REMAINING_UNSUBS=$REMAINING_UNSUBS"
       [[ "$REMAINING_UNSUBS" == "0" ]] || { echo "REMAINING_UNSUBS_NONZERO" >&2; return 1 2>/dev/null || exit 1; }
       ```
   - **6.2 실제 장애 복구 전환 직전 최종 대장 재적용 (실서버 복원 시 필수 — 리허설 제외)**:
     - 리허설·1차 검증을 마친 뒤에도 운영 웹 앱이 떠 있는 동안 들어온 추가 해지가 `/var/lib/gongchae-bell/erasure/erasure.log`에 쌓였을 수 있다. `ALERTS_ENABLED=false`·`COLLECTOR_ENABLED=false`만으로는 웹 해지(`/subscriptions/unsubscribe*`)가 멈추지 않는다.
     - 실제 운영 DB 전환 직전에는 **웹을 포함한 모든 운영 앱 컨테이너(`app-blue`, `app-green` 모두)를 먼저 중지**해 새 해지 쓰기를 완전히 멈춘 뒤, 호스트 대장 디렉터리를 직접 마운트해 멱등(`idempotent`) 재적용을 1회 더 실행하고 잔여 `0`건을 재확인한다(**이때도 백업 파일로 호스트 대장을 덮어쓰지 않는다**):
       ```bash
       # 운영 app-blue, app-green 모두 중지 완료 후 실행 (호스트 대장 직접 마운트)
       docker run --rm --network "$RESTORE_NET" \
         -u 1000:1000 \
         -v /var/lib/gongchae-bell/erasure:/erasure:rw \
         -e DB_URL="jdbc:postgresql://${RESTORE_PG}:5432/${RESTORE_DB}" \
         -e DB_USER="$RESTORE_USER" \
         -e DB_PASSWORD="$RESTORE_PASS" \
         -e ALERTS_ENABLED=false \
         -e COLLECTOR_ENABLED=false \
         -e HEARTBEAT_COLLECTOR_URL= \
         -e ALERTS_ERASURE_LOG=/erasure/erasure.log \
         "${APP_IMAGE}:${TARGET_TAG}" \
         --spring.main.web-application-type=none \
         --alerts.erasure-reapply=true

       JOURNAL_SRC="/var/lib/gongchae-bell/erasure/erasure.log"
       # 이어서 6.1의 REMAINING_UNSUBS=0 대조 명령을 /var/lib/gongchae-bell/erasure/erasure.log 기준으로 재실행
       ```
   - **6.3 발송 재개(`ALERTS_ENABLED=true`) 전 후속 게이트 (재적용과 별개 단계)**:
     1. 살아 있는 호스트 대장이 `/var/lib/gongchae-bell/erasure/erasure.log`(`1000:1000`, `0600`)로 보존·배치돼 있는지 확인한다.
     2. 웹훅·HMAC·토큰 키(`ALERTS_WEBHOOK_KEY_ID`·`ALERTS_WEBHOOK_KEY`·`ALERTS_HMAC_KEY`·`ALERTS_TOKEN_KEY`)가 복원된 DB 구독 행과 호환되는지 확인한다.
     3. 실제 장애 복구라면 6.2 최종 재적용·0건 검증을 통과한 격리 DB로 운영 DB를 전환한 뒤에만 `COLLECTOR_ENABLED`/`ALERTS_ENABLED=true` 재개를 검토한다. (전환 세부 절차·자동 연속성/키 검증 도구는 `#17` 후속에서 확정한다.)
7. **격리 자원 및 임시 경로 정리**:
   - 리허설·검증이 끝나면 운영 `compose.prod.yml`의 `postgres`·`postgres_data`는 건드리지 않고, 1·4단계에서 만든 격리 자원만 삭제한다. `$RESTORE_WORK/reapply`가 `1000:1000` 소유이므로 `ubuntu`에서 일반 `rm -rf` 대신 **`sudo rm -rf "$RESTORE_WORK"`**를 써야 개인정보가 포함된 덤프·대장 사본이 `/tmp`에 남지 않는다:
     ```bash
     docker rm -f "$RESTORE_PG"
     docker volume rm "$RESTORE_VOL"
     docker network rm "$RESTORE_NET"
     sudo rm -rf "$RESTORE_WORK"
     ```
8. **한계 고지 (ADR-0013)**:
   - **호스트 생존 사고**: 살아 있는 호스트 대장을 사용하므로 백업 시점 이후의 해지도 유지된다(전환 시 6.2 최종 재적용으로 전환 직전 해지까지 반영).
   - **VM·호스트 영구 소실**: 마지막 성공 백업(OCI 저장소 기준 최대 약 24시간 + 실패 일수, OCI 밖 PC pull 사본 복원 시 마지막 성공 pull 이후 기간) 사이에 기록된 `erasure.log`는 백업에 없으므로, 그 사이 해지한 구독자에게 복원 후 다시 발송될 수 있다. (재발송 메일 안 해지 링크로 재해지할 수는 있으나 유실 자체를 없애지는 못한다.)

---

## 8. 복원 리허설 기록 및 #17 이관 범위

### 복원 리허설 기록 (실서버 미실시)

| 일시 (KST) | 대상 환경 | 복원한 snapshot ID / 데이터 시점 | 소요 시간 | 대장 재적용(`deleted=N`) | 결과 및 비고 |
|---|---|---|---|---|---|
| *미실시 (#17 예정)* | 격리 검증 DB (실서버 리허설 전) | *미실시* | *미측정* | *미실시* | 본 브랜치에서는 일회용 `postgres:17` + 로컬 `restic` 통합 시험(`backup-integration-test.sh`)으로 메커니즘만 검증 |

### #17 및 M2 전환 전 이관·선행 범위

- 사용자 NAS·PC에서 외부 포트 개방 없이 `restic copy` pull 스케줄 실행 및 사본 접근 제한·별도 Healthchecks.io heartbeat 검증 (Windows PC 소스·로컬 격리 시험·설치 절차는 `deploy/backup/pc/pc-pull.ps1`과 [PC pull 운영 절차](pc-pull-backup.md)에 준비 완료; 실제 OCI 읽기 전용 IAM 주체·버킷 연동, PC 영구 설치·NTFS ACL 적용·작업 스케줄러 등록·실운영 heartbeat 수신은 별도 운영 적용 라운드에서 수행)
- **M2 활성화 전 백업 읽기 권한 게이트**: 호스트 `ubuntu`(UID `1001`)로 도는 `gongchae-backup.service`가 앱 컨테이너(`1000:1000`, `0600`)가 생성하는 `/var/lib/gongchae-bell/erasure/erasure.log`를 fail-closed 없이 읽을 수 있도록 읽기 권한 또는 복사 방식을 `ALERTS_ENABLED=true` 전환 전 선행 확정·검증
- 실운영 스키마·키가 포함된 격리 PostgreSQL 복원 리허설, `ERASURE_REAPPLY_COMPLETE` 및 missing/손상 대장 fail-closed 검증, 실측 기반 RTO/RPO 설정
- `erasure.log` 정리 주기, 허용 복원 최소 시점, 연속 커버리지, PC pull 오프라인/실패 복구 절차 및 개인정보처리방침의 실제 사본 파기·잔류 한계(실행 간격·prune/자격 오류·PC 오프라인) 고지 정합성 확정
