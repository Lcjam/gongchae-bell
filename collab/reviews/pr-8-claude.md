# PR #8 리뷰 · claude (후속 확인)

> 2026-11-14 · 조율: codex · 대상: PR #8 `feat/#2-app-foundation`, head `42d09d7`
> 읽은 것: `03-foundation-validation.md`, PR 본문, 현재 `ci.yml`, `.env.example`, README, erd.md, V1, CatalogController, 테스트, layout. 앞선 리뷰: [02-claude-foundation-review.md](../2026-11-14-implementation/02-claude-foundation-review.md)
> 코드는 고치지 않았다. 확인은 모두 읽기 전용(GitHub API, 실행 중인 앱에 GET)으로 했다.

## 결론
**머지를 막을 지적은 남아 있지 않다.** 필수 지적 B1은 원격 CI 결과로 해결을 확인했다. R1~R4는 모두 처리됐거나, 근거 있는 대안으로 처리됐다. 머지 여부는 사용자가 정한다.

## 필수 지적 B1 (CI 액션의 node20 런타임) → 해결
- `ci.yml`이 정확한 태그로 고정됐다: `checkout@v7.0.1`, `setup-java@v6.3.0`, `setup-gradle@v6.4.1`, `upload-artifact@v7.0.2`, `gitleaks-action@v3.0.0`. 각 메이저 태그의 `action.yml`이 `node24`인 것은 02 리뷰 때 확인했다.
- 원격 실행 [43576628354](https://github.com/Lcjam/gongchae-bell/actions/runs/43576628354), head `42d09d7`, `pull_request` 이벤트:
  - `test` (`ubuntu-24.04-arm`): **success**, 08:36:35Z~08:37:54Z. 로그 `BUILD SUCCESSFUL in 1m 5s`. 업로드된 `test-results`를 받아 확인한 결과 **4 tests, 0 failures, 0 skipped**(17.5초). ARM 러너에서 Testcontainers PostgreSQL 17이 동작한다는 01-claude §5의 미확인 항목도 이걸로 닫힌다.
  - `secrets` (gitleaks v3): **success**.
  - test job 로그에 node20 관련 경고가 없다.
- codex 메시지에는 "원격 secrets CI 통과"만 있었는데, 이 리뷰를 쓰는 동안 test job도 끝나서 둘 다 통과한 것을 확인했다.

## R1~R4 처리 확인
| # | 지적 | 처리 | 판단 |
|---|---|---|---|
| R1 | 실행 중 jar가 다시 빌드돼 검증용 앱이 깨짐 | 옛 PID 10672는 종료됐다. 현재 PID 18852의 명령줄은 `java -jar .tools/runtime/gongchae-bell.jar`(사본)다. 내가 GET으로 다시 확인했다: `/` `/jobs` `/robots.txt` 200, 관리 포트 readiness 200, `/actuator/prometheus` 200 | **해결** |
| R2 | `collect_run.error varchar(100)`에 긴 예외 메시지를 넣으면 결과 갱신이 실패함 | 스키마는 그대로 두고, erd.md에 "원본 예외 메시지나 요청 URL을 저장하지 않고 100자 이내 내부 오류 코드만 저장"이라는 규칙을 적었다 | **수용.** 내 안보다 낫다. `serviceKey`가 요청 URL의 쿼리에 들어가므로 원본 예외 메시지를 저장하지 않는 것은 비밀값 보호 규칙이기도 하다. 단 지금은 문서 규칙일 뿐이다 → #3에서 코드로 강제할 것(아래 #3-8) |
| R3 | `.env.example`의 빈 `DB_URL=` | CLAUDE.md의 "`.env.example`에는 키 이름만 둔다"를 지키고, README 1단계에 세 값 모두 입력하라는 안내와 "DB_URL을 빈 문자열로 내보내면 시작할 수 없다"는 경고를 넣었다 | **수용.** 프로젝트 규칙이 우선이다 |
| R4 | README의 Java 필수 조건 문구가 서로 어긋남 | "Java 17 이상(Gradle 실행용) … 앱 컴파일·실행용 Java 21은 toolchain이 다운로드"로 고쳤다 | **해결** |

## 머지 전 정리하면 좋은 것 (차단 아님)
- PR 본문의 "ARM64 CI는 이 PR에서 실행합니다"와 `03-foundation-validation.md`의 "최종 CI 검증은 PR 실행으로 확인한다"는 이제 결과가 있다. 위 실행 링크와 "ARM64 4 tests 통과"로 바꿔 두면 완료 기준의 증거가 PR 안에서 닫힌다.
- 푸터의 출처 링크 `data.go.kr/data/15125273/openapi.do`가 "재정경제부_공공기관 채용정보 조회서비스" 페이지인 것을 확인했다. 문제없다.

## #3 수집 코어로 넘길 확인 목록
#3은 별도 워크트리에서 한다고 들었다. 내 앞선 두 리뷰에서 수집 코어에 해당하는 것만 추렸다. 상세 근거는 링크한 절에 있다.

1. 회차 선점은 짧은 트랜잭션으로 먼저 커밋한 뒤 API를 호출한다 ([01 §3-1](../2026-11-14-implementation/01-claude.md))
2. BACKFILL은 실패하거나 멈춘 달을 다시 선점할 수 있어야 한다 (01 §3-2, ADR-0003 보충 필요)
3. 예산은 `INSERT … ON CONFLICT DO UPDATE … WHERE count < 900 RETURNING`처럼 한 문장으로, 별도 트랜잭션에서 증가시킨다. 재시도도 시도마다 센다 (01 §3-3)
4. 공고 쓰기: 기존 행을 sn 순서로 `FOR UPDATE`, 새 행은 `ON CONFLICT (sn)`, 상세 수집은 자기 컬럼만 UPDATE한다. 해시는 다중값 코드를 정렬해서 계산한다 (01 §3-4)
5. 날짜는 모두 KST `Clock` 빈에서 계산한다. `@Scheduled`에 `zone = "Asia/Seoul"`, 스케줄러 풀 3 이상, 종료 대기 30초를 둔다 (01 §3-9)
6. 응답 봉투: 공개 경로 fixture는 `{"data": {...}}` 래퍼가 있고 공식 API는 없다고 fixtures/README에 적혀 있다. 공식 봉투는 아직 실제 응답으로 확인하지 않았다. 파서가 봉투를 한 곳에서 벗기게 만들고, 공식 응답을 1회 받으면 그 fixture를 추가한다 (01 §2-3)
7. 날짜가 비었거나 형식이 틀린 행의 규칙: NOT NULL은 유지하고 그 행은 건너뛰며 건수를 남긴다 (02 F2)
8. `collect_run.error`에는 정해진 오류 코드(enum이나 상수)만 들어가게 한다. 실패 fixture에서 `result = 'FAILED'`와 코드가 기록되는지 테스트한다 (위 R2)
9. 두 번째 `@SpringBootTest` 클래스를 만들기 전에 Testcontainers를 공용 컨테이너로 옮긴다 (02 F1)
10. FR-C5 테스트는 같은 DB에 Spring 컨텍스트 두 개를 띄워 동시에 호출하는 방식을 권한다. 지금의 슬롯 테스트는 DB 제약만 증명한다 (01 §3-11)

D1(확인 토큰)은 M2 전 별도 3자 라운드라고 해서 여기서 다루지 않았다.
