# 운영 성능·자원 측정 도구 및 기준선 (#18)

> 근거: [PRD §6·§7](../prd.md), [운영 Runbook](../runbook.md), [모니터링과 경보](monitoring.md), [ADR-0014](../adr/0014-initial-settings.md)
> **현재 상태 (2026-11-20)**: 최소 2파일 측정 도구(`deploy/perf/perf-probe.py`, `deploy/perf/sample-resources.sh`)와 로컬 대역 단위 시험만 구현돼 있다. **실제 운영 부하 실측, 수집 중 관측, `blue/green` 겹침 자원 관측, 실패 유도 `G4_FAIL → abort` 훈련, 근거 기반 목표 수치 설정은 아직 수행하지 않았다.** 아래 숫자는 모두 실행 전 **설정·안전 상한 제안**이며 실측값이나 목표가 아니다.

---

## 1. 실행 위치 분리 계약 (외부 PC vs Linux OCI VM)

| 도구 | 실행 위치 | 역할 | 접근·금지 경계 |
|---|---|---|---|
| `deploy/perf/perf-probe.py` | **OCI VM 바깥 고정 클라이언트** (최초: 사용자 PC, Python 3 표준 라이브러리) | 고정 URL 사전 검증(`preflight`), 읽기 전용 GET keep-alive closed-loop 부하, 원자료 CSV 및 화면군/케이스별 nearest-rank 집계(`summary.json`, `summary.md`, `conditions.md`) | `/jobs/{sn}/source`·`/subscriptions/*`·POST 금지, 리다이렉트 미추적, 응답 본문·PII·비밀값 비저장, SSH·VM 직접 접근 없음 |
| `deploy/perf/sample-resources.sh` | **Linux OCI VM 내부** (`ubuntu` 계정, Bash + `/proc` + `docker`) | 5초 monotonic 예약으로 호스트 `/proc/stat`·`/proc/meminfo`·`/proc/loadavg`·`df -B1` 및 `docker stats --no-stream`·`RestartCount` 수집, 구간 전후 `docker system df`·선택 볼륨 `du -sb` | **읽기 전용** (컨테이너 재시작, DB·`.env`·배포 상태 변경 금지) |

---

## 2. 고정 URL 목록·사전 검증(Preflight)·헤더

- 예시 파일: [`deploy/perf/urls.example.tsv`](../../deploy/perf/urls.example.tsv) (`group`, `case_id`, `path` 3열 TSV). 실제 실행 전에 대상 환경의 실존 코드·공고 `sn` 5건·기관 코드 5건으로 사본을 만들어 고정한다.
- **다섯 화면군**: `home`(`/`), `list`(`/jobs`), `filter`, `detail`(`/jobs/{sn}` 5건), `institution`(`/institutions/{code}` 5건).
- **실제 허용 필터값 (`JobSearchCriteria.java` 소문자 대소문자 구별)**:
  - `status`: `open` | `check` | `closed` | `all` (대문자 `OPEN`/`ALL` 금지)
  - `sort`: `deadline` | `begin` (`start` 금지)
  - `career`: `newcomer` | `newonly` | `career`
- **사전 검증(Preflight)**: 부하 시작 전에 모든 URL을 1회 조회해 HTTP `200`, 화면군별 핵심 마커(`home`·`detail`·`institution`의 `<h1>`/섹션 ID, `/jobs`의 `#jobs-filter-panel`·`검색 결과 N건`), 필터별 `<option selected>`·`#filter-q` 값(`result_count > 0`)·`<li class="page-item active">` 실제 페이지 번호를 검증한다. `sort=begin`처럼 기본 `/jobs`와 결과 건수가 같을 수 있는 필터도 선택 옵션으로 정확히 판별하며, 하나라도 적용되지 않거나 잘못된 `sn`/기관이면 측정을 시작하지 않는다.
- **고정 내부 헤더 (`CatalogMetrics.java`)**: 모든 요청에 `X-Gongchae-Internal: 1`과 `User-Agent: gongchae-probe/perf-18`을 전송해 `gongchae_page_views_total{traffic="user"}` 오염을 막는다(`traffic="internal"`로 분류, `http_server_requests_seconds`는 정상 집계).

---

## 3. 측정 설정 제안 · 집계 규칙 · 안전 중단

### 3.1 최초 실행 프로파일 (설정 제안 — 목표 아님)

| 프로파일 | 동시성 (`C`) | 상한 (먼저 도달 시 종료) | 비고 |
|---|---|---|---|
| `S1` | `1` | 화면군당 `40`요청 또는 최대 `180초` | 순차 기준선, 워밍업 1라운드 제외 |
| `S2` | `4` | 최대 `120초` 또는 최대 `1,500`요청 | 결정적 라운드로빈 순환, 일일 부하 총량 `15분` 이내는 운영 실행 기록으로 준수 |

- 최대 시간이나 최대 요청 수를 넘어 분모를 강제로 채우지 않는다.
- **closed-loop keep-alive**: 워커당 `http.client.HTTPSConnection` 1개를 재사용하며 원자료에 `reused_conn`(`0|1`)과 `reconnect_count`를 남긴다. 오류 요청은 자동 재시도하지 않고 오류로 기록한 뒤 다음 요청에서 연결을 복구한다. 결과는 **closed-loop 동시성 응답 시간**이며 포화 상태 open-loop 도착률 성능이 아니다.
- **분모·오류·p95**: `measure` 단계 전체 요청을 `n_total`로 삼고, 비200(3xx 포함)·타임아웃(기본 10초)·연결/TLS 오류를 구분 집계한다. nearest-rank(`ceil(0.95 * n)`)로 성공 표본 `p95_ok`(`n_ok`)를 기본 보고하고, 오류가 1건 이상이면 전체 표본 `p95_all`(타임아웃 상한 절단 표기)을 병기한다. `n < 20`인 화면군·케이스는 p95 대신 `n`과 `max`만 적는다.

