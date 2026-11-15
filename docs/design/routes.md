# 라우트와 외부 연동

## 화면 (GET은 조회만 한다)
| 경로 | 설명 |
|---|---|
| `GET /` | 홈 |
| `GET /jobs?ncs=&region=&hire=&career=&status=&q=&sort=&page=` | 목록. 허용 목록 밖의 값은 무시한다. `career`: `newcomer`(R2010+R2030), `newonly`(R2010), `career` |
| `GET /jobs/{sn}` | 상세. 없는 공고는 404 |
| `GET /jobs/{sn}/source` | 원문으로 이동하고 서버 측에서 집계한다. **저장된 링크로만** 이동한다(임의 URL 인자 없음 → 오픈 리다이렉트 방지) |
| `GET /institutions/{code}` | 기관 (최근 3년) |
| `GET /privacy` | 개인정보처리방침 |
| `GET /robots.txt`, (Should) `GET /sitemap.xml` | 검색엔진용 |

## 구독 (상태 변경은 POST로만, 모든 POST는 Origin 검사)
| 경로 | 설명 |
|---|---|
| `GET /subscriptions/new?{필터}` | 현재 필터를 채운 신청 폼 (이메일 / 디스코드 탭) |
| `POST /subscriptions/email` | 고지 동의와 14세 확인을 받는다 → `PENDING` + `EMAIL_CONFIRM` 작업. **이미 구독 중이어도 같은 응답**을 돌려준다 |
| `GET /subscriptions/confirm?t=` → `POST /subscriptions/confirm` | 확인 화면 → 확인 (1회용 토큰, 만료 있음) |
| `POST /subscriptions/discord` | URL 검증([ADR-0009](../adr/0009-webhook-storage-ssrf.md)) → 테스트 메시지(`wait=true`) 성공 → `ACTIVE` → 완료 화면에 관리 링크를 한 번 보여 준다 |
| `GET /subscriptions/manage?t=` → `POST /subscriptions/manage` | 조건과 알림 종류 수정 |
| `GET /subscriptions/unsubscribe?t=` → `POST /subscriptions/unsubscribe` | 해지 확인 → 해지 (즉시 파기) |
| `POST /subscriptions/unsubscribe/one-click?t=` | (Should) RFC 8058 원클릭 해지 |
| `GET /subscriptions/recover` | 관리 링크 다시 받기 화면 |
| `POST /subscriptions/recover/email` | 등록된 이메일이면 관리 링크를 보낸다. 응답은 항상 같다 |
| `POST /subscriptions/recover/discord` | 웹훅 URL을 다시 입력 → `dest_hmac`이 일치하면 관리 링크를 한 번 보여 준다 |

- 토큰([ADR-0008](../adr/0008-tokens.md)): 확인 = 무작위 + 해시. 관리와 해지 = HMAC(서버 키, 목적, 구독 ID, `token_version`)
- 토큰이 들어간 페이지: `Referrer-Policy: no-referrer`, `Cache-Control: no-store`, `X-Robots-Tag: noindex`. Caddy와 앱 로그에서 `t` 파라미터를 지운다
- 요청 제한([ADR-0010](../adr/0010-rate-limit-and-budget.md)): 신청, 재발송, 웹훅 테스트, 토큰 실패를 IP 기준과 대상 기준으로 제한한다

## 운영 (공개 안 함)
| 경로 | 접근 |
|---|---|
| 관리 포트 `:8081/actuator/health/readiness`, `/actuator/prometheus` | Docker 내부망(Prometheus, deploy.sh)만 |
| Grafana `:3000` | SSH 터널만 |
| 최초 적재, 재수집, 대장 재적용 | 서버에서 명령으로만 실행 (공개 운영 API 없음) |

## 외부 API
| 연동 | 요청 | 주기와 호출량 |
|---|---|---|
| 채용 `list` 최초 적재 | `serviceKey`, `resultType=json`, `pbancBgngYmd`(YYYY-MM-DD, 목표 시작일), `numOfRows=1000`, `pageNo`. **`pbancEndYmd`는 보내지 않는다**(아래 주) | 1회. `ceil(totalCount/1000)`회로 끝 페이지까지 읽는다. 2026-11-15 `pbancBgngYmd=2023-11-15` 1페이지의 totalCount는 36,772이므로 37회다. 시작일이 목표 종료일 이후인 행은 받되 반영하지 않는다. raw 수신과 반영 집합을 `collect_run`에 따로 기록한다 |
| 채용 `list` 정기 | `ongoingYn=Y`, `numOfRows=1000`, `pageNo=1` | 매시 7분, 하루 24회. `totalCount` ≠ 받은 건수면 INCOMPLETE (추가 페이지를 자동으로 호출하지 않는다) |
| 채용 `detail` | `sn` | 새 진행 공고만 (Should) |
| 호출 예산 | 모든 시도를 `api_budget`에 먼저 반영 | 500 경고, 900 자동 중단 |
| 디스코드 웹훅 | `POST /api/webhooks/{id}/{token}?wait=true`, `allowed_mentions: {"parse": []}` | 새 공고: 수집 회차마다 / 마감 임박: 매일 09:00. 404 → 비활성, 429 → `retry_after` 대기 |
| Resend | `POST https://api.resend.com/emails`, `Idempotency-Key` 헤더([공식](https://resend.com/docs/dashboard/emails/idempotency-keys)) | 확인 메일 + 매일 08:00 묶음. 무료 한도는 하루 100, 월 3,000이다([공식](https://resend.com/pricing)) → 전체 발송 상한을 그 아래로 둔다 |
| Healthchecks.io | 성공 시 GET 핑 | 수집, 묶음, 백업, NAS 가져오기 |

**최초 적재 날짜 조건 (2026-11-15 실측)**: [공식 명세](https://www.data.go.kr/data/15125273/openapi.do)는 `pbancBgngYmd`·`pbancEndYmd`를 모두 선택 항목이자 "채용공시 시작일 기준" 조회 범위로 적는다. 실제 응답은 `pbancEndYmd`를 **마감일 상한**처럼 적용했다. 월별로 `pbancEndYmd=월말`을 보낸 37개월 22,733건에는 시작월과 마감월이 다른 공고가 0건이었다. `pbancBgngYmd=2023-11-15&pbancEndYmd=2026-11-15` 1페이지는 totalCount 36,277에 2026-11-15 이후 마감이 0건이었다. `pbancEndYmd`를 뺀 1페이지는 totalCount 36,772에 그런 공고가 381건, 오늘 이후 시작 공고가 4건이었다(agy 검증, 영속 예산 2회. 기록은 root 작업 경로의 `collab/2026-11-15-backfill/agy-query-contract.md`). 그래서 최초 적재는 시작일 하한만 보내 전체를 끝까지 읽고, 시작일 상한은 앱이 적용한다. 서버 쪽 동작의 원인은 확인하지 않았다. 실제 1페이지 본문은 [`fixtures/alio-official-list-lower-bound-sample.json`](../../fixtures/README.md)이다.

`list` 응답에서 쓰는 필드와 저장 규칙은 [erd.md 공고 응답 매핑](erd.md#공고-응답-매핑-3-계약)에 있다. 응답을 반영하는 메서드 계약(완결성, 실패 코드, 최초 적재 페이지 실패 처리)은 [수집 반영 계약](../../collab/2026-11-15-implementation/claude-ingestion-contract.md)이다.
