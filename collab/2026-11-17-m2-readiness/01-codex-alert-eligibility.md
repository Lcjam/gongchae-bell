# 알림의 OPEN/CHECK 경계 독립 의견 · codex

2026-11-17. 새 agy/Sol 독립안은 읽기 전에 작성했다. 기존 #7 준비표의 미정 경계만 다룬다. 코드·테스트·설정은 변경하지 않았다.

## 기존 근거

불변 main `000dce5`의 `CatalogRepository.java:145-149`는 목록의 open/default를 `status IN ('OPEN','CHECK') AND end_date >= :today`로 취급한다. `PostingIngestor.java:173-180`에서 완전 snapshot 누락은 마감 전이면 CHECK, 마감 후이면 CLOSED다. CHECK는 명시적인 마감 관측과 구별한 불확실 상태다. PRD FR-C4는 이 구별을 유지하고 FR-A4는 현재 조건에 맞는 새 공고/마감 임박을 전송한다. `12/13/14`는 현재 target-set 재확인과 unknown 본문/키 규칙을 고정했지만 상태 경계를 별도 열거하지 않았다.

## 제안

- 알림 대상의 공통 유효 상태를 목록의 open과 같은 `OPEN 또는 CHECK`, KST 오늘 이상 마감으로 맞춘다. CLOSED·지난 마감·관심 조건 불일치·종류 off·비활성 구독은 제외한다. CHECK를 마감 확인 상태로 해석하지 않는다.
- 최초 Discord NEW outbox는 **정기 수집에서 실제로 처음 INSERT된 SN**에만 만든다. 이 경로의 새 공고는 OPEN/CLOSED로 판정되므로 정상적인 새 CHECK INSERT를 기대하지 않으며, CLOSED는 제외한다. 다시 나타난 기존 CHECK→OPEN을 새 SN으로 취급하지 않는다.
- 이메일 미발송 NEW는 기존 활성화 시각 하한·non-backfill·중복 item·현재 조건을 유지하면서 공통 유효 상태를 쓴다. 처음 INSERT 이후 하루 안에 한 번 snapshot에서 누락됐다는 이유만으로 미발송 NEW가 사라지지 않는다.
- D-3는 batch 날짜+3과 현재 조건/종류를 검사하며 공통 유효 상태를 쓴다. NEW와 달리 backfill/활성화 전 관측 공고도 현재 D-3면 대상이 될 수 있다.
- TX2에서도 같은 자격 술어를 재검사한다. OPEN→CHECK만 바뀌고 조건·공고 내용·마감·종류가 같으면 target set은 같다. unknown 시도 뒤라도 전체 payload hash가 같을 때만 기존 키로 재시도한다. CHECK 경고 등 실제 본문이 바뀌면 target이 같더라도 hash 불일치로 UNCERTAIN 종결한다. 기존 unknown/target 변경 차단을 완화하지 않는다.

## 반례와 비용

OPEN만 허용하면 07:07에 본 NEW가 08:00 직전 한 번 누락된 경우 이메일에서 탈락할 수 있고, CHECK인 D-3가 그날 내내 재등장하지 않으면 유일한 D-3 기회를 잃는다. 반대로 CHECK를 포함하면 실제로 원천에서 철회됐지만 명시적 종료/마감은 관측 못 한 공고를 안내할 수 있다. 목록도 같은 불확실성을 표시하므로 알림에서 상태를 확정적으로 표현하지 않고 상세/원문 확인 동선을 유지하는 편이 기존 제품의 의미와 일치한다고 본다.

이 제안은 새 추적·재알림·임의 lookback을 추가하지 않는다. 독립 검토와 교차 검토 뒤 작은 기준 한 항목으로 종합하며, 이 문서만으로 새 코드 계약을 확정하지 않는다.
