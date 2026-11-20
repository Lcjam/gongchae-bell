# PR23 알림 매칭·발송 독립 검수

2026-11-18 root Codex가 PR23 code head `1b7c23cde830424a0bff9f9219379af985dbc063`, base `ed3f28f38623afeccd41006548a9e4f54ed0e301`를 검수 중이다. 다른 비작성자의 PR23 검수 결과를 읽기 전에 이 기록을 썼다. 작성자의 최종 CI·제출은 별도로 확인한다.

## 현재 판정

추가 수정 요청이다. 먼저 확인한 outbox 부모 잠금 결함을 아래에 기록하며 나머지 발송·회수·스케줄 접점 검수도 계속한다.

## P2 해지와 outbox 생성 경합이 외곽 수집·일일 생성 TX를 롤백함

`AlertOutbox.createJobWithItems`는 부모를 명시적으로 잠그기 전에 `INSERT INTO notification ... SELECT ... FROM subscription s WHERE s.id = :subId AND s.status = 'ACTIVE'`를 실행한다. 조회된 활성 구독과 실제 FK 검사 사이에 해지가 커밋되면 FK 오류가 난다. 자동 FK 잠금만으로 조회·해지 경합을 처리할 수 없다.

정기 수집은 PostingIngestor의 공고 반영 TX 안에서 이 outbox를 호출하므로 한 구독의 동시 해지가 같은 회차의 공고 반영·다른 구독 outbox까지 롤백할 수 있다. 일일 묶음도 여러 구독을 같은 생성 TX에서 처리하므로 전체 생성이 실패한다. #6에서 부모 조회 뒤 child INSERT 전에 KEY SHARE로 해지와 직렬화한 계약을 #7 접점에도 적용해야 한다.

root가 ignored `.tools/pr23-fk-race.py`에서 실제 V5 migration과 제출된 INSERT SQL을 그대로 추출해 제한된 독립 재현을 실행했다. posting은 FK 의존성을 위한 sn PK stand-in이고, 부모·알림·시도·item·제한 테이블은 제출 V5 원문이다. 네트워크·포트 없는 임시 PostgreSQL17 컨테이너에 합성 구독 하나를 넣고 다음 순서를 고정했다.

1. 해지 세션이 subscription DELETE 뒤 커밋을 기다린다.
2. 다른 TX가 합성 posting marker를 기록하고 제출 outbox SQL을 실행한다. pg_stat_activity에서 이 세션의 Lock 대기를 확인한다.
3. 해지를 커밋하면 outbox SQL이 SQLSTATE 23503, notification_subscription_id_fkey로 실패한다. 그 TX 앞의 posting marker도 0행으로 롤백되고 notification도 0행이다.
4. 진단은 exit0과 `EXACT_OUTBOX_SQL_AFTER_CONCURRENT_DELETE_FK_23503_AND_OUTER_POSTING_ROLLBACK`을 반환했다. 임시 컨테이너는 finally에서 제거했다. 실제 DB·API·키·env는 사용하지 않았다.

부모를 child 생성 전에 명시적으로 잠그고, 이미 지워졌거나 비활성인 경우에는 해당 구독을 건너뛰도록 수정한다. 정상 PostingIngestor·일일 묶음 진입점에서 해지 선행/경합을 재현해 전체 생성·공고 반영은 보존되고 해당 구독의 HTTP가 없음을 회귀 검증한다. 무관한 스키마·기존 동기 경로의 계약을 바꾸지 않는다.

## 검수 진행 범위

현재 조건·OPEN·D-3의 R 전후 재검사, 두 종류를 분리한 대상 집합, unknown/full payload hash, 독립 R 예약·펜싱과 item 반환, 404 별도 TX3, RFC8058 본문·헤더와 고정 라벨 지표를 소스로 대조 중이다. 슬롯 실패 뒤 정책은 기존 ADR-0003의 다음 회차·당일 슬롯 부재 만회 계약을 기준으로 검토한다. 별도 재선점 정책을 추가 요구하지 않는다.

## 독립 검수 교차 확인 후 추가 요청

위 독립 기록과 직접 재현을 마친 뒤 Sol-high의 `pr-23-sol.md`를 읽었다. 같은 부모 잠금 경합을 별도로 재현한 결과가 일치한다. 추가 두 P2도 source와 정상 배포 경로를 대조해 수정 요청으로 수용한다.

### 운영 워커 설정이 앱 컨테이너에 전달되지 않음

application.yml은 ALERTS_WORKER_CONCURRENCY·ALERTS_BATCH_SIZE·ALERTS_SCHEDULER_ENABLED를 읽으며 운영 문서에도 설정으로 설명한다. 실제 prod Compose의 앱 공통 environment와 env 이름 예시에는 세 항목이 없다. `.env` 보간 입력이 자동으로 컨테이너 환경에 모두 들어가는 구성이 아니므로 비기본 값과 false가 앱에 전달되지 않는다. 두 색의 실제 merged config에서 설정 전달을 검증하고 문서를 맞춘다. Sol-high는 합성 값 1·3·false가 양색 environment에 없는 것을 별도 Compose config 진단으로 확인했다.

### NEW의 수집 반영부터 발송까지의 지연이 빠짐

AlertDispatcher 성공 시의 지연은 job.createdAt을 시작점으로 쓰고 AlertsMetrics는 그 차이를 기록한다. Discord NEW는 같은 수집 TX의 관측 시각으로 작업을 만들어 가깝지만 이메일의 job.created_at은 08시 묶음 생성 또는 만회 시각이다. NEW 공고의 수집 반영과 그 뒤 묶음까지 기다린 시간이 빠진다. PRD G7의 수집 반영→발송 관측을 충족하도록 원래 공고의 반영 시각을 사용하는 관측을 추가·정리하고, 작업 생성→발송 지표의 의미도 명확히 한다. 시점이 다른 합성 공고·작업의 성공 사례로 시작점을 검증한다. 이를 운영 실측으로 표현하지 않는다.

두 검수 모두 직접 앱 전체 시험은 반복하지 않았다. 기존 집중·경합 시험의 구조와 source/CI 근거, 특정 FK 경합의 격리 PG 재현으로 검토했다. #7 실제 공급자·HTTPS·수신함·경보 수신은 운영 후속이다.
