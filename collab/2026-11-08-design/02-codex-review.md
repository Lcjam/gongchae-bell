# 설계 2라운드 교차 비판 · Codex

2026-11-08 · [claude](01-claude.md), [codex](01-codex.md), [agy](01-agy.md)를 [PRD v1.0](../../docs/prd.md)·[검증 기록](../03-feasibility.md)과 대조했다. **출시를 막는 쟁점은 알림 정합성·남용 제한·파기 후 복구다.** 설정 제안과 실측을 구분하며, 계정·실서버·실발송은 이번에도 시험하지 않았다.

## 1. 사실 오류와 미검증 주장

| 대상 | 판정과 바로잡을 내용 |
|---|---|
| agy §1 메모리 | **“여유 6GB로 OOM 원천 방지”는 철회.** 표의 합계는 제한값의 합이며 RSS 실측이 아니다. 컨테이너 자체의 한도 초과, OS·Docker·페이지 캐시·백업 부하도 남는다. 정상·수집·배포 겹침을 실측해야 한다. [Docker 메모리 문서][DOCKER] |
| claude §1·ADR4, agy ADR3 | **프록시 설정 reload 무중단과 서비스 전체의 실패 0건은 다르다.** Caddy는 설정 적용 실패 시 이전 설정을 유지하지만, 새 앱 readiness·진행 중 요청·DB 호환성까지 보장하지 않는다. claude의 두 upstream 상시 등록은 명시적인 전환 장벽이 없다. 연결 후 실패의 기본 재시도는 GET에 한정되므로 POST까지 안전하다는 근거로 쓰면 안 된다. [Caddy API][CADDY], [reverse_proxy][PROXY] |
| agy ADR2, claude ADR3 | **ShedLock으로 “완벽 차단”은 과장.** 공식 README는 실행이 lockAtMostFor를 넘으면 중복 실행 가능성을 경고한다. claude의 트랜잭션 advisory lock은 트랜잭션 종료 때 풀리므로 잠금 획득 쿼리만 별도 트랜잭션으로 실행하면 보호하지 못한다. [ShedLock][SHED], [PostgreSQL][PG] |
| claude ADR6 | **OCI 무료 안내와 사용 가능 한도는 별개.** 공식 Always Free에는 월 3,000건이 있지만 서비스 한도표의 Always Free max-emails-day는 0이다. 충돌을 미확인으로 둔 점은 맞으며, 콘솔·시험 전 1순위로 확정할 근거는 없다. 또한 발송 리전은 홈 리전과 같다고 보장되지 않는다. “홈 리전 한국이면 국외 이전 없음”은 철회하고 실제 엔드포인트·처리 위치를 확인해야 한다. [무료 안내][OCI], [한도표][OCILIMIT], [발송 리전][OCIMAIL] |
| agy ADR5 | **“AWS SES 무료 티어 부재”는 조건을 빠뜨렸다.** 현재 공식 가격표는 신규 고객의 최대 200달러 크레딧과 6개월 무료 플랜을 안내한다. 지속 운영의 상시 무료 한도로 간주할 수 없다는 이유로 배제하는 것은 타당하지만, 무료 혜택 자체가 없다는 표현은 틀리다. [AWS 가격][SES] |
| 세 안의 저장소 설명 | **OCI 20GB는 계정 유형을 붙여야 한다.** Always Free 전용 상태는 세 저장 계층 합산 20GB·월 5만 요청, 유료/체험 계정은 Standard·IA·Archive 각 10GB 안내다. agy의 R2 “월 100만 연산”도 불완전하다. Standard의 무료분은 저장 10GB-month, Class A 100만·B 1,000만 요청이며 초과분은 과금된다. S3 호환이라는 이유만으로 업로드·보관 정리 코드가 없어지는 것도 아니다. [OCI][OCI], [R2 가격][R2], [R2 가입 조건][R2START] |
| claude 외부 감시 | **UptimeRobot 무료를 개인 비상업 전용처럼 한정하지 말 것.** 현재 공식 도움말은 상업 이용도 허용하며 50개·5분 간격을 명시한다. [공식 도움말][UPTIME] |
| 세 안 이메일 ADR | **한도 자체는 확인됨:** Resend 월 3,000·일 100, 저장 미국·메일/로그 30일; Brevo 일 300, DB 저장 EU(프랑스·독일·벨기에), 무료 브랜딩은 공식 근거가 있다. 다만 “포트폴리오 완성도 저하”는 agy의 주관이다. Brevo 로그·미리보기는 기본 무기한이고 삭제에 최대 72시간이 걸릴 수 있다. Codex도 이 72시간을 공급자 전체 데이터·백업의 삭제 완료 기한으로 일반화하면 안 된다. [Resend 가격][RESEND], [보관][RESENDSEC], [Brevo 무료][BREVO], [위치][BREVOLOC], [로그][BREVOLOG] |
| 세 안 국외 이전 설명 | **국가 이름을 처리방침에 적는 것만으로 적법성이 확보되지는 않는다.** 별도 동의 또는 계약 이행에 필요한 위탁·보관 등 적용 근거와 그 요건, 위탁 문서·공개 항목을 정해야 한다. 수집 동의와 국외 이전 근거를 구분한다. [법 제28조의8][LAW28], [제26조][LAW26] |

