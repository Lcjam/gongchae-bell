# #4 보조 세션 잠금 제안 · sol

상태: **[ADR-0016](../../docs/adr/0016-supplemental-session-lock.md)으로 승격했다(2026-11-15, codex·agy 수용).** 이 파일은 검토 기록으로 남긴다. 결정의 원본은 ADR-0016이다.

(승격 전 상태: 교차 검토 전 초안. 채택 ADR-0003을 대체하거나 이미 승인된 결정으로 쓰지 않는다.)
2026-11-15 claude 수정: codex 11 결정 1번과 PR #11 claude 리뷰 M3에 따라 연결 생명주기를 바꿨다(아래 "구현과 연결 생명주기"). 수정자가 claude이므로 claude의 PR #11 리뷰는 이 초안의 독립 검토로 세지 않는다. agy 교차 검토가 남아 있다.

## 유지하는 회차 계약

정기 수집은 KST 시간 슬롯의 `collect_run(kind,slot)` UNIQUE INSERT에 성공한 앱만 HTTP를 호출한다. 수동 최초 적재 역시 지정한 명령 슬롯, 월 슬롯, 페이지 슬롯을 각각 UNIQUE INSERT로 선점한다. 이미 사용한 슬롯은 다시 실행하지 않는다. 실패한 명령은 새 수동 슬롯으로 재개하며, 완료한 월/페이지 메타데이터를 조회해 건너뛴다. 회차 결과가 null인 기존 행을 자동으로 훔치지 않는다.

## 추가 보호가 필요한 경우

서로 다른 수동 슬롯의 최초 적재가 같은 기간을 처리하거나, 상세 sweep이 다음 정기 시간까지 길어지면 회차 UNIQUE만으로는 서로 다른 슬롯의 중첩을 막지 못한다. 보조 잠금은 이 두 경우의 동시 실행만 줄인다. 기존 회차 키와 기록은 그대로 둔다.

대안은 DB lease+fence, 실행 중 상태용 테이블/회수 명령, 전용 세션 advisory lock이다. lease 만료는 HTTP가 진행 중인 앱을 새 앱이 대신할 수 있고 추가 상태/회수 정책이 필요하다. 현재 단일 PostgreSQL·직접 연결 배포에서 전용 세션 잠금이 가장 작다.

## 구현과 연결 생명주기

`AdvisoryLease`가 **Hikari 풀 밖에서 전용 물리 연결**을 하나 연다(`JdbcConnectionDetails`의 URL·계정으로 만든 `DriverManagerDataSource`, 호출마다 새 연결). 트랜잭션 밖에서 `pg_try_advisory_lock(bigint)`를 실행한다. 최초 적재 전체는 47111001, 상세 sweep 전체는 47111002를 쓴다. 선점 실패 시 연결을 닫고 외부 호출 없이 돌아간다. 매 페이지/상세 요청 전 같은 연결이 살아 있는지 확인한다.

해제는 같은 연결로 `pg_advisory_unlock`을 실행한 뒤 **결과와 관계없이 그 물리 연결을 닫는다.** unlock이 SQL 오류로 실패하거나 false를 돌려줘도(예: 문 타임아웃이나 취소로 세션은 살아 있는 경우) 연결을 닫으면 백엔드가 끝나고 PostgreSQL이 세션 잠금을 푼다. 풀에서 빌린 연결이 아니므로 잠금을 쥔 세션이 풀로 돌아가는 경로 자체가 없다. 이때 호출자에게는 `DATABASE_FAILURE`를 알린다. 프로세스 종료로 DB 세션이 닫혀도 PostgreSQL이 자동 해제한다. 잠금을 기다리는 큐나 TTL은 없다. 이 키는 다른 기능에서 재사용하지 않는다.

잠금당 DB 연결이 하나 더 필요하고, 이 연결은 Hikari 최대 크기에 포함되지 않는다(앱당 최대 2개: 최초 적재 명령 1, 상세 sweep 1). PostgreSQL `max_connections`에서 그만큼을 남긴다. PgBouncer transaction pooling에는 이 방식을 사용할 수 없다(현재 설계에 없음). DB가 다른 호스트로 옮겨 반쯤 열린 TCP가 생길 수 있으면 `tcp_keepalives_idle` 같은 keepalive 설정이 필요하다(서버가 끊긴 세션을 알아챌 때까지 잠금이 남는다).

## 검증

`RuntimeDatabaseTest.supplementalSessionLockExcludesAnotherOwnerAndReleasesAfterCloseAndBackendDeath`는 동시 소유 차단, 정상 close 뒤 재선점, `pg_terminate_backend`로 세션을 끊은 뒤 새 실행의 선점을 검증한다. `leaseClosesItsPhysicalConnectionWhenUnlockFailsOnALiveSessionSoAnotherOwnerAcquires`는 세션은 살아 있고 unlock 문만 실패하는 대역(SQLState 57014)과 실제 PostgreSQL 세션을 조합해, close가 `DATABASE_FAILURE`를 알리고 소유 백엔드가 `pg_stat_activity`에서 사라진 뒤 다른 소유자가 재선점함을 검증한다. `CollectorWiringIntegrationTest.leaseConnectionIsNotBorrowedFromThePool`은 실제 Spring 배선에서 잠금을 쥐는 동안 Hikari 활성 연결 수가 늘지 않음을 확인한다. 별도 정기 수집 통합 테스트는 두 실제 런타임 객체가 같은 시간 슬롯을 실행해 HTTP 대역 호출 1회/예산 1회/실제 #3 반영 1회를 검증한다.

## 한계와 검토 사항

세션 단절 직전에 이미 보낸 외부 HTTP는 취소되거나 되돌아가지 않는다. 새 수동 슬롯에서 재개할 때 진행 중이던 요청과 겹칠 가능성까지 exactly-once라고 주장하지 않는다. 재반영은 코어의 멱등성으로 처리하고 모든 HTTP 예약은 각각 예산에 남는다. 같은 회차 슬롯의 중복 실행은 UNIQUE INSERT가 계속 막는다. 오래된 null 결과 행은 실행 중 종료의 기록으로 보존하고 미완료 지표에 노출한다.

머지 전 Claude/agy가 연결 반환·세션 단절·재개 정책을 교차 검토하고 최종 승인되면 별도 ADR에 옮긴다.
