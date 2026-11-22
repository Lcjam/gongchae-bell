# #18 성능·자원 측정 교차 검토 · claude

2026-11-20 · 설계 2라운드 · 요청: root codex, [`2026-11-19-resume/claude-performance-cross-review-request.md`](../2026-11-19-resume/claude-performance-cross-review-request.md)

- **읽은 것:** [`01-codex.md`](01-codex.md), [`01-agy.md`](01-agy.md), 내 [`01-claude.md`](01-claude.md), [`02-codex-review.md`](02-codex-review.md).
- **source 직접 대조(main `eb90301`):** `CatalogMetrics`, `JobSearchCriteria`, `deploy/deploy.sh`(`switch_to_target`, `abort`, 상태 파일), `deploy/g4-probe.sh`, `.github/workflows/deploy.yml`.
- **하지 않은 것:** 구현, 워크플로·운영 변경, 실측, 서버·env·키 접근. 아래 숫자는 설정 제안이다.

## 1. source로 확인한 사실 (교차 검토의 근거)

| # | 사실 | 위치 | 영향 |
|---|---|---|---|
| S1 | 내부 트래픽 판정: `X-Gongchae-Internal` 값이 `true`(대소문자 무시)나 `1`, 또는 UA에 `gongchae-probe`·`uptimerobot`·`healthchecks`·`g4-deploy-check`가 있으면 `INTERNAL`이다. UA `curl/`은 `BOT`이다 | `CatalogMetrics.java:12-15,49-59` | 부하 요청에 헤더와 UA를 고정하면 user 화면 지표가 오염되지 않는다(agy·codex 수용). `http_server_requests_*`에는 합성 요청이 그대로 들어간다 |
| S2 | `/jobs` 열거 값은 **대소문자를 구별**한다. `status ∈ {open, check, closed, all}`(기본 `open`), `sort ∈ {deadline, begin}`(기본 `deadline`). 목록 밖 값은 **오류 없이 기본값으로 바뀐다** | `JobSearchCriteria.java:19-21,38-39`, `allowEnum` | agy 예시의 `status=ALL`·`status=OPEN`·`sort=start`는 실제로는 `open`·`deadline`으로 측정된다. codex 지적이 맞다 |
| S3 | 전환 단계 순서: `write_pending_state` → upstream reload → `post_switch_check` → `SWITCH_OK` 로그. `SWITCH_OK`는 **표준 출력에만** 남고 상태 파일이 없다. 사후 점검이 실패하면 pending을 지우고 `last_deploy_result=POST_SWITCH_ROLLED_BACK`이 된다. abort가 성공하면 `ABORTED_TO_<COLOR>`가 된다 | `deploy.sh:532-590,747` | 내 01의 "pending과 upstream 확인 + 5초" 촉발은 `post_switch_check` 완료의 증거가 아니다(§4) |
| S4 | `g4-probe.sh`는 `CURL_BIN` 환경변수로 curl을 바꿀 수 있다. 그러나 `deploy.yml`의 해당 단계 env는 고정이다(`DEPLOY_*`, `SITE_DOMAIN`, `ACTION`, `TARGET_SHA`, `IMAGE_TAG`). URL, 창 15초, 최소 15회, 간격 1초는 명령 인자로 고정돼 있다. **runner에서만 실패를 주는 hook은 지금 없다** | `g4-probe.sh:23,74`, `deploy.yml:110-186` | runner 전용 훈련은 워크플로를 바꿔야 가능하다(§4) |
| S5 | abort는 프로브가 끝난 뒤(`wait` → `g4_rc` → `deploy.sh abort`) 실행된다 | `deploy.yml:211-217` | 실제 장애 방식에서 사용자 노출은 "장애 시점부터 15초 창 끝까지 + SSH와 abort 시간"이다 |

## 2. 측정 정의: 합의안과 정확한 정의

**다섯 화면군과 대표 경로(1단계):**
- `home` `/`
- `list` `/jobs`
- `filter`:
  - `career=newcomer`
  - `region`·`ncs` 실존 코드 조합
  - `q=<키워드>&status=all`
  - `status=all&page=<깊은 쪽>`
  - `sort=begin`
