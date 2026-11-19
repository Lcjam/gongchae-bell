# PR13 수정 라운드 독립 재검수 · sol

2026-11-18. 대상 HEAD `7fa3f385773b3bcf2cb76b7a8f2766c1867cc432`, 코드 `af62adb2065117c6f3d0d099c303e3e3def6652d`, 비교 기준 `08cfdac3de9d6282393f379d062314d5f3440432`다. 두 대상 커밋 사이 diff는 수정 결과·이전 결과 문서 두 파일뿐이다.

**판정은 C4 추가 수정 후 재검수다.** C1·C2·C3·C5·C6는 현재 소스에서 수용한다. C4는 풀 획득 후 재검사·HTTP 직전 재검사·소진 예산의 문장 시작 거부가 개선됐지만, 아래 제어 가능한 대기를 전체 요청 예산으로 제한하지 못한다. 수정 작성자는 Claude를 유지한다.

## 필수 수정

### C4-a 조건 검증 SQL이 요청 예산보다 먼저 실행됨

`SubscriptionController.java` 76·102행에서 `service.conditions()`를 먼저 호출한다. `SubscriptionService.java` 90행은 `catalog.findFilterOptions()`로 실제 SQL을 실행하며, 예산 시작은 뒤의 `subscribeEmail()` 115행·`registerDiscord()` 343행이다. 따라서 조건 조회의 연결 획득·SQL 대기가 지난 뒤 새 25초가 시작된다. 잘못된 입력을 다시 그리는 Controller 87·107행도 필터 SQL을 다시 호출한다.

조건 조회 전에 같은 단조 시계 예산을 시작하고, 정상 처리와 오류 화면 처리의 대기까지 해당 예산에 묶어야 한다. 서비스 내부부터 시작한다고 문구만 재정의하면 계약 13 §4·14 항목 4와 원 C4가 남는다.

### C4-b 연속 SQL이 최초 제한 시간을 각각 재사용함

`DeliveryLedger.java` 93행은 `waitLimit.get()`를 한 번 읽는다. 뒤의 notification UPDATE 94~98행, `limits.acquireHere()` 100행의 rate_limit upsert, attempt INSERT 104~106행은 같은 제한 시간을 각각 사용한다. `RateLimiter.java` 64~67행이 설정하는 것은 개별 문장의 제한이며, 이 코드에는 트랜잭션 전체 경과 시간을 차감하는 제한이 없다.

예를 들어 R 진입 때 SQL 몫이 22초이고 첫 UPDATE와 다음 upsert가 각각 15초씩 기다린다고 가정하면 두 문장은 각자 제한 이내지만 누적은 30초다. 이는 코드로 구성한 반례이며 새 실측치가 아니다. R 뒤 검사로 HTTP를 막아도 이미 DB 대기 예산을 초과한다.

대기 가능한 각 문장 직전에 남은 몫을 다시 확인하거나 동등한 트랜잭션 전체 제한을 적용해야 한다. `SubscriptionService.java` 147·155~162행의 createSync/기존 PENDING 갱신, 290~295행의 재발송 조회 후 createSync도 같은 최초 제한을 물려받는다. Discord 성공 저장 362~384행과 `SyncSender.java` 126~135행의 결과 기록·종결도 정리 몫을 문장마다 새로 지급하지 않도록 함께 점검해야 한다. R의 원자 커밋·펜싱·null/unknown 보존·예약 환불 없음은 유지한다.

### C4-c 풀 대기가 예산에서 제외됨

`SyncSender.java` 27~29행은 풀 획득 대기를 명시적으로 제외한다. `application.yml`의 Hikari 상한은 연결마다 5초이며, `SyncSender.java` 88~105행은 남은 5초로 A 획득에 들어간 뒤에야 다시 검사한다. `end()` 160~164행과 Discord 최종 저장 TX도 새 연결의 대기 뒤 정리 SQL 몫을 지급한다. 따라서 정상적인 풀 경합만으로 요청 예산을 넘길 수 있다.

현재 풀 획득 상한과 최종 정리 몫을 진입 전에 확보하거나 남은 시간에 획득 대기를 제한해야 한다. 계약 13 §4·14 항목 4는 풀 대기를 포함한다. GC·OS 정지·DB 불응까지 물리적인 절대 25초를 보장하라는 요구는 없다. 제어 가능한 풀 경합을 제외한 새 설명은 수용할 수 없다.

### 회귀 테스트 자체의 종료 보장

`SubscriptionFlowIntegrationTest.java` 410~430행은 같은 테스트 스레드가 별도 연결의 미커밋 INSERT를 보유한 채 426행의 `mvc.perform()`를 동기로 기다린다. 요청의 제한이 회귀하면 429행 rollback에 도달하지 못한다. 수정 결과에 적힌 변이 확인의 약 10분 대기·수동 JVM 종료는 이 구조와 일치한다. 이번 검수에서는 재실행하지 않았다.

