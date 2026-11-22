# 성능·자원 측정 합의와 구현 순서 · Codex

2026-11-20. 세 독립안과 02 세 교차검토를 읽고 실제 JobSearchCriteria/CatalogMetrics/G4/deploy source를 비교한 종합이다. 제안된 숫자는 측정 목표가 아니라 최초 실행 한계다. PRD의 실제 수집·bluegreen·훈련 요구를 완료로 줄여 잡지 않는다.

## 지금 구현할 측정 도구

프로덕션 도구는 표준라이브러리 Python `deploy/perf/perf-probe.py`와 호스트 `deploy/perf/sample-resources.sh` 두 개를 기본으로 한다. 필요한 의미 있는 로컬 시험과 실행 문서는 별도다. 새 Actions workflow·외부 서비스·부하 도구 의존성은 필요 없다.

- 공개 HTTPS를 VM 밖 한 고정 장소(최초는 사용자 PC)에서 읽기 전용 GET으로 측정한다. 다섯 화면군 home/list/filter/detail/institution, 상세5·기관5와 제한된 필터 대표 경로를 실행 전 고정한다. source redirect·POST/구독 경로를 호출하지 않는다. URL마다 screen group과 case를 남겨 서로 다른 SQL 조건을 조용히 같은 조건으로 보고하지 않는다.
- `status=open/check/closed/all`, `sort=deadline/begin`, `career=newcomer/newonly/career`의 실제 소문자 허용값을 사용한다. 사전 검증은 응답의 selected filter·q value·실제 페이지번호·본문핵심을 확인한다. sort는 결과 건수가 같을 수 있으므로 '기본값과 count가 다름'을 일반 규칙으로 쓰지 않는다. 실제 적용되지 않은 필터/유효하지 않은sn·기관은 측정을 진행하지 않는다.
- 워커마다 HTTPSConnection을 재사용하는 closed-loop, 응답 body는 소모한 뒤 저장하지 않는다. reconnect/reuse 관측을 기록한다. 오류 요청은 자동 재시도하지 않고 오류로 남기며 다음 요청에서 연결을 복구한다. `X-Gongchae-Internal: 1`, gongchae-probe UA를 모든 요청에 고정한다.
- warmup과 측정 단계를 원자료에서 구별한다. 분모는 보낸 모든 요청, 성공200·비200(3xx포함)·timeout·연결/TLS를 따로 집계한다. 성공표본의 n/p95와 전체오류율을 기본 보고하고 오류가 있으면 전체 경과시간 p95도 별도로 쓴다. timeout elapsed는 잘린 관측이다. nearest-rank를 쓰고 n<20에는 p95 대신 n/max를 표시한다. case별 n과 화면군별 n을 모두 남긴다.
- S1(C1)은 화면군당40요청·최대180초, S2(C4)는120초·최대1500요청이다. 결정적 라운드로빈으로 케이스를 순환하며 각 화면군 n>=20 충족 여부를 보고한다. 최대시간/요청 수를 넘어 분모를 강제로 채우지 않는다. 하루 측정 부하 총15분 이내는 운영 실행 기록으로 지키며 불필요한 영속 quota 서비스를 만들지 않는다.
- HTTP 자동 중단은 최근30초 오류율>2%, 5xx누적3건, 연속timeout2회, stop-file/SIGINT다. 원자료를 마무리하고 ABORTED를 성공측정으로 숨기지 않는다. 기존 원자료를 덮지 않으며 조건 파일에 SHA·digest·색·실제데이터건수/flags·시작끝UTC·장소·RTT·URL목록을 남긴다. 알 수 없는 값은 미확인으로 표기한다.
- 자원은 호스트5초 monotonic 예약으로 `/proc/stat`·meminfo·loadavg·df와 docker stats 원문/시각을 기록한다. 예정/실제시각·건너뛴표본을 남기고 명령 뒤 sleep5로간격을틀리게쓰거나 밀린표본을 몰아 찍지 않는다. docker CPU코어기준과 MemUsage를 RSS와 구별한다. du/docker system df는 전후만 한다. MemAvailable1536MiB미만·disk80%초과·RestartCount증가를 경고해 운영자가 부하를 즉시 중단할 수 있게 한다. 샘플러가 앱을 재시작하거나 DB를 변경하지 않는다.

## 실제 측정과 남은 완료 범위

도구 검수와 CI 뒤 평상시/무부하·제한부하부터 실제 측정한다. 실제 collector가 꺼진 상태는 수집 중이라고 쓰지 않는다. 정기 수집 활성의 선행 경보 게이트를 통과한 다음 정상 :07 슬롯 한 회를 관측한다. fixture 수집은 별도 근거이며 실제API를 측정 때문에 반복호출하지 않는다. bluegreen은 다음 정상 승인 배포에 sampler를 붙여 JVM 중첩을 관측한다. 트래픽이 두 앱에 나뉜다는 주장은 하지 않는다.

## 실패 G4 훈련은 별도 준비/실행 단계

세 검토에서 동일 G4 고정 URL/창/최소요청과 실제 wait→g4_rc→abort를 유지해야 한다는 점에 합의했다. 사후 action=rollback은 이 증거가 아니다. 실제 새앱 정지502 노출은 runner관측실패와 영향·증거가 다르다. 지금은 두 파일 성능 도구만 구현하고 어느 훈련도 실행하지 않는다.

후속 runner-only 구체안은 기존 CURL_BIN을 단계 안에서만 사용하고 dispatch전용 기본off·가짜PASS불가·주입식별 로그/context·실제curl과별도정상감시·고정route/15초/최소15회/1초·source밖runnerwrapper를 검증해야 한다. rc비영이면 status599가 버려지는 실제분기를 반영한다. SHA태그 재빌드digest와 배포범위를 기록한다. 실제502안은 pending/lock만의 불충분촉발 대신 실제 switch성공과복구실패상태배제, hard상한/회복을 검토한다. concrete검수안 뒤 실행방식을 정하고 필요한 사용자결정을 받는다.

원자료·집계·근거 기반 목표, 자원한계와3자원인/재발방지→훈련포스트모템은 계속 남은 #18 완료기준이다. 아직 실측이나 훈련 완료를 주장하지 않는다.