- `detail` `/jobs/{sn}` 5개
- `institution` `/institutions/{code}` 5개(상위와 중간)

agy의 5/5 축소를 받고, 내 01의 50/20은 철회한다. 코드·SN·기관은 실행 직전 공개 화면이나 읽기 전용 조회로 고정한다. **사전 검증 1회:** 필터 URL마다 응답에 적용 값(선택된 옵션 등)이 있고, 무필터 응답과 결과가 다른지 확인한다. 실패하면 그 경로를 측정에서 뺀다(S2 때문).

**제외:** `/jobs/{sn}/source`(외부 302와 원문 이동 집계), 모든 `POST /subscriptions/*`.

**헤더(S1):** `X-Gongchae-Internal: 1`, `User-Agent: gongchae-probe/perf-18`.

**keep-alive:**
- 워커마다 `http.client.HTTPSConnection` 하나를 재사용하는 closed-loop다. `urllib.request`는 요청마다 연결을 새로 열 수 있어 쓰지 않는다.
- 연결이 끊긴 요청은 재시도하지 않고 오류로 기록한다. 다음 요청 전에 다시 연결한다.
- 결과는 "동시성 C의 closed-loop 응답 시간"이다. 포화 상태의 open-loop 도착률 성능을 뜻하지 않는다(codex 지적 수용).
- 첫 요청의 TLS 연결 비용은 워밍업에서 빠진다. 클라이언트 위치와 RTT를 기록한다.

**분모와 오류:**
- 측정 단계(워밍업 제외)에서 **보낸 모든 요청**을 `n_total`로 센다.
- 오류는 200이 아닌 응답(3xx 포함, 대상 경로는 모두 200이 기대값), 타임아웃(읽기 10초), 연결·TLS 오류다.
- `error_rate = n_err / n_total`. 경로 묶음마다 따로 낸다.

**p95:**
- nearest-rank다: 오름차순 정렬 뒤 `x[⌈0.95·n⌉]`(1부터). `g4-probe.sh`의 `int((NR*95+99)/100)`와 같다.
- **1차 보고는 성공(200) 표본만으로 한 p95와 `n_ok`다.**
- 오류가 1건이라도 있으면 "전체 표본 p95"를 함께 낸다. 이때 타임아웃은 10초 이상으로 잘린 값이라고 표시한다.
- 실패 표본을 조용히 빼거나 성공 p95에 섞지 않는다(codex 수용). 표본이 20개 미만인 묶음은 p95 대신 최댓값과 n만 적는다.

**표본과 시간 상한(제안):**
- S1(C=1)은 묶음당 40회, 최대 3분.
- S2(C=4)는 120초, 최대 1,500요청.
- 하루 부하 총량은 15분 이내.
- 안전 중단: 직전 30초 오류율 2% 초과, 5xx 3건, 연속 타임아웃 2회 중 하나. agy와 내 안을 합친 것이다. 목표가 아니라 보호 장치다.

**자원 표본:**
- **일정:** 단조 시계로 `t0 + k·5초`에 예약한다. 예정 시각과 실제 시각을 모두 남긴다. 앞 표본이 넘치면 다음 예약을 건너뛴다. 몰아서 찍지 않는다(codex 수용). 내 01의 `vmstat 1 2`는 철회한다.
- **호스트:** `/proc/stat`(CPU 누적 틱, 차분은 집계 단계에서), `/proc/meminfo`의 `MemAvailable`·`SwapFree`, `/proc/loadavg`. 지연이 없다.
- **컨테이너:** `docker stats --no-stream` 원문을 그대로 남긴다.
  - `CPUPerc`는 코어 하나를 100%로 잡는 값이라 2 vCPU에서 최대 200%다. 호스트 비율은 2로 나눠 따로 낸다.
  - `MemUsage`는 docker가 cgroup 사용량에서 캐시 일부를 뺀 표시값이고 **RSS가 아니다**. 이름을 그대로 쓰고 RSS라고 부르지 않는다. 여유 판단은 호스트 `MemAvailable`을 주 근거로 한다.
- **디스크:** `df -B1 /`는 매 표본에서, 볼륨 크기(`du -sb`)와 `docker system df`는 구간 앞뒤에만 잰다.
- agy의 2초 간격은 `docker stats` 한 번에 1초 이상 걸릴 수 있어 지키기 어렵다. 5초로 통일하자는 의견이다.

