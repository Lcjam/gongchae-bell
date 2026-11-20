# PR #26 모바일 배지 줄바꿈 검수 · claude

2026-11-19 · 1차 비작성자 검수 · 요청: root codex, [`2026-11-19-resume/claude-mobile-review-request.md`](../2026-11-19-resume/claude-mobile-review-request.md) · 작성자: agy(추가 세션)

## 대상

| 항목 | 값 |
|---|---|
| 최종 원격 HEAD(`fix/#19-mobile-badges` = `refs/pull/26/head`, `git ls-remote`로 직접 확인) | `944bbf5cec3170735dff9afe2d26d057d99b9a9f` |
| 코드 커밋 | `18709cf` (`jobs.html` 12줄, `app.css` 3줄) |
| 문서 커밋 | `944bbf5` (`agy-mobile-fix-result.md`만 추가, source 변경 없음) |
| 기준 | `517eb6b` |
| PR 상태(`gh pr view`, 조회 시점) | OPEN, draft, MERGEABLE |

요청서의 SHA와 같다. 바뀐 것이 없다.

## 판정: 수용

긴 직무·지역·고용형태 배지를 숨기거나 자르지 않는다. 카드 폭 안에서 줄바꿈해 전체 내용을 읽을 수 있다. D-day·상태·구분·문자 주의 같은 짧은 배지는 `nowrap`을 유지한다. desktop 표, 필터, 페이지네이션, backend는 바뀌지 않았다. 필요한 수정은 없다. 아래 P3 하나는 선택 사항이다.

## 1. 변경 범위 대조

- `jobs.html`의 변경은 모두 모바일 카드(`d-md-none`) 안에 있다. 기관명 링크에 `job-card-inst`, D-day 묶음에 `flex-shrink-0`, 배지 래퍼에 `job-card-badges`, 다중 값 배지 세 개에 `job-meta-badge`를 붙였다. desktop 표(`<td>` listJoin), 필터, 페이지네이션 마크업은 diff가 0줄이다.
- `app.css` 세 줄은 모두 `.job-card-inst`·`.job-card-badges` 범위 선택자라 다른 화면에 퍼지지 않는다.
- 같은 패턴(다중 값을 `nowrap` 배지 한 개로 표시)이 다른 템플릿에 있는지 `git grep`으로 찾았다. `home.html`·`institution.html`·`job-detail.html`의 배지는 짧은 값뿐이다. 상세 화면의 다중 값은 `<dd>` 일반 텍스트다. 범위를 넓혀야 할 곳은 없다.

## 2. 직접 한 한정 검증 (모바일 에뮬레이션 조건)

**작성자 측정과의 차이:** 작성자 측정은 360px에서 `clientWidth=345`였다. 데스크톱 스크롤바가 있는 조건이고, root가 재현한 모바일 조건(meta viewport, `visualViewport` 자동 축소)과 다르다. 그래서 이 조건만 따로 확인했다.

**방법:** 자기 Orca 탭을 열고 `orca viewport --width 360 --height 800 --scale 1 --mobile`로 설정했다. `pointer: coarse` 적용을 확인했다. 정적 페이지 두 개를 측정했다.
- 수정 전: `517eb6b`의 카드 마크업과 `app.css`.
- 수정 후: `944bbf5`의 카드 마크업과 `app.css`.
- 공통: 저장소에 들어 있는 Bootstrap 5.3.8, layout과 같은 viewport meta와 `container`.
- 내용: root가 실측한 13개 지역 문자열 그대로, 긴 기관명, 14개 직무, 6개 고용형태, 공백 없는 긴 고용형태 토큰, 짧은 값 카드.
- 페이지와 측정 스크립트는 scratchpad에만 두었다. 측정 뒤 탭을 닫았다.

