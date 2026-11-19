# PR25 실제 운영 기록 1·2라운드 독립 검수 (agy)

2026-11-19 root Codex 배정(`collab/2026-11-19-resume/agy-runtime-review-request.md`)에 따른 1차 비작성자 검수다. 대상은 primary Claude 작성 PR #25(`ops/#14-runtime-evidence`, `runtime-evidence` worktree)의 초기·2라운드 문서 HEAD `ffc961985dd64f95b016013bc6f60b272ddd751f`(1라운드 `2216d8285185a230c442d9c44f2fa8dafb8b1538`)이며, 후속 G4 요청·결과는 이번 판정 범위에 섞지 않았다. 실제 서버·계정·키·env·SSH·provider에는 접근하지 않았고 앱 코드·테스트·구현 설정·운영 상태·타 작성자 파일도 변경하지 않았다.

## 판정

**1·2라운드 운영 기록 범위에서 수용한다(차단 결함 없음).**

- PR #25는 문서 2개(`collab/2026-11-19-resume/claude-runtime-result.md`, `collab/2026-11-19-resume/claude-runtime-round2.md`)만 추가했고 root 사본과 바이트 일치한다.
- PR #25 기록 자체는 시각·배포 SHA·초기 적재 건수·재실행 0 HTTP·경보 전송 성공과 채널 화면 확인 미완료 구분·미검증 목록·권한 분류기 거부 원문 및 1라운드 결함 정정·비밀값 비노출 기준을 충족한다.
- **범위 한정:** 이 판정은 PR #25가 기술한 "초기 배포(`bootstrap`) + 초기 적재(`initial-001`·`initial-002`) + 운영 경보 API 전송 훈련(`GongchaeDrill`) + 미실행/거부 기록"의 정합성에 대한 수용이며, 미검증으로 남은 외부 G4 무중단·롤백·정기 수집·운영 디스코드 사람 화면 확인·백업·복원·부하·실제 사용자 알림 완료를 의미하지 않는다.

## 대조 결과

### 1. 범위 · 사본 일치 · CI

- **변경 범위(`52c172c..ffc9619`)**: `collab/2026-11-19-resume/claude-runtime-result.md`(+167줄), `collab/2026-11-19-resume/claude-runtime-round2.md`(+123줄) 2개 파일만 추가. 앱·테스트·인프라/워크플로 설정 변경 없음. `git diff --check` 통과, `gh pr view 25` 기준 `MERGEABLE`(draft).
- **root 사본 바이트 일치**: PR #25 HEAD `ffc9619`의 두 파일과 root `collab/2026-11-19-resume/` 사본이 각각 15,651 bytes, 13,916 bytes로 완전히 일치함을 직접 확인했다.
- **PR #25 CI**: HEAD `ffc9619`에서 `CI` [44504342230](https://github.com/Lcjam/gongchae-bell/actions/runs/44504342230)(`test`, `deploy-verify`, `backup-verify`, `secrets` 4잡 `success`) 및 `Monitoring` [44504342277](https://github.com/Lcjam/gongchae-bell/actions/runs/44504342277)(`rules`, `compose-integration`, `alert-e2e` 3잡 `success`) 완료. (1라운드 `2216d82`도 CI `44501163128`·Monitoring `44501163305` `success`.)

### 2. 시각 · 실제 배포 SHA · 이미지 · 파일 해시

- **커밋·CI·이미지 대조(Git/GitHub 메타 직접 확인)**:
  - `836a3c0af0625a093c8488a6d455da1fe694a5cc` 커밋 시각은 `2026-11-19T18:40:37+09:00`(문서 기록 `18:40:37`과 일치), `CI` `44498615122` 4잡 `success`.
  - `Deploy` `44498980228`(`09:43:12Z~09:44:13Z` = `18:43:12~18:44:13 KST`) 로그에서 `Resolve target SHA`의 `RUN_SHA`가 `836a3c0af0625a093c8488a6d455da1fe694a5cc`이고, `sha-836a3c0af0625a093c8488a6d455da1fe694a5cc`가 digest `sha256:17ffb07859d615cdb215eb401ff088224acd9c9f2b0fae40dc3e80f63032c62a`로 푸시되었으며, `Blue/Green Deploy with External G4 Probe` 잡은 `skipped`임을 직접 확인했다.
  - `517eb6b11b06a15aebeb7c0d3c94662eacd62ea7`(커밋 시각 `18:42:45 KST`)의 `Deploy` `44499316061` 로그에서 `sha-517eb6b...`가 digest `sha256:c750129c022a0a2967e51ae67de1d0cd04442e7550c9b10c2c4c6924eb7b2305`로 푸시되고 deploy 잡이 `skipped`임을 직접 확인했다.
  - Git 파일 SHA-256 앞 8자리: `836a3c0`의 `compose.prod.yml` `f2619f2c…`, `compose.monitoring.yml` `1de53367…`, `deploy/deploy.sh` `5b38284b…`, `517eb6b`의 `deploy/monitoring/prometheus/rules/gongchae.yml` `ee611796…` 모두 보고서 기재값과 일치한다.
