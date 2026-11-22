# #18 성능·자원 측정과 실패 G4 → abort 훈련 · claude 독립안

2026-11-20 · 설계 1라운드 독립안 · 요청: root codex, [`2026-11-19-resume/claude-performance-independent-request.md`](../2026-11-19-resume/claude-performance-independent-request.md)

- **읽은 것(main `eb90301`):**
  - PRD §6·§7, GitHub #18.
  - `deploy/deploy.sh`(`check_internal_app`, `post_switch_check`, `abort`), `deploy/g4-probe.sh`, `.github/workflows/deploy.yml`.
  - `compose.prod.yml`, `docs/runbook.md` §3–4, `docs/design/routes.md`, 기존 실측 `collab/2026-11-19-resume/claude-g4-result.md`.
- **읽지 않은 것:** 이번 라운드의 `01-agy.md`, `01-codex.md`.
- **하지 않은 것:** 앱·테스트·설정·워크플로 변경, 서버·env·키·provider 접근, 실측.
- **아래 숫자는 모두 제안값이나 안전 한계다.** 성능 목표가 아니다. 목표는 PRD대로 기준선을 잰 뒤 정한다.

## 0. 요약

1. **부하는 VM 밖에서 건다.** 대상은 공개 HTTPS 실제 경로(실제 3년 데이터)다. 하네스는 표준 라이브러리 Python 하나로 만든다. keep-alive closed-loop로 요청마다 CSV를 남긴다. 같은 창의 서버측 Prometheus 히스토그램과 대조한다. 외부 원문 이동 `/jobs/{sn}/source`는 **제외**한다.
2. **자원은 VM 안의 가벼운 샘플러로 잰다.** `docker stats`, `vmstat`, `free`, `df`, 볼륨 크기를 5초 간격 CSV로 남긴다. 세 상태를 잰다.
   - 평상시: 무부하와 부하.
   - 수집 중: 실제 정기 수집 1회. 외부 API는 하루 24회 정상 호출 안에서 쓰고 추가로 부르지 않는다. 반복이 필요하면 fixture 대역 복제본에서 따로 잰다.
   - blue/green 겹침: 실제 배포 창.
3. **실패 G4 → abort 훈련 후보:** 정상 이미지로 bluegreen을 진행하다가 전환 직후(내부 `post_switch_check` 통과 뒤, 외부 G4 15초 창 안) 새 색 컨테이너를 `docker stop -t 0`으로 멈춘다. 그러면 외부 프로브가 502를 기록하고 `G4_FAIL`이 나며, 워크플로가 `deploy.sh abort`로 아직 살아 있는 구 색에 복귀한다.
   - 기존 성공 G4 뒤의 `action=rollback`은 사후 rollback이라 이 증거가 될 수 없다.
   - **문서 정렬 필요:** runbook 243행의 "실패 유도 롤백(`G4_FAIL` 또는 `rollback`)"은 사후 rollback도 근거로 인정하는 것처럼 읽힌다.
4. **코드에서 확인한 사실:** abort는 프로브가 **끝난 뒤** 실행된다(`deploy.yml` 211–217행). 그래서 실패 유도 훈련에서 사용자는 장애 시점부터 15초 창이 끝날 때까지와 abort 실행 시간 동안 오류를 본다. 훈련 기록의 "탐지·복구 시간"에 이 구조를 그대로 적는다.

## 1. 기존 사실과 경계

- **성공 G4는 있다:** `836a3c0 → 517eb6b` bluegreen에서 외부 45회, 실패 0, p95 588ms(`claude-g4-result.md`).
- **사후 rollback(`action=rollback`) G4도 있다:** 24회, 실패 0. 같은 기록 115행은 "실패 유도 롤백(`G4_FAIL` → abort 경로): 하지 않았다"고 적었다. **PRD §7 "실패 롤백 G4 기록"은 아직 비어 있다.**
- **G4 계약(`g4-probe.sh`, `deploy.yml`):**
  - `/`와 `/jobs`를 1초 간격으로, connect 3초·max 5초로 조회한다.
  - 전환 전 기준선 10초, 전환 뒤 post window 15초, 최소 15회.
  - 실패 = curl 오류, 5xx, 200 아님.
  - FAIL 줄마다 UTC 초 단위 시각이 Actions 로그에 남는다. JSON 보고서에는 첫 실패 시각이 없다.
