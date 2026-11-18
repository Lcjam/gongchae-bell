# 구현 및 출시 검증 현황

기준: 2026-11-16. 완료 범위는 PRD §7 전체다. 아래 단계는 구현 순서이며 출시 기준을 축소하지 않는다. 체크 표시는 실제 결과가 확인된 경우에만 갱신한다.

| 범위 | 검증해야 하는 근거 | 현재 상태 |
|---|---|---|
| 실행 기반 (#2) | Java 21 빌드, PostgreSQL 17 Flyway, CI, 홈/목록 빈 상태, 두 리뷰 | 구현·검증 완료, [PR #8](https://github.com/Lcjam/gongchae-bell/pull/8) 머지 ([검증 기록](../collab/2026-11-14-implementation/03-foundation-validation.md#원격-ci-및-후속-리뷰)) |
| FR-C1 | 월별 3년 최초 적재, 멱등 fixture, 실제 건수/표본 기록 | **로컬 개발 DB 실측 완료.** 시작일 하한 전체 조회(START_SCAN, V4) 실행기(#4, [PR #11](https://github.com/Lcjam/gongchae-bell/pull/11))로 2026-11-15 21:20~21:26 KST에 2023-11-15~2026-11-15 시작 공고를 적재했다. raw 36,772건 중 36,768건 반영(미래 시작 4건 제외), HTTP 37회(일 예산 43→80), 신규 14,035건, 월 걸침 13,693건, 같은 기간 새 슬롯 재실행 0회, 연도별·코드·품질·오래된 기간 표본 기록([실행 기록](../collab/2026-11-15-backfill/agy-start-scan-result.md), [root DB 검수](../collab/2026-11-15-backfill/codex-start-scan-review.md)). 그보다 앞선 월별 방식의 22,733건 `COMPLETE`는 시작월과 마감월이 같은 공고만 받은 **부분 적재**로 정정했다([결함과 수정](../collab/2026-11-15-implementation/claude-collector-fix-result.md#4차-최초-적재를-시작일-하한-전체-조회로-start_scan)). 운영 서버 DB 적재는 배포(M3) 범위다 |
| FR-C2~C4 | 완결성/실패 시 무변경, KST 상태, 필드별 변경 관측 fixture | #3 반영 로직과 fixture 테스트, [PR #9](https://github.com/Lcjam/gongchae-bell/pull/9) 머지. 정기·최초 적재 실행기(#4, [PR #11](https://github.com/Lcjam/gongchae-bell/pull/11))가 같은 반영 경로를 호출한다. 정기 수집을 켜고 운영한 관측은 아직 없다 ([결과](../collab/2026-11-15-implementation/claude-ingestion-result.md)) |
| FR-C5~C6 | 두 앱 선점/재시작, 영속 호출 예산/재시도/경보 | 세션 잠금([ADR-0016](adr/0016-supplemental-session-lock.md))·회차 선점, 영속 호출 예산·재시도·경보 지표와 Testcontainers 테스트(#4, [PR #11](https://github.com/Lcjam/gongchae-bell/pull/11)). 실측 적재에서 예산·시도 기록이 HTTP 37회와 일치했다. 두 앱 실제 동시 운영과 500 경보 실제 수신(M3)은 미검증 |
| FR-C7~C8 | 상세 보존, 품질/개별 공고 링크 fixture | 목록 반영이 상세 컬럼을 건드리지 않음과 품질 플래그(#3) 테스트 작성·머지, 화면 표시는 #5 머지(공식 swagger 문서 계약 fixture 기준). 상세 수집(#4, [PR #11](https://github.com/Lcjam/gongchae-bell/pull/11))과 공식 `detail` 실응답 표본 저장 테스트. 최초 적재는 설계대로 상세를 부르지 않았다(36,768건 모두 상세 없음). 실제 상세 수집은 정기 수집을 켠 뒤 관측한다. 기존 `DONE` 상세 보존은 fixture·통합 테스트 결과이며 실제 DB에서 관측한 것이 아니다 |
| FR-W1~W4 | 필터/정렬/페이징, 홈, 상세, 3년 기관 통계, 원문 이동 | 구현 완료 (`#5` / `catalog`, [PR #10](https://github.com/Lcjam/gongchae-bell/pull/10) 머지. `detail` 단계·첨부 표시 테스트는 공식 swagger 문서 계약 fixture 기준이며, 공식 `detail` 실응답 표본으로 수집 빈 → DB → 화면까지 잇는 통합 테스트는 #4 PR에 합쳤다(Sol `OfficialFixtureIntegrationTest`)) |
| FR-W5~W8 | 기준 시각, escaping/URL 검사, 메타데이터/robots, 360px 조작 | 구현 완료 (`#5` / `catalog`) |
| FR-A1~A3 | 이메일 확인, Discord SSRF/암호화, 목적별 토큰, 수정/해지/복구 | #6 구현·로컬 대역 검증(PR 리뷰 대기): 이메일 신청·확인(유효 확인 POST만 활성), 디스코드 URL 검사·주소 검사·AES-GCM·테스트 성공 시 등록, 목적별 토큰, 조건 수정·해지·관리 링크 복구, Origin 예외와 토큰 실패 제한([계약](../collab/2026-11-16-implementation/claude-subscriptions-contract.md), [운영](ops/alerts.md)). 실제 Resend·Discord 발송과 실제 HTTPS 브라우저 관측은 미검증 |
| FR-A4~A5 | 트랜잭션 outbox, 일별 묶음, 재확인/선점/경합/복구/429/404 테스트 | 기반만 #6에 있음: 발송 작업·시도 테이블, 예약 R·펜싱·종결 규칙, 비동기 선점·회수 SQL과 테스트. 매칭·outbox·묶음·비동기 워커·디스코드 404 비활성화는 #7(미구현) |
| FR-A6~A8 | 고지/14세/파기/대장/처리방침/국외이전, 요청·발송 제한 | #6 구현·로컬 대역 검증: 신청 전 고지·필수 동의·만 14세 확인(서버 검사), 동의 버전·시각 저장, 처리방침 화면(운영 값은 필수 설정, 없으면 '미정'), 상태별 정리 주기, 해지 대장 fsync와 fail-closed 복원, 요청 제한과 발송 상한, RFC 8058 원클릭 해지 경로. 실제 고지 값·국외 이전 근거·공급자 계약·백업 사본 삭제·대장 정리 시점은 실사용 전 게이트 |
| 운영 기반 | 이미지, 자동 blue/green 배포/롤백, 모니터링, 백업/NAS pull | 배포(#14): 이미지·blue/green·롤백 소스가 main에 병합(`be30f69`), 실서버 적용 전([runbook](runbook.md)). 모니터링(#15): Prometheus·Grafana overlay, 경보 규칙과 `promtool` 시험, 정기 수집 heartbeat(기본 꺼짐), #14 통합(`deploy.sh monitoring`)과 격리 스택·실제 Compose 시험까지 PR #21(미머지). 운영 설치·실제 수신은 미검증([모니터링](ops/monitoring.md)). 백업(#16): `pg_dump`·해지 대장 restic 일일 백업 소스가 main에 병합(`52c172c`), 실제 OCI 저장소·복원 리허설 전([백업과 복구](ops/backup-restore.md)). NAS pull(#17)은 미구현 |
| 공개 출시 | HTTPS 핵심 화면, 실제 양 채널 수신/해지/SPF/DKIM, 실제 API 최초 적재 | 실제 API 최초 적재는 로컬 개발 DB에서만 실측(FR-C1). 나머지는 미검증 |
| 운영 증거 | G4 성공/실패 배포, 경보 수신, 격리 복원, 부하/자원, 훈련 회고 | 미검증 |
| 문서/프로세스 | 이슈/PR/두 리뷰, runbook, CHANGELOG, sprint 리뷰, 홍보 규정 | 진행 중 |

**M1 수집·웹 이슈(#2~#5)는 이 표의 범위까지다. M2 알림은 #6(구독·보안, 리뷰 대기)까지이고 #7(매칭·발송)이 남았다. M3 운영과 PRD §7 공개 출시 게이트는 미완료다.**

공개 AJAX `list` 표본과 공식 OpenAPI `list`·`detail`·빈 기간·시작일 하한 1페이지 응답(2026-11-15 각 1회 취득)은 [fixtures](../fixtures/README.md)에 있다. 공식 응답 표본은 #4 테스트가 쓴다. 3년 적재의 근거는 표본이 아니라 FR-C1 행의 실행 기록이다. #5의 `detail` 단계(`steps`)·첨부(`files`) 화면 출력 테스트는 공식 swagger 문서 계약에 맞춘 fixture 기준이다.

배포·실발송에는 사용자의 도메인/DNS, 서버 접속, 담당 연락처, API/Resend/운영 알림 설정 및 NAS pull 환경이 필요하다. 비밀값은 환경변수 또는 GitHub Secrets에만 둔다.