요청을 제한 시간 안에 관측하고, 실패 경로에서도 잠금 보유 연결을 finally로 해제한 뒤 요청 작업을 정리하도록 고쳐야 한다. executor의 종료 대기보다 잠금 해제가 먼저여야 한다. `DeliveryLedgerTest.java` 337~374행의 같은 스레드 잠금 보유 대기 검증에도 같은 종료 원칙을 적용할 필요가 있다. 수정된 경로의 집중 검증과 최종 CI를 남기면 충분하며, 기존 C1~C6 전체 변이 재실행은 요구하지 않는다.

## 수용하는 수정과 확인 범위

| 항목 | 소스 판정과 남은 검증 한계 |
|---|---|
| C1 | `SubscriptionService.java` 289~296행이 조회부터 createSync까지 같은 TX의 `FOR KEY SHARE`를 유지한다. 해지 DELETE/FOR UPDATE와 직렬화되고 이후 상태·버전 변경은 A의 자격 검사로 잡는다. 경합 회귀 테스트 380~405행은 삭제 선행 후 고정 응답·메일 0·notification 0을 확인한다. |
| C2 | `ErasureJournal.java` 44·57·76~85행이 활성화 시 정규 파일 확인, CREATE 없는 열기, 복원 시 부재/디렉터리 실패, 기존 빈 파일 허용을 구현한다. 복원 명령 49행의 전체 읽기가 DB TX보다 먼저다. 앱 초기화가 누락 파일을 만들지 않는다. 테스트는 run 예외·완료 로그 부재·DB 보존을 확인하며 별도 프로세스 종료 코드는 미실측이다. |
| C3 | `ErasureJournal.java` 57~69행이 매 append마다 OS 잠금 안에서 파일 force 후 디렉터리 force를 수행한다. 기존 파일인지 잠금 전에 관측한 값으로 생략하지 않는다. 108~110행의 Windows 예외는 문서와 같다. 테스트는 동일 JVM의 다른 채널로 잠금 유지와 실패 전파를 확인하며 두 JVM 실경합은 미검증이다. |
| C4 개선분 | 풀 획득 후와 R 전·HTTP 직전 재검사, R 연결 획득 뒤 supplier 평가, 소진 시 1ms 보정 제거, 예산 예외 후 DEADLINE 정리는 수용한다. 기존 가상 타이머·단일 행 잠금 테스트는 연속 SQL 누적 대기와 요청 입구 조건 SQL의 누락을 검증하지 않는다. |
| C5 | `AddressGuard.java` 36~42행의 caller 대기는 timeout으로 제한된다. DNS 작업은 boolean future만 완료하므로 늦게 끝나도 HTTP를 시작할 경로가 없다. `HttpTransports.java` 134~140행은 경과 시간을 빼고 남은 시간만 HTTP에 전달한다. stalled DNS 테스트의 HTTP 0과 shared timeout 검증을 확인했다. 조회마다 데몬 스레드를 쓰는 한계와 HttpClient 재조회 TOCTOU는 문서에 남아 있다. |
| C6 | `HttpTransports.java` 68~74행이 유효한 긴 대기를 1일로 보존하며 NaN/무한대·음수와 구별한다. `SyncSender.java` 143·189행은 예정 backoff보다 긴 지시에서 요청 내 재시도를 종료한다. parser와 이메일·웹훅 1회 종료 회귀 테스트가 있다. |

꺼짐 상태 확인·해지 GET의 503 보완도 수용한다. 빈 필터 인자가 링크에 남는다는 구현자 설명은 `SubscriptionFlowIntegrationTest.java` 348~352행의 기대값과 일치한다.

## CI 근거와 미검증 게이트

코드 CI `44099813628`, 최종 HEAD CI `44100316401`의 test/check bootJar·secrets 성공은 root가 직접 조회한 결과와 수정 결과 문서를 근거로 삼았다. 이 독립 검수에서는 원격 재조회·테스트·변이 실행을 하지 않았다. 보고된 138개 통과는 provider 대역·격리 PostgreSQL 기준이며 이번 검수의 추가 실측 수치가 아니다. [코드 CI](https://github.com/Lcjam/gongchae-bell/actions/runs/44099813628) · [최종 HEAD CI](https://github.com/Lcjam/gongchae-bell/actions/runs/44100316401)

실제 provider 발송·멱등 재생·수신함/메일 클라이언트 원클릭 해지, HTTPS·Firefox·Safari Origin, 고지의 운영 값·외부 처리 근거·보관, 대장 마운트와 복원 리허설·백업 삭제, Caddy 로그/IP 신뢰, 풀 경합 부하 측정은 미검증 게이트로 유지한다. #7 비동기 알림은 이번 판정 범위 밖이다.

검토한 범위는 CLAUDE.md, M2 계약 12·13·14, 기존 codex·agy·codex-round2 보고서, 구현자 수정 결과, 대상 SHA의 관련 소스·테스트·문서 diff다. git 객체를 읽고 이 보고서 한 파일을 작성했다. 구현 코드·테스트·설정·브랜치·DB·계정·서버·env·키는 변경하거나 실행하지 않았다.
