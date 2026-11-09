# 설계 3자 라운드 · 조율자(claude) 종합

> 입력: [01-claude](01-claude.md) · [01-codex](01-codex.md) · [01-agy](01-agy.md) · [02-claude](02-claude-review.md) · [02-codex](02-codex-review.md) · [02-agy](02-agy-review.md)
> 산출물: `docs/design/*.md`, `docs/adr/*.md` (사용자 결정 3개 뒤 작성)

## 1. 합의 (셋 다 같은 결론)
| 주제 | 결정 |
|---|---|
| Java | **21** (claude 25 철회) |
| 앱 구조 | Spring Boot 단일 앱, 기능별 패키지. 별도 워커, Redis, 큐 없음 |
| 무중단 배포 | 새 색 기동 → 내부 readiness → **Caddy upstream 명시 전환 + reload** → G4 외부 시험 → 옛 색 SIGTERM. 실패하면 옛 색 유지 (claude의 헬스체크 방식 철회) |
| 관측 | Prometheus + Grafana(내부 전용, Grafana 경보 → 운영 디스코드) + Healthchecks.io + UptimeRobot. **node-exporter 없음**, 호스트 자원은 `docker stats`로 실측 기록 |
| 다중값 코드 | `posting_code` 조인 테이블 (codex 배열안, agy 문자열안 철회) |
| 웹훅 저장 | JDK AES-256-GCM(매번 새 nonce, key_id) + 중복 판별용 HMAC. 키는 환경변수 (DB, 백업과 분리) |
| 발송 선점 | `FOR UPDATE SKIP LOCKED`. 해지와 수정은 같은 구독 행 잠금으로 직렬화. 오래 멈춘 `SENDING`은 회수하고 "결과 불명확"으로 센다 |
| 빼는 것 | AWS SDK, Brevo 콜백(비동기 반송은 MVP 밖), ShedLock, Bucket4j, Caffeine, node-exporter, 이메일 주소 암호화, `daily_page_count`, `collect_stage`, 컨테이너별 고정 메모리 한도(실측 전) |
| PRD 정정 | **FR-A5**: 수신자 단위의 영구 거부(디스코드 404, 이메일 수신 주소 거부)만 비활성화한다. 공급자 5xx와 연결 오류는 재시도만 한다 (codex와 claude 제안, agy 이견 없음) |

## 2. 갈린 기술 선택 → 조율자 결정
기술 선택은 사용자에게 묻지 않는다는 원칙(codex, agy 공통)에 따라 조율자가 근거를 달아 정한다.

