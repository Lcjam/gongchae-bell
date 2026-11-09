# ADR-0006 관측: Prometheus + Grafana + 외부 감시

- **상태**: 채택 (2026-11-08)
- **맥락**: PRD는 HTTP p95, 오류율, 수집과 발송 상태, 호출 예산, 경보 수신, 자원 실측을 요구한다. 방문 측정은 서버 측 집계로 한다(U6).
- **선택지**: 직접 운영(Prometheus + Grafana) / 외부 무료 관측 서비스 / node-exporter와 Loki 추가
- **결정**
  - **Prometheus + Grafana를 내부 전용으로 운영한다** (Grafana 경보 → 운영 디스코드). Prometheus 보존 기간을 길게(초기 1년) 잡아 방문 집계를 장기 보관한다. 라벨에는 이메일, 토큰, 구독 ID를 넣지 않는다
  - 외부: UptimeRobot(웹 HTTP, 무료 50개·5분), Healthchecks.io(수집, 묶음, 백업, NAS 가져오기 heartbeat, 무료 20개)
  - node-exporter, Loki, Alertmanager는 넣지 않는다. 호스트 자원은 정해진 시점에 `docker stats`와 `df`로 실측해 기록한다
- **결과**: 외부 계정에 지표를 맡기지 않고 보존 기간을 직접 통제한다. 대신 메모리를 쓴다(실측으로 확인). 상시 디스크 경보가 필요해지면 node-exporter를 추가한다.
