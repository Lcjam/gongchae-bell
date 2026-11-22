# 검수 완료 main `32e71e4` 운영 적용 (#14·#7·#19) · Claude

2026-11-19 root Codex 배정 `collab/2026-11-19-resume/claude-main32-deploy-request.md`를 수행한 기록이다. 시각은 UTC이고 괄호 안은 KST다. 앞 기록은 [`claude-g4-result.md`](claude-g4-result.md)다.

**제출 방식:** PR25 HEAD `013ec5d`를 보존하라는 지시에 따라 이 파일은 `runtime-evidence` worktree에 커밋하지 않은 로컬 파일과 root 사본으로만 둔다. push하지 않았다.

## 요약

| 항목 | 결과 |
|---|---|
| 요청 | `gh workflow run Deploy -R Lcjam/gongchae-bell --ref main -f action=deploy -f image_sha=32e71e4bb56c9137cb12f9b49088a66d949f988a` 단독 호출. run 생성 10:33:34Z(19:33:34). **허용됨** |
| Deploy run | [`44506783359`](https://github.com/Lcjam/gongchae-bell/actions/runs/44506783359), Build & Push success, blue/green 배포 잡 success |
| deploy_id | `bluegreen-32e71e4bb56c-44506783359-1` |
| 외부 G4 (GitHub 러너, AWS us-west-2) | **30회, 실패 0**, 5xx 0, 연결·TLS 0, 시간 초과 0, p95 725ms, max 1,884ms |
| 운영 상태 | green `sha-32e71e4…`, prev blue `sha-836a3c0…`, pending 없음, `DEPLOY_OK` |
| DB | Flyway V5 그대로(새 마이그레이션 없음). 공고 지문·코드·시도·구독·발송 행이 배포 전과 같음 |
| 알림·수집 | `ALERTS_ENABLED=false`, `COLLECTOR_ENABLED=false` 그대로. 발송·스케줄 로그 0줄, 발송 행 0 |
| 영구 설정 | `AUTO_DEPLOY_ENABLED`·권한 규칙·모드 변경 없음. Variables는 `SITE_DOMAIN`만 있음 |

## 실행 전 확인 (10:33:13Z, 19:33)

- main `32e71e4`(PR23 알림 워커 + PR26 모바일 배지). 이 SHA의 CI `44505216382`, Monitoring `44505216463`은 success다. workflow_run Deploy `44505638505`은 이미지 발행 success, 배포 잡 skipped였다.
- `517eb6b..32e71e4` 배포 관련 차분:
  - `compose.prod.yml`: 앱 env 4개 추가(`ALERTS_WORKER_CONCURRENCY`, `ALERTS_BATCH_SIZE`, `ALERTS_SCHEDULER_ENABLED`, `HEARTBEAT_DIGEST_URL`)
  - `deploy/.env.prod.example`: 같은 키 이름
  - `application.yml`: 스케줄 풀 4, `alerts.dispatch.*`, `heartbeat.digest-url`
  - `deploy.yml`, `deploy.sh`, `g4-probe.sh`, Caddy, 모니터링 설정, `db/migration`은 변경 없음
- 알림이 꺼진 상태의 소스 확인: `AlertScheduler.scheduleDailyBatches`·`pollDispatch`는 첫 줄에서 `if (!properties.enabled()) return;`으로 끝난다. 스케줄러 빈은 `ALERTS_SCHEDULER_ENABLED=true`(기본값)로 만들어지지만 알림이 꺼져 있으면 아무 일도 하지 않는다.
- 서버 상태:
  - blue `836a3c0`(`ROLLBACK_OK`), prev green `517eb6b`, pending 없음
  - `.env` sha256 `178bb921…`, `compose.prod.yml` `f2619f2c…`(517eb6b판)
- DB 기준값: Flyway V1~V5 성공. 공고 36,879, 지문 md5 `c92183c2eea0644cac25ccae5a1430da`(sn·content_hash·last_seen_at·last_changed_at). `posting_code` 206,295, `api_attempt` 37, `collect_run` 40, `subscription`·`notification`·`notification_item` 0.
- `sha-32e71e4…` 태그의 실행 전 digest는 `sha256:67b919eb92df8a84f99e540e968e92792342a6fc0cd9956033f10a4b945a9a72`(workflow_run이 발행)였다.

## 실행 경과

| UTC | 사건 |
|---|---|
| 10:33:39~10:34:47 | Build & Push(Azure centralus). `INPUT_SHA=32e71e4…`. **같은 태그를 다시 빌드해 덮어씀**: 새 digest `sha256:92e1d6a4acb3b7cbdc80908dfad6a8abd63dcc3149e94ec0df609083785d6eac` |
| 10:34:52 | 배포 잡 시작. 러너 `ip-10-0-128-42`(AWS us-west-2). checkout `ref: 32e71e4…`(배포 정의 동기화 대상) |
| 10:35:02 | `mode=bluegreen` |
| 10:35:05 | 외부 G4 프로브 시작 |
| 10:35:16 | `Starting bluegreen switch … active=blue(836a3c0) -> target=green(32e71e4)` |
| 10:35:25 | app-green 컨테이너 시작 |
| 10:35:31 | Flyway: `Successfully validated 5 migrations`, `Current version of schema "public": 5`, `Schema "public" is up to date. No migration necessary.` |
| 10:35:35 | `Started GongchaeBellApplication in 9.298 seconds` |
| 10:35:38 | `Internal readiness and key routes passed for app-green` |
| 10:35:39 | `SWITCH_OK … active_kept=blue serving=green` |
| 10:35:54 | `G4_PASS … total=30 ok=30 failures=0 p95_ms=725 max_ms=1884` |
| 10:35:56~10:35:57 | finalize: blue 정지·제거 |
| 10:35:57 | `DEPLOY_OK color=green sha=sha-32e71e4… prev_color=blue prev_sha=sha-836a3c0…` |

`g4-probe-report` 아티팩트:

```json
{"deploy_id":"bluegreen-32e71e4bb56c-44506783359-1","execution_context":"github-actions-external","runner_host":"ip-10-0-128-42","base_url":"https://gongchae.enjoylcjworld.com","started_at":"2026-11-19T10:35:05Z","finished_at":"2026-11-19T10:35:54Z","interval_sec":1,"connect_timeout_sec":3,"max_time_sec":5,"total_requests":30,"ok_requests":30,"failures":0,"http_5xx":0,"connect_tls_errors":0,"timeouts":0,"other_non_200":0,"p95_latency_ms":725,"max_latency_ms":1884}
```

## 배포 뒤 확인 (10:36:38~10:37:50Z)

**양색·태그·이미지**
- `deploy.sh status`:
  - `active_color=green`, `active_sha=sha-32e71e4…`, `active_deploy_id=bluegreen-32e71e4bb56c-44506783359-1`
  - `prev_color=blue`, `prev_sha=sha-836a3c0…`, `prev_deploy_id=rollback-32e71e4bb56c-44505579286-1`
  - `upstream_color=green`, `pending_deploy_id=none`, `last_deploy_result=DEPLOY_OK`
- `upstream.caddy`는 `reverse_proxy app-green:8080`이다. 상태 파일 `blue_tag=sha-836a3c0…`, `green_tag=sha-32e71e4…`.
- 실행 중인 앱은 `app-green` 하나다(blue 제거됨). 재시작 0회. 이미지 ID와 RepoDigest가 `sha256:92e1d6a4…`, arm64로 새 빌드와 같다.
- 서버 `compose.prod.yml` sha256 `1f2a4183…`은 Git `32e71e4`판과 같다. 운영 `.env` sha256 `178bb921…`는 배포 전과 같다(보존).

**새 워커 env 전달** (컨테이너 env 이름 확인, 비밀값은 출력하지 않고 설정 여부만 봄)
- `ALERTS_ENABLED=false`, `ALERTS_WORKER_CONCURRENCY=2`, `ALERTS_BATCH_SIZE=20`, `ALERTS_SCHEDULER_ENABLED=true`, `COLLECTOR_ENABLED=false`
- `HEARTBEAT_DIGEST_URL`·`HEARTBEAT_COLLECTOR_URL`은 빈 값(꺼짐)
- `RESEND_API_KEY`, `ALERTS_MAIL_FROM`, `ALERTS_TOKEN_KEY`, `ALERTS_HMAC_KEY`, `ALERTS_WEBHOOK_KEY`, `ALERTS_WEBHOOK_KEY_ID`, `PRIVACY_*` 7개는 모두 빈 값

**알림 꺼짐 부작용 없음**
- app-green 로그 57줄 중 `ALERT_`, `HEARTBEAT_`, `COLLECTION_`, `BACKFILL`, `WARN`, `ERROR` 줄은 0이다(10:36:50, 10:37:50 두 번 확인).
- DB(10:37:01Z): `subscription`·`notification`·`notification_item` 0, `api_attempt` 37, `collect_run` 40으로 배포 전과 같다.
- `POST /subscriptions/email`(빈 값, 같은 Origin)과 `GET /subscriptions/manage?t=x`는 503이다. 알림 비활성 계약대로다. 실제 메일·Discord 구독은 만들지 않았다.

**DB 보존**
- Flyway 이력은 V1~V5 모두 성공으로 배포 전과 같다.
- 공고 36,879, 지문 `c92183c2eea0644cac25ccae5a1430da`, `posting_code` 206,295로 배포 전과 같다.

**핵심 화면** (외부, 내 PC, 10:37, 각 1회)
- 200: `/`, `/jobs`, `/jobs?status=all`, `/jobs/306337`, `/institutions/C0856`, `/robots.txt`, `/sitemap.xml`, `/privacy`, `/subscriptions/new`, `/subscriptions/recover`
- 404: `/actuator/health`
- 보안 헤더 5종(HSTS, CSP, X-Frame-Options, X-Content-Type-Options, Referrer-Policy)이 있다.
- `/jobs`는 진행 563건이고, 홈은 "알림 기능 준비 중" 배지를 그대로 보여 준다.
- 모바일 최종 폭은 root가 따로 재검수한다.

**모니터링**
- Prometheus 대상은 app-green up, app-blue down(비활성 색)이고 경보 0이다. `max(collector_api_attempts_today)`=37이다.
- Grafana 경보 0, 전달 규칙 `Normal (NoData)`이다.

**노출 포트**: 외부에서 5432·8081·3000·9090은 시간 초과다.

**자원** (10:37:50Z 1회): app-green 413MiB, postgres 173MiB, grafana 124MiB, prometheus 34MiB, caddy 17MiB. 디스크 `/` 7.1G 사용, 38G 여유.

## 참고: 독립 교차 측정 (내 PC, 한국 가정망)

CI G4를 대신하지 않는다. 10:33:29~10:36:13Z에 `/`·`/jobs`를 번갈아 1초 간격으로 116회 조회했다. 비200·curl 오류 0, p95 312ms, max 754ms다. 전환 구간(10:35:16~10:35:58)의 30회도 실패 0이다.

## 관찰

1. dispatch가 이번에도 같은 커밋 태그를 다시 빌드했다(`67b919eb…` → `92e1d6a4…`). 운영 이미지는 dispatch가 만든 digest다. 같은 태그가 이미지를 바꾸는 문제는 이전 결과의 관찰 2와 같다. root 결정을 기다린다.
2. G4 max 1,884ms는 30회 중 한 번이다. p95는 725ms다. 각 요청 시각은 아티팩트에 없어 전환 시점과 겹쳤는지는 확인하지 못했다. 같은 시각 한국 측정 max는 754ms였다.

## 미검증·범위 밖

- 실패 유도 abort 리허설: 이번 결과로 대신하지 않는다.
- 알림 실제 발송(Resend·Discord 구독), `ALERTS_ENABLED=true`, 해지 대장 파일 생성: 범위 밖
- 정기 수집 활성화: 범위 밖
- 미병합 PR27, 새 M2 모니터링 source: 섞지 않았다
- 공개 모바일 최종 폭: root 재검수
