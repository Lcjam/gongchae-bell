# 실제 서버 초기 배포 결과 (#14) · Claude

2026-11-19 root Codex 배정(`collab/2026-11-19-resume/claude-request.md` §2, 초안 `collab/2026-11-18-implementation/claude-runtime-request.md`)에 따라 Claude가 실행했다. 시각은 KST이고 괄호 안은 UTC다. 비밀값과 사용자 데이터 원문은 적지 않았다.

## 요약

| 항목 | 상태 |
|---|---|
| 공개 HTTPS `https://gongchae.enjoylcjworld.com` | **완료.** bootstrap `836a3c0`, Let's Encrypt 인증서 |
| DNS `gongchae` A `158.179.179.104` | **완료.** 권한 NS 3곳과 공용 resolver 3곳에서 조회됨 |
| 운영 DB 최초 3년 적재 (START_SCAN) | **완료.** 36,879건 반영, HTTP 37회 |
| GitHub `production` 환경·Secrets 4개·`SITE_DOMAIN` | **완료** |
| 모니터링 (Prometheus·Grafana) | **설치 완료.** 경보 수신 시험은 하지 않음 |
| `AUTO_DEPLOY_ENABLED=true`, 외부 G4 blue/green, 롤백 G4 | **미실행.** Claude Code 권한 분류기가 막았다. 사용자 승인 필요(아래 "막힌 항목") |
| 정기 수집 `COLLECTOR_ENABLED=true` 전환 | **미실행(계획만).** 지시 범위대로 현재 값과 전환 계획만 보고한다 |
| 사용자 알림 `ALERTS_ENABLED` | `false` 유지 |

## 사용한 SHA와 이미지