- **시각 일관성(KST = UTC+9)**:
  - 1라운드(`18:36`~`18:55 KST`)·2라운드(`19:09:57`~`19:16 KST`)의 KST/UTC 환산과 경과 시간이 모순 없이 맞는다. Let's Encrypt 인증서 `notBefore` `08:51:46Z`는 발급 시점(`18:50 KST` = `09:50Z`) 기준 표준 1시간 백데이트 관행과 부합한다.
  - 2라운드 경보 훈련 소요 계산: 규칙 추가+SIGHUP `10:11:25Z` → 발동 전송 `10:12:30.013Z` (**65초**, Prometheus firing `10:11:43.79Z` 기준 ~46–47초), 규칙 삭제+SIGHUP `10:13:14Z` → 해제 전송 `10:14:30.014Z` (**76초**)가 타임스탬프와 정확히 일치한다.

### 3. 초기 적재 · 재실행 0 HTTP · 경보 수신 한계 · 소스 대조

- **초기 적재 (`initial-001`) 및 재실행 (`initial-002`)**:
  - 소스·문서 계약(`docs/ops/collector.md` "최초 적재"·"재개와 세대", `application.yml`): `START_SCAN`으로 `to` 이후 시작일 3건 제외(raw `36,882` / 반영 `36,879`), 일 한도 `500`/`900`/`828`(정기 몫 72), 동일 기간·새 슬롯 재실행(`--collector.backfill-restart-pages` 미사용) 시 직전 `COMPLETE` scan의 두 SN 집합으로 HTTP 0회 완료하는 설계와 일치한다.
  - root 직접 관측(`codex-runtime-review.md`): `10:00:43Z` 운영 DB에서 `START_SCAN` `COMPLETE`, 기간 `2023-11-19~2026-11-19`, raw `36,882` · `posting` `36,879` · API 예산 `37` · 구독 `0`, `COLLECTOR_ENABLED=false`, `ALERTS_ENABLED=false`를 독립 확인했다.
  - 보고서 수치(서버 전용): `initial-002`(`10:10:23~10:10:32Z`) 전후 `posting` `36,879` 및 md5 지문 `c92183c2eea0644cac25ccae5a1430da` 동일, `api_attempt` `37`·`api_budget` `37` 불변, `collect_run` 행만 `39→40`으로 1행 추가.
  - `CatalogViews.recrutSeLabel`(`src/main/java/kr/gongchae/bell/catalog/CatalogViews.java`): 실제 소스에 `R2010`·`R2020`·`R2030`만 매핑되고 `R2040` 분기가 없어 코드 원문이 노출된다는 관찰이 정확하다.
- **운영 경보 훈련(`GongchaeDrill`)과 한계 구분**:
  - `docs/ops/monitoring.md` "실제 수신 확인" 1번 절차(`rules/drill.yml` + `SIGHUP` → 삭제 + `SIGHUP`) 및 `deploy/monitoring/grafana/provisioning/alerting/forward.yml`(`ops-discord` 연락처, `GongchaePrometheusAlert`, `noDataState: OK`)과 일치한다.
  - **API 전송 성공과 사람 채널 확인의 구분**: 보고서는 Grafana `lastNotifyAttempt`·`lastNotifyAttemptError 없음`(`460ms`/`302ms`)이 "Discord API로의 쓰기 전송 성공"일 뿐이며, 웹훅이 쓰기 전용이라 **사람이 실제 운영 디스코드 채널에서 두 메시지를 보았는지 확인해야 `monitoring.md` 수신 표를 닫을 수 있다**고 명시적으로 선을 그었다. 미실시 항목(`Healthchecks`, `UptimeRobot`, `G4`, `정기 수집`, `백업·복원`, `부하`, `ALERTS_ENABLED=true`)도 빠짐없이 분리했다.
- **`bootstrap` vs G4 구분**: `docs/runbook.md` §3에 맞춰 `bootstrap`을 G4 무중단 배포 측정으로 세지 않았고, `prev_*` 없음(롤백 근거 없음)도 명확히 적었다.

