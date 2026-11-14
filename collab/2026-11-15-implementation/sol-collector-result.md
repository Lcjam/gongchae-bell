# #4 수집 실행기 결과 · sol

브랜치: `feat/#4-collector-runtime`. 작업 경로: `C:/Users/lcj/orca/workspaces/my-project/issue-4-collector-runtime`.

## 구현

- 공식 API 고정 HTTP 클라이언트: 정기 목록 1페이지, 최초 적재 월/페이지, 신규 상세. 일반 인증키를 한 번 인코딩하며 공개 경로로 대체하지 않는다. 10초 요청/연결 타임아웃, 최초 포함 최대 3회, 2초/4초 대기, 429 Retry-After, 리다이렉트 차단. 실패 메시지는 내부 고정 코드뿐이다.
- `ApiBudget`: KST 날짜별 최대 900회, 500번째 예약 WARN. 모든 최초 적재/정기/상세/재시도는 HTTP 전에 REQUIRES_NEW 트랜잭션에서 예산 증가와 `api_attempt`를 함께 커밋한다. 외부 반영 롤백/재시작/결과 저장 전 종료에도 예약은 남는다.
- `CollectionRuns`: UNIQUE INSERT로 회차 선점. 정기 코어의 완료/파싱 실패 기록을 덮어쓰지 않고 HTTP/예산 실패만 마감한다.
- KST 매시 7분 정기 실행. 명시적 `COLLECTOR_ENABLED=true`와 환경변수 키가 있어야 실행한다. 기본은 비활성이다. 성공 시 #3 실제 `PostingIngestor.applyRegular`를 호출한다.
- 최초 적재: 기본 최근 3년을 월별/페이지별 처리한다. 수동 별도 슬롯, 완료 월/페이지 건너뛰기, 실패 페이지 재개, totalCount 변경 시 명시적 미완료 페이지 재시작 옵션. #3 v2 `PageResult.sns`의 정확한 식별자 집합으로 페이지 간 중복과 월의 총수신/고유 수를 검사한다. inserted로 추정하거나 본문을 다시 파싱하지 않는다. 전체 기간 고유 수도 수신 SN 합집합으로 기록해 월 사이에 시작일이 이동한 공고를 이중 집계하지 않는다. 날짜 오류는 코어의 페이지 전체 실패이며 달을 완료하지 않는다.
- 로컬 전용 ApplicationRunner 명령. 스케줄과 동시 활성화를 금지하며 작업 후 컨텍스트/DB 풀을 종료한다. 웹 수집 운영 엔드포인트는 없다.
- 신규 진행 상세: 과거 최초 적재 공고 제외, DONE 재호출 없음, FAILED 다음 회차 재시도. 자신의 상세 컬럼만 갱신하며 오류 때 저장된 배열을 지우지 않는다. 보조 세션 잠금은 긴 상세 sweep의 중첩을 막는다.
- V3는 기존 V1/V2를 수정하지 않고 수집 기간/월/페이지/정확한 수신 SN/고유 수 및 `api_attempt`만 추가한다.
- 영속 DB 기반 Prometheus 지표: 당일 시도/종류/재시도/미완료, 마지막 완전 수집, 연속 미완료, 24시간 선점/완료, 중단된 회차. 라벨에 키나 공고/구독 ID를 넣지 않는다.

## 검증

Windows x64, Java Temurin 21.0.13, Gradle 9.9.1, Docker 29.9.2, PostgreSQL 17 Testcontainers에서 fixture와 HTTP 대역만 사용했다. 실제 공식 데이터 API 호출은 0회다.

정식 #3 반영 전에는 수정 없는 #3 소스를 임시 Gradle init-script의 읽기 전용 sourceDir로 참조해 실제 반영기를 검증했다. 이 스크립트는 .gradle 안의 로컬 파일이며 커밋하지 않는다. 정식 반영 후 init-script 없이 전체 check/bootJar 결과를 아래에 추가한다.

검증 케이스:

