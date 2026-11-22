# #18 운영 성능·자원 기준선 및 장애 훈련 회고 독립안 (`agy`)

- **작성자**: `agy` (3자 설계 1라운드 독립안 — 타 에이전트 `#18` 독립안 미열람)
- **요청 근거**: `collab/2026-11-19-resume/agy-performance-independent-request.md`, `CLAUDE.md`, `docs/prd.md` (§3·§6·§7), GitHub Issue `#18`, `origin/main` (`c4465f5`) 운영 문서(`docs/runbook.md`, `docs/ops/collector.md`, `docs/ops/monitoring.md`, `docs/adr/0014-initial-settings.md`, `compose.prod.yml`, `deploy/g4-probe.sh`)
- **수치 표기 원칙**: 본 문서의 동시성·기간·표본 수·중단 임계값은 **실행 전 설계/설정 제안**이며 실측이 아니다. 과거 로컬/운영 적재 참고치인 3년 공고 `36,879건`은 맥락 참고용이며, 실제 실행 시점의 운영 DB 건수는 매 회차마다 직접 재관측해 원자료에 기록한다.

---

## 1. 목적과 경계 (무엇을 증명하고 무엇을 하지 않는가)

### 1.1 달성할 완료 기준 (`#18` · PRD §6·§7)
1. **웹 응답 기준선**: 3년치 실데이터가 적재된 운영 환경에서 홈(`/`)·목록(`/jobs`)·조건검색(`/jobs?...`)·상세(`/jobs/{sn}`)·기관(`/institutions/{code}`)의 **p95·오류율**을 데이터량·경로 분모·동시성·기간·수집 활성 상태와 함께 재현 가능하게 실측하고, 그 근거로 초기 목표를 제안한다.
2. **자원 기준선**: 단일 OCI Ampere A1 VM에서 **평상시 · 수집 중 · blue/green 겹침** 구간의 CPU·메모리·디스크를 실측해 여유와 한계(예: `ADR-0014`에 보류된 컨테이너 메모리 한도 근거)를 기록한다.
3. **장애 훈련과 포스트모템**: **실제 장애가 아닌 계획된 훈련**임을 최상단에 명시한 장애 훈련 1건을 데이터 유실·비밀값 노출 없이 수행·복구하고, 3자 독립 검토 후 조율자가 `docs/incidents/YYYY-MM-DD-제목.md`로 종합한다.

### 1.2 명시적 제외·금지 범위
- 새 제품 기능 추가, 유료 부하 도구·추가 VM 투입, 장시간 스트레스/파괴 부하 금지.
- 부하 시험 중 실제 외부 원천 API(`apis.data.go.kr`) 반복 호출 금지, `POST /subscriptions/*` 및 실제 Resend·Discord 반복 발송 금지.
- **기존 증거와의 경계**: 이미 수행한 **성공 `bluegreen` G4 배포**와 **커밋 후 사후 수동 `rollback`**은 정상 전환과 사후 복귀의 증거일 뿐, **전환 실패 유도 `abort` 훈련(`G4_FAIL` → `deploy.sh abort`)의 증거로 간주하지 않는다.**

---

## 2. 최소 측정 하네스 설계 (무의존성 · 무과금)