A1 2 OCPU·12GB 가정은 현재 [공식 문서][OCI]와 일치하지만 본인 VM 확인은 여전히 남는다. 약 36회 최초 적재·상세 약 30회/일·합계 약 54회/일은 PRD와 검증 기록의 **추정**이지 실측 운영량이 아니다. 토큰 24시간·재시도 3회·메모리 예산 등은 초기 정책으로 표시하면 지어낸 성과 수치에 해당하지 않는다.

## 2. ADR별 차이와 권고

| 주제 | claude | codex | agy | 권고와 이유 |
|---|---|---|---|---|
| Java | 25 | 21 | 21 | **21**로 CI·로컬·이미지 통일. 25 전용 기능이 없으므로 “생태계가 더 안정적”이라는 미검증 비교는 불필요 |
| 스케줄 잠금 | advisory xact lock | 자체 lease·heartbeat·fence | JDBC ShedLock | **JDBC ShedLock + DB 시각 + 논리 실행 회차 유니크**. 직접 임대 구현을 줄이되, 제한 시간·잠금 만료·순차 중복 시험 필요 |
| 발송 선점 | SKIP LOCKED | 구독 행 잠금+SKIP LOCKED·임대 | 조건부 UPDATE | **원자 선점+구독 변경과 동일 잠금 순서+선점 시각/회수 정책**. 선점 UPDATE 하나로 중단 복구까지 해결되지 않음 |
| 배포 | 두 upstream의 건강 상태로 전환 | readiness 후 단일 upstream reload | readiness 후 upstream reload | **새 앱 내부 점검 → 명시적 전환 → 외부 시험 → 옛 앱 종료**. 실패 시 옛 색상을 실제로 유지해야 롤백 가능 |
| 관측 | Prometheus·Grafana·node-exporter | Prometheus·Grafana+호스트 도구 | Prometheus·Grafana | **Prometheus·Grafana+외부 HTTP/heartbeat 유지**. node-exporter는 상시 호스트 경보를 구성할 때 추가; PRD의 자원 기준선은 우선 호스트 명령으로 기록 |
| 이메일 | SMTP, OCI 우선 | Brevo API 조건부 | Resend API | **Resend API를 우선 검증**. 24시간 멱등 키가 중단 재시도에 유용하다. 일·월 예산과 국외 보관·파기 검증은 필수이며 무조건 선정은 아님. [멱등 문서][IDEMP] |
| 코드 다중값 | 조인 테이블 | 배열+GIN | VARCHAR 목록 | **claude의 posting_code**. 필터·NCS 집계에 명시적인 원소 단위 질의 사용; 문자열 부분 일치 방식은 피함 |
| 웹훅 저장 | AES-GCM+HMAC | AES-GCM+HMAC·key_id·AAD | AES-GCM+SHA-256 | **AES-GCM+중복 해시**, 매번 새 nonce·키 ID·복구 시험 명시. 서버 키는 DB와 분리; Converter만 적어서는 규칙이 완성되지 않음 |
| 요청 제한 | IP 메모리+대상 DB | DB 공유 카운터 | Caffeine+Bucket4j | **DB 공유 카운터**. IP·대상·전체 예산을 두 앱과 재시작에 걸쳐 합산 |
| 일정·만회 | 일정은 제시, 만회 미정 | 당일 만회·과거 D-3 제외·연장 재알림 없음 | 일일 묶음·만회 미정 | **Codex의 만회 정책 채택**. 재시도 3회는 최초 포함인지 통일하고 429 지시를 우선 |
| 백업 | pg_dump+age+rclone | pg_dump+restic | pg_dump+OCI CLI·외부 전송 | **암호화 덤프를 두 위치에 보관하는 한 가지 도구 흐름**. restic 우선, 실제 두 저장소 연동·정리·키 복구 시험으로 확정 |
| 복원 후 해지 | 백업 보관 고지만 | 독립 파기 대장 재적용 | 언급 없음 | **복구 시 외부 발송 차단은 필수**. 활성 구독 보존 여부를 먼저 결정한 뒤 대장 재적용 또는 전체 구독 파기를 선택(§6) |