- **내부 점검(`deploy.sh`):** 전환 전 readiness와 `/`·`/jobs`·`/robots.txt` 200, 전환 직후 Caddy 경유 `post_switch_check`. 이 둘에서 걸리면 G4가 아니라 `PRE_SWITCH_FAIL`·`POST_SWITCH_FAIL` 경로다.
- **현재 운영:** `COLLECTOR_ENABLED=false`, `ALERTS_ENABLED=false`. 3년 START_SCAN 적재가 있다(posting 36,879, 2026-11-19 기록).

## 2. 측정 원칙

| 구분 | 실제 | 합성·대역 | 쓰지 않음 |
|---|---|---|---|
| 대상 시스템 | 운영 VM, 공개 HTTPS, Caddy, 운영 DB의 실제 3년 데이터 | — | 개발 DB 수치로 대신하기 |
| 트래픽 | — | 하네스가 만드는 합성 요청(실사용자 아님, 읽기 전용 GET) | 실사용자 트래픽 수치를 부하 결과로 섞기 |
| 수집 | 실제 정기 수집 1회(수집을 켠 운영에서, 정상 호출 범위) | fixture API 대역을 쓰는 격리 복제본 | 측정을 위해 외부 API를 추가로 부르기 |
| 발송 | — | — | 실제 Resend·Discord(알림은 계속 꺼 둔다) |

- 결과마다 **분모**(측정 창 안의 요청 수, 워밍업 제외), **동시성**, **기간**, **수집 여부**, **이미지 SHA·digest**, **활성 색**, **데이터 건수**, **부하 발생지**를 함께 기록한다.
- `/jobs/{sn}/source`(외부 원문 302와 원문 이동 집계)는 부하 대상에서 뺀다. 외부 사이트를 호출하게 하거나 운영 집계를 오염시키기 때문이다. 하네스는 리다이렉트를 따라가지 않는다.

## 3. 성능 측정안

### 3.1 대상 URL 집합 (고정 파일)

| 묶음 | 내용(제안) | 만드는 법 |
|---|---|---|
| home | `/` | 고정 |
| list | `/jobs` 1쪽, 깊은 쪽(`page`를 끝 쪽 근처로) | 고정 |
| filter | `career=newcomer`, `region` 하나, `ncs` 하나, `region`+`ncs`+`hire` 조합, `q` 키워드 두 개, `sort` 변형 | `routes.md`의 허용 값 안에서 고정 |
| detail | `/jobs/{sn}` 50개 | 공개 `/jobs` 목록 쪽에서 정해진 seed로 뽑아 고정한다(DB 직접 조회 없음) |
| institution | `/institutions/{code}` 20개(공고가 많은 기관 포함) | 같은 방식 |

- 파일 `deploy/perf/urls.tsv`(묶음, 경로)로 커밋한다. 같은 목록을 다시 쓰면 같은 조건이 된다.
- 묶음마다 결과를 따로 집계하고, 섞어서 평균 내지 않는다.

### 3.2 하네스 (가장 작은 것)

`deploy/perf/load.py`: 표준 라이브러리만 쓴다(`http.client`, `threading`, `csv`). 새 의존성이 없다.
- 워커 N개가 각자 HTTPS 연결 하나를 **keep-alive**로 재사용하며 closed-loop로 요청한다. URL 목록을 묶음별 가중치로 순환한다.
  - curl을 요청마다 새로 띄우면 매번 TLS 핸드셰이크가 들어가 서버 성능보다 클라이언트 비용을 재게 된다. 그래서 keep-alive를 쓴다. G4 프로브의 요청당 연결 방식은 계약용이라 부하 하네스와 다르게 둔다.
