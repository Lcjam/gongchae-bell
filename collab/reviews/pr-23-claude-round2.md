# PR #23 2차 차분 검수 · claude

2026-11-19 · 요청: root codex, [`2026-11-19-resume/restart-claude-pr23-review.md`](../2026-11-19-resume/restart-claude-pr23-review.md) · 1차 기록: [`pr-23-claude.md`](pr-23-claude.md)

## 대상

| 항목 | SHA |
|---|---|
| 최종 HEAD(원격 `feat/#7-alert-delivery`, 내가 `git ls-remote`로 직접 확인) | `f4c00d4c531fef10462752d94919588bff737c52` |
| 2차 수정 코드 | `cc0d6df` (부모 `cf50131` = 1차 `b50d3d0` + main `52c172c` 통합) |
| main 통합 | `a37b751` (`517eb6b` 병합, 충돌 해결: `compose.prod.yml`) |
| 기준 main | `517eb6b` |
| 1차 검수 head | `b50d3d0` |

최종 SHA는 요청서와 같다. 검수 worktree `pr23-claude-review`에서 fetch와 읽기만 했다. 앱 코드, 테스트, 설정, 작성자 브랜치는 바꾸지 않았다.

## 판정: 수용

2차 수정 네 가지(env 전달, NEW 수집→발송 지연, heartbeat 공용화, 자격 결정 원문 보존)가 1차 P2 두 건과 sol의 깨진 링크 관찰을 닫는다. 1차에서 수용한 부모 잠금도 그대로다. **새 결함(P1·P2)은 찾지 못했다.** 아래 관찰은 모두 비차단이며 새 필수 요구가 아니다.

## 1. 항목별 확인

| 항목 | 결과 | 근거 |
|---|---|---|
| 부모 KEY SHARE 보존 | 보존됨. `b50d3d0..f4c00d4`에서 `AlertOutbox`·`DeliveryLedger`·`SubscriptionService`·`PostingIngestor`의 diff가 0줄이다. `SELECT 1 … FOR KEY SHARE`는 `AlertOutbox.java:56`에 그대로 있다 | `git diff --stat` |
| 양 색 env 전달(1차 F1) | 닫힘. `x-app-common.environment`에 세 키가 `${…:-2}`·`${…:-20}`·`${…:-true}`로 들어갔다. 이 앵커를 `app-blue`·`app-green`이 함께 쓴다. `:-` 형태라 `.env.prod.example`처럼 빈 값을 줘도 기본값이 된다. CI `deploy-verify`는 비기본값(1·3·false)으로 두 색의 merged JSON environment를 검사한다(`ci.yml:46`, f4c00d4에 남아 있음). main 통합 충돌 해결 뒤에도 `HEARTBEAT_COLLECTOR_URL`과 함께 세 키가 남아 있다 | `git show --cc a37b751` |
| application.yml 바인딩 | 닫힘. `alerts.dispatch.*: ${ALERTS_…:기본}`(`application.yml:83-86`). `AlertDispatchPropertiesTest`는 `ConfigDataApplicationContextInitializer`로 실제 yml을 읽어 세 가지를 확인한다: 기본값과 스케줄러 등록, 비기본값과 `AlertScheduler` 미등록, 5일 때 시작 실패. 이것과 CI의 Compose 검사를 합치면 `.env`부터 컨테이너 env, Spring 속성까지 이어진다. 남는 한 고리인 실제 컨테이너 OS env → placeholder는 Spring 표준 동작이라 따로 시험하지 않았다 | 테스트 원문 |
| NEW 수집→발송 지연(1차 F2) | 닫힘. 성공(`finishAsync` 커밋 성공)일 때 `rechecked.newPostings()`의 항목마다 `posting.first_seen_at → finishedAt`을 `alerts.delivery.collection.latency{kind}`에 기록한다 | `AlertDispatcher.java` diff |
| ├ 분모 | 성공 발송된 (작업, NEW 항목) 1건이다. 같은 sn의 DEADLINE 항목과 DEADLINE 전용 작업은 넣지 않는다. UNCERTAIN과 실패는 SENT가 아니므로 기록하지 않는다. unknown 뒤 SENT는 한 번만 기록한다 | 코드 경로 |
| ├ 재확인 payload와의 관계 | HTTP는 `eligible.sameSns(rechecked)`이고 해시가 같을 때만 나간다. 따라서 `rechecked.newPostings()`는 실제로 보낸 NEW 집합과 SN이 같다. `firstSeenAt`은 `PostingSummary`에만 더해졌고 `AlertPayloadBuilder`는 diff가 0이다. **payload 해시 입력이 바뀌지 않았으므로**, 배포 중 unknown 재시도 때 옛 앱과 새 앱의 해시가 같다. `sameSns`는 SN만 비교하므로 레코드 필드 추가의 영향이 없다 | `git diff` |
| ├ 혼합 kind 시험 | `collectionToDeliveryLatencyTracksNewPostingsAcrossPreBundleWaitAndMixedKinds`는 같은 sn의 NEW+DEADLINE, backfill DEADLINE, DEADLINE 전용 구독, DISCORD_DEADLINE이 섞인 상태에서 다음을 확인한다: 작업 지연 10초, 수집 지연 3305+1805초, DISCORD_DEADLINE 0건. 08:00 묶음 전 대기가 들어가는 것을 숫자로 입증한다. Prometheus 노출 시험(`rfc8058…`)도 자기 안에서 NEW를 발송하므로 테스트 순서에 기대지 않는다 | 테스트 원문 |
| heartbeat 공용화·설정 충돌 | 닫힘. `DigestHeartbeat`를 지우고 `AlertsConfiguration`의 `@Bean digestHeartbeat` → `new Heartbeat("digest", url)`로 바꿨다. `AlertBatchGenerator`는 `@Qualifier("digestHeartbeat")`로 받는다. `RegularCollector`는 매개변수 이름 `collectorHeartbeat`로 받는다. `RegularCollector`는 조건 없는 `@Component`라 전체 컨텍스트 통합 시험마다 빈 두 개 상태로 만들어진다. CI 성공이 주입 모호성이 없음을 보여 준다. `application.yml`의 `heartbeat:` 블록은 하나이고 `collector-url`·`digest-url` 두 키를 담는다. compose와 example의 `HEARTBEAT_DIGEST_URL`도 한 번씩만 있다. 잘못된 URL이면 시작 실패, 로그에 URL 없음 같은 동작은 1차 `DigestHeartbeat`와 같다 | `git show --cc a37b751`, `git grep` |
| 자격 결정 원문 보존 | 닫힘. 트리의 `collab/2026-11-17-m2-readiness/` 8개 파일이 root 원본과 **바이트가 같다**(`cmp`). 빠진 1개(`codex-resume-review.md`)는 어디서도 링크하지 않는다. `docs/ops/alerts.md`의 collab 링크 3개와 보존 파일 안의 상대 링크는 모두 트리 안에서 열린다. 보존 파일에서 example.com 밖의 이메일 형식 문자열은 0건이다 | `cmp`, 링크 해석 스크립트 |

