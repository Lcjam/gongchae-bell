# Changelog

모든 주요 변경 사항을 [Keep a Changelog](https://keepachangelog.com/ko/1.1.0/) 형식으로 기록한다.
실측하지 않은 운영 수치나 미실행 서버 배포를 완료로 적지 않는다.

## [2026-11-22] - 운영 배포 main `e0208ff`

`gongchae.enjoylcjworld.com` 운영 서버에 blue/green으로 배포했다. 직전 운영판은 `4cc6e4e`다.
- 배포: Deploy run [`45001647801`](https://github.com/Lcjam/gongchae-bell/actions/runs/45001647801)(workflow_dispatch), `bluegreen-e0208ffd16c3-45001647801-1`, blue → **green**, `DEPLOY_OK`, 운영 이미지 digest `sha256:5f80098b29b51fe6e5f759241a66e6532a645b8690a81af417355d4cb2d264f9`
- 외부 G4(GitHub 러너): 28요청, 실패 0, p95 934ms, max 945ms
- 사용자 화면에 반영된 변경: 프론트 디자인 통합(PR #39, `app.css`·공통 layout). 알림이 꺼진 상태라 header의 `알림 받기`는 나타나지 않는다.
- 함께 반영된 변경: 해지 대장 처리 보완(PR #34, `ALERTS_ENABLED=false`라 운영에서는 아직 쓰이지 않음), 서버 `deploy/perf/` 성능 도구(PR #35)
- 바뀌지 않은 것: DB 스키마(Flyway V5), `COLLECTOR_ENABLED=false`, `ALERTS_ENABLED=false`, 모니터링 컨테이너
- 기록: [`collab/2026-11-19-resume/claude-main-e020-deploy-result.md`](collab/2026-11-19-resume/claude-main-e020-deploy-result.md)

## [Unreleased]

### Added
- **#17 해지 대장 파일 도구 (`ops/#17-journal-tool`)**: 앱 JAR의 `erasure-journal version|check|init|mark|verify`. Spring·DB 없이 공통 strict 파서로 대장을 검사하고, 첫 표지 생성(`init`)과 표지 추가(`mark`)를 같은 디렉터리 임시 파일 → fsync → 원자 게시 → 디렉터리 fsync → 재확인 순서로 한다. 실제 boot JAR을 Linux 컨테이너에서 DB·env·네트워크 없이 돌리는 smoke를 CI `test` 잡에 붙였다.
  - *(미완료)*: 앱 정지·권한 설치·보존 사본 확인을 맡는 감독 작업과 대장 정리(compact)는 다음 PR이다. 그 전에는 운영에서 쓰기 명령을 실행하지 않는다.
- **#14 배포 자동화 소스·문서 준비 (`ops/#14-bluegreen-deploy`)**:
  - ARM64 멀티스테이지 `Dockerfile`(`eclipse-temurin:21-jdk-noble` → `eclipse-temurin:21-jre-noble`, UID `1000:1000` 비루트 실행, `-Xmx512m`)과 민감 자료·로컬 상태를 제외한 `.dockerignore`.
  - 운영 Compose `compose.prod.yml` (`postgres:17` 내부망 격리, `app-blue`/`app-green` `profiles` 분리 및 `stop_grace_period: 40s`, `caddy:2-alpine` 80/443 전용 공개, `erasure` 호스트 디렉터리 분리).
  - Caddy 설정 `deploy/caddy/Caddyfile` 및 `upstream.{blue,green}.caddy` (보안 헤더, `/subscriptions*` `no-referrer`/`no-store`/`noindex`, `t` 쿼리·`Referer`·`Authorization`·`Cookie` 로그 마스킹, edge 프록시 IP 신뢰 경계).
  - ADR-0005 순서(`switch` → 외부 G4 판정 → `finalize` 구 색 `stop -t 40` 또는 `abort` 즉시 복귀)와 `deploy_id` 상관관계·`flock` 배타 잠금·중단 상태 fail-closed를 구현한 `deploy/deploy.sh` 및 외부 G4 프로브 `deploy/g4-probe.sh`.
  - CI 검증 잡(`deploy-verify`: 구문/단위·대역 시험/Compose 검증/실제 Caddy 컨테이너 통합 시험/ARM64 이미지 빌드)을 `.github/workflows/ci.yml`에 추가하고, GHCR ARM64 푸시 및 외부 G4 게이트 기반 SSH 배포 워크플로 `.github/workflows/deploy.yml` 작성.
  - 키 이름 전용 운영 환경변수 예시 `deploy/.env.prod.example` (`application.yml`에 `server.forward-headers-strategy: native` 추가, `DeploymentConfigIntegrationTest` 추가).
  - 최초 `bootstrap`과 `bluegreen` rollout 측정 조건 분리, 고정 G4 규격, 실패/중단 복구 절차, `#15`/`#16`(`pg_dump -Fc` + `erasure.log`) 연동을 포함한 `docs/runbook.md` 작성.
  - *(미완료 · 후속 운영 실행)*: OCI 실서버 `bootstrap` 적용, `gongchae.enjoylcjworld.com` 실 HTTPS 자동 배포 성공/실패 롤백 G4 실측, 외부 포트 차단·자원 실측은 실서버 환경 준비 후 기록한다.

- **#15 모니터링 소스·문서 (`ops/#15-monitoring`)**:
  - `compose.monitoring.yml` overlay(Prometheus `v3.16.0` 호스트 포트 없음·보존 1년, Grafana `13.0.2` `127.0.0.1:3000` 루프백만), scrape·경보 규칙 6개와 `promtool` 시험, 대시보드 `공채벨 운영`, Grafana 전달 규칙·운영 디스코드 연락처(웹훅 미설정 시 로컬 닫힌 포트로 전송 꺼짐).
  - 앱: 정기 회차 `COMPLETE` 뒤 선점한 앱만 1회 보내는 Healthchecks heartbeat(`HEARTBEAT_COLLECTOR_URL`, 기본 꺼짐), API 경고·차단 한도 게이지, HTTP 요청 히스토그램(5ms~30s, 25초 예산 요청까지 p95가 포화되지 않음).
  - #14 통합: `deploy.sh monitoring <compose 인자>`(앱 태그를 `deploy/state`에서 읽어 overlay 명령 실행), `compose.prod.yml`이 앱에 heartbeat URL 전달, `deploy.yml`이 `compose.monitoring.yml` 동기화, 실제 Compose 통합 시험 `compose-integration.sh`와 CI 잡.
  - *(미완료 · 후속 운영 실행)*: 서버 설치, 외부 포트 차단 확인, 운영 디스코드·Healthchecks.io·UptimeRobot 실제 발동·수신·복구, 메모리·디스크 실측. M2 발송 지표와 묶음 heartbeat는 #7.

### Added (M1 Baseline on `main`)
- **#2~#5 공고 수집 및 웹 M1 기반** (`000dce55`): Java 21 / Spring Boot 4.1.2 / PostgreSQL 17 / Flyway(V1~V4), 공공데이터 채용정보 수집기(`START_SCAN` 최초 적재, 정기 수집, 상세 수집, 영속 일 예산), 탐색·상세·기관 3년 통계 웹 화면.