- 요청마다 CSV 한 줄: `t_start_utc, worker, group, path, status, elapsed_ms, bytes, error`. 리다이렉트는 따라가지 않는다. 타임아웃은 10초(제안).
- 인자: `--concurrency`, `--warmup`, `--duration`, `--stop-file`. `SIGINT`나 stop 파일을 받으면 기록을 마치고 종료한다.
- **안전 중단(보호 장치, 목표 아님):** 다음 중 하나면 즉시 멈추고 `ABORTED_SAFETY`를 기록한다.
  - 직전 30초 오류 비율 2% 초과.
  - 5xx 1건이라도 연속 5초 이상.
  - 응답 시간 10초 초과가 5건.

`deploy/perf/summarize.py`(표준 라이브러리): 워밍업을 뺀 창으로 묶음별 집계를 낸다.
- 지표: 요청 수, 오류 수와 비율(분모 = 창 안 요청 수), p50·p95·p99·max, 처리량.
- p95는 정렬 뒤 nearest-rank로 계산한다. `g4-probe.sh`와 같은 정의라야 비교할 수 있다.

**부하 발생지:**
- 1순위: GitHub Actions `workflow_dispatch` 전용 워크플로(`perf.yml`). push에는 걸지 않는다. 장점은 외부이고 재현 가능하다는 것이다. 단점은 러너 지역이 바뀐다는 것이다(G4 기록에서 centralus·us-west-2가 나왔다). 그래서 매번 지역과 RTT를 기록한다.
- 대안: 사용자 PC(한국). 실제 사용자에 가깝다.
- 둘을 섞어 집계하지 않는다. VM 안에서는 걸지 않는다. 부하 발생기가 앱과 CPU를 다투면 자원 측정이 오염된다.

**서버측 대조:** 같은 창에서 기존 `http_server_requests_seconds_bucket{uri=…}`(30초 범위 버킷)로 `histogram_quantile(0.95, sum by (le, uri)(rate(…[창])))`을 구해 함께 적는다. 버킷 보간값이므로 ms 단위의 정밀값이 아니다. 클라이언트 원자료가 주 근거이고 서버값은 "네트워크를 뺀 앱 시간"의 보조다.

### 3.3 런 매트릭스 (최소)

| 런 | 부하 | 기간 | 수집 | 목적 |
|---|---|---|---|---|
| R0 | 없음 | 10분 | 없음 | 무부하 자원 기준선 |
| R1 | 동시성 1 | 워밍업 1분 + 5분 | 없음 | 지연 하한 |
| R2 | 동시성 4 | 1 + 5분, **두 번**(서로 다른 시각) | 없음 | 기준선과 반복 편차 |
| R3 | 동시성 8 | 1 + 5분 | 없음 | R2가 오류 0이고 자원 여유가 있을 때만 |
| R4 | 동시성 4 | 정기 수집 슬롯(매시 7분)을 사이에 둔 1 + 10분 | **실제 1회** 또는 대역 복제본 | 수집 중 지연·자원 |
| R5 | G4 프로브만 | 실제 배포 창 | 없음 | blue/green 겹침 자원(§4) |

- 동시성 1·4·8은 2 vCPU와 Hikari 풀 10을 생각한 시작 제안이다. 결과를 보고 조정한다.
- 운영 사용자가 적은 시각에 하고, 시작과 끝을 기록한다.

## 4. 자원 측정안

- **샘플러:** `deploy/perf/sample-resources.sh`(VM에서 ubuntu로 실행, 의존성 없음)가 5초마다 CSV 행을 남긴다.
  - `docker stats --no-stream`(컨테이너별 CPU%, 메모리 사용·한도, 네트워크, 블록 I/O).
  - `vmstat 1 2`의 둘째 줄, `free -m`, `df -B1 /`.
  - 시작과 끝에 `docker system df`, Postgres 볼륨 크기(`sudo du -sb`), 컨테이너 `RestartCount`.
  - 오버헤드는 작지만 0이 아니다. R0에서 샘플러만 돈 상태를 기준으로 삼는다.
