# #17 복구 대장 최종안 교차 검수 · Sol

2026-11-20 KST · root Codex 요청 · 비작성자 읽기 전용 검수.

`CLAUDE.md`, recovery `01`·`02`·`03` 각 에이전트 기록, 사용자 결정 `04`, Claude `05`, agy `06`을 대조했다. 실제 source는 root checkout `a5f687c`가 아니라 **`origin/main=eb903017c4fb256a8ece665da795d7a94f6aeb25`**를 고정해 `git show`로 읽었다. `06`의 기준 `c4465f5`와 비교하면 아래에서 판단한 Journal/Reapply/Controller/Maintenance/Matcher/배치/Compose/deploy 파일은 동일하고, alerts 영역에는 별도 Dispatcher 변경만 있다.

**판정:** `06`의 게이트 내장·M1 3조건·UNCERTAIN 방향은 수용한다. 다만 barrier 실행 배타성, 명령의 태그 보간, 교체 권한, 마지막 표지 가드는 그대로 구현하면 안 된다. 줄 수 목표는 판정 근거가 아니며 아래의 최소 조건과 회귀로 닫는다. 새 구현·운영 적용은 하지 않았다.

## 1. P1 · 세션 0의 한 번 확인은 barrier 동안의 writer 부재를 보장하지 않는다

대상: `06` §3.1, §3.2의 INV 보장 문장.

`deploy/deploy.sh:24`의 **`deploy.lock`**과 백업의 **`backup.lock`**은 다르다. `deploy.sh`의 배포·rollback·finalize·abort 등은 `acquire_lock()`을 사용하고, 앱 기동은 `:239`의 Compose up으로 수행한다. `06`은 backup.lock만 유지한다.

반례는 다음 순서다. barrier가 양색을 stop하고 세션 0을 확인한다 → 동시에 CI/수동 `deploy.sh`가 deploy.lock을 얻어 앱을 올린다 → 새 해지 트랜잭션이 대장을 열거나 append한다 → barrier가 rename·표지를 쓴다. 세션 조회 뒤에 생긴 트랜잭션은 검사에 잡히지 않으며, 옛 inode에 열린 writer 또는 표지와 DB commit 사이의 걸침이 다시 가능하다. 마지막 `ps`/세션 재조회만 추가해도 검사 사이 경쟁 자체는 남는다.

최소 보완은 **기존 deploy.lock을 앱 정지 전부터 파일 교체·검증 및 필요한 정지 상태 백업이 끝날 때까지 함께 유지**하는 것이다. 같은 잠금에 참여하지 않는 직접 docker/호스트 JVM/수동 DB writer는 그 정비 구간에 시작하지 않는 운영 전제를 명시한다. 컨테이너 0·검사 세션을 뺀 client backend 0·prepared xact 0은 그 전제 아래 검증값이다. 별도 온라인 compaction 서비스나 새 DB 설정을 우선 도입할 이유는 없다.

backup.lock은 교체까지 유지하고 기존 `backup.sh run`을 호출할 때는 해제해야 한다. 그때에도 deploy 배타성과 앱 정지는 유지한다. 잠금을 잡은 채 같은 잠금을 다시 잡는 `deploy.sh`/`backup.sh`를 호출해 자기 자신을 막는 구현은 피한다. 장애 trap이 검증되지 않은 대장 위로 앱을 자동 재개하지 않아야 한다.

필요 회귀는 **barrier가 잠금을 보유한 동안 경쟁 deploy가 앱 기동까지 도달하지 못함**, 세션 0 조회 뒤 경쟁 writer 시작 시도가 차단됨, 파일 교체 실패 시 정상 재개로 기록하지 않음이다. 현재 반례는 source 제어 흐름 분석이며 실제 경쟁 실행은 하지 않았다.

## 2. P2 · 제시된 직접 Compose stop/ps 명령은 태그 보간에서 실패한다

대상: `06` §3.1 step 2, Blocker 5.

