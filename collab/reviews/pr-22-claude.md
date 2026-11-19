# PR22 (#16 백업) 검수 · 1라운드 · Claude

2026-11-18. 구현에 참여하지 않은 root Claude가 검수했다. 대상은 PR #22 `ops/#16-backup` head `c9225fa`(코드 `f0605c3`), 범위 `git diff 000dce5 c9225fa`. 요청 기준은 `collab/2026-11-18-implementation/agy-backup-request.md`와 issue #16이다.

## 판정

**수정 요청.** 백업 대상·순서·검증·비밀값 분리·heartbeat 구조는 잘 짜였다. 다만 보관 정책이 실제로는 아무것도 지우지 않는 결함(B1)이 있다. 이대로면 백업이 끝없이 쌓여 OCI 무료 용량을 넘고, 문서의 14일 보관 서술과도 어긋난다.

## 결함

### B1 (P1) 무작위 임시 경로 때문에 `forget --keep-daily 14`가 snapshot을 하나도 지우지 않는다

- **위치**: `deploy/backup/backup.sh:379`(`mktemp -d .../gongchae-backup.XXXXXX`), `:419`(그 디렉터리에서 상대 경로로 `restic backup`), `:424`(`forget --tag gongchae-bell --keep-daily ... --prune`)
- **원인**: restic은 snapshot의 `paths`를 절대 경로로 기록한다(`NewSnapshot`이 `filepath.Abs` 적용). 그런데 `forget`의 기본 묶음은 `--group-by host,paths`다. 실행마다 임시 디렉터리 이름이 달라서 snapshot마다 묶음이 하나씩 생기고, `--keep-daily N`은 묶음마다 최신 일일 snapshot을 남긴다. 결과적으로 모든 snapshot이 영구 보존된다.
- **재현** (restic 0.19.1 공식 이미지, 로컬 파일 저장소, 2026-11-18): `mktemp -d /tmp/gongchae-backup.XXXXXX` 3개에서 각각 `restic backup --tag gongchae-bell --tag daily gongchae-bell.dump` → `paths`가 `/tmp/gongchae-backup.LOiGnf/...`, `.dKHlIM/...`, `.ocIpNG/...`로 모두 다름 → `forget --tag gongchae-bell --keep-daily 1` 뒤 **3개 그대로**. 같은 저장소에 `--group-by host,tags`를 붙이면 **1개**로 줄었다.
- **영향**: 하루 1개씩 계속 쌓인다. 문서 §2의 "최대 14개 일일 snapshot"과 실제 동작이 다르고, 해지된 구독자 데이터가 담긴 오래된 덤프가 보관 기간을 넘어 남는다(개인정보처리방침 보관 서술과 충돌 위험). OCI 무료 범위(아래 교차 검증)를 결국 넘는다. 공식 문서에 따르면 무료 체험이 끝날 때 20GB를 넘으면 객체가 모두 삭제된다.
- **시험이 못 잡은 이유**: 단위 시험은 restic 대역이고, 통합 시험은 백업을 한 번만 한다.
- **수정 제안**: `forget`에 `--group-by host,tags`를 붙인다(태그 집합 `gongchae-bell,daily`는 고정). 경로 메타데이터도 안정되게 임시 디렉터리를 고정 이름(잠금 아래에서 지우고 다시 만듦)으로 바꾸는 것도 함께 고려한다. 통합 시험에 "`BACKUP_KEEP_DAILY=1`로 두 번 실행하면 snapshot 1개만 남는다"를 넣어 이 결함을 고정한다. #17의 `restic copy`와 pull 쪽 보관 정책 문서에도 같은 묶음 기준을 적는다.

### B2 (경미) `ALERTS_ENABLED` 감지가 따옴표·대소문자에 약하다

