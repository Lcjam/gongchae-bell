# 완료 검수 기록·프론트 인계 문서 보존 결과 (#19)

요청: `collab/2026-11-19-resume/agy-review-records-request.md` (보낸 에이전트: root Codex)

## 1. 작업 위치 · 커밋 · PR · CI

| 항목 | 값 |
|---|---|
| Orca worktree | `C:/Users/lcj/orca/workspaces/my-project/nov19-review-records` |
| 브랜치 | `docs/#19-nov19-review-records` |
| 기준 `main` | `fd106543aa90b59476e14f18f9be36e2b47757db` |
| 보존·갱신 커밋 SHA | `7b3147e2d246aa78be7070d2e2b6a921aca78092` (`docs: preserve PR 23/25/26 reviews and update frontend structure (#19)`) |
| 최종 head | 이 결과 파일(`collab/2026-11-19-resume/agy-review-records-result.md`)만 추가한 문서 후속 커밋 (기존 보존 파일·`docs/design/frontend-structure.md` 무변경) |
| Draft PR | [#29](https://github.com/Lcjam/gongchae-bell/pull/29) (`Refs #19`) |
| CI (`7b3147e`) | CI [44509421163](https://github.com/Lcjam/gongchae-bell/actions/runs/44509421163) (`test`, `deploy-verify`, `backup-verify`, `secrets` 4잡 `success`) · Monitoring [44509421063](https://github.com/Lcjam/gongchae-bell/actions/runs/44509421063) (`rules`, `compose-integration`, `alert-e2e` 3잡 `success`) |

## 2. 바이트 동일 보존 파일 vs 변경한 문서

### A. 수정 없이 보존한 파일 (root 원본과 바이트 동일)

- **새로 보존한 15개 파일 (전부 SHA-256 일치 확인)**:
  - 검수 원문 10개:
    - `collab/reviews/pr-23-claude.md` (`a1421763f92b9c07…`)
    - `collab/reviews/pr-23-codex.md` (`32048db4d5c221f9…`)
    - `collab/reviews/pr-23-sol.md` (`3f98b94630bbfdc0…`)
    - `collab/reviews/pr-23-claude-round2.md` (`9f4f7d1efcf2b595…`)
    - `collab/reviews/pr-23-codex-round2.md` (`95407525847b2c12…`)
    - `collab/reviews/pr-25-agy.md` (`5aad429006e93576…`)
    - `collab/reviews/pr-25-agy-round2.md` (`08d3c15c312204e1…`)
    - `collab/reviews/pr-25-codex.md` (`d685472d49a89221…`)
    - `collab/reviews/pr-26-claude.md` (`8dfcf15b5194207a…`)
    - `collab/reviews/pr-26-codex.md` (`6296bbd3e9048f88…`)
  - 상대 링크로 필요한 배정 원문 3개:
    - `collab/2026-11-19-resume/extra-claude-pr23-review.md` (`350214a6d2da5cc7…`)
    - `collab/2026-11-19-resume/restart-claude-pr23-review.md` (`e01ea72c98e74014…`)
    - `collab/2026-11-19-resume/claude-mobile-review-request.md` (`019ab2d6e68a4825…`)
  - 실제 운영 모바일 검수·증거 2개:
    - `collab/2026-11-19-resume/codex-runtime-review.md` (`3c7171dba8b35eeb…`)
    - `collab/2026-11-19-resume/evidence/jobs-mobile-main32.png` (`f2b44184fa86595b…`)
- **트리에 이미 있던 4개 파일 (무변경, root와 SHA-256 일치 재확인)**:
  - `collab/2026-11-19-resume/agy-mobile-fix-result.md` (`35324047bd17435e…`)
  - `collab/2026-11-19-resume/claude-g4-result.md` (`a9603c8cb235de96…`)
  - `collab/2026-11-19-resume/claude-runtime-result.md` (`37aeede21f527964…`)
  - `collab/2026-11-19-resume/claude-runtime-round2.md` (`3845faf6d4431f9e…`)

### B. 변경한 인계 문서 (1개)

- `docs/design/frontend-structure.md`:
  - 상단 기준을 `2026-11-19 main fd106543aa90b59476e14f18f9be36e2b47757db`(M1, PR #13, PR #23 `48f6878`, PR #26 `32e71e4`, PR #25 `fd10654`)로 갱신하고, PR #23 백엔드 병합 완료와 운영 `ALERTS_ENABLED=false`·`COLLECTOR_ENABLED=false` 유지(실제 메일/Discord 발송·정기 수집 등 운영 게이트 미완료)를 구분했다.
  - §1 파일 트리 및 주석에 `app.css`·`jobs.html`(`[M1/PR26]`), `manage.html`(`[PR13/PR23]`), `AlertsConfiguration.java`·`SubscriptionService.java`(`[PR13/PR23]`)와 PR #23 내부 워커 클래스 요약을 반영했다(웹 라우트·폼 입력 계약 무변경).
  - §4.2 `ManageView` 레코드에 `disabledReason` 필드와 `status != 'ACTIVE'` + `disabledReason == 'WEBHOOK_404'` 재신청 안내 표시를 반영했다(`SubscriptionService.java:56`, `manage.html:14` 직접 확인).
  - §6 반응형 항목에 `/jobs` 모바일 카드 클래스(`.job-card-inst`, `.flex-shrink-0`, `.job-card-badges`, `.job-meta-badge`)와 `D-day`·`상태`·`구분`·`문자 주의` `white-space: nowrap` 유지를 반영했다.
  - §7 구현 상태 요약에서 M1(PR #26 포함), PR #13, `#7` 알림 발송 워커(PR #23 병합 완료 / `ALERTS_ENABLED=false`), M3 공개 배포·관측(`codex-runtime-review.md` 기준 완료된 1차·`32e71e4` 모바일 재검수와 남은 게이트 분리)을 최신 상태로 맞췄다.

## 3. 점검 및 제외 범위

- **상대 링크 검사**: 보존된 Markdown 14개 파일과 `docs/design/frontend-structure.md`의 모든 상대 링크가 worktree 내에서 해석됨(`broken_links=0`).
- **민감값 검사**: Discord 실 웹훅 URL·개인키 헤더·`example.com`/`noreply@` 외 이메일 문자열 검색 결과 0건(`secret_hits=0`), `git diff --check` 통과, CI `secrets`(Gitleaks) 통과.
- **제외·보존**: secrets·SSH·`.env`·`scratch`·기타 미완료/분리 자료(`claude-main32-deploy-result.md` 등)는 포함하지 않았다. 앱 소스·테스트·구현 설정·타 작성자 원문·실서버·provider는 변경하거나 접근하지 않았다.
