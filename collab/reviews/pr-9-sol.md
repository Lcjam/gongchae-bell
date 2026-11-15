# PR #9 독립 검수 · sol

2026-11-15. 대상은 불변 커밋 **`aab6d5b2a9b64fe4b767f8deb415c77417e4dd86`**이다. GitHub PR 메타데이터에서 현재 head와 일치함을 확인했다. `CLAUDE.md`, PRD FR-C2~C4/C8, 공개 계약 v3와 결과, 검수 03, 검수 결정 10, agy 리뷰를 읽고 지정 워크트리에서 `git show`로 코드·테스트를 확인했다. 코드·브랜치·설정·사용자 변경은 수정하지 않았으며 로컬 테스트와 실제 API를 실행하지 않았다.

**판정: 지정 범위의 코드 검수 수용. 필수 수정 결함을 찾지 못했다. 머지 승인은 아니다.** 공식 API fixture 검증과 #4 호출자 연동은 아래 미검증 경계가 남아 있다.

## 필수 지적

없음.

## 요구사항 및 기존 수정 요청 확인

| 항목 | 확인한 코드와 테스트 | 판단 |
|---|---|---|
| 계약 v3: 파싱 → 호출자 의미 검증 → 반영 | `parsePage(String)`는 파서와 `ParsedPage` 생성만 수행하고 JDBC를 호출하지 않는다. 공개 생성자가 없고 `sns()`는 수정 불가 정렬 집합이다. `totalCount()`·`sns()`·`beginsWithin()`으로 반영 전에 크기·중복·기간을 검사할 수 있다. `upsertPage(ParsedPage, Instant)`만 남았으며 기존 String 오버로드와 `PageResult`는 삭제됐다. | 수용. 호출자가 검사에 실패한 페이지를 반영하지 않을 수 있다. 월·페이지 의미 검사의 실제 실행은 #4 책임이다. |
| 거부 페이지 무변경 | `parsedPageWritesNothingSoTheCallerCanRejectItBeforeAnyChange`는 기존 제목 변경과 기간 밖 신규 행을 함께 파싱하고 5개 공고 관련 테이블 전체 덤프 불변, 기간 양끝 경계와 SN 집합을 확인한다. `pageSnSetCountsStoredPostingsAndStaysTheSameWhenThePageRepeats`는 기존 공고를 포함한 수신 고유 수와 inserted의 차이를 확인한다. | 수용. 재파싱 없이 선검증에 필요한 정보가 제공된다. |
| FR-C2: 실패·잘림·빈 본문 | `body == null`은 `NON_JSON`으로 명시 변환된다. `unusableResponsesChangeNoPostingDataAndKeepTheLastCompleteTime`의 null 본문을 포함한 오류 매트릭스는 회차 결과·고정 실패 코드, 공고 관련 전체 덤프 불변, 마지막 COMPLETE 시각 유지를 확인한다. 음수 totalCount도 `INVALID_ENVELOPE`로 거부한다. | 수용. 빈 본문이 회차를 미완료 상태로 남기던 경로가 막혔다. |
| FR-C2: 반영 원자성·실패 기록 | `write`와 COMPLETE 마감은 같은 트랜잭션이다. `writeErrorMidwayRollsBackEveryTableAndRecordsApplyError`는 앞선 행의 기관·코드·공고·변경 쓰기 이후 CHECK 제약 오류를 일으켜 전체 롤백, 기준 시각 유지, FAILED/APPLY_ERROR 마감과 원래 예외 재전파를 확인한다. 제약은 finally에서 제거한다. | 수용. 실패 기록 자체가 실패하면 원래 예외에 suppressed로 붙이는 경계도 코드·결과 문서에 명시돼 있다. |
| FR-C3: 상태 판정 | `end_date < KST today` 또는 ongoingYn=N으로만 CLOSED가 된다. 완전 수집에서 마감 전 누락은 CHECK, 재등장은 OPEN이다. KST 자정·D-0·마감 연장·누락 테스트가 이를 확인한다. | 수용. 불완전 응답에서는 상태 변경 경로에 진입하지 않는다. |
| FR-C4: 변경 관측 | 12개 비교 필드가 해시와 diff에 포함된다. 동일 입력·코드 순서·decimalDay 변화는 0건이고 마감일 변화는 정확한 old/new와 관측 시각으로 1건이다. 두 우대 원문의 일반적인 변경·삭제 테스트와 제어문자 보존 테스트가 있다. | 수용. 상세 컬럼은 목록 UPDATE에서 제외되고 별도 보존 테스트도 있다. |
| FR-C8: 품질·원문 | srcUrl은 정규화 없이 저장한다. 같은 응답 내 동일 기관의 URL 재사용 및 루트 경로만 generic으로 표시하고, URL이 같으면 기존 판정을 유지한다. 다른 기관의 공유 URL과 스킴 없는 값은 별도 테스트한다. 깨진 문자 의심 표시와 원문 불변 테스트가 있다. | 수용. 웹의 잡알리오 링크 출력은 #5와 합본 확인 대상이다. |
| 동시성 테스트의 범위 | 신규 SN 충돌 테스트는 별도 트랜잭션의 미커밋 삽입과 실제 DB lock wait를 확인한 후 충돌 재읽기·갱신·변경 기록을 검사한다. 정기/적재 겹침 테스트는 barrier로 시작하고 두 작업 성공과 고유 행 수를 검사한다. 쓰기 잠금은 기관 → 코드명 → SN 정렬 순서다. | 해당 재현 범위 수용. 두 앱의 같은 회차 실행을 1회로 제한하는 실행기 통합 검증 전체를 대신하지 않는다. |