### 2.1 외부 HTTP 프로브 하네스 (`deploy/tests/perf-baseline.py` 제안)
- **도구 선택**: 새 외부 의존성이나 유료 서비스 없이 **Python 3 표준 라이브러리**(`concurrent.futures.ThreadPoolExecutor`, `urllib.request`/`http.client`, `csv`, `json`) 단일 스크립트로 작성한다(호스트·외부 러너 모두 Python 3 기본 보유).
- **실행 위치**: `deploy/g4-probe.sh`와 동일하게 **OCI VM 바깥**(GitHub Actions `ubuntu-24.04` 러너 또는 외부 호스트)에서 공개 HTTPS(`https://gongchae.enjoylcjworld.com`)로 실행하고, `runner_host`와 실행 위치를 메타에 기록한다.
- **G2 실사용자 지표 오염 차단 (`CatalogMetrics` 정합)**:
  - `src/main/java/kr/gongchae/bell/catalog/CatalogMetrics.java`는 `X-Gongchae-Internal: 1`(또는 `User-Agent`에 `gongchae-probe` 포함) 요청을 `traffic="internal"`로 분류한다.
  - 따라서 모든 부하 요청에 **`User-Agent: gongchae-probe/perf-18`과 `X-Gongchae-Internal: 1`**을 고정 전송해 `gongchae_page_views_total{traffic="user"}`를 오염시키지 않는다.
  - 동시에 `http_server_requests_seconds`는 정상 기록되므로, **클라이언트 왕복(TLS+Caddy+네트워크 포함) p95**와 **서버 내부 Prometheus `http_server_requests_seconds` p95**를 같은 시간 창에서 이중 대조해 네트워크 지연과 앱/DB 지연을 분리한다.
- **원자료 및 집계 포맷**:
  - 요청 1건마다 CSV/JSONL에 `ts_utc, phase(warmup|measure), scenario, route_group, path, status, latency_ms, error_kind(none|http_5xx|non_200|timeout|connect_tls)` 기록(토큰·개인정보·비밀값 없음).
  - 집계는 보간 오차가 없는 **정확한 정렬 분위수(p50, p95, p99, max)**와 경로별 분모(`total_requests`, `ok_200`, `error_count`, `error_rate`)를 출력한다.
  - **경로 제외 규칙**: `/jobs/{sn}/source`(외부 302 리다이렉트, `gongchae_source_redirects` 카운터 증가)와 모든 `POST /subscriptions/*`(레이트 리밋·외부 발송·개인정보 경로)는 부하 대상에서 제외한다.

### 2.2 서버 자원 샘플러 (`deploy/tests/sample-resources.sh` 제안)
- **읽기 전용 수집 항목** (운영 설정·DB 변경 없음):
  - 컨테이너별: `docker stats --no-stream --format "{{.Name}},{{.CPUPerc}},{{.MemUsage}},{{.MemPerc}},{{.NetIO}},{{.BlockIO}},{{.PIDs}}"` (`postgres`, `app-blue`, `app-green`, `caddy`, `prometheus`, `grafana`).
  - 호스트 전체: `free -m`(`MemAvailable`, `Swap`), `/proc/loadavg`, `df -h /`, 실행 전후 `docker system df` 및 `postgres_data`·`prometheus_data` 볼륨 크기(실측).
  - 앱 내부 보조 지표(Prometheus API 조회, 읽기 전용): 측정 창의 `jvm_memory_used_bytes{area="heap"}`, `hikaricp_connections_active`, `http_server_requests_seconds` 버킷 기반 p95.

---

## 3. 측정 대상·분모·동시성·기간·수집 상태 조건 (설정 제안)

### 3.1 실행 직전 필수 DB 스냅샷 메타 (매 실행 재관측)
과거 적재치 `36,879건`을 그대로 인용하지 않고, 실행 직전 읽기 전용 DB 집계로 아래 메타를 원자료 헤더에 고정 기록한다:
- `posting_total` (전체 공고 수), `posting_open` (`status='OPEN'`), `posting_closed`, `posting_check`
- `institution_count` (고유 기관 수), `posting_code_count` (조인 테이블 행 수)
- `collect_run` 최근 완료 시각(`last_complete_at`), `COLLECTOR_ENABLED` (`true|false`), `ALERTS_ENABLED` (`false`), 활성 앱 색(`active_color`) 및 이미지 태그(`active_sha`)
- 실존 테스트 ID 집합: 진행 공고 `sn` 5건(상세 보유 + 미수집 혼합), 공고 수가 많은 상위 기관 3건 + 중간 기관 2건의 `pblnt_inst_cd`, 허용 목록 내 NCS/지역 코드 2쌍.

