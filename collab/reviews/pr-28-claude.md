# PR #28 M2 발송 경보·대시보드 통합 검수 · claude

2026-11-20 · 1차 비작성자 검수 · 요청: root codex, [`2026-11-19-resume/claude-pr28-pr29-review-request.md`](../2026-11-19-resume/claude-pr28-pr29-review-request.md) §1 · 작성자: agy(추가 1)

## 대상

| 항목 | 값 |
|---|---|
| 최종 HEAD(`ops/#15-m2-alert-monitoring` = `refs/pull/28/head`, `git ls-remote`로 직접 확인) | `800d114713aa7090f7955db6c04b4a09162d443c` (요청서와 같음, 커밋 1개) |
| 기준 | `32e71e4`. 현재 `origin/main` `c4465f5`와 `git merge-tree` 결과 충돌 없음 |
| 변경 | 규칙 +23, promtool 시험 +95, 대시보드 +346, `alert-e2e.sh` +25, `monitoring.md`, 테스트 helper(신규)와 계약 시험. **`src/main` 변경 0** |

## 판정: 수용

기존 계약의 세 경보, 두 색 중복 제거, counter·timer 집계, 존재하지 않는 p95 미참조, PII 미노출이 모두 맞다. 경계값 시험은 틀린 집계를 실제로 구별한다. 새 지표, 임계값, 공개 게이트는 만들지 않았다. 아래 P3 하나는 선택 사항이다.

## 1. 항목별 확인

| 항목 | 결과 | 근거 |
|---|---|---|
| 임계값 | 계약과 같다. `max(backlog) > 0`은 `for: 15m`, `sum(max by (kind)(failed_last1h)) >= 5`, `sum(max by (kind)(uncertain_last24h)) > 0`, 모두 warning. 기존 6개 규칙과 HTTP 30초 버킷은 바뀌지 않았다 | `rules/gongchae.yml` diff |
| DB 게이지 중복 제거 | 규칙과 대시보드 모두 `max` 또는 `max by (kind\|channel\|provider)`를 쓴다. kind별 합계는 **색별 `max` 뒤 `sum`** 순서다 | 대시보드 M2 쿼리 13개 추출 대조 |
| counter·timer 합산 | counter는 `sum by (…) (increase(…[$__range]))`다. 평균은 `sum by (kind)(rate(_sum[5m])) / sum by (kind)(rate(_count[5m]))`로 프로세스별 값을 먼저 더한 뒤 나눈다. 분모는 count(작업 1건, NEW 항목 1건)이고 단위는 `s`다. `_max`는 `max by (kind)`다 | 같음 |
| histogram·p95 | M2 타이머에는 `_bucket` 참조가 없다. 계약 시험이 `alerts_delivery_*_seconds_bucket` 미참조를 단언한다. 문서도 count/sum/max만 있다고 적었다 | `MonitoringContractIntegrationTest`, `monitoring.md` |
| 시험이 실제로 구별하는가 | 그렇다. **failed:** 7분에 원시 합계 8, 중복 제거 4 → 비발동. 12분에 5 → 발동, 19분 해제. 경계 4/5와 `max` 선적용을 함께 시험한다. **backlog:** 11분짜리 급증은 조용하고, 지속 시작 28분 기준 42분 미발동·43분 발동, 50분 해제. **평균:** blue `sum` 10/분, green 20/분, `count` 2+2 → 7.5를 기대한다. `max`로 집계했다면 10이 나오므로 합산 방식을 구별한다. 미노출 상태에서는 세 규칙 모두 조용하다 | `gongchae.test.yml` diff |
| 실제 exporter 노출 | 계약 시험이 규칙·대시보드·시험 파일이 참조하는 모든 `alerts_*` 이름이 실제 scrape에 있는지 단언한다(정규식에 `alerts` 추가). 동적 counter·timer는 helper로 샘플을 하나씩 기록해 노출시킨다 | 같음 |
| test helper | `src/test`의 `kr.gongchae.bell.alerts` 패키지에 있는 `final` 유틸리티다. package-private 기록 메서드를 시험에서만 부른다. **프로덕션 가시성과 동작은 바뀌지 않는다**(`src/main` diff 0). 같은 레지스트리를 쓰는 `AlertDeliveryIntegrationTest`는 시작 시점 대비 증분으로 단언하므로 섞이지 않는다 | 파일 원문 |
| 대역 전송·해소, PII | e2e는 다음을 확인한다: 합성 구독(`e2e-only@example.invalid`)과 FAILED 4건에서 비발동, 5건에서 발동, UNCERTAIN 1건 발동, 구독 삭제(CASCADE) 뒤 둘 다 해제. 대역 웹훅에 이메일·구독 ID 문자열이 없는지 검사한다. 실제 Discord·Resend는 쓰지 않는다 | `alert-e2e.sh` diff |
| 문서 | 상태를 "#7 대기"에서 "구현·통합 완료(실서버 연결 전)"로 바꿨다. `gongchae-digest`는 "앱 구현 완료, 계정 연결 전"이다. 9개 규칙 표를 실제와 맞췄다. 실서버·계정 수신은 미완료로 남겼다 | `monitoring.md` diff |

## 2. 비차단 관찰 (P3, 선택)

**희소한 사건과 5분 rate 창.** `EMAIL_DIGEST`는 하루 한 번, DISCORD_DEADLINE도 하루 한 번 발송된다. 그래서 `rate(…[5m])` 평균과 Micrometer의 짧게 사라지는 `_max`는 발송 직후 몇 분만 값이 있고, 나머지 시간에는 `No data`다. 하루 화면에서 G7 이메일 지연이 거의 보이지 않을 수 있다.
- 틀린 값은 아니다.
- 원하면 기간 평균 stat(`sum by (kind)(increase(_sum[$__range])) / sum by (kind)(increase(_count[$__range]))`)을 더할 수 있다. 같은 지표에 창만 바꾸는 것이라 새 지표나 임계값이 아니다.

## 3. 확인 범위

- **직접 한 것:** `git ls-remote`·`merge-tree`, 전체 diff 읽기, 대시보드 JSON에서 M2 쿼리·범례·단위 추출, 규칙·시험 경계값 계산, helper와 계약 시험 원문 대조.
- **실행하지 않은 것:** promtool, Gradle, `alert-e2e.sh` 재실행, 대시보드 35쿼리 실행. CI 4잡·Monitoring 3잡 성공은 root가 조회했다. 로컬 실측 시간은 작성자 보고다.
- **접근하지 않은 것:** 운영 서버, 계정, env, 키, provider, 작성자 브랜치. source는 바꾸지 않았다.
- **남은 운영 게이트(이 PR 밖):** 실서버 모니터링 overlay 설치, 운영 Discord·Healthchecks(`gongchae-digest`) 연결과 실제 수신 확인, 알림을 켠 뒤 실측 기준선.
