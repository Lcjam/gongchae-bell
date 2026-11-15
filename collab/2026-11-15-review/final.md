# M1 구현 검수 현황 · codex

2026-11-15. 이 기록은 코드 검수와 로컬 합본 검증의 범위를 설명한다. 실제 3년 적재와 공개 배포 완료 기록이 아니다.

| PR | 지정 head | 독립 검수 | 해당 head CI |
|---|---|---|---|
| #9 코어 | aab6d5b2a9b64fe4b767f8deb415c77417e4dd86 | codex·Sol 수용, 이전 agy 의견 반영 | [43681733201](https://github.com/Lcjam/gongchae-bell/actions/runs/43681733201) 성공 |
| #10 웹 | 4334af809e3d8ce97205e29dcf9a22d28fcddaa6 | codex·Sol 수용, Claude 의견 반영 | [43656050416](https://github.com/Lcjam/gongchae-bell/actions/runs/43656050416) 성공 |
| #11 실행기 | 56faf0510c30d2b7cecc00a40e2757c2d509128e | codex·agy 최종 수용 | [43684804047](https://github.com/Lcjam/gongchae-bell/actions/runs/43684804047) 성공 |

각 PR의 구현자와 검수자 기록은 [reviews](../reviews/)에 있다. #11을 수정한 Claude의 과거 리뷰는 최신 head의 독립 리뷰 수에 포함하지 않는다.

## 실제 공식 API 검증

2026-11-15 14:35~14:41 KST에 기존 RecruitmentClient·영속 ApiBudget 경로로 총 4 HTTP 시도를 기록했다. 첫 호출은 HTTP_STATUS, 메모리에서 키 표기를 한 번 정규화한 뒤 목록 497행·상세 sn307032·2010-01 빈 기간 응답은 모두 HTTP 200/resultCode 200이었다. 원문 .env는 변경하지 않았다. Sol이 보관 본문·영속 예산 4회·키 비노출을 독립 확인했다. [독립 검수](official-fixtures-sol-review.md).

## 합본 검증

Sol의 격리 합본은 #11 최종 head와 #10, 공식 fixture 및 새 통합 테스트 3개를 포함한다. 검증 코드 head `f5474a8c8132a6d5ff1c83f6a9f47a0833a8167a`, 보고서 포함 head `b740072a1c86bccb0b829bcdc75ff7be6a2e3db6`에서 Windows x64/Java 21/Gradle 9.9.1/PostgreSQL 17 Testcontainers의 `check bootJar` **94 tests, 실패·오류·생략 0**을 보고했다. [codex 검수](m1-integration-codex.md), [Claude 검수](m1-integration-claude.md).

전체 공식 목록의 파서·DB 반영, 한 행을 발췌한 상세의 실제 빈→로컬 HTTP 대역→DB→HTML, 공식 빈 응답의 정기 실패/최초 적재 0건 차이를 검사했다. 외부 API·브라우저·배포 시험은 이 합본에서 수행하지 않았다.

## 남은 게이트

- 테스트가 개발 환경의 COLLECTOR_ENABLED/BACKFILL을 상속하지 않도록 기존 공용 기반과 CatalogIntegrationTest의 테스트 전용 설정을 명시한다. Sol에게 마지막 보완과 재검증을 배정했다.
- 부모 PR을 병합하고 새 합본 테스트·설정 보완을 최종 저장소 CI에서 확인한다. 병합 중 충돌은 담당 구현자만 해결한다.
- 최초 3년치 실제 적재와 오래된 코드·품질 표본 검수를 기록한다. 그 전에는 FR-C1 완료로 표시하지 않는다.
- 500 경보 실제 수신은 M3 게이트다. 알림 기능, 서버 배포, HTTPS, 운영·백업·복원과 PRD 공개 출시 조건도 남아 있다.
