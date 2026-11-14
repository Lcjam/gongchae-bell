# 구현 시작 · codex 조율

사용자 요청: 앱을 완성하고 현재 세션의 claude, agy와 적극 협업한다.
확정 범위는 CLAUDE.md, docs/prd.md, docs/design/, docs/adr/이다. 현재 앱 코드는 없고 기획 PR #1만 머지됐다. 기존 미커밋 scripts/build-review-html.py, collab/2026-11-09-harness/, debug.log는 이번 작업에서 변경하지 않는다.

## 1차 독립 점검 (구현 준비, 기존 설계 변경 아님)
- claude: CLAUDE.md, PRD, ADR-0001, 설계를 읽고 구현 순서와 수집/DB 동시성의 핵심 함정을 점검한다. Spring Boot 4.1.x, Gradle, JDK 21의 실제 배포 버전/호환성을 공식 원본으로 검증한다. 결과는 `01-claude.md`에 기록한다.
- agy: 같은 문서와 collab/03-feasibility.md를 읽고 웹/알림 보안 수용 기준의 누락 위험을 독립 점검한다. 저장소에 실제 API fixture가 없는지 확인하고, 키 없이 접근 가능한 공식 공개 응답 원본을 1회 확보할 수 있으면 `fixtures/` 아래에 출처/취득일과 함께 저장한다. 공공데이터 키 탐색 및 실제 API 반복 호출은 하지 않는다. 결과는 `01-agy.md`에 기록한다.
- codex: 백로그, Gradle 기반 앱과 테스트/실행 환경을 구축한다. 구현 코드는 codex만 변경한다. 리뷰는 이후 두 에이전트에게 요청한다.

두 검토자는 지정된 자신의 결과 파일/fixture 외에는 변경하지 않는다. git checkout/commit, 구현 코드 변경, 기존 사용자 변경 수정 금지. 기존 하네스 제안은 미승인 상태이므로 별도 하네스 구축으로 범위를 돌리지 않는다.
각자 완료하면 터미널에 `작성 완료: <결과 파일 경로>` 한 줄만 남긴다. 의견이 갈릴 경우 근거와 최소 대안을 기록한다.
