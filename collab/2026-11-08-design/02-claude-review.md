# 설계 2라운드 · claude 교차 비판

> 01-codex와 01-agy를 읽고 썼다. 판단 기준은 **PRD 수용 기준을 충족하는 가장 작은 구성**이다.

## 1. 사실과 미검증 주장
| 주장 | 누구 | 판정 |
|---|---|---|
| "Brevo 무료 플랜은 'Sent with Brevo' 로고가 강제된다" | agy | **미확인.** 마케팅 캠페인에 해당하는지 트랜잭션 메일에도 해당하는지 출처가 없다. 공식 도움말에서 확인 필요 |
| "AWS SES는 무료 티어가 없다" | agy | **미확인.** 출처가 없다. 이번 결정에 영향은 없다 |
| Brevo 무료 일 300회, 저장소 EU, 로그 삭제 최대 72시간 | codex | **공식 도움말 인용.** 신뢰한다. 파기 고지에 "수탁자 쪽 삭제 지연"을 넣어야 한다는 지적이 맞다 |
| Resend 월 3,000·일 100, 미국 저장 | codex, agy | 공식 가격 페이지 인용으로 일치한다 |
| Caddy reload가 실패하면 기존 설정을 유지한다 | codex | 공식 API 문서 인용. upstream 명시 전환 방식의 근거가 된다 |
| 요청 인자명 `pageNo`, `serviceKey`가 03에 명시돼 있지 않다 | codex | 03 swagger 표와 실호출(`serviceKey`, `pageNo`, `numOfRows`, `ongoingYn`)로 **이미 확인했다.** 03의 실호출 기록에 있다 |

## 2. ADR별 결정 비교
| 주제 | claude | codex | agy | 권고 |
|---|---|---|---|---|
| Java | 25 | 21 | 21 | **21.** 25 고유 기능이 필요 없다. 내 안을 철회한다 |
| 동시 실행 방지 | advisory lock + SKIP LOCKED | job_lease + fence + SKIP LOCKED | ShedLock + 조건부 UPDATE | **세션 advisory lock**(`pg_try_advisory_lock`) + 발송은 `SKIP LOCKED`. 앱이 죽으면 연결이 끊겨 잠금이 자동으로 풀린다. 같은 호스트의 두 앱이라 fence가 필요한 분할 상황은 사실상 없다. 의존성 0 |
| 무중단 전환 | 두 upstream + 헬스체크 + 재시도 | upstream 명시 전환 + reload | upstream 명시 전환 + reload | **명시 전환 + `caddy reload`.** 결과가 결정적이고 실패하면 기존 설정이 유지된다. 내 안을 철회한다 |
| 관측 | Prom + Grafana + node-exporter | Prom + Grafana, exporter 없음 | Prom + Grafana | **Prom + Grafana, exporter 없음.** 호스트 자원은 정해진 시점에 `docker stats`로 실측해 기록한다. node-exporter를 철회한다 |
| 방문 통계 장기 보존 | (없음) | `daily_page_count` 테이블 | (없음) | **Prometheus 보존 기간을 늘린다**(라벨이 적어 디스크 부담이 작다). 테이블을 새로 만들지 않는다 |
| 이메일 발송 | SMTP 추상화, OCI 1순위 → Brevo | Brevo HTTP API + 콜백 | Resend API | **SMTP(`spring-boot-starter-mail`)로 구현한다.** OCI, Brevo, Resend 모두 SMTP를 지원해서 설정만 바꾸면 된다. 서비스 선택은 **사용자 결정**(아래 §6). 콜백(비동기 반송)은 PRD상 MVP 밖이다 |
| 이메일 주소 암호화 | 평문 | AES-GCM + HMAC 조회 | 평문 | **평문.** PRD에 없는 요구다. DB는 외부에 열지 않고 백업은 restic으로 암호화된다 |
| 관리 토큰 재발급 | 해시 저장 | PRF로 재생성 + 세대 | 해시 저장 | **해시 저장 + 재발급할 때 새 토큰으로 교체.** 비밀번호 재설정과 같은 패턴이라 PRF가 필요 없다. 모든 메일에 넣는 **해지 전용 토큰**은 따로 두고 만료시키지 않는다 |
| 요청 제한 | IP는 메모리, 대상은 DB 컬럼 | DB 버킷 테이블 | Bucket4j + Caffeine | **IP는 메모리 고정 윈도(의존성 없이), 대상은 기존 컬럼**(`confirm_sent_count`, `confirm_sent_at`). 새 테이블과 라이브러리를 넣지 않는다 |
| 호출 예산 | `api_call` 행 단위 | 행 단위 | `api_call_log` **날짜당 1행 카운터** | **agy 안.** 날짜당 1행을 원자적으로 증가시키면 500/900 판정에 충분하다. 호출 상세는 로그와 지표로 본다 |
| 다중값 코드 | 조인 테이블 | 배열 + GIN | 쉼표 문자열 | **조인 테이블**(표준 JPA로 필터). 쉼표 문자열은 LIKE 검색이라 정확하지 않다 |
| SSRF 구현 | (미정) | HttpClient5 + 사용자 정의 DnsResolver | 정규식 + 리다이렉트 금지 | **JDK HttpClient(`Redirect.NEVER`) + 정확한 호스트 허용 목록 + 전송 전 `InetAddress`로 사설 대역 거부.** 허용 호스트가 디스코드로 고정돼 있어서 DNS 재바인딩 방어용 별도 라이브러리는 과하다 |
| CSRF | (없음) | Spring Security + 쿠키 CSRF | Spring Security | **Spring Security를 넣지 않는다.** 로그인 세션이 없어서 CSRF로 얻는 게 없다. 상태 변경은 폼 안의 토큰이 권한이다. 보안 헤더는 Caddy가 붙인다 |
| 백업 | age + rclone | restic | OCI CLI + R2 | **restic.** 암호화, 보관 정리, 여러 저장소를 도구 하나로 처리한다. 내 age + rclone을 철회한다 |
| 복원 후 해지 유지 | (없음) | 별도 저장소의 파기 대장 + AWS SDK | (없음) | **사용자 결정.** 아래 §6 |
| 최초 적재 재개 | collect_run | `collect_stage` 테이블 | (없음) | **월 단위 범위를 `collect_run`에 기록하고, 재실행하면 완료된 달을 건너뛴다.** 새 테이블은 필요 없다 |