## 2. 비차단 관찰 (새 필수 요구 아님)

| # | 관찰 | 영향 | 제안 |
|---|---|---|---|
| O1 | `management.metrics.distribution.percentiles-histogram`은 `http.server.requests`만 켠다. 두 지연 타이머는 count·sum·max만 노출한다 | G7 기준선을 평균과 최근 max로는 잴 수 있지만 p95는 계산할 수 없다 | #15의 경보 규칙과 대시보드를 붙일 때 같이 정한다 |
| O2 | `docs/ops/monitoring.md`의 "M2 발송 지표 통합 계약 (#7 대기)" 절이 "main에 발송기가 없다"는 상태로 남아 있다. `deploy/monitoring`의 규칙과 대시보드에 `alerts_*`가 0건이다 | 계약상 "같은 PR이나 후속 PR"이 허용된다. 병합 뒤 문서 상태가 낡는다 | 후속 PR에서 규칙, `promtool` 시험, 패널과 함께 고친다 |
| O3 | 수집 지연의 분모는 작업에 담긴 NEW 항목 전체다. 본문에는 이메일 섹션당 20줄, Discord 8줄만 나오고 나머지는 "외 N건"과 목록 링크로 간다 | "발송 성공 항목"에 요약으로만 전달된 항목도 들어간다. 정의 문서와 충돌하지는 않는다 | 필요하면 `alerts.md`에 한 줄로 적는다 |
| 1차 P3 | F3(R 뒤 웹훅 복호화 예외가 unknown으로 기록됨), F4(KEY SHARE 주석), F5(`TARGET_CHANGED`·`NOT_SENT` 운영 문서 누락)는 바뀌지 않았다. sol의 깨진 링크 관찰은 위에서 닫혔다 | 1차와 같이 비차단 | 작성자와 root의 판단 |

## 3. 확인 범위

- **직접 한 것:**
  - `git fetch`, `git ls-remote`로 최종 SHA 확인.
  - `cc0d6df`와 `a37b751`(`--cc` 충돌 해결 포함)의 diff를 읽고, 1차 head 대비 잠금 관련 파일의 diff가 0인지 확인.
  - 새 테스트 2개 원문 읽기. 보존 문서 8개 `cmp`, 링크 해석, 이메일 형식 grep. `heartbeat`·env 키의 중복 grep.
- **실행하지 않은 것:** Gradle 빌드와 테스트, `docker compose config`, promtool, gitleaks. CI 결과(CI 4잡·Monitoring 3잡 success, MERGEABLE)는 root가 조회한 것이다. 작성자 기록의 168 tests와 deploy-test 17/17은 작성자의 보고다. 나는 둘 다 다시 조회하거나 실행하지 않았다.
- **접근하지 않은 것:** 실제 서버, env, 키, provider, 개발 DB, OCI, Healthchecks 계정.

## 4. 남은 운영 게이트 (병합 조건 아님)

- 실제 `/opt/gongchae-bell/.env`의 워커 키 값과 `HEARTBEAT_DIGEST_URL` 설정. Healthchecks `gongchae-digest` check와 유예.
- `alerts_*` 지표의 Prometheus 규칙, `promtool` 시험, Grafana 패널(O1·O2). 지연 기준선은 실사용 뒤 실측으로 정한다.
- 실제 Resend·Discord 발송과 수신, SPF·DKIM·DMARC, RFC 8058 원클릭 실동작, 개인정보 고지 실제 값(1차 기록과 같음).
