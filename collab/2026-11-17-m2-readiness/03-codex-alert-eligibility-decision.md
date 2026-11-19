# #7 알림 자격 경계 종합 · codex

2026-11-17. [root](01-codex-alert-eligibility.md)·[agy](01-agy-alert-eligibility.md)·[Sol](01-sol-alert-eligibility.md)의 독립안, 각 [root](02-codex-alert-eligibility-review.md)·[agy](02-agy-alert-eligibility-review.md)·[Sol](02-sol-alert-eligibility-review.md) 교차 검토를 대조했다. root가 목록 일관성에 둔 초안을 능동 푸시와 기존 홈의 기준을 근거로 재고해 의견 차이를 해소했다. 다수결로 결정하지 않았다.

## #7 구현에 적용할 경계

1. 네 경로(정기 신규 outbox, 이메일 미발송 NEW, D-3, TX2 재확인)의 기본 자격은 **`p.status = 'OPEN' AND p.end_date >= :kstToday`**다. `kstToday`는 해당 검사 시점의 KST 날짜다. CHECK는 마감 확정 상태가 아니지만 알림에서는 제외하며, 기존 목록의 CHECK 표시/선택은 유지한다. 관심 조건·활성 상태·종류 검사도 그대로 필요하다.
2. Discord NEW는 정기 수집에서 실제 처음 INSERT된 SN만 동일 트랜잭션으로 기록한다. backfill·기존 공고 변경·CHECK/CLOSED 재등장으로 NEW를 새로 만들지 않는다. 이메일 NEW는 기존 non-backfill·활성화 이후 최초 관측·미중복 조건을 유지한다. 미발송 공고가 다시 OPEN이 되면 기존 규칙 안에서 최초 한 번 후보가 될 수 있다.
3. D-3는 생성과 TX2의 **R 전·R 뒤 HTTP 직전 모두** 현재 저장된 `end_date = :kstToday.plusDays(3)`이어야 한다. 과거 `batchDate + 3`만으로 재시도 자격을 유지하지 않는다. 자정 뒤 D-2가 됐으면 자격이 사라진다. `batch_key` 날짜는 본문의 결정성에 쓰며 현재 자격을 과거 날짜로 고정하는 근거가 아니다.
4. 재검사 대상 집합은 **`(SN, 종류)` 집합**이다. 같은 SN의 NEW와 DEADLINE은 별개 항목이다. unknown이 있으면 대상 집합 또는 전체 payload hash가 달라질 때 원래 item을 보존하고 UNCERTAIN 종결한다. 본문이 우연히 같다는 이유로 탈락한 DEADLINE을 무시하지 않는다. unknown이 없을 때만 기존 탈락 항목 삭제/실패 항목 반환 규칙을 적용한다.
5. R 전 탈락을 발견하면 새 예약/HTTP가 없다. **R 뒤 자정을 넘겨 탈락한 경우** 이미 커밋한 예약은 환불하지 않고 NOT_SENT 등 확실한 비전송 결과를 기록하며 HTTP를 막는다. 기존 펜싱·잠금 순서·최대 3시도·23시간·unknown 보존을 완화하지 않는다.

## 기록할 한계와 검증

OPEN-only는 기존 PRD에 명시돼 있던 술어가 아니라 이번에 좁힌 MVP 알림 경계다. 원천의 일시 누락으로 CHECK가 된 유효 공고의 그날 D-3가 빠질 수 있다. 조기 철회 공고/일시 누락의 실제 빈도는 측정하지 않았고 비교 수치를 만들지 않는다. 새 상태 옵션·추가 만회·재등장 알림·lookback은 넣지 않는다.

#7에서는 CHECK 제외, 재등장 NEW 미생성, 미발송 NEW의 첫 선택, 자정 전후 D-3, 같은 SN의 두 종류 중 하나만 탈락, R 뒤 자정 경과 때 예약 보존/HTTP 0회를 대역·격리 PG로 검증한다. 아직 구현/실행한 결과가 아니다. [준비표](agy-issue7-handoff.md)의 자정 경계 정정을 반영하고, 최종 소스 문서/테스트는 #6 수정본 재검수·머지 뒤 #7 작성자가 반영한다.