**수집 구간 전제:**
- 실제 정기 수집 1회 관측을 우선한다. 매시 7분 슬롯이고, 외부 API를 추가로 부르지 않는다.
- 운영에서 collector를 켜기 전에는 **평상시만** 재고 수집 구간은 "미완료"로 둔다(codex 수용).
- fixture 복제본은 별개의 근거로만 쓴다. 내 01의 "base URL만 대역으로"는 설정이 가능한지 확인하지 않은 주장이라 보류한다.
- 관측할 때는 `collect_run` id·결과·소요와 `collector_*` 지표를 같은 창에 기록한다.

**blue/green 겹침:** 다음 승인된 정상 배포(또는 §4 훈련)에 샘플러를 붙인다. 측정을 위해 배포를 일부러 반복하지 않는다. 두 색을 배포 밖에서 함께 띄우지 않는다(codex 수용). 요청이 두 앱에 나뉜다는 보장은 없으므로, 메모리 중첩 관측으로만 기록한다.

## 3. 최소 2파일 하네스로 먼저 갈 수 있는 범위

| 파일(제안) | 역할 |
|---|---|
| `deploy/perf/perf-probe.py` | 사전 검증, 부하(S1·S2), 원자료 CSV, 묶음별 집계(JSON+MD). 표준 라이브러리만 쓴다 |
| `deploy/perf/sample-resources.sh` | VM 자원 샘플러. stop 파일로 종료한다 |

**1단계에서 채우는 것:**
- 평상시 다섯 화면군의 p95·오류율(C=1, C=4).
- 무부하·부하 자원.
- 원자료와 조건을 `docs/perf/YYYY-MM-DD-<id>/`에 남긴다.
- 실행 위치는 사용자 PC(한국)나 외부 호스트 하나로 고정하고 기록한다. 새 Actions 워크플로는 만들지 않는다. 내 01의 `perf.yml`과 별도 `summarize.py`는 철회한다.

**1단계에서 비워 두는 것:** 수집 중(collector 활성 전제), blue/green 겹침(다음 배포), 실패 G4 훈련(별도 결정), C=8·10. 표에 "미측정"으로 둔다.

**시험:** 로컬 대역 HTTP 서버로 확인한다: 헤더 전송, 분모와 오류 분류, nearest-rank, stop 파일 종료, 안전 중단. 스타일 복사형 시험은 만들지 않는다.

## 4. 실패 G4 → abort 훈련: 두 방식 비교 (실행 배정 아님)

| | A. 실제 장애(새 색 정지) | B. runner 관측 실패 |
|---|---|---|
| 밟는 경로 | 실제 502 → 외부 G4 실패 → `wait`/`g4_rc` → `deploy.sh abort` → 구 색 복귀 | 프로브 판정만 실패 → 같은 `g4_rc` → 같은 `deploy.sh abort` |
| 증명하는 것 | 앱 장애의 **외부 탐지**와 abort 복구, 사용자가 본 오류 구간 | 서버 측 abort 절차(구 색 헬스, Caddy 복귀, 새 색 정지, 상태)와 워크플로 분기. **앱 장애 탐지는 증명하지 않는다** |
| 사용자 영향 | 실제 502. 장애 시점부터 15초 창 끝까지 + SSH·abort(S5) | 없음(공개 경로 그대로) |
| 지금 가능한가 | 워크플로 변경 없이 가능. 서버에 촉발 스크립트가 필요하다 | **불가.** 기존 hook이 없다(S4). 워크플로 변경이 필요하다 |

**A의 촉발 정정(내 철회):**
- 01의 "pending과 upstream 확인 + 고정 5초"는 철회한다. pending은 `post_switch_check` **전에** 기록된다(S3).
- 증거 기반 촉발은 다음과 같다:
  1. `pending_deploy_id`가 이번 id다.
  2. 그 id의 전환 프로세스가 끝났다. `deploy.lock`을 `flock -n`으로 짧게 잡았다 놓을 수 있거나, 해당 `deploy.sh … --phase switch` 프로세스가 없다.
  3. pending이 아직 남아 있다. 사후 점검이 실패했다면 pending을 지우므로, 남아 있으면 `SWITCH_OK`라는 뜻이다.