- 두 실제 #4 런타임 인스턴스 → HTTP 대역 → #3 실제 반영기 → PostgreSQL 경로에서 동일 슬롯 외부 요청 1회, 예산 1회, 공고 반영 1회.
- 두 예산 인스턴스의 899 경합은 합계 900. 외부 트랜잭션 롤백 뒤 예약/시도 보존, KST 날짜 변경, 500 WARN 1회, 재시작 후 지표 조회.
- 실제 HTTP 대역의 키 1회 인코딩, 고정 정기 파라미터, 최초 적재 날짜/페이지, 5xx 제한 재시도, 429 대기, 리다이렉트 차단, 타임아웃, 예산 차단 시 요청 0회.
- 실제 표본에서 파생한 1,000행 페이지로 완료 월과 페이지를 건너뛰는 실패 재개, 날짜 오류 전체 페이지 미반영, totalCount 변경/페이지 간 중복 거부, 기존 공고 재반영 시 inserted=0이어도 정확한 unique_count, 빈 달과 기간 밖 행 거부.
- 페이지 반영 직후/체크포인트 직후 `AssertionError` 종료 주입 후 새 슬롯 재개: 데이터 유실/공고 중복/변경 기록 증가 없음. PostgreSQL 세션 강제 종료 테스트는 잠금의 자동 해제를 따로 검증한다.
- 실제 Spring ApplicationRunner의 로컬 적재 실행, 웹/스케줄 없이 컨텍스트 종료. 상세 실패와 재시도, 성공/과거 공고 미호출, 저장 배열 보존.

## 운영 문서와 결정

실행/재개/점검 절차는 [collector.md](../../docs/ops/collector.md). 보조 세션 잠금은 [검토 초안](sol-collector-lock-proposal.md)이며, 채택 ADR-0003을 수정하지 않았다. UNIQUE INSERT는 그대로 회차 소유권을 갖는다. 서로 다른 수동 슬롯/긴 상세 작업의 중첩을 줄이는 추가 잠금은 머지 전 Claude/agy의 교차 검토와 결정이 남는다.

## 미검증과 후속

- 공식 `apis.data.go.kr` 실제 봉투/상세/빈 달 결과는 아직 확인하지 않았다. 공개 데이터셋 Swagger만 읽어 상세 result/step/file 형태를 확인했다. 상세 테스트는 문서 계약에 맞춘 대역이며 실응답 fixture라고 주장하지 않는다.
- 실제 3년 적재의 건수/기간/오래된 코드와 품질 표본 검수는 환경 준비 후 기록한다. 현재 테스트 행 수는 합성/파생 fixture 조건의 검증치다.
- 500 WARN+Prometheus 지표는 구현했으나 실제 운영 알림 수신은 M3 Grafana→디스코드 연결에서 검증해야 한다. Healthchecks 계정과 실제 heartbeat 수신도 이번 작업에 포함하지 않았다. root가 이 경계를 수용했으며 별도 전송기를 중복 구축하지 않는다.
- 보조 세션 단절 직전에 이미 보낸 HTTP는 되돌릴 수 없다. 같은 슬롯의 UNIQUE 선점은 유지되지만 서로 다른 수동 슬롯과 네트워크 분할까지 외부 exactly-once라고 주장하지 않는다. 자세한 한계는 잠금 초안에 있다.
- #3 두 번째 교차 리뷰와 #3/#4 머지, 배포, DNS 변경은 수행하지 않았다.

## 커밋과 정식 합본 검증

구현 커밋/정식 #3 기반/전체 테스트/초안 PR 결과는 정식 반영 뒤 아래에 기록한다.

- 정식 기반: `925aa18e4f5bd6f426b540ac20deb070328239a5` (#3 v2). #4 독립 변경을 `d6245e4`로 먼저 보존한 뒤 rebase해 구현 커밋 `8db36bc`가 됐다. 전체 기간 고유 수 합집합 보강은 `b6361685cd04049327155df4edc71a445b3b44c3`으로 분리했다. 원본/다른 작업 브랜치는 수정하지 않았다.
- 정식 기반에서 **init-script 없이** `gradle --no-daemon clean check bootJar`, 이후 고유 수 보강 뒤 `gradle --no-daemon check bootJar`가 성공했다. 마지막 전체 실행의 JUnit XML 합계는 **59 tests / 0 failures / 0 skipped**다(#3 31건 + #4 28건). 월을 넘어 시작일이 바뀐 SN의 received=2/unique=1을 최초 실행과 완료 월 재개 모두 확인했다.
- 로컬 gitleaks 8.30.1로 `925aa18..HEAD`의 #4 구현 두 커밋을 검사해 누출 0건이었다.
- 초안 PR: [#11](https://github.com/Lcjam/gongchae-bell/pull/11), base `feat/#3-ingestion-core`. #3 main 반영 후 main으로 base 변경과 통합 검증이 필요하다. PR 생성은 GitHub 연결 도구로 수행했다. CI 결과는 [PR checks](https://github.com/Lcjam/gongchae-bell/pull/11/checks)에서 추적한다.
- 코어/웹 파일, V1/V2, 원본 사용자 변경에는 손대지 않았다. 머지/배포/DNS 변경 없음. root 및 두 번째 구현자 교차 검토 대기.
