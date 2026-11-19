# PR13 재검수 결과 · codex

2026-11-18. 검수한 불변 head는 `7fa3f385773b3bcf2cb76b7a8f2766c1867cc432`, 코드 커밋은 `af62adb2065117c6f3d0d099c303e3e3def6652d`다. 로컬 작업tree의 변경 없음과 GitHub PR13의 같은 head·open/draft를 확인했다. 최종 head의 CI `44100316401` 및 코드 CI `44099813628`에서 test/check bootJar·secrets 성공을 직접 조회했다. 보고된 138개 통과는 실제 provider를 호출하지 않는 격리 테스트 결과다.

## 판정

**C4 추가 수정 후 재검수.** C1의 부모 KEY SHARE, C2의 누락 파일 거부와 사전 빈 파일 준비, C3의 공유 잠금 안 directory force, C5의 제한된 DNS 검사와 남은 HTTP 시간, C6의 긴 Retry-After 보존은 소스에서 수용한다. C4의 여러 행 잠금과 R 뒤 검사도 개선됐으며 다음 경로가 남아 있다.

## C4-a 조건 검증이 예산 시작보다 먼저 실행됨

`SubscriptionController.java:76,102`는 `service.conditions`를 먼저 부른다. `SubscriptionService.java:90`의 `catalog.findFilterOptions()` SQL에는 예산과 statement 제한이 없고, `sender.start()`는 이후 `subscribeEmail:115`·`registerDiscord:343`에서 실행된다. 정상 진입점의 DB/풀 대기와 SQL 실행이 요청 예산에서 빠진다. 조건 조회가 오래 걸린 뒤 발송기가 새 25초를 받는 경로다.

예산을 해당 DB 조회 전에 시작하고 조건 검증부터 실제 보내기와 정리까지 같은 단조 시계의 남은 시간을 사용해야 한다. 잘못된 입력을 렌더링할 때 반복하는 필터 조회도 점검한다. 예산을 서비스 메서드 내부로 재정의하는 문서 수정만으로 끝내면 원계약 13/14와 원 C4를 충족하지 못한다.

## C4-b R 안의 연속 SQL이 처음 정한 제한 시간을 재사용함

`DeliveryLedger.reserve:93`은 `waitLimit.get()`를 한 번 읽는다. 이후 `notification UPDATE`(94~98), 전역 `rate_limit upsert`(100), `attempt INSERT`(104~106)가 그때의 같은 statement_timeout을 각각 받는다. 첫 문장의 잠금 대기에서 시간이 지나도 다음 문장은 이전 몫으로 다시 대기할 수 있다. R 뒤 HTTP 검사로 전송을 막아도 이미 누적 DB 예산을 초과해 R을 커밋할 수 있다.

각 대기 가능한 문장을 시작하기 전에 현재 남은 R 몫을 다시 확인하거나, 같은 효과를 갖는 트랜잭션 전체 제한을 적용해야 한다. 예를 들어 notification 잠금 대기 뒤 별도의 SEND_TOTAL 행 대기가 이어지는 사례로 검증한다. signup의 기존 PENDING 갱신·createSync와 결과 기록 단계도 같은 기준으로 점검한다. 기존 원자 예약·펜싱·시도/카운터 환불 없음은 유지한다.

## C4-c 풀 대기를 예산에서 제외한 새 설명은 계약을 축소함

`SyncSender.java:27-29`와 수정 결과 문서는 Hikari 5초 풀 대기를 예산 제외로 선언했다. 원계약 13/14와 원 C4는 제어 가능한 풀/DB 대기를 함께 고려하도록 했다. 풀을 받은 뒤 시간이 지났음을 확인하는 것에 더해, 풀에 진입할 때부터 남은 시간과 최종 정리 몫을 고려해야 한다.

Hikari 전역 설정을 요청마다 바꾸거나 별도 대형 프레임워크를 도입할 필요는 없다. 현재 알려진 5초 상한을 예산에 미리 확보하고, 필요한 시간이 부족하면 새 연결/예약을 시작하지 않는 작은 방식도 검토할 수 있다. DB·OS·GC 불응까지 물리적 응답 시간을 절대 보장하라는 요구는 아니다. 정상적인 풀 경합을 새 예외 범위로 빼는 변경은 수용하지 않는다.

## 회귀 테스트의 종료도 고칠 것

`SubscriptionFlowIntegrationTest.java:410-430`은 같은 스레드에서 잠금 보유 중 `mvc.perform`를 기다린다. 제한이 회귀하면 구현자의 변이 확인처럼 무한 대기하며 rollback에 도달하지 못한다. 요청을 제한 시간 안에 관측하고 성공·실패 양쪽에서 잠금을 finally로 해제하는 식으로 테스트 자체가 종료되게 바꾼다. 수동 JVM 종료를 회귀 테스트의 자동 실패 판정으로 사용하지 않는다.

이번 수정은 C4 경로와 그 회귀 검증에 집중한다. 이미 검증한 C1/C2/C3 등의 광범위한 변이 확인을 반복할 필요는 없다. 변경 부분의 집중 검증 뒤 최종 CI/check bootJar·secrets와 새 코드/문서 SHA를 남긴다. 실제 환경의 고지·발송·HTTPS·운영 검증은 기존 이슈와 공개 출시 기준에서 계속 추적한다.