### 3.2 대상 5개 화면군 (7개 세부 버킷, 모두 읽기 전용 `GET`)

| 화면군 | 세부 버킷 | 예시 경로 (실존 코드/ID 주입) | 관측 이유 |
|---|---|---|---|
| **홈** | `home` | `GET /` | 마감 임박(D-0~D-3) + 오늘 시작 공고 조회 |
| **목록(기본)** | `jobs_default` | `GET /jobs` | 필터 옵션 조회 + 기본 페이징 |
| **조건검색** | `jobs_filter_combo` | `GET /jobs?ncs=<코드>&region=<코드>&career=newcomer&status=OPEN` | `posting_code` 다중 조인 + 신입 가능(`R2010+R2030`) |
| **조건검색** | `jobs_keyword` | `GET /jobs?q=<키워드>&status=ALL&sort=start` | 3년 전체 `status=ALL` 제목 검색 비용 확인 |
| **조건검색** | `jobs_deep_page` | `GET /jobs?status=ALL&page=20` | 깊은 `OFFSET` 페이징 비용 확인 |
| **상세** | `job_detail` | `GET /jobs/{sn}` (5건 순환) | 공고 상세 + `steps`/`files` 조회 |
| **기관** | `institution` | `GET /institutions/{code}` (상위 3 + 중간 2 순환) | 3년 이력 + 연·월 시기 패턴 + 직무 분포 + 모집 인원 집계 |

### 3.3 동시성·기간·분모·수집 상태 매트릭스 (설정 제안 — 실측 전)

- **워밍업(집계 제외)**: 각 시나리오 시작 직전 7개 버킷 × 3회 = `21`요청(`phase=warmup`).
- **타임아웃**: `connect=3s`, `read=10s`.

| 시나리오 | 동시성 (`C`) | 기간 / 최소 분모 (제안) | 수집 상태 (`COLLECTOR_ENABLED`) | 목적 |
|---|---|---|---|---|
| **S1. 순차 기준선** | `C = 1` | 버킷당 `40`회 (총 `280`요청, 약 1~2분) | 실행 시 실제 값 명시 (`false` 또는 `true`, 창 내 수집 없음) | 경합 없는 순수 단건 응답 시간·쿼리 병목 식별 |
| **S2. 동시 경합 기준선** | `C = 5` | `60초` 또는 버킷당 최소 `50`회 (총 `>= 350`요청) | 창 내 수집 없음 | 소규모 동시 요청 시 p95·오류율·Hikari 풀(최대 10) 여유 관측 |
| **S3. 정기 수집 겹침** | `C = 5` (또는 `C = 1`) | 매시 **7분 정기 수집 슬롯 1회** 포함 `180초` (06:30~09:30, 버킷당 `>= 40`회) | **`true` 필수** (예정된 1회 정기 슬롯 자연 도래 — **인위적 반복 호출 금지**) | 정기 수집(`ongoingYn=Y` 반영 + 신규 상세 sweep)과 웹 읽기 동시 실행 시 p95·잠금·자원 변화 실측 |

- **수집 비활성 상태 처리 규칙**: 만약 `#18` 착수 시점에 운영 서버가 아직 `COLLECTOR_ENABLED=false`라면, **S1·S2는 `collector_enabled=false` 기준선으로 먼저 기록**하고, **S3는 `COLLECTOR_ENABLED=true` 전환 뒤 첫 정상 매시 7분 슬롯에서 측정**한다. 두 상태를 혼합하거나 비활성 데이터를 수집 중 실측으로 표기하지 않는다.
- *(선택 확장)* S2에서 오류율 `0%`, p95·메모리 여유가 충분할 때만 `C = 10`(60초) 1회를 추가해 Hikari 기본 풀 10 상한 근처의 한계를 1회 관측할 수 있다(필수 조건은 아님).

