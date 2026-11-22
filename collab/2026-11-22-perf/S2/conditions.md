# 성능 측정 조건 (#18)

| 항목 | 값 |
|---|---|
| 실행 상태 | `COMPLETED` |
| 중단 사유 | `none` |
| 대상 Base URL | `https://gongchae.enjoylcjworld.com` |
| 시작 / 종료 (UTC) | `2026-11-22T08:17:38.815Z` / `2026-11-22T08:18:08.256Z` |
| 프로파일 / 동시성 | `S2` / `C=4` |
| 시간 / 요청 상한 | `total_load_duration=120.0s (warmup+measure)`, `max_requests=1500`, `per_group_requests=None` |
| 소켓 타임아웃 / 워밍업 | `socket_timeout=10.0s (per connect/read, not total response cap)`, `warmup_rounds=1` |
| 고정 헤더 | `X-Gongchae-Internal: 1`, `User-Agent: gongchae-probe/perf-18` |
| 사전 검증 RTT (min/med/max) | `46.0 / 63.0 / 328.0 ms` |
| 클라이언트 위치 | `운영자 PC(한국 가정망, Windows Python 3.12)` |
| 앱 SHA / 이미지 digest | `e0208ffd16c39b511cc0065dc8ee40e5c8b4f9c1` / `sha256:5f80098b29b51fe6e5f759241a66e6532a645b8690a81af417355d4cb2d264f9` |
| 활성 색 | `green` |
| 데이터 건수 / 플래그 | `posting=36879`, `COLLECTOR_ENABLED=false`, `ALERTS_ENABLED=false` |

## 고정 URL 목록

| group | case_id | path |
|---|---|---|
| `home` | `home_root` | `/` |
| `list` | `jobs_default` | `/jobs` |
| `filter` | `career_newcomer` | `/jobs?career=newcomer` |
| `filter` | `region_ncs_combo` | `/jobs?region=R3018&ncs=R600014&status=open` |
| `filter` | `keyword_all` | `/jobs?q=채용&status=all` |
| `filter` | `deep_page_all` | `/jobs?status=all&page=10` |
| `filter` | `sort_begin` | `/jobs?sort=begin` |
| `detail` | `detail_1` | `/jobs/306769` |
| `detail` | `detail_2` | `/jobs/306805` |
| `detail` | `detail_3` | `/jobs/306740` |
| `detail` | `detail_4` | `/jobs/306783` |
| `detail` | `detail_5` | `/jobs/306791` |
| `institution` | `inst_1` | `/institutions/C0066` |
| `institution` | `inst_2` | `/institutions/C0059` |
| `institution` | `inst_3` | `/institutions/C1055` |
| `institution` | `inst_4` | `/institutions/C0210` |
| `institution` | `inst_5` | `/institutions/C0306` |
