# PR28 M2 발송 관측 통합 검수 · Codex

2026-11-19 비작성자 root Codex. 작성자는 추가agy1이다. 대상 HEAD `800d114713aa7090f7955db6c04b4a09162d443c`, 기준 main32다.

## 판정: 수용 (다른 비작성자 검수 대기)

기존 M2 통합 계약의 소비자 구현 범위에서 차단 결함을 찾지 못했다. 별도 Claude가 같은 HEAD를 검수 중이다. 실제 운영 규칙 적용·외부 수신·heartbeat·G7 기준선과 #15 전체 완료는 별도다.

## 직접 확인

- source 7파일의 변경 범위, 규칙·promtool 시험·Grafana 16개 신규/대체 쿼리·실제 exporter 시험·대역e2e diff를 읽었다. production Java source는 바뀌지 않았으며 dynamic meter helper는 test source에만 있다.
- DB 공유 게이지는 max로 두 색을 먼저 중복 제거한다. 실패/불명은 max by(kind) 뒤 sum이며, 서로 다른 kind는 합산한다. backlog>0 15분·FAILED>=5·UNCERTAIN>0의 기존 임계/지속 조건을 유지했다.
- counter/timer는 각 프로세스가 실제로 기록한 서로 다른 표본이므로 sum/increase·sum(rate(sum))/sum(rate(count))로 합친다. DB 게이지의 max 중복 제거를 잘못 적용하지 않았다. 분모를 1로 clamp해서 저빈도 평균을 왜곡하지 않는다. 샘플 없는 구간의 NaN/빈값과 프로세스 종료/재시작은 Prometheus 표본의 한계다.
- job 지연과 NEW 수집 지연은 분모·제목·단위가 다르며 count/sum/max만 읽는다. 존재하지 않는 M2 bucket/p95를 참조하지 않고 기존 HTTP p95 계약도 유지했다. promtool의 두 색 가중 평균7.5초·NEW4초 합성 검증을 대조했다.
- 규칙 시험은 15분 미만/이상·복구, 두 색 raw합8이지만 중복제거4라 실패알람 미발동, 중복제거5에서 발동, UNCERTAIN0/1·미노출을 구분한다. e2e는 실제 앱의 SQL게이지를 격리 DB에서4→5로 올려 실패와 불명 알람이 대역에 발동/해소되는 것을 검증한다. backlog15분은 promtool, 실제e2e에서는 세 게이지 노출을 확인하므로 실제15분 end-to-end 발동까지 실행했다고 쓰지 않는다.
- 실제 exporter contract 시험이 `alerts_*` 참조를 모두 열거하고 동적 timer/counter를 생성해 scrape를 대조한다. PII 미노출과 synthetic 수신자/UUID 비노출을 확인한다.
- 최종 CI44509241357의 test·deploy-verify·backup-verify·secrets, Monitoring44509241026의 rules·compose-integration·alert-e2e 전부 SUCCESS를 직접 조회했다. author의 로컬168 tests·대역실측은 보고서 근거이며 root가 같은 전체시험을 다시 실행한 수치가 아니다.

## 별도 producer 후속

소비자 쿼리 검수 중 기존 AlertDispatcher의 성공지연 기록이 SQL 갱신 뒤이면서 외곽 TX commit 전임을 다시 확인했다. 이전 'finishAsync 커밋 성공 뒤' 설명은 정확하지 않았다. 성공SENT 분모에 대한 실제 commit실패 영향을 원작성자에게 재현·필요 수정하도록 `agy-delivery-metric-commit-request.md`로 배정했다. 이는 이번 PR에서 바꾼 소비자 쿼리의 결함으로 세지 않으며 #7 후속은 별도로 검수한다. 실제 운영 알림은 계속false다.
