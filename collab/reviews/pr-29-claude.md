# PR #29 검수 기록 보존·프론트 인계 문서 검수 · claude

2026-11-20 · 1차 비작성자 검수 · 요청: root codex, [`2026-11-19-resume/claude-pr28-pr29-review-request.md`](../2026-11-19-resume/claude-pr28-pr29-review-request.md) §2 · 작성자: agy(추가 2) · PR28과는 별도 판정이다

## 대상

| 항목 | 값 |
|---|---|
| 최종 HEAD(`docs/#19-nov19-review-records` = `refs/pull/29/head`, `git ls-remote`로 직접 확인) | `c08b06145bf33035575211b1929ebd44a676a6bd` (요청서와 같음) |
| 보존·인계 커밋 | `7b3147e`. 결과 기록 `c08b061`은 결과 파일만 바꾼다 |
| 기준 | `fd10654`. 현재 `origin/main` `c4465f5`와 `git merge-tree` 결과 충돌 없음 |
| 변경 | `collab/` 16개(추가)와 `docs/design/frontend-structure.md` 1개. **앱·테스트·구현 설정 변경 0** |

## 판정: 수용

필요한 수정이 없다.

## 1. 바이트 동일 보존 (직접 `cmp`)

트리(`c08b061`)의 파일을 root 원본과 바이트 단위로 비교했다. **20개 모두 같다.**
- 검수 원문 10개: `pr-23-claude`·`pr-23-codex`·`pr-23-sol`·`pr-23-claude-round2`·`pr-23-codex-round2`·`pr-25-agy`·`pr-25-agy-round2`·`pr-25-codex`·`pr-26-claude`·`pr-26-codex`.
- 배정 원문 3개: `extra-claude-pr23-review`·`restart-claude-pr23-review`·`claude-mobile-review-request`.
- 운영 기록과 증거: `codex-runtime-review.md`, `evidence/jobs-mobile-main32.png`.
- 결과 파일 1개.
- 이미 트리에 있던 4개(`agy-mobile-fix-result`·`claude-g4-result`·`claude-runtime-result`·`claude-runtime-round2`)도 같다.

작성자 보고와 일치한다.

## 2. 상대 링크: 커밋 트리 기준

이 PR이 바꾼 md 파일 전부에서 상대 링크를 뽑았다. 외부 URL과 앵커만 있는 링크는 뺐다. 경로를 정규화한 뒤 `git cat-file -e c08b061:<경로>`로 확인했다. **고유 링크 16개 모두 커밋 트리 안에 있다.** 작업 디렉터리가 아니라 트리 기준이다. 보존된 검수 원문이 가리키는 배정 원문도 함께 들어 있다(`pr-23-claude.md` → `extra-claude-pr23-review.md`, `pr-23-claude-round2.md` → `restart-claude-pr23-review.md`, `pr-26-claude.md` → `claude-mobile-review-request.md`).

## 3. `frontend-structure.md`와 실제 main 대조 (`fd10654`)

| 문서 서술 | 실제 | 결과 |
|---|---|---|
| `ManageView(…, token, disabledReason)` | `SubscriptionService.java:56`의 7개 필드 레코드(6개 생성자 호환 포함) | 일치 |
| `status != 'ACTIVE'`이고 `disabledReason == 'WEBHOOK_404'`일 때 재신청 안내 | `manage.html:14`의 `th:case="*"` 안 `view.disabledReason == 'WEBHOOK_404'` | 일치 |
| 모바일 클래스 `.job-card-inst`·`.flex-shrink-0`·`.job-card-badges`·`.job-meta-badge`와 CSS 값 | `app.css:13-16` 값 그대로, `jobs.html`에 해당 클래스 8곳 | 일치 |
| 파일 트리(`ops/Heartbeat.java`, `AlertDispatchProperties.java`, `filter-toggle.js` 등), `AlertsConfiguration`의 `digestHeartbeat` 등록과 `alertsEnabled` 주입 | 경로가 있고 코드에서 확인됨 | 일치 |
| 운영 적재 36,879건 | `claude-runtime-result.md:11,71,73`, `codex-runtime-review.md:7`(posting 36879) | 일치 |
| **backend 병합과 운영 미개시의 구분** | PR #23 병합(`48f6878`)은 "백엔드 코드 병합 완료"로 적었다. 운영은 `ALERTS_ENABLED=false`·`COLLECTOR_ENABLED=false`를 유지하고, 실제 발송·정기 수집·백업 복원·외부 감시 등은 "미완료"로 분리했다 | 맞게 구분함 |

기준 SHA를 `fd10654`로 적었다. 현재 main은 그 뒤 PR #27(문서)만 더해져 프론트 관련 파일에는 차이가 없다.

## 4. 비밀값과 실사용자 정보

추가된 텍스트 전체를 검사했다. 대상은 이메일, Discord 웹훅 경로, 개인키 헤더, Healthchecks·Resend·GitHub 토큰 패턴, `password=`, SSH 지문이다. 결과:
- `example.com`·`example.invalid` 밖의 이메일, 웹훅, 키, 토큰은 0건이다.
- 걸린 것은 공개 사이트 도메인과 그 공개 A 레코드 IP, 컨테이너 이미지 digest, GitHub Actions 실행 ID, 커밋 SHA뿐이다. 모두 공개 정보이고 이미 main의 `claude-runtime-result.md`에도 있다.
- 증거 PNG는 공개 `/jobs` 모바일 화면이다(공고, 기관명, 페이지 번호). 개인정보가 없다.

## 5. 확인 범위

- **직접 한 것:** `ls-remote`·`merge-tree`, 커밋 범위와 변경 파일 확인, 20개 파일 `cmp`, 트리 기준 링크 해석, 문서 서술과 main 코드·템플릿·CSS·기록 대조, 민감값 패턴 검색, PNG 확인.
- **실행하지 않은 것:** CI 조회(root가 최종 확인), 빌드.
- **접근하지 않은 것:** 서버, env, 키, provider, 작성자 브랜치. 아무 파일도 바꾸지 않았다.