### 4. 권한 분류기 거부 원문 · 1라운드 결함 정정 · 비밀값

- **1라운드 결함 정정**: `claude-runtime-round2.md` §4에서 1라운드 첫 거부 명령의 실제 버그(`image_sha=517eb6bc$(true)` → 쉘에서 8자리 존재하지 않는 커밋 `517eb6bc`가 되어 `deploy.yml`의 `[0-9a-f]{7,40}` 형식 검사는 통과하나 `checkout` 단계에서 실패했을 것이라는 추정, 및 `variable set`+`workflow run` 복합 호출로 승인 단위를 흐린 점)를 숨기지 않고 기록했다. `claude-runtime-result.md` §"막힌 항목", `claude-runtime-user-input.md`, `claude-runtime-round2.md` 모두에서 전체 40자리 SHA `517eb6b11b06a15aebeb7c0d3c94662eacd62ea7`와 단일 명령 분리 실행으로 올바르게 정정되었다.
- **거부 원문 및 우회 금지**: Claude Code `auto` 모드 분류기 거부 문구(`Permission for this action was denied by the Claude Code auto mode classifier. Reason: [Destructive or Unwanted Actions]...`)를 원문 그대로 남겼고(2번째 호출의 마지막 문장 부재 차이 포함), 서버 수동 `deploy.sh --phase switch` 등으로 우회·대체 실행하지 않았음을 적시했다. (`settings.json` 상태·거부 원문·UI 동작은 작성자 세션 보고 기준이며, 외부 확인 가능한 `deploy.yml`의 `workflow_dispatch` 변수 미의존 동작은 `.github/workflows/deploy.yml` 88행으로 직접 확인했다.)
- **비밀값 누출 검사**: 두 파일 전체에서 Discord 웹훅 실 URL·API 키·DB 비밀번호·SSH 개인키를 정규식·육안으로 점검한 결과 노출 0건이며(`https://discord.com/api/webhooks/<id>/<token>` 플레이스홀더와 공개 SSH 키 지문 `SHA256:MeLTg7oz...`만 존재), CI `secrets`(Gitleaks) 잡도 `success`다.

## 비차단 관찰

1. `claude-runtime-result.md` 21행에서 `sha-836a3c0...` 이미지의 `Deploy` run ID로 `44498980228`을 인용했다. GitHub Actions API의 `workflow_run` 최상위 `headSha` 메타필드에는 당시 기본 브랜치 HEAD인 `517eb6b`가 표시되지만, 해당 run 로그의 `Resolve target SHA`·`docker push` 출력은 실제로 `836a3c0af0625a093c8488a6d455da1fe694a5cc`와 보고된 digest(`sha256:17ffb078...`)를 가리키므로 기록 자체가 오류는 아니다.
2. `docs/runbook.md` §8과 `docs/ops/collector.md`의 운영 DB 실측·`docs/ops/monitoring.md` 실제 수신 표 반영은 이번 PR에 넣지 않고 사용자/root 확인 후속으로 남겼다(`restart-claude-runtime.md`의 "결과 기록만 갱신" 지시와 일치).

## 검증 경계 (근거 출처 구분)

- **agy 직접 확인**: PR #25 git diff(`52c172c..ffc9619`), `git diff --check`, root 사본 바이트 비교, GitHub Actions CI/Deploy/Monitoring 메타·로그(`44498615122`, `44498980228`, `44499316061`, `44501163128`, `44501163305`, `44504342230`, `44504342277`), Git 커밋 시각·파일 SHA-256, `deploy.yml`·`forward.yml`·`CatalogViews.java`·`docs/runbook.md`·`docs/ops/collector.md`·`docs/ops/monitoring.md` 대조, 민감값 패턴 점검.
- **root 직접 관측(`codex-runtime-review.md`)**: 공개 HTTPS 200·1.1.1.1 DNS, `deploy.sh status` `blue`/`BOOTSTRAP_OK`, 5개 컨테이너·Grafana health·포트 바인딩, `10:00:43Z` 운영 DB `START_SCAN` `COMPLETE`·`36,882`/`36,879`/`37`/`0` 및 `false` 플래그.
- **작성자(Claude) 보고 기준(agy 미접근 영역)**: 가비아 콘솔 조작, 서버 `/opt/gongchae-bell/.env` 및 SSH `authorized_keys` 권한/백업, `initial-002` DB md5 지문·`collect_run` id 40, `GongchaeDrill` Prometheus/Grafana API 세부 응답(`lastNotifyAttempt`, `460ms`/`302ms`), `docker stats` 표본, Claude Code 세션 거부 원문·`settings.json` 내용.
