# PR26 모바일 목록 줄바꿈 검수 · Codex

2026-11-19 비작성자 root Codex. 작성자는 추가 agy다. 최종 HEAD `944bbf5cec3170735dff9afe2d26d057d99b9a9f`, 코드 `18709cfba5fd08329100ae9e33c3acd11e92df00`, 기준 main517이다.

## 판정: 수용

root가 공개 360px 목록에서 실제 재현한 긴 지역 배지 overflow를 관련 모바일 카드 범위에서 고쳤다. 다중 값 전체 텍스트를 유지해 접으며 hide/ellipsis로 가리지 않는다. 두 번째 비작성자 [Claude 검수](pr-26-claude.md)도 수용이다.

## 확인 근거

- 원래 공개 화면의 clientWidth360·scrollWidth415·visualViewport scale 약0.87과 원인 배지 폭384.90625/오른쪽413.90625는 root 직접 측정이다.
- 실제 diff를 읽었다. 템플릿 변경은 `d-md-none` 카드 안의 클래스뿐이다. 세 다중 값 배지만 white-space normal·overflow-wrap anywhere·max-width100%를 받고, 기관명은 flex 축소/긴 토큰 접기를 허용한다. D-day와 상태 묶음은 shrink를 막는다. desktop 표·필터·페이지네이션·URL·backend는 바꾸지 않았다. CSS 선택자도 이 카드 범위에 한정된다.
- 작성자의 실제 앱+격리 PG 측정은 360px에서 desktop scrollbar 때문에 client/scroll345였으므로 모바일 에뮬레이션 결과와 동일한 분모로 취급하지 않았다. Claude가 별도 정적 카드로 같은 viewport meta·커밋된 Bootstrap/CSS를 사용해 수정 전 지역 배치384.9/413.9를 재현했고, 수정 뒤 client/scroll360·scale1·배지 우측331 이하를 직접 확인했다. 실제 앱 렌더링과 정적 카드 재현의 범위를 구별한다.
- 최종 원격 HEAD를 직접 조회했다. CI `44503831450`의 test·deploy-verify·backup-verify·secrets, Monitoring `44503831374`의 rules·compose-integration·alert-e2e 전부 SUCCESS와 MERGEABLE이다. 코드 커밋의 성공만으로 최종 문서 HEAD의 성공을 대체하지 않았다.

이번 검수에서 source·test·구현 설정을 바꾸지 않았고 root가 전체 Gradle/브라우저 시험을 다시 실행하지 않았다. 한글 단어 중간 줄바꿈은 비차단 UI 선택이며 별도 프론트 작업에서 다룰 수 있다. 실제 운영 배포 뒤 공개 모바일 360px 재측정은 아직 남았다. 이 source 병합으로 #19 전체 공개 감사를 완료 처리하지 않는다.
