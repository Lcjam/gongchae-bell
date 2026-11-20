# PR23 #7 독립 검수

2026-11-18 Codex 검수 담당이 구현에 참여하지 않고 독립 검수했다. root의 다른 리뷰를 읽기 전에 이 결론을 작성했다.

## 대상과 판정

- 코드 대상은 `1b7c23cde830424a0bff9f9219379af985dbc063`, base는 `ed3f28f38623afeccd41006548a9e4f54ed0e301`이다. diff는 24개 파일, 2,288줄 추가·18줄 삭제다.
- 검수 시작 시 관측한 HEAD `0471389cd4b4186d8f0686602404ef6cbdc4092a`는 대상 코드 뒤 결과 문서 하나를 추가한 커밋이었다. 검수 후반에는 작성자의 `AlertOutbox`·시험 파일에 미커밋 수정이 관측됐다. 아래 위치와 판정은 고정 코드 SHA에 한정하며 그 후속 수정의 수용 판정은 포함하지 않는다.
- **수정 요청이다.** 생성과 해지 경합, 운영 env 전달, NEW 지연 지표에서 P2 세 건을 확인했다. 현재 진행 CI를 성공 근거로 사용하지 않았다.

## P2-1 생성 INSERT가 해지와 겹치면 정상 구독자의 일일 묶음까지 롤백됨

위치는 `AlertMatcher.java:58-79`, `AlertOutbox.java:50-61`, `AlertBatchGenerator.java:79-104,117-137`이다.

생성기는 잠금 없는 `findActiveSubscribers` 결과를 순회하고 `INSERT INTO notification … SELECT … FROM subscription WHERE … status='ACTIVE'`로 작업을 만든다. 생성 SQL은 부모를 먼저 잠그지 않는다. 해지 트랜잭션이 부모를 DELETE한 뒤 아직 커밋하지 않은 동안 이 INSERT가 시작하면 SELECT는 이전 ACTIVE 버전을 읽지만, FK 검사는 DELETE를 기다린 뒤 부모 부재로 실패할 수 있다.

이 예외는 모든 구독자를 감싼 하나의 생성 트랜잭션을 롤백한다. 앞서 다른 정상 구독자에게 생성한 작업도 사라진다. 날짜 슬롯은 `CollectionRuns.claim`의 별도 트랜잭션에서 이미 커밋돼 있고 실패 기록 뒤에도 같은 `(kind,slot)`으로 다시 선점되지 않는다. 따라서 한 명의 해지가 그날 전체 묶음의 누락으로 이어질 수 있다. 같은 writer가 정기 수집 outbox에도 사용되므로 해당 경합은 수집·공고 반영 트랜잭션의 롤백 원인도 된다.

### 직접 확인한 제한 재현

고정 SHA의 실제 `V5__subscriptions.sql`을 일회용 PostgreSQL 17에 적용하고 합성 ACTIVE 구독 두 개를 만들었다. posting은 FK 충족용 최소 테이블만 만들었다. 세션 B가 두 번째 구독을 DELETE하고 2초 동안 미커밋으로 유지하는 동안, 세션 A는 하나의 트랜잭션에서 첫 번째 구독의 notification 생성 후 두 번째 구독에 제출된 INSERT SELECT를 실행했다. statement/lock timeout을 두고 종료 상한을 제한했다.

- 두 번째 INSERT가 FK 오류로 실패했고 notification 최종 개수는 0이었다. 첫 번째 구독은 살아 있었다.
- 생성 전 독립적으로 기록한 합성 DIGEST 날짜 슬롯은 실패 뒤 같은 `ON CONFLICT … DO NOTHING RETURNING`에서 재선점 0건이었다.
- 진단은 exit 0과 `UNSUBSCRIBE_RACE_FK_ERROR_ROLLS_BACK_ALL_BATCH_NOTIFICATIONS`, `SAME_DAY_SLOT_ALREADY_CLAIMED_AFTER_ROLLBACK`로 끝났다.

