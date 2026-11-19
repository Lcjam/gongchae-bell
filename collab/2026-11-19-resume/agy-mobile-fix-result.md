# 공개 모바일 목록 가로 넘침 수정 결과 (#19)

요청: `collab/2026-11-19-resume/extra-agy-mobile-fix.md` (보낸 에이전트: root Codex, #19 공개 화면 검수 수정 1라운드)

## 1. Source · 브랜치 · PR · CI

| 항목 | 값 |
|---|---|
| 기준 `main` | `517eb6b11b06a15aebeb7c0d3c94662eacd62ea7` |
| 작업 브랜치 | `fix/#19-mobile-badges` |
| 코드 커밋 SHA | `18709cfba5fd08329100ae9e33c3acd11e92df00` (`fix(web): wrap long metadata badges on mobile jobs cards (#19)`) |
| 최종 head | 이 결과 파일(`collab/2026-11-19-resume/agy-mobile-fix-result.md`)을 커밋한 문서 후속 커밋 (코드·CSS·테스트 변경 없음) |
| Draft PR | [#26](https://github.com/Lcjam/gongchae-bell/pull/26) (`Refs #19`) |
| 로컬 Gradle (`18709cf`) | `./gradlew --no-daemon check bootJar` 통과 (`BUILD SUCCESSFUL`), `git diff --check` 통과 |
| CI (`18709cf`) | CI [44502960958](https://github.com/Lcjam/gongchae-bell/actions/runs/44502960958) (`test`, `deploy-verify`, `backup-verify`, `secrets` 4잡 모두 `success`) · Monitoring [44502960929](https://github.com/Lcjam/gongchae-bell/actions/runs/44502960929) (`rules`, `compose-integration`, `alert-e2e` 3잡 모두 `success`) |

### 수정 파일과 범위

- `src/main/resources/templates/jobs.html`: 모바일 카드(`d-md-none`) 상단 기관명 링크에 `job-card-inst`, D-day·상태 묶음에 `flex-shrink-0`을 추가하고, 하단 메타 배지 래퍼에 `job-card-badges`, 긴 다중 값 배지(직무 `ncsNames`·지역 `regionNames`·고용형태 `hireNames`)에 `job-meta-badge` 클래스를 부여했다.
- `src/main/resources/static/css/app.css`:
  - `.job-card-inst { min-width: 0; overflow-wrap: anywhere; }`
  - `.job-card-badges .badge { line-height: 1.3; }`
  - `.job-card-badges .job-meta-badge { white-space: normal; text-align: left; overflow-wrap: anywhere; max-width: 100%; }`
- `D-day`·`상태`·`구분`(`recrutSeLabel`)·`문자 주의` 등 짧은 배지는 `white-space: nowrap`을 유지했고, 기존 desktop 테이블·필터·페이지네이션·backend 계약·공고/코드 값·`docs/design/frontend-structure.md`·타 작성자 파일·실서버/env/키/개발DB/provider는 변경·접근하지 않았다. 스타일 속성 복사 테스트는 추가하지 않고 기존 통합 렌더링 검증을 재사용했다.

## 2. 화면 / 폭 측정 (기존 root 재현 vs 수정 후 로컬 실앱 검증)

### 기존 근거 (수정 전, root 공개 화면 재현)

- 대상: `https://gongchae.enjoylcjworld.com/jobs?career=newcomer` (360×800)
- 실측: `clientWidth=360`, `scrollWidth=415`, `innerWidth=415`, `visualViewport.scale≈0.869565`
- 원인: 모바일 카드 지역 `span.badge.text-bg-light`(`서울, 대전, 대구, 부산, 울산, 경기, 강원, 충남, 충북, 경북, 경남, 전북, 전남광주`)가 Bootstrap `white-space: nowrap`으로 너비 `384.90625px`·오른쪽 `413.90625px`까지 확장되어 카드 밖으로 넘침.

### 수정 효과 검증 (수정 후 `18709cf`, 로컬 실앱 + 일회성 DB)

- 환경: `build/libs/gongchae-bell.jar`를 일회성 `postgres:17` 컨테이너에 연결해 로컬 실행(`127.0.0.1:18080`, `COLLECTOR_ENABLED=false`, `ALERTS_ENABLED=false`)하고 전용 Orca 브라우저 탭에서 측정했다.
- 합성 데이터: 실제 재현 길이와 동일한 13개 지역(`서울, 대전, 대구, 부산, 울산, 경기, 강원, 충남, 충북, 경북, 경남, 전북, 전남광주`), 14개 직무, 6개 고용형태, 긴 기관명, `문자 주의`, 23건 페이지네이션 포함.

| 뷰포트 / 경로 | `innerWidth` | `clientWidth` | `scrollWidth` | `vvScale` | 주요 확인 |
|---|---:|---:|---:|---:|---|
| 360×800 `/jobs?career=newcomer` | 360 | 345 | 345 | 1 | `desktopVisible=false`, `mobileVisible=true`, 카드(`left=12`, `right=333`, `width=321`) 내 모든 배지 `overflowsCard=false`. 13개 지역 배지 `width=287`, `right=316`, `height=34.6`(2줄), 14개 직무 배지 `width=287`, `right=316`, `height=61.9`(4줄), 6개 고용형태 배지 `width=287`, `right=316`, `height=34.6`(2줄)로 카드 내부 래핑. `D-0`·`확인 필요`·`D-5`·`진행 중`·`신입`·`신입+경력`·`문자 주의` `white-space: nowrap` 유지 및 스크린샷 육안 확인 |
| 360×800 `/jobs` (필터 토글) | 360 | 345 | 345 | - | 초기 `filterHidden=true`, `필터 열기` 클릭 시 `필터 닫기`로 전환, `maxBadgeOverflow=-17` (카드 우측 안쪽 17px 여유) |
| 360×800 `/jobs?career=newcomer&page=2` | 360 | 345 | 345 | - | `cards=3`, `activePage="2"`, 가로 넘침 0 |
| 1280×800 `/jobs?career=newcomer` | 1280 | 1265 | 1265 | 1 | `desktopVisible=true`, `mobileVisible=false`, `tableRows=20`, `paginationCount=1`, 스크린샷으로 기존 desktop 테이블·필터·배지 유지 확인 |

- 검증 후 전용 Orca 브라우저 탭 종료, 로컬 앱 프로세스 중지, 일회성 `postgres:17` 컨테이너 삭제 완료.

## 3. 남은 실제 배포 확인

- PR [#26](https://github.com/Lcjam/gongchae-bell/pull/26) 비작성자 리뷰 및 root 검수 후 `main` 통합
- 배포 담당자의 실제 운영 배포 적용 뒤 공개 `https://gongchae.enjoylcjworld.com/jobs?career=newcomer` 360×800 재검수 (`clientWidth=scrollWidth=360`, `visualViewport.scale=1` 및 긴 지역 배지 래핑 확인)