## 3. 최소 구성으로 줄일 것

- **agy:** Bucket4j+Caffeine은 공유 예산을 해결하지 못하면서 의존성 둘을 늘린다. DB 제한으로 대체한다. 패키지에는 RateLimitBucket이 있지만 DDL·공유 사용법도 없다.
- **Codex:** 자체 lease·fence와 파기 대장 전용 AWS SDK는 구현·장애 경우의 수가 크다. 스케줄은 검증된 JDBC 잠금으로 줄인다. 파기 대장의 필요성은 복원 정책에 따라 결정하며, 제거하더라도 해지 유지 수용 기준은 생략하지 않는다.
- **공통:** SMTP/API·여러 공급자 동시 지원, 상세 이력용 과도한 테이블·본문 snapshot은 필수가 아니다. 선택한 공급자 하나와 필요한 시도 메타데이터부터 구현한다. 재시도 요청을 고정할 snapshot이 필요하면 저장 항목과 파기 시점을 명시한다.
- **우선순위 유지:** 상세 steps/files, sitemap, RFC 8058은 Should다. Must 출시 조건으로 올리지 않는다. 차트·로그인·Redis·별도 워커·공개 상태 페이지를 추가할 이유는 없다.
- 별도 CLI를 쓰는 백업과 호스트 측정에는 역할이 있다. 반면 **node-exporter를 무조건 필수로 올리거나 메모리 제한값을 검증 없이 확정할 근거는 없다.**

## 4. 빠지면 수용 기준을 충족하지 못하는 설계

