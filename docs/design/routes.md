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
| 채용 `list` 최초 적재 | `serviceKey`, `resultType=json`, `pbancBgngYmd`/`pbancEndYmd`(YYYY-MM-DD, 월 단위), `numOfRows=1000`, `pageNo` | 1회. 3년치 약 36회(추정). 실제 건수와 페이지를 `collect_run`에 기록. 완료된 달은 다시 실행할 때 건너뛴다 |
| 채용 `list` 정기 | `ongoingYn=Y`, `numOfRows=1000`, `pageNo=1` | 매시 7분, 하루 24회. `totalCount` ≠ 받은 건수면 INCOMPLETE (추가 페이지를 자동으로 호출하지 않는다) |
| 채용 `detail` | `sn` | 새 진행 공고만 (Should) |
| 호출 예산 | 모든 시도를 `api_budget`에 먼저 반영 | 500 경고, 900 자동 중단 |
| 디스코드 웹훅 | `POST /api/webhooks/{id}/{token}?wait=true`, `allowed_mentions: {"parse": []}` | 새 공고: 수집 회차마다 / 마감 임박: 매일 09:00. 404 → 비활성, 429 → `retry_after` 대기 |
| Resend | `POST https://api.resend.com/emails`, `Idempotency-Key` 헤더([공식](https://resend.com/docs/dashboard/emails/idempotency-keys)) | 확인 메일 + 매일 08:00 묶음. 무료 한도는 하루 100, 월 3,000이다([공식](https://resend.com/pricing)) → 전체 발송 상한을 그 아래로 둔다 |
| Healthchecks.io | 성공 시 GET 핑 | 수집, 묶음, 백업, NAS 가져오기 |