`compose.prod.yml`은 두 image에 `${APP_BLUE_TAG:?Set APP_BLUE_TAG}`와 `${APP_GREEN_TAG:?Set APP_GREEN_TAG}`를 요구한다. 실제 `deploy.sh:219`의 `export_color_tags()`는 이를 `deploy/state/blue_tag`·`green_tag`에서 읽고 없는 색에는 `unset`을 채운다. 이 값들은 .env에 있다는 계약이 아니다. `06`의 직접 명령에는 이 처리가 없다.

**직접 제한 검증:** Compose **v5.6.1** standalone CLI에 고정 SHA의 YAML을 stdin으로 주고, `--env-file NUL`, 합성 APP_IMAGE/DB_USER/DB_PASSWORD만 사용했다. 두 색 profile을 켜도 태그가 없으면 config가 `Set APP_BLUE_TAG`·`Set APP_GREEN_TAG` 보간 오류로 실패했다. 합성 태그 둘을 넣은 동일 config는 exit 0이었다. 실제 .env·Docker daemon·컨테이너는 사용하지 않았다. 최초 CLI 탐색 때 기본 Docker config 접근 거부 경고가 있었고 내용을 읽지 못했으며, 이후는 비어 있는 별도 DOCKER_CONFIG 경로와 standalone 바이너리를 사용했다.

최소 보완은 barrier의 Compose 공통 호출도 **기존 state 태그 적재 규약**을 따르게 하는 것이다. stop뿐 아니라 ps와 DB exec도 같은 보간·프로젝트 문맥을 써야 한다. 조회 명령 실패를 빈 stdout과 같게 취급하면 안 된다. 실제 운영 project name 및 compose file도 deploy와 일치시킨다.

