# PR #10 검수 · codex

- 검수 head: `4334af809e3d8ce97205e29dcf9a22d28fcddaa6`.
- 판정: 지정 head의 요청된 수정 수용. 공식 상세 응답과 #3/#4/#5 합본 검증은 남아 있다.
- 초기 결함과 재검수: [05-codex-catalog-review.md](../2026-11-15-review/05-codex-catalog-review.md).
- Claude 교차 의견에 대한 결정: [10-review-decisions.md](../2026-11-15-review/10-review-decisions.md).

완료된 diff·코드·테스트를 대조했다. 전형 정보는 확인된 공식 필드만 이름을 붙여 출력하고 미상 형식은 별도 안내한다. 첨부 필드와 HTTP(S) 링크 검증, 오늘까지 시작한 공고를 기준으로 하는 3년 이력 표시·경계 테스트, 성공한 상세 조회의 집계, 마감 공고만 존재할 때의 빈 상태, `APP_BASE_URL` 안내가 요청대로 반영됐다. 이전 검수의 오류 화면 기준 시각·DB 장애 안전 경로와 HTML 순서·필터·통계 검증도 유지된다.

Sol은 같은 head를 독립 검수했고 [CI 43656050416](https://github.com/Lcjam/gongchae-bell/actions/runs/43656050416) `check bootJar`와 secrets 성공을 확인했다. 세부 증거는 [pr-10-sol.md](pr-10-sol.md). root는 이 재검수에서 브라우저와 로컬 테스트를 다시 실행하지 않았다.

실제 상세 응답 → #4 저장 → 웹 표시, 라벨 단락 우대 표시, 미수집/실패/DONE 구분은 합본에서 확인한다. 이 기록은 그 경로와 공개 배포 완료를 대신하지 않는다.