### 3.2 안전 중단 및 원자료 보존

- **`perf-probe.py` 자동 HTTP 안전 중단 (`ABORTED_SAFETY`, exit `3`)**:
  - 직전 `30초` 창 오류율 `> 2%` (최소 창 표본 10건 이상)
  - 누적 HTTP `5xx >= 3건`
  - 연속 타임아웃 `>= 2회`
  - 또는 `--stop-file` 생성 / `SIGINT`·`SIGTERM` 수신(`STOPPED_BY_*`)
  - 중단 시에도 수집된 원자료 CSV와 요약을 저장하고 `ABORTED_SAFETY` 상태를 숨기지 않는다.
- **`sample-resources.sh` 호스트 경고 (자동 재시작 없음)**:
  - `MemAvailable < 1,536 MiB` (`WARN_MEM_AVAILABLE_LOW`)
  - `/` 디스크 사용률 `> 80%` (`WARN_DISK_USAGE_HIGH`)
  - 컨테이너 `RestartCount` 증가 (`WARN_CONTAINER_RESTART`)
  - 경고 관측 시 운영자가 외부 `perf-probe.py`에 `--stop-file` 또는 `SIGINT`로 즉시 부하를 중단한다.
- **원자료 덮어쓰기 금지**: 두 도구 모두 출력 디렉터리에 기존 결과 파일(`requests.csv`, `summary.json`, `resources.csv` 등)이 있으면 즉시 실패(`OUTPUT_EXISTS`)한다. `conditions.md`에는 알 수 없는 메타 값을 추측하지 않고 `미확인`으로 남긴다.

### 3.3 자원 표본 정의 (`sample-resources.sh`)

- `scheduled_utc`와 `actual_utc`를 모두 기록하며, `docker stats` 지연 등으로 1주기를 넘기면 밀린 표본을 몰아 찍지 않고 건너뛴 횟수(`skipped_since_prev`, `total_skipped_ticks`)만 기록한다.
- `docker stats`의 `CPUPerc`는 코어당 100% 기준(2 vCPU에서 최대 200%)이므로 원문과 `host_vcpu`(기본 `2`)로 나눈 정규화 값을 함께 남긴다.
- `mem_usage_cgroup_raw`는 Docker cgroup 표시값이며 **프로세스 RSS가 아니다.** 실제 VM 여유 판단은 호스트 `/proc/meminfo`의 `mem_available_mib`를 주 근거로 삼는다.

---

## 4. 실행 명령 예시 (실제 실행은 별도 승인·배정 후)

```bash
# 1) OCI VM (Linux 호스트)에서 읽기 전용 자원 샘플러 시작
/opt/gongchae-bell/deploy/perf/sample-resources.sh \
  --out-dir /tmp/perf-res-$(date -u +%Y%m%dT%H%M%SZ) \
  --interval 5 \
  --stop-file /tmp/perf-res.stop

# 2) 외부 PC/호스트에서 사전 검증 후 S1 실행
python3 deploy/perf/perf-probe.py \
  --base-url https://gongchae.enjoylcjworld.com \
  --urls /path/to/fixed-urls.tsv \
  --preflight-only

python3 deploy/perf/perf-probe.py \
  --base-url https://gongchae.enjoylcjworld.com \
  --urls /path/to/fixed-urls.tsv \
  --out-dir /path/to/out-s1 \
  --profile S1 \
  --stop-file /path/to/probe.stop \
  --client-location "user-pc-kr" \
  --app-sha "<실측 시 active_sha>" \
  --image-digest "<실측 시 RepoDigest>" \
  --active-color "<blue|green>" \
  --posting-count "<실측 직전 DB 건수>" \
  --collector-enabled "<false|true>" \
  --alerts-enabled "false"
```

---

## 5. 실측 현황 및 남은 #18 완료 범위

| 구분 | 상태 | 비고 |
|---|---|---|
| 로컬 단위·대역 검증 (`deploy/tests/perf-test.py`, `sample-resources-test.sh`) | **구현 완료** | 합성 HTTP/proc/docker 대역 검증. 실제 VM·공개 사이트 측정 아님 |
| 평상시 무부하·제한 부하(S1·S2) 실측 | **미실측** | 도구 검수·CI 통과 후 수행 예정 (`COLLECTOR_ENABLED=false` 상태 명시) |
| 수집 중 구간 실측 | **미실측** | `COLLECTOR_ENABLED=true` 전환 및 선행 경보 게이트 통과 후 정상 `:07` 슬롯 1회 관측 예정 (`collector` 비활성 데이터를 수집 중으로 기록하지 않음) |
| `blue/green` 겹침 자원 실측 | **미실측** | 다음 정상 승인 배포에 `sample-resources.sh`를 붙여 JVM 중첩 메모리·CPU 관측 예정 |
| 실패 유도 `G4_FAIL → abort` 훈련 및 포스트모템 (`docs/incidents/`) | **미실시** | 사후 `action=rollback`과 구별. 훈련 방식(runner-only vs 실제 502) 별도 준비·결정 후 수행 |
| 근거 기반 초기 성능 목표·메모리 한도 제안 | **미정** | 위 실측 완료 후에만 기입 |
