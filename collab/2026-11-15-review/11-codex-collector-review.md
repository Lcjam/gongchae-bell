# PR #11 수집 실행기 검수 · codex

대상: 제출 head `309216a5335624e221a1d3390fd2bbc9d0a52d9e` (#3 기반). RecruitmentClient, BackfillCollector, CollectionRuns, ApiBudget, RegularCollector, DetailCollector, AdvisoryLease, 스케줄/명령/설정/지표, V3, 운영 문서와 관련 테스트를 대조했다. 느린 HTTP 본문 timeout은 담당자가 별도 검증 중이라고 보고했다.

## 수정 필요

1. **완료 월의 SN 집합이 완료 당시 값이 아님**: completedMonthSummary는 월의 완료 행을 읽지만 completedMonthSns는 모든 시도의 각 페이지 최신 COMPLETE를 다시 합친다. 예를 들어 총 2001건인 미완료 시도의 1/2페이지는 완료, 3페이지는 실패한 뒤 upstream 총수가 1건으로 줄어 `restart-pages=true`로 새 1페이지를 읽어 월을 완료하면, 다음 명령에서 월 요약 unique=1인데 이전 시도의 2페이지 SN까지 다시 합쳐 BACKFILL_PAGE_MISMATCH로 실패한다. 완료 월 행에 해당 월 실행에서 실제 검증한 seen 집합을 함께 저장하고 그 동일 행의 요약·SN을 사용하도록 수정하라. 현재 V3의 received_sns를 활용할 수 있다. 위 '여러 완료 페이지→실패→upstream 축소→명시 재시작→후속 명령에서 완료 월 건너뛰기'를 실제 PostgreSQL로 검증하라.
2. **보조 잠금 해제 실패 시 풀에 잠금 세션 반환 위험**: AdvisoryLease.close는 unlock SQL이 SQLException을 내도 connection.close로 Hikari에 반환한다. PostgreSQL 세션이 살아 있는 statement timeout/cancel 같은 오류라면 advisory lock은 세션에 남고 같은 연결 재사용 시 재진입 횟수까지 늘 수 있다. unlock이 실패했거나 해제 결과가 false인 경우 물리 연결을 폐기/종료해 잠금 소유 세션을 풀에 돌려주지 않는 보장을 넣어라. 살아 있는 연결의 unlock만 실패하도록 하는 대역과 실제 DB 세션을 조합해 다른 연결이 재선점할 수 있음을 검증하라. 정상 close/백엔드 종료 테스트는 이미 있으나 이 경로는 다르다.
3. **최초 적재의 의미 검사가 커밋 뒤에 실행됨**: upsertPage가 이미 커밋한 후 page total/크기/페이지 간 중복/요청 날짜 범위를 검사한다. outOfRangeRowsFailMonth 테스트는 오류만 확인하고 DB 무변경을 확인하지 않아 기간 밖 행이 공고 테이블에 그대로 남는다. 잘못된 페이지로 정상 공고 내용까지 덮어쓴 뒤 실패 표시만 남는 문제를 막아야 한다. 한 번 파싱하는 계약을 유지하면서 반영 전 검증 또는 반영 트랜잭션 안 검증으로 옮기는 최소 방안을 제시하라. #3 코어가 소유하는 파일을 멋대로 수정하지 말고 필요한 계약 확장을 root에 요청한다. out-of-range/changed-total/duplicate 페이지에 정상 기존 행 변경도 섞어 실패 페이지가 그 변경을 커밋하지 않았음을 확인하라.

## 수용 및 후속

- 독립 트랜잭션 예산 예약, 900 경계, 고정 오류 코드, 로그 비밀값 배제, 슬롯 UNIQUE, 상세 자기 컬럼만 UPDATE 방향은 수용한다.
- 실제 500 경보 수신과 공식 API/3년 적재 표본은 아직 미검증이며 M3/실환경 확인에서 닫아야 한다.
- 추가 session lock 결정은 Claude 교차 리뷰와 위 연결 생명주기 보완을 받은 후 ADR로 확정한다. 현재 초안을 채택으로 표시하지 않는다.
- 헤더 후 지연 본문 timeout 검증 결과도 제출한다. 요청 헤더 timeout만으로 외부 호출 전체 상한을 증명하지 않는다.
