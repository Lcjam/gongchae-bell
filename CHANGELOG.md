# Changelog

모든 주요 변경 사항을 [Keep a Changelog](https://keepachangelog.com/ko/1.1.0/) 형식으로 기록한다.
실측하지 않은 운영 수치나 미실행 서버 배포를 완료로 적지 않는다.

## [Unreleased]

### Added
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