| 주제 | 입장 | 결정과 근거 |
|---|---|---|
| 스케줄 중복 방지 | claude: 세션 advisory lock / codex: ShedLock + 회차 유니크 / agy: advisory xact lock | **회차 유니크 INSERT**: `collect_run(kind, slot)` 유니크에 `INSERT ... ON CONFLICT DO NOTHING`. 행을 넣은 쪽만 실행한다(정기 = 매시 슬롯, 이메일 묶음 = 날짜). 잠금도 의존성도 없다. 실행 중 죽으면 그 회차만 비고, 다음 회차가 이어 받는다. codex가 지적한 xact lock의 함정과 ShedLock 시간 초과 문제가 둘 다 없다 |
| 요청 제한 | claude: IP는 메모리 / codex, agy: DB 공유 카운터 | **DB 카운터 테이블 하나** (`rate_limit(scope, key_hmac, window_start)` 조건부 증가). 두 앱과 재시작에 걸쳐 합산된다. 2:1 다수 의견을 따르고, 테이블 하나라 비용도 작다 |
| 관리·해지 토큰 | claude: 해시 저장 + 재발급 시 교체 / codex: 서버 키로 재생성(PRF) | **codex 방식.** 매일 메일마다 해지 링크를 넣어야 하는데 해시만 저장하면 원문을 다시 만들 수 없다. 토큰 = HMAC(서버 키, 목적, 구독 ID, 버전). 저장하는 건 버전 숫자 하나다. 버전을 올리면 기존 링크가 모두 무효가 된다. **확인 토큰**만 무작위 값 + 해시 + 만료 + 1회 사용으로 둔다 |
| CSRF·Spring Security | codex, agy: 넣는다 / claude: 뺀다 | **넣지 않는다.** 로그인 세션이 없어서 CSRF로 위조할 권한이 없다. 상태 변경은 폼 안의 토큰이 권한이다. 보안 헤더는 Caddy가 붙이고, 상태 변경 POST에는 `Origin` 헤더 검사(필터 몇 줄)만 둔다. 나중에 로그인 기능을 넣을 때 다시 검토한다 |
| SSRF 실제 연결 IP 검사 | codex: HttpClient5 + DnsResolver / claude: JDK HttpClient + 사전 조회 | **JDK HttpClient(`Redirect.NEVER`) + 정확한 호스트 허용 목록 + 전송 전 `InetAddress`로 사설·루프백·링크로컬(IPv4/IPv6) 거부.** 허용 호스트가 디스코드 도메인으로 고정돼 있어서, 조회 시점과 연결 시점 사이에 결과가 바뀌는 위험은 디스코드 DNS를 공격자가 통제할 때만 생긴다. 남는 위험으로 ADR에 적는다 |
| 이메일 연동 방식 | claude: SMTP / codex, agy: HTTP API | **선택한 공급자의 HTTP API를 JDK HttpClient로** 호출한다(SDK 없음). Resend를 쓰면 멱등 키(24시간)로 중단 후 재시도 때 중복을 줄일 수 있다 |
| 백업 도구 | claude, codex: restic / agy: rclone + 암호화 | **restic.** 암호화, 보관 정리, S3·SFTP 백엔드를 도구 하나로 처리한다 |
| 호출 예산 | agy: 날짜당 카운터 / codex: 요청 전 예약 + 시도별 기록 | **날짜당 카운터를 요청 직전에 별도 커밋으로 증가**(예약)한다. 시도별 상세는 로그와 지표(Prometheus)로 본다 |
| 만회 정책 | codex 제안 | **채택**: 놓친 일일 실행은 당일 한 번 만회한다. 마감 임박은 실행일 기준 D-3만, 이미 지난 공고는 제외한다. 마감 연장 시 재알림은 하지 않는다 |
| 목록 설명 필드 | codex 지적 | 자격, 우대, 전형 설명은 **공고 본체(`posting`)에 저장**한다 (상세 API 없이도 상세 화면이 나오게) |

## 3. 사용자 결정 (2026-11-08 결정 완료: Resend · NAS·PC(pull) · 호스트 파일 대장 → PRD U10~U12, ADR-0007·0012·0013)
1. **이메일 발송 서비스**: Resend(codex, agy 권고 / 하루 100·월 3,000 / 미국 저장 / 멱등 키) vs Brevo(하루 300 / EU 저장 / 무료 플랜 브랜딩 / 로그 기본 무기한). OCI Email Delivery는 무료 한도 0 충돌과 발송 리전 문제로 제외(claude 철회)
2. **OCI 밖 백업 위치와 비용 허용**: Cloudflare R2(10GB 무료, **결제수단 등록 필요**, 초과 시 과금) / Backblaze B2(10GB 무료) / 사용자 NAS·PC(SFTP)
3. **복원 후 해지 유지 정책**: (a) OCI 밖 파기 대장 (b) 호스트 파일 대장 (DB 장애만 막음) (c) 재해 복구 시 모든 구독을 폐기하고 재등록을 받음

**배포 전 입력** (결정 아님): 도메인 이름과 DNS 권한, 운영 디스코드 웹훅, **개인정보 담당 연락처**(처리방침 필수 항목, codex 지적). 비밀값은 채팅에 적지 않고 Secrets로 전달한다.

## 4. 에이전트별 기록
- **codex**: 정합성(토큰 재생성의 필요성, 수신자·공급자 오류 구분, 잠금 순서, 만회 정책)에서 가장 기여했다. 01의 과설계(lease·fence, 파기 대장 강제, 콜백)를 스스로 철회했다
- **agy**: 01의 의존성 3개를 철회했다. 이번에는 미확인 주장을 미확인으로 표시했다(R2 결제수단). "Brevo 로고 강제"는 codex가 공식 근거를 확인했다
- **claude**: Java 25, 헬스체크 전환, node-exporter, OCI 이메일 1순위("한국 리전이면 국외 이전 없음"은 근거 부족), UptimeRobot을 "비상업 전용"으로 본 것을 철회했다
