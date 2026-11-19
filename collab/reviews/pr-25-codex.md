# PR25 실제 운영 기록 검수 · Codex

2026-11-19 비작성자 root Codex. 작성자는 primary Claude다. 대상 고정 HEAD `013ec5dc278f4cbe23f33d27e126654e1f9ba96b`, 기존 ffc9619의 초기·2라운드 기록과 추가 G4 기록이다. 이 기록은 실제 관측과 source/사용자 공개 완료를 구별한다.

## 판정

초기 배포·초기 적재·정상 재실행·운영 경보 전송·정상 G4 전환/사후 롤백의 기록 범위는 수용한다. 같은 최종 HEAD의 agy도 `pr-25-agy-round2.md`에서 G4 차분을 수용했다. 최종 CI44506128462의 4잡과 Monitoring44506128474의 3잡 모두 SUCCESS이며 MERGEABLE임을 root가 재조회했다. source/문서 병합 조건은 충족하며 실제 출시 게이트는 아래처럼 남긴다. 초기/2라운드 ffc9619의 agy 수용은 `pr-25-agy.md`에 있다.

## root 직접 확인

- `codex-runtime-review.md`의 DNS·공개HTTPS·실제blue836·DB36879·API37·구독0·모니터링 포트/health 관측은 root 직접 읽기 전용 확인이다. 여러 권한 NS·이미지 해시·정상 재실행 세부 지문과 운영 경보 발동/해소 수치는 작성자 기록/독립 agy 메타 검수와 구별한다. 기존 실측 시각과 신규 시각을 합쳐 동시 측정처럼 쓰지 않았다.
- GitHub run `44504907458`의 Build & Push와 Blue/Green Deploy 잡 모두 success를 직접 조회했다. 아티팩트14519302271의 실제 JSON을 내려받았다. execution_context는 github-actions-external, target deploy_id는 bluegreen-517eb6b11b06-44504907458-1이다. 10:22:57~10:24:04Z에 total45·ok45·failures0·5xx0·연결/TLS0·timeout0·기타비200=0, p95/max588/633ms였다.
- run `44505579286`의 실제 rollback 배포 잡 success를 직접 조회했다. Build & Push skipped는 설계된 경로다. 아티팩트14518578388의 실제 JSON은 rollback-32e71e4bb56c-44505579286-1, execution_context github-actions-external, 10:25:58~10:26:41Z total24·ok24·실패종류 전부0, p95/max1194/1407ms다.
- 10:30:27Z 실제 서버를 다시 읽었다. active/upstream blue, active_sha836a3c0, active_deploy_id는 위 rollback id, prevgreen517eb6b, pending모두none, last_resultROLLBACK_OK였다. blue 앱·DBhealthy·Caddy·Prometheus·Grafana가 실행 중이고 공개 필터 목록 HTTPS200이다.
- PRD G4는 고정 외부 전·중·후 요청 실패0을 요구한다. 위 두 JSON과 author 타임라인은 정상 전환과 사후 롤백의 해당 관측을 입증한다. 지역별 지연 원인을 단일 측정으로 확정하지 않았다. G7·전체 성능/자원 기준선으로 쓰지 않는다.

## 범위·남은 항목

- 실제 두 거부 원문은 AUTO_DEPLOY_ENABLED 변경으로 시작한 호출이었다. root가 원문을 대조해 지속 설정 변경을 뺀 정확한 SHA의 단독 dispatch를 정상 경로로 요청했고 허용됐다. 권한 규칙/모드를 변경하지 않았으며 자동 배포 스위치 거부가 해소됐다고 하지 않는다.
- 사후 rollback 성공은 실패를 유도한 G4_FAIL→abort/구버전 보존 리허설과 다르다. PRD §7의 실패 롤백·장애 훈련은 아직 남았다. #14 전체 완료로 처리하지 않는다.
- 운영 Discord는 실제 API 전송 성공까지다. 사람의 채널 화면 확인은 질문을 보냈고 답변 대기다. 실제 구독·메일·정기 수집·UptimeRobot/Healthchecks·백업·외부 사본·복원·부하와 공개 출시 조건도 아직 남았다.
- 현재 main32 source는 PR23·26까지 검수/병합됐지만 본 PR25의 G4 대상은 M1 source517이고 rollback 대상836이다. workflow main SHA·deploy_id 문자열과 실제 대상 이미지 SHA를 구별한다. main32 실운영 적용은 후속 별도 task이며 이 고정 검수에 섞지 않는다.

검수 중 source·test·구현 설정·운영 상태를 바꾸지 않았다. 실제 서버에는 상태/HTTP 읽기만 했다. root 아티팩트 검수 사본은 ignored `.tools/review/g4-44504907458`, `.tools/review/g4-44505579286`에 있다.
