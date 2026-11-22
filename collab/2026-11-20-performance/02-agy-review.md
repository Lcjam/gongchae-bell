# #18 성능·자원 측정 및 실패 G4 훈련 설계 교차 검토 · agy

2026-11-20 · 설계 2라운드 교차 검토 · 요청: root Codex(`collab/2026-11-19-resume/agy-performance-cross-review-request.md`)

- **작성자·구분**: 추가 agy2. 1라운드 [`01-agy.md`](01-agy.md)는 다른 agy 세션이 작성한 독립안이며 본 검토에서 자기 실행 결과처럼 다루지 않는다.
- **읽은 문서**: `CLAUDE.md`, [`01-codex.md`](01-codex.md), [`01-agy.md`](01-agy.md), [`01-claude.md`](01-claude.md), [`02-codex-review.md`](02-codex-review.md), [`02-claude-review.md`](02-claude-review.md).
- **직접 대조한 source**: `JobSearchCriteria.java`, `CatalogMetrics.java`, `deploy/caddy/Caddyfile`, `deploy/g4-probe.sh`, `deploy/deploy.sh`, `.github/workflows/deploy.yml`, `docs/runbook.md`.
- **하지 않은 것**: 코드·시험·설정·타 브랜치(PR #30 포함) 변경, 실측·훈련 실행, 서버·계정·env·키·provider 접근. 아래 수치는 모두 실행 전 설정 제안이다.

---

## 1. 기존 `01-agy.md` 항목의 명시적 철회·정정

| `01-agy.md` 항목 | 처리 | source 근거 및 이유 |
|---|---|---|
| 조건검색 예시 `status=OPEN`, `status=ALL`, `sort=start` | **철회·교정** | `JobSearchCriteria.java:19-21,38-39,136-142`: 열거형은 소문자 대소문자 구별(`status ∈ {open,check,closed,all}`, `sort ∈ {deadline,begin}`, `career ∈ {newcomer,newonly,career}`)이며 불일치 시 오류 없이 기본값(`open`, `deadline`)으로 조용히 치환된다. Codex·Claude 지적이 정확하다. |
| HTTP 클라이언트 `urllib.request`/`http.client` 병기 | **철회** | `urllib.request`는 연결 재사용이 보장되지 않는다. 워커당 `http.client.HTTPSConnection` keep-alive closed-loop로 통일한다. |
| 1단계 동시성 `C=5`(선택 `C=10`), 2초 자원 샘플링, `docker stats`의 "RSS" 표기 | **철회** | 1단계는 `C=1`·`C=4`로 축소하고, `docker stats --no-stream` 자체가 1초 이상 걸리므로 5초 monotonic 예약으로 통일한다. `docker stats`의 `MemUsage`는 RSS가 아니라 cgroup 표시값이므로 원문 명칭 그대로 쓰고 여유 판단은 호스트 `MemAvailable`을 주 근거로 쓴다. |
| 외부 HTTP 하네스 중단 조건에 호스트 `MemAvailable`/디스크 혼합 | **철회·분리** | `perf-probe.py`는 VM 밖에서 실행되므로 SSH 결합 없이 호스트 메모리를 직접 읽지 않는다. HTTP 안전 중단(`perf-probe.py`)과 VM 자원 경고(`sample-resources.sh` → 운영자 stop-file 중단)를 분리한다. |
| 훈련 권장안 A의 "훈련 전용 `G4_HOOK`" 및 `/drill-g4-fail` 404 집계 | **철회** | `deploy/deploy.sh:753-759`의 `G4_HOOK`은 서버 CLI `--phase full` 경로에만 있으며 `.github/workflows/deploy.yml:176-221` 외부 G4 경로에는 없다. `/drill-g4-fail` 404는 고정 URL(`/`·`/jobs`) 계약을 바꾸므로 동일 `G4_FAIL → abort` 경로가 아니다. |

---

## 2. 1단계 성능·자원 하네스 합의 검토 (바로 구현 가능 여부)

**결론: 아래 4가지 구현 세부 사항을 고정하면 1단계 2파일 하네스는 바로 구현할 수 있다.**

### 2.1 수용하는 합의 범위
- **다섯 화면군 + 대표 경로**: `home`(`/`), `list`(`/jobs`), `filter`(소문자 허용값: `career=newcomer`, 실존 `region`+`ncs` 조합, `q=<키워드>&status=all`, `status=all&page=<깊은 쪽>`, `sort=begin`), `detail`(`/jobs/{sn}` 5건), `institution`(`/institutions/{code}` 5건). `/jobs/{sn}/source`와 모든 `POST /subscriptions/*`는 제외.
- **2파일 최소 하네스**: `deploy/perf/perf-probe.py`(사전 검증·부하·원자료 CSV·묶음별 JSON/MD 집계, Python 3 표준 라이브러리 전용) + `deploy/perf/sample-resources.sh`(VM 내 5초 monotonic 예약 샘플러, `/proc/stat`·`/proc/meminfo`·`/proc/loadavg`·`df -B1 /`·`docker stats --no-stream`, 전후 `du -sb`/`docker system df`). 새 Actions 워크플로(`perf.yml`)와 별도 `summarize.py`는 1단계에서 제외한다.
- **keep-alive 및 헤더**: 워커당 `http.client.HTTPSConnection` 재사용(closed-loop 응답 시간이며 open-loop 도착률 포화 성능과 구분, 리다이렉트 미추적, 끊김 시 미재시도·오류 기록 후 재연결). 모든 요청에 `X-Gongchae-Internal: 1`과 `User-Agent: gongchae-probe/perf-18` 고정(`CatalogMetrics.java:12-16,49-73`에서 `INTERNAL` 분류, `deploy/caddy/Caddyfile`에서 두 헤더를 제거하지 않음을 직접 확인).
- **분모·오류·p95**: 측정 창 전체 요청을 `n_total`, 비200(3xx 포함)·타임아웃(10초)·연결/TLS 오류를 오류로 집계해 `error_rate = n_err / n_total`을 묶음별로 산출한다. nearest-rank(`ceil(0.95 * n)`, `g4-probe.sh:143`과 동일)로 성공 표본 `p95_ok`(`n_ok`)를 1차 보고하고, 오류가 1건 이상이면 전체 표본 `p95_all`(타임아웃 상한 절단 표기)을 병기하며 `n < 20`이면 p95 대신 `max`와 `n`을 적는다.
- **동시성·상한·수집 전제·원자료**: S1(`C=1`, 묶음당 40회, 최대 3분)·S2(`C=4`, 120초, 최대 1,500요청, 일일 부하 총량 15분 이내)를 설정 제안으로 고정한다. 운영 `COLLECTOR_ENABLED=false` 상태에서는 **평상시만** 측정하고 수집 중 구간은 `미측정`으로 둔다(정기 수집 활성화 후 자연 도래하는 `:07` 슬롯 1회를 관측하며, 격리 fixture 대역은 별도 근거로 분리). `blue/green` 겹침은 다음 정상 승인 배포에 샘플러를 붙여 메모리 중첩만 관측한다. 원자료에는 본문·PII·비밀값을 저장하지 않고 SHA·digest·활성 색·실행 직전 DB 건수·플래그·클라이언트 위치·기준 RTT·`urls.tsv`를 함께 남긴다.

### 2.2 구현 전 고정해야 할 구체 보완점 4건
1. **사전 필터 검증 규칙 세분화 (`jobs.html`·`JobSearchCriteria.java` 대조)**:
   - `02-claude-review.md` §2의 "무필터 응답과 결과가 다른지 확인"은 `sort=begin`에서 `totalCount`가 기본 `/jobs`와 같기 때문에 건수만 비교하면 오탐된다.
   - 구현에서는 유형별로 검증해야 한다: ① 열거형 필터(`status=all`, `sort=begin`, `career=newcomer`, `ncs`, `region`)는 해당 `<option value="..." selected>` 존재와 `#jobs-filter-panel`의 `d-none` 제거 확인, ② `q=<키워드>&status=all`은 `<input id="filter-q" ... value="...">` 반영 **및 `totalCount > 0`**(빈 상태 화면을 잘못 측정하지 않도록), ③ `status=all&page=<N>`은 `CatalogRepository`의 끝 페이지 클램핑을 잡기 위해 `<li class="page-item active">` 안의 실제 페이지 번호가 `<N>`과 일치하는지 확인한다.
2. **keep-alive 실제 재사용 관측**:
   - `HTTPSConnection`을 쓰더라도 프록시/서버가 `Connection: close`를 반환하거나 유휴 종료가 일어나면 요청마다 TLS 핸드셰이크가 숨어 들어간다. 원자료 CSV에 `reused_conn`(`0|1`) 또는 집계에 `reconnect_count`를 남겨 keep-alive가 실제로 유지됐음을 입증해야 한다.
3. **S2 묶음별 최소 분모 보장**:
   - S2(`C=4`, 120초, 최대 1,500요청)에서 특정 느린 화면군 때문에 다른 묶음이 `n < 20`에 미달하지 않도록, 워커별 결정적 라운드 로빈(고정 URL 순환)으로 요청을 배분하고 정상 종료 시 5개 화면군 모두 `n >= 20` 충족 여부를 요약에 표시한다.
4. **안전 중단 경계 고정**:
   - `perf-probe.py` 자동 안전 중단은 HTTP 관측만으로 완결(직전 30초 오류율 > 2%, 5xx >= 3건, 연속 타임아웃 >= 2회, `--stop-file`/`SIGINT`)하고, VM 내부 `sample-resources.sh`는 `MemAvailable < 1,536 MiB`·디스크 `> 80%`·`RestartCount` 증가 시 경고를 표준 출력에 찍어 운영자가 즉시 부하를 중단하게 분리한다.

---

## 3. 실패 G4 → `abort` 훈련 검토 및 구체 반례

### 3.1 증거 범위와 기존 문서 정합
- **실제 앱 장애(방식 A: 새 색 `docker stop -t 0`)**는 "외부 `g4-probe.sh`의 실제 502 탐지 + `deploy.yml` `wait`→`g4_rc`→`deploy.sh abort` 복귀 + 실제 사용자 502 노출 구간(`post_window 15s` 종료 + SSH/`abort` 시간)"까지 증명한다. 대신 짧은 실제 502가 공개 URL에 노출된다.
- **runner-only 관측 실패(방식 B: runner 측 프로브 실패 주입)**는 공개 서비스 영향 `0`으로 "`g4-probe.sh` 실패 종료 → `deploy.yml` `wait`→`g4_rc`→`deploy.sh abort` → 구 색 복귀·상태 정리"를 증명한다. 단, **실제 앱 장애(502) 탐지는 증명하지 않으므로** 포스트모템 최상단에 이 한계를 명시해야 한다.
- 두 방식 모두 기존 사후 `action=rollback`과 구별되며, `docs/runbook.md:243`의 `실제 실패 유도 롤백(G4_FAIL 또는 rollback)` 문구는 후속 문서 작업에서 `G4_FAIL → abort`와 사후 `action=rollback`을 분리하도록 바로잡아야 한다(세 주체 공통).

### 3.2 `02-claude-review.md` §4 구체안에 대한 소스 기반 반례 3건

1. **반례 1 — 방식 B의 `curl-fault.sh`("상태 `599`와 비영 종료")는 `g4-probe.sh` 실제 분기와 충돌한다**:
   - `deploy/g4-probe.sh:74-89`를 직접 보면 `$CURL_BIN`이 **비영(`rc != 0`)으로 종료하면 `out`(`599`)을 파싱하지 않고 즉시 `return`** 하며 `kind=connect_tls curl_rc=<N>`(28이면 `timeout`)으로만 기록한다. 즉 비영 종료에서는 `status=599`가 FAIL 로그에도 JSON에도 남지 않는다.
   - 반대로 `rc == 0`으로 `599 <time_total>`을 출력하면 `g4-probe.sh:106`(`^5[0-9]{2}$`)이 `http_5xx`로 합산하므로 실제 서버 `5xx`와 JSON 카운터상 구분이 흐려진다(차라리 `rc == 0` + `499 <time_total>`로 `other_non_200` + `status=499`를 쓰거나, 고정 비영 `rc`(예: `rc=99`, `curl_rc=99`)로 `connect_tls` 로그에 남기는 등 `g4-probe.sh`의 실제 분기와 정확히 1:1로 맞춰야 한다).
   - **배포 동기화 범위 주의**: `.github/workflows/deploy.yml:161`은 `tar --exclude='deploy/state' -cf - compose.prod.yml compose.monitoring.yml deploy`로 `deploy/` 전체를 운영 서버 `/opt/gongchae-bell/deploy`에 동기화한다. runner 전용 래퍼를 `deploy/drill/curl-fault.sh`에 두면 불필요하게 운영 서버에도 복사되므로, `deploy/` 밖(예: `scripts/drill/`)에 두거나 `tar --exclude`로 제외해야 경계가 분명하다.
   - **이미지 재빌드·가드 조건**: `deploy.yml:38,69-76`상 `workflow_dispatch(action=deploy)`는 `build-and-push`를 실행해 같은 `sha-<commit>` 태그를 재빌드하므로 digest 변경을 함께 기록해야 하며, `drill_runner_fault` 입력은 반드시 `github.event_name == 'workflow_dispatch'`에서만 해석되고 실패를 성공으로 뒤집지 못함을 격리 테스트로 선검증해야 한다.

2. **반례 2 — 방식 A의 "`deploy.lock` 해제 + `pending_deploy_id` 잔류 = `SWITCH_OK`"는 실패 분기에서 거짓 양성이 가능하다**:
   - `deploy/deploy.sh:572-586`을 직접 확인하면, `post_switch_check`가 실패한 뒤 `rollback_reload_from_prev`가 실패(`ROLLBACK_RELOAD_FAILED`, 575행)하거나 `stop_and_remove_color`가 실패(`POST_SWITCH_CLEANUP_FAIL`, 580행)한 경우에도 `clear_pending_state`를 호출하지 않고 `deploy.lock`을 해제하며 종료한다.
   - 따라서 `(pending_deploy_id == id) && (lock 해제)`만으로는 `SWITCH_OK`를 유일하게 판별할 수 없다. 방식 A를 택한다면 촉발 조건에 ① `pending_deploy_id == id`, ② `last_deploy_id != id`(실패 결과 미기록), ③ `upstream.caddy`가 `app-<pending_color>:8080`을 가리킴, ④ `app-<pending_color>` 실행 중, ⑤ `deploy.lock` 미점유를 함께 확인하고, 수동 `abort` 복구 상한 시간을 사전 고정해야 한다.

3. **관찰 3 — `g4-probe.sh`의 트래픽 분류**:
   - `deploy/g4-probe.sh:74`는 `X-Gongchae-Internal` 헤더나 `gongchae-probe` UA를 보내지 않아 기본 `curl/` UA가 전달된다. `CatalogMetrics.java:19,67-70` 기준 `g4-probe.sh` 요청은 `traffic="bot"`으로 집계된다(`user`는 오염되지 않으나 `internal`이 아님). 훈련·성능 창에서 Prometheus `gongchae_page_views_total`을 대조할 때 `perf-probe.py`(`internal`)와 `g4-probe.sh`(`bot`)의 태그 차이를 혼동하지 않도록 문서에 명시하는 것이 좋다.

---

## 4. 종합 제안 (실행·배정 전 합의용)

1. **1단계 즉시 구현 가능**: §2.1 합의 + §2.2 보완 4건을 반영한 2파일 하네스(`perf-probe.py`, `sample-resources.sh`)와 로컬 대역 단위 검증을 먼저 구현하고, 평상시(`COLLECTOR_ENABLED=false`) 기준선만 실측한다. 수집 중·`blue/green` 겹침은 각각 collector 활성화 뒤 첫 `:07` 슬롯과 다음 정상 승인 배포에 분리해 붙인다.
2. **장애 훈련 방식 결정**: 아직 실행·새 워크플로 변경은 배정 전이다. 사용자 영향 `0`을 우선하면 **반례 1을 수정한 방식 B**(한계: 앱 장애 탐지 미포함 명시)를, 실제 502 외부 탐지·MTTR까지 검증하려면 **반례 2의 다중 조건을 보강한 방식 A**(짧은 계획 점검 창·사용자 동의)를 선택할 수 있으며, 최종 선택은 조율자 종합과 사용자 결정에 따른다.
