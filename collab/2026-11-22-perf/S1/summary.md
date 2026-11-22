# 성능 측정 요약 (#18)

- 상태: **COMPLETED**
- 시작/종료(UTC): `2026-11-22T08:16:55.457Z` ~ `2026-11-22T08:17:10.938Z` (총 부하 15.49s = 워밍업 1.97s + 측정 13.52s)
- 방식: closed-loop keep-alive (`concurrency=1`, `reconnect_count=0`, `reused_conn=200/200`)
- 워밍업(측정 분모 제외, 안전 중단 동일 적용): `requests=25`, `errors=0/0/0/0` (5xx/non200/to/conn)
- 주의: closed-loop 동시성 응답 시간이며 포화 상태 open-loop 도착률 성능이 아니다. 타임아웃 경과 시간은 소켓 연결·읽기 1회 대기 상한(`--timeout`)에서 잘린 관측이며 응답 전체 경과 상한이 아니다.

## 화면군별 집계 (measure 단계, nearest-rank)

| 화면군 | n_total | n_ok | 오류율 | n>=20 | 성공 p50 | 성공 p95 | 성공 max | 전체 p95 (오류 시) | 오류 내역 (5xx/non200/to/conn) |
|---|---:|---:|---:|---|---:|---:|---:|---:|---|
| `home` | 40 | 40 | 0.00% | Y | 47.0ms | 63.0ms | 63.0ms | - | 0/0/0/0 |
| `list` | 40 | 40 | 0.00% | Y | 62.0ms | 63.0ms | 63.0ms | - | 0/0/0/0 |
| `filter` | 40 | 40 | 0.00% | Y | 63.0ms | 281.0ms | 296.0ms | - | 0/0/0/0 |
| `detail` | 40 | 40 | 0.00% | Y | 47.0ms | 63.0ms | 63.0ms | - | 0/0/0/0 |
| `institution` | 40 | 40 | 0.00% | Y | 63.0ms | 79.0ms | 79.0ms | - | 0/0/0/0 |

## 케이스별 집계

| 화면군 | case_id | n_total | n_ok | 오류율 | 성공 p95 | 성공 max |
|---|---|---:|---:|---:|---:|---:|
| `home` | `home_root` | 40 | 40 | 0.00% | 63.0ms | 63.0ms |
| `list` | `jobs_default` | 40 | 40 | 0.00% | 63.0ms | 63.0ms |
| `filter` | `career_newcomer` | 8 | 8 | 0.00% | n=8, max=94.0ms (n<20) | 94.0ms |
| `filter` | `region_ncs_combo` | 8 | 8 | 0.00% | n=8, max=78.0ms (n<20) | 78.0ms |
| `filter` | `keyword_all` | 8 | 8 | 0.00% | n=8, max=296.0ms (n<20) | 296.0ms |
| `filter` | `deep_page_all` | 8 | 8 | 0.00% | n=8, max=109.0ms (n<20) | 109.0ms |
| `filter` | `sort_begin` | 8 | 8 | 0.00% | n=8, max=63.0ms (n<20) | 63.0ms |
| `detail` | `detail_1` | 8 | 8 | 0.00% | n=8, max=62.0ms (n<20) | 62.0ms |
| `detail` | `detail_2` | 8 | 8 | 0.00% | n=8, max=63.0ms (n<20) | 63.0ms |
| `detail` | `detail_3` | 8 | 8 | 0.00% | n=8, max=63.0ms (n<20) | 63.0ms |
| `detail` | `detail_4` | 8 | 8 | 0.00% | n=8, max=63.0ms (n<20) | 63.0ms |
| `detail` | `detail_5` | 8 | 8 | 0.00% | n=8, max=63.0ms (n<20) | 63.0ms |
| `institution` | `inst_1` | 8 | 8 | 0.00% | n=8, max=63.0ms (n<20) | 63.0ms |
| `institution` | `inst_2` | 8 | 8 | 0.00% | n=8, max=79.0ms (n<20) | 79.0ms |
| `institution` | `inst_3` | 8 | 8 | 0.00% | n=8, max=63.0ms (n<20) | 63.0ms |
| `institution` | `inst_4` | 8 | 8 | 0.00% | n=8, max=63.0ms (n<20) | 63.0ms |
| `institution` | `inst_5` | 8 | 8 | 0.00% | n=8, max=79.0ms (n<20) | 79.0ms |