Java 생성기를 직접 실행한 재현은 아니며, 실제 V5 FK와 제출 SQL의 경합 결과를 확인하고 Java 트랜잭션·슬롯 코드를 대조한 결론이다. 생성 입구에서도 부모→자식 순서를 보장해, 먼저 끝난 해지는 해당 구독을 건너뛰고 생성이 먼저면 해지가 그 부모 보호 뒤에 진행되도록 수정해야 한다. 단일 구독의 정상 해지 때문에 다른 구독자의 묶음·수집 전체를 실패시키지 않는 회귀 시험이 필요하다.

## P2-2 문서화한 세 워커 설정이 실제 Compose 앱으로 전달되지 않음

`application.yml:68-70`은 `ALERTS_WORKER_CONCURRENCY`, `ALERTS_BATCH_SIZE`, `ALERTS_SCHEDULER_ENABLED`를 읽고 `docs/ops/alerts.md:28`도 운영 설정으로 설명한다. 하지만 `compose.prod.yml`의 공통 앱 environment에는 세 키가 없고 `deploy/.env.prod.example`에도 없다. 이 Compose는 `.env` 전체를 컨테이너 env로 전달하는 구조가 아니다.

고정 SHA의 prod Compose와 합성 env만 Docker CLI 컨테이너에 넣고 `compose --profile blue --profile green config --format json`을 실행했다. 합성 env에는 동시성 1, 배치 3, 스케줄러 false를 명시했지만, 생성된 app-blue와 app-green environment에는 세 키가 모두 없었다. 앱은 기본 2·20·true를 사용한다. 특히 운영자가 문서대로 스케줄러를 꺼도 실제 배포 앱의 예약 생성·발송은 계속 활성화된다.

정상 배포 경로에서 세 키를 전달하고 키 이름 예시·문서를 맞춰야 한다. 합성 `.env`의 false와 비기본 수치가 양색 앱 environment에 도달하는 설정 회귀 검증이 필요하다. 이 진단은 Compose config만 실행했으며 앱이나 Docker 소켓을 제공하지 않았다.

## P2-3 이메일 NEW의 수집 반영→발송 지연을 작업 생성→발송 시간으로 줄여 기록함

`AlertDispatcher.java:189`는 성공 지연에 `job.createdAt()`을 전달하고 `AlertsMetrics.java:86-89`는 그 시각부터 finishedAt까지를 `alerts.delivery.latency`에 기록한다. 이메일 작업의 created_at은 `AlertBatchGenerator.java:84,93`의 08시 또는 만회 실행 시각이다. NEW 공고의 실제 수집 반영 시각은 사용하지 않는다. `alerts.delivery.delay.max.seconds`도 notification.created_at부터의 나이만 본다.

PRD G7과 구현 요청은 수집 반영부터 발송까지의 지연을 요구한다. 예를 들어 합성 시각으로 07:30 반영·08:00 묶음 생성·08:00:05 발송이면 현재 계산은 5초이고 요구한 지연은 1,805초다. 이는 소스 수식에 대한 예시 계산이며 실측치가 아니다. 이메일이 다음 날 묶음까지 기다린 시간도 같은 방식으로 지표에서 빠진다.

작업 대기 시간의 정의를 문서화하고 NEW의 수집 반영 시점부터 발송까지의 지연도 관측해야 한다. 생성 시각과 수집 시각이 다른 합성 사례로 값의 시작점을 검증해야 한다. 이 항목은 코드·계약 대조로 확인했으며 별도 앱 시험은 실행하지 않았다.

## 비차단 문서 관찰

`docs/ops/alerts.md`가 새로 연결한 `collab/2026-11-17-m2-readiness/03-codex-alert-eligibility-decision.md`는 고정 SHA의 Git tree에 없다. root의 해당 결정 파일을 읽어 검수했지만 PR을 checkout한 독자는 이 상대 링크를 열 수 없다. 결정 기록을 보존하거나 저장소에서 열리는 근거로 연결하면 된다.