- 그 직후 `docker stop -t 0 app-<새 색>`을 한다. 워크플로는 SSH가 끝난 직후 stop 파일을 만들고 15초 창을 시작하므로, 이 순간은 창 안이다.
- 증거: Actions의 FAIL 줄 시각, `ABORTED_TO_<COLOR>`, 훈련 전후 `deploy.sh status`.

**B의 최소 방법(구체안, 미구현):** `deploy.yml`에 `workflow_dispatch` 전용 선택 입력(예: `drill_runner_fault`, 기본 꺼짐)을 둔다. 켜면 그 단계에서만 `CURL_BIN=deploy/drill/curl-fault.sh`를 export한다. 래퍼 규칙:
- 실제 curl을 그대로 실행한다.
- `$RUNNER_TEMP/g4.stop`이 생긴 뒤(= 전환이 끝난 post window)의 결과만 실패로 바꾼다. 예: 상태 `599`와 비영 종료. 그래서 FAIL 줄과 JSON에서 주입 실패로 구별된다.
- **실패를 성공으로 바꾸는 일은 절대 하지 않는다.** 그래서 가짜 PASS를 만들 수 없다.
- URL(`/`·`/jobs`), 간격 1초, 창 15초, 최소 15회는 그대로다.
- 같은 실행에서 `--context`를 `github-actions-external-drill`로 바꿔 보고서에 훈련 표시를 남긴다.
- 변경 범위는 워크플로 몇 줄과 래퍼 1파일이다. push 경로에서는 켤 수 없어야 한다.

**agy 권장안 A 정정:**
- "훈련 전용 `G4_HOOK`"는 `deploy.sh`의 서버 CLI 경로에만 있다. 워크플로 G4에는 없다.
- `/drill-g4-fail` 404를 실패로 세면 고정 URL 계약이 바뀐다.
- 그래서 그 안은 지금 그대로는 "같은 G4_FAIL → abort"가 아니다(codex와 같은 결론).

**의견(결정은 사용자):** PRD §7의 "실패 롤백 G4 기록"을 사용자 영향 0으로 먼저 채우려면 B다. 다만 증거의 한계(앱 장애 탐지 아님)를 포스트모템 맨 위에 적어야 한다. 실제 탐지까지 보이려면 A를 짧은 공지 창에서 한다. 두 방식 모두 `action=rollback` 성공은 증거가 아니다. runbook 243행 문구는 고쳐야 한다(세 안 공통).

## 5. 자기 철회와 보완 요약

| 01-claude 항목 | 처리 |
|---|---|
| 상세 50·기관 20, 동시성 8, `perf.yml`, 별도 `summarize.py`, 훈련 스크립트를 1단계에 포함 | **철회.** §3의 2파일·5/5·C=1·4로 축소 |
| 자원 샘플러 `vmstat 1 2` + "5초 간격" | **철회.** 단조 시계 예약, `/proc` 기반, 예정·실제 시각 기록 |
| 훈련 촉발 "pending + 5초" | **철회.** 잠금 해제 + pending 유지 증거(§4) |
| 실제 장애 방식 A를 1순위로 둠 | **보완.** A·B를 증거 종류로 나눠 비교하고 순위는 사용자 결정으로 넘김 |
| internal 헤더 없음 | **보완.** S1 헤더와 UA 고정 |
| 수집 대역 "base URL만 대역으로" | **보류.** 확인하지 않은 주장 |
| "p95는 nearest-rank, 성공/오류 분리, 위치 고정, `/source` 제외" | 유지 |

## 6. 남은 쟁점

1. 훈련 방식(A, B, A 뒤 B)과 B에 필요한 워크플로 변경 승인.
2. 부하 실행 위치(사용자 PC와 외부 호스트 중 무엇으로 고정할지).
3. collector 운영 활성 시점. 수집 구간은 그 뒤에만 채운다.
4. 안전 중단값과 하루 부하 총량의 최종값(제안만 함).
5. 병목이 나왔을 때 #18 범위: 기준선과 한계 기록으로 닫고 최적화는 별도 이슈로 하자는 agy 제안에 동의한다.
