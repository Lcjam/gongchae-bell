# PR24 문서 독립 검수

2026-11-18 Codex 검수 담당이 `docs/#19-review-records`의 불변 HEAD `743c2e9846bfb409bcedf123fdddc34afa4bcd03`를 읽기 전용으로 검수했다. 기준 main은 `52c172c3d6c0a76ab5d3902e1f360b779f86b572`다.

## 결론

**수용한다. 차단 결함을 발견하지 못했다.** diff는 Markdown 17개 파일·945줄 추가이며 앱 소스·시험·운영 설정 변경은 0이다. 작업 트리는 clean이고 `git diff --check`도 통과했다. 이전 검수는 각 문서의 당시 SHA·라운드·판정에 해당하는 역사 기록으로 읽었다.

## 확인한 범위

- 프론트 구조 문서의 기준 main SHA와 PR13 병합 SHA `ed3f28f38623afeccd41006548a9e4f54ed0e301`가 현재 소스 계보와 맞는다. 최초 UI 검수 `7fa3f385773b3bcf2cb76b7a8f2766c1867cc432`에서 최종 `2b152930dc145a377f40cb8d01a7b7cd6c4a175b`까지 템플릿·조건 레코드·라우트·폼 계약이 유지되고, 요청 예산·오류 응답 처리만 보완된 것을 diff로 확인했다.
- 실제 `templates/`, 정적 파일, `CatalogController`, `CatalogViews`, `JobSearchCriteria`, `FreshnessModelInterceptor`, `AlertsConfiguration`, `SubscriptionController`, `SubscriptionService`, `OriginFilter`, `SafeUrlValidator`와 문서의 트리·라우트·필터·모델·폼을 대조했다. GET 확인 화면과 POST 변경, 기본 알림 비활성, 토큰 화면 헤더, Origin 예외, 관리 대상 마스킹, 웹훅 재표시 방지 설명이 소스와 맞는다.
- 카탈로그·구독 UI의 main 포함과 #7 워커·실제 운영 배포·발송 검증의 진행 상태를 구별한 메타 설명이 적절하다. 과거 리뷰의 수정 요청을 현재 코드의 미해결 판정으로 취급하지 않았다.
- 추가된 `agy-subscriptions-cross-review.md`가 과거 `pr-13-agy.md`의 판정 정정 근거를 보존한다. 추가 문서들의 날짜·불변 SHA와 후속 검수 기록을 함께 확인했다. 과거 운영·복원 미실시 기록도 당시 범위의 설명으로 유지돼 있다.
- 변경 문서의 민감값 관련 문자열을 확인했다. 검수 기록에 로컬 사용자명 `lcj`와 절대 작업 경로 `C:/Users/lcj/orca/workspaces/my-project/issue-16-backup`, 임시 진단 경로가 포함된다. 새 프론트 구조 문서는 저장소 상대 경로와 소스 계약을 사용한다. 확인한 내용에서 실제 자격증명·개인키·사용자 이메일·실제 운영 웹훅 토큰을 발견하지 못했다.

## 비차단 관찰

`docs/design/frontend-structure.md` §4.2 신청 모델 행의 `error` 열은 `email`, `webhook`, `kinds`, `consent`, `age14`, `q`만 나열한다. 실제 `JobSearchCriteria.strict`와 `SubscriptionService.conditions`는 `ncs`, `region`, `hire`, `career`도 오류 필드로 전달하며 `new.html`의 기본 분기에서 일반 조건 오류로 표시한다. 같은 문서 §4.1은 이 값들의 400 처리를 이미 설명한다. 오류 값 목록을 완전하게 쓰려면 이 네 값과 기본 분기를 보충할 수 있다.

## 검증 경계

직접 수행한 검증은 Git 변경·메타데이터·소스 대조와 문서 문자열 확인, diff whitespace 검사다. root가 수행한 Gitleaks 및 상대 링크 검사 결과는 전달된 보고로 구분한다. 전체 앱 시험·서버·provider·외부 사이트 검증을 반복하지 않았고 실제 `.env`·키·DB·계정·서버에 접근하지 않았다. 어떤 소스나 브랜치도 변경하지 않았다.