## 수용 가능한 구현 경계

- 정기 수집의 실제 INSERT 성공 SN만 같은 트랜잭션의 outbox 훅으로 넘기며 backfill·변경·재등장을 제외한다. 공고·item 유니크 키와 전체 롤백 연결이 있다.
- matcher는 기존 목록 조건 술어를 재사용하고 OPEN·마감 유효성, 활성화 이후 non-backfill NEW, 현재 날짜 기준 D-3를 검사한다.
- dispatcher는 부모 SHARE 아래 R 전에는 자식 읽기와 payload 계산만 한다. 별도 R 뒤 notification을 잠그고 claim·예약 번호·키를 확인한다. 현재 자격·typed 대상 `(sn,kind)`·전체 전송 payload hash를 재검사하며 R 뒤 자정 탈락에서 NOT_SENT와 예약 보존을 수행한다.
- unknown이 있으면 바뀐 대상·본문을 재발송하지 않고 item을 보존한다. 확실한 거절 뒤에만 키를 바꾼다. 3시도·23시간·재시도 대기·다음 UTC 창·확실한 실패 item 반환은 기존 Ledger 계약을 재사용한다.
- 404 비활성화는 TX2 커밋 후 별도 TX3다. 범용 4xx·5xx로 임의 비활성화하는 경로는 없다. 기존 동기 종류를 비동기 claim에서 제외하며 기존 동기 메서드의 의미를 보존한다.
- 이메일 전체 JSON에 관리·해지 링크와 RFC8058 헤더를 넣고, Discord는 allowed_mentions와 요약·목록 링크를 포함한다. hash 입력은 실제 전송 바이트 전체다.
- 워커 executor의 기본 동시성은 2이고 종료 대기·중단 처리가 있다. digest heartbeat는 선점 후 생성 완료를 기록한 앱에서 보내며 URL 없는 고정 로그와 실패 격리를 사용한다. 지표 라벨은 코드의 고정 kind·channel·provider·outcome이고 이메일·웹훅·토큰·구독 ID를 붙이지 않는다.

## 확인 범위와 남은 검증

CLAUDE.md, PRD FR-A4·A5·A8/G7, GitHub #7 이슈 본문, root 구현 요청, 확정 12·13·14 계약, 자격 결정, main의 #6 계약을 읽었다. 새 BatchGenerator·Dispatcher·Matcher·Outbox·PayloadBuilder·Scheduler·Worker·Metrics·DigestHeartbeat, Ledger·PostingIngestor·SubscriptionService 접점과 두 새 시험을 대조했다. [이슈 #7](https://github.com/Lcjam/gongchae-bell/issues/7)

독립 실행은 위 두 제한 진단이다. PostgreSQL은 `--network none`, 메모리 상한 160MB의 일회용 컨테이너 하나로 실행했다. 진단·합성 env·고정 스키마/Compose 사본은 root ignored `.tools/pr23-review/`에만 두었다. 전체 Gradle·기존 통합 시험·대규모 병렬 작업은 실행하지 않았다.

제출 시험에는 슬롯·다중 claim·R 후 중단·unknown·자정·HTTP 중 해지·본문/토큰 변경·RFC8058·지표 이름/PII 검증이 있다. 생성 INSERT와 해지 사이 FK 경합, 실제 Compose 워커 env, 수집 시각 기준 지연 값은 해당 시험으로 검증되지 않는다. 최종 CI와 수정 후 회귀는 후속 고정 SHA에서 확인해야 한다. 검수 중 진행하던 작성자 CI·결과 기록은 통과 근거로 취급하지 않았다.

실제 양 채널 발송·수신, SPF/DKIM/DMARC, 실제 HTTPS·계정 고지·운영 자원과 지표 기준선은 후속 실환경 게이트다. 이번 검수에서는 실제 provider·`.env`·키·개발 DB·서버·계정·OCI·DNS·결제에 접근하지 않았고 앱 코드·시험·설정·브랜치를 수정하지 않았다.
