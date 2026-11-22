# 공개 HTTPS 화면 검수 · root Codex

2026-11-22 **13:31~13:36Z / 22:31~22:36 KST**. 실제 `https://gongchae.enjoylcjworld.com`을 Orca 내장 Chromium의 새 전용 탭에서 읽기 전용으로 확인했다. PR41의 합성 fixture QA를 재실행하거나 실제 운영 검증으로 바꾼 기록이 아니다. 구현·운영 env·DB·계정·권한·정기 수집·알림 설정은 수정하지 않았다.

## 직접 확인한 결과

| 화면/상태 | 실제 표시 및 관측 | 원자료 |
|---|---|---|
| 홈1280 | 회색 hero `rgb(245,245,247)`, 파란 accent `#0071e3`, 마감 공고 카드, 오늘 시작 공고 빈 상태, 알림 준비 중 | [PNG](evidence/home-desktop.png), [JSON](evidence/home-desktop.json) |
| 목록1280 | 표와 검색 결과462건, 알림 준비 중 | [PNG](evidence/jobs-desktop.png), [JSON](evidence/jobs-desktop.json) |
| 목록360 | 모바일 카드와 접힌 필터, 검색 결과462건 | [PNG](evidence/jobs-mobile.png), [JSON](evidence/jobs-mobile.json) |
| 필터360 펼침 | 실제 버튼 클릭 뒤 ‘필터 닫기’/`aria-expanded=true`, 폼 표시, 가로 넘침 없음 | [PNG](evidence/jobs-mobile-expanded.png), [JSON](evidence/jobs-mobile-expanded.json) |
| 빈 검색360 | 고정 없는 검색어 GET, 결과0건/조건에 맞는 공고 없음/필터 초기화, 200 | [PNG](evidence/empty-mobile.png), [JSON](evidence/empty-mobile.json) |
| 상세360 | 실존306769, 기존 자격/우대/전형 설명 및 ‘상세 정보 미수집’, 원문/기관 이력 링크 표시 | [PNG](evidence/detail-mobile.png), [JSON](evidence/detail-mobile.json) |
| 기관360 | C0066 한국동서발전(주) 이력/통계 표시 | [PNG](evidence/institution-mobile.png), [JSON](evidence/institution-mobile.json) |
| 개인정보1280 | 운영 준비용 고지와 미정 항목 표시, 200. 법률 준수·운영 준비 완료 판정 아님 | [PNG](evidence/privacy-desktop.png), [JSON](evidence/privacy-desktop.json) |
| 신청360 | ‘알림 신청은 준비 중입니다’, 조회/홈 링크, 200. 실제 신청·메일·웹훅 전송 없음 | [PNG](evidence/disabled-mobile.png), [JSON](evidence/disabled-mobile.json) |
| 미존재 상세360 | 999999999999, 찾을 수 없음/복귀 링크, **실제 HTTP404** | [PNG](evidence/missing-mobile.png), [JSON](evidence/missing-mobile.json) |

10개 화면/상태 JSON에서 header/main/footer/skip-link 각각1개, header CTA0·query 포함 알림 신청 링크0을 확인했다. 데스크톱 viewport1280에서 문서폭1265/1265(스크롤바 제외), 모바일360에서360/360이다. 기관 표 내부 scroll 같은 개별 컴포넌트 전체 너비를 새로 측정한 것은 아니다.

홈·모바일 목록·펼친 필터·개인정보·404 PNG는 직접 육안으로 열었다. 나머지는 DOM/HTTP와 캡처 생성까지 확인했으며 모든 이미지 픽셀/스크린리더/전면 접근성을 검사한 것으로 쓰지 않는다.

공개 CSS를 같은 브라우저에서 GET 후 SHA256을 계산했다. **16300bytes, 200, `87c7622b17511f4dfb025cad83545ee27793f48aefb365a3462cb4e1da4a254a`**로 PR39 최종본과 같다([원자료](evidence/public-css.json)). 서버 active greenE020은 별도 [13:24:15Z metadata 관측](../2026-11-22-recovery-barrier/runtime-baseline-2224.md)이며, 이 화면 측정 시점의 새 SSH/배포를 뜻하지 않는다.

## 요청/콘솔·증거 범위

캡처된 [network](evidence/network.json)는34개 entry/truncated=false, 모두GET이다. 31개는 같은 공개 origin의 HTTP이고3개는 Bootstrap의 inline `data:image/svg+xml`로 외부 HTTP가 아니다. HTML 응답9개는 정상8개200/미존재1개404다. 이 entry 수를 전체 네트워크 패킷 수·성능 시험·실사용자 수로 쓰지 않는다. [console](evidence/console.json)은 entries0/truncated=false였다.

조작은 고정 공개 URL GET/viewport 변경/모바일 필터 토글뿐이다. 신청 POST·외부 원문 링크·공공 API·메일/Discord provider 발송은 실행하지 않았다. CSS hash GET에는 internal header를 붙였다. 일반 브라우저 navigation에는 이 header를 따로 설정하지 않았으므로 이 소량의 내부 검수 방문을 실제 사용자 성과로 세지 않는다. 본인 생성 탭만 끝에 닫았다.

PNG10/JSON13은 ignored 검수 디렉터리에서 이 evidence 폴더로 내용을 바꾸지 않고 복사했고 작업 트리에서23/23 raw bytes 동일을 확인했다. 아직 공개 Git blob의 개행 정규화까지 검수한 기록은 아니다. raw JSON은 공개 페이지 텍스트/DOM·고정 시험 query·응답 정보이며 내부 Orca runtime ID/응답 wrapper는 제외했다.

초기 검수 JS의 attribute selector 인용이 Windows argv에서 사라져 첫 eval이 실패했다. DOM probe를 인용 없는 필터 방식으로 고친 뒤 실제 결과를 수집했다. 초기3개 JSON의 `filter.expanded` helper selector는 잘못된 id를 읽어null이나, 실제 버튼 배열의false/true가 남아 있다. 펼침 측정부터 실제 id `jobs-filter-panel`로 바로잡았다. 원자료는 사후 덮어쓰지 않았고 앱 코드 결함으로 분류하지 않는다. 콘솔 인쇄의 Python cp949 문제는 UTF8 출력으로 바로잡았으며 저장한 UTF8 DOM/화면 문자는 정상이다.

## 실제 운영 전에 남은 항목

- 푸터는 모두 ‘아직 완전 수집 기록이 없습니다’였다. 정기 수집 미개시와 일치하며 지연 배너가 없다는 사실을 최신 데이터/정상 주기 수집의 증거로 쓰지 않는다. 실제 지연/회복 분기는 이 방문에서 발동하지 않았다.
- 개인정보 담당자·연락처·버전/시행일·호스팅/공급자 보관/국외 처리·대장 정리 시점은 미정으로 표시된다. 사용자가 이미 준 연락처를 다시 묻지 않고 M2 전 운영 설정·고지에 반영해야 한다. 정책의 실제 근거와 백업/정비/복원 게이트도 별도다.
- 실제 양채널 신청→확인/수정/발송/해지, 유효 token 관리 화면, 지연/회복, 타 브라우저·실기기·보조기기, 실제 collector중 성능/가용성은 미검증이다. 공개 화면이 렌더됐다는 이유로 PRD§7 전체를 완료 처리하지 않는다.