- **위치**: `backup.sh:248-251` `grep -qE '^[[:space:]]*ALERTS_ENABLED=true(...)'`
- `ALERTS_ENABLED="true"`나 `TRUE`처럼 Compose와 Spring은 받아들이는 값이 M1로 판정된다. 앱은 켜질 때 대장 파일이 없으면 시작하지 않으므로(#6 C2) 실제 누락 위험은 낮다. 그래도 fail-closed 의도에 맞게 따옴표와 대소문자를 허용하거나, M2 전환 절차에서 `backup.env`에 `REQUIRE_ERASURE_LOG=true`를 명시하도록 문서에 적는 편이 낫다.

### B3 (경미) env 적재·도구 확인 단계의 실패는 `/fail`을 보내지 않는다

- `IN_BACKUP_RUN=1`은 `cmd_run` 안에서만 켜진다. `backup.env` 권한 오류·누락 키·도구 누락은 `/fail` 없이 끝나고 Healthchecks의 일정 유예 초과로만 드러난다. 설계상 불가피한 부분(URL을 읽기 전 실패)이 있으니, 문서 §실패 경보에 "설정 오류는 미수신 경보로 감지된다"를 한 줄 적는다.

## 확인한 것

- **덤프 일관성**: 실행 중 Compose `postgres` 컨테이너 안에서 `pg_dump -Fc`(단일 트랜잭션 snapshot), `pg_restore --list`로 TOC 검증, 빈 파일 거부. DB 볼륨 파일 복사 없음
- **해지 대장 순서**: 덤프 뒤에 대장을 복사하므로 대장이 덤프 시점 이후 해지까지 포함한다. 복원 뒤 재적용이 덤프에 남은 해지 구독을 지울 수 있는 순서다(ADR-0013). 줄 중간 복사는 끝 줄바꿈 확인과 1회 재시도로 막는다(앱의 Java 파일 잠금과 `flock`은 Linux에서 서로 보지 않으므로 이 방식이 현실적이다)
- **비밀값**: restic·S3 자격증명은 `backup.env`(0600/0400, 소유자 검사, symlink 거부)에서만 읽고 앱 `.env`와 분리. 로그와 heartbeat 본문은 고정 문자열·URL 자격증명·hc-ping 경로 마스킹. `compose exec`는 호스트 env를 컨테이너로 넘기지 않는다
- **heartbeat**: 덤프·검증·backup·prune·요약이 모두 성공한 뒤에만 성공 ping, 그 외 `/fail`. 성공 ping 실패도 실패 종료(보수적)
- **systemd**: `OnCalendar=*-*-* 03:00:00 Asia/Seoul`, `Persistent=true`, `PrivateTmp=yes`. `User=ubuntu`는 docker 그룹 소속이 전제인데 서버 인벤토리에서 직접 확인하지 못했다. 설치 절차에 `id -nG ubuntu | grep docker` 확인을 넣기를 권한다
- **CI**: [44227670423](https://github.com/Lcjam/gongchae-bell/actions/runs/44227670423) `f0605c3` test·backup-verify(ARM64, `ALL_BACKUP_UNIT_TESTS_PASSED`, `BACKUP_INTEGRATION_TEST_PASSED`)·secrets 성공을 로그로 확인
- **OCI 무료 한도 교차 검증**(외부 사실 규칙, 2026-11-18 공식 페이지 직접 확인): [Always Free Resources](https://docs.oracle.com/en-us/iaas/Content/FreeTier/freetier_topic-Always_Free_Resources.htm)에 Always Free 전용 계정은 "20 GB of combined Standard tier, Infrequent Access tier, and Archive tier data", 유료·체험 계정은 티어별 10GB, 두 경우 모두 "50,000 Object Storage API requests per month"로 적혀 있다. 문서 표와 일치한다. 추가로 "If you are using more than the 20-GB limit when your Free Trial ends, all of your objects will be deleted."가 있다. 유료 계정에서 Object Storage 한도 초과분 과금에 대한 문장은 이 페이지에 없다. 페이지에 갱신 일자는 표시되지 않는다

## 확인하지 못한 것

`backup-test.sh`와 통합 시험을 로컬에서 다시 돌리지 않았다(CI 로그로 판단). 실제 OCI·Healthchecks·서버 설치는 PR 본문 후속 게이트다.
