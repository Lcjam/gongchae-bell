# 성능 측정 요약 (#18)

- 상태: **COMPLETED**
- 시작/종료(UTC): `2026-11-22T08:17:38.815Z` ~ `2026-11-22T08:18:08.256Z` (총 부하 29.44s = 워밍업 2.34s + 측정 27.09s)
- 방식: closed-loop keep-alive (`concurrency=4`, `reconnect_count=0`, `reused_conn=1500/1500`)
- 워밍업(측정 분모 제외, 안전 중단 동일 적용): `requests=100`, `errors=0/0/0/0` (5xx/non200/to/conn)
- 주의: closed-loop 동시성 응답 시간이며 포화 상태 open-loop 도착률 성능이 아니다. 타임아웃 경과 시간은 소켓 연결·읽기 1회 대기 상한(`--timeout`)에서 잘린 관측이며 응답 전체 경과 상한이 아니다.

## 화면군별 집계 (measure 단계, nearest-rank)

| 화면군 | n_total | n_ok | 오류율 | n>=20 | 성공 p50 | 성공 p95 | 성공 max | 전체 p95 (오류 시) | 오류 내역 (5xx/non200/to/conn) |
|---|---:|---:|---:|---|---:|---:|---:|---:|---|
| `home` | 300 | 300 | 0.00% | Y | 47.0ms | 63.0ms | 94.0ms | - | 0/0/0/0 |
| `list` | 300 | 300 | 0.00% | Y | 62.0ms | 79.0ms | 125.0ms | - | 0/0/0/0 |
| `filter` | 300 | 300 | 0.00% | Y | 78.0ms | 344.0ms | 437.0ms | - | 0/0/0/0 |
| `detail` | 300 | 300 | 0.00% | Y | 47.0ms | 63.0ms | 79.0ms | - | 0/0/0/0 |
| `institution` | 300 | 300 | 0.00% | Y | 63.0ms | 79.0ms | 110.0ms | - | 0/0/0/0 |

## 케이스별 집계

| 화면군 | case_id | n_total | n_ok | 오류율 | 성공 p95 | 성공 max |
|---|---|---:|---:|---:|---:|---:|
| `home` | `home_root` | 300 | 300 | 0.00% | 63.0ms | 94.0ms |
| `list` | `jobs_default` | 300 | 300 | 0.00% | 79.0ms | 125.0ms |
| `filter` | `career_newcomer` | 60 | 60 | 0.00% | 78.0ms | 94.0ms |
| `filter` | `region_ncs_combo` | 60 | 60 | 0.00% | 78.0ms | 94.0ms |
| `filter` | `keyword_all` | 60 | 60 | 0.00% | 391.0ms | 437.0ms |
| `filter` | `deep_page_all` | 60 | 60 | 0.00% | 156.0ms | 172.0ms |
| `filter` | `sort_begin` | 60 | 60 | 0.00% | 94.0ms | 94.0ms |
| `detail` | `detail_1` | 60 | 60 | 0.00% | 63.0ms | 63.0ms |
| `detail` | `detail_2` | 60 | 60 | 0.00% | 63.0ms | 63.0ms |
| `detail` | `detail_3` | 60 | 60 | 0.00% | 63.0ms | 78.0ms |
| `detail` | `detail_4` | 60 | 60 | 0.00% | 63.0ms | 78.0ms |
| `detail` | `detail_5` | 60 | 60 | 0.00% | 63.0ms | 79.0ms |
| `institution` | `inst_1` | 60 | 60 | 0.00% | 63.0ms | 79.0ms |
| `institution` | `inst_2` | 60 | 60 | 0.00% | 94.0ms | 110.0ms |
| `institution` | `inst_3` | 60 | 60 | 0.00% | 78.0ms | 78.0ms |
| `institution` | `inst_4` | 60 | 60 | 0.00% | 78.0ms | 79.0ms |
| `institution` | `inst_5` | 60 | 60 | 0.00% | 94.0ms | 110.0ms |
