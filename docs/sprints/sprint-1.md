# Sprint 1 리뷰 · M1 제품 (수집·웹)

> 기간: 2026-11-14 ~ 2026-11-15 KST. M1 이슈 #2~#5를 만든 때부터 3년 최초 적재를 실측할 때까지다. 기획·설계([PR #1](https://github.com/Lcjam/gongchae-bell/pull/1), 2026-11-09)는 그 전 단계다. `Sprint N` 마일스톤은 만들지 않았고, 범위는 `M1 제품` 마일스톤의 이슈로 정했다.
> 작성: claude(#4 구현자) 초안이다. root(codex) 검수 전이며, 수치는 아래 링크한 기록에서만 가져왔다.

## 목표

PRD의 M1: 공공기관 채용공고를 공식 API로 수집하고(FR-C1~C8), 웹에서 탐색·상세·기관 3년 통계를 보여 준다(FR-W1~W8). 상세 상태는 [구현 현황](../implementation-status.md)에 있다.

## 완료

| 이슈 | PR | 결과 |
|---|---|---|
| #2 실행 기반 | [#8](https://github.com/Lcjam/gongchae-bell/pull/8), 2026-11-14 머지 | Java 21·PostgreSQL 17·Flyway·CI(ARM64)·비밀값 스캔 |
| #3 파싱·완결성·변경 관측 | [#9](https://github.com/Lcjam/gongchae-bell/pull/9), 2026-11-15 머지 | 반영 계약 v1~v4([계약](../../collab/2026-11-15-implementation/claude-ingestion-contract.md)) |
| #5 탐색·상세·기관 화면 | [#10](https://github.com/Lcjam/gongchae-bell/pull/10), 2026-11-15 머지 | [결과](../../collab/2026-11-15-implementation/agy-catalog-result.md) |
| #4 수집 실행기·3년 최초 적재 | [#11](https://github.com/Lcjam/gongchae-bell/pull/11), root 검수 후 머지 | 로컬 개발 DB에서 2023-11-15~2026-11-15 시작 공고 36,768건 반영(raw 36,772, HTTP 37회, 재실행 0회). [실행 기록](../../collab/2026-11-15-backfill/agy-start-scan-result.md), [root DB 검수](../../collab/2026-11-15-backfill/codex-start-scan-review.md) |
| 검수 기록 | [#12](https://github.com/Lcjam/gongchae-bell/pull/12), 2026-11-15 머지 | M1 코드·공식 API·합본 검수 기록 |

PR마다 구현자 외 두 리뷰를 거쳤다([reviews](../../collab/reviews/)). #4는 수정한 claude의 과거 리뷰를 독립 리뷰로 세지 않는다.

## 미완료

- **M2 알림**(#6, #7): 착수하지 않았다. 구현 전 계약만 협의 중이다.
- **M3 운영**: 배포, 모니터링, 백업·복원, 경보 실제 수신(일 예산 500 경고 포함).
- **PRD §7 공개 출시 조건** 전부. 운영 서버 DB 적재, 정기 수집을 켠 운영 관측, 실제 상세 수집, 두 앱 동시 운영, 성능 기준선이 여기에 들어간다.

## 데모

화면 스크린샷은 저장소에 없다. 화면 검증(360px 포함)은 [#5 결과](../../collab/2026-11-15-implementation/agy-catalog-result.md)에, 수집 실측은 위 실행 기록에, 테스트·CI는 [#4 결과](../../collab/2026-11-15-implementation/claude-collector-fix-result.md)와 각 PR의 CI 실행에 있다.

## 회고 (KPT)

**Keep**
- 구현 1명에 독립 리뷰 2건과 root 검수를 붙여 결함을 머지 전에 고쳤다. 예를 들어 #4는 1차 리뷰의 High 지적과 재검수 6항목을 반영했다.
- 실제 API 실측을 완료 조건으로 두었다. 덕분에 테스트와 문서로는 보이지 않던 누락(시작월과 마감월이 다른 공고 0건)을 찾았다.
- 실측 수치를 보고서와 root의 독립 DB 대조로 두 번 확인했다.

**Problem**
- `pbancEndYmd`를 공식 명세 문구대로 월 상한으로 보냈다. 실제로는 마감일 상한처럼 동작했다. 응답 봉투가 완결이라는 뜻의 `COMPLETE`를 목표 범위의 완결로 오해해, 22,733건 부분 적재를 한때 완료로 기록했다.
- 머지 커밋 메시지와 실제 내용이 달랐던 적이 있다(상태 문서가 빠진 `dc8d016`을 `f75d8f1`로 보완).
- 에이전트 사용량 한도로 작업이 끊겨 인계가 두 번 생겼다(#4 수정 Sol→claude, 실제 적재 Sol→agy).

**Try**
- 외부 API의 날짜·필터 의미는 명세 문구 대신 실제 응답 분포(경계를 걸친 행, 미래 날짜)로 확인하고 fixture로 고정한다.
- 완료 판정에서 "응답이 완결됐다"와 "목표 범위를 다 받았다"를 따로 기록한다.
- 커밋 직후 `git show`로 의도한 파일과 내용이 들어갔는지 확인한다.

---

## 후속 상태 안내 (2026-11-22)

- 위 본문(로컬 개발 DB 36,768건, 미완료 목록, 스크린샷 없음, KPT 회고)은 **2026-11-14~2026-11-15 M1 완료 시점의 역사 기록**으로 보존한다.
- 2026-11-22(`main` `fb51d48`, PR #38·#40·#41·#42 병합 기준 · 운영 앱 `e0208ff` 유지) 최신 누적 현황과 남은 PRD §7 공개 출시 게이트는 [구현 및 출시 검증 현황](../implementation-status.md)에서 관리한다.
  - 운영 DB 최초 3년 적재(36,879건, 2026-11-19): [운영 초기 배포 기록](../../collab/2026-11-19-resume/claude-runtime-result.md)
  - M3 수동 blue/green·사후 롤백·모니터링 mount·`e0208ff` 배포 및 제한 성능 실측: [G4 기록](../../collab/2026-11-19-resume/claude-g4-result.md), [4cc 기록](../../collab/2026-11-19-resume/claude-main4cc-deploy-result.md), [e020 기록](../../collab/2026-11-19-resume/claude-main-e020-deploy-result.md)
  - 프론트엔드 리프레시 구조 문서 및 합성 스크린샷/QA 증거(PR #39·#41): [프론트 구조](../design/frontend-structure.md), [합성 QA 기록](../qa/frontend-refresh-2026-11-22.md)
