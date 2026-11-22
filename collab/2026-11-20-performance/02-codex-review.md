# 성능·자원 독립안 교차 검토 · Codex

2026-11-20. root `01-codex.md` 작성 뒤 `01-agy.md`와 `01-claude.md`를 모두 읽고 현재 실제 CatalogMetrics·JobSearchCriteria·deploy workflow·G4 source와 비교했다. 아직 측정/훈련 실행은 하지 않았다.

## 수용과 자기 정정

- Python 표준 라이브러리, VM 밖 HTTP 요청·VM 안 자원 표본, 경로별 분모·nearest-rank·raw 보존, 실제/fixture 수집 분리, 정상 배포 overlap 관측은 세 안에 공통이다. 이 범위로 먼저 작은 하네스를 만들 수 있다.
- **자기 정정:** root 독립안은 부하가 page-view 통계를 바꾸는 점을 기록하자고만 했다. 실제 `CatalogMetrics.classifyTraffic`는 `X-Gongchae-Internal: 1` 및 gongchae-probe UA를 internal로 분리한다. agy의 제안을 수용해 둘을 고정하고 user 카운터 오염을 피한다. HTTP 서버 지표에는 internal도 포함되므로 전체 관측과 합성 표본의 경계는 계속 명시한다.
- CLAUDE의 keep-alive closed-loop를 수용하되 결과가 포화 상태의 open-loop 도착률 성능을 뜻하지 않음을 적는다. 워커/연결별 재사용을 보장하고 실제 사용한 요청 수를 분모로 쓴다. URL discovery용 반복 크롤링을 측정에 섞지 않는다.

## 수정·축소 요구

1. **agy URL 예시는 실제 허용값으로 교정해야 한다.** `status=ALL/OPEN`과 `sort=start`는 현재 `JobSearchCriteria` 허용값과 비교해서 써야 한다. 잘못된 값이 정상 기본값으로 정규화되면 '전체/시작순 검색'을 측정했다고 잘못 보고할 수 있다. 구현자는 응답의 적용 필터까지 검증하고 숫자/코드는 실행 전 source와 DB/공개 화면에서 고정한다. 측정용 query가 실제로 적용됐는지 의미 있는 fixture 검증이 필요하다.
2. **최소 하네스는 두 파일 정도로 충분하다.** HTTP+집계를 한 Python에 두고 자원 sampler만 별도 셸로 둘 수 있다. 처음부터 50상세/20기관·8/10동시성·다중 runner·장시간 반복·새 Actions workflow까지 모두 만들 필요는 없다. 다섯 화면군과 제한된 대표 경로, concurrency1·4를 먼저 준비하고 제안된 총요청/최대시간과 중단 기준을 구현 리뷰에서 고정한다. 이 숫자는 목표가 아닌 설정 제안이다.
3. **오류 표본 처리:** 분모와 timeout·비200·네트워크 오류는 모두 남기고 경로별 지연과 성공 표본 지연을 구별한다. 실패를 지우거나 10초 timeout 표본을 성공 p95로 섞어 의미를 흐리지 않는다. p95 정밀도보다 표본 수와 집계법이 우선이다.
4. **자원 sampler는 기준선 자체를 왜곡하지 않아야 한다.** `vmstat 1 2`는 이미 2초 소비하므로 '5초 간격'을 명령 뒤 sleep5로 단순 구현하면 실제7초 이상이다. monotonic 기준 예정 시각/실제시각을 남긴다. docker stats도 비용이 있으므로 측정기간과 매 표본 timestamp를 기록한다. du와 DB 대량 해시는 구간 전후만 한다. docker 메모리를 RSS라고 자동으로 부르지 않는다(표시값/캐시 처리 기준 명시).
5. **수집 구간:** 실제 정상 정기 슬롯 한 회 관측을 우선한다. 운영 collector를 켤 선행 경보 게이트가 완료되기 전엔 평상시만 먼저 측정하고 수집 구간은 미완료로 둔다. fixture 복제본이 필요하면 실제 VM 관측과 별개 근거다. 측정을 위해 추가 실제 API 호출을 만들지 않는다.

## 실패 G4 훈련 쟁점

- Claude안은 새 색 stop으로 실제502를 노출하고 외부 G4 뒤 abort를 실제로 밟는다. 그러나 upstream 변경 뒤 고정5초 sleep은 post_switch_check 완료와 G4 post-window 시작의 증거가 아니다. hook/판정 단계의 실제 기록과 deploy_id를 기준으로 해야 한다. 실제 사용자가 받는 장애 시간은15초 창+ssh/abort이고 단일 VM 복구 실패 때 더 길어진다.
- agy A와 root는 runner의 판정 입력만 통제해 공개 경로를 유지하는 쪽을 선호한다. **실제로 존재하지 않는 G4_HOOK을 있다고 가정하거나 /drill 경로404를 원래 /·/jobs 고정 계약 시험이라고 쓰면 안 된다.** 현재 workflow의 실제 wait→g4_rc→abort 경로와 원래 URL/창/최소요청을 보존하면서 runner에서만 실패를 주는 구체적인 방법과 격리 검증이 먼저 필요하다.
- 실제 앱 장애502 탐지와 runner 관측 실패로 인한 abort는 다른 훈련이다. 후자를 한다면 그 한계를 포스트모템에 그대로 적고 앱 장애 탐지를 증명했다고 쓰지 않는다. 어느 쪽을 실행할지는 concrete 준비안·3자 검토 뒤 결정한다. 사용자에게 아직 실행/장애노출 승인을 구하지 않는다.
- `action=rollback` 성공은 사후 복귀이고 G4_FAIL→abort 증거가 아니다. runbook의 두 개를 동등하게 읽히게 하는 문구는 후속 문서에서 바로잡아야 한다.

이번 교차 검토는 source·설계 읽기와 문서 작성뿐이다. 운영·키·env·provider·구현 설정을 변경하지 않았다.
