# 초기 운영 배포 검수 · Codex

2026-11-19 root Codex의 읽기 전용 검수다. Claude의 `claude-runtime-result.md`와 PR25는 작성자 보고서이며 아래는 root가 직접 관측한 범위를 따로 적는다.

- 09:51 UTC와 10:12:08 UTC에 공개 `https://gongchae.enjoylcjworld.com/` HTTP200을 확인했다. 1.1.1.1 A 조회는 158.179.179.104였다. bootstrap source는 `836a3c0af0625a093c8488a6d455da1fe694a5cc`다.
- 10:12 UTC `deploy.sh status`는 active/upstream blue, `BOOTSTRAP_OK`, prev/pending none이다. blue 앱·PostgreSQL·Caddy·Prometheus·Grafana 컨테이너가 올라와 있다. Grafana health의 database ok를 확인했다. PostgreSQL·앱·Prometheus는 공개 host port가 없고 Grafana만 127.0.0.1:3000으로 묶여 있다. Caddy 80/443이 공개돼 있다.
- 10:00:43 UTC 운영 DB에서 START_SCAN의 command/scan COMPLETE, 기간 2023-11-19~2026-11-19, raw 36882·posting 36879·API 예산 37·구독0을 확인했다. 이 시점 flags는 COLLECTOR_ENABLED=false, ALERTS_ENABLED=false였다. 이전 개발 DB 36768건과 구분한다.
- 실제 공개 360×800 브라우저에서 홈은 clientWidth/scrollWidth 360이었다. 목록 `jobs?career=newcomer`는 clientWidth360, scrollWidth415, visualViewport scale 약0.87이었다. 긴 지역 badge의 nowrap 하나가 오른쪽으로 넘치는 원인이었다. 이 결과를 근거로 별도 agy에게 #19 모바일 수정 요청을 전달했다. 수정 후 결과는 아직 검수 전이다.

bootstrap을 외부 G4 무중단 성공으로 세지 않는다. 현재 prev가 없어 실제 롤백 완료 근거도 없다. G4/자동 배포 스위치는 Claude 자동 권한 분류기의 거부 기록을 확인했고 정상 승인 경로 조사 중이다. root가 거부된 동작을 대체 실행하지 않았다. 현재 source로 관측한 초기 적재/HTTPS만 수용하며 정기 수집·실제 provider 알림·운영 경보 수신·외부 heartbeat·백업·복원·부하·공개 출시 전체 완료로 확장하지 않는다.

## 19:38~19:39 KST 후속: 공개 모바일 수정 적용 확인

앞 문단은 초기 검수 시점의 기록이다. 이후 실제 G4와 사후 롤백은 `claude-g4-result.md` 및 `collab/reviews/pr-25-codex.md`에서 별도로 검수했고, 검수 완료 main32 적용은 `claude-main32-deploy-result.md`에 기록됐다.

root는 기존 공개 `/jobs?career=newcomer` 탭을 reload하고 360×800·scale1·mobile/pointer:coarse 조건으로 직접 재검수했다. innerWidth/clientWidth/scrollWidth 모두360, visualViewport.scale1, 카드20개였다. 원래 넘치던 긴13개 지역 배지는 white-space normal, 폭302·오른쪽331·카드오른쪽348이었고 전체 다중값 배지의 카드 밖 overflow는0개였다. 필터 닫기를 실제 클릭한 뒤 '필터 열기'·aria-expanded=false와 client/scroll360·scale1을 확인했다.

원래 긴 배지가 있는 카드로 스크롤해 화면을 직접 보았다. 전체 지역 문자열이 두 줄로 카드 안에 표시되며 잘림/가로 축소가 없었다. [실제 공개 모바일 스크린샷](evidence/jobs-mobile-main32.png)을 원본 PNG로 보존했다. 필터/폭/렌더링의 이 확인은 root 직접 관측이며, 작성자의 synthetic desktop/모바일 시험과 구별한다. 실제 iOS/Android 기기 시험은 아니다. 정기 수집이 꺼져 있어서 푸터가 '아직 완전 수집 기록이 없습니다'라고 표시하는 것은 그대로 남는다.