---

## 4. 자원 실측 계획 (평상시 · 수집 중 · blue/green 겹침)

| 구간 | 샘플링 간격 · 기간 (제안) | 측정 조건 | 핵심 확인 지표 |
|---|---|---|---|
| **R1. 평상시 (유휴)** | `5초` × `5분` (60샘플) | 단일 앱 색(`blue` 또는 `green`) + `postgres` + `caddy` + `prometheus` + `grafana`, 외부 부하 없음 | 기준 CPU%, 컨테이너별 RSS 합계, `MemAvailable`, `/` 및 볼륨 `du -sh` |
| **R2. 부하 · 수집 중** | `2초` × S2(`60초`) 및 S3(`180초`) | S2 동시 부하 중 / S3 매시 7분 실제 정기 수집 1회 중 | `app`·`postgres` CPU 피크, JVM 힙/RSS 증가, Hikari 활성 연결 피크 |
| **R3. blue/green 겹침** | `2초` × 배포 전 30초 ~ `switch` ~ 구 색 `stop -t 40` 완료 후 30초 | 실제 `bluegreen` 전환(또는 아래 §5 `abort` 훈련) 중 `app-blue` + `app-green` 동시 기동 구간 | **2앱(`-Xmx512m` × 2) + PG + Caddy + 모니터링 동시 피크 RSS**, 최소 `MemAvailable`, 스왑/OOM 유무 |

- **디스크 한계 기록**: `/` 가용 용량, `postgres_data` 현재 크기, `prometheus_data` 현재 크기 및 24시간 증가분을 실측한 뒤 1년 보존 디스크 소요량은 반드시 **"추정(실측 일 증가량 × 365)"**으로 구분 표기한다.

---

## 5. 장애 훈련 및 포스트모템 설계 (훈련 vs 실제 장애 경계)

### 5.1 경계 원칙
1. 포스트모템 파일 `docs/incidents/YYYY-MM-DD-<제목>.md` 첫 줄에 **`유형: 계획된 장애 훈련 (Planned Drill — 실제 장애 아님)`**을 명시한다(`CLAUDE.md`·`#18` 완료 기준).
2. 기존 성공 `bluegreen` G4 및 사후 수동 `rollback`은 이 훈련을 대체하지 않는다. 이번 훈련은 **전환 중 실패 감지 → 자동 `deploy.sh abort` 복귀** 경로를 실제로 밟고 타임라인·영향·탐지·복구 시간·재발 방지를 남기는 데 초점을 둔다.
3. 실제 운영 DB 행 변경·외부 API 반복 호출·실사용자 알림 발송·비밀값 노출이 없어야 한다.

### 5.2 훈련 시나리오 제안 (1순위 권장안 vs 대안 비교)

- **권장안 A — 무중단 `G4_FAIL` 유도 → 자동 `deploy.sh abort` 복귀 훈련 (사용자 영향 0건 목표)**:
  - **절차**:
    1. 현재 운영 중인 `main` 정상 이미지(동일 SHA)로 `deploy.sh deploy <tag> --mode bluegreen --phase switch --deploy-id drill-abort-<utc>`를 실행한다(`app-blue` + `app-green` 동시 기동 → 동시에 **R3 blue/green 겹침 자원 실측** 수행).
    2. VM 밖에서 **두 프로브를 병행**한다:
       - **실제 사용자 경로 감시 프로브**: 기존 `deploy/g4-probe.sh` (`/`·`/jobs`, 1s 간격) — 전환·복귀 전 구간 실제 `200` 연속성(`failures == 0`) 확인.
       - **실패 판정 트리거**: 훈련용 G4 판정이 의도된 조건(예: 훈련 전용 `G4_HOOK`가 실패 종료 또는 존재하지 않는 훈련 경로 `/drill-g4-fail` 404를 실패로 집계)으로 **`G4_FAIL`**을 반환하게 한다.
    3. 워크플로/운영 절차가 자동으로 **`deploy.sh abort --deploy-id drill-abort-<utc>`**를 호출해 아직 떠 있는 구 색으로 `caddy reload` 복귀 → 새 색 `stop -t 40` 정지 → `pending_*` 정리(`active_color` 원상 유지)를 마친다.
    4. `deploy.sh status`(`pending_deploy_id=none`), 외부 `g4-probe.sh` 사후 15초 `failures == 0`, DB 데이터 변화 0건, Caddy 로그에 비밀값/토큰 미노출을 확인한다.
  - **장점**: `deploy.sh abort`의 실제 실행·`caddy reload` 복귀·`stop -t 40` 정리·blue/green 메모리 피크를 모두 실측하면서도 공개 서비스 다운타임(`5xx`) `0건`을 유지한다.
  - **한계(명시 필요)**: 실제 앱 결함으로 `502`/`500`이 발생해 G4가 탐지하는 과정은 대체하지 않는다.