덧붙여 Blocker 5의 “profile 없이는 stop에서 앱이 빠진다”는 주장은 **서비스명을 명시한 stop까지 일반화하면 틀리다**. Docker 공식 문서는 profile 서비스도 `docker compose stop phpmyadmin`처럼 명시해 중지하는 예를 제공한다. 양색 profile을 명시하는 현재 최종 명령 자체는 유지해도 된다. 실제 남은 오류는 태그 보간과 실행 결과 확인이다. [Docker profiles 공식 문서](https://docs.docker.com/compose/how-tos/profiles/#stop-application-and-services-with-specific-profiles)

## 3. P2 · ubuntu 단독 barrier와 제안한 0750/0640 권한은 양립하지 않는다

대상: `06` Blocker 3 최소 해법, §3.2 step 3, §4 barrier 행.

제안대로 디렉터리 `1000:ubuntu 0750`, 파일 `1000:ubuntu 0640`이면 ubuntu는 파일을 읽을 수 있지만 **디렉터리에 새 임시 파일을 만들거나 rename할 수 없다**. ubuntu가 임시 파일 소유자를 1000으로 chown하는 것도 일반 권한으로는 안 된다. 따라서 “ubuntu 권한 barrier”가 lock 획득부터 파일 교체까지 모두 수행하는 설명은 실행 불가다. 소유·모드를 0600으로 되돌리면 백업 접근 문제를 재발시킨다.

최소 보완은 **잠금 보유와 대장 교체 권한을 구분**하는 것이다. ubuntu 소유의 기존 backup.lock을 유지하는 감독 프로세스와, 디렉터리 교체·소유/모드 보존 권한을 가진 제한된 helper의 경계를 정한다. root가 lock을 새로 만들지 않도록 사전 생성/소유 검증하고, helper 실행 중에도 감독 프로세스가 잠금을 계속 보유해야 한다. sudo가 fd를 보존할 것이라고 가정하지 않는다. 실제 호스트의 UID/GID는 설치 시 확인한다.

검증 조건은 교체 전후 앱 UID1000의 append·strict 읽기 성공, backup 사용자 읽기 성공/쓰기 불가, other 접근 불가, lock 소유 불변이다. 이 권한 시험은 아직 실행하지 않았다.

## 4. P2 · 마지막 줄이 표지라는 가드는 정상적인 다음 주기 정리를 영구히 막을 수 있다

대상: `06` Blocker 4 guard 2, §3.2의 마지막 줄 조건.

`L=[M1,u1,M2]`를 만든 뒤 다음 정리 주기까지 새 해지가 0건이라고 하자. 다음 barrier는 이제 `M2` 앞의 u1을 지울 수 있어야 한다. 하지만 마지막 줄은 계속 M2이므로 `BARRIER_ALREADY_AT_TAIL`에 걸려 **새 해지가 다시 들어올 때까지 u1을 정리하지 못한다**. 개인정보 줄의 체류가 트래픽에 종속된다. 최초 `[M1]`처럼 지울 줄이 없는 상태와 위 상태는 다르다.

“마지막 표지 뒤 새 해지가 없음”은 “같은 작업의 즉시 재시도” 증거가 아니다. **지울 실제 해지 줄이 없을 때는 절단하지 않는 guard 1은 유지**하되, 정상적인 다음 barrier 진행을 tail 여부만으로 거부하지 않아야 한다. 선택된 주기에 따라 의도적으로 다음 barrier로 진행하는 호출과 동일 작업 재시도의 취급을 정한다. 당장 새 영속 시스템을 추가할 필요는 없지만, 같은 실행을 다시 요청했을 때 어느 표지를 재사용/인식할지는 도구 계약으로 남겨야 한다.

최소 회귀는 `[M1]`, `[M1,M2]`의 무의미한 절단 방지, `[M1,u1,M2]`에서 **새 해지 없이 정상 다음 주기 진행**, 같은 실행의 즉시 재시도에서 유효한 옛 복원점을 추가로 버리지 않음이다. 복원점 0개를 허용한 `04`는 14일 초과 만료의 선택이며, 임의의 반복 호출이 14일 이내 복원점까지 버려도 된다는 승인으로 쓰지 않는다.

## 5. 수용하는 사실과 보장 범위

### flags=false는 발송 차단과 DB 동결이 같은 뜻이 아니다

`SubscriptionController:196`~`:197`, `:224`, `:233`, `:241`에서 `ALERTS_ENABLED=false`는 관리 화면 해지·일반 해지·one-click 해지를 **503으로 차단**한다. 이 상태를 “웹 해지는 정상 제공되는 발송 중지 모드”라고 안내하면 안 된다. `06`의 이 사실 정정은 맞다.

`AlertsMaintenance:34`~`:49`는 enabled 검사 없이 5분마다 pending/disabled/완료 작업·rate_limit 삭제와 sweepSync를 실행한다. `ALERTS_SCHEDULER_ENABLED=false`도 별도 `AlertScheduler`의 설정이므로 maintenance 전체 중지를 뜻하지 않는다. .env 값 변경만으로 이미 기동한 JVM의 상태가 바뀌는 것도 아니다. barrier에는 앱 완전 정지를, 복원에는 **full 앱 기동 전에 SQL 마감**을 유지한다. `web-application-type=none`의 일회성 명령 역시 모든 예약 작업이 자동으로 사라진다는 근거로 쓰지 않는다.

### UNCERTAIN + item 보존은 덤프에 있는 작업·item에 대해 유효하다

`V5`의 terminal/finished_at CHECK, worker의 PENDING/RETRY 선점, `DeliveryLedger.finishAsync/recoverAsync`, `notification_item.notification_id ON DELETE SET NULL`과 `06` SQL이 맞는다. 덤프의 미완료 작업을 앱 기동 전에 UNCERTAIN으로 닫고 item을 남기면 그 작업은 다시 선점되지 않으며 NEW/DEADLINE 재매칭도 막힌다. 이는 **미발송일 수도 있는 작업을 재시도하지 않는 대가**를 포함한다.

백업 뒤에 생성된 작업·item은 덤프에 없으므로 이 조치로 복원할 수 없다. `PostingIngestor`는 flags=false여도 정기 삽입을 from_backfill=false로 하고, `AlertOutbox`만 조기 반환한다. `AlertMatcher.findMatchingNew()`는 OPEN·미마감·비backfill·확인 이후 first_seen·NEW item 없음 조건으로 다시 선택한다. 따라서 같은 날 DIGEST 슬롯 마킹은 당일만 막고, 조건에 남는 공고는 익일 EMAIL_DIGEST에 다시 들어갈 수 있다. 덤프에 공고만 있고 발송 item이 없었던 경우도 포함된다. DEADLINE은 해당 KST 날짜 D-3 선택이라는 별도 범위다.

`06`의 이메일 중복 한계 정정은 수용한다. “복원 후 중복 0”은 주장할 수 없다. 현재 DB만으로 이미 발송된 공고와 아직 미발송 공고를 완전히 구별할 수 없으므로 **중복 감수/추가 누락 감수의 운영 선택을 남긴다**. 일괄 backfill화·item 선채우기를 정답처럼 자동 적용하지 않는다. 공백 구간과 적용한 SQL/슬롯·첫 수집 방법은 기록하고, duplicate-risk 지표만으로 그 구간의 중복 부재를 입증하지 않는다.

### 게이트 내장과 M1 예외 축소는 필요하다

현재 `ErasureJournal.readStrict()`는 READ+WRITE/OS 잠금을 요구하고 `ErasureReapplyCommand`는 latest UUID 목록 DELETE만 수행한다. `06`처럼 공통 strict 파서로 B를 읽기 전용 검증하고 CLI의 B 입력을 필수로 해 **삭제 전에** coverage 검증을 강제하는 방향이 맞다. 구버전 COMPLETE 로그만으로 게이트 성공을 인정하지 않는다. 표지 UUID는 실제 해지 대상에서 분리하고 중복 표지는 거부한다.

**SUB_COUNT=0만의 M2 면제는 허용하지 않는다.** 유일한 자동 생략은 SUB_COUNT=0 AND erasure_included=false AND M2 미개시 근거/호스트 대장 부재라는 기존 M1 3조건이다. M2 개시 후 flags=false 정비 중이어도 REQUIRE_ERASURE_LOG=true를 유지한다. M2의 빈 구독 테이블은 missing·손상·표지 유실 대장 검증을 생략할 근거가 아니다.

초기 표지는 빈 L 재생성 사고를 더 잘 잡지만, **B 이후 정상 줄이 통째로 사라진 tail 손실**이나 정상 과거 대장으로의 되돌림을 언제나 탐지하지는 못한다. `05`의 이 한계와 VM 영구 소실 뒤 마지막 백업 이후 해지 유실 한계를 최종 설명에서 제거하면 안 된다.

## 6. 구현 전에 닫을 최소 검증

- shared strict 파서: 가변 소수초·날짜 유효성·잘린 줄·중복 표지. 불변 B `:ro` 성공, 누락/손상/coverage 불일치는 **DELETE 이전 실패**, M2 SUB=0도 동일 실패. M1은 위 3조건만 생략.
- S0/S1/S2: byte prefix, S2 head 표지가 B에 정확히 한 번 있고 그 suffix가 L prefix, 기존 원본 보존. 시계 역행이 있어도 줄 순서를 기준으로 판정.
- 정지 barrier: 위 1~4의 경쟁 배포·권한·무해지 구간·재시도와 rename 전/후 실패. 특정 80~120줄에 맞췄다는 결과로 대체하지 않음.
- 합성 복원 DB: SQL 마감 뒤 worker가 UNCERTAIN을 재선점하지 않음, 30일 job 삭제 뒤 item 유지, 덤프 밖 공백 구간 EMAIL_DIGEST 재생성 반례를 실제로 구분. 이 범위를 넘는 중복 0 주장은 하지 않음.

직접 실행한 것은 합성 입력의 로컬 Compose config 검증뿐이다. Java/PG 경쟁·권한·crash·발송 재생성 시험은 실행하지 않았고 향후 검증 조건으로 적었다. 실제 서버·.env·SSH키·provider·백업 저장소에 접근하거나 앱/시험/구현 설정/브랜치를 변경하지 않았다. 결과 파일 하나만 작성했다.