구현 근거: [PostingIngestor](https://github.com/Lcjam/gongchae-bell/blob/aab6d5b2a9b64fe4b767f8deb415c77417e4dd86/src/main/java/kr/gongchae/bell/ingestion/PostingIngestor.java), [ParsedPage](https://github.com/Lcjam/gongchae-bell/blob/aab6d5b2a9b64fe4b767f8deb415c77417e4dd86/src/main/java/kr/gongchae/bell/ingestion/ParsedPage.java), [통합 테스트](https://github.com/Lcjam/gongchae-bell/blob/aab6d5b2a9b64fe4b767f8deb415c77417e4dd86/src/test/java/kr/gongchae/bell/ingestion/PostingIngestorIntegrationTest.java).

## 권장

- PR 본문에는 아직 계약 v2의 `upsertPage(String, Instant)`/`PageResult`와 이전 테스트 수가 남아 있다. 검수한 v3 시그니처와 최신 CI 근거로 본문을 갱신하면 #4 담당자의 오연동을 줄일 수 있다. 코드 변경 요청은 아니다.
- agy가 지적한 코드 목록의 Java 정렬/DB collation 차이 및 EDU·기관명 단독 변경 테스트 부재는 기존 비차단 의견을 유지한다. 현재 fixture의 대문자·숫자 코드에서 실패를 발견한 것은 아니다.

## 미검증·후속

- 공식 `apis.data.go.kr` 실제 응답, 빈 달의 응답 코드/봉투, 실제 detail 형식은 이 검수에서 확인하지 않았다. 공개 경로 fixture와 공식 API 실응답 검증을 같은 것으로 취급하지 않는다.
- #4가 계약 v3를 정식 반영한 합본에서 페이지 크기·월 총수 변경·페이지 간 중복·요청 기간을 **upsertPage 호출 전에** 거부하는지, 실패 월 기록과 회차 선점이 보장되는지 확인해야 한다.
- DB 연결 단절·교착 및 실패 기록용 트랜잭션까지 실패하는 상황은 로컬에서 재현하지 않았다. 현재 롤백 테스트는 CHECK 제약 오류 1종이다.
- M2 outbox는 이번 PR 범위가 아니다. 이번 검수로 FR-C1/C5/C6/C7 전체 완료나 공개 출시 DoD 충족을 선언하지 않는다.

## 최종 CI 근거

GitHub connector로 해당 SHA의 PR-triggered 실행과 job을 직접 조회했다. [CI 43681733201](https://github.com/Lcjam/gongchae-bell/actions/runs/43681733201): `test`의 `./gradlew --no-daemon check bootJar` **success**, `secrets`의 Gitleaks **success**. 작업 도중의 파일 변경이나 다른 head의 결과를 이 판정에 섞지 않았다.
