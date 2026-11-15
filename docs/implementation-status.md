# 구현 및 출시 검증 현황

기준: 2026-11-15. 완료 범위는 PRD §7 전체다. 아래 단계는 구현 순서이며 출시 기준을 축소하지 않는다. 체크 표시는 실제 결과가 확인된 경우에만 갱신한다.

| 범위 | 검증해야 하는 근거 | 현재 상태 |
|---|---|---|
| 실행 기반 (#2) | Java 21 빌드, PostgreSQL 17 Flyway, CI, 홈/목록 빈 상태, 두 리뷰 | 구현·검증 완료, [PR #8](https://github.com/Lcjam/gongchae-bell/pull/8) 머지 ([검증 기록](../collab/2026-11-14-implementation/03-foundation-validation.md#원격-ci-및-후속-리뷰)) |
| FR-C1 | 월별 3년 최초 적재, 멱등 fixture, 실제 건수/표본 기록 | 시작일 하한 전체 조회(START_SCAN, V4) 최초 적재 실행기와 fixture·로컬 대역 테스트(#4) 작성, [PR #11](https://github.com/Lcjam/gongchae-bell/pull/11) 리뷰 대기. 2026-11-15 월별 방식으로 실제 적재한 22,733건은 시작월과 마감월이 같은 공고만 받은 부분 적재라서 완료가 아니다(`pbancEndYmd`가 마감일 상한처럼 동작). 새 방식의 실제 3년 적재는 아직 하지 않았다 ([결과](../collab/2026-11-15-implementation/claude-collector-fix-result.md)) |
| FR-C2~C4 | 완결성/실패 시 무변경, KST 상태, 필드별 변경 관측 fixture | #3 반영 로직과 fixture 테스트, [PR #9](https://github.com/Lcjam/gongchae-bell/pull/9) 머지. 정기·최초 적재 실행기(#4)가 같은 반영 경로를 호출, [PR #11](https://github.com/Lcjam/gongchae-bell/pull/11) 리뷰 대기 ([결과](../collab/2026-11-15-implementation/claude-ingestion-result.md)) |
| FR-C5~C6 | 두 앱 선점/재시작, 영속 호출 예산/재시도/경보 | 세션 잠금([ADR-0016](adr/0016-supplemental-session-lock.md))·회차 선점, 영속 호출 예산·재시도·경보 지표와 Testcontainers 테스트(#4) 작성, [PR #11](https://github.com/Lcjam/gongchae-bell/pull/11) 리뷰 대기. 두 앱 실제 동시 운영은 미검증 |
| FR-C7~C8 | 상세 보존, 품질/개별 공고 링크 fixture | 목록 반영이 상세 컬럼을 건드리지 않음과 품질 플래그(#3) 테스트 작성·머지, 화면 표시는 #5 머지(공식 swagger 문서 계약 fixture 기준). 상세 수집(#4)과 공식 `detail` 실응답 표본 저장 테스트 작성, [PR #11](https://github.com/Lcjam/gongchae-bell/pull/11) 리뷰 대기 |
| FR-W1~W4 | 필터/정렬/페이징, 홈, 상세, 3년 기관 통계, 원문 이동 | 구현 완료 (`#5` / `catalog`, [PR #10](https://github.com/Lcjam/gongchae-bell/pull/10) 머지. `detail` 단계·첨부 표시 테스트는 공식 swagger 문서 계약 fixture 기준이며, 공식 `detail` 실응답 표본으로 화면까지 잇는 테스트는 별도 PR) |
| FR-W5~W8 | 기준 시각, escaping/URL 검사, 메타데이터/robots, 360px 조작 | 구현 완료 (`#5` / `catalog`) |
| FR-A1~A3 | 이메일 확인, Discord SSRF/암호화, 목적별 토큰, 수정/해지/복구 | 미구현 |
| FR-A4~A5 | 트랜잭션 outbox, 일별 묶음, 재확인/선점/경합/복구/429/404 테스트 | 미구현 |
| FR-A6~A8 | 고지/14세/파기/대장/처리방침/국외이전, 요청·발송 제한 | 미구현 |
| 운영 기반 | 이미지, 자동 blue/green 배포/롤백, 모니터링, 백업/NAS pull | 미구현 |
| 공개 출시 | HTTPS 핵심 화면, 실제 양 채널 수신/해지/SPF/DKIM, 실제 API 최초 적재 | 미검증 |
| 운영 증거 | G4 성공/실패 배포, 경보 수신, 격리 복원, 부하/자원, 훈련 회고 | 미검증 |
| 문서/프로세스 | 이슈/PR/두 리뷰, runbook, CHANGELOG, sprint 리뷰, 홍보 규정 | 진행 중 |

공개 AJAX `list` 표본과 공식 OpenAPI `list`·`detail`·빈 기간 응답(2026-11-15 각 1회 취득)은 [fixtures](../fixtures/README.md)에 있다. 공식 응답 표본은 #4 테스트가 쓰며, 이것으로 3년 적재가 검증됐다고 주장하지 않는다. #5의 `detail` 단계(`steps`)·첨부(`files`) 화면 출력 테스트는 공식 swagger 문서 계약에 맞춘 fixture 기준이다.

배포·실발송에는 사용자의 도메인/DNS, 서버 접속, 담당 연락처, API/Resend/운영 알림 설정 및 NAS pull 환경이 필요하다. 비밀값은 환경변수 또는 GitHub Secrets에만 둔다.