- **세 상태:**
  1. **평상시:** R0(무부하)와 R1–R3(부하).
  2. **수집 중:**
     - 실제: 운영에서 수집을 켠 뒤의 정기 수집 1회(`ongoingYn=Y`, 1회 호출, 정상 운영 범위)를 R4 창에 넣는다. 수집을 켜는 시점은 운영 결정이다.
     - 대역: 격리 복제본(운영과 같은 이미지, 3년 덤프 복원본)에서 `RecruitmentClient`가 실제 응답 fixture를 주는 대역을 부르게 해 반복 측정한다. 앱 source를 바꾸지 않고 base URL 설정만 대역 서버로 돌릴 수 있는지는 확인해야 한다.
     - 둘을 섞어 쓰지 않는다. 3년 최초 적재(START_SCAN)는 다시 하지 않는다.
  3. **blue/green 겹침:** 실제 bluegreen 배포 동안 샘플러를 돌린다. 측정 구간은 새 색 기동부터 구 색 `stop -t 40` 종료까지다. 두 JVM(`-Xms256m -Xmx512m`) + Postgres + Caddy + 모니터링의 최고점을 잰다. §5 훈련 때도 같이 돌린다. 일부러 배포를 반복해 측정을 늘리지 않는다.
- **기록:** 상태별 CPU 평균·최고, 메모리 최고와 VM 전체 여유, 디스크 사용과 증가량. 여유 계산은 실측 값만 쓴다.

## 5. 실패 G4 → abort 훈련 후보

**방식(제안):**
1. 정상 이미지(예: 현재 main)로 `Deploy`의 `workflow_dispatch(action=deploy)`를 실행한다.
2. VM에서 미리 띄운 관찰 스크립트 `deploy/drill/g4-abort-drill.sh`가 다음을 기다린다: `deploy/state/pending_deploy_id`가 이번 id로 바뀌고, Caddy upstream이 새 색을 가리키고, 그 뒤 **5초**(제안, `post_switch_check`가 끝날 여유).
3. 그때 새 색 컨테이너를 `docker stop -t 0`으로 멈춘다.
   - `restart: unless-stopped`라서 `docker kill`(비정상 종료)은 자동 재시작될 수 있다. 수동 stop은 재시작되지 않는다.
4. 외부 프로브가 502를 기록한다 → `G4_FAIL` → 워크플로가 `deploy.sh abort --deploy-id`를 호출한다 → 아직 실행 중인 구 색 헬스 확인 → Caddy 복귀 → 새 색 정지, `pending_*` 정리.

**왜 이 경로가 G4 판정인가:**
- 내부 전환 전 점검과 `post_switch_check`를 통과한 뒤에 장애를 넣으므로, 실패를 잡는 것은 외부 G4다.
- 5초 안에 `post_switch_check`가 끝나지 않아 거기서 걸리면 `POST_SWITCH_FAIL` → abort 경로가 된다. 이것도 "실패 유도 abort"이지만, **어느 판정이 발동했는지를 기록에 따로 적는다.**

**관찰·기록:**
- Actions 로그의 FAIL 줄 시각(첫·마지막 실패), G4 JSON, `deploy.sh` 로그, abort 전후 `deploy.sh status`.
- 한국 측 보조 프로브(1초)로 사용자가 본 오류 구간.
- 샘플러 자원 기록.
- **예상 사용자 영향(코드 구조상):** 장애 시점부터 프로브 종료(전환 후 15초 창 끝)까지 + abort 실행 시간 동안 502다. 실제 길이는 측정으로만 적는다.

**안전과 범위:**
- 앱은 상태가 없고 DB는 건드리지 않는다. 수집과 알림은 꺼져 있어 중간에 끊겨도 발송·적재 부작용이 없다. 수집을 켠 뒤에 한다면 정기 수집 슬롯(매시 7분)을 피한다.
- 비밀값은 다루지 않는다(기존 워크플로 secrets를 그대로 쓴다).
- 사전 공지를 하고, 사용자가 적은 시각에 한다.

**실패하면(`ABORT_RESTORE_FAIL` 등):** runbook §3의 수동 2단계 복구로 구 색을 살린다. 이것도 포스트모템에 적는다.

**복원 검증:**
- `active_color`·`active_sha`가 훈련 전과 같다.
- `pending=none`, 새 색 정지.
- 외부 200 연속 5분.
- Prometheus `up` 정상.
- 그 뒤 같은 SHA로 정상 bluegreen을 다시 해, 배포 경로가 깨지지 않았음을 확인한다.