- **대안 B — 실제 `502` 감지 → `abort` 복구 훈련 (짧은 계획 점검 창 필요)**:
  - `switch` 직후 새 색 컨테이너만 일시 `docker stop`해 실제 외부 `g4-probe.sh`가 `502`를 감지하고 즉시 `deploy.sh abort`로 구 색에 복귀하는 방식.
  - 실제 MTTD(502 첫 발생 → 탐지)와 MTTR(502 발생 → 200 복구)을 실측할 수 있으나, 공개 도메인에 수 초~십수 초간 실제 `502`가 노출된다. 공개 출시 전(현재 실사용자·구독 0) 짧은 점검 창이라면 실행 가능하나, 반드시 사용자·조율자 합의와 `30초` 하드 타임아웃 수동 `abort` 대기 조건이 필요하다.

- **경보 수신 훈련과의 분리**: `GongchaeHttp5xxHigh`·Grafana→Discord·UptimeRobot·Healthchecks.io 수신 확인은 이미 `docs/ops/monitoring.md`의 비파괴 절차(`drill.yml`, 임시 404 모니터, `/fail` 1회)로 정의돼 있으므로 `#15` 수신 게이트와 역할을 섞지 않고, `#18` 포스트모템은 **배포 `abort` 복구 흐름과 MTTR/한계**에 집중한다.

### 5.3 3자 회고 협업 절차 (`CLAUDE.md` 준수)
1. 훈련 실행 후 원자료(타임라인, `g4-probe` JSON, `deploy.sh` 출력, `docker stats` 피크, 앱/Caddy 로그 요약 — 비밀값 제거)를 `collab/2026-11-20-performance/`에 공유한다.
2. `claude`, `agy`, `codex`(또는 Sol) 3자가 각자 독립적으로 원인·탐지/복구 지연 요인·재발 방지책을 작성한다.
3. 조율자가 이를 종합해 `docs/incidents/YYYY-MM-DD-<훈련제목>.md`로 최종 정리하고 리뷰 후 머지한다.

---

## 6. 단계별 중단(Abort) 및 회복(Recovery) 기준 (설정 제안)

