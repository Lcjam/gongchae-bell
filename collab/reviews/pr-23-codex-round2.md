# PR23 2차 최종 변경 검수 · Codex

2026-11-19, 비작성자 root Codex. 작성자는 agy다. 대상 최종 HEAD `f4c00d4c531fef10462752d94919588bff737c52`, 추가 코드 `cc0d6dfac61b7490a19c8b2e32f1e22feb9be2cd`, main 통합 `a37b7516ef58febd1f3c62391d49c1224744b0aa`, 기준 main `517eb6b11b06a15aebeb7c0d3c94662eacd62ea7`이다.

## 판정

추가 변경에서 기존 P2-2·P2-3 수정은 수용한다. 기존 P2-1 부모 잠금 수정의 수용도 유지한다. 다른 비작성자 Claude도 동일 최종 HEAD를 [2차 검수](pr-23-claude-round2.md)에서 수용했다. source 병합 조건은 충족하며 실제 provider 발송·운영 게이트와 #7 전체 완료는 별도다.

## 직접 확인

- 원격 PR23 최종 HEAD, MERGEABLE, CI `44500989377`의 test·deploy-verify·backup-verify·secrets 및 Monitoring `44500989310`의 rules·compose-integration·alert-e2e 전부 SUCCESS를 조회했다. 코드/통합 커밋의 CI와 최종 문서 HEAD의 CI를 구별했다.
- `compose.prod.yml`의 공통 environment가 두 색에 env 3종을 전달한다. `.env.prod.example` 키, 실제 application.yml placeholder, `AlertDispatchProperties`와 `AlertScheduler` 접점을 읽었다. CI는 양 색에 1/3/false 비기본 값이 도달하는지 merged JSON을 확인한다. 새 설정 시험은 실제 application.yml 로딩으로 기본값·비기본값·scheduler 빈 비등록·동시성 상한을 검사한다.
- `AlertMatcher.findStillEligible`가 실제 `posting.first_seen_at`을 읽고, dispatcher가 R 이후 확정한 `NEW` 집합에서 성공 발송 작업의 각 NEW 항목만 새 타이머에 기록한다. `PostingSummary`의 추가 필드는 기존 명시적 payload 직렬화에 들어가지 않아 UNKNOWN 재시도의 기존 body hash를 바꾸지 않는다. job 생성→성공 타이머는 별도로 유지했다.
- 새 합성 회귀는 수집 07:05/07:30, 묶음 08:00, 발송 08:00:05를 분리한다. 이메일 성공 작업 2건의 job 지연 10초와 NEW 항목 2건의 수집 지연 5110초가 다르며, 같은 sn의 DEADLINE과 DEADLINE 전용 구독·backfill이 새 분모에 들어가지 않는다. Discord NEW와 DEADLINE의 구별도 확인했다. 운영 측정 수치로 취급하지 않는다.
- main 통합에서 중복 heartbeat 설정 블록을 제거하고 digest 빈에 명시적인 qualifier를 썼다. RegularCollector는 기존 collectorHeartbeat 이름으로 주입한다. 공용 Heartbeat의 timeout·비밀 URL 비노출·실패가 슬롯 성공을 뒤집지 않는 동작과 대조했다.
- 부모 KEY SHARE 수정과 실제 ingestor·digest·unsubscribe 경합 회귀는 보존돼 있다. 기존 [1차 검수](pr-23-codex.md)의 재현과 수정 근거를 유지한다.

## 범위와 남은 확인

이번 검수에서 앱 source·시험·구현 설정·운영 상태를 변경하지 않았다. author의 168개 로컬 시험 수는 보고서 수치이며 root가 새로 전체 Gradle 시험을 반복한 수치가 아니다. 최종 원격 7개 job은 직접 확인했다. 상대 링크로 보존한 자격 결정과 직접 참조 8개 원문의 SHA256이 모두 root와 같음을 실제 파일명으로 열거해 확인했다. 실제 공개 이메일/Discord 발송·구독/해지·Origin·개인정보 값과 보유/복원 정책은 아직 운영 완료 근거가 없다.
