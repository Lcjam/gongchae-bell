# ADR-0003 스케줄 중복 방지: 회차 유니크 INSERT

- **상태**: 채택 (2026-11-08)
- **맥락**: blue/green 배포 중에는 두 앱의 `@Scheduled`가 동시에 돈다. 같은 수집이나 묶음 발송이 두 번 실행되면 안 된다 (PRD FR-C5).
- **선택지**
  - ShedLock: 의존성이 하나 늘고, 실행이 `lockAtMostFor`를 넘으면 중복될 수 있다 (공식 README 경고)
  - advisory xact lock: 잠금을 얻은 트랜잭션이 끝나면 풀려서 긴 작업을 못 지킨다
  - 세션 advisory lock: 같은 연결로 해제를 보장해야 한다
  - 자체 lease와 fence: 구현량이 크다
  - **회차 유니크 INSERT**
- **결정**: 작업마다 회차 키(`slot`)를 정한다. 정기 수집은 매시 시각, 묶음과 마감 알림은 날짜, 최초 적재는 월이다. 실행을 시작할 때 다음을 실행한다.
  ```sql
  INSERT INTO collect_run(kind, slot, started_at) VALUES (:kind, :slot, now())
  ON CONFLICT (kind, slot) DO NOTHING RETURNING id;
  ```
  행이 반환된 앱만 실행한다. 끝나면 `result`와 건수를 갱신한다.
- **결과**
  - 잠금 라이브러리가 필요 없고, 실행 기록이 곧 선점 기록이 된다
  - 실행 중에 죽으면 그 회차는 `result IS NULL`로 남고, 다음 회차가 이어 받는다
  - 이메일 묶음처럼 놓치면 안 되는 일일 작업은 **만회**한다: 매분 "오늘 슬롯이 없고 지정 시각이 지났는가"를 확인해서 당일 1회 실행한다. 마감 임박은 실행일 기준 D-3만 보낸다
  - 수동 재실행은 운영 명령으로 별도 슬롯 키를 쓴다