## 3. 빼자는 것 (PRD 밖이거나 더 작게 가능)
- codex: `erasure_journal` + AWS SDK(§6 결정에 따름), `subscription_token`의 세대·PRF, `dispatch_attempt`/`callback_receipt`/`/callbacks/brevo`(MVP 밖), 이메일 암호화, `rate_limit_bucket`/`quota_counter`, `daily_page_count`, `collect_stage`, Spring Security, HttpClient5
- agy: ShedLock, Bucket4j, Caffeine, Spring Security, 컨테이너별 고정 메모리 한도(실측 전에 정하면 근거 없는 수치다)
- claude: node-exporter, age + rclone

## 4. 빠진 것 (PRD 수용 기준에 필요)
- **FR-A5 오류 분류** (codex 지적): "5xx N회면 비활성"은 공급자 장애 때 멀쩡한 구독까지 꺼 버린다. **수신자 단위의 영구 거부(SMTP RCPT 단계 550/551/553, 디스코드 404)만 비활성화하고, 공급자 5xx와 연결 오류는 재시도만 한다**로 PRD 문구를 고쳐야 한다
- **해지 링크 토큰** (세 안 모두 흐릿함): 매일 보내는 메일에 들어가는 해지 링크는 만료되면 안 된다. 해지 전용 토큰을 분리한다
- **배포 잠금** (codex): 배포 스크립트가 동시에 두 번 실행되지 않게 `flock`을 건다
- **Flyway 호환성 검증** (codex): CI에서 이전 버전 앱이 새 스키마로 동작하는지 확인하는 단계

## 5. 내 01에서 철회하거나 고칠 것
Java 25 → 21 · 헬스체크 기반 전환 → 명시 전환 · node-exporter 삭제 · age + rclone → restic · `api_call` 행 단위 → 날짜 카운터 · 해지 전용 토큰 추가 · FR-A5 오류 분류 반영

## 6. 사용자 결정이 필요한 것
1. **이메일 발송 서비스** (계정을 만들어야 하고 처리방침에 들어가는 내용이 달라진다)
   - OCI Email Delivery: 홈 리전이 한국이면 국외 이전이 없다. 다만 무료 한도가 문서끼리 충돌하므로 콘솔에서 확인해야 한다
   - Brevo: 하루 300통, EU 저장, 국외 이전 고지가 필요하다
   - Resend: 하루 100통, 미국 저장, 국외 이전 고지가 필요하다
2. **OCI 밖 백업 위치**: Cloudflare R2 또는 Backblaze B2(새로 가입, 10GB 무료) / 사용자의 NAS나 PC(SFTP, 상시 켜져 있어야 한다)
3. **복원 후 해지 유지 수준**
   - (a) 완전 보장: 해지할 때마다 OCI 밖에 기록한다 (codex 안). 구현이 늘어난다
   - (b) 최소: 해지 기록을 호스트의 별도 파일에 남겨서 DB만 망가진 경우는 막는다. VM 자체가 사라지면 마지막 백업 이후(최대 24시간) 해지한 사람에게 다시 발송될 수 있다. 이 위험을 처리방침과 runbook에 적는다