- 검수 완료 main: `836a3c0af0625a093c8488a6d455da1fe694a5cc`(PR21 병합, 18:40:37). CI `44498615122` success.
- 이미지: `ghcr.io/lcjam/gongchae-bell:sha-836a3c0af0625a093c8488a6d455da1fe694a5cc`. Deploy `44498980228`의 Build & Push 잡 success(18:43:12~18:44:13). 서버 배포 잡은 skipped다. 익명 manifest HEAD는 HTTP 200, digest `sha256:17ffb07859d615cdb215eb401ff088224acd9c9f2b0fae40dc3e80f63032c62a`. 서버에서 pull한 이미지의 `Architecture=arm64`이고 RepoDigest가 같다.
- 서버 배포 정의(`compose.prod.yml`, `compose.monitoring.yml`, `deploy/`)는 같은 SHA의 `git archive`로 옮겼다. 서버 파일 sha256이 Git 원본과 같다(`compose.prod.yml f2619f2c…`, `compose.monitoring.yml 1de53367…`, `deploy/deploy.sh 5b38284b…`).
- 이후 main은 `517eb6b11b06a15aebeb7c0d3c94662eacd62ea7`(PR24, 문서만 변경)다. 그 이미지도 Deploy `44499316061`이 발행했다. G4 blue/green의 전환 대상으로 쓰려던 SHA다.
- PR23(#7 알림 워커)은 섞지 않았다.

## 시간 순서

| KST | 단계 | 결과 |
|---|---|---|
| 18:36 (09:36Z) | 서버 읽기 전용 점검 | Ubuntu 24.04.4 ARM64, 2 vCPU·11.6GiB, 디스크 41G 여유. 컨테이너·볼륨 0, `/opt/gongchae-bell` 없음. UFW 22·80·443. host `ubuntu`는 UID 1001(컨테이너 UID 1000은 host `opc`와 같은 번호) |
| 18:36 | DNS 점검 | `gongchae` A 없음. 권한 NS `ns.gabia.co.kr`·`ns1.gabia.co.kr`·`ns.gabia.net` |
| 18:38 | 외부 포트 점검(내 PC) | 80·443 연결 거부(호스트 도달, 리스너 없음), 5432·8081·3000 시간 초과. VCN 보안 목록이 80·443을 허용하는 것으로 판단 |
| 18:40 | 가비아 세션 만료 보고 | `collab/2026-11-19-resume/claude-runtime-user-input.md`. 사용자가 로그인했다 |
| 18:39~18:40 | 서버 디렉터리·`.env` | 아래 "서버 준비" |
| 18:41:45 | 가비아 레코드 추가·저장 | 레코드 6 → 7개 |
| 18:42:11 | DNS 확인 | 6곳 모두 `158.179.179.104` |
| 18:43~18:44 | GitHub 환경·Secrets·변수 | 아래 "GitHub 설정" |
| 18:45:19 | 운영 postgres 기동 | healthy |
| 18:46:02~18:48:22 | 최초 적재 `initial-001` | `BACKFILL_COMPLETE`, 2분 20초 |
| 18:49:43~18:50:10 | `deploy.sh bootstrap` | `BOOTSTRAP_OK` |
| 18:50~18:51 | 외부 HTTPS·헤더·포트 | 아래 "서비스 확인" |
| 18:52:55 | 모니터링 기동 | 아래 "모니터링" |
| 18:52 | AUTO_DEPLOY 활성화·Deploy 실행 시도 | 권한 분류기 거부 |

## 서버 준비

- 런북 §1.3 명령대로 만들었다. `/opt/gongchae-bell`과 `deploy/state`는 `ubuntu:ubuntu 0750`, `/var/lib/gongchae-bell/erasure`는 `1000:1000 0750`이다. 신규 설치이므로 `erasure.log`는 만들지 않았다. `ALERTS_ENABLED=false`에서는 필요 없고, 알림을 켜기 전에 런북 명령으로 만든다.
- `/opt/gongchae-bell/.env`는 `ubuntu 0600`이다. 넣은 키는 `APP_IMAGE`, `SITE_DOMAIN`, `APP_BASE_URL`, `DB_NAME=gongchae`, `DB_USER=gongchae`, `DB_PASSWORD`, `COLLECTOR_ENABLED=false`, `COLLECTOR_SERVICE_KEY`, `ALERTS_ENABLED=false`, `GRAFANA_ADMIN_PASSWORD`, `OPS_DISCORD_WEBHOOK_URL`다.
  - `DB_PASSWORD`·`GRAFANA_ADMIN_PASSWORD`는 서버에서 `openssl rand -hex`로 새로 만들었다. 개발 DB 값은 쓰지 않았다.
  - `COLLECTOR_SERVICE_KEY`·`OPS_DISCORD_WEBHOOK_URL`은 root `.env`의 해당 한 줄을 SSH 표준입력으로만 보냈다. 보내기 전에 값을 출력하지 않고 문자 집합만 검사했다(`deploy.sh`가 `.env`를 bash로 `source`하므로). 키는 96자 `[A-Za-z0-9%+/=]`, 웹훅은 Discord `https://discord.com/api/webhooks/<id>/<token>` 형식이다.
  - root `.env`는 바꾸지 않았다. 로컬 임시 secret 파일은 만들지 않았다.
- 개발 DB를 복사하거나 덮어쓰지 않았다. 운영 DB는 compose `postgres_data` 볼륨의 새 DB다.

## GitHub 설정

- `production` environment를 새로 만들었다. 배포 브랜치는 사용자 지정 정책 `main` 하나다. 이전에는 environment·Secrets·Variables가 모두 없었다.
- environment Secrets: `DEPLOY_HOST`, `DEPLOY_USER`, `DEPLOY_SSH_KEY`, `DEPLOY_KNOWN_HOSTS`(18:44:03~18:44:10). 값은 표준입력·파일로만 넣었다.
  - `DEPLOY_SSH_KEY`는 이번에 만든 **배포 전용** ed25519 키다(지문 `SHA256:MeLTg7ozACsAoU8PZeTd/ZSHNFN7vynADWK51dEwCYk`, 주석 `gongchae-deploy-gha-20261119`). 서버 `~ubuntu/.ssh/authorized_keys`에 `restrict` 옵션을 붙여 추가했다(포워딩·pty 금지). 추가 전 사본은 `authorized_keys.bak-20261119`다. 이 키로 로그인과 `deploy.sh status`를 확인했고 로컬 개인키는 지웠다. 기존 관리자 키와 ACL은 바꾸지 않았다.
  - `DEPLOY_KNOWN_HOSTS`는 새로 `ssh-keyscan`하지 않았다. 이미 검증해 쓰던 `.tools/ssh/harumal-prod-20261115/known_hosts`의 호스트 키 줄을 넣었다.
- Repository Variable: `SITE_DOMAIN=gongchae.enjoylcjworld.com`. `AUTO_DEPLOY_ENABLED`는 **설정하지 못했다**(미설정 = 자동 배포 꺼짐).

## 최초 3년 적재 (FR-C1, 운영 DB)

명령: `--spring.main.web-application-type=none --collector.enabled=false --collector.backfill=true --collector.backfill-slot=initial-001 --collector.backfill-from=2023-11-19 --collector.backfill-to=2026-11-19`. `docker compose --profile blue run --rm --no-deps`로 `836a3c0` 이미지를 한 번 실행했다. 정기 수집과 알림은 꺼진 상태였다. 실행 KST 날짜는 2026-11-19다.

| 측정 | 값 |
|---|---|
| 명령 행 `command/initial-001`, scan 행 `initial-001/scan` | 모두 `COMPLETE`, `strategy=START_SCAN`, `scan_generation=2` |
| 기간 | 2023-11-19 ~ 2026-11-19 |
| 원격 `totalCount` / raw 수신 / raw 고유 | 36,882 / 36,882 / 36,882 |
| 반영 고유(`applied_count`, FR-C1 고유 공고 수) | **36,879** |
| 반영 안 한 raw | 3건(sn 306938·306939·307247). 설계대로 시작일이 `to` 이후인 행이며 이후 정기 수집이 넣는다 |
| 페이지 행 | 37개(1~37), 전부 `COMPLETE`. 합계 inserted 36,879, changed 0, closed 0 |
| HTTP 시도(`api_attempt`) | BACKFILL 37회, 37회 성공, 재시도 0, 결과 없음 0 |
| `api_budget` 2026-11-19 | 37. 500/900/828 한도와 정기 몫 72회 기본값 그대로 |
| 재실행 | 0회. 한 번에 완료됐고 `BACKFILL_COUNT_CHANGED` 없음 |
| 소요 | 18:46:02~18:48:22 (2분 20초) |

품질 표본(같은 DB, 18:49):
- 공고 36,879건, 기관 353곳. 시작일 2023-11-20 ~ 2026-11-19, 마감일 2023-11-22 ~ 2027-08-11. 연도별 시작 건수: 2023년 1,093, 2024년 12,382, 2025년 12,454, 2026년 10,950.
- 상태: `CLOSED/N` 36,316, `OPEN/Y` 563. 모두 `from_backfill=true`, `detail_status=NONE`(과거 공고 상세는 요청하지 않는 설계).
- `broken_text` 1,542건, `src_url_generic` 17,131건, `src_url` 없음 0, 제목 빈 값 0, 마감일이 시작일보다 빠른 행 0.
- 코드: NCS 25종, REGION 19종, HIRE 5종, EDU 7종. 모든 공고에 네 유형이 있다.
- 채용구분: `R2030` 22,793, `R2010` 11,761, `R2020` 2,311, **`R2040` 14**. `R2040`은 `CatalogViews.recrutSeLabel`에 라벨이 없어 화면에 코드 문자열 `R2040`이 그대로 나온다. 앱 소스 관찰이므로 아래 "root 배정 필요"에 넣었다.
- DB 크기 120MB.

## bootstrap과 서비스 확인

- `PULL_POLICY=always ./deploy/deploy.sh bootstrap sha-836a3c0… --deploy-id bootstrap-836a3c0af062-manual-20261119`를 서버에서 실행했다. 18:49:43 시작, 18:50:10 `BOOTSTRAP_OK`. 상태는 `active_color=blue`, `upstream_color=blue`, `pending_deploy_id=none`이다.
  - bootstrap은 설치이며 **G4 무중단 측정이 아니다**(런북 §3).
  - GitHub `workflow_dispatch(action=bootstrap)`를 쓰지 않은 이유: dispatch는 같은 SHA 이미지를 다시 빌드해 기존 태그를 덮어쓴다. 런북 §3.1의 서버 실행 경로를 썼다.
- TLS: subject `CN=gongchae.enjoylcjworld.com`, issuer `Let's Encrypt YE1`, 유효 2026-11-19 08:51:46Z ~ 2027-02-17 08:51:45Z. ACME 실패 없이 첫 기동에서 발급됐다(DNS를 먼저 연결한 뒤 Caddy를 올렸다).
- 외부(내 PC, 한국 가정망) HTTPS, 18:50 측정:
  - `/`, `/jobs`, `/robots.txt`, `/sitemap.xml`, `/privacy`, `/subscriptions/new`는 모두 200이다. 1회 요청의 총 시간은 0.15~0.43초다. 표본 1회씩이며 성능 측정이 아니다.
  - `/actuator/health`, `/actuator/prometheus`는 404다(공개 안 됨).
  - `http://…/jobs`는 308로 https에 리다이렉트된다.
  - `/jobs`는 진행 공고 563건, `/jobs?status=all`은 36,879건을 보여 DB와 같다. 푸터는 "아직 완전 수집 기록이 없습니다"다(정기 수집 전이라 의도된 상태).
  - `/jobs/306337/source`는 302이고 `Referrer-Policy: no-referrer`가 붙는다.
- 보안 헤더 `/`: `Strict-Transport-Security: max-age=31536000`, `Content-Security-Policy`(default-src 'self', frame-ancestors 'none' 등), `X-Frame-Options: DENY`, `X-Content-Type-Options: nosniff`, `Referrer-Policy: strict-origin-when-cross-origin`.
- `/subscriptions/new`(알림 비활성 안내): `Cache-Control: no-store`, `Referrer-Policy: no-referrer`, `X-Robots-Tag: noindex`.
- 노출 포트: 외부에서 80(308)과 443(200)만 응답한다. 22는 SSH다. 5432·8080·8081·3000·9090은 5초 시간 초과다(18:51, 18:55). 서버 `ss -ltnp`의 공개 리스너는 docker-proxy 80·443이고, 3000은 `127.0.0.1`에만 열려 있다.

## 모니터링 (#15, PR21 병합분)

- `deploy/deploy.sh monitoring up -d prometheus grafana`(18:52:55)로 `prom/prometheus:v3.16.0`·`grafana/grafana-oss:13.0.2`를 같은 Compose 프로젝트에 올렸다.
- 18:54 확인:
  - Prometheus 대상은 `app-blue:8081` up, `app-green:8081` down(비활성 색이라 정상)이다.
  - 규칙 6개(`GongchaeApiBudgetStopped`, `…Warning`, `…AppMetricsDown`, `…Http5xxHigh`, `…RegularCollectionFailing`, `…RegularCollectionStale`)가 올라왔고 firing 경보는 0이다.
  - `max(collector_api_attempts_today)`는 37로 DB와 같다.
  - Grafana는 health 200, 익명 API 401이다. 대시보드 `공채벨 운영`, 데이터소스 `Prometheus(http://prometheus:9090)`, 연락 지점 `ops-discord`(discord), 규칙 `GongchaePrometheusAlert`가 프로비저닝됐다.
- `OPS_DISCORD_WEBHOOK_URL`을 넣었으므로 Prometheus 경보가 firing되면 운영 디스코드로 실제 전송된다. **수신 시험은 하지 않았다.** 지금은 수집이 꺼져 있어 수집 경보 둘은 울리지 않는다(`collector_last_complete_epoch=0`).
- Grafana 접속: `ssh -L 3000:127.0.0.1:3000 ubuntu@158.179.179.104` 뒤 `http://localhost:3000`, 계정 `admin`이다. 비밀번호는 서버 `.env`의 `GRAFANA_ADMIN_PASSWORD`다.
- Healthchecks·UptimeRobot 계정 연결과 `HEARTBEAT_COLLECTOR_URL`은 하지 않았다(계정 없음, root 배정 대상).

## 자원 (18:55, `docker stats --no-stream` 1회 표본)

| 컨테이너 | CPU | 메모리 |
|---|---|---|
| app-blue | 0.15% | 386MiB |
| postgres | 0.48% | 167MiB |
| grafana | 0.24% | 157MiB |
| prometheus | 0.15% | 32MiB |
| caddy | 0.00% | 13MiB |

호스트 메모리 사용 1,472MiB / 11,925MiB(가용 10,452MiB), 디스크 `/` 6.9G 사용·38G 여유(16%), load average 0.58. 유휴 직후 1회 측정이며 부하 측정이 아니다.

## 막힌 항목: 사용자 승인 필요

18:52에 아래 두 명령이 Claude Code 자동 모드 권한 분류기에서 "Destructive or Unwanted Actions"로 거부됐다. 거부를 우회하지 않았고, 같은 목적의 서버 수동 전환도 하지 않았다.

1. `gh variable set AUTO_DEPLOY_ENABLED --body true`: main push 뒤 CI가 성공하면 운영에 자동 배포하는 스위치다.
2. `gh workflow run Deploy --ref main -f action=deploy -f image_sha=<517eb6b 전체 SHA>`: 운영 blue/green 전환과 GitHub 러너(VM 밖)의 외부 G4 프로브다. 이어서 `action=rollback`으로 롤백 G4를 할 계획이었다.

사용자가 Claude Code에서 이 두 동작을 허용하면 Claude가 이어서 한다. 또는 사용자가 직접 실행해도 된다. 실행 뒤 남길 것은 `g4-probe-report` 아티팩트의 `deploy_id`, `total_requests`, `failures=0`, `p95_latency_ms`, `max_latency_ms`이다. 순서는 다음과 같다.

1. Variables에서 `AUTO_DEPLOY_ENABLED=true`
2. Actions → Deploy → Run workflow(main, `action=deploy`, `image_sha=517eb6b11b06a15aebeb7c0d3c94662eacd62ea7`)
3. 성공하면 같은 화면에서 `action=rollback`

주의: `AUTO_DEPLOY_ENABLED=true`를 켜면 그 뒤 main 병합(예: PR23)이 CI 성공 뒤 자동으로 운영에 나간다.

## 정기 수집 전환 계획 (현재 `COLLECTOR_ENABLED=false`)

- 전제 1(최초 적재 `COMPLETE`)은 충족했다. 순서를 지켰으므로 지금 켜도 진행 공고 563건이 상세 대상이 되지 않는다(`from_backfill=true`).
- 남은 전제:
  - 운영 디스코드 경보 수신 확인(collector.md 게이트, M3)
  - `docs/ops/monitoring.md` 7번 "첫 정기 수집 확인"을 사람이 볼 수 있는 시점
  - 전환 배포 방법. `.env` 변경은 앱 재기동이 필요하므로 G4 blue/green으로 하는 것이 맞다. 그러려면 위 승인이 필요하다.
- 제안: G4 승인 → blue/green 확인 → `.env`에서 `COLLECTOR_ENABLED=true` → blue/green 재배포 → 첫 매시 7분 슬롯 뒤 `collector_last_complete_epoch > 0`과 푸터 기준 시각을 확인한다. 사용자·root 결정을 기다린다.

## root 배정 필요 (앱 소스·문서)

1. `recrutSeLabel`에 `R2040`(운영 DB 14건) 라벨이 없다. 화면에 코드가 그대로 나온다. 코드 의미는 원천 명세로 확인한 뒤 정해야 한다. PR24 검수 비차단 관찰 1과 같은 지점이다.
2. 런북 §8 "남은 실서버 운영 실행 조건" 1·4·5번과 `docs/ops/collector.md`의 "운영 서버 DB에 적재할 때 다시 기록한다"를 이 기록으로 갱신할지 결정이 필요하다. 이번 PR은 이 기록 문서만 담는다.
3. 런북 §1.1은 SSH를 관리자 IP로 제한하라고 권하지만 UFW 22는 Anywhere다. 배포 러너 IP가 바뀌어서 그대로 두었다.

## 미검증

- 외부 G4 blue/green·롤백(위 승인 대기)
- 운영 디스코드 경보 실제 수신
- Healthchecks·UptimeRobot
- 정기 수집 첫 회차
- 백업 타이머(#16)·복원(#17)
- 부하·성능 측정(#18)
- 실제 Resend·Discord 사용자 알림(#7, `ALERTS_ENABLED=false`)

서버 상태는 bootstrap 직후의 blue 단일 색이며 `prev_*`는 없다.