**기록 위치:** `docs/incidents/YYYY-MM-DD-g4-abort-drill.md`. 맨 위에 **"훈련"**이라고 적는다. 타임라인, 영향, 탐지·복구 시간, 원인(의도적 주입), 재발 방지 또는 개선점을 쓴다. CLAUDE.md대로 3자가 각자 쓰고 조율자가 종합한다.

**대안 후보(약함):**
- `POST_SWITCH_HOOK`로 실패시키기: 외부 G4가 아니라 내부 판정이다.
- 일부러 고장 낸 이미지: 앱 source 변경이 필요하고, 대부분 내부 점검에서 걸린다.
- 그래서 1순위로 두지 않는다.

**관찰한 개선 후보(이번에 정하지 않음):** 프로브가 첫 실패에서 바로 끝나 abort를 앞당기는 fail-fast는 사용자 노출 시간을 줄인다. 다만 G4 계약(창 길이, 최소 요청 수) 변경이라 별도 결정 사항이다.

## 6. 중단과 복원 검증 (성능 런 공통)

- **멈추는 방법:** 하네스는 stop 파일이나 `SIGINT`로 멈추고 CSV를 마무리한다. 안전 중단도 같은 경로다. 샘플러도 stop 파일로 멈춘다.
- **런 뒤 5분:** 외부 프로브 오류 0, 컨테이너 `RestartCount` 변화 없음, Hikari 활성 연결과 JVM 힙이 런 전 수준, 디스크 증가 기록(로그 포함).
- **경보:** HTTP 5xx 경보가 울릴 수 있다. 런 창을 알리고 기록한다. 경보를 끄지 않는다.
- **결과를 버리는 조건:** 런 중 실제 배포나 장애가 겹치면 그 런은 무효로 표시하고 다시 한다.

## 7. 저장소 원자료 경로 (제안)

```
deploy/perf/load.py, summarize.py, sample-resources.sh, urls.tsv
deploy/drill/g4-abort-drill.sh
.github/workflows/perf.yml          # workflow_dispatch 전용
docs/perf/YYYY-MM-DD-<run-id>/
  conditions.md                     # SHA·digest, 활성 색, 데이터 건수, 동시성, 기간, 발생지·지역·RTT, 수집 여부, 시작·끝 UTC
  requests.csv.gz                   # 요청별 원자료
  resources.csv.gz                  # 5초 샘플
  prometheus.json                   # 같은 창 서버측 쿼리 결과
  summary.md                        # summarize.py 출력, 실측/대역 구분 표시
docs/incidents/YYYY-MM-DD-g4-abort-drill.md
```

- 원자료에는 공개 URL과 숫자만 있다. 실사용자 IP·쿠키·토큰·이메일은 없다.
- 압축 원자료는 런당 수백 KB 이하로 예상한다(미측정). 커밋 크기 기준은 리뷰에서 정한다.

## 8. 남은 결정과 위험

1. 부하 발생지(Actions 러너, 사용자 PC, 둘 다)와 지역 고정 여부.
2. 동시성 단계와 안전 중단값(제안만 함).
3. 수집 중 측정을 "운영 수집을 켠 뒤 실제 1회"로 할지, "대역 복제본"으로 할지, 둘 다 할지. 수집을 켜는 일은 별도 운영 결정이다.
4. 훈련 일정과 사전 공지. 훈련으로 생기는 실제 502 노출을 받아들일지.
5. runbook 243행 "실패 유도 롤백(`G4_FAIL` 또는 `rollback`)" 문구를 PRD §7과 맞출지.
6. 위험:
   - 운영에 합성 부하를 거는 동안 실제 사용자 지연이 늘 수 있다(안전 중단으로 완화).
   - 러너 지역이 바뀌면 p95 비교가 흔들린다(조건 기록으로 완화).
   - 대역 복제본은 실제 VM과 자원 조건이 다를 수 있다. 복제본을 어디서 돌릴지에 따라 다르다.

## 9. 하지 않은 것

- 실측, 서버·계정·워크플로 실행, 스크립트 작성.
- 이번 라운드 다른 독립안 읽기.
- 목표 수치 설정.