| 대상·PRD | 누락과 최소 보완 |
|---|---|
| 세 안 · C2·C3·C6 | 완결성 확인 전에는 상태 갱신하지 않는 경계와 KST 날짜 전환을 명시한다. 호출 예산은 **실제 요청 전에 별도 커밋으로 예약**하고 재시도·수동·초기 적재를 함께 센다. agy의 일 합계 하나만으로는 개별 호출 시도·결과를 확인할 수 없다 |
| agy · W3·W4·C4 | 자격·우대는 선택 수집 job_detail에 있고 필수 전형 설명은 DDL에 없다. **목록 응답의 필수 설명을 본체에 저장**해야 과거 공고와 C7 미구현 때도 상세가 나온다. 인원 원문·해석 실패 표시, 상세 NONE/FAILED, 비교 필드 전체 보관도 필요 |
| claude·agy · A1·A3 | 활성 이메일 중복 신청의 동일 응답·덮어쓰기 금지, 토큰 목적/만료/단회 검증, 재발급·매 메일 해지 링크 생성 방법이 빠졌다. **해시에서 원문을 복원할 수는 없다.** agy는 관리 링크 재발급·Discord URL 재입력 라우트와 구독 폼 GET도 추가해야 함 |
| claude·agy · A2·W6 | URL 파싱뿐 아니라 실제 연결 IP 검사(IPv4/IPv6), 리다이렉트 금지·wait=true·자동 멘션 차단을 설계해야 한다. 허용된 도메인이라고 검사만 하고 실제 연결은 다른 DNS 결과를 쓰면 안 됨. 출력 escape와 http(s) 링크 허용도 공통 규칙으로 명시 |
| agy 중심 · A4 | 공고별 outbox 행만으로는 **이메일 신규+마감 하루 한 통**을 표현하지 못한다. 발송 묶음과 항목을 분리하고 (구독, 날짜/회차) 유니크를 둔다. 수집 반영+대상 기록의 동일 트랜잭션, 최초 적재 제외, 선점 직전 재필터, 서버 중단 만회도 필요 |
| 세 안 · A4·A6 | 발송 상세 이력을 7/14일 뒤 지우면서 중복 키까지 없애면 마감 연장 뒤 재발송을 막지 못한다. **활성 구독의 최소 중복 키와 단기 본문/시도 이력을 구분**하고 각각의 보유 목적·기간을 정한다. 해지 시에는 둘 다 파기 |
| claude·agy · A4·§7 | 해지/수정과 발송이 경합할 때 같은 구독 잠금을 쓰는지, SENDING 중단을 언제 회수하는지 빠졌다. 단순 상태 선점만으로 발송 후 종료의 중복을 없앨 수 없다. 두 앱·선점 후 종료·수락 후 종료·해지 경합 시험을 각각 명시 |
| 세 안 · A7 | claude·agy는 전체 발송 상한과 모든 대상별 제한이 빠졌고, **Codex도 관리 토큰 시도는 IP만 제안**했다. 신청·재발송·테스트·토큰 시도마다 IP/대상 규칙과 공유 전체 예산을 적어야 한다. 유효하지 않은 토큰은 토큰 fingerprint 등으로 대상 키를 만들고 저장량도 제한 |
| claude·agy · A6·§7 | 백업 14일 고지만으로 **복원 후 해지 유지**가 되지 않는다. 비활성 상태의 보유/파기 시점도 빠졌다. 복원 시 발송 차단·해지 반영·키 복구·재개 조건을 시험해야 한다. agy의 압축 덤프는 암호화가 아니므로 사본 접근 제한·키 분리도 구체화 |
| 세 안 · G2·G7·§6 | 페이지/원문 이동/활성 구독의 집계, 봇·내부·시험 분류를 구체화한다. “재시도 수”를 “확인된 중복 수”로 보고하지 말고 **확인된 중복과 수락 여부 불명확을 분리**한다. API 성공은 받은편지함 수신이 아니다. 성능·자원 기준선, 두 종류 경보 수신, 취약점·비밀값 검사의 실행·기록 경로도 필요 |

추가로 agy의 8081/8082는 **호스트 loopback 바인딩인지** 명시해야 한다. Caddy 경로 차단만으로 host publish 포트를 막을 수 없으며, Caddy 컨테이너에서 호스트 localhost를 upstream으로 쓰는 것도 별개다. Docker 내부 서비스 이름을 쓰고 운영 포트는 미공개/loopback으로 제한한다. [Docker 포트 문서][PORT]

## 5. 내 01에서 철회·수정할 점

1. **자체 임대 구현 우선 선택을 철회한다.** fence는 외부 Discord/메일 요청을 취소하지 못한다. JDBC 잠금·논리 회차·원자 선점으로 줄이고, 확인할 수 없는 외부 결과는 남긴다.
2. **동기 OCI 파기 대장을 기본안으로 확정하지 않는다.** 외부 기록 성공/DB 실패의 보상, 장기 장애 중 해지 처리, OCI 전체 접근 상실 때의 대장 가용성이 미설계였다. 대장 유지 시 독립 사본·복원 발송 차단·재처리 규칙이 필요하며, SDK 추가도 그때 정한다.
3. **Brevo 우선순위를 재검토한다.** 무료량만으로 선택하기엔 기본 로그 보관과 파기 검증 부담이 크다. Resend의 멱등 기능을 먼저 검증하되 두 공급자의 국외 처리·보관 조건을 모두 고지에 반영한다. Brevo의 72시간은 로그 삭제 설명으로 한정한다.
4. **웹훅 마스킹 표시도 제거한다.** 내 와이어프레임의 “마스킹 목적지”는 이메일만 해당한다. Discord URL은 관리 화면에도 표시하지 않고 채널 종류·구독 상태만 보여 준다(A2).
5. 관리 토큰 시도의 **대상별 제한**, 최소 중복 키 보유 기간, 초기 적재 날짜 파라미터(pbancBgngYmd/pbancEndYmd, YYYY-MM-DD)를 보완한다. 배열 GIN보다 claude의 코드 조인 테이블을 우선 채택한다.