| 단계 | 즉시 중단 조건 (하나라도 충족 시) | 즉시 회복 절차 |
|---|---|---|
| **부하 측정 (S1~S3)** | ① HTTP `5xx >= 3건` 또는 오류율 `> 2%` (`GongchaeHttp5xxHigh` 5%/5건 임계 이전 차단)<br>② `read timeout(10s)` 2회 연속 발생<br>③ 호스트 `MemAvailable < 1,536 MiB` 또는 컨테이너 OOM/재시작 발생<br>④ `/` 디스크 사용률 `> 80%` | 1. 외부 부하 스크립트 즉시 중단<br>2. `docker compose ... ps`, `deploy.sh status`, 내부 `/actuator/health/readiness` 확인<br>3. 외부 `g4-probe.sh --duration 15`로 `failures == 0` 확인 후 원인 분석 |
| **blue/green `abort` 훈련** | ① `switch` 전 새 색 readiness 실패(`PRE_SWITCH_FAIL`)<br>② `abort` 실행 후 `30초` 내 구 색 `200` 미복구<br>③ `pending_deploy_id` 잔류 또는 `STATE_MISMATCH` | 1. `./deploy/deploy.sh status`로 `active_color`·`pending_deploy_id` 확인<br>2. `pending_deploy_id`가 있으면 즉시 `./deploy/deploy.sh abort --deploy-id <ID>` 재실행<br>3. `active_color` 컨테이너 기동·`caddy validate && reload` 확인 후 외부 `g4-probe.sh` `failures == 0` 확인 |

---

## 7. 저장소 산출물과 근거 기반 목표 설정 흐름

1. **스크립트 (구현 라운드)**: `deploy/tests/perf-baseline.py`, `deploy/tests/sample-resources.sh` (+ 로컬 모의 서버로 CSV/p95 집계·중단 조건·`X-Gongchae-Internal` 헤더를 검증하는 단위 테스트).
2. **기준선 문서**: `docs/ops/performance-baseline.md`
   - 측정 일시(KST/UTC), DB 실측 건수, 수집/알림 활성 상태, 실행 위치(`runner_host`), 동시성·기간·분모 표
   - 7개 세부 버킷별 외부 왕복 p50/p95/p99/max·오류율 + 서버 내부 Prometheus p95 대조 표
   - R1(평상시)·R2(부하/수집)·R3(blue/green 겹침) CPU·메모리·디스크 실측 표
   - **근거 기반 초기 목표 제안**(예: 실측 p95 기반 여유 계수, 오류율 기준, 컨테이너 메모리 한도안) — 측정 전 미리 적지 않고 **실측 뒤에만 기입**
3. **훈련 포스트모템**: `docs/incidents/YYYY-MM-DD-<훈련제목>.md` (최상단 "훈련" 표기, 타임라인, 영향, MTTD/MTTR, 원인, 3자 검토 기반 재발 방지).

---

## 8. 2라운드 교차 검토용 핵심 쟁점

1. **장애 훈련 강도 선택 (권장안 A vs 대안 B)**:
   - 공개 출시 전(구독 0) 시점에서 **무중단 `G4_FAIL` 유도 `abort`(A, 실사용 5xx 0건)**로 갈지, 아니면 **수 초간 실제 `502`를 노출해 외부 `g4-probe.sh`의 실제 장애 탐지→`abort` MTTR(B)**까지 실측할지 합의가 필요하다.
2. **수집 중 측정(S3)의 시점**:
   - 현재 운영 서버가 `COLLECTOR_ENABLED=false`라면 S1·S2를 먼저 기록하고 S3는 `COLLECTOR_ENABLED=true` 전환 직후 실제 매시 7분 슬롯 1회에 맞춰 별도 추가할지, 아니면 `COLLECTOR_ENABLED=true` 전환 이후 `#18` 실측을 일괄 수행할지 정해야 한다.
3. **병목 발견 시 `#18` 범위 경계**:
   - 예컨대 3년 전체 `GET /jobs?q=<키워드>&status=ALL` 또는 상위 기관 `/institutions/{code}`에서 p95가 예상보다 높게 나올 경우, `#18`에서는 **실측 기준선과 한계 기록**으로 닫고 쿼리/인덱스 최적화는 별도 이슈로 분리하는 편이 DoD와 일정 통제에 안전하다.
4. **부하 실행 위치 고정**:
   - 외부 러너(GitHub Actions `ubuntu-24.04`)와 로컬 PC는 RTT가 다르므로 한쪽으로 고정하되, 서버 내부 Prometheus `http_server_requests_seconds` p95를 반드시 함께 기록해 네트워크 편차를 분리한다.
