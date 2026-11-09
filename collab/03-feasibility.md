# 3단계 · 1순위 실현 가능성 검증 (Claude)

> 2026-11-08 · 대상: [final.md](final.md)의 1순위 "공고 변경·마감 알리미"
> 방법: 공식 문서와 페이지 원문(swagger 포함) 확인, 실제 HTTP 호출, 공개 저장소의 실사용 사례 대조, 레지스트리와 Maven Central 직접 조회

## 결론

**실현 가능하다. 기술적으로 막히는 항목은 없다.** 다만 두 가지는 사용자 정보가 있어야 확정할 수 있다. 하나는 **본인 서버가 A1(ARM)인지**, 다른 하나는 **본인 API 키가 정상 활성화되는지**다.

새로 드러난 리스크도 하나 있다. 같은 API로 "잡알리오 알리미/레이더"를 만든 공개 저장소가 **최소 4개** 있다. 공고 알림이라는 기능만으로는 차별화가 되지 않는다. 차별점은 **운영 기록**(수집 신뢰성, 배포, 복구, 장애 회고)에서 나와야 한다. 원래 계획과 같은 방향이지만, 이제 선택이 아니라 필수다.

## 항목별 판정

| # | 항목 | 판정 | 근거 | 주의점 |
|---|---|---|---|---|
| 1 | **데이터 원천**: 공공기관 채용정보 API `apis.data.go.kr/1051000/recruitment` (`/list`, `/detail`) | ✅ | 페이지 원문: **개발단계 자동승인**, 개발계정 **1,000회/일**, 운영단계는 심의승인(활용사례를 등록하면 증량), 이용허락범위 **제한 없음**, 갱신 "실시간". 키 없이 호출하면 `SERVICE_KEY_IS_NULL`이 와서 엔드포인트가 살아 있음을 확인 | 키 미등록 403(코드 30)을 겪은 사례가 있다 (W1nyu/jobRadar) |
| 2 | 응답 필드 | ✅ | swagger 원문: `recrutPblntSn`(ID), `recrutPbancTtl`, `instNm`, `pbancBgngYmd`/`pbancEndYmd`, `decimalDay`(D-day), `ongoingYn`, `recrutSe`, `hireTypeLst`, `ncsCdLst`, `workRgnLst`, `recrutNope`, `srcUrl` 등 | **수정시각 필드가 없다**. 변경 감지는 내용 해시로 한다. 목록의 `files`와 `steps`는 빈 배열이다 |
| 3 | 요청 필터 | ✅ | `ongoingYn`, `recrutSe`(R2010 신입, R2030 신입+경력), `ncsCdLst`(R600020 정보통신), `workRgnLst`, `hireTypeLst`(R1010 정규직), **`recrutPbancTtl`(제목 포함 검색)**, 공시 시작일 범위 | 앞서 "키워드 검색 미지원"이라고 한 건 **틀렸다**. 요청 날짜는 `YYYY-MM-DD`, 응답은 `YYYYMMDD`. 코드표 주의 (R3013은 대구) |
| 4 | 실제 응답 형태 | ✅ | 키 없는 공개 경로 `opendata.alio.go.kr/.../recrutInquiryAjaxList.do`에서 HTTP 200과 **공식 API와 같은 스키마의 실데이터**를 받았다 | 실데이터에 깨진 문자가 있고, `srcUrl`이 개별 공고가 아니라 기관 채용 페이지인 경우가 있다. 데이터 정제가 실제 작업이 된다 |
| 5 | 실사용 사례 | ✅ | chan1098/jobalio: 키로 호출 성공 (`totalCount 561`, `numOfRows=100`). Joo-Nick/job-radar: 같은 엔드포인트 사용 | 차별성 리스크 (위 결론) |
| 6 | **대체 경로** | ✅ | 공식 키에 문제가 생기면 위 공개 JSON 경로로 수집할 수 있다 (W1nyu/jobRadar가 실제로 전환) | 화면용 내부 경로라 예고 없이 바뀔 수 있다. 공식 API를 우선한다 |
| 7 | 호출 예산 | ✅ | 실제 진행 중 공고는 **504건**이다 (공개 경로 `totalCount`. 잡알리오 화면의 385건은 기본 2개월 등록일 필터가 걸린 값). 100건씩 받으면 6회, 1시간 주기면 하루 144회. 신규 상세조회 약 30회를 더해도 **하루 약 175회로 한도의 18%** | 상세조회를 매 건 부르면 한도가 바닥난다. 신규 공고만 부를 것 |
| 7-1 | **실측 (공개 경로, 공식 API와 같은 스키마)** | ✅ | `numOfRows=1000`이면 504건이 한 번에 온다. 필터 동작 확인: 정보통신(R600020) 26건, 여기에 신입(R2010) 4건. 응답 필드 30개가 공식 swagger와 일치. 마감일, D-day, NCS 코드는 504건 모두 있음 | **원문 링크의 20%(101건)는 기관 첫 화면이라 개별 공고가 아니다**. 3%(17건)는 글자가 깨져 있다. `recrutSe`는 코드 하나만 받으므로 신입과 신입+경력은 전량을 받아 직접 거른다 |
| 8 | **수요 (공고 양)** | ✅ | 잡알리오 최근 1년: 전체 12,312 · 신입 가능 11,490 · 정보통신 1,053 · 정보통신+신입 954 · **정보통신+신입+정규직 230 (주 4~5건)**. 하루 신규 약 34건 | 개발 직군만 대상으로 하면 알림이 주 몇 건 수준이다. 비개발 취준생까지 대상으로 하면 하루 수십 건이다 |
| 9 | 알림: 디스코드 웹훅 | ✅ | 무료이고 승인이 필요 없다. 공식 문서는 429 응답과 `Retry-After`/`retry_after` 준수만 규정한다 | 웹훅별 정확한 한도는 비공식 자료뿐이다(5회/2초 설). 묶음 알림이면 하루 몇 건이라 여유롭다. 개인 DM은 봇이 필요하니 MVP는 **그룹 채널 웹훅**으로 한다 |
| 10 | 서버: OCI A1 2 OCPU / 12GB | ⚠️ 조건부 | 아래 메모리 추정으로 약 5GB면 충분하다 | **사용자 서버가 AMD Micro(1GB)뿐이면 이 스택은 안 맞는다.** shape 확인 필요 |
| 11 | 컨테이너 이미지 ARM64 | ✅ | Docker Hub 매니페스트 직접 조회: `eclipse-temurin:25-jre`/`21-jre`, `postgres:17`, `nginx:stable`, `caddy:2`, `prom/prometheus`, `grafana/grafana` **모두 linux/arm64 지원** | |
| 12 | 프레임워크와 라이브러리 | ✅ | Maven Central 원본 메타데이터: Spring Boot **4.1.2** 안정판 (4.2는 RC), Spring Framework 7.0.x, Spring Batch 6.0.6, Flyway 13.12.1 (+ `flyway-database-postgresql`), Testcontainers 2.0.5, Resilience4j `spring-boot4` 2.4.0 | **Framework 7에 `@Retryable`/`@ConcurrencyLimit`이 내장**됐으니 Resilience4j 없이 시작할 수 있다. 이 PC에는 Java 17이 깔려 있다. Boot 4는 17 이상이면 되지만 21이나 25 LTS를 권장한다. Docker Desktop은 설치돼 있다 |
| 13 | CI/CD: GitHub Actions ARM64 | ✅ | `ubuntu-24.04-arm` 러너는 **공개 저장소에서 무료**다 (2025-08 GA) | **비공개 저장소에서는 못 쓴다.** 그때는 x86에서 JAR를 빌드하고 `buildx --platform linux/arm64`로 COPY만 하는 이미지를 만든다(에뮬레이션 불필요). 포트폴리오는 어차피 공개 저장소를 권장 |
| 14 | 외부 감시 | ✅ | Healthchecks.io Hobbyist **$0에 20개 작업** (공식 가격 페이지). UptimeRobot 무료 플랜은 모니터 50개, 5분 간격 | UptimeRobot 무료 플랜의 상업 이용 제한은 보도마다 엇갈린다. 개인 포트폴리오는 비상업이라 해당 없음 |
| 15 | 백업: OCI Object Storage | ✅ | Always Free 20GB, 월 API 5만 회. 하루 1회 업로드와 보관 정리를 해도 월 100회 미만이다. S3 호환 API(Customer Secret Key) 또는 OCI CLI로 올린다 | 사본 하나는 OCI 밖에도 둔다 |
| 16 | 도메인과 HTTPS | ✅ | DuckDNS 무료 서브도메인 서비스 동작 확인 + Caddy 자동 HTTPS(Let's Encrypt). 유료 도메인은 연 1~2만 원대 | |
| 17 | 2순위 원천: 온통청년 API | ⏸ 보류 | data.go.kr에 등록됨 (2025-05). 온통청년 직접 신청은 **담당자 심사 후 발급**이고 XML이다 | 승인 여부가 불확실하다. MVP에서 제외 |

## 메모리 예산 추정 (실측 전)

| 구성 요소 | 추정 RSS | 비고 |
|---|---|---|
| Spring 앱 × 2 (배포 순간에만 2개) | 2.0~2.4GB | 힙 512~768MB 기준 |
| PostgreSQL 17 | 1.0~1.5GB | shared_buffers 512MB~1GB |
| Prometheus + Grafana | 0.5~0.8GB | 이 정도 규모 기준 |
| Caddy 또는 Nginx, OS와 Docker | 0.7~0.8GB | |
| **합계** | **약 4.5~5.5GB / 12GB** | 일반적인 값으로 잡은 **추정**이다. 서버에서 `docker stats`로 실측할 것 |

메모리를 20%(2.4GB) 넘게 쓰므로, 유휴 회수 조건(CPU, 네트워크, 메모리가 **모두** 20% 미만)에는 해당하지 않을 것으로 본다. 이것도 실측으로 확인한다.

## ✅ 공식 API 실호출 검증 (사용자 키, 2026-11-08)

| 호출 | 결과 |
|---|---|
| `list` 진행 중, `numOfRows=100` | HTTP 200 · `resultCode=200` · totalCount **504** · 100건 · 필드 30개 · 599ms |
| `list` 진행 중, `numOfRows=1000` | **504건 전체가 한 번에 옴** · 672ms |
| `list` + `ncsCdLst=R600020` (정보통신) | 26건 |
| `list` + 정보통신 + `recrutSe=R2010` (신입) | 4건 (모두 R2010) |
| `list` + `recrutPbancTtl=전산` (제목 검색) | 2건 |
| `detail` (`sn=306805`) | HTTP 200 · `steps` 2건, `files` 3건이 상세조회에서 채워짐 · 175ms |

| `list` 과거 기간 (`pbancBgngYmd=2025-11-08`, `pbancEndYmd=2025-12-07`, 진행 여부 미지정) | `resultCode=200` · totalCount 542 · 542건 (마감 공고 포함, `ongoingYn=N` 확인) |
| `list` 최근 1년 (`2025-11-08`~`2026-11-08`) | **totalCount 11,784** (공시 시작일 기준. 잡알리오 화면의 12,312건은 등록일 기준이라 다르다) |

- 키는 **발급 직후 바로 동작**했다. 403(코드 30) 문제는 없었다.
- **잡알리오 공고 링크**: `https://job.alio.go.kr/recruitview.do?idx=306805`를 열면 해당 공고(대한적십자사 전북혈액원 시설경비원)가 표시된다. 표본 1건 확인이므로 구현 시 fixture 여러 건으로 재확인한다.
- **기관 식별자**: swagger에 `pblntInstCd`(공시기관코드)와 `pbadmsStdInstCd`(행정표준기관코드)가 있다. 실데이터 예는 `pblntInstCd=C0059`(대한적십자사)다. 잡알리오 기관정보 화면의 기관 ID(`apba_id`, 예: 국민건강보험공단 `C0026`)와 형식이 같다.
- **잡알리오 기존 기능 (2026-11-08 확인)**: 공고 검색에 NCS, 신입·경력, 고용형태, 지역 필터와 마감일순 정렬이 이미 있다. 기관정보(`/orginfo.do`, 2026년 지정 342개 기관)와 기관비교(`/orgcompare.do`) 화면도 있다. 하지만 기관 상세(`/orginfoview.do`)는 소개, 설립 근거, 연혁, 경영 목표, 조직도만 보여 주고 **기관별 채용 이력이나 통계는 없었다.** 기관비교의 비교 항목은 JS로 열려서 확인하지 못했다.
- 한 번 수집에 **1회 호출**로 끝난다. 1시간 주기면 하루 24회, 신규 상세조회 약 30회를 더해 **약 54회로 한도의 5%**다. 10분 주기로 바꿔도 약 174회다.
- 키는 저장소와 파일에 저장하지 않았다. 앞으로도 환경변수나 GitHub Secrets로만 쓴다.

**→ 서버를 제외한 모든 항목의 실현 가능성 확인 완료.**

## (이전 기록) 사용자 확인이 필요했던 것

서버(#10)는 사용자 요청으로 검증 범위에서 뺐다.

1. **본인 API 키**: 공공데이터포털에서 "공공기관 채용정보 조회서비스"를 활용신청한다(개발계정 자동승인). 필터, 페이지 크기, 데이터 품질은 공개 경로로 이미 실측했으니, 키로 확인할 것은 **공식 게이트웨이 쪽**만 남는다.
   - 키 정상 동작 여부 (403 코드 30 여부. 발급 직후에는 반영까지 시간이 걸릴 수 있다)
   - 공식 API의 `numOfRows` 최대값 (100은 타인 사례로 확인됨)
   - 키는 저장소에 커밋하지 않고 환경변수로만 쓴다.

## 출처
- 공공데이터포털 API 페이지 (swagger, 심의유형, 트래픽): https://www.data.go.kr/data/15125273/openapi.do
- 잡알리오: https://job.alio.go.kr/recruit.do · 공개 JSON: https://opendata.alio.go.kr/new/odaApiMng/recrutInquiryList.do
- 실사용 사례: https://github.com/chan1098/jobalio · https://github.com/W1nyu/jobRadar · https://github.com/Joo-Nick/job-radar · https://github.com/LeLu815/job-notice
- Discord 레이트 리밋: https://docs.discord.com/developers/topics/rate-limits
- Healthchecks.io 가격: https://healthchecks.io/pricing/
- GitHub ARM64 러너 GA: https://github.blog/changelog/2025-08-07-arm64-hosted-runners-for-public-repositories-are-now-generally-available/
- Spring Framework 7 Resilience: https://docs.spring.io/spring-framework/reference/core/resilience.html
- OCI S3 호환 API: https://docs.oracle.com/iaas/Content/Object/Tasks/s3compatibleapi.htm
- UptimeRobot 무료 플랜 정리: https://notifier.so/guides/uptimerobot-pricing-2026/
- DuckDNS: https://www.duckdns.org/spec.jsp