세 안 모두 HTTP API 5xx와 SMTP 5xx를 같은 구독 실패로 취급하는 PRD A5를 그대로 구현하면 위험하다. **HTTP 5xx는 서버 오류이고 SMTP 5yz는 영구 거부**이며, 인증/공급자 장애도 수신 주소 오류와 구별해야 한다. 수신자 영구 실패만 비활성화하는 문구로 PRD 정정을 제안하되, 확정 PRD를 여기서 몰래 바꾸지 않는다. [HTTP 표준][HTTP], [SMTP 표준][SMTP]

## 6. 사용자 결정·입력이 꼭 필요한 것

- **복원 후 사용자 경험:** 기존 활성 구독 복구가 필요한가, 재난 복구 시 모든 구독을 폐기하고 재등록을 받는 단순 정책도 허용하는가? 후자는 기존 사용자의 알림이 멈추는 제품 결정이다. 승인 전에는 활성 구독을 임의 폐기하지 않는다.
- **OCI 밖 사본 위치와 비용 범위:** 이미 접근 가능한 저장소가 있는지, 없으면 무료분 초과 과금 가능성이 있는 외부 스토리지 가입을 허용할지. 구체 도구 선택은 ADR에서 정한다.
- 기존 도메인 이름·DNS 설정 권한, 운영 경보 수신 채널, 공개할 개인정보 담당 연락처. **비밀값은 이 문서나 채팅에 적지 않고 정해진 Secrets 경로로 제공**한다.

Java·DB 잠금·해시·이메일 공급자 자체의 선택은 사용자에게 다시 묻지 않는다. 계정별 발송 가능 여부·서버 자원·위탁/국외 처리 조건은 출시 전 확인할 항목이며, 이 리뷰가 검증 완료를 뜻하지 않는다.

[DOCKER]: https://docs.docker.com/engine/containers/resource_constraints/
[CADDY]: https://caddyserver.com/docs/api#post-load
[PROXY]: https://caddyserver.com/docs/caddyfile/directives/reverse_proxy
[SHED]: https://github.com/lukas-krecan/ShedLock
[PG]: https://www.postgresql.org/docs/17/explicit-locking.html#ADVISORY-LOCKS
[OCI]: https://docs.oracle.com/en-us/iaas/Content/FreeTier/freetier_topic-Always_Free_Resources.htm
[OCILIMIT]: https://docs.oracle.com/en-us/iaas/Content/General/service-limits/default.htm
[OCIMAIL]: https://docs.oracle.com/en-us/iaas/Content/Email/Concepts/overview.htm
[SES]: https://aws.amazon.com/ses/pricing/
[R2]: https://developers.cloudflare.com/r2/pricing/
[R2START]: https://developers.cloudflare.com/r2/get-started/
[UPTIME]: https://help.uptimerobot.com/en/articles/11604710-who-should-use-uptimerobot-s-free-plan
[RESEND]: https://resend.com/pricing
[RESENDSEC]: https://resend.com/security
[IDEMP]: https://resend.com/docs/dashboard/emails/idempotency-keys
[BREVO]: https://help.brevo.com/hc/en-us/articles/208580669-FAQs-What-are-the-limits-of-the-Free-plan
[BREVOLOC]: https://help.brevo.com/hc/en-us/articles/360001005510-Data-storage-location
[BREVOLOG]: https://help.brevo.com/hc/en-us/articles/360021533839-Manage-your-transactional-logs-and-email-previews
[LAW28]: https://www.law.go.kr/LSW/lsSideInfoP.do?docCls=jo&joBrNo=08&joNo=0028&lsiSeq=283839&urlMode=lsScJoRltInfoR
[LAW26]: https://www.law.go.kr/LSW/lsSideInfoP.do?docCls=jo&joBrNo=00&joNo=0026&lsiSeq=283839&urlMode=lsScJoRltInfoR
[PORT]: https://docs.docker.com/engine/network/port-publishing/
[HTTP]: https://www.rfc-editor.org/rfc/rfc9110.html#name-server-error-5xx
[SMTP]: https://www.rfc-editor.org/rfc/rfc5321.html#section-4.2.1

