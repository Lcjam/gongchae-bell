# PR #10 교차 리뷰 수정본 독립 검수 · sol

2026-11-15. 대상은 불변 커밋 **`4334af809e3d8ce97205e29dcf9a22d28fcddaa6`**이다. GitHub PR 메타데이터에서 현재 head와 일치함을 확인했다. `CLAUDE.md`, PRD 웹 요구사항과 G2, 기존 검수 05, `10-review-decisions.md`, Claude의 PR #10 리뷰, 구현 결과를 읽고 지정 워크트리의 `git show`로 수정 코드·테스트를 대조했다. 코드·테스트·설정·사용자 변경은 수정하지 않았으며 로컬 테스트·브라우저·실제 API를 재실행하지 않았다.

**판정: M1/M2/L1~L3 수정은 수용. 이 범위에서 필수 수정 결함을 찾지 못했다. 머지 승인은 아니다.** #3/#4/#5 합본과 실제 원천 응답 확인은 별도 검증이 남아 있다.

## 필수 지적

없음.

## 교차 리뷰 수정 확인

| 항목 | 코드와 테스트 근거 | 판단 |
|---|---|---|
| M1: 실제 step 문서 형식의 라벨 | `parseSteps`는 `sortNo`, `recrutNope`, `aplyNope`, `cmpttRt`만 읽어 전형 정보 순서·모집 인원·지원자 수·경쟁률로 표시한다. 단계명을 만들어내거나 객체 전체 값을 이어 붙이는 폴백은 없다. 객체 원소와 step 래퍼 형식 모두 테스트한다. `parseFiles`는 atchFileNm/url만 읽고 URL 검증을 적용한다. | 결정한 문서 계약 범위 수용. 실제 API 의미·형식까지 검증됐다고 해석하지 않는다. |
| M1: 미확인 형식 | 모르는 객체·문자열 원소·잘못된 JSON을 raw 문자열로 출력하지 않는다. unknownFormat 플래그가 빈 목록/일부 누락 안내로 연결된다. 단위·통합 테스트는 RAW_SECRET_STEP/FILE과 공고 제목·단계 일련번호·날짜 미노출을 확인한다. 출력은 th:text를 유지한다. | 수용. 기존 XSS와 악성 링크 방어를 보존한다. |
| M2: 오늘까지 시작한 최근 3년 | 모든 기관 통계·이력 쿼리는 `[today-3y, today]`를 유지한다. 기관 화면에 오늘까지 기준과 미래 시작 공고 제외 안내가 있다. 내일 시작 공고는 `/jobs`에 표시되면서 기관 통계·이력에는 제외되는 통합 테스트가 추가됐다. | 수용. 3년 경계, 고유 수·모집 인원·비숫자 제외 수·직무 고유 수의 기존 정확한 결과 검증도 유지한다. |
| L1: 404 카운터 | 상세·기관은 조회 성공 후에만 page view를 기록한다. 없는 SN·숫자가 아닌 SN·없는 기관·없는/무효 source 요청에서 카운터 불변을 확인하고, 성공한 상세/기관 및 source 이동은 증가함을 확인한다. | 수용. 카운터를 조회 수로 해석할 때 404가 섞이던 문제가 해소됐다. |
| L2: 운영 외부 주소 | `.env.example`에는 APP_BASE_URL 키 이름만 추가됐다. README는 실제 공개 외부 주소 및 Spring의 .env 자동 미로딩을 설명한다. | 수용. runbook 연결은 예정대로 M3 후속이다. |
| L3: 마감 공고만 있는 빈 결과 | 기본 조회가 빈 경우에만 `hasAnyPosting()`으로 저장 데이터 존재를 확인한다. 마감 공고가 있으면 진행 공고 0건 안내와 전체 공고 링크가 나온다. 통합 테스트는 모든 공고를 CLOSED/과거 마감일로 바꾸고 미수집 문구 부재와 전체 공고 조회를 확인한다. | 수용. 기본 빈 DB와 필터 빈 결과 분기도 유지된다. |

구현 근거: [CatalogRepository](https://github.com/Lcjam/gongchae-bell/blob/4334af809e3d8ce97205e29dcf9a22d28fcddaa6/src/main/java/kr/gongchae/bell/catalog/CatalogRepository.java), [CatalogController](https://github.com/Lcjam/gongchae-bell/blob/4334af809e3d8ce97205e29dcf9a22d28fcddaa6/src/main/java/kr/gongchae/bell/catalog/CatalogController.java), [단위 테스트](https://github.com/Lcjam/gongchae-bell/blob/4334af809e3d8ce97205e29dcf9a22d28fcddaa6/src/test/java/kr/gongchae/bell/catalog/CatalogUnitTest.java), [통합 테스트](https://github.com/Lcjam/gongchae-bell/blob/4334af809e3d8ce97205e29dcf9a22d28fcddaa6/src/test/java/kr/gongchae/bell/catalog/CatalogIntegrationTest.java).

## 권장

- PR 본문의 문서 커밋 `4334af8` CI가 아직 진행 중이라는 문구는 이제 아래 성공 결과로 갱신할 수 있다. 코드 수정 요청은 없다.
- `institution.html`의 미래 공고 설명에서 두 번째 "목록"을 "기관 이력 목록"으로 쓰면 첫 문장의 공고 목록과 구별하기 쉽다. 현재 동작·결정 자체의 결함은 아니다.

## 미검증·후속

- step/file 테스트는 **공식 문서 계약 형태로 만든 fixture**를 DB에 넣는 테스트다. 실제 detail 응답 수신이나 #4 DetailCollector → DB → #5 화면의 합본 경로를 입증하지 않는다. 구현 결과 문서도 이를 구분하고 있다.
- 합본에서 #3 라벨 단락 pref 보존, #4의 step/file 래퍼 제거와 저장, detail 미수집/실패/DONE 구분을 한 경로로 확인해야 한다. 실응답이 문서와 다르면 임의 필드 추측으로 출력을 확대하지 않는다.
- 현재 CatalogIntegrationTest는 고정 Clock과 자체 컨테이너를 유지한다. #3 공용 컨테이너로 변경하지 않았으므로 L5의 종료 정리 추가 조건은 아직 발생하지 않았다. 향후 공용 기반으로 옮길 때 `@AfterEach` 정리와 테스트 격리를 함께 검증한다.
- 브라우저 결과는 agy의 결과 파일을 읽었으며 이번 검수자가 재관측한 것은 아니다. 모바일 JS 구조와 2시간 지연 기준은 결정대로 유지된 것을 확인했다.

## 최종 CI 근거

GitHub connector로 해당 SHA의 PR-triggered 실행과 job을 직접 조회했다. [CI 43656050416](https://github.com/Lcjam/gongchae-bell/actions/runs/43656050416): `test`의 `./gradlew --no-daemon check bootJar` **success**, `secrets`의 Gitleaks **success**. 이 결과는 이전 코드 커밋의 CI를 대신 인용한 것이 아니다.