| 조건 | innerWidth | clientWidth | scrollWidth | visualViewport.scale | 결과 |
|---|---:|---:|---:|---:|---|
| 수정 전 | 1032 | 360 | 1032 | 0.348837 | 지역 배지 **너비 384.9, 오른쪽 413.9**. root 공개 화면 실측(384.90625, 413.90625)과 같다. 즉 이 재현은 운영 화면과 같은 배치다. 합성 14개 직무 배지(1002.8px)가 더 길어서 전체 폭은 root의 415보다 크게 나왔다 |
| 수정 후 | 360 | 360 | 360 | 1 | 모든 배지의 오른쪽이 331 이하다(카드 내용 끝 331, 카드 끝 348). 직무 61.9px(3줄), 지역·고용형태·공백 없는 토큰 각 34.6px(2줄)로 카드 안에서 줄바꿈된다. D-day, 상태, 구분, 문자 주의는 `nowrap`이고 위치가 수정 전과 같다. 긴 기관명은 수정 전후 모두 3줄로 접힌다(한글은 원래 줄바꿈이 가능하다. `job-card-inst`는 공백 없는 라틴 문자열 같은 경우에 대비한 방어로 본다) |

스크린샷으로도 확인했다. 수정 전은 전체가 축소되고 배지가 카드 밖으로 나간다. 수정 후는 360px 그대로 모든 배지가 카드 안에 있다. 파일은 Orca가 저장한 임시 PNG라 저장소에는 남기지 않았다.

**작성자 측정과의 대조:** 작성자의 실제 앱 측정(345폭, 데스크톱 스크롤바 조건)도 같은 결론이다. 배지 폭 287, 오른쪽 316, `overflowsCard=false`. 필터 토글, 2페이지, 1280 desktop도 작성자가 실제 앱으로 확인했다. 이 부분은 변경된 마크업이 없어 다시 측정하지 않았다.

## 3. 비차단 관찰

| # | 등급 | 내용 | 제안 |
|---|---|---|---|
| M1 | P3(선택) | 한글은 음절 사이에서도 줄을 바꿀 수 있어, 지역이나 직무 이름이 중간에서 끊긴다(예: "경 / 남", "법 / 률·경찰"). 읽을 수는 있지만 이름 단위로 끊기면 더 읽기 쉽다. 제목(`job-card-title`)도 이미 같은 방식이라 이번 PR이 새로 만든 문제는 아니다 | 원하면 `.job-meta-badge`에 `word-break: keep-all`을 더한다. 쉼표 뒤 공백에서만 줄을 바꾸고, `overflow-wrap: anywhere`는 공백 없는 긴 토큰의 안전망으로 남긴다. 사용자의 프론트 작업과 겹치면 그쪽에서 정해도 된다 |
| M2 | 정보 | `.job-card-badges .badge { line-height: 1.3 }` 때문에 짧은 배지도 높이가 17.8px에서 21px로 커진다 | 여러 줄 배지와 높이를 맞추는 의도된 변화로 본다. 결함 아님 |

## 4. 확인 범위

- **직접 한 것:**
  - `git fetch`, `git ls-remote`, `gh pr view`로 SHA와 PR 상태 확인. diff 전체와 다른 템플릿 grep.
  - Orca 브라우저 모바일 에뮬레이션에서 수정 전후 정적 재현 측정과 스크린샷.
- **실행하지 않은 것:**
  - 실제 앱과 DB를 띄운 렌더링: 작성자가 실행했다. 내 재현은 Thymeleaf를 거치지 않은 정적 마크업이다. 다만 마크업은 템플릿의 카드 구조를 그대로 옮겼고, CSS는 커밋된 파일이다.
  - Gradle 빌드, CI 조회: CI는 작성자 보고와 root의 최종 확인에 맡긴다.
  - 실제 기기(iOS Safari, Android Chrome).
- **접근하지 않은 것:** 공개 운영 URL(root 탭과 겹치지 않게 하려고), 서버, env, 키, 작성자 브랜치. source, 테스트, 설정은 바꾸지 않았다.

## 5. 남은 확인

- main 병합 뒤 배포 담당자가 적용하면, 공개 `/jobs?career=newcomer`를 360×800 모바일 조건으로 다시 잰다(`clientWidth=scrollWidth=360`, `visualViewport.scale=1`). root가 할 예정이다.
- 실제 모바일 브라우저 한 종 이상에서 눈으로 확인한다(선택).
