# main32 실제 공개 화면 검수

2026-11-19 root Codex의 읽기 전용 검수다. 배포 대상 main32와 실제 운영 기록은 `claude-main32-deploy-result.md`, 모바일 실측/스크린샷은 `codex-runtime-review.md` 후속에 있다.

- 공개 목록을 1280×800·scale1 desktop으로 바꿔 직접 측정했다. innerWidth1280, clientWidth/scrollWidth1265(세로 스크롤바 포함), scale1, desktop표block·20행, 모바일카드none이었다. 모바일 전용 CSS가 desktop 표를 바꾸지 않았음을 확인했다.
- 실제 페이지 링크에서 상세 `/jobs/306337`과 기관 `/institutions/C0856`을 얻었다. HTTP GET으로 홈·해당 상세·기관 모두200이며 각 페이지 제목과 맞았다.
- 합성 검색어 `gongchae-empty-qa-20261119`의 목록은200과 '조건에 맞는 공고가 없습니다' 안내를 반환했다.
- 존재하지 않는 long 범위 최대 SN `/jobs/9223372036854775807`은404와 '페이지를 찾을 수 없습니다' 화면을 반환했다.
- 공개 `/actuator/prometheus`는404였고 Prometheus HELP 텍스트를 반환하지 않았다.

실제 원천 API·메일·Discord 구독 발송은 하지 않았다. desktop 폭은 브라우저 DOM 직접 측정이며 뒤의 GET검수는 렌더링/브라우저 조작과 구별한다. 3년 데이터에 대한 부하·실제 기기·비활성/지연 상태 고의 유도·개인정보 공개 게이트와 전체 #19 완료를 이 확인으로 대체하지 않는다.
